package monggodb.contracts.shared;

/**
 * Hasil commitTransaction atau abortTransaction.
 * success mengikuti penyelesaian aktual, bukan sekadar permintaan diterima.
 * Operasi yang ditolak mengembalikan status aktual tanpa mengubahnya.
 *
 * @param txId transaksi yang diselesaikan
 * @param success true bila operasi commit atau abort yang diminta benar-benar selesai
 * @param status status aktual transaksi
 * @param abortReason alasan pembatalan, wajib bila status ABORTED, boleh diisi saat FAILED
 * @param message penjelasan yang siap ditampilkan
 */
public record TransactionResult(TxId txId, boolean success, TransactionStatus status,
                                AbortReason abortReason, String message) {

    /** Menjaga invarian */
    public TransactionResult {
        if (txId == null || status == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "txId dan status TransactionResult tidak boleh null");
        }
        if (status == TransactionStatus.ABORTED && abortReason == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "abortReason TransactionResult tidak boleh null untuk ABORTED");
        }
        if (success && status != TransactionStatus.COMMITTED && status != TransactionStatus.ABORTED) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "status TransactionResult harus COMMITTED atau ABORTED jika success true");
        }
    }
}
