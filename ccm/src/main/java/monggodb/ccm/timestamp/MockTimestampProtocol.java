package monggodb.ccm.timestamp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import monggodb.ccm.AccessMode;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/**
 * Mock internal protokol timestamp (CC-08, CC-05). Keputusan validateTimestamp diatur lewat {@link #setNextDecision}.
 * Pemanggilan lainnya hanya dicatat untuk dicek di tes.
 */
public class MockTimestampProtocol {

    private final Map<TxId, Set<ResourceId>> touched = new HashMap<>();
    private final Set<TxId> failed = new HashSet<>();
    private final Map<String, ResourceId> openAccess = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private AccessDecision nextDecision = AccessDecision.ALLOWED;
    private List<TxId> nextBlockedBy = List.of();

    /** Mengatur keputusan validateTimestamp berikutnya, lalu kembali ke ALLOWED. */
    public synchronized void setNextDecision(AccessDecision decision, List<TxId> blockedBy) {
        if (decision == null || (blockedBy != null && blockedBy.stream().anyMatch(id -> id == null))) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "Keputusan tidak valid");
        }
        nextDecision = decision;
        nextBlockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }

    /**
     * Validasi satu permintaan akses (versi mock, keputusan dari setNextDecision).
     * Pada ABORTED, abortReason TIMESTAMP_VIOLATION dan transaksi hanya ditandai FAILED.
     *
     * @param txId transaksi peminta, timestamp sama dengan nilai TxId
     * @param resource tabel yang diakses
     * @param mode READ atau WRITE
     * @param requestId identitas stabil permintaan, sama dengan yang dipakai acquireLock
     * @return keputusan akses
     * @throws CCMException INVALID_REQUEST bila ada masukan yang null atau requestId kosong
     */
    public synchronized AccessResponse validateTimestamp(TxId txId, ResourceId resource, AccessMode mode, String requestId) {
        if (txId == null || resource == null || mode == null || requestId == null || requestId.isBlank()) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "txId, resource, mode, dan requestId wajib diisi");
        }
        calls.add("validateTimestamp " + txId + " " + resource.table() + " " + mode + " " + requestId);
        AccessDecision decision = nextDecision;
        List<TxId> blockedBy = nextBlockedBy;
        nextDecision = AccessDecision.ALLOWED;
        nextBlockedBy = List.of();

        // rollback dijalankan executor abort CCM di luar mutex ini, bukan di sini
        if (decision == AccessDecision.ABORTED) {
            failed.add(txId);
            return new AccessResponse(txId, requestId, decision, LSN.NONE, AbortReason.TIMESTAMP_VIOLATION,
                    List.of(), "Mock: melanggar urutan timestamp");
        }
        touched.computeIfAbsent(txId, key -> new LinkedHashSet<>()).add(resource);
        if (decision == AccessDecision.ALLOWED) {
            openAccess.put(requestId, resource);
        }
        return new AccessResponse(txId, requestId, decision, LSN.NONE, null,
                decision == AccessDecision.WAITING ? blockedBy : List.of(), "Mock: akses " + decision);
    }

    /**
     * Sisi timestamp dari releaseResources: mencatat resource milik transaksi yang dilepas,
     * menghapus penandanya, lalu meminta wakeWaiters untuk tiap resource tersebut.
     *
     * @param txId transaksi yang selesai
     * @param finalStatus COMMITTED atau ABORTED
     */
    public synchronized void releaseResources(TxId txId, TransactionStatus finalStatus) {
        if (txId == null || finalStatus == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "txId dan finalStatus wajib diisi");
        }
        Set<ResourceId> resources = touched.remove(txId);
        failed.remove(txId);
        calls.add("releaseResources " + txId + " " + finalStatus + " " + describe(resources));
        if (resources != null) {
            for (ResourceId resource : resources) {
                wakeWaiters(resource);
            }
        }
    }

    /**
     * Sisi timestamp dari wakeWaiters: mencatat resource yang penunggunya dibangunkan.
     * Mock tidak memvalidasi ulang penunggu.
     *
     * @param resource resource yang penandanya baru dihapus
     */
    public synchronized void wakeWaiters(ResourceId resource) {
        if (resource == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "resource wajib diisi");
        }
        calls.add("wakeWaiters " + resource.table());
    }

    /**
     * Dipanggil completeAccess: mencatat penutupan akses, lalu membangunkan penunggu resource-nya.
     * Resource dicari dari requestId yang tadi di-ALLOWED oleh validateTimestamp.
     *
     * @param txId pemilik akses
     * @param requestId identitas akses yang ditutup
     * @param success true bila operasi SM berhasil
     */
    public synchronized void onAccessCompleted(TxId txId, String requestId, boolean success) {
        if (txId == null || requestId == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "txId dan requestId wajib diisi");
        }
        calls.add("onAccessCompleted " + txId + " " + requestId + " " + success);
        ResourceId resource = openAccess.remove(requestId);
        if (resource != null) {
            wakeWaiters(resource);
        }
    }

    /** Dipanggil resetAfterRecovery: mengosongkan state per resource dan penanda gagal. */
    public synchronized void resetTimestampState() {
        touched.clear();
        failed.clear();
        openAccess.clear();
        nextDecision = AccessDecision.ALLOWED;
        nextBlockedBy = List.of();
        calls.add("resetTimestampState");
    }

    /** @return true bila transaksi ditandai FAILED oleh validateTimestamp dan belum dilepas */
    public synchronized boolean isMarkedFailed(TxId txId) {
        return failed.contains(txId);
    }

    /** @return catatan pemanggilan berurutan, untuk dicek tes */
    public synchronized List<String> getCalls() {
        return List.copyOf(calls);
    }

    private static String describe(Set<ResourceId> resources) {
        if (resources == null || resources.isEmpty()) return "[]";
        List<String> names = new ArrayList<>();
        for (ResourceId resource : resources) names.add(resource.table());
        return names.toString();
    }
}
