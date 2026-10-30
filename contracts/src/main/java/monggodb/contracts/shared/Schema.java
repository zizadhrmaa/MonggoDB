package monggodb.contracts.shared;

import java.util.List;
import java.util.Objects;

/**
 * Struktur satu tabel. Urutan kolom di sini sama dengan urutan definisi tabel,
 * dan menentukan urutan nilai di Row serta layout byte record.
 * Pencarian kolom lewat indexOf dan getColumn tidak membedakan huruf besar dan kecil.
 */
public final class Schema {

    /** Nama tabel. */
    private final String tableName;

    /** Daftar kolom sesuai urutan definisi tabel. */
    private final List<Column> columns;

    /**
     * @param tableName nama tabel
     * @param columns   daftar kolom sesuai urutan definisi tabel
     */
    public Schema(String tableName, List<Column> columns) {
        this.tableName = Objects.requireNonNull(tableName, "tableName tidak boleh null");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns tidak boleh null"));
    }

    /** @return nama tabel */
    public String getTableName() {
        return tableName;
    }

    /** @return daftar kolom (tidak bisa diubah) */
    public List<Column> getColumns() {
        return columns;
    }

    /**
     * @param name nama kolom
     * @return posisi kolom mulai dari 0, atau -1 jika tidak ada
     */
    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param name nama kolom
     * @return kolom dengan nama tersebut, atau null jika tidak ada
     */
    public Column getColumn(String name) {
        int idx = indexOf(name);
        return idx == -1 ? null : columns.get(idx);
    }

    /** @return jumlah kolom */
    public int size() {
        return columns.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Schema)) {
            return false;
        }
        Schema other = (Schema) o;
        return tableName.equalsIgnoreCase(other.tableName) && columns.equals(other.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName.toLowerCase(), columns);
    }

    @Override
    public String toString() {
        return tableName + columns;
    }
}
