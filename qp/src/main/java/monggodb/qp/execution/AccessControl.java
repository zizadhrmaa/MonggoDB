package monggodb.qp.execution;

import java.util.concurrent.TimeUnit;

import monggodb.contracts.ccm.ConcurrencyControlManager;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.ResourceId;

/**
 * Meminta dan menutup izin akses ke CCM dengan penanganan WAITING dan ABORTED yang seragam.
 */
public final class AccessControl {

    private AccessControl() {
    }

    /**
     * Meminta izin baca satu resource. Jika CCM menjawab WAITING, menunggu dengan requestId yang sama
     * sampai ALLOWED, ABORTED, atau batas waktu dari ctx habis.
     *
     * @param ctx      konteks eksekusi
     * @param resource resource yang dibaca
     * @return jawaban CCM berstatus ALLOWED, yang wajib ditutup dengan finishAccess
     * @throws AccessDeniedException ABORTED jika transaksi dibatalkan, WAIT_TIMEOUT jika izin tidak kunjung diberikan
     */
    public static AccessResponse acquireReadAccess(ExecutionContext ctx, ResourceId resource) {
        ConcurrencyControlManager ccm = ctx.getCcm();
        AccessResponse response = ccm.requestRead(ctx.getTxId(), resource);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ctx.getAccessTimeoutMs());
        while (response.decision() == AccessDecision.WAITING) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                throw new AccessDeniedException(AccessDeniedException.Code.WAIT_TIMEOUT,
                        "Izin baca tabel " + resource.table() + " belum diberikan setelah "
                                + ctx.getAccessTimeoutMs() + " ms", response);
            }
            response = ccm.waitForAccess(ctx.getTxId(), response.requestId(), remainingMs);
        }
        if (response.decision() == AccessDecision.ABORTED) {
            throw new AccessDeniedException(AccessDeniedException.Code.ABORTED,
                    "Transaksi " + ctx.getTxId() + " dibatalkan saat membaca tabel " + resource.table()
                            + " (" + response.abortReason() + ")", response);
        }
        return response;
    }

    /**
     * Menutup izin yang sebelumnya ALLOWED.
     *
     * @param ctx       konteks eksekusi
     * @param requestId identitas izin dari acquireReadAccess
     * @param success   true jika operasi SM selama izin berlaku berhasil
     */
    public static void finishAccess(ExecutionContext ctx, String requestId, boolean success) {
        ctx.getCcm().completeAccess(ctx.getTxId(), requestId, success);
    }
}
