package monggodb.contracts.shared;

/** Node pengurutan hasil berdasarkan satu kolom. */
public class SortNode extends PlanNode {
    /** Input operasi pengurutan. */
    private final PlanNode child;
    /** Kolom dan arah pengurutan. */
    private final SortKey key;

    /** @param child input pengurutan
     * @param key kolom dan arah pengurutan
     */
    public SortNode(PlanNode child, SortKey key) {
        super(PlanNodeType.SORT);
        this.child = child;
        this.key = key;
    }

    /** @return input pengurutan
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public PlanNode getChild() { return child; }

    /** @return kolom dan arah pengurutan
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public SortKey getKey() { return key; }
}
