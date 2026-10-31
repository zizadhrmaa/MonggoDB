package monggodb.sm.indexManager;

import java.util.List;

import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.Schema;

/**
 * Daftar index per tabel dan skema tabel.
 */
public interface IndexCatalog {

    /**
     * Mengambil semua index pada satu tabel.
     *
     * @param table nama tabel
     * @return daftar index, kosong jika tidak ada
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calls tidak ada
     * @calledBy IndexManagerImpl
     */
    List<IndexInfo> getIndexes(String table);

    /**
     * Mengambil skema satu tabel untuk memetakan nama kolom ke posisi nilai di Row.
     *
     * @param table nama tabel
     * @return skema tabel
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calls tidak ada
     * @calledBy IndexManagerImpl
     */
    Schema getTableSchema(String table);
}