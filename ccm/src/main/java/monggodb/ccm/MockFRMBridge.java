package monggodb.ccm;

import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ChangeRequest;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.TransactionResult;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/**
 * Mock internal Milestone 1 untuk {@link FRMBridge} (CC3).
 * Mengembalikan nilai dummy tanpa menjalankan logika implementasi asli.
 */
public class MockFRMBridge implements FRMBridge {

    @Override
    public LSN forwardChangeLog(TxId txId, ChangeRequest change) {
        if (txId == null || change == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "txId dan change forwardChangeLog tidak boleh null");
        }
        return new LSN(1L);
    }

    @Override
    public TransactionResult rollbackTransaction(TxId txId, AbortReason reason) {
        if (txId == null || reason == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "txId dan reason rollbackTransaction tidak boleh null");
        }
        return new TransactionResult(txId, true, TransactionStatus.ABORTED,
                reason, "Mock rollback selesai");
    }

    /**
     * Stub mock untuk inisialisasi state CCM pasca-pemulihan (Metode Internal No. 13).
     * Implementasi asli dipegang oleh PIC CC4 dan CC1.
     *
     * @param result hasil pemulihan dari FRM.recover()
     * @throws CCMException RECOVERY_FAILED bila hasil pemulihan tidak berhasil
     * @calls resetLockState, resetTimestampState
     * @calledBy initialize
     */
    @Override
    public void resetAfterRecovery(RecoveryResult result) {
        if (result == null || !result.success()) {
            throw new CCMException(CCMException.Code.RECOVERY_FAILED,
                    "Mock recovery gagal");
        }
    }
}

