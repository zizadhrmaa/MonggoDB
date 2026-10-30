package monggodb.contracts.shared;

/**
 * Merepresentasikan nilai literal dalam ekspresi SQL.
 * Literal dapat berupa INTEGER, FLOAT, CHAR, VARCHAR, atau NULL.
 */
public class LiteralExpr extends ExpressionNode {

    /** Nilai literal yang direpresentasikan oleh ekspresi. */
    private final Value value;

    /**
     * Membuat node ekspresi literal.
     *
     * @param value nilai literal SQL
     */
    public LiteralExpr(Value value) {
        super(ExprKind.LITERAL);
        this.value = value;
    }

    /**
     * Mengembalikan nilai literal.
     *
     * @return nilai literal
     */
    public Value getValue() {
        return value;
    }
}