package monggodb.contracts.shared;

/** Status lifecycle transaksi. */
public enum TransactionStatus {
    /** Transaksi dapat meminta akses baca dan tulis. */
    ACTIVE,
    /** COMMIT "sedang" dipersistenkan, resource belum boleh dilepas (transaksi selesai dan sedang di commit). */
    PARTIALLY_COMMITTED,
    /** Transaksi gagal, resource tetap dilindungi sampai undo selesai. */
    FAILED,
    /** Undo selesai dan ABORT sudah dicatat. Status final, tidak dapat berubah lagi. */
    ABORTED,
    /** COMMIT sudah persisten. Status final, tidak dapat berubah lagi. */
    COMMITTED
}
