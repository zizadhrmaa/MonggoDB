package monggodb.contracts.shared;

import java.util.List;

/** Parameter join yang digunakan QO dan QP. */
public class JoinSpec {
    /** Jenis logis penggabungan. */
    public enum JoinType { CARTESIAN, CONDITIONAL, NATURAL }
    /** Algoritma fisik yang disediakan untuk join. */
    public enum JoinAlgorithm { BNLJ }

    /** Jenis join. */
    private final JoinType joinType;
    /** Kondisi untuk conditional join. */
    private final ExpressionNode condition;
    /** Nama kolom bersama untuk natural join. */
    private List<String> commonColumns;
    /** Algoritma yang dipilih optimizer. */
    private JoinAlgorithm algorithm;
    /** Jumlah baris outer per blok buffer BNLJ. */
    private int blockLimit;

    /** @param joinType jenis join
     * @param condition kondisi untuk conditional join, atau null
     * @param commonColumns kolom bersama untuk natural join, atau null
     * @param algorithm algoritma join, atau null sebelum pemilihan
     * @param blockLimit jumlah baris outer per blok buffer
     */
    public JoinSpec(JoinType joinType, ExpressionNode condition, List<String> commonColumns,
                    JoinAlgorithm algorithm, int blockLimit) {
        this.joinType = joinType;
        this.condition = condition;
        this.commonColumns = commonColumns;
        this.algorithm = algorithm;
        this.blockLimit = blockLimit;
    }

    /** @return jenis join
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public JoinType getJoinType() { return joinType; }

    /** @return kondisi join, atau null
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public ExpressionNode getCondition() { return condition; }

    /** @return kolom bersama, atau null
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public List<String> getCommonColumns() { return commonColumns; }

    /** @param commonColumns kolom bersama hasil binding
     * @calls tidak ada
     * @calledBy QO3
     */
    public void setCommonColumns(List<String> commonColumns) { this.commonColumns = commonColumns; }

    /** @return algoritma join, atau null sebelum pemilihan
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public JoinAlgorithm getAlgorithm() { return algorithm; }

    /** @param algorithm algoritma join yang dipilih
     * @calls tidak ada
     * @calledBy QO5
     */
    public void setAlgorithm(JoinAlgorithm algorithm) { this.algorithm = algorithm; }

    /** @return jumlah baris outer per blok buffer
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public int getBlockLimit() { return blockLimit; }

    /** @param blockLimit jumlah baris outer per blok buffer
     * @calls tidak ada
     * @calledBy QO5
     */
    public void setBlockLimit(int blockLimit) { this.blockLimit = blockLimit; }
}
