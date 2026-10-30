package monggodb.contracts.shared;

/** Error dari Concurrency Control Manager. */
public class CCMException extends MdbmsException {

    /** Jenis error CCM. */
    public enum Code {
        /** Identitas transaksi tidak dikenal. */
        TX_UNKNOWN,
        /** Operasi tidak sesuai dengan status transaksi saat ini. */
        INVALID_STATE,
        /** Input atau permintaan tidak valid. */
        INVALID_REQUEST,
        /** Protokol tidak dapat diganti saat transaksi atau akses belum selesai. */
        PROTOCOL_BUSY,
        /** Penerimaan transaksi baru ditutup sementara atau karena shutdown. */
        ADMISSION_PAUSED,
        /** CCM belum berhasil menyelesaikan recovery. */
        NOT_READY,
        /** Pemulihan melalui FRM gagal. */
        RECOVERY_FAILED,
        /** Pencatatan log transaksi gagal. */
        LOG_FAILED,
        /** Undo gagal; transaksi tetap FAILED dan resource tetap dilindungi. */
        ROLLBACK_FAILED
    }

    /** Kode error spesifik CCM. */
    private final Code ccmCode;

    /**
     * @param code jenis error
     * @param message pesan yang siap ditampilkan
     */
    public CCMException(Code code, String message) {
        super(code.name(), message);
        this.ccmCode = code;
    }

    /**
     * @param code jenis error
     * @param message pesan yang siap ditampilkan
     * @param cause penyebab asli
     */
    public CCMException(Code code, String message, Throwable cause) {
        super(code.name(), message, cause);
        this.ccmCode = code;
    }

    /** @return kode error spesifik CCM */
    public Code getCcmCode() {
        return ccmCode;
    }
}
