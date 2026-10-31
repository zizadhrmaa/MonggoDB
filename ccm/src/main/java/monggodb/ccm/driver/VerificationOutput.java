package monggodb.ccm.driver;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import monggodb.ccm.serializability.ScheduleOp;
import monggodb.ccm.serializability.VerificationResult;
import monggodb.contracts.shared.TxId;

/** Keluaran schedule dan hasil verifikasi CC-09 yang dipakai driver */
final class VerificationOutput {

    private VerificationOutput() {
    }

    /** Teks schedule, misalnya R1(A) W2(A=5) C1, dengan nilai tulis diambil per seq */
    static String schedule(List<ScheduleOp> schedule, Function<TxId, String> label, Map<Long, Integer> writeValues) {
        List<String> parts = schedule.stream().map(op -> {
            String n = label.apply(op.txId()).substring(1);
            return switch (op.kind()) {
                case READ -> "R" + n + "(" + op.resource().table() + ")";
                case WRITE -> "W" + n + "(" + op.resource().table()
                        + (writeValues.containsKey(op.seq()) ? "=" + writeValues.get(op.seq()) : "") + ")";
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
}
