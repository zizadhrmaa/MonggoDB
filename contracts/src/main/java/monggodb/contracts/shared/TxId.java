package monggodb.contracts.shared;

/**
 * Identitas transaksi, unik dan terus naik. Immutable.
 * CCM mengalokasikan nilainya dan tidak memakainya ulang, termasuk setelah recovery.
 * Nilai yang sama dipakai sebagai timestamp logis.
 *
 * @param value identitas dan timestamp logis transaksi
 */
public record TxId(long value) implements Comparable<TxId> {

    @Override
    public int compareTo(TxId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return "Tx" + value;
    }
}
