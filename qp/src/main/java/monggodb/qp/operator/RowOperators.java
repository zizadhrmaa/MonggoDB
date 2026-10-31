package monggodb.qp.operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.ColumnRef;
import monggodb.contracts.shared.QPException;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.qp.execution.ExecutionOperator;
import monggodb.qp.execution.ResultColumn;
import monggodb.qp.execution.ResultSchema;
import monggodb.qp.execution.Tuple;

/**
 * Pembentuk operator baris (Table Scan dan Project) dari node plan, beserta fungsi bantu skema
 * yang dipakai bersama: penyusunan skema tabel dan Project, pencarian posisi kolom, dan konversi Row ke Tuple.
 * Nama tabel, alias, dan kolom dibandingkan tanpa membedakan huruf besar dan kecil.
 */
public final class RowOperators {

    private RowOperators() {
    }

    /**
     * Membentuk operator Table Scan dari node Scan.
     *
     * @param tableName nama tabel
     * @param alias     alias tabel, atau null jika tidak ada
     * @return operator Table Scan yang belum dibuka
     * @throws QPException INVALID_PLAN jika nama tabel kosong
     */
    public static ScanOperator createScanOperator(String tableName, String alias) {
        return new ScanOperator(tableName, alias);
    }

    /**
     * Membentuk operator Project dari node Project. Skema keluaran disusun saat open,
     * setelah skema child tersedia.
     *
     * @param child   operator sumber baris
     * @param columns kolom keluaran sesuai urutan SELECT
     * @return operator Project yang belum dibuka
     * @throws QPException INVALID_PLAN jika child kosong atau daftar kolom kosong
     */
    public static ProjectOperator createProjectOperator(ExecutionOperator child, List<ColumnRef> columns) {
        return new ProjectOperator(child, columns);
    }

    /**
     * Menyusun skema keluaran Table Scan dari skema tabel di SM. Urutan kolom sama dengan definisi tabel.
     * Qualifier utama adalah alias jika ada, lalu nama tabel, sehingga kolom bisa dirujuk dengan keduanya.
     *
     * @param schema skema tabel dari SM
     * @param alias  alias tabel, atau null
     * @return skema keluaran dengan kolom berbentuk tabel.kolom atau alias.kolom
     */
    public static ResultSchema buildTableSchema(Schema schema, String alias) {
        List<String> qualifiers = new ArrayList<>();
        if (alias != null) {
            qualifiers.add(alias);
        }
        qualifiers.add(schema.getTableName());
        List<ResultColumn> columns = new ArrayList<>(schema.size());
        for (Column column : schema.getColumns()) {
            columns.add(new ResultColumn(column.getName(), column.getDataType(), qualifiers));
        }
        return new ResultSchema(columns);
    }

    /**
     * Menyusun skema keluaran Project sesuai urutan kolom SELECT. Nama, tipe data, dan qualifier
     * setiap kolom dibawa dari skema asal.
     *
     * @param inputSchema skema keluaran child
     * @param columns     kolom keluaran sesuai urutan SELECT
     * @return skema keluaran Project
     * @throws QPException COLUMN_NOT_FOUND atau AMBIGUOUS_COLUMN jika ada kolom yang tidak bisa ditentukan
     */
    public static ResultSchema buildProjectedSchema(ResultSchema inputSchema, List<ColumnRef> columns) {
        List<ResultColumn> projected = new ArrayList<>(columns.size());
        for (ColumnRef ref : columns) {
            projected.add(inputSchema.getColumn(resolveColumnIndex(inputSchema, ref)));
        }
        return new ResultSchema(projected);
    }

    /**
     * Mencari posisi kolom pada skema. Tanpa prefix, nama kolom harus cocok dengan tepat satu kolom.
     * Dengan prefix, kolom cocok bila salah satu qualifier-nya sama dengan prefix, sehingga kolom bersama
     * hasil NATURAL JOIN bisa dirujuk dengan prefix tabel mana pun.
     *
     * @param schema skema yang dicari
     * @param ref    referensi kolom, boleh dengan atau tanpa prefix tabel
     * @return posisi kolom, mulai dari 0
     * @throws QPException COLUMN_NOT_FOUND jika tidak ada yang cocok, AMBIGUOUS_COLUMN jika lebih dari satu yang cocok,
     *                     atau INVALID_PLAN jika nama kolom kosong
     */
    public static int resolveColumnIndex(ResultSchema schema, ColumnRef ref) {
        if (ref.getColumn() == null || ref.getColumn().isBlank()) {
            throw new QPException(QPException.Code.INVALID_PLAN, "Referensi kolom tidak memiliki nama kolom");
        }
        String name = ref.getColumn().toLowerCase(Locale.ROOT);
        String qualifier = ref.getTable();
        String label = qualifier == null ? name : qualifier.toLowerCase(Locale.ROOT) + "." + name;
        int found = -1;
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            ResultColumn column = schema.getColumn(i);
            if (column.getName().equals(name) && (qualifier == null || column.hasQualifier(qualifier))) {
                found = i;
                matches.add(column.getLabel());
            }
        }
        if (matches.isEmpty()) {
            throw new QPException(QPException.Code.COLUMN_NOT_FOUND, "Kolom " + label + " tidak ditemukan");
        }
        if (matches.size() > 1) {
            throw new QPException(QPException.Code.AMBIGUOUS_COLUMN,
                    "Kolom " + label + " ambigu, cocok dengan " + String.join(", ", matches));
        }
        return found;
    }

    /**
     * Mengubah Row dari SM menjadi Tuple dengan nilai yang sama, skema tabel, dan RID asal.
     *
     * @param row       record dari SM
     * @param schema    skema keluaran operator yang membaca tabel
     * @param tableName nama tabel asal record
     * @return Tuple yang membawa RID record
     * @throws IllegalStateException jika jumlah nilai record tidak sesuai skema
     */
    public static Tuple toTuple(Row row, ResultSchema schema, String tableName) {
        if (row.size() != schema.size()) {
            throw new IllegalStateException("Jumlah nilai record tabel " + tableName + " (" + row.size()
                    + ") tidak sesuai jumlah kolom skema (" + schema.size() + ")");
        }
        return new Tuple(row.getValues(), schema, row.getRid());
    }
}
