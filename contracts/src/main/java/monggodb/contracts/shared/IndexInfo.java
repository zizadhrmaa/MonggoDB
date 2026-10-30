package monggodb.contracts.shared;

import java.util.Objects;

/**
 * Metadata sebuah index pada sebuah kolom tabel
 */
public final class IndexInfo {
    private final String indexName;
    private final String table;
    private final String column;
    private final IndexType type;

    /**
     * @param indexName nama index (unik)
     * @param table nama tabel yang diindeks
     * @param column nama kolom yang diindex
     * @param type jenis index (B+Tree atau Hash)
     * @throws NullPointerException jika ada argumen yang null
     */
    public IndexInfo(String indexName, String table, String column, IndexType type) {
        this.indexName = Objects.requireNonNull(indexName, "indexName tidak boleh null");
        this.table = Objects.requireNonNull(table, "table tidak boleh null");
        this.column = Objects.requireNonNull(column, "column tidak boleh null");
        this.type = Objects.requireNonNull(type, "type tidak boleh null");
    }

    /**
     * @return nama index
     */
    public String getIndexName() {
        return indexName;
    }

    /**
     * @return nama tabel
     */
    public String getIndexTable() {
        return table;
    }

    /**
     * @return nama kolom
     */
    public String getIndexColumn() {
        return column;
    }

    /**
     * @return jenis index
     */
    public IndexType getIndexType() {
        return type;
    }

    /**
     * @return true jika indexName, table, column, dan type sama
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexInfo)) return false;
        IndexInfo other = (IndexInfo) o;
        return indexName.equals(other.indexName) && table.equals(other.table) &&
                column.equals(other.column) && type == other.type;
    }

    /**
     * @return hash code dari indexName, table, column, dan type
     */
    @Override
    public int hashCode() {
        return Objects.hash(indexName, table, column, type);
    }

    /**
     * @return representasi string dari IndexInfo
     */
    @Override 
    public String toString() {
        return String.format("IndexInfo{indexName='%s', table='%s', column='%s', type=%s}", indexName, table, column, type);
    }
}