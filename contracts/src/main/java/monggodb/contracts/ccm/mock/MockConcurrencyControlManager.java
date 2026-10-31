package monggodb.contracts.ccm.mock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import monggodb.contracts.ccm.ConcurrencyControlManager;
import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.frm.mock.MockFailureRecoveryManager;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMConfig;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ChangeRequest;
import monggodb.contracts.shared.FRMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.RollbackResult;
import monggodb.contracts.shared.TransactionResult;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/**
 * Mock CCM memakai shared type dan state di memori, masih tanpa protokol atau undo.
 */
public class MockConcurrencyControlManager implements ConcurrencyControlManager {

    private final CCMConfig config;
    private final FailureRecoveryManager frm;
    private final Map<TxId, TransactionStatus> statuses = new HashMap<>();
    private final Map<TxId, AbortReason> abortReasons = new HashMap<>();
    private final Map<TxId, TransactionResult> results = new HashMap<>();
    private final Map<TxId, CCMException> rollbackErrors = new HashMap<>();
    private final Map<String, AccessResponse> requests = new HashMap<>();
    private final Map<String, ChangeRequest> changes = new HashMap<>();
    private final Map<TxId, Map<String, String>> writeRequests = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();
    private final Set<TxId> rollingBack = new HashSet<>();
    private ProtocolType protocol;
    private long lastTxId;
    private long nextRequestId = 1;
    private int admissionCounter;
    private boolean initialized;
    private boolean stopped;
    private AccessDecision nextDecision = AccessDecision.ALLOWED;
    private AbortReason nextAbortReason;
    private List<TxId> nextBlockedBy = List.of();
    private boolean failNextLog;
    private boolean failRecovery;
    private boolean failRollback;
    private long rollbackDelayMs;

    public MockConcurrencyControlManager() {
        this(new CCMConfig());
    }

    public MockConcurrencyControlManager(CCMConfig config) {
        this(config, new MockFailureRecoveryManager());
    }

    /** @param frm FRM yang sudah disiapkan; MockFailureRecoveryManager tidak meng-undo data SM */
    public MockConcurrencyControlManager(CCMConfig config, FailureRecoveryManager frm) {
        if (config == null || frm == null) throw error(CCMException.Code.INVALID_REQUEST, "config dan frm wajib diisi");
        this.config = config;
        this.frm = frm;
        protocol = config.protocol();
    }

    /** Kontrol simulasi untuk driver */

    /** Mengatur keputusan satu permintaan baru; ABORTED memerlukan overload dengan alasan. */
    public synchronized void setNextAccessDecision(AccessDecision decision) {
        setNextAccessDecision(decision, null, List.of());
    }

    public synchronized void setNextAccessDecision(AccessDecision decision, AbortReason reason, List<TxId> blockedBy) {
        if (decision == null || (decision == AccessDecision.ABORTED && reason == null)
                || (blockedBy != null && blockedBy.stream().anyMatch(id -> id == null))) {
            throw error(CCMException.Code.INVALID_REQUEST, "Keputusan akses tidak valid");
        }
        nextDecision = decision;
        nextAbortReason = reason;
        nextBlockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }

    /** Driver menyelesaikan WAITING secara manual, bukan lewat protokol nyata. Kalau misal ada yang sudah mengimpleentasikan dari sebelah, boleh diganti ya. */
    public synchronized void resolveAccess(TxId txId, String requestId, AccessDecision decision, AbortReason reason) {
        AccessResponse response = request(txId, requestId);
        ensureActive(txId);
        if (response.decision() != AccessDecision.WAITING
                || (decision != AccessDecision.ALLOWED && decision != AccessDecision.ABORTED)
                || (decision == AccessDecision.ABORTED && reason == null)) {
            throw error(CCMException.Code.INVALID_REQUEST, "Resolusi akses tidak valid");
        }
        applyDecision(txId, requestId, decision, reason, List.of());
    }

    /** Hook CC3: menggagalkan pencatatan log berikutnya. */
    public synchronized void failNextLog() {
        failNextLog = true;
    }

    public synchronized void failRecovery() {
        failRecovery = true;
    }

    public synchronized void delayRollback(long delayMs) {
        timeoutNanos(delayMs);
        rollbackDelayMs = delayMs;
    }

    public synchronized void failRollback() {
        failRollback = true;
    }

    public synchronized List<LogRecord> getLogRecords() {
        return List.copyOf(frm.readLog(null));
    }

    /** Utils */

    private TransactionStatus status(TxId txId) {
        TransactionStatus status = statuses.get(txId);
        if (status == null) throw error(CCMException.Code.TX_UNKNOWN, "Transaksi tidak dikenal: " + txId);
        return status;
    }

    private AccessResponse request(TxId txId, String requestId) {
        status(txId);
        AccessResponse response = requests.get(requestId);
        if (response == null || !response.txId().equals(txId)) {
            throw error(CCMException.Code.INVALID_REQUEST, "requestId tidak dikenal untuk transaksi ini");
        }
        return response;
    }

    private void ensureActive(TxId txId) {
        if (status(txId) != TransactionStatus.ACTIVE) throw error(CCMException.Code.INVALID_STATE, "Transaksi tidak ACTIVE");
    }

    private TransactionResult result(TxId txId, boolean success, String message) {
        return new TransactionResult(txId, success, status(txId), abortReasons.get(txId), message);
    }

    private boolean hasAccess(TxId txId) {
        return inProgress.stream().anyMatch(id -> requests.get(id).txId().equals(txId));
    }

    private boolean isIdle() {
        return rollingBack.isEmpty() && inProgress.isEmpty() && statuses.values().stream().allMatch(
                status -> status == TransactionStatus.COMMITTED || status == TransactionStatus.ABORTED);
    }

    private void resetNextDecision() {
        nextDecision = AccessDecision.ALLOWED;
        nextAbortReason = null;
        nextBlockedBy = List.of();
    }

    private void checkLogFailure() {
        if (failNextLog) {
            failNextLog = false;
            throw new FRMException(FRMException.Code.LOG_WRITE_FAILED, "Log gagal (simulasi mock)");
        }
    }

    private long timeoutNanos(long timeoutMs) {
        if (timeoutMs < 0) throw error(CCMException.Code.INVALID_REQUEST, "Waktu tunggu tidak boleh negatif");
        return TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    private void await(long nanos) {
        try {
            TimeUnit.NANOSECONDS.timedWait(this, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CCMException(CCMException.Code.INVALID_STATE, "Penantian terinterupsi", e);
        }
    }

    private static CCMException error(CCMException.Code code, String message) {
        return new CCMException(code, message);
    }

    private AccessResponse response(TxId txId, String requestId, AccessDecision decision, LSN lsn, List<TxId> blockedBy) {
        return new AccessResponse(txId, requestId, decision, lsn,
                decision == AccessDecision.ABORTED ? abortReasons.get(txId) : null, blockedBy,
                decision == AccessDecision.ABORTED ? "Transaksi dibatalkan; undo mungkin belum selesai" : "Akses " + decision);
    }

    private void markFailed(TxId txId, AbortReason reason) {
        statuses.put(txId, TransactionStatus.FAILED);
        abortReasons.putIfAbsent(txId, reason);
        requests.replaceAll((id, current) -> current.txId().equals(txId)
                ? response(txId, id, AccessDecision.ABORTED, LSN.NONE, List.of()) : current);
        notifyAll();
    }

    private AccessResponse newRequest(TxId txId, ResourceId resource, ChangeRequest change) {
        if (resource == null) throw error(CCMException.Code.INVALID_REQUEST, "resource wajib diisi");
        if (status(txId) != TransactionStatus.ABORTED) ensureActive(txId);
        String requestId = "mock-" + nextRequestId++;
        requests.put(requestId, response(txId, requestId, AccessDecision.WAITING, LSN.NONE, List.of()));
        if (change != null) {
            changes.put(requestId, change);
            writeRequests.computeIfAbsent(txId, key -> new HashMap<>()).put(change.operationId(), requestId);
        }
        if (status(txId) == TransactionStatus.ABORTED) {
            requests.put(requestId, response(txId, requestId, AccessDecision.ABORTED, LSN.NONE, List.of()));
        } else {
            AccessDecision decision = nextDecision;
            AbortReason reason = nextAbortReason;
            List<TxId> blockedBy = nextBlockedBy;
            resetNextDecision();
            applyDecision(txId, requestId, decision, reason, blockedBy);
        }
        return requests.get(requestId);
    }

    private void applyDecision(TxId txId, String requestId, AccessDecision decision, AbortReason reason, List<TxId> blockedBy) {
        if (decision == AccessDecision.ABORTED) {
            markFailed(txId, reason);
            startAsyncAbort(txId, reason);
        } else {
            LSN lsn = LSN.NONE;
            ChangeRequest change = changes.get(requestId);
            if (decision == AccessDecision.ALLOWED && change != null) {
                try {
                    lsn = forwardChangeLog(txId, change);
                } catch (CCMException e) {
                    markFailed(txId, AbortReason.LOG_FAILED);
                    startAsyncAbort(txId, AbortReason.LOG_FAILED);
                    throw e;
                }
            }
            if (decision == AccessDecision.ALLOWED) inProgress.add(requestId);
            requests.put(requestId, response(txId, requestId, decision, lsn, blockedBy));
        }
        notifyAll();
    }

    /** CC3: membentuk LogRecord dan meminta FRM mencatat perubahan data sekali per operationId. */
    public synchronized LSN forwardChangeLog(TxId txId, ChangeRequest change) {
        status(txId);
        if (change == null) throw error(CCMException.Code.INVALID_REQUEST, "change wajib diisi");
        try {
            checkLogFailure();
            return frm.logChange(new LogRecord(LSN.NONE, txId, change.type(), change.table(), change.rid(),
                    change.before(), change.after(), 0L, LSN.NONE));
        } catch (FRMException e) {
            throw new CCMException(CCMException.Code.LOG_FAILED, "Log perubahan gagal; transaksi FAILED", e);
        }
    }

    /** CC3: mengoordinasikan satu jalur pembatalan transaksi ke FRM dan melepas resource setelah undo berhasil. */
    public synchronized TransactionResult rollbackTransaction(TxId txId, AbortReason reason) {
        status(txId);
        if (reason == null) throw error(CCMException.Code.INVALID_REQUEST, "Alasan abort wajib diisi");
        while (rollingBack.contains(txId)) await(TimeUnit.SECONDS.toNanos(1));
        if (status(txId) == TransactionStatus.COMMITTED) return result(txId, false, "Transaksi sudah committed");
        if (status(txId) == TransactionStatus.ABORTED) return results.get(txId);
        if (rollbackErrors.containsKey(txId)) throw rollbackErrors.get(txId);
        markFailed(txId, reason);
        rollingBack.add(txId);
        long delay = rollbackDelayMs;
        boolean failure = failRollback;
        rollbackDelayMs = 0;
        failRollback = false;
        try {
            while (hasAccess(txId)) await(TimeUnit.SECONDS.toNanos(1));
            long start = System.nanoTime();
            long remaining = timeoutNanos(delay);
            while (remaining > 0) {
                await(remaining);
                remaining = timeoutNanos(delay) - (System.nanoTime() - start);
            }
            if (failure) throw error(CCMException.Code.ROLLBACK_FAILED, "Rollback gagal (simulasi mock)");
            RollbackResult undo = frm.rollback(txId);
            if (undo == null || (undo.status() != RollbackResult.Status.DONE && undo.status() != RollbackResult.Status.ALREADY_ABORTED)) {
                throw error(CCMException.Code.ROLLBACK_FAILED, "FRM belum mengonfirmasi undo selesai");
            }
            statuses.put(txId, TransactionStatus.ABORTED);
            results.put(txId, result(txId, true, "Transaksi sudah dibatalkan"));
            return results.get(txId);
        } catch (RuntimeException e) {
            CCMException failureResult = new CCMException(CCMException.Code.ROLLBACK_FAILED, "Rollback gagal; status tetap FAILED", e);
            rollbackErrors.put(txId, failureResult);
            throw failureResult;
        } finally {
            rollingBack.remove(txId);
            notifyAll();
        }
    }

    /** CC3: membersihkan state runtime CCM dan menginisialisasi counter TxId di atas maxTxId hasil pemulihan. */
    public synchronized void resetAfterRecovery(RecoveryResult result) {
        if (result == null || !result.success()) throw error(CCMException.Code.RECOVERY_FAILED, "Recovery tidak berhasil");
        if (result.maxTxId() != null) lastTxId = Math.max(lastTxId, result.maxTxId().value());
        statuses.clear();
        abortReasons.clear();
        results.clear();
        rollbackErrors.clear();
        requests.clear();
        changes.clear();
        writeRequests.clear();
        protocol = config.protocol();
        resetNextDecision();
    }

    private void startAsyncAbort(TxId txId, AbortReason reason) {
        CompletableFuture.runAsync(() -> {
            try {
                rollbackTransaction(txId, reason);
            } catch (RuntimeException ignored) {
                // Status transaksi tetap FAILED jika undo gagal
            }
        });
    }

    /** Implementasi mock untuk ConcurrencyControlManager.
     *  Komentar pada fungsi berikut menunjukkan siapa yang bertanggung jawab melengkapi fungsi tersebut.
     *  Jadi, boleh dilengkapin dan dicek ya ^^
     */

    /** CC1: startup/recovery; hook kegagalan dilanjutkan CC3. */
    @Override
    public synchronized RecoveryResult initialize() {
        if (stopped || !isIdle()) throw error(CCMException.Code.INVALID_STATE, "CCM sudah shutdown atau masih sibuk");
        initialized = false;
        try {
            if (failRecovery) {
                failRecovery = false;
                throw error(CCMException.Code.RECOVERY_FAILED, "Recovery gagal (simulasi mock)");
            }
            RecoveryResult result = frm.recover();
            resetAfterRecovery(result);
            initialized = true;
            notifyAll();
            return result;
        } catch (FRMException e) {
            throw new CCMException(CCMException.Code.RECOVERY_FAILED, "Recovery CCM gagal", e);
        }
    }

    /** CC1: memulai transaksi; hook log gagal dilanjutkan CC3. */
    @Override
    public synchronized TxId beginTransaction() {
        if (stopped) throw error(CCMException.Code.ADMISSION_PAUSED, "CCM sudah shutdown");
        if (!initialized) throw error(CCMException.Code.NOT_READY, "Recovery belum berhasil");
        if (admissionCounter > 0) throw error(CCMException.Code.ADMISSION_PAUSED, "Admission sedang ditutup");
        if (lastTxId == Long.MAX_VALUE) throw error(CCMException.Code.INVALID_STATE, "TxId sudah habis");
        TxId txId = new TxId(++lastTxId);
        try {
            checkLogFailure();
            frm.logBegin(txId);
        } catch (FRMException e) {
            throw new CCMException(CCMException.Code.LOG_FAILED, "Log BEGIN gagal", e);
        }
        statuses.put(txId, TransactionStatus.ACTIVE);
        return txId;
    }

    /** CC1: membaca status transaksi. */
    @Override
    public synchronized TransactionStatus getTransactionStatus(TxId txId) {
        return status(txId);
    }

    /** CC1: commit transaksi; hook log gagal dilanjutkan CC3. */
    @Override
    public synchronized TransactionResult commitTransaction(TxId txId) {
        TransactionStatus status = status(txId);
        if (status == TransactionStatus.COMMITTED) return results.get(txId);
        if (status == TransactionStatus.ABORTED || status == TransactionStatus.FAILED) {
            return result(txId, false, "Transaksi sudah dibatalkan atau gagal");
        }
        ensureActive(txId);
        if (hasAccess(txId) || requests.values().stream().anyMatch(r -> r.txId().equals(txId) && r.decision() == AccessDecision.WAITING)) {
            throw error(CCMException.Code.INVALID_STATE, "Masih ada akses yang belum selesai");
        }
        statuses.put(txId, TransactionStatus.PARTIALLY_COMMITTED);
        try {
            checkLogFailure();
            frm.logCommit(txId);
        } catch (FRMException e) {
            markFailed(txId, AbortReason.LOG_FAILED);
            startAsyncAbort(txId, AbortReason.LOG_FAILED);
            throw new CCMException(CCMException.Code.LOG_FAILED, "Log COMMIT gagal; transaksi FAILED", e);
        }
        statuses.put(txId, TransactionStatus.COMMITTED);
        results.put(txId, result(txId, true, "Transaksi sudah committed"));
        notifyAll();
        return results.get(txId);
    }

    /** CC1: abort idempoten; simulasi rollback dilanjutkan CC3. */
    @Override
    public synchronized TransactionResult abortTransaction(TxId txId, AbortReason reason) {
        return rollbackTransaction(txId, reason);
    }

    /** CC1: membaca protokol aktif. */
    @Override
    public synchronized ProtocolType getProtocol() {
        return protocol;
    }

    /** CC1: mengganti protokol saat tidak sibuk. */
    @Override
    public synchronized void setProtocol(ProtocolType protocol) {
        if (protocol == null) throw error(CCMException.Code.INVALID_REQUEST, "Protokol wajib diisi");
        if (!isIdle()) throw error(CCMException.Code.PROTOCOL_BUSY, "Transaksi atau akses belum selesai");
        this.protocol = protocol;
    }

    /** CC1: menaikkan hitungan penutupan admission. */
    @Override
    public synchronized void blockNewTransactions() {
        if (admissionCounter == Integer.MAX_VALUE) throw error(CCMException.Code.INVALID_STATE, "Hitungan admission penuh");
        admissionCounter++;
    }

    /** CC1: menunggu transaksi, akses, dan rollback selesai. */
    @Override
    public synchronized boolean waitUntilNoActive(long timeoutMs) {
        long remaining = timeoutNanos(timeoutMs);
        long start = System.nanoTime();
        while (!isIdle() && remaining > 0) {
            await(remaining);
            remaining = timeoutNanos(timeoutMs) - (System.nanoTime() - start);
        }
        return isIdle();
    }

    /** CC1: menurunkan hitungan penutupan admission. */
    @Override
    public synchronized void resumeTransactions() {
        if (admissionCounter == 0) throw error(CCMException.Code.INVALID_STATE, "Tidak ada blok admission untuk dibuka");
        admissionCounter--;
    }

    /**
     * CC1: shutdown idempoten, menunggu rollback maksimal 5000 ms.
     * Mock tidak memiliki detector.
     */
    @Override
    public synchronized void shutdown() {
        stopped = true;
        long start = System.nanoTime();
        long remaining = timeoutNanos(5000);
        while (!rollingBack.isEmpty() && remaining > 0) {
            await(remaining);
            remaining = timeoutNanos(5000) - (System.nanoTime() - start);
        }
        if (!rollingBack.isEmpty()) throw error(CCMException.Code.INVALID_STATE, "Timeout menunggu rollback saat shutdown");
    }

    // TODO CC4: lanjutkan skenario read/wait; keputusan saat ini diatur setter, tanpa protokol nyata.
    /** CC4: meminta izin baca. */
    @Override
    public synchronized AccessResponse requestRead(TxId txId, ResourceId resource) {
        status(txId);
        return newRequest(txId, resource, null);
    }

    /** CC4: menunggu keputusan akses. */
    @Override
    public synchronized AccessResponse waitForAccess(TxId txId, String requestId, long timeoutMs) {
        request(txId, requestId);
        long remaining = timeoutNanos(timeoutMs);
        long start = System.nanoTime();
        while (request(txId, requestId).decision() == AccessDecision.WAITING && remaining > 0) {
            await(remaining);
            remaining = timeoutNanos(timeoutMs) - (System.nanoTime() - start);
        }
        return request(txId, requestId);
    }

    // TODO CC2: lanjutkan skenario write/complete dengan state bersama di atas.
    /** CC2: meminta izin tulis dan LSN; kegagalan log dilanjutkan CC3. */
    @Override
    public synchronized AccessResponse requestWrite(TxId txId, ResourceId resource, ChangeRequest change) {
        status(txId);
        if (change == null || resource == null || !resource.table().equals(change.table())) {
            throw error(CCMException.Code.INVALID_REQUEST, "change dan tabel resource harus valid");
        }
        if (status(txId) != TransactionStatus.ABORTED) ensureActive(txId);
        String previous = writeRequests.getOrDefault(txId, Map.of()).get(change.operationId());
        if (previous != null) {
            if (!changes.get(previous).equals(change)) throw error(CCMException.Code.INVALID_REQUEST, "operationId dipakai dengan isi berbeda");
            return requests.get(previous);
        }
        return newRequest(txId, resource, change);
    }

    /** CC2: menutup akses setelah operasi SM selesai. */
    @Override
    public synchronized void completeAccess(TxId txId, String requestId, boolean success) {
        request(txId, requestId);
        if (!inProgress.remove(requestId)) throw error(CCMException.Code.INVALID_REQUEST, "Akses tidak sedang berlangsung");
        notifyAll();
        if (!success) abortTransaction(txId, AbortReason.ACCESS_FAILED);
    }
}
