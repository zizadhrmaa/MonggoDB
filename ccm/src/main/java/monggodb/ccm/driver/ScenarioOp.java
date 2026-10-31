package monggodb.ccm.driver;

import monggodb.contracts.shared.AccessDecision;

/**
 * Satu langkah terencana dalam ScenarioTx, misalnya W2(A=5)
 *
 * @param kind jenis langkah
 * @param item item (A, B, ...), dipetakan driver ke ResourceId(table = item, granularity = TABLE);
 *             null untuk COMMIT/ABORT
 * @param value nilai tulis, hanya untuk WRITE
 * @param step giliran barrier, urutan langkah di seluruh schedule mulai 1
 * @param expectedDecision keputusan pertama yang diharapkan, opsional (null bila tidak dicek)
 * @param pauseBeforeMs jeda sebelum langkah ini dilepas, 0 bila tidak ada; dipakai misalnya agar transaksi
 *                      menunggu lebih lama dari deadlockCheckIntervalMs (tambahan dari dokumen #26)
 */
public record ScenarioOp(Kind kind, String item, Integer value, int step, AccessDecision expectedDecision,
                         long pauseBeforeMs) {

    /** Jenis langkah skenario */
    public enum Kind { READ, WRITE, COMMIT, ABORT }
}
