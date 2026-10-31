package monggodb.sm.catalog;

import java.nio.file.Path;
import java.util.List;

import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TableStats;

/**
 * Kontrak internal pengelola katalog dan metadata tabel untuk komponen Storage Manager (SM4).
 * Menyediakan operasi persistensi berkas katalog ke disk serta manipulasi metadata tabel, index, dan statistik di memori.
 */
public interface CatalogManager {

    /**
     * Membaca berkas katalog dari folder data ke memori sekali saat startup.
     *
     * @param dataDir folder data tempat berkas katalog disimpan
     * @throws StorageException jika berkas katalog gagal dibaca atau format tidak valid
     * @calls parser format katalog milik SM4
     * @calledBy startup
     */
    void loadCatalog(Path dataDir) throws StorageException;

    /**
     * Menulis katalog di memori ke disk secara atomik (tulis berkas sementara, lalu rename).
     *
     * @throws StorageException jika berkas katalog gagal ditulis ke disk
     * @calls tidak ada
     * @calledBy registerTable, registerIndex, shutdown, createSnapshot
     */
    void saveCatalog() throws StorageException;

    /**
     * Mendaftarkan skema tabel baru ke katalog dan menyimpannya secara atomik ke disk.
     *
     * @param schema skema tabel baru yang akan didaftarkan
     * @throws StorageException TABLE_ALREADY_EXISTS jika tabel sudah ada,
     *                          atau TABLE_NOT_FOUND jika tabel foreign key yang dirujuk tidak ada
     * @calls saveCatalog
     * @calledBy SM1 (createTable)
     */
    void registerTable(Schema schema) throws StorageException;

    /**
     * Menghapus tabel beserta index dan statistiknya dari katalog.
     *
     * @param table   nama tabel yang akan dihapus
     * @param cascade true jika tabel yang merujuk ikut dihapus, false jika menolak saat masih dirujuk
     * @return daftar nama semua tabel yang terhapus
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada,
     *                          atau TABLE_REFERENCED jika masih dirujuk foreign key dan cascade bernilai false
     * @calls saveCatalog
     * @calledBy SM1 (dropTable)
     */
    List<String> dropTable(String table, boolean cascade) throws StorageException;

    /**
     * Mengambil daftar nama seluruh tabel yang terdaftar di katalog.
     *
     * @return daftar nama tabel
     * @calls tidak ada
     * @calledBy SM1 (listTables)
     */
    List<String> listTables();

    /**
     * Mengambil skema tabel berdasarkan nama tabel.
     *
     * @param table nama tabel
     * @return skema tabel
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ditemukan
     * @calls tidak ada
     * @calledBy SM1 (getTableSchema)
     */
    Schema getTableSchema(String table) throws StorageException;

    /**
     * Memeriksa apakah suatu tabel terdaftar di dalam katalog.
     *
     * @param table nama tabel yang diperiksa
     * @return true jika tabel terdaftar, false jika tidak
     * @calls tidak ada
     * @calledBy SM1
     */
    boolean hasTable(String table);

    /**
     * Mendaftarkan metadata index baru ke katalog dan menyimpannya ke disk.
     *
     * @param indexInfo metadata index yang akan didaftarkan
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada,
     *                          COLUMN_NOT_FOUND jika kolom tidak ada,
     *                          atau INDEX_ALREADY_EXISTS jika nama index sudah terdaftar
     * @calls saveCatalog
     * @calledBy SM1 (createIndex)
     */
    void registerIndex(IndexInfo indexInfo) throws StorageException;

    /**
     * Menghapus metadata index dari katalog dan memperbarui disk.
     *
     * @param table     nama tabel pemilik index
     * @param indexName nama index yang akan dihapus
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada,
     *                          atau INDEX_NOT_FOUND jika index tidak ada
     * @calls saveCatalog
     * @calledBy SM1 (dropIndex)
     */
    void dropIndex(String table, String indexName) throws StorageException;

    /**
     * Mengambil semua index yang terdaftar pada tabel tertentu.
     *
     * @param table nama tabel
     * @return daftar index pada tabel tersebut
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calls tidak ada
     * @calledBy SM1 (getIndexes)
     */
    List<IndexInfo> getIndexes(String table) throws StorageException;

    /**
     * Mengambil statistik terkini dari tabel tertentu.
     *
     * @param table nama tabel
     * @return statistik tabel
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calls tidak ada
     * @calledBy SM1 (getStats)
     */
    TableStats getStats(String table) throws StorageException;

    /**
     * Memperbarui informasi statistik suatu tabel di katalog.
     *
     * @param table nama tabel
     * @param stats statistik tabel yang baru
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calls tidak ada
     * @calledBy SM1 (recomputeStats)
     */
    void updateStats(String table, TableStats stats) throws StorageException;
}

