package monggodb.contracts.shared;

/**
 * Merepresentasikan operasi biner dalam ekspresi SQL. Mendukung operasi aritmatika, perbandingan, dan logika AND.
 */
public class BinaryExpr extends ExpressionNode {

    /**
     * Jenis operator biner yang didukung.
     */
    public enum BinaryOp {
        ADD,
        SUB,
        MUL,
        DIV,
        EQ,
        NEQ,
        LT,
        LTE,
        GT,
        GTE,
        AND
    }

    /** Operator biner yang digunakan dalam ekspresi. */
    private final BinaryOp op;

    /** Operand sebelah kiri. */
    private final ExpressionNode left;

    /** Operand sebelah kanan. */
    private final ExpressionNode right;

    /**
     * Membuat node ekspresi biner.
     *
     * @param op operator biner
     * @param left operand kiri
     * @param right operand kanan
     */
    public BinaryExpr(BinaryOp op, ExpressionNode left,
                      ExpressionNode right) {
        super(ExprKind.BINARY_OP);
        this.op = op;
        this.left = left;
        this.right = right;
    }

    /**
     * Mengembalikan operator biner.
     *
     * @return operator biner
     */
    public BinaryOp getOp() {
        return op;
    }

    /**
     * Mengembalikan operand kiri.
     *
     * @return ekspresi operand kiri
     */
    public ExpressionNode getLeft() {
        return left;
    }

    /**
     * Mengembalikan operand kanan.
     *
     * @return ekspresi operand kanan
     */
    public ExpressionNode getRight() {
        return right;
    }
}