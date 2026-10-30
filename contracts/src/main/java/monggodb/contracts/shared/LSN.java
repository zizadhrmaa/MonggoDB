package monggodb.contracts.shared;

/**
 * Log Sequence Number: nomor urut entri log, unik dan terus naik. Immutable.
 *
 * @param value nomor urut; entri log nyata dimulai dari 1
 */
public record LSN(long value) implements Comparable<LSN> {

    /** Penanda perubahan tanpa log (memuat basis data contoh, driver SM). Lebih kecil dari LSN nyata mana pun. */
    public static final LSN NONE = new LSN(0);

    @Override
    public int compareTo(LSN o) {
        return Long.compare(value, o.value);
    }

    @Override
    public String toString() {
        return value == 0 ? "LSN.NONE" : "LSN" + value;
    }
}
