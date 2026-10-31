package monggodb.contracts.shared;

/** Kolom dan arah pengurutan untuk ORDER BY. */
public class SortKey {
    /** Arah pengurutan. */
    public enum Direction { ASC, DESC }

    /** Kolom pengurutan. */
    private final ColumnRef column;
    /** Arah pengurutan. */
    private final Direction direction;

    /** @param column kolom pengurutan
     * @param direction arah pengurutan; parser menggunakan ASC jika tidak ditulis
     */
    public SortKey(ColumnRef column, Direction direction) {
        this.column = column;
        this.direction = direction;
    }

    /** @return kolom pengurutan
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public ColumnRef getColumn() { return column; }

    /** @return arah pengurutan
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public Direction getDirection() { return direction; }
}
