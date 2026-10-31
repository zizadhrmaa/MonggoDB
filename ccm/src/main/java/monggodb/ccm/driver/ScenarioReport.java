package monggodb.ccm.driver;

import java.util.List;

import monggodb.ccm.serializability.ScheduleOp;
import monggodb.ccm.serializability.VerificationResult;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;

/**
 * Hasil satu skenario yang ditampilkan driver (CC-10)
 *
 * @param scenarioName nama skenario
 * @param operations status setiap operasi menurut urutan keputusan, termasuk yang WAITING atau ABORTED dan
 *                   tidak punya ScheduleOp
 * @param schedule schedule akhir
 * @param verification hasil verifySchedule atas schedule; null bila verifier tidak diberikan
 * @param finalDataOk true bila data akhir cocok dan perubahan transaksi aborted tidak tersisa;
 *                    null bila tidak dicek (tanpa data store)
 * @param timedOut true bila Scenario.timeoutMs terlewati
 * @param verdict PASS/FAIL dibanding expected
 */
public record ScenarioReport(String scenarioName, List<OperationStatus> operations, List<ScheduleOp> schedule,
                             VerificationResult verification, Boolean finalDataOk, boolean timedOut,
                             Verdict verdict) {

    public ScenarioReport {
        operations = operations == null ? List.of() : List.copyOf(operations);
        schedule = schedule == null ? List.of() : List.copyOf(schedule);
    }

    /** Kesimpulan skenario */
    public enum Verdict { PASS, FAIL }

    /**
     * Status satu operasi, dicatat dua kali bila sempat WAITING
     *
     * @param label label transaksi pemilik operasi
     * @param op langkah terencana, termasuk step
     * @param decision keputusan yang diterima
     * @param abortReason alasan, hanya bila ABORTED
     */
    public record OperationStatus(String label, ScenarioOp op, AccessDecision decision, AbortReason abortReason) {
    }
}
