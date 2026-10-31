package monggodb.contracts.shared;

/** Node pembacaan seluruh tabel. */
public class ScanNode extends PlanNode {
    /** Nama tabel sumber. */
    private final String table;
    /** Alias tabel, atau null jika tidak ada. */
    private final String alias;

    /** @param table nama tabel
     * @param alias alias tabel, atau null
     */
    public ScanNode(String table, String alias) {
        super(PlanNodeType.SCAN);
        this.table = table;
        this.alias = alias;
    }

    /** @return nama tabel
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public String getTable() { return table; }

    /** @return alias tabel, atau null
     * @calls tidak ada
     * @calledBy QO dan QP2
     */
    public String getAlias() { return alias; }
}
