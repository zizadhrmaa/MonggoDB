package monggodb.ccm.serializability;

import java.util.List;

import monggodb.contracts.shared.TxId;

/**
 * Hasil verifikasi serializability dari verifySchedule (CC-09)
 *
 * @param serializable true bila precedence graph transaksi commit tidak bersiklus
 * @param serialOrder urutan serial yang setara, kosong bila tidak serializable
 * @param cycle salah satu siklus yang ditemukan, kosong bila serializable
 * @param committedTx transaksi commit yang dihitung
 * @param message keterangan yang siap ditampilkan driver
 */
public record VerificationResult(boolean serializable, List<TxId> serialOrder, List<TxId> cycle,
                                 List<TxId> committedTx, String message) {

    public VerificationResult {
        serialOrder = serialOrder == null ? List.of() : List.copyOf(serialOrder);
        cycle = cycle == null ? List.of() : List.copyOf(cycle);
        committedTx = committedTx == null ? List.of() : List.copyOf(committedTx);
    }
}
