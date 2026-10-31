package monggodb.contracts.shared;

/** Error dari Failure Recovery Manager. */
public class FRMException extends MdbmsException {

    /** Jenis error FRM. */
    public enum Code {
        /** Gagal menulis atau memaksa log ke disk; operasi yang butuh jaminan WAL tidak boleh dilanjutkan. */
        LOG_WRITE_FAILED,
        /** Log rusak di bagian tengah sehingga tidak dapat dipakai. */
        LOG_CORRUPTED,
        /** Pemulihan gagal. */
        RECOVERY_FAILED,
        /** Checkpoint gagal. */
        CHECKPOINT_FAILED,
        /** Target pemulihan historis di luar riwayat yang tersedia. */
        RESTORE_OUT_OF_RANGE
    }

    /** Kode error dalam bentuk enum. */
    private final Code errorCode;

    /**
     * @param code jenis error
     * @param message pesan yang siap ditampilkan
     */
    public FRMException(Code code, String message) {
        super(code.name(), message);
        this.errorCode = code;
    }

    /**
     * @param code jenis error
     * @param message pesan yang siap ditampilkan
     * @param cause penyebab asli
     */
    public FRMException(Code code, String message, Throwable cause) {
        super(code.name(), message, cause);
        this.errorCode = code;
    }

    /** @return kode error dalam bentuk enum */
    public Code getErrorCode() {
        return errorCode;
    }
}
