package monggodb.contracts.shared;

/** Struktur dasar node query tree atau execution plan. */
public abstract class PlanNode {
    /** Jenis operasi. */
    private final PlanNodeType type;
    /** Estimasi jumlah baris hasil operasi. */
    private long estimatedRows;
    /** Estimasi biaya operasi. */
    private double estimatedCost;

    /** @param type jenis operasi */
    protected PlanNode(PlanNodeType type) { this.type = type; }

    /** @return jenis operasi
     * @calls tidak ada
     * @calledBy QO dan QP
     */
    public PlanNodeType getType() { return type; }

    /** @return estimasi jumlah baris
     * @calls tidak ada
     * @calledBy QO3 dan QO5
     */
    public long getEstimatedRows() { return estimatedRows; }

    /** @param estimatedRows estimasi jumlah baris
     * @calls tidak ada
     * @calledBy QO3
     */
    public void setEstimatedRows(long estimatedRows) { this.estimatedRows = estimatedRows; }

    /** @return estimasi biaya
     * @calls tidak ada
     * @calledBy QO dan QP
     */
    public double getEstimatedCost() { return estimatedCost; }

    /** @param estimatedCost estimasi biaya
     * @calls tidak ada
     * @calledBy QO3
     */
    public void setEstimatedCost(double estimatedCost) { this.estimatedCost = estimatedCost; }
}
