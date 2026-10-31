package monggodb.qp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import monggodb.contracts.shared.DataType;

/**
 * Satu kolom pada skema hasil operator: nama kolom, tipe data, dan qualifier (nama tabel atau alias)
 * yang boleh dipakai untuk merujuknya. Nama dan qualifier disimpan dalam huruf kecil.
 * Qualifier pertama dipakai sebagai prefix pada header hasil.
 */
public final class ResultColumn {

    /** Nama kolom dalam huruf kecil. */
    private final String name;

    /** Tipe data nilai pada kolom ini. */
    private final DataType dataType;

    /** Qualifier kolom dalam huruf kecil tanpa duplikat, qualifier utama di posisi pertama. */
    private final List<String> qualifiers;

    /**
     * @param name       nama kolom
     * @param dataType   tipe data kolom
     * @param qualifiers nama tabel atau alias yang boleh dipakai sebagai prefix, boleh kosong
     */
    public ResultColumn(String name, DataType dataType, List<String> qualifiers) {
        this.name = Objects.requireNonNull(name, "name tidak boleh null").toLowerCase(Locale.ROOT);
        this.dataType = Objects.requireNonNull(dataType, "dataType tidak boleh null");
        List<String> normalized = new ArrayList<>();
        for (String qualifier : Objects.requireNonNull(qualifiers, "qualifiers tidak boleh null")) {
            String lower = Objects.requireNonNull(qualifier, "qualifier tidak boleh null").toLowerCase(Locale.ROOT);
            if (!normalized.contains(lower)) {
                normalized.add(lower);
            }
        }
        this.qualifiers = List.copyOf(normalized);
    }

    /** @return nama kolom dalam huruf kecil */
    public String getName() {
        return name;
    }

    /** @return tipe data kolom */
    public DataType getDataType() {
        return dataType;
    }

    /** @return semua qualifier kolom (tidak bisa diubah) */
    public List<String> getQualifiers() {
        return qualifiers;
    }

    /** @return qualifier utama untuk header, atau null jika kolom tidak punya qualifier */
    public String getQualifier() {
        return qualifiers.isEmpty() ? null : qualifiers.get(0);
    }

    /**
     * @param qualifier nama tabel atau alias
     * @return true jika qualifier tersebut boleh dipakai untuk merujuk kolom ini, tanpa membedakan huruf besar dan kecil
     */
    public boolean hasQualifier(String qualifier) {
        return qualifier != null && qualifiers.contains(qualifier.toLowerCase(Locale.ROOT));
    }

    /** @return nama kolom untuk header, berbentuk qualifier.kolom atau kolom saja jika tanpa qualifier */
    public String getLabel() {
        String qualifier = getQualifier();
        return qualifier == null ? name : qualifier + "." + name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResultColumn)) {
            return false;
        }
        ResultColumn other = (ResultColumn) o;
        return name.equals(other.name) && dataType == other.dataType && qualifiers.equals(other.qualifiers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, dataType, qualifiers);
    }

    @Override
    public String toString() {
        return getLabel() + " " + dataType;
    }
}
