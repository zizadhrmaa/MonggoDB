
package monggodb.contracts.shared;

/**
 * Exception untuk kesalahan sintaks dan semantik pada Query Optimizer.
 */
public class QOException extends MdbmsException {

    /** Jenis kesalahan yang dihasilkan Query Optimizer. */
    public enum Code {
        SYNTAX_ERROR,
        SEMANTIC_ERROR
    }

    /** Kode kesalahan QO dalam bentuk enum. */
    private final Code errorCode;

    /** Posisi karakter yang menyebabkan kesalahan. */
    private final int position;

    /** Token SQL yang menyebabkan kesalahan. */
    private final String token;

    /**
     * Membentuk exception dari Query Optimizer.
     *
     * @param code jenis kesalahan
     * @param message pesan kesalahan untuk pengguna
     * @param position posisi karakter penyebab kesalahan
     * @param token token SQL penyebab kesalahan
     */
    public QOException(Code code, String message, int position, String token) {
        super(code.name(), message);
        this.errorCode = code;
        this.position = position;
        this.token = token;
    }

    /** @return kode kesalahan dalam bentuk enum */
    public Code getErrorCode() {
        return errorCode;
    }

    /** @return posisi karakter penyebab kesalahan */
    public int getPosition() {
        return position;
    }

    /** @return token SQL penyebab kesalahan */
    public String getToken() {
        return token;
    }
}
