package monggodb.contracts.shared;

import java.util.Objects;

/**
 * Tabel dan kolom yang dirujuk oleh sebuah foreign key.
 * Hanya disimpan di katalog, tidak dicek saat INSERT, UPDATE, atau DELETE.
 * Dipakai untuk DROP TABLE CASCADE/RESTRICT dan tampilan skema.
 */
public final class ForeignKeyRef {

    /** Nama tabel yang dirujuk. */
    private final String table;

    /** Nama kolom yang dirujuk. */
    private final String column;

    /**
     * @param table  nama tabel yang dirujuk
     * @param column nama kolom yang dirujuk
     */
    public ForeignKeyRef(String table, String column) {
        this.table = Objects.requireNonNull(table, "table tidak boleh null");
        this.column = Objects.requireNonNull(column, "column tidak boleh null");
    }

    /** @return nama tabel yang dirujuk */
    public String getTable() {
        return table;
    }

    /** @return nama kolom yang dirujuk */
    public String getColumn() {
        return column;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ForeignKeyRef)) {
            return false;
        }
        ForeignKeyRef other = (ForeignKeyRef) o;
        return table.equalsIgnoreCase(other.table) && column.equalsIgnoreCase(other.column);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table.toLowerCase(), column.toLowerCase());
    }

    @Override
    public String toString() {
        return table + "(" + column + ")";
    }
}
