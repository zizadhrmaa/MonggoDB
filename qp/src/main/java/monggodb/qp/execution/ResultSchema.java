package monggodb.qp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Skema keluaran sebuah operator: daftar kolom sesuai urutan nilai di Tuple.
 * Skema tetap tersedia walaupun operator menghasilkan 0 baris, sehingga header hasil selalu bisa ditampilkan.
 */
public final class ResultSchema {

    /** Kolom keluaran sesuai urutan nilai di Tuple. */
    private final List<ResultColumn> columns;

    /**
     * @param columns kolom keluaran sesuai urutan, tidak boleh null dan tidak boleh berisi null
     */
    public ResultSchema(List<ResultColumn> columns) {
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns tidak boleh null"));
    }

    /** @return daftar kolom (tidak bisa diubah) */
    public List<ResultColumn> getColumns() {
        return columns;
    }

    /**
     * @param index posisi kolom, mulai dari 0
     * @return kolom pada posisi tersebut
     */
    public ResultColumn getColumn(int index) {
        return columns.get(index);
    }

    /** @return jumlah kolom */
    public int size() {
        return columns.size();
    }

    /** @return label header setiap kolom sesuai urutan, berbentuk qualifier.kolom */
    public List<String> getLabels() {
        List<String> labels = new ArrayList<>(columns.size());
        for (ResultColumn column : columns) {
            labels.add(column.getLabel());
        }
        return List.copyOf(labels);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResultSchema)) {
            return false;
        }
        return columns.equals(((ResultSchema) o).columns);
    }

    @Override
    public int hashCode() {
        return columns.hashCode();
    }

    @Override
    public String toString() {
        return columns.toString();
    }
}
