package monggodb.ccm.serializability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.TxId;

/** Verifier conflict serializability dengan precedence graph dan topological sort Kahn */
public class ScheduleVerifierImpl implements ScheduleVerifier {

    @Override
    public VerificationResult verifySchedule(List<ScheduleOp> schedule) {
        if (schedule == null) {
            throw invalid("schedule tidak boleh null");
        }
        Set<TxId> committed = validate(schedule);

        Map<TxId, Set<TxId>> edges = new TreeMap<>();
        committed.forEach(tx -> edges.put(tx, new TreeSet<>()));
        List<ScheduleOp> accesses = schedule.stream()
                .filter(op -> committed.contains(op.txId()))
                .filter(op -> op.kind() == ScheduleOp.Kind.READ || op.kind() == ScheduleOp.Kind.WRITE)
                .toList();
        for (int i = 0; i < accesses.size(); i++) {
            for (int j = i + 1; j < accesses.size(); j++) {
                if (conflict(accesses.get(i), accesses.get(j))) {
                    edges.get(accesses.get(i).txId()).add(accesses.get(j).txId());
                }
            }
        }

        Map<TxId, Integer> inDegree = new HashMap<>();
        committed.forEach(tx -> inDegree.put(tx, 0));
        edges.values().forEach(targets -> targets.forEach(t -> inDegree.merge(t, 1, Integer::sum)));
        PriorityQueue<TxId> ready = new PriorityQueue<>();
        inDegree.forEach((tx, degree) -> {
            if (degree == 0) {
                ready.add(tx);
            }
        });
        List<TxId> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            TxId tx = ready.poll();
            order.add(tx);
            for (TxId next : edges.get(tx)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }

        List<TxId> committedTx = new ArrayList<>(committed);
        if (order.size() == committed.size()) {
            return new VerificationResult(true, order, List.of(), committedTx,
                    order.isEmpty() ? "Serializable; tidak ada transaksi commit"
                            : "Serializable; urutan serial setara: " + path(order));
        }
        Set<TxId> remaining = new TreeSet<>(committed);
        order.forEach(remaining::remove);
        List<TxId> cycle = findCycle(edges, remaining);
        return new VerificationResult(false, List.of(), cycle, committedTx,
                "Tidak serializable; siklus " + path(cycle) + " -> " + cycle.get(0));
    }

    /**
     * Memeriksa seq naik dan tidak ada operasi setelah COMMIT/ABORT
     * @return transaksi yang punya COMMIT, terurut menurut TxId
     */
    private static Set<TxId> validate(List<ScheduleOp> schedule) {
        Set<TxId> committed = new TreeSet<>();
        Set<TxId> ended = new HashSet<>();
        long lastSeq = Long.MIN_VALUE;
        for (ScheduleOp op : schedule) {
            if (op == null) {
                throw invalid("schedule berisi operasi null");
            }
            if (op.seq() <= lastSeq) {
                throw invalid("seq harus naik, ditemukan " + op.seq() + " setelah " + lastSeq);
            }
            lastSeq = op.seq();
            if (ended.contains(op.txId())) {
                throw invalid("operasi " + op.kind() + " " + op.txId() + " setelah transaksi selesai (seq " + op.seq() + ")");
            }
            if (op.kind() == ScheduleOp.Kind.COMMIT || op.kind() == ScheduleOp.Kind.ABORT) {
                ended.add(op.txId());
                if (op.kind() == ScheduleOp.Kind.COMMIT) {
                    committed.add(op.txId());
                }
            }
        }
        return committed;
    }

    /** Konflik bila transaksi berbeda, resource sama, dan minimal satu WRITE */
    private static boolean conflict(ScheduleOp a, ScheduleOp b) {
        return !a.txId().equals(b.txId()) && a.resource().equals(b.resource())
                && (a.kind() == ScheduleOp.Kind.WRITE || b.kind() == ScheduleOp.Kind.WRITE);
    }

    /** Menelusuri pendahulu simpul sisa Kahn sampai kembali ke simpul yang sudah dikunjungi */
    private static List<TxId> findCycle(Map<TxId, Set<TxId>> edges, Set<TxId> remaining) {
        Map<TxId, TxId> predecessor = new HashMap<>();
        for (TxId from : remaining) {
            for (TxId to : edges.get(from)) {
                if (remaining.contains(to)) {
                    predecessor.putIfAbsent(to, from);
                }
            }
        }
        List<TxId> walk = new ArrayList<>();
        TxId current = remaining.iterator().next();
        while (!walk.contains(current)) {
            walk.add(current);
            current = predecessor.get(current);
        }
        List<TxId> cycle = new ArrayList<>(walk.subList(walk.indexOf(current), walk.size()));
        Collections.reverse(cycle);
        int start = cycle.indexOf(cycle.stream().min(TxId::compareTo).orElseThrow());
        List<TxId> rotated = new ArrayList<>(cycle.subList(start, cycle.size()));
        rotated.addAll(cycle.subList(0, start));
        return rotated;
    }

    private static String path(List<TxId> txs) {
        return String.join(" -> ", txs.stream().map(TxId::toString).toList());
    }

    private static CCMException invalid(String message) {
        return new CCMException(CCMException.Code.INVALID_REQUEST, "Schedule tidak valid: " + message);
    }
}
