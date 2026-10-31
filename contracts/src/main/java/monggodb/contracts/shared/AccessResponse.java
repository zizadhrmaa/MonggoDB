package monggodb.contracts.shared;

import java.util.List;

/**
 * Hasil requestRead, requestWrite, atau waitForAccess. Immutable.
 * ALLOWED harus diikuti completeAccess setelah operasi SM selesai.
 * WAITING belum mengizinkan akses atau membuat log; ABORTED belum menjamin undo selesai.
 *
 * @param txId transaksi peminta
 * @param requestId identitas stabil permintaan, dipakai kembali saat menunggu atau retry
 * @param decision keputusan akses
 * @param lsn LSN mutasi WRITE; {@link LSN#NONE} jika tidak ada log, termasuk jika input null
 * @param abortReason alasan pembatalan, wajib bila decision ABORTED; null bila tidak ada
 * @param blockedBy transaksi penghalang saat WAITING; daftar kosong jika tidak ada atau input null
 * @param message penjelasan yang siap ditampilkan
 */
public record AccessResponse(TxId txId, String requestId, AccessDecision decision, LSN lsn,
                             AbortReason abortReason, List<TxId> blockedBy, String message) {

    public AccessResponse {
        if (txId == null || requestId == null || requestId.isBlank() || decision == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "txId, requestId, dan decision AccessResponse harus valid");
        }
        if (decision == AccessDecision.ABORTED && abortReason == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "abortReason AccessResponse tidak boleh null untuk ABORTED");
        }
        if (lsn == null) lsn = LSN.NONE;
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }
}
