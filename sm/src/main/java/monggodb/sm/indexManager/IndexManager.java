package monggodb.sm.indexManager;

import java.util.List;
import java.util.Optional;

import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Value;

/**
 * Mengelola index internal SM: menemukan metadata index, memperbarui entri index saat record berubah, dan melakukan pencarian equality maupun range.
 */
public interface IndexManager {

    /**
     * Mencari index pada tabel berdasarkan nama index, tanpa membedakan huruf besar dan kecil.
     *
     * @param table nama tabel
     * @param indexName nama index
     * @return metadata index, atau kosong jika tidak ada
     * @throws StorageException INVALID_REQUEST jika nama null atau kosong, TABLE_NOT_FOUND dari katalog
     * @calls IndexCatalog.getIndexes
     * @calledBy IndexManagerImpl.lookupEqual, IndexManagerImpl.lookupRange
     */
    Optional<IndexInfo> findIndex(String table, String indexName);

    /**
     * Mendaftarkan index dan membuat struktur kosong sesuai jenisnya. Struktur lama dengan nama yang sama diganti.
     *
     * @param info metadata index
     * @throws StorageException TABLE_NOT_FOUND atau COLUMN_NOT_FOUND dari katalog
     * @calls IndexCatalog.getTableSchema
     * @calledBy buildIndex, createIndex
     */
    void registerIndex(IndexInfo info);

    /**
     * Menambah pasangan key dan RID ke semua index tabel. Nilai kolom index yang NULL dilewati.
     *
     * @param table nama tabel
     * @param row isi record sesuai urutan kolom skema
     * @param rid alamat record
     * @throws StorageException INVALID_REQUEST, TABLE_NOT_FOUND, COLUMN_NOT_FOUND, atau SCHEMA_MISMATCH
     * @calls IndexCatalog.getIndexes, IndexCatalog.getTableSchema, IndexStructure.insert
     * @calledBy SM1.insertRecord, SM1.insertRecordAt, SM1.updateRecord
     */
    void insertEntry(String table, Row row, RID rid);

    /**
     * Menghapus pasangan key dan RID dari semua index tabel. Idempoten dan melewati nilai NULL.
     *
     * @param table nama tabel
     * @param row isi record sesuai urutan kolom skema
     * @param rid alamat record
     * @throws StorageException INVALID_REQUEST, TABLE_NOT_FOUND, COLUMN_NOT_FOUND, atau SCHEMA_MISMATCH
     * @calls IndexCatalog.getIndexes, IndexCatalog.getTableSchema, IndexStructure.delete
     * @calledBy SM1.deleteRecord, SM1.updateRecord
     */
    void deleteEntry(String table, Row row, RID rid);

    /**
     * Mengosongkan seluruh index tabel tanpa menghapus metadata index.
     *
     * @param table nama tabel
     * @throws StorageException TABLE_NOT_FOUND dari katalog
     * @calls IndexCatalog.getIndexes, IndexStructure.clear
     * @calledBy SM1.clearTable
     */
    void clearIndexes(String table);

    /**
     * Mencari RID dengan nilai key yang sama.
     *
     * @param table nama tabel
     * @param indexName nama index
     * @param key nilai key, tidak boleh null atau NULL SQL
     * @return daftar RID tanpa duplikat, kosong jika tidak ada
     * @throws StorageException INVALID_REQUEST, INDEX_NOT_FOUND, atau TYPE_MISMATCH
     * @calls findIndex, IndexStructure.search
     * @calledBy StorageManager.lookupEqual
     */
    List<RID> lookupEqual(String table, String indexName, Value key);

    /**
     * Mencari RID dalam rentang key. Batas null berarti sisi tersebut terbuka.
     *
     * @param table nama tabel
     * @param indexName nama index
     * @param lower batas bawah atau null
     * @param upper batas atas atau null
     * @param lowerInclusive true jika batas bawah ikut
     * @param upperInclusive true jika batas atas ikut
     * @return daftar RID tanpa duplikat, berurut naik menurut key
     * @throws StorageException INVALID_REQUEST, INDEX_NOT_FOUND, INDEX_NOT_SUPPORTED, atau TYPE_MISMATCH
     * @calls findIndex, IndexStructure.rangeScan
     * @calledBy StorageManager.lookupRange
     */
    List<RID> lookupRange(String table, String indexName, Value lower, Value upper,
                          boolean lowerInclusive, boolean upperInclusive);
}