package monggodb.contracts.shared;

import java.util.List;

/**
 * Hasil FRM.recover dan FRM.restoreTo.
 *
 * @param success true jika pemulihan berhasil
 * @param maxTxId ID transaksi terbesar di log, untuk melanjutkan penomoran CCM (CC-05); null jika log kosong
 * @param lastLSN LSN terakhir di log setelah pemulihan
 * @param redoneTx transaksi committed yang perubahannya diterapkan ulang
 * @param undoneTx transaksi yang perubahannya dibatalkan
 * @param message penjelasan yang siap ditampilkan
 */
public record RecoveryResult(boolean success, TxId maxTxId, LSN lastLSN,
                             List<TxId> redoneTx, List<TxId> undoneTx, String message) {

    public RecoveryResult {
        redoneTx = redoneTx == null ? List.of() : List.copyOf(redoneTx);
        undoneTx = undoneTx == null ? List.of() : List.copyOf(undoneTx);
        if (lastLSN == null) lastLSN = LSN.NONE;
    }
}
