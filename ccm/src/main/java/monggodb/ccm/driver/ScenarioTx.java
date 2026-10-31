package monggodb.ccm.driver;

import java.util.List;

/**
 * Satu transaksi skenario yang dimulai driver sesuai urutan label
 *
 * @param label label transaksi (T1, T2, ...)
 * @param ops langkah terencana transaksi
 */
public record ScenarioTx(String label, List<ScenarioOp> ops) {

    public ScenarioTx {
        ops = ops == null ? List.of() : List.copyOf(ops);
    }
}
