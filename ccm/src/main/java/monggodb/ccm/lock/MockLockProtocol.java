package monggodb.ccm.lock;

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
 * Mock internal protokol penguncian (CC-06, CC-05, CC-07). Keputusan acquireLock diatur lewat {@link #setNextDecision}.
 * Pemanggilan metode dicatat berurutan untuk diverifikasi pada pengujian unit dan integrasi komponen.
 */
public class MockLockProtocol implements LockProtocol {

    private record ActiveAccess(TxId txId, ResourceId resource) {
    }

    private final Map<TxId, Set<ResourceId>> heldResources = new HashMap<>();
    private final Map<String, ActiveAccess> inProgress = new HashMap<>();
    private final Map<TxId, Set<TxId>> waitForSnapshot = new HashMap<>();
    private final Set<TxId> failed = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private AccessDecision nextDecision = AccessDecision.ALLOWED;
    private List<TxId> nextBlockedBy = List.of();
    private boolean detectorStopped;

    /** Membuat instance mock protokol penguncian dengan state awal kosong dan keputusan default ALLOWED. */
    public MockLockProtocol() {
    }

    /**
     * Mengatur keputusan acquireLock berikutnya, lalu otomatis kembali ke ALLOWED.
     *
     * @param decision keputusan akses berikutnya (ALLOWED, WAITING, atau ABORTED)
     * @param blockedBy daftar transaksi penghalang bila keputusan WAITING
     * @throws CCMException INVALID_REQUEST bila decision null atau blockedBy memuat elemen null
     */
    public synchronized void setNextDecision(AccessDecision decision, List<TxId> blockedBy) {
        if (decision == null || (blockedBy != null && blockedBy.stream().anyMatch(id -> id == null))) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "Keputusan lock mock tidak valid");
        }
        nextDecision = decision;
        nextBlockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }

    /**
     * Simulasi satu permintaan lock S/X pada resource bertingkat TABLE (versi mock, keputusan dari setNextDecision).
     * Pada ABORTED, abortReason diisi DEADLOCK_VICTIM dan transaksi hanya ditandai FAILED tanpa menjalankan rollback di dalam mutex lock.
     *
     * @param txId identitas transaksi peminta
     * @param resource tabel yang diminta
     * @param mode READ (lock S) atau WRITE (lock X)
     * @param requestId identitas stabil permintaan akses
     * @return keputusan akses beserta daftar penghalang atau alasan abort
     * @throws CCMException INVALID_REQUEST bila ada masukan yang null, requestId kosong, atau granularity bukan TABLE
     * @calls buildWaitForGraph, detectDeadlock (saat permintaan menjadi WAITING)
     * @calledBy dispatchAccess
     */
    @Override
    public synchronized AccessResponse acquireLock(TxId txId, ResourceId resource, AccessMode mode, String requestId) {
        if (txId == null || resource == null || mode == null || requestId == null || requestId.isBlank()
                || resource.granularity() != ResourceId.Granularity.TABLE) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "txId, resource bertingkat TABLE, mode, dan requestId wajib diisi");
        }
        calls.add("acquireLock " + txId + " " + resource.table() + " " + mode + " " + requestId);
        AccessDecision decision = nextDecision;
        List<TxId> blockedBy = nextBlockedBy;
        nextDecision = AccessDecision.ALLOWED;
        nextBlockedBy = List.of();

        // Rollback dijalankan oleh executor abort CCM di luar mutex lock table
        if (decision == AccessDecision.ABORTED) {
            failed.add(txId);
            waitForSnapshot.remove(txId);
            return new AccessResponse(txId, requestId, decision, LSN.NONE, AbortReason.DEADLOCK_VICTIM,
                    List.of(), "Mock: transaksi menjadi victim deadlock");
        }

        heldResources.computeIfAbsent(txId, key -> new LinkedHashSet<>()).add(resource);
        if (decision == AccessDecision.WAITING) {
            Set<TxId> blockers = new LinkedHashSet<>(blockedBy);
            blockers.remove(txId);
            waitForSnapshot.put(txId, blockers);
            return new AccessResponse(txId, requestId, decision, LSN.NONE, null,
                    blockedBy, "Mock: akses WAITING");
        }

        waitForSnapshot.remove(txId);
        inProgress.put(requestId, new ActiveAccess(txId, resource));
        return new AccessResponse(txId, requestId, AccessDecision.ALLOWED, LSN.NONE, null,
                List.of(), "Mock: akses ALLOWED");
    }

    /**
     * Sisi lock dari releaseResources: melepas seluruh kepemilikan lock dan entri tunggu milik transaksi
     * setelah mencapai status final (COMMITTED atau ABORTED), lalu memanggil wakeWaiters untuk tiap resource terdampak.
     *
     * @param txId transaksi yang selesai
     * @param finalStatus COMMITTED atau ABORTED
     * @throws CCMException INVALID_REQUEST bila txId atau finalStatus null, atau bukan COMMITTED/ABORTED
     * @calls wakeWaiters
     * @calledBy commitTransaction, rollbackTransaction
     */
    @Override
    public synchronized void releaseResources(TxId txId, TransactionStatus finalStatus) {
        if (txId == null || (finalStatus != TransactionStatus.COMMITTED && finalStatus != TransactionStatus.ABORTED)) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "txId wajib diisi dan finalStatus harus COMMITTED atau ABORTED");
        }
        Set<ResourceId> resources = heldResources.remove(txId);
        failed.remove(txId);
        inProgress.values().removeIf(active -> active.txId().equals(txId));
        waitForSnapshot.remove(txId);
        waitForSnapshot.values().forEach(blockers -> blockers.remove(txId));
        calls.add("releaseResources " + txId + " " + finalStatus + " " + describe(resources));
        if (resources != null) {
            for (ResourceId resource : resources) {
                wakeWaiters(resource);
            }
        }
    }

    /**
     * Sisi lock dari wakeWaiters: mencatat resource yang antrian tunggunya dievaluasi ulang.
     * Mock tidak mempromosikan penunggu secara nyata.
     *
     * @param resource resource yang lock atau aksesnya baru berubah
     * @throws CCMException INVALID_REQUEST bila resource null
     * @calls tidak ada
     * @calledBy releaseResources, onAccessCompleted
     */
    @Override
    public synchronized void wakeWaiters(ResourceId resource) {
        if (resource == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "resource wajib diisi");
        }
        calls.add("wakeWaiters " + resource.table());
    }

    /**
     * Dipanggil completeAccess: menghapus penanda akses sedang berlangsung tanpa melepas lock 2PL,
     * mencari resource dari requestId yang sebelumnya ALLOWED di acquireLock, lalu memanggil wakeWaiters.
     *
     * @param txId pemilik akses
     * @param requestId identitas akses yang ditutup
     * @param success true bila operasi SM berhasil
     * @throws CCMException INVALID_REQUEST bila txId atau requestId tidak valid atau tidak tercatat ALLOWED
     * @calls wakeWaiters
     * @calledBy completeAccess
     */
    @Override
    public synchronized void onAccessCompleted(TxId txId, String requestId, boolean success) {
        if (txId == null || requestId == null || requestId.isBlank()) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "txId dan requestId wajib diisi");
        }
        ActiveAccess active = inProgress.get(requestId);
        if (active == null || !active.txId().equals(txId)) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "requestId tidak sedang berlangsung untuk transaksi ini: " + requestId);
        }
        inProgress.remove(requestId);
        calls.add("onAccessCompleted " + txId + " " + requestId + " " + success);
        wakeWaiters(active.resource());
    }

    /**
     * Dipanggil resetAfterRecovery: mengosongkan seluruh lock table, antrian tunggu, penanda akses aktif,
     * dan penanda transaksi gagal.
     *
     * @calls tidak ada
     * @calledBy resetAfterRecovery
     */
    @Override
    public synchronized void resetLockState() {
        heldResources.clear();
        inProgress.clear();
        waitForSnapshot.clear();
        failed.clear();
        nextDecision = AccessDecision.ALLOWED;
        nextBlockedBy = List.of();
        detectorStopped = false;
        calls.add("resetLockState");
    }

    /**
     * Dipanggil shutdown: menghentikan thread pendeteksi deadlock berkala secara idempoten.
     *
     * @calls tidak ada
     * @calledBy shutdown
     */
    @Override
    public synchronized void stopDetector() {
        detectorStopped = true;
        calls.add("stopDetector");
    }

    /**
     * Mengembalikan snapshot atomik transaksi penunggu beserta penghalangnya untuk buildWaitForGraph (CC-07).
     *
     * @return salinan tidak dapat diubah dari hubungan tunggu saat ini
     * @calls tidak ada
     * @calledBy acquireLock, thread detector berkala
     */
    @Override
    public synchronized Map<TxId, Set<TxId>> snapshotWaitForGraph() {
        Map<TxId, Set<TxId>> copy = new HashMap<>();
        waitForSnapshot.forEach((waiter, blockers) -> copy.put(waiter, Set.copyOf(blockers)));
        return Map.copyOf(copy);
    }

    /**
     * Memeriksa apakah transaksi telah ditandai FAILED akibat menjadi victim deadlock.
     *
     * @param txId identitas transaksi yang diperiksa
     * @return true bila transaksi ditandai FAILED (DEADLOCK_VICTIM) oleh acquireLock dan belum dilepas
     */
    public synchronized boolean isMarkedFailed(TxId txId) {
        return failed.contains(txId);
    }

    /**
     * Memeriksa apakah suatu permintaan akses masih berstatus sedang berlangsung di Storage Manager.
     *
     * @param requestId identitas permintaan akses
     * @return true bila requestId masih tercatat sebagai akses SM yang sedang berlangsung
     */
    public synchronized boolean isInProgress(String requestId) {
        return inProgress.containsKey(requestId);
    }

    /**
     * Memeriksa apakah detector deadlock berkala telah dihentikan.
     *
     * @return true bila stopDetector sudah dipanggil sejak inisialisasi/reset terakhir
     */
    public synchronized boolean isDetectorStopped() {
        return detectorStopped;
    }

    /**
     * Mengambil daftar resource yang masih dipegang lock-nya oleh suatu transaksi.
     *
     * @param txId identitas transaksi
     * @return salinan himpunan resource yang masih dipegang lock-nya oleh transaksi
     */
    public synchronized Set<ResourceId> getHeldResources(TxId txId) {
        Set<ResourceId> resources = heldResources.get(txId);
        return resources == null ? Set.of() : Set.copyOf(resources);
    }

    /**
     * Mengambil riwayat pemanggilan metode secara berurutan pada mock ini.
     *
     * @return catatan pemanggilan metode secara berurutan untuk diverifikasi pada tes
     */
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

