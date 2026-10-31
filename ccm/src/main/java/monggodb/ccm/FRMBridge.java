package monggodb.ccm;

import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ChangeRequest;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.TransactionResult;
import monggodb.contracts.shared.TxId;

/**
 * Kontrak internal CC3 (FRM Bridge) untuk menjembatani CCM dengan Failure Recovery Manager (FRM).
 */
public interface FRMBridge {

    /**
     * Membentuk LogRecord dan meminta FRM mencatat perubahan data sekali per operationId.
     *
     * @param txId identitas transaksi pemilik mutasi
     * @param change ringkasan mutasi record beserta operationId
     * @return LSN catatan log mutasi yang dihasilkan FRM
     * @throws CCMException INVALID_REQUEST bila input tidak valid, atau LOG_FAILED bila pencatatan gagal
     * @calls FRM.logChange
     * @calledBy requestWrite
     */
    LSN forwardChangeLog(TxId txId, ChangeRequest change);

    /**
     * Mengoordinasikan satu jalur pembatalan transaksi ke FRM dan baru melepas resource setelah undo berhasil.
     *
     * @param txId identitas transaksi yang dibatalkan
     * @param reason alasan pembatalan transaksi
     * @return hasil akhir pembatalan transaksi
     * @throws CCMException TX_UNKNOWN, INVALID_REQUEST, atau ROLLBACK_FAILED bila undo gagal
     * @calls FRM.rollback, releaseResources
     * @calledBy abortTransaction
     */
    TransactionResult rollbackTransaction(TxId txId, AbortReason reason);

    /**
     * Membersihkan state runtime CCM dan menginisialisasi counter TxId di atas maxTxId hasil pemulihan.
     * <p>
     * Catatan kontrak (Metode Internal No. 13): PIC implementasi asli metode ini dipegang oleh
     * CC4 dan CC1; metode ini disertakan di sini sebagai stub mock Milestone 1.
     *
     * @param result hasil pemulihan dari FRM.recover()
     * @throws CCMException RECOVERY_FAILED bila hasil pemulihan tidak berhasil
     * @calls resetLockState, resetTimestampState
     * @calledBy initialize
     */
    void resetAfterRecovery(RecoveryResult result);
}

