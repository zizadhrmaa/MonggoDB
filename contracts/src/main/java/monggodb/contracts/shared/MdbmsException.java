package monggodb.contracts.shared;

/**
 * Parent exception mDBMS. Unchecked, tidak perlu throws di interface.
 * Pesan ditampilkan ke pengguna tanpa stack trace.
 * Diturunkan ke StorageException, CCMException, FRMException, QOException, QPException
 * dengan enum masing-masing.
 */
public class MdbmsException extends RuntimeException {
    private final String code;

    /**
     * @param code    nama kode dari enum subclass
     * @param message pesan yang ditampilkan
     */
    public MdbmsException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 
     * @return nama kode error 
     */
    public String getCode() {
        return code;
    }
}
