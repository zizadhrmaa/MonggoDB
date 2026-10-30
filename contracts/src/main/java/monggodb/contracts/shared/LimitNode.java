package monggodb.contracts.shared;

/** Node pembatas jumlah baris hasil SELECT. */
public class LimitNode extends PlanNode {
    /** Input operasi limit. */
    private final PlanNode child;
    /** Jumlah maksimum baris. */
    private final int limit;

    /** @param child input limit
     * @param limit jumlah maksimum baris
     */
    public LimitNode(PlanNode child, int limit) {
        super(PlanNodeType.LIMIT);
        this.child = child;
        this.limit = limit;
    }

    /** @return input limit
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public PlanNode getChild() { return child; }

    /** @return jumlah maksimum baris
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public int getLimit() { return limit; }
}
