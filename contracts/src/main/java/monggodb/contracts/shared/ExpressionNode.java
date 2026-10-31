package monggodb.contracts.shared;

/**
 * Struktur dasar untuk merepresentasikan ekspresi SQL yang digunakan pada WHERE, JOIN ON, dan SET.
 */
public abstract class ExpressionNode {

    /* Jenis ekspresi yang didukung Query Optimizer. */
    public enum ExprKind {
        COLUMN_REF,
        LITERAL,
        BINARY_OP,
        IS_NULL,
        SUBQUERY
    }

    /** Jenis ekspresi yang direpresentasikan oleh node. */
    private final ExprKind kind;

    /** Tipe hasil ekspresi yang ditentukan oleh binder QO3. */
    private DataType resultType;

    /**
     * Membuat node ekspresi dengan jenis tertentu.
     *
     * @param kind jenis ekspresi
     */
    protected ExpressionNode(ExprKind kind) {
        this.kind = kind;
        this.resultType = null;
    }

    /**
     * Mengembalikan jenis ekspresi.
     *
     * @return jenis ekspresi
     */
    public ExprKind getKind() {
        return kind;
    }

    /**
     * Mengembalikan tipe hasil ekspresi.
     *
     * @return tipe hasil ekspresi, atau null jika belum dibinding
     */
    public DataType getResultType() {
        return resultType;
    }

    /**
     * Menetapkan tipe hasil ekspresi setelah validasi semantik.
     *
     * @param resultType tipe hasil ekspresi
     */
    public void setResultType(DataType resultType) {
        this.resultType = resultType;
    }
}
