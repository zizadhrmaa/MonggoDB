package monggodb.contracts.shared;

import java.util.Objects;

/**
 * Target pemulihan historis FRM.restoreTo.
 *
 * @param kind jenis target
 * @param time batas waktu commit (epoch ms), dipakai jika kind = TIME
 * @param txId transaksi committed terakhir yang disertakan, dipakai jika kind = TX_ID
 */
public record RecoveryTarget(Kind kind, long time, TxId txId) {

    /** Jenis target pemulihan. */
    public enum Kind {
        /** Semua transaksi yang commit paling lambat pada waktu tertentu. */
        TIME,
        /** Keadaan basis data sampai COMMIT transaksi tertentu. */
        TX_ID
    }

    public RecoveryTarget {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.TX_ID) Objects.requireNonNull(txId, "txId wajib untuk target TX_ID");
    }
}
