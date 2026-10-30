package monggodb.contracts.shared;

import java.util.List;
import java.util.Objects;

/**
 * Isi satu record: nilai-nilai atribut (urut sesuai kolom di Schema) dan RID tempat record disimpan.
 * Atribut NULL disimpan sebagai Value dengan isNull = true, bukan null di list.
 * Row immutable, jadi before-image bisa disimpan CCM/FRM tanpa perlu disalin.
 */
public final class Row {

    /** Alamat record. Null untuk record baru yang belum disimpan (sebelum allocateRid). */
    private final RID rid;

    /** Nilai atribut sesuai urutan kolom di Schema. */
    private final List<Value> values;

    /**
     * @param rid    alamat record, boleh null untuk record baru
     * @param values nilai atribut, tidak boleh null dan tidak boleh berisi null
     */
    public Row(RID rid, List<Value> values) {
        Objects.requireNonNull(values, "values tidak boleh null");
        this.rid = rid;
        this.values = List.copyOf(values);
    }

    /** @return RID record, atau null jika belum disimpan */
    public RID getRid() {
        return rid;
    }

    /** @return list nilai atribut (tidak bisa diubah) */
    public List<Value> getValues() {
        return values;
    }

    /**
     * @param index posisi kolom di Schema, mulai dari 0
     * @return nilai pada kolom tersebut
     */
    public Value getValue(int index) {
        return values.get(index);
    }

    /** @return jumlah atribut */
    public int size() {
        return values.size();
    }

    /**
     * Membuat Row baru dengan nilai yang sama tapi RID berbeda.
     * Dipakai setelah allocateRid untuk memasang RID ke record baru.
     *
     * @param newRid RID baru
     * @return Row baru, Row lama tidak berubah
     */
    public Row withRid(RID newRid) {
        return new Row(newRid, values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Row)) {
            return false;
        }
        Row other = (Row) o;
        return Objects.equals(rid, other.rid) && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rid, values);
    }

    @Override
    public String toString() {
        return "Row" + (rid == null ? "" : rid) + values;
    }
}
