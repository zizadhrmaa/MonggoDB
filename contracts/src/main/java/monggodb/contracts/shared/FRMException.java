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

    /** Kode error spesifik FRM. */
    private final Code frmCode;

    /**
     * @param code jenis error
     * @param message pesan yang siap ditampilkan
     */
    public FRMException(Code code, String message) {
        super(code.name(), message);
        this.frmCode = code;
    }

    /**
     * @param code jenis error
     * @param message pesan yang siap ditampilkan
     * @param cause penyebab asli
     */
    public FRMException(Code code, String message, Throwable cause) {
        super(code.name(), message, cause);
        this.frmCode = code;
    }

    /** @return kode error spesifik FRM */
    public Code getFrmCode() {
        return frmCode;
    }
}
