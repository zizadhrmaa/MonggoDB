package monggodb.contracts.shared;

/**
 * Parent exception mDBMS. Unchecked, tidak perlu throws di interface.
 * Pesan ditampilkan ke pengguna tanpa stack trace.
 * Diturunkan ke StorageException, CCMException, FRMException, QOException, QPException
 * dengan enum masing-masing.
 */
public class MdbmsException extends RuntimeException {
    /** Nama kode error, diambil dari enum milik subclass. */
    private final String code;

    /**
     * Konstruktor exception dengan kode dan pesan.
     *
     * @param code    nama kode dari enum subclass
     * @param message pesan yang ditampilkan
     */
    public MdbmsException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Dipakai saat membungkus exception lain (mencantumkan asal exception).
     *
     * @param code    nama kode dari enum subclass
     * @param message pesan yang ditampilkan
     * @param cause   exception asli penyebab error
     */
    public MdbmsException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Mengembalikan kode error dalam bentuk teks.
     *
     * @return nama kode error
     */
    public String getCode() {
        return code;
    }
}
