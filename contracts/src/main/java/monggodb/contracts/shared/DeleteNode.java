package monggodb.contracts.shared;

/**
 * Merepresentasikan perintah DELETE untuk menghapus baris yang memenuhi kondisi tertentu.
 *
 * Child plan menghasilkan baris target beserta RID yang diperlukan untuk proses penghapusan.
 */
public class DeleteNode extends PlanNode {

    /** Query plan untuk menentukan baris target DELETE. */
    private final PlanNode child;

    /** Nama tabel yang akan dihapus datanya. */
    private final String table;

    /**
     * Membuat node perintah DELETE.
     *
     * @param child query plan untuk memilih baris target
     * @param table nama tabel yang akan dihapus datanya
     */
    public DeleteNode(PlanNode child, String table) {
        super(PlanNodeType.DELETE);
        this.child = child;
        this.table = table;
    }

    /**
     * Mengembalikan child plan.
     *
     * @return query plan untuk memilih baris target
     */
    public PlanNode getChild() {
        return child;
    }

    /**
     * Mengembalikan nama tabel tujuan DELETE.
     *
     * @return nama tabel
     */
    public String getTable() {
        return table;
    }
}