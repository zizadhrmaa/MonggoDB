package monggodb.contracts.shared;

/**
 * Hasil FRM.rollback.
 *
 * @param status hasil pembatalan
 * @param message penjelasan yang siap ditampilkan
 */
public record RollbackResult(Status status, String message) {

    /** Status hasil rollback. */
    public enum Status {
        /** Seluruh perubahan sudah di-undo dan ABORT sudah dicatat. */
        DONE,
        /** Transaksi sudah selesai dibatalkan sebelumnya; tidak ada data yang diubah. */
        ALREADY_ABORTED,
        /** Transaksi sudah commit sehingga tidak dapat dibatalkan. */
        TX_COMMITTED,
        /** ID transaksi tidak dikenal FRM. */
        TX_UNKNOWN,
        /** Undo gagal; CCM tetap menahan lock transaksi. */
        FAILED
    }
}
