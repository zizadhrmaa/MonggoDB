package monggodb.ccm.lifecycle;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import monggodb.ccm.AccessMode;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMConfig;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/** Pengujian mandiri mock lifecycle CC1 melalui LifecycleTest.main, tanpa dependency test tambahan. */
public final class LifecycleTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        allocation();
        seededAllocation();
        transitions();
        delegation();
        dispatchStateChange();
        recoveryAndAdmission();
        System.out.println("PASS: CC1 internal mocks, " + checks + " checks");
    }

    private static void allocation() throws Exception {
        MockTransactionState state = new MockTransactionState();
        try (var executor = Executors.newFixedThreadPool(4)) {
            Callable<Set<TxId>> allocate = () -> {
                Set<TxId> ids = new HashSet<>();
                for (int i = 0; i < 100; i++) ids.add(state.nextTxId());
                return ids;
            };
            Set<TxId> all = new HashSet<>();
            var futures = executor.invokeAll(List.of(allocate, allocate, allocate, allocate), 5, TimeUnit.SECONDS);
            for (var future : futures) all.addAll(future.get(1, TimeUnit.SECONDS));
            check(all.size() == 400, "TxId unik saat alokasi bersamaan");
            check(state.nextTxId().value() == 401, "Counter terus meningkat");
            check(state.getStatuses().isEmpty(), "Alokasi tidak membuat BEGIN");
        }
    }

    private static void seededAllocation() {
        MockTransactionState state = new MockTransactionState();
        state.setTransactionStatus(new TxId(1), TransactionStatus.ACTIVE);
        check(state.nextTxId().value() == 2, "TxId fixture tidak dialokasikan ulang");
        state.setTransactionStatus(new TxId(100), TransactionStatus.ACTIVE);
        check(state.nextTxId().value() == 101, "Counter mengikuti fixture terbesar");
        state.setTransactionStatus(new TxId(1), TransactionStatus.FAILED);
        check(state.nextTxId().value() == 102, "Fixture lama tidak menurunkan counter");
        state.setTransactionStatus(new TxId(Long.MAX_VALUE), TransactionStatus.ACTIVE);
        expect(CCMException.Code.INVALID_STATE, state::nextTxId);
    }

    private static void transitions() {
        TxId id = new TxId(1);
        for (TransactionStatus from : TransactionStatus.values()) {
            for (TransactionStatus to : TransactionStatus.values()) {
                MockTransactionState state = new MockTransactionState();
                state.setTransactionStatus(id, from);
                boolean allowed = switch (from) {
                    case ACTIVE -> to == TransactionStatus.PARTIALLY_COMMITTED || to == TransactionStatus.FAILED;
                    case PARTIALLY_COMMITTED -> to == TransactionStatus.COMMITTED || to == TransactionStatus.FAILED;
                    case FAILED -> to == TransactionStatus.ABORTED;
                    case COMMITTED, ABORTED -> false;
                };
                if (allowed) {
                    state.transitionState(id, from, to);
                    check(state.getStatuses().get(id) == to, "Transisi valid");
                } else {
                    expect(CCMException.Code.INVALID_STATE, () -> state.transitionState(id, from, to));
                    check(state.getStatuses().get(id) == from, "Transisi ditolak tidak mengubah state");
                }
                state.setTransactionStatus(id, from);
                if (from == TransactionStatus.ACTIVE) check(state.ensureActive(id) == from, "ACTIVE diterima");
                else expect(CCMException.Code.INVALID_STATE, () -> state.ensureActive(id));
            }
        }
        MockTransactionState state = new MockTransactionState();
        expect(CCMException.Code.TX_UNKNOWN, () -> state.ensureActive(id));
        expect(CCMException.Code.TX_UNKNOWN, () -> state.transitionState(id, TransactionStatus.ACTIVE, TransactionStatus.FAILED));
        state.setTransactionStatus(id, TransactionStatus.ACTIVE);
        expect(CCMException.Code.INVALID_REQUEST, () -> state.transitionState(id, null, TransactionStatus.FAILED));
        expect(CCMException.Code.INVALID_STATE, () -> state.transitionState(id, TransactionStatus.FAILED, TransactionStatus.ABORTED));
    }

    private static void delegation() {
        MockTransactionState state = new MockTransactionState();
        AtomicInteger lockCalls = new AtomicInteger();
        AtomicInteger timestampCalls = new AtomicInteger();
        TxId id = new TxId(1);
        ResourceId resource = new ResourceId("items");
        AccessResponse waiting = new AccessResponse(id, "read", AccessDecision.WAITING,
                LSN.NONE, null, List.of(new TxId(2)), "handler dummy");
        AccessResponse aborted = new AccessResponse(id, "write", AccessDecision.ABORTED,
                LSN.NONE, AbortReason.TIMESTAMP_VIOLATION, List.of(), "handler dummy");
        MockAccessDispatcher.ProtocolHandler lock = (txId, table, mode, requestId) -> {
            lockCalls.incrementAndGet();
            check(txId.equals(id) && table.equals(resource) && mode == AccessMode.READ && requestId.equals("read"), "Input READ diteruskan");
            return waiting;
        };
        MockAccessDispatcher.ProtocolHandler timestamp = (txId, table, mode, requestId) -> {
            timestampCalls.incrementAndGet();
            check(txId.equals(id) && table.equals(resource) && mode == AccessMode.WRITE && requestId.equals("write"), "Input WRITE diteruskan");
            return aborted;
        };
        MockAccessDispatcher lockDispatcher = new MockAccessDispatcher(state, new CCMConfig(), lock, timestamp);
        MockAccessDispatcher timestampDispatcher = new MockAccessDispatcher(state,
                new CCMConfig(ProtocolType.TIMESTAMP_BASED, 1000, 5000), lock, timestamp);
        expect(CCMException.Code.TX_UNKNOWN, () -> lockDispatcher.dispatchAccess(id, resource, AccessMode.READ, "read"));
        state.setTransactionStatus(id, TransactionStatus.ACTIVE);
        check(lockDispatcher.dispatchAccess(id, resource, AccessMode.READ, "read") == waiting, "Respons WAITING tidak diganti CC1");
        check(timestampDispatcher.dispatchAccess(id, resource, AccessMode.WRITE, "write") == aborted, "Respons ABORTED tidak diganti CC1");
        check(state.getStatuses().get(id) == TransactionStatus.ACTIVE, "Dispatch tidak menjalankan abort/rollback");
        check(lockCalls.get() == 1 && timestampCalls.get() == 1, "Hanya handler terpilih dipanggil");
        expect(CCMException.Code.INVALID_REQUEST, () -> lockDispatcher.dispatchAccess(id, null, AccessMode.READ, "read"));
        expect(CCMException.Code.INVALID_REQUEST, () -> lockDispatcher.dispatchAccess(id, resource, null, "read"));
        expect(CCMException.Code.INVALID_REQUEST, () -> lockDispatcher.dispatchAccess(id, resource, AccessMode.READ, " "));
        MockAccessDispatcher invalid = new MockAccessDispatcher(state, new CCMConfig(),
                (txId, table, mode, requestId) -> null, (txId, table, mode, requestId) -> null);
        expect(CCMException.Code.INVALID_STATE, () -> invalid.dispatchAccess(id, resource, AccessMode.READ, "read"));
        state.transitionState(id, TransactionStatus.ACTIVE, TransactionStatus.FAILED);
        expect(CCMException.Code.INVALID_STATE, () -> lockDispatcher.dispatchAccess(id, resource, AccessMode.READ, "read"));
    }

    private static void dispatchStateChange() throws Exception {
        for (AccessDecision decision : List.of(AccessDecision.ALLOWED, AccessDecision.WAITING)) {
            MockTransactionState state = new MockTransactionState();
            TxId id = new TxId(1);
            state.setTransactionStatus(id, TransactionStatus.ACTIVE);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MockAccessDispatcher.ProtocolHandler handler = (txId, resource, mode, requestId) -> {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("Timeout menunggu handler");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return new AccessResponse(txId, requestId, decision, LSN.NONE, null, List.of(), "handler dummy");
            };
            MockAccessDispatcher dispatcher = new MockAccessDispatcher(state, new CCMConfig(), handler, handler);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var pending = executor.submit(() -> expect(CCMException.Code.INVALID_STATE,
                        () -> dispatcher.dispatchAccess(id, new ResourceId("items"), AccessMode.READ, "read")));
                try {
                    check(entered.await(2, TimeUnit.SECONDS), "Handler berjalan sebelum perubahan status");
                    state.transitionState(id, TransactionStatus.ACTIVE, TransactionStatus.FAILED);
                } finally {
                    release.countDown();
                }
                pending.get(2, TimeUnit.SECONDS);
                check(state.getStatuses().get(id) == TransactionStatus.FAILED, "Dispatch tidak mengubah kembali status");
            }
        }

        MockTransactionState state = new MockTransactionState();
        TxId id = new TxId(1);
        state.setTransactionStatus(id, TransactionStatus.ACTIVE);
        MockAccessDispatcher.ProtocolHandler aborted = (txId, resource, mode, requestId) -> {
            state.transitionState(txId, TransactionStatus.ACTIVE, TransactionStatus.FAILED);
            return new AccessResponse(txId, requestId, AccessDecision.ABORTED, LSN.NONE,
                    AbortReason.TIMESTAMP_VIOLATION, List.of(), "handler menandai FAILED");
        };
        AccessResponse response = new MockAccessDispatcher(state, new CCMConfig(), aborted, aborted)
                .dispatchAccess(id, new ResourceId("items"), AccessMode.READ, "abort");
        check(response.decision() == AccessDecision.ABORTED, "Respons ABORTED dari handler tetap diteruskan");
        check(response.abortReason() == AbortReason.TIMESTAMP_VIOLATION, "Alasan abort tetap dipertahankan");
    }

    private static void recoveryAndAdmission() {
        MockTransactionState state = new MockTransactionState();
        MockAdmissionState admission = new MockAdmissionState();
        check(admission.getAdmissionCounter() == 0, "Admission awal terbuka");
        admission.setAdmissionCounter(2);
        expect(CCMException.Code.INVALID_REQUEST, () -> admission.setAdmissionCounter(-1));
        state.setTransactionStatus(new TxId(1), TransactionStatus.FAILED);
        RecoveryResult failed = new RecoveryResult(false, new TxId(100), LSN.NONE, List.of(), List.of(), "gagal");
        expect(CCMException.Code.RECOVERY_FAILED, () -> state.resetAfterRecovery(failed));
        check(state.getStatuses().size() == 1, "Recovery gagal tidak menghapus state");
        expect(CCMException.Code.INVALID_REQUEST, () -> state.resetAfterRecovery(null));
        state.resetAfterRecovery(new RecoveryResult(true, new TxId(100), LSN.NONE, List.of(), List.of(), "pulih"));
        check(state.getStatuses().isEmpty(), "State CC1 dibersihkan");
        check(state.nextTxId().value() == 101, "ID berikutnya di atas maxTxId");
        check(admission.getAdmissionCounter() == 2, "Recovery mempertahankan blok admission");
        state.resetAfterRecovery(new RecoveryResult(true, null, LSN.NONE, List.of(), List.of(), "kosong"));
        check(state.nextTxId().value() == 102, "Recovery tidak memakai ulang ID");
        state.resetAfterRecovery(new RecoveryResult(true, new TxId(Long.MAX_VALUE), LSN.NONE, List.of(), List.of(), "batas"));
        expect(CCMException.Code.INVALID_STATE, state::nextTxId);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
