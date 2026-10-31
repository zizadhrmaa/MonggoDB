package monggodb.ccm.lock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import monggodb.ccm.AccessMode;
import monggodb.ccm.lifecycle.MockAccessDispatcher;
import monggodb.ccm.lifecycle.MockTransactionState;
import monggodb.ccm.timestamp.MockTimestampProtocol;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMConfig;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/** Pengujian mandiri mock protokol penguncian CC2 melalui LockProtocolTest.main atau Maven Surefire. */
public class LockProtocolTest {

    private static int checks;

    public static void main(String[] args) {
        checks = 0;
        acquireDecisionsAndWaitSnapshot();
        completeAccessKeeps2PLLock();
        releaseResourcesAndWakeWaiters();
        recoveryResetAndDetectorStop();
        dispatcherIntegrationWithCC1AndCC4();
        validationErrors();
        System.out.println("PASS: CC2 internal lock mocks, " + checks + " checks");
    }

    /** Entry point untuk Maven Surefire POJO test runner. */
    public void testAll() {
        main(new String[0]);
    }

    private static void acquireDecisionsAndWaitSnapshot() {
        MockLockProtocol lock = new MockLockProtocol();
        TxId t1 = new TxId(1);
        TxId t2 = new TxId(2);
        ResourceId items = new ResourceId("items");

        AccessResponse allowed = lock.acquireLock(t1, items, AccessMode.READ, "req-1");
        check(allowed.decision() == AccessDecision.ALLOWED, "Default keputusan ALLOWED");
        check(allowed.txId().equals(t1) && allowed.requestId().equals("req-1"), "Identitas respons sesuai");
        check(allowed.lsn().equals(LSN.NONE) && allowed.abortReason() == null, "ALLOWED tanpa LSN/abortReason");
        check(lock.isInProgress("req-1"), "Akses ALLOWED tercatat sedang berlangsung");
        check(lock.getHeldResources(t1).contains(items), "Resource tercatat dipegang T1");

        lock.setNextDecision(AccessDecision.WAITING, List.of(t1));
        AccessResponse waiting = lock.acquireLock(t2, items, AccessMode.WRITE, "req-2");
        check(waiting.decision() == AccessDecision.WAITING, "Keputusan WAITING sesuai setter");
        check(waiting.blockedBy().equals(List.of(t1)), "Daftar penghalang T2 berisi T1");
        check(!lock.isInProgress("req-2"), "Permintaan WAITING belum berstatus sedang berlangsung");
        Map<TxId, Set<TxId>> snapshot = lock.snapshotWaitForGraph();
        check(snapshot.get(t2).equals(Set.of(t1)), "Snapshot wait-for graph memuat edge T2 -> T1");

        AccessResponse fallbackAllowed = lock.acquireLock(t1, items, AccessMode.WRITE, "req-3");
        check(fallbackAllowed.decision() == AccessDecision.ALLOWED, "Keputusan kembali ke ALLOWED setelah satu pemanggilan");

        lock.setNextDecision(AccessDecision.ABORTED, List.of());
        AccessResponse aborted = lock.acquireLock(t2, items, AccessMode.WRITE, "req-4");
        check(aborted.decision() == AccessDecision.ABORTED, "Keputusan ABORTED sesuai setter");
        check(aborted.abortReason() == AbortReason.DEADLOCK_VICTIM, "Alasan abort pada lock protocol adalah DEADLOCK_VICTIM");
        check(lock.isMarkedFailed(t2), "Transaksi victim ditandai FAILED");
        check(!lock.snapshotWaitForGraph().containsKey(t2), "Transaksi ABORTED dihapus dari wait-for graph");
    }

    private static void completeAccessKeeps2PLLock() {
        MockLockProtocol lock = new MockLockProtocol();
        TxId t1 = new TxId(10);
        ResourceId orders = new ResourceId("orders");

        lock.acquireLock(t1, orders, AccessMode.WRITE, "req-write");
        check(lock.isInProgress("req-write"), "Akses tercatat in-progress sebelum completeAccess");

        lock.onAccessCompleted(t1, "req-write", true, orders);
        check(!lock.isInProgress("req-write"), "Penanda in-progress dihapus saat onAccessCompleted");
        check(lock.getHeldResources(t1).contains(orders), "Strict 2PL: lock tetap dipegang sampai releaseResources");
        List<String> calls = lock.getCalls();
        check(calls.contains("onAccessCompleted Tx10 req-write true"), "onAccessCompleted tercatat");
        check(calls.get(calls.size() - 1).equals("wakeWaiters orders"), "onAccessCompleted memanggil wakeWaiters");
    }

    private static void releaseResourcesAndWakeWaiters() {
        MockLockProtocol lock = new MockLockProtocol();
        TxId t1 = new TxId(1);
        TxId t2 = new TxId(2);
        ResourceId rA = new ResourceId("table_a");
        ResourceId rB = new ResourceId("table_b");

        lock.acquireLock(t1, rA, AccessMode.READ, "r1");
        lock.acquireLock(t1, rB, AccessMode.WRITE, "r2");
        lock.setNextDecision(AccessDecision.WAITING, List.of(t1));
        lock.acquireLock(t2, rA, AccessMode.WRITE, "r3");

        lock.releaseResources(t1, TransactionStatus.COMMITTED);
        check(lock.getHeldResources(t1).isEmpty(), "Seluruh lock T1 dilepas saat COMMITTED");
        check(!lock.isInProgress("r1") && !lock.isInProgress("r2"), "Penanda akses T1 dibersihkan");
        check(lock.snapshotWaitForGraph().get(t2).isEmpty(), "T1 dihapus dari himpunan penghalang T2");

        List<String> calls = lock.getCalls();
        check(calls.contains("releaseResources Tx1 COMMITTED [table_a, table_b]"), "Urutan pelepasan resource tercatat");
        check(calls.contains("wakeWaiters table_a") && calls.contains("wakeWaiters table_b"),
                "wakeWaiters dipanggil untuk setiap resource milik T1");

        lock.setNextDecision(AccessDecision.ABORTED, List.of());
        lock.acquireLock(t2, rA, AccessMode.WRITE, "r4");
        check(lock.isMarkedFailed(t2), "T2 ditandai FAILED sebelum rollback selesai");
        lock.releaseResources(t2, TransactionStatus.ABORTED);
        check(!lock.isMarkedFailed(t2), "Penanda FAILED T2 dibersihkan setelah ABORTED");
        check(lock.getHeldResources(t2).isEmpty(), "Resource T2 dilepas setelah ABORTED");
    }

    private static void recoveryResetAndDetectorStop() {
        MockLockProtocol lock = new MockLockProtocol();
        TxId t1 = new TxId(5);
        ResourceId users = new ResourceId("users");

        lock.acquireLock(t1, users, AccessMode.WRITE, "req-5");
        lock.stopDetector();
        check(lock.isDetectorStopped(), "Detector berhenti setelah stopDetector");
        lock.stopDetector();
        check(lock.isDetectorStopped(), "stopDetector bersifat idempoten");

        lock.resetLockState();
        check(lock.getHeldResources(t1).isEmpty(), "resetLockState mengosongkan kepemilikan lock");
        check(!lock.isInProgress("req-5"), "resetLockState mengosongkan akses in-progress");
        check(lock.snapshotWaitForGraph().isEmpty(), "resetLockState mengosongkan wait-for graph");
        check(!lock.isDetectorStopped(), "resetLockState mereset status detector");
        check(lock.getCalls().get(lock.getCalls().size() - 1).equals("resetLockState"), "resetLockState tercatat");
    }

    private static void dispatcherIntegrationWithCC1AndCC4() {
        MockTransactionState txState = new MockTransactionState();
        MockLockProtocol lock = new MockLockProtocol();
        MockTimestampProtocol timestamp = new MockTimestampProtocol();
        MockAccessDispatcher dispatcher = new MockAccessDispatcher(
                txState,
                new CCMConfig(ProtocolType.LOCK_BASED, 1000, 5000),
                lock::acquireLock,
                timestamp::validateTimestamp
        );

        TxId t1 = txState.nextTxId();
        txState.setTransactionStatus(t1, TransactionStatus.ACTIVE);
        ResourceId accounts = new ResourceId("accounts");

        AccessResponse res = dispatcher.dispatchAccess(t1, accounts, AccessMode.WRITE, "disp-1");
        check(res.decision() == AccessDecision.ALLOWED, "MockAccessDispatcher meneruskan LOCK_BASED ke acquireLock CC2");
        check(lock.isInProgress("disp-1"), "acquireLock CC2 mencatat akses dari dispatcher");
        check(timestamp.getCalls().isEmpty(), "Handler timestamp tidak dipanggil saat protokol LOCK_BASED");
    }

    private static void validationErrors() {
        MockLockProtocol lock = new MockLockProtocol();
        TxId t1 = new TxId(1);
        ResourceId items = new ResourceId("items");

        expect(CCMException.Code.INVALID_REQUEST, () -> lock.setNextDecision(null, List.of()));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.acquireLock(null, items, AccessMode.READ, "r1"));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.acquireLock(t1, null, AccessMode.READ, "r1"));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.acquireLock(t1, items, null, "r1"));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.acquireLock(t1, items, AccessMode.READ, " "));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.releaseResources(null, TransactionStatus.COMMITTED));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.releaseResources(t1, null));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.releaseResources(t1, TransactionStatus.ACTIVE));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.releaseResources(t1, TransactionStatus.FAILED));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.wakeWaiters(null));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.onAccessCompleted(null, "r1", true, items));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.onAccessCompleted(t1, "", true, items));
        expect(CCMException.Code.INVALID_REQUEST, () -> lock.onAccessCompleted(t1, "r1", true, null));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    private static void expect(CCMException.Code code, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected " + code);
        } catch (CCMException e) {
            check(e.getErrorCode() == code, "Expected " + code + ", got " + e.getErrorCode());
        }
    }
}
