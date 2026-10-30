package monggodb.contracts.shared;

import java.util.List;

/**
 * Merepresentasikan perintah INSERT untuk menambahkan satu baris data ke dalam tabel.
 *
 * Nilai disimpan sesuai urutan kolom tabel dan tipe datanya disesuaikan oleh binder.
 */
public class InsertNode extends PlanNode {

    /** Nama tabel tujuan INSERT. */
    private final String table;

    /** Daftar nilai yang akan dimasukkan sesuai urutan kolom tabel. */
    private final List<Value> values;

    /**
     * Membuat node perintah INSERT.
     *
     * @param table nama tabel tujuan
     * @param values daftar nilai yang akan dimasukkan
     */
    public InsertNode(String table, List<Value> values) {
        super(PlanNodeType.INSERT);
        this.table = table;
        this.values = values;
    }

    /**
     * Mengembalikan nama tabel tujuan.
     *
     * @return nama tabel
     */
    public String getTable() {
        return table;
    }

    /**
     * Mengembalikan daftar nilai INSERT.
     *
     * @return daftar nilai sesuai urutan kolom tabel
     */
    public List<Value> getValues() {
        return values;
    }
}