package monggodb.contracts.sm;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.RecordIterator;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TableStats;
import monggodb.contracts.shared.Value;

/**
 * Pintu masuk Storage Manager untuk komponen lain: baca tulis record, katalog, statistik, index,
 * snapshot, dan flush ke disk.
 * Nama tabel, kolom, dan index tidak case-sensitive. Untuk tabel yang tidak ada, semua metode
 * melempar StorageException(TABLE_NOT_FOUND).
 */
public interface StorageManager {

    // ---------- lifecycle ----------

    /**
     * Membuka folder data, memuat katalog, dan menyiapkan buffer.
     * Kalau folder masih kosong, basis data contoh dimuat.
     *
     * @param dataDir folder data
     * @throws StorageException jika folder atau katalog gagal dibuka
     * @calledBy app (startSystem QP1), pertama kali sebelum FRM.startup
     * @calls Tidak ada
     */
    void startup(Path dataDir) throws StorageException;

    /**
     * Menulis semua dirty page dan katalog ke disk, lalu menutup berkas.
     *
     * @throws StorageException jika gagal menulis ke disk
     * @calledBy app (shutdownSystem QP1), setelah CCM.shutdown dan sebelum FRM.shutdown
     * @calls FRM.flushLogUntil (lewat flush milik SM2)
     */
    void shutdown() throws StorageException;

    // ---------- baca dan tulis record ----------

    /**
     * Membaca semua record valid satu tabel secara bertahap per halaman.
     * Tabel kosong menghasilkan iterator kosong.
     *
     * @param table nama tabel
     * @return iterator Row beserta RID-nya, bisa di-reset untuk rescan BNLJ
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calledBy QP3 (Table Scan), QP4 (inner relation BNLJ)
     * @calls Tidak ada
     */
    RecordIterator scanTable(String table) throws StorageException;

    /**
     * Mengambil satu record berdasarkan RID.
     *
     * @param table nama tabel
     * @param rid   alamat record
     * @return record di RID tersebut, atau kosong jika slot kosong atau masih dipesan
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calledBy QP3 (Index Scan), QP3 dan QP5 (before-image DELETE dan UPDATE), FR3 dan FR5 (cek sebelum undo/redo)
     * @calls Tidak ada
     */
    Optional<Row> fetchRecord(String table, RID rid) throws StorageException;

    /**
     * Memesan slot untuk record baru. Slot ini belum terlihat oleh scan, fetch, maupun index
     * sampai diisi lewat insertRecordAt.
     *
     * @param table nama tabel
     * @param row   record baru, tanpa RID
     * @return RID slot yang dipesan
     * @throws StorageException TABLE_NOT_FOUND atau SCHEMA_MISMATCH
     * @calledBy QP5 (INSERT, sebelum CCM.requestWrite)
     * @calls Tidak ada
     */
    RID allocateRid(String table, Row row) throws StorageException;

    /**
     * Membatalkan pesanan slot yang tidak jadi diisi.
     *
     * @param table nama tabel
     * @param rid   RID yang dipesan lewat allocateRid
     * @calledBy QP5 (jika requestWrite menghasilkan ABORTED)
     * @calls Tidak ada
     */
    void releaseRid(String table, RID rid);

    /**
     * Menyimpan record di RID tertentu: mengisi slot pesanan, mengisi slot kosong, atau menimpa
     * slot yang sudah berisi. Aman dipanggil dua kali (idempoten). Index dan statistik ikut diperbarui.
     *
     * @param table nama tabel
     * @param rid   alamat tujuan
     * @param row   isi record
     * @param lsn   LSN catatan log perubahan ini, atau LSN.NONE jika tanpa log
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calledBy QP5 (INSERT setelah ALLOWED), FR3 (undo DELETE), FR5 (redo INSERT)
     * @calls Tidak ada
     */
    void insertRecordAt(String table, RID rid, Row row, LSN lsn) throws StorageException;

    /**
     * Mengganti seluruh isi record di RID tertentu. RID tidak berubah walaupun ukuran record berubah.
     * Index dan statistik ikut diperbarui.
     *
     * @param table  nama tabel
     * @param rid    alamat record
     * @param newRow isi record yang baru
     * @param lsn    LSN catatan log perubahan ini
     * @throws StorageException TABLE_NOT_FOUND, RECORD_NOT_FOUND, atau SCHEMA_MISMATCH
     * @calledBy QP5 (UPDATE), FR3 (undo UPDATE), FR5 (redo UPDATE)
     * @calls Tidak ada
     */
    void updateRecord(String table, RID rid, Row newRow, LSN lsn) throws StorageException;

    /**
     * Menghapus record berdasarkan RID, termasuk entrinya di semua index. Idempoten.
     *
     * @param table nama tabel
     * @param rid   alamat record
     * @param lsn   LSN catatan log perubahan ini
     * @return true jika record terhapus, false jika slot sudah kosong
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calledBy QP3 (DELETE), FR3 (undo INSERT), FR5 (redo DELETE)
     * @calls Tidak ada
     */
    boolean deleteRecord(String table, RID rid, LSN lsn) throws StorageException;

    /**
     * Menghapus semua record satu tabel sekaligus, termasuk isi index-nya.
     *
     * @param table nama tabel
     * @return jumlah record yang terhapus
     * @calledBy Driver SM
     * @calls Tidak ada
     */
    int clearTable(String table);

    // ---------- snapshot dan flush ----------

    /**
     * Menyalin semua berkas data, index, dan katalog ke folder snapshot setelah semua dirty page di-flush.
     *
     * @param snapshotName nama snapshot
     * @throws StorageException jika gagal menyalin berkas
     * @calledBy FR4 (createInitialSnapshot)
     * @calls FRM.flushLogUntil (lewat flush milik SM2)
     */
    void createSnapshot(String snapshotName) throws StorageException;

    /**
     * Mengganti semua data, index, dan katalog dengan isi snapshot, lalu mengosongkan buffer
     * dan memuat ulang katalog. Hanya dipanggil saat tidak ada transaksi aktif.
     *
     * @param snapshotName nama snapshot
     * @throws StorageException SNAPSHOT_NOT_FOUND jika snapshot tidak ada
     * @calledBy FR4 (restoreTo)
     * @calls Tidak ada
     */
    void loadSnapshot(String snapshotName) throws StorageException;

    /**
     * Menulis semua dirty page di buffer pool ke disk secara permanen.
     * Sebelum page ditulis, log sampai pageLSN-nya di-flush dulu lewat FRM.
     *
     * @throws StorageException jika gagal menulis ke disk atau flush log gagal
     * @calledBy FR4 (checkpoint dan akhir restoreTo), FR5 (akhir recovery)
     * @calls FRM.flushLogUntil
     */
    void flushAll() throws StorageException;

    // ---------- katalog dan statistik ----------

    /**
     * @return nama semua tabel di katalog, termasuk yang dibuat setelah startup
     * @calledBy QO3 (validasi), Driver SM
     * @calls Tidak ada
     */
    List<String> listTables();

    /**
     * Mengambil skema tabel: urutan kolom (sesuai definisi tabel), tipe, panjang, dan constraint.
     *
     * @param table nama tabel
     * @return skema tabel
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calledBy QO3 (validasi tabel dan kolom, SELECT *, kolom NATURAL JOIN), QP3 (skema hasil dan header tabel kosong), QP5 (validasi INSERT)
     * @calls Tidak ada
     */
    Schema getTableSchema(String table) throws StorageException;

    /**
     * Mengambil semua index pada satu tabel.
     *
     * @param table nama tabel
     * @return daftar index, kosong jika tidak ada
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calledBy QO5 (memilih Index Scan)
     * @calls Tidak ada
     */
    List<IndexInfo> getIndexes(String table) throws StorageException;

    /**
     * Mengambil statistik terkini satu tabel. Tabel kosong menghasilkan recordCount = 0.
     *
     * @param table nama tabel
     * @return statistik tabel
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calledBy QO3 (estimasi biaya), QO5
     * @calls Tidak ada
     */
    TableStats getStats(String table) throws StorageException;

    // ---------- index ----------

    /**
     * Membuat index pada satu kolom, mengisinya dari data yang sudah ada, dan mencatatnya di katalog.
     *
     * @param indexName nama index
     * @param table     nama tabel
     * @param column    nama kolom
     * @param type      jenis index, default BTREE
     * @throws StorageException TABLE_NOT_FOUND, COLUMN_NOT_FOUND, atau INDEX_ALREADY_EXISTS
     * @calledBy QP5 (CREATE INDEX)
     * @calls Tidak ada
     */
    void createIndex(String indexName, String table, String column, IndexType type) throws StorageException;

    /**
     * Mencari RID record yang nilai key-nya sama. Entri dengan key NULL tidak pernah dikembalikan.
     *
     * @param table     nama tabel
     * @param indexName nama index
     * @param key       nilai yang dicari, tidak boleh NULL
     * @return daftar RID tanpa duplikat, kosong jika tidak ada yang cocok
     * @throws StorageException INDEX_NOT_FOUND jika index tidak ada, INVALID_REQUEST jika key NULL
     * @calledBy QP3 (IndexScan)
     * @calls Tidak ada
     */
    List<RID> lookupEqual(String table, String indexName, Value key) throws StorageException;

    /**
     * Mencari RID record dalam rentang key, hasilnya berurut naik menurut key.
     * Batas null berarti sisi itu terbuka (flag inklusifnya diabaikan); kalau keduanya null,
     * hasilnya semua RID di index.
     *
     * @param table          nama tabel
     * @param indexName      nama index
     * @param lower          batas bawah, atau null
     * @param upper          batas atas, atau null
     * @param lowerInclusive true jika batas bawah ikut
     * @param upperInclusive true jika batas atas ikut
     * @return daftar RID tanpa duplikat
     * @throws StorageException INDEX_NOT_FOUND, INDEX_NOT_SUPPORTED (index HASH), atau INVALID_REQUEST (batas berupa Value NULL)
     * @calledBy QP3 (IndexScan)
     * @calls Tidak ada
     */
    List<RID> lookupRange(String table, String indexName, Value lower, Value upper,
                          boolean lowerInclusive, boolean upperInclusive) throws StorageException;

    // ---------- DDL ----------

    /**
     * Membuat tabel baru: mendaftarkan skema ke katalog dan membuat file data kosong.
     * Katalog langsung disimpan ke disk. Tidak dicatat di log dan tidak dibatalkan ABORT.
     * PRIMARY KEY dan FOREIGN KEY hanya disimpan di katalog.
     *
     * @param schema skema tabel baru
     * @throws StorageException TABLE_ALREADY_EXISTS, atau TABLE_NOT_FOUND jika tabel yang dirujuk foreign key tidak ada
     * @calledBy QP5 (eksekutor CREATE TABLE)
     * @calls Tidak ada
     */
    void createTable(Schema schema) throws StorageException;

    /**
     * Menghapus tabel beserta file data, semua index, dan page-nya di buffer, lalu memperbarui katalog.
     * CASCADE ikut menghapus tabel yang merujuk lewat foreign key, RESTRICT menolak jika masih dirujuk.
     * Hanya dipanggil saat tidak ada transaksi aktif. Tidak dicatat di log dan tidak bisa dibatalkan.
     *
     * @param table   nama tabel
     * @param cascade true untuk CASCADE, false untuk RESTRICT
     * @return nama semua tabel yang terhapus
     * @throws StorageException TABLE_NOT_FOUND atau TABLE_REFERENCED
     * @calledBy QP5 (eksekutor DROP TABLE), setelah CCM.blockNewTransactions dan waitUntilNoActive, sebelum FRM.checkpoint
     * @calls Tidak ada
     */
    List<String> dropTable(String table, boolean cascade) throws StorageException;
}
