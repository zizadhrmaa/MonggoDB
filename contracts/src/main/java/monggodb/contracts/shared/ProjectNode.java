package monggodb.contracts.shared;

import java.util.List;

/** Node proyeksi kolom sesuai urutan keluaran. */
public class ProjectNode extends PlanNode {
    /** Input operasi proyeksi. */
    private final PlanNode child;
    /** Kolom keluaran sesuai urutan SELECT. */
    private List<ColumnRef> columns;

    /** @param child input proyeksi
     * @param columns daftar kolom keluaran
     */
    public ProjectNode(PlanNode child, List<ColumnRef> columns) {
        super(PlanNodeType.PROJECT);
        this.child = child;
        this.columns = columns;
    }

    /** @return input proyeksi
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public PlanNode getChild() { return child; }

    /** @return daftar kolom keluaran
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public List<ColumnRef> getColumns() { return columns; }

    /** @param columns daftar kolom setelah ekspansi atau penyesuaian
     * @calls tidak ada
     * @calledBy QO3, QO4, dan QO5
     */
    public void setColumns(List<ColumnRef> columns) { this.columns = columns; }
}
