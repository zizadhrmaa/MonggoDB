package monggodb.contracts.shared;

import java.util.Map;
import java.util.Objects;

/**
 * Statistik satu tabel yang dipakai QO3 dan QO5 untuk estimasi biaya.
 * Nilainya selalu mencerminkan kondisi terkini setelah INSERT, UPDATE, dan DELETE.
 */
public final class TableStats {

    /** Nama tabel. */
    private final String tableName;

    /** Jumlah record n_r. */
    private final long recordCount;

    /** Jumlah blok b_r yang dipakai tabel. */
    private final long blockCount;

    /** Ukuran rata-rata record l_r dalam byte. */
    private final double avgRecordSize;

    /** Blocking factor f_r = floor(blockSize / l_r). */
    private final double blockingFactor;

    /** Ukuran satu blok dalam byte. */
    private final int blockSize;

    /** Statistik per kolom, dengan nama kolom sebagai key. */
    private final Map<String, ColumnStats> columnStats;

    /**
     * @param tableName      nama tabel
     * @param recordCount    n_r
     * @param blockCount     b_r
     * @param avgRecordSize  l_r dalam byte
     * @param blockingFactor f_r
     * @param blockSize      ukuran blok dalam byte
     * @param columnStats    statistik per kolom
     */
    public TableStats(String tableName, long recordCount, long blockCount, double avgRecordSize,
                      double blockingFactor, int blockSize, Map<String, ColumnStats> columnStats) {
        this.tableName = Objects.requireNonNull(tableName, "tableName tidak boleh null");
        this.recordCount = recordCount;
        this.blockCount = blockCount;
        this.avgRecordSize = avgRecordSize;
        this.blockingFactor = blockingFactor;
        this.blockSize = blockSize;
        this.columnStats = Map.copyOf(Objects.requireNonNull(columnStats, "columnStats tidak boleh null"));
    }

    /** @return nama tabel */
    public String getTableName() {
        return tableName;
    }

    /** @return jumlah record n_r */
    public long getRecordCount() {
        return recordCount;
    }

    /** @return jumlah blok b_r */
    public long getBlockCount() {
        return blockCount;
    }

    /** @return ukuran rata-rata record l_r dalam byte */
    public double getAvgRecordSize() {
        return avgRecordSize;
    }

    /** @return blocking factor f_r */
    public double getBlockingFactor() {
        return blockingFactor;
    }

    /** @return ukuran blok dalam byte */
    public int getBlockSize() {
        return blockSize;
    }

    /** @return statistik semua kolom (tidak bisa diubah) */
    public Map<String, ColumnStats> getColumnStats() {
        return columnStats;
    }

    /**
     * @param column nama kolom
     * @return statistik kolom tersebut, atau null jika tidak ada
     */
    public ColumnStats getColumnStats(String column) {
        return columnStats.get(column);
    }

    @Override
    public String toString() {
        return tableName + "{n_r=" + recordCount + ", b_r=" + blockCount + ", l_r=" + avgRecordSize
                + ", f_r=" + blockingFactor + ", blockSize=" + blockSize + "}";
    }
}
