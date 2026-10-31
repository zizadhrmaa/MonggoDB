package monggodb.ccm.driver;

import java.util.Map;

import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.TransactionStatus;

/**
 * Hasil yang diharapkan dari satu skenario per label transaksi
 *
 * @param finalStatus label ke COMMITTED/ABORTED
 * @param abortReason label ke alasan, hanya untuk yang ABORTED
 * @param serializable apakah schedule akhir harus serializable
 * @param finalData data akhir yang diharapkan; bila kosong dibandingkan dengan eksekusi ulang serial (CC-09)
 */
public record ScenarioExpected(Map<String, TransactionStatus> finalStatus, Map<String, AbortReason> abortReason,
                               boolean serializable, Map<String, Integer> finalData) {

    public ScenarioExpected {
        finalStatus = finalStatus == null ? Map.of() : Map.copyOf(finalStatus);
        abortReason = abortReason == null ? Map.of() : Map.copyOf(abortReason);
        finalData = finalData == null ? Map.of() : Map.copyOf(finalData);
    }
}
