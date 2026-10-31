package monggodb.ccm.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import monggodb.contracts.ccm.mock.MockConcurrencyControlManager;
import monggodb.contracts.frm.mock.MockFailureRecoveryManager;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMConfig;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ChangeRequest;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.shared.Value;

/** Driver uji MockConcurrencyControlManager sesuai pembagian mock CC1–CC4 */
public class MockCCMDriver {

    private interface Check {
        void run() throws Exception;
    }

    private final List<String> rows = new ArrayList<>();
    private int passed;

    private MockFailureRecoveryManager frm;
    private MockConcurrencyControlManager ccm;

    public static void main(String[] args) {
        MockCCMDriver d = new MockCCMDriver();
        d.runAll();
        System.out.println("| PIC | Pengecekan | Hasil | Keterangan |");
        System.out.println("|---|---|---|---|");
        d.rows.forEach(System.out::println);
        System.out.println();
        System.out.println(d.passed + " dari " + d.rows.size() + " pengecekan sesuai.");
    }

    private void runAll() {
        // CC1: kerangka dan siklus hidup
        check("CC1", "BEGIN sebelum initialize ditolak NOT_READY", false,
                () -> expectCode(CCMException.Code.NOT_READY, ccm::beginTransaction));
        check("CC1", "TxId naik; commit dan abort idempoten", true, () -> {
            TxId t1 = ccm.beginTransaction(), t2 = ccm.beginTransaction();
            expect(t2.compareTo(t1) > 0, "TxId tidak naik");
            expect(ccm.commitTransaction(t1).equals(ccm.commitTransaction(t1)), "commit ulang berbeda");
            expect(ccm.abortTransaction(t2, AbortReason.USER_REQUEST)
                    .equals(ccm.abortTransaction(t2, AbortReason.USER_REQUEST)), "abort ulang berbeda");
            expect(frm.readLog(t1).stream().filter(r -> r.type() == LogType.COMMIT).count() == 1, "COMMIT tercatat ganda");
        });
        check("CC1", "admission memakai hitungan", true, () -> {
            ccm.blockNewTransactions();
            ccm.blockNewTransactions();
            ccm.resumeTransactions();
            expectCode(CCMException.Code.ADMISSION_PAUSED, ccm::beginTransaction);
            ccm.resumeTransactions();
            ccm.beginTransaction();
            expectCode(CCMException.Code.INVALID_STATE, ccm::resumeTransactions);
        });
        check("CC1", "setProtocol ditolak PROTOCOL_BUSY saat ada transaksi", true, () -> {
            ccm.beginTransaction();
            expectCode(CCMException.Code.PROTOCOL_BUSY, () -> ccm.setProtocol(ProtocolType.TIMESTAMP_BASED));
        });
        check("CC1", "shutdown idempoten, BEGIN ditolak ADMISSION_PAUSED", true, () -> {
            ccm.shutdown();
            ccm.shutdown();
            expectCode(CCMException.Code.ADMISSION_PAUSED, ccm::beginTransaction);
        });

        // CC4: requestRead dan waitForAccess
        check("CC4", "READ ALLOWED tanpa LSN dan tanpa log", true, () -> {
            TxId t = ccm.beginTransaction();
            AccessResponse r = ccm.requestRead(t, res("A"));
            ccm.completeAccess(t, r.requestId(), true);
            expect(r.decision() == AccessDecision.ALLOWED && LSN.NONE.equals(r.lsn()), "respons: " + r);
            expect(frm.readLog(t).size() == 1, "READ membuat log");
        });
        check("CC4", "WAITING dengan blockedBy, resolveAccess membangunkan waitForAccess", true, () -> {
            TxId t1 = ccm.beginTransaction(), t2 = ccm.beginTransaction();
            ccm.setNextAccessDecision(AccessDecision.WAITING, null, List.of(t1));
            AccessResponse w = ccm.requestRead(t2, res("A"));
            expect(w.blockedBy().equals(List.of(t1)), "blockedBy: " + w.blockedBy());
            var woken = CompletableFuture.supplyAsync(() -> ccm.waitForAccess(t2, w.requestId(), 2000));
            Thread.sleep(100);
            ccm.resolveAccess(t2, w.requestId(), AccessDecision.ALLOWED, null);
            expect(woken.get(2, TimeUnit.SECONDS).decision() == AccessDecision.ALLOWED, "waitForAccess tidak bangun");
        });
        check("CC4", "ABORTED TIMESTAMP_VIOLATION lalu otomatis di-abort", true, () -> {
            TxId t = ccm.beginTransaction();
            ccm.setNextAccessDecision(AccessDecision.ABORTED, AbortReason.TIMESTAMP_VIOLATION, null);
            expect(ccm.requestRead(t, res("A")).abortReason() == AbortReason.TIMESTAMP_VIOLATION, "alasan hilang");
            eventually(() -> ccm.getTransactionStatus(t) == TransactionStatus.ABORTED,
                    () -> "status tetap " + ccm.getTransactionStatus(t));
        });

        // CC2: requestWrite dan completeAccess
        check("CC2", "WRITE ALLOWED membawa LSN, operationId sama tidak dicatat ulang", true, () -> {
            TxId t = ccm.beginTransaction();
            ChangeRequest c = change("A", 5);
            AccessResponse r = ccm.requestWrite(t, res("A"), c);
            expect(!LSN.NONE.equals(r.lsn()), "LSN kosong");
            expect(ccm.requestWrite(t, res("A"), c).requestId().equals(r.requestId()), "requestId berbeda");
            expect(frm.readLog(t).stream().filter(x -> x.type() == LogType.UPDATE).count() == 1, "log ganda");
        });
        check("CC2", "commit ditolak INVALID_STATE selama akses belum ditutup", true, () -> {
            TxId t = ccm.beginTransaction();
            ccm.requestRead(t, res("A"));
            expectCode(CCMException.Code.INVALID_STATE, () -> ccm.commitTransaction(t));
        });
        check("CC2", "completeAccess(false) membatalkan transaksi dengan ACCESS_FAILED", true, () -> {
            TxId t = ccm.beginTransaction();
            ccm.completeAccess(t, write(t, "A", 5).requestId(), false);
            expect(ccm.getTransactionStatus(t) == TransactionStatus.ABORTED, "status: " + ccm.getTransactionStatus(t));
            expect(ccm.abortTransaction(t, AbortReason.USER_REQUEST).abortReason() == AbortReason.ACCESS_FAILED,
                    "alasan bukan ACCESS_FAILED");
        });

        // CC3: simulasi kegagalan FRM
        check("CC3", "log BEGIN gagal LOG_FAILED, BEGIN berikutnya normal", true, () -> {
            ccm.failNextLog();
            expectCode(CCMException.Code.LOG_FAILED, ccm::beginTransaction);
            ccm.beginTransaction();
        });
        check("CC3", "log COMMIT gagal lalu transaksi di-abort", true, () -> {
            TxId t = ccm.beginTransaction();
            ccm.completeAccess(t, write(t, "A", 5).requestId(), true);
            ccm.failNextLog();
            expectCode(CCMException.Code.LOG_FAILED, () -> ccm.commitTransaction(t));
            eventually(() -> ccm.getTransactionStatus(t) == TransactionStatus.ABORTED,
                    () -> "status tetap " + ccm.getTransactionStatus(t));
        });
        check("CC3", "rollback tertunda (FAILED selama undo) dan ROLLBACK_FAILED", true, () -> {
            TxId t1 = ccm.beginTransaction(), t2 = ccm.beginTransaction();
            ccm.delayRollback(300);
            var abort = CompletableFuture.supplyAsync(() -> ccm.abortTransaction(t1, AbortReason.USER_REQUEST));
            Thread.sleep(100);
            expect(ccm.getTransactionStatus(t1) == TransactionStatus.FAILED, "bukan FAILED selama undo");
            expect(abort.get(2, TimeUnit.SECONDS).status() == TransactionStatus.ABORTED, "abort tidak selesai");
            ccm.failRollback();
            expectCode(CCMException.Code.ROLLBACK_FAILED, () -> ccm.abortTransaction(t2, AbortReason.USER_REQUEST));
            expect(ccm.getTransactionStatus(t2) == TransactionStatus.FAILED, "bukan FAILED setelah ROLLBACK_FAILED");
        });
        check("CC3", "initialize gagal RECOVERY_FAILED, BEGIN NOT_READY, initialize ulang pulih", false, () -> {
            ccm.failRecovery();
            expectCode(CCMException.Code.RECOVERY_FAILED, ccm::initialize);
            expectCode(CCMException.Code.NOT_READY, ccm::beginTransaction);
            ccm.initialize();
            ccm.beginTransaction();
        });
    }

    // ---- Helper ----

    private void check(String pic, String name, boolean initialize, Check body) {
        frm = new MockFailureRecoveryManager();
        ccm = new MockConcurrencyControlManager(new CCMConfig(), frm);
        String detail = "";
        try {
            if (initialize) {
                ccm.initialize();
            }
            body.run();
            passed++;
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null && !(e instanceof AssertionError) ? e.getCause() : e;
            detail = cause instanceof AssertionError ? cause.getMessage() : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        rows.add("| " + pic + " | " + name + " | " + (detail.isEmpty() ? "SESUAI" : "TIDAK SESUAI") + " | " + detail + " |");
    }

    private static ResourceId res(String item) {
        return new ResourceId(item);
    }

    /** ChangeRequest UPDATE dengan RID tiruan per item */
    private static ChangeRequest change(String item, int value) {
        RID rid = new RID(0, item.charAt(0) - 'A');
        return new ChangeRequest("drv-" + item + "-" + value, LogType.UPDATE, item, rid,
                new Row(rid, List.of(Value.ofNull(DataType.INTEGER))), new Row(rid, List.of(Value.ofInteger(value))));
    }

    private AccessResponse write(TxId t, String item, int value) {
        return ccm.requestWrite(t, res(item), change(item, value));
    }

    private static void expect(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static void expectCode(CCMException.Code code, Runnable action) {
        try {
            action.run();
        } catch (CCMException e) {
            expect(e.getErrorCode() == code, "kode " + e.getErrorCode() + ", seharusnya " + code);
            return;
        }
        throw new AssertionError("tidak melempar " + code);
    }

    /** Menunggu hingga 1 detik untuk perilaku yang seharusnya terjadi otomatis */
    private static void eventually(BooleanSupplier condition, java.util.function.Supplier<String> message)
            throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) {
            Thread.sleep(20);
        }
        expect(condition.getAsBoolean(), message.get());
    }
}
