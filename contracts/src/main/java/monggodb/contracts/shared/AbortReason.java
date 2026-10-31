package monggodb.contracts.shared;

/** Alasan pembatalan transaksi. */
public enum AbortReason {
    /** Pengguna meminta ABORT secara eksplisit. */
    USER_REQUEST,
    /** Transaksi dipilih sebagai victim deadlock. */
    DEADLOCK_VICTIM,
    /** Operasi melanggar urutan timestamp. */
    TIMESTAMP_VIOLATION,
    /** Pencatatan log gagal sehingga transaksi tidak dapat dilanjutkan. */
    LOG_FAILED,
    /** Operasi SM gagal setelah akses diizinkan. */
    ACCESS_FAILED,
    /** Koneksi atau sesi pemilik transaksi terputus. */
    DISCONNECT
}
