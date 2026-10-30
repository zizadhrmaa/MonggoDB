package monggodb.contracts.shared;

import java.util.Objects;

/**
 * Statistik satu kolom untuk estimasi biaya di QO.
 * Baris NULL tidak pernah lolos kondisi = maupun rentang, jadi nullCount dipisah dari distinctCount.
 */
public final class ColumnStats {

    /** Nama kolom. */
    private final String column;

    /** Jumlah nilai unik V(A,r), NULL tidak dihitung. */
    private final long distinctCount;

    /** Jumlah baris yang kolom ini bernilai NULL. */
    private final long nullCount;

    /** Nilai terkecil yang bukan NULL. Hanya untuk INTEGER/FLOAT, selain itu null. */
    private final Value minValue;

    /** Nilai terbesar yang bukan NULL. Hanya untuk INTEGER/FLOAT, selain itu null. */
    private final Value maxValue;

    /**
     * @param column        nama kolom
     * @param distinctCount jumlah nilai unik tanpa NULL
     * @param nullCount     jumlah baris bernilai NULL
     * @param minValue      nilai minimum, null untuk kolom teks, tabel kosong, atau kolom yang isinya NULL semua
     * @param maxValue      nilai maksimum, aturannya sama dengan minValue
     */
    public ColumnStats(String column, long distinctCount, long nullCount, Value minValue, Value maxValue) {
        this.column = Objects.requireNonNull(column, "column tidak boleh null");
        this.distinctCount = distinctCount;
        this.nullCount = nullCount;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /** @return nama kolom */
    public String getColumn() {
        return column;
    }

    /** @return jumlah nilai unik tanpa NULL */
    public long getDistinctCount() {
        return distinctCount;
    }

    /** @return jumlah baris bernilai NULL */
    public long getNullCount() {
        return nullCount;
    }

    /** @return nilai minimum, atau null */
    public Value getMinValue() {
        return minValue;
    }

    /** @return nilai maksimum, atau null */
    public Value getMaxValue() {
        return maxValue;
    }

    @Override
    public String toString() {
        return column + "{distinct=" + distinctCount + ", null=" + nullCount
                + ", min=" + minValue + ", max=" + maxValue + "}";
    }
}
