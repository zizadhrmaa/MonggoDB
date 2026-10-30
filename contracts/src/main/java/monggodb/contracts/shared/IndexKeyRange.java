package monggodb.contracts.shared;

/**
 * Mendeskripsikan nilai kunci atau rentang kunci yang digunakan Index Scan.
 * Batas null berarti sisi rentang tersebut terbuka; untuk pencarian EQUAL, lower menyimpan key yang dicari.
 */
public class IndexKeyRange {

    /** Jenis pencarian indeks: kesamaan satu key atau rentang key. */
    public enum RangeKind {
        EQUAL,
        RANGE
    }

    /** Jenis pencarian indeks. */
    private final RangeKind kind;

    /** Batas bawah, atau null jika tidak memiliki batas bawah. */
    private final Value lower;

    /** Batas atas, atau null jika tidak memiliki batas atas. */
    private final Value upper;

    /** Apakah batas bawah termasuk dalam rentang (diabaikan jika lower null). */
    private final boolean lowerInclusive;

    /** Apakah batas atas termasuk dalam rentang (diabaikan jika upper null). */
    private final boolean upperInclusive;

    /**
     * Membentuk spesifikasi pencarian key pada indeks.
     *
     * @param kind jenis pencarian EQUAL atau RANGE
     * @param lower batas bawah, atau key untuk EQUAL; boleh null untuk RANGE tanpa batas bawah
     * @param upper batas atas; boleh null jika tanpa batas atas
     * @param lowerInclusive apakah batas bawah diikutsertakan
     * @param upperInclusive apakah batas atas diikutsertakan
     */
    public IndexKeyRange(RangeKind kind, Value lower, Value upper,
                         boolean lowerInclusive, boolean upperInclusive) {
        this.kind = kind;
        this.lower = lower;
        this.upper = upper;
        this.lowerInclusive = lowerInclusive;
        this.upperInclusive = upperInclusive;
    }

    /** @return jenis pencarian EQUAL atau RANGE */
    public RangeKind getKind() {
        return kind;
    }

    /** @return batas bawah atau key untuk EQUAL, dapat null */
    public Value getLower() {
        return lower;
    }

    /** @return batas atas, dapat null */
    public Value getUpper() {
        return upper;
    }

    /** @return true jika batas bawah disertakan */
    public boolean isLowerInclusive() {
        return lowerInclusive;
    }

    /** @return true jika batas atas disertakan */
    public boolean isUpperInclusive() {
        return upperInclusive;
    }
}
