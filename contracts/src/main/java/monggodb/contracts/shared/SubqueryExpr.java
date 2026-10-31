package monggodb.contracts.shared;

/**
 * Merepresentasikan kondisi IN atau EXISTS yang menggunakan subquery dalam ekspresi SQL.
 * Mendukung subquery biasa maupun correlated subquery.
 */
public class SubqueryExpr extends ExpressionNode {

    /** Jenis kondisi subquery yang didukung. */
    public enum SubqueryKind {
        IN,
        EXISTS
    }

    /** Jenis kondisi subquery, yaitu IN atau EXISTS. */
    private final SubqueryKind kind;

    /** Ekspresi pembanding, hanya digunakan untuk IN. */
    private final ExpressionNode operand;

    /** Query yang dijalankan sebagai subquery. */
    private final ParsedQuery subquery;

    /** True jika subquery merujuk kolom dari query luar. */
    private boolean correlated;

    /**
     * Membuat node ekspresi subquery.
     *
     * @param kind jenis kondisi subquery
     * @param operand ekspresi pembanding untuk IN,
     *                null untuk EXISTS
     * @param subquery query yang digunakan sebagai subquery
     * @param correlated true jika subquery merujuk query luar
     */
    public SubqueryExpr(SubqueryKind kind, ExpressionNode operand, ParsedQuery subquery, boolean correlated) {
        super(ExprKind.SUBQUERY);
        this.kind = kind;
        this.operand = operand;
        this.subquery = subquery;
        this.correlated = correlated;
    }

    /**
     * Mengembalikan jenis kondisi subquery.
     *
     * @return jenis subquery IN atau EXISTS
     */
    public SubqueryKind getSubqueryKind() {
        return kind;
    }

    /**
     * Mengembalikan ekspresi pembanding.
     *
     * @return operand untuk IN, null untuk EXISTS
     */
    public ExpressionNode getOperand() {
        return operand;
    }

    /**
     * Mengembalikan query yang menjadi subquery.
     *
     * @return ParsedQuery subquery
     */
    public ParsedQuery getSubquery() {
        return subquery;
    }

    /**
     * Memeriksa apakah subquery bersifat correlated.
     *
     * @return true jika correlated subquery
     */
    public boolean isCorrelated() {
        return correlated;
    }

    /**
     * Menetapkan status correlated subquery.
     *
     * @param correlated true jika subquery merujuk query luar
     */
    public void setCorrelated(boolean correlated) {
        this.correlated = correlated;
    }
}