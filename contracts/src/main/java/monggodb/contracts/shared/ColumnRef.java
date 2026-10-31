package monggodb.contracts.shared;

/**
 * Merepresentasikan referensi kolom dalam ekspresi SQL. Digunakan pada SELECT, WHERE, JOIN ON, ORDER BY, dan SET.
 */
public class ColumnRef extends ExpressionNode {

    /** Nama tabel atau alias, ditentukan setelah binding. */
    private String table;

    /** Nama kolom yang direferensikan. */
    private final String column;

    /** Menandakan referensi kolom berasal dari query luar. */
    private boolean outer;

    /** Tipe data kolom yang ditentukan oleh binder. */
    private DataType dataType;

    /**
     * Membuat referensi kolom yang belum dibinding.
     *
     * @param table nama tabel atau alias, boleh null
     * @param column nama kolom
     */
    public ColumnRef(String table, String column) {
        super(ExprKind.COLUMN_REF);
        this.table = table;
        this.column = column;
        this.outer = false;
        this.dataType = null;
    }

    /** @return nama tabel atau alias */
    public String getTable() {
        return table;
    }

    /** @return nama kolom */
    public String getColumn() {
        return column;
    }

    /** @return true jika merujuk kolom dari query luar */
    public boolean isOuter() {
        return outer;
    }

    /** @return tipe data kolom, atau null sebelum binding */
    public DataType getDataType() {
        return dataType;
    }

    /**
     * Menetapkan informasi kolom setelah validasi semantik.
     *
     * @param table nama tabel atau alias hasil resolusi
     * @param outer apakah kolom berasal dari query luar
     * @param dataType tipe data kolom
     */
    public void bind(String table, boolean outer, DataType dataType) {
        this.table = table;
        this.outer = outer;
        this.dataType = dataType;
        setResultType(dataType);
    }
}