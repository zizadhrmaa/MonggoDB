package monggodb.ccm.driver;

import java.util.List;
import java.util.Map;

import monggodb.contracts.shared.ProtocolType;

/**
 * Definisi satu skenario uji driver untuk satu protokol
 *
 * @param name nama skenario, juga awalan operationId
 * @param protocol protokol yang diuji
 * @param transactions transaksi skenario; urutan label menentukan urutan beginTransaction
 * @param initialData nilai awal data store (item ke nilai)
 * @param expected hasil yang diharapkan
 * @param timeoutMs batas waktu skenario; skenario FAIL bila terlewati
 */
public record Scenario(String name, ProtocolType protocol, List<ScenarioTx> transactions,
                       Map<String, Integer> initialData, ScenarioExpected expected, long timeoutMs) {

    public Scenario {
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
        initialData = initialData == null ? Map.of() : Map.copyOf(initialData);
    }
}
