package monggodb.ccm.deadlock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import monggodb.contracts.shared.TxId;

/** Mock internal deadlock detector (CC-07). Victim yang dikembalikan detectDeadlock diatur lewat {@link #setForcedVictims}. */
public class MockDeadlockDetector {

    private List<TxId> forcedVictims = List.of();

    /** @param forcedVictims victim yang dikembalikan detectDeadlock; null atau kosong berarti tidak ada deadlock */
    public synchronized void setForcedVictims(List<TxId> forcedVictims) {
        this.forcedVictims = forcedVictims == null ? List.of() : List.copyOf(forcedVictims);
    }

    /**
     * Menyalin snapshot apa adanya tanpa menerapkan aturan edge
     * @param snapshot transaksi penunggu ke transaksi yang ditunggunya, diambil atomik dari lock table CC2
     * @return wait-for graph untuk detectDeadlock
     * @calls tidak ada
     * @calledBy acquireLock (saat permintaan menjadi WAITING); thread detector berkala tiap deadlockCheckIntervalMs
     */
    public Map<TxId, Set<TxId>> buildWaitForGraph(Map<TxId, Set<TxId>> snapshot) {
        Map<TxId, Set<TxId>> graph = new HashMap<>();
        if (snapshot != null) {
            snapshot.forEach((waiter, holders) -> graph.put(waiter, Set.copyOf(holders)));
        }
        return graph;
    }

    /**
     * Mengembalikan victim yang diatur lewat setForcedVictims, kosong bila belum diatur
     * @param graph wait-for graph dari buildWaitForGraph
     * @return victim, satu per siklus; kosong bila tidak ada deadlock
     * @calls tidak ada; pemanggil menandai victim FAILED (DEADLOCK_VICTIM) lalu executor abort memanggil
     *        abortTransaction
     * @calledBy acquireLock (saat permintaan menjadi WAITING); thread detector berkala tiap deadlockCheckIntervalMs
     */
    public synchronized List<TxId> detectDeadlock(Map<TxId, Set<TxId>> graph) {
        return forcedVictims;
    }
}
