package monggodb.contracts.shared;

/**
 * Merepresentasikan hasil parsing atau optimasi query SQL.
 * Menyimpan jenis statement, akar query plan, dan estimasi biaya.
 */
public class ParsedQuery {

    /** Jenis statement SQL yang diproses. */
    private final StatementType statementType;

    /** Akar query tree atau execution plan. Bernilai null untuk BEGIN, COMMIT, dan ABORT. */
    private final PlanNode head;

    /** Estimasi total biaya eksekusi query. Bernilai 0 untuk perintah yang tidak dioptimasi. */
    private final double cost;

    /**
     * Membentuk objek hasil parsing atau optimasi query.
     *
     * @param statementType jenis statement SQL
     * @param head akar query plan, atau null jika tidak ada
     * @param cost estimasi total biaya eksekusi
     */
    public ParsedQuery(StatementType statementType, PlanNode head, double cost) {
        this.statementType = statementType;
        this.head = head;
        this.cost = cost;
    }

    /** @return jenis statement SQL
     * @calls tidak ada
     * @calledBy QO dan QP1
     */
    public StatementType getStatementType() {
        return statementType;
    }

    /** @return akar query plan
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public PlanNode getHead() {
        return head;
    }

    /** @return estimasi total biaya eksekusi
     * @calls tidak ada
     * @calledBy QO dan QP1
     */
    public double getCost() {
        return cost;
    }
}
