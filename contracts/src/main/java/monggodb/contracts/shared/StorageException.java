package monggodb.contracts.shared;

/**
 * Exception dari Storage Manager. Turunan MdbmsException, jadi unchecked.
 * Pesannya sudah bisa langsung ditampilkan ke pengguna.
 */
public class StorageException extends MdbmsException {

    /** Jenis kesalahan dari SM. */
    public enum Code {
        /** Tabel tidak ada di katalog. */
        TABLE_NOT_FOUND,
        /** Nama tabel sudah dipakai. */
        TABLE_ALREADY_EXISTS,
        /** DROP TABLE RESTRICT ditolak karena tabel masih dirujuk foreign key. */
        TABLE_REFERENCED,
        /** Kolom tidak ada di tabel. */
        COLUMN_NOT_FOUND,
        /** Tidak ada record di RID tersebut. */
        RECORD_NOT_FOUND,
        /** RID di luar file tabel. */
        INVALID_RID,
        /** Permintaan tidak valid, misalnya lookup index dengan key NULL. */
        INVALID_REQUEST,
        /** Isi Row tidak cocok dengan Schema tabel. */
        SCHEMA_MISMATCH,
        /** RID belum dipesan lewat allocateRid. */
        RID_NOT_RESERVED,
        /** Snapshot tidak ditemukan. */
        SNAPSHOT_NOT_FOUND,
        /** Gagal baca/tulis file di disk. */
        IO_ERROR,
        /** Tipe nilai tidak sesuai tipe kolom. */
        TYPE_MISMATCH,
        /** Teks lebih panjang dari panjang kolom CHAR/VARCHAR. */
        VALUE_TOO_LONG,
        /** NULL dimasukkan ke kolom not null. */
        NULL_NOT_ALLOWED,
        /** Byte record rusak. */
        MALFORMED_RECORD,
        /** Tipe data tidak didukung. */
        UNSUPPORTED_DATA_TYPE,
        /** Nama index sudah dipakai. */
        INDEX_ALREADY_EXISTS,
        /** Index tidak ada di tabel tersebut. */
        INDEX_NOT_FOUND,
        /** Operasi tidak didukung jenis index ini, misalnya range pada HASH. */
        INDEX_NOT_SUPPORTED
    }

    /** Kode kesalahan dalam bentuk enum. */
    private final Code errorCode;

    /**
     * @param code    jenis kesalahan
     * @param message pesan untuk pengguna
     */
    public StorageException(Code code, String message) {
        super(code.name(), message);
        this.errorCode = code;
    }

    /**
     * Dipakai SM untuk membungkus exception lain, misalnya IOException.
     *
     * @param code    jenis kesalahan
     * @param message pesan untuk pengguna
     * @param cause   exception asal
     */
    public StorageException(Code code, String message, Throwable cause) {
        super(code.name(), message, cause);
        this.errorCode = code;
    }

    /** @return kode kesalahan dalam bentuk enum */
    public Code getErrorCode() {
        return errorCode;
    }
}
