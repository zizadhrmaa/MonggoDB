package monggodb.contracts.shared;

/** Jenis entri log FRM. */
public enum LogType {
    /** Awal transaksi. */
    BEGIN,
    /** Penyisipan record; before kosong ("record tidak ada"). */
    INSERT,
    /** Perubahan record; before dan after terisi. */
    UPDATE,
    /** Penghapusan record; after kosong ("record tidak ada"). */
    DELETE,
    /** Transaksi berhasil commit. */
    COMMIT,
    /** Pembatalan transaksi selesai: seluruh perubahannya sudah di-undo. */
    ABORT,
    /** Awal pembuatan checkpoint. */
    CHECKPOINT_START,
    /** Checkpoint selesai; seluruh data sebelum titik ini sudah ada di disk. */
    CHECKPOINT_END
}
