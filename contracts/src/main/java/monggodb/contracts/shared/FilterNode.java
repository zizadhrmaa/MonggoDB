package monggodb.contracts.shared;

/** Node seleksi baris berdasarkan ekspresi kondisi. */
public class FilterNode extends PlanNode {
    /** Input operasi filter. */
    private final PlanNode child;
    /** Kondisi yang harus dipenuhi baris. */
    private final ExpressionNode condition;

    /** @param child input filter
     * @param condition ekspresi kondisi
     */
    public FilterNode(PlanNode child, ExpressionNode condition) {
        super(PlanNodeType.FILTER);
        this.child = child;
        this.condition = condition;
    }

    /** @return input filter
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public PlanNode getChild() { return child; }

    /** @return ekspresi kondisi
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public ExpressionNode getCondition() { return condition; }
}
