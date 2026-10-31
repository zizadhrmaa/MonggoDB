package monggodb.sm.catalog;

import java.util.List;
import java.util.Map;

import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.TableStats;

/**
 * Struktur pembawa data katalog dalam memori sebelum atau sesudah serialisasi format JSON.
 * Digunakan oleh parser SM4 untuk memindahkan representasi objek katalog secara utuh.
 *
 * @param tables  daftar skema tabel yang terdaftar
 * @param indexes daftar index yang terdaftar
 * @param stats   pemetaan nama tabel ke statistik tabel
 */
public record CatalogData(
        List<Schema> tables,
        List<IndexInfo> indexes,
        Map<String, TableStats> stats) {
}

