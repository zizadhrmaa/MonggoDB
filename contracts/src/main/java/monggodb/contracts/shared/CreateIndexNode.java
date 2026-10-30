package monggodb.contracts.shared;

/**
 * Merepresentasikan perintah CREATE INDEX untuk membuat indeks pada satu kolom tabel.
 *
 * Mendukung indeks BTREE dan HASH, dengan BTREE sebagai tipe default.
 */
public class CreateIndexNode extends PlanNode {

    /** Nama indeks yang akan dibuat. */
    private final String indexName;

    /** Nama tabel tempat indeks dibuat. */
    private final String table;

    /** Nama kolom yang akan diindeks. */
    private final String column;

    /** Jenis indeks yang digunakan, yaitu BTREE atau HASH. */
    private final IndexType type;

    /**
     * Membuat node CREATE INDEX dengan tipe tertentu.
     *
     * @param indexName nama indeks
     * @param table nama tabel
     * @param column nama kolom
     * @param type jenis indeks yang akan dibuat
     */
    public CreateIndexNode(String indexName, String table, String column, IndexType type) {
        super(PlanNodeType.CREATE_INDEX);
        this.indexName = indexName;
        this.table = table;
        this.column = column;
        this.type = type;
    }

    /**
     * Membuat node CREATE INDEX dengan tipe default BTREE.
     *
     * @param indexName nama indeks
     * @param table nama tabel
     * @param column nama kolom
     */
    public CreateIndexNode(String indexName, String table, String column) {
        this(indexName, table, column, IndexType.BTREE);
    }

    /**
     * Mengembalikan nama indeks.
     *
     * @return nama indeks
     */
    public String getIndexName() {
        return indexName;
    }

    /**
     * Mengembalikan nama tabel.
     *
     * @return nama tabel
     */
    public String getTable() {
        return table;
    }

    /**
     * Mengembalikan nama kolom yang diindeks.
     *
     * @return nama kolom
     */
    public String getColumn() {
        return column;
    }

    /**
     * Mengembalikan jenis indeks.
     *
     * @return tipe indeks BTREE atau HASH
     */
    public IndexType getType() {
        return type;
    }
}