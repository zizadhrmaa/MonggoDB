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
        /** Kolom tidak ada di skema hasil operator. */
        COLUMN_NOT_FOUND, 
        /** Nama kolom ambigu (cocok dengan lebih dari satu kolom). */
        AMBIGUOUS_COLUMN, 
        /** Pembagian dengan nol. */
        DIVISION_BY_ZERO, 
        /** CREATE INDEX, CREATE TABLE, atau DROP TABLE dijalankan di dalam BEGIN. */
        DDL_IN_TRANSACTION, 
        /** Plan dari QO tidak bisa diterjemahkan menjadi operator. */
        INVALID_PLAN, 
    }

    /** 
     * Jenis error QP, sama dengan getCode() tetapi dalam bentuk enum. 
     */
    private final Code errorCode;

    /**
     * Konstruktor error QP.
     *
     * @param code    jenis error QP
     * @param message pesan yang ditampilkan
     */
    public QPException(Code code, String message) {
        super(code.name(), message);
        this.errorCode = code;
    }

    /**
     * Mengembalikan jenis error dalam bentuk enum.
     *
     * @return jenis error QP
     */
    public Code getErrorCode() {
        return errorCode;
    }
}
