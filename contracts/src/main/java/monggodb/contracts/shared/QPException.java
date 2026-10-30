package monggodb.contracts.shared;

/**
 * Turunan MdbmsException.
 * Untuk error yang berasal dari QP.
 */
public class QPException extends MdbmsException {

    /** 
     * Jenis error QP. 
     */
    public enum Code {
        COLUMN_NOT_FOUND, 
        AMBIGUOUS_COLUMN, 
        DIVISION_BY_ZERO, 
        DDL_IN_TRANSACTION, 
        INVALID_PLAN, 
    }

    /**
     * @param code    jenis error QP
     * @param message pesan yang ditampilkan
     */
    public QPException(Code code, String message) {
        super(code.name(), message);
    }
}
