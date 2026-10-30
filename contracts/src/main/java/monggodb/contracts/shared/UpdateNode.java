package monggodb.contracts.shared;

/**
 * Merepresentasikan perintah UPDATE untuk mengubah nilai satu kolom pada baris yang memenuhi kondisi.
 *
 * Child plan menghasilkan baris target beserta RID, sedangkan value menyimpan ekspresi nilai baru.
 */
public class UpdateNode extends PlanNode {

    /** Query plan untuk menentukan baris target UPDATE. */
    private final PlanNode child;

    /** Nama tabel yang akan diperbarui. */
    private final String table;

    /** Kolom yang akan diperbarui. */
    private final ColumnRef column;

    /** Ekspresi yang menghasilkan nilai baru. */
    private final ExpressionNode value;

    /**
     * Membuat node perintah UPDATE.
     *
     * @param child query plan untuk memilih baris target
     * @param table nama tabel yang akan diperbarui
     * @param column kolom yang akan diperbarui
     * @param value ekspresi nilai baru
     */
    public UpdateNode(PlanNode child, String table, ColumnRef column, ExpressionNode value) {
        super(PlanNodeType.UPDATE);
        this.child = child;
        this.table = table;
        this.column = column;
        this.value = value;
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
     * Mengembalikan nama tabel tujuan UPDATE.
     *
     * @return nama tabel
     */
    public String getTable() {
        return table;
    }

    /**
     * Mengembalikan kolom yang akan diperbarui.
     *
     * @return referensi kolom
     */
    public ColumnRef getColumn() {
        return column;
    }

    /**
     * Mengembalikan ekspresi nilai baru.
     *
     * @return ekspresi UPDATE
     */
    public ExpressionNode getValue() {
        return value;
    }
}