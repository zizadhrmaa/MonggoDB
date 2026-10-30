package monggodb.contracts.shared;

/**
 * Merepresentasikan perintah DROP TABLE untuk menghapus tabel dari basis data.
 *
 * Mendukung opsi CASCADE dan RESTRICT untuk menentukan perilaku penghapusan terhadap objek yang bergantung.
 */
public class DropTableNode extends PlanNode {

    /** Nama tabel yang akan dihapus. */
    private final String table;

    /** True untuk CASCADE, false untuk RESTRICT. */
    private final boolean cascade;

    /**
     * Membuat node perintah DROP TABLE.
     *
     * @param table nama tabel yang akan dihapus
     * @param cascade true untuk CASCADE, false untuk RESTRICT
     */
    public DropTableNode(String table, boolean cascade) {
        super(PlanNodeType.DROP_TABLE);
        this.table = table;
        this.cascade = cascade;
    }

    /**
     * Mengembalikan nama tabel yang akan dihapus.
     *
     * @return nama tabel
     */
    public String getTable() {
        return table;
    }

    /**
     * Memeriksa apakah penghapusan menggunakan CASCADE.
     *
     * @return true jika CASCADE, false jika RESTRICT
     */
    public boolean isCascade() {
        return cascade;
    }
}