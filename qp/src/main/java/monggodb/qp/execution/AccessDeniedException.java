package monggodb.qp.execution;

import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.MdbmsException;

/**
 * Izin akses dari CCM tidak diberikan: transaksi dibatalkan (ABORTED) atau izin masih WAITING
 * sampai batas waktu habis. Operator berhenti dan melempar exception ini tanpa membatalkan transaksi sendiri.
 */
public class AccessDeniedException extends MdbmsException {

    /** Penyebab izin tidak diberikan. */
    public enum Code {
        /** CCM menjawab ABORTED. Alasannya ada di getAbortReason(). */
        ABORTED,
        /** Izin masih WAITING setelah batas waktu menunggu habis. */
        WAIT_TIMEOUT
    }

    /** Penyebab dalam bentuk enum. */
    private final Code errorCode;

    /** Jawaban terakhir dari CCM. */
    private final AccessResponse response;

    /**
     * @param code     penyebab izin tidak diberikan
     * @param message  pesan untuk pengguna
     * @param response jawaban terakhir dari CCM
     */
    public AccessDeniedException(Code code, String message, AccessResponse response) {
        super(code.name(), message);
        this.errorCode = code;
        this.response = response;
    }

    /** @return penyebab dalam bentuk enum */
    public Code getErrorCode() {
        return errorCode;
    }

    /** @return jawaban terakhir dari CCM */
    public AccessResponse getResponse() {
        return response;
    }

    /** @return alasan pembatalan dari CCM, atau null jika penyebabnya bukan ABORTED */
    public AbortReason getAbortReason() {
        return response == null ? null : response.abortReason();
    }
}
