package monggodb.qp.execution;

import java.util.List;
import java.util.Objects;

import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.Value;

/**
 * Satu baris yang diteruskan antaroperator: nilai-nilai kolom sesuai urutan skema keluaran,
 * skema itu sendiri, dan RID record asal di SM.
 * Nilai NULL disimpan sebagai Value dengan isNull = true. Tuple immutable.
 */
public final class Tuple {

    /** Nilai kolom sesuai urutan kolom di skema. */
    private final List<Value> values;

    /** Skema yang menjelaskan nilai-nilai Tuple. */
    private final ResultSchema schema;

    /** RID record asal, atau null jika Tuple tidak berasal dari satu record. */
    private final RID rid;

    /**
     * @param values nilai kolom, jumlahnya harus sama dengan jumlah kolom skema dan tidak boleh berisi null
     * @param schema skema keluaran operator
     * @param rid    RID record asal, boleh null
     * @throws IllegalArgumentException jika jumlah nilai tidak sama dengan jumlah kolom skema
     */
    public Tuple(List<Value> values, ResultSchema schema, RID rid) {
        Objects.requireNonNull(values, "values tidak boleh null");
        this.schema = Objects.requireNonNull(schema, "schema tidak boleh null");
        if (values.size() != schema.size()) {
            throw new IllegalArgumentException("Jumlah nilai " + values.size()
                    + " tidak sesuai jumlah kolom " + schema.size());
        }
        this.values = List.copyOf(values);
        this.rid = rid;
    }

    /** @return nilai kolom (tidak bisa diubah) */
    public List<Value> getValues() {
        return values;
    }

    /**
     * @param index posisi kolom di skema, mulai dari 0
     * @return nilai pada kolom tersebut
     */
    public Value getValue(int index) {
        return values.get(index);
    }

    /** @return skema Tuple */
    public ResultSchema getSchema() {
        return schema;
    }

    /** @return RID record asal, atau null */
    public RID getRid() {
        return rid;
    }

    /** @return jumlah nilai */
    public int size() {
        return values.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Tuple)) {
            return false;
        }
        Tuple other = (Tuple) o;
        return values.equals(other.values) && schema.equals(other.schema) && Objects.equals(rid, other.rid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, schema, rid);
    }

    @Override
    public String toString() {
        return "Tuple" + (rid == null ? "" : rid) + values;
    }
}
