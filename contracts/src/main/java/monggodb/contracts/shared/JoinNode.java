package monggodb.contracts.shared;

/** Node penggabungan dua input. */
public class JoinNode extends PlanNode {
    /** Input kiri join. */
    private final PlanNode left;
    /** Input kanan join. */
    private final PlanNode right;
    /** Parameter join. */
    private final JoinSpec spec;

    /** @param left input kiri
     * @param right input kanan
     * @param spec parameter join
     */
    public JoinNode(PlanNode left, PlanNode right, JoinSpec spec) {
        super(PlanNodeType.JOIN);
        this.left = left;
        this.right = right;
        this.spec = spec;
    }

    /** @return input kiri
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public PlanNode getLeft() { return left; }

    /** @return input kanan
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public PlanNode getRight() { return right; }

    /** @return parameter join
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public JoinSpec getSpec() { return spec; }
}
