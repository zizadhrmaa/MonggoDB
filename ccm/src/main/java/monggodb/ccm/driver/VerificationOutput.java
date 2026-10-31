package monggodb.ccm.driver;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import monggodb.ccm.serializability.ScheduleOp;
import monggodb.ccm.serializability.VerificationResult;
import monggodb.contracts.shared.TxId;

/** Keluaran schedule dan hasil verifikasi CC-09 yang dipakai driver */
final class VerificationOutput {

    private VerificationOutput() {
    }

    /** Teks schedule, misalnya R1(A) W2(A=5) C1 */
    static String schedule(List<ScheduleOp> schedule, Function<TxId, String> label) {
        List<String> parts = schedule.stream().map(op -> {
            String n = label.apply(op.txId()).substring(1);
            return switch (op.kind()) {
                case READ -> "R" + n + "(" + op.resource().table() + ")";
                case WRITE -> "W" + n + "(" + op.resource().table() + "=" + op.value() + ")";
                case COMMIT -> "C" + n;
                case ABORT -> "A" + n;
            };
        }).toList();
        return parts.isEmpty() ? "(kosong)" : String.join(" ", parts);
    }

    static String describe(VerificationResult r, Function<TxId, String> label) {
        if (r.serializable()) {
            return r.serialOrder().isEmpty() ? "serializable, tidak ada transaksi commit"
                    : "serializable, urutan serial " + String.join(" -> ", r.serialOrder().stream().map(label).toList());
        }
        return "tidak serializable, siklus " + String.join(" -> ", r.cycle().stream().map(label).toList())
                + " -> " + label.apply(r.cycle().get(0));
    }

    /** Membandingkan nilai akhir WRITE transaksi commit dengan eksekusi serial dari verifier (CC-09) */
    static void checkSerialEquivalence(List<ScheduleOp> schedule, VerificationResult result, List<String> failures) {
        if (!result.serializable()) {
            return;
        }
        Set<TxId> committed = new HashSet<>(result.committedTx());
        Map<String, Integer> concurrent = new TreeMap<>();
        Map<String, Integer> serial = new TreeMap<>();
        for (ScheduleOp op : schedule) {
            if (op.kind() == ScheduleOp.Kind.WRITE && committed.contains(op.txId())) {
                concurrent.put(op.resource().table(), op.value());
            }
        }
        for (TxId tx : result.serialOrder()) {
            for (ScheduleOp op : schedule) {
                if (op.kind() == ScheduleOp.Kind.WRITE && op.txId().equals(tx)) {
                    serial.put(op.resource().table(), op.value());
                }
            }
        }
        boolean same = concurrent.equals(serial);
        System.out.println("  Setara serial : " + (same ? "ya " : "tidak ") + concurrent
                + (same ? "" : ", eksekusi serial menghasilkan " + serial));
        if (!same) {
            failures.add("data akhir " + concurrent + " berbeda dari eksekusi serial " + serial);
        }
    }
}
