package monggodb.contracts.shared;

/**
 * Merepresentasikan kondisi IS NULL atau IS NOT NULL dalam ekspresi SQL.
 */
public class IsNullExpr extends ExpressionNode {

    /** Ekspresi yang akan diperiksa apakah bernilai NULL. */
    private final ExpressionNode operand;

    /** True untuk IS NOT NULL, false untuk IS NULL. */
    private final boolean negated;

    /**
     * Membuat node ekspresi pemeriksaan NULL.
     *
     * @param operand ekspresi yang diperiksa
     * @param negated true untuk IS NOT NULL, false untuk IS NULL
     */
    public IsNullExpr(ExpressionNode operand, boolean negated) {
        super(ExprKind.IS_NULL);
        this.operand = operand;
        this.negated = negated;
    }

    /**
     * Mengembalikan ekspresi yang diperiksa.
     *
     * @return ekspresi operand
     */
    public ExpressionNode getOperand() {
        return operand;
    }

    /**
     * Mengembalikan apakah kondisi menggunakan IS NOT NULL.
     *
     * @return true jika IS NOT NULL, false jika IS NULL
     */
    public boolean isNegated() {
        return negated;
    }
}