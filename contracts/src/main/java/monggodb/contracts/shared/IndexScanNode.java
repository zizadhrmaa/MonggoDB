package monggodb.contracts.shared;

/**
 * Node query plan yang membaca baris tabel melalui suatu indeks.
 * Nama tabel, alias, indeks, kolom, dan spesifikasi key diteruskan kepada Query Processor untuk pelaksanaan Index Scan.
 */
public class IndexScanNode extends PlanNode {

    /** Nama tabel sumber data. */
    private final String table;

    /** Alias tabel, atau null jika query tidak menggunakan alias. */
    private final String alias;

    /** Nama indeks yang dipilih oleh optimizer. */
    private final String indexName;

    /** Referensi kolom yang diindeks. */
    private final ColumnRef column;

    /** Spesifikasi key atau rentang key yang akan dicari. */
    private final IndexKeyRange range;

    /**
     * Membuat node Index Scan tanpa child.
     *
     * @param table nama tabel sumber
     * @param alias alias tabel, atau null jika tidak ada
     * @param indexName nama indeks yang digunakan
     * @param column kolom tempat indeks dibuat
     * @param range spesifikasi key atau rentang pencarian
     */
    public IndexScanNode(String table, String alias, String indexName, ColumnRef column, IndexKeyRange range) {
        super(PlanNodeType.INDEX_SCAN);
        this.table = table;
        this.alias = alias;
        this.indexName = indexName;
        this.column = column;
        this.range = range;
    }

    /** @return nama tabel sumber */
    public String getTable() {
        return table;
    }

    /** @return alias tabel, atau null jika tidak ada */
    public String getAlias() {
        return alias;
    }

    /** @return nama indeks yang digunakan */
    public String getIndexName() {
        return indexName;
    }

    /** @return referensi kolom yang diindeks */
    public ColumnRef getColumn() {
        return column;
    }

    /** @return spesifikasi key atau rentang pencarian */
    public IndexKeyRange getRange() {
        return range;
    }
}
