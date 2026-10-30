package monggodb.contracts.shared;

import java.util.Objects;

/**
 * Definisi satu kolom tabel. Dipakai SM untuk validasi dan encoding nilai,
 * dan dipakai QO untuk validasi query.
 * primaryKey dan references hanya disimpan, tidak ditegakkan saat INSERT, UPDATE, atau DELETE.
 */
public final class Column {

    /** Nama kolom. */
    private final String name;

    /** Nama tabel pemilik kolom. */
    private final String table;

    /** Tipe data kolom. */
    private final DataType dataType;

    /** Panjang maksimum untuk CHAR dan VARCHAR, 0 untuk tipe lain. */
    private final int length;

    /** True jika kolom ini primary key. */
    private final boolean primaryKey;

    /** True jika kolom tidak boleh NULL. Kolom primary key otomatis notNull. */
    private final boolean notNull;

    /** Kolom yang dirujuk jika kolom ini foreign key, null jika bukan. */
    private final ForeignKeyRef references;

    /**
     * @param name       nama kolom
     * @param table      nama tabel pemilik kolom
     * @param dataType   tipe data kolom
     * @param length     panjang untuk CHAR/VARCHAR, 0 untuk INTEGER dan FLOAT
     * @param primaryKey true jika primary key
     * @param notNull    true jika tidak boleh NULL
     * @param references foreign key yang dirujuk, boleh null
     */
    public Column(String name, String table, DataType dataType, int length,
                  boolean primaryKey, boolean notNull, ForeignKeyRef references) {
        this.name = Objects.requireNonNull(name, "name tidak boleh null");
        this.table = Objects.requireNonNull(table, "table tidak boleh null");
        this.dataType = Objects.requireNonNull(dataType, "dataType tidak boleh null");
        if (length < 0) {
            throw new IllegalArgumentException("length tidak boleh negatif: " + length);
        }
        this.length = length;
        this.primaryKey = primaryKey;
        this.notNull = notNull || primaryKey;
        this.references = references;
    }

    /** @return nama kolom */
    public String getName() {
        return name;
    }

    /** @return nama tabel pemilik kolom */
    public String getTable() {
        return table;
    }

    /** @return tipe data kolom */
    public DataType getDataType() {
        return dataType;
    }

    /** @return panjang CHAR/VARCHAR, atau 0 untuk tipe lain */
    public int getLength() {
        return length;
    }

    /** @return true jika primary key */
    public boolean isPrimaryKey() {
        return primaryKey;
    }

    /** @return true jika kolom tidak boleh NULL */
    public boolean isNotNull() {
        return notNull;
    }

    /** @return foreign key yang dirujuk, atau null jika bukan foreign key */
    public ForeignKeyRef getReferences() {
        return references;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Column)) {
            return false;
        }
        Column other = (Column) o;
        return name.equalsIgnoreCase(other.name)
                && table.equalsIgnoreCase(other.table)
                && dataType == other.dataType
                && length == other.length
                && primaryKey == other.primaryKey
                && notNull == other.notNull
                && Objects.equals(references, other.references);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name.toLowerCase(), table.toLowerCase(), dataType, length, primaryKey, notNull, references);
    }

    @Override
    public String toString() {
        String type = length > 0 ? dataType + "(" + length + ")" : dataType.toString();
        return table + "." + name + " " + type;
    }
}
