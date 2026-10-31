package monggodb.sm.record;

import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;

/**
 * Sumber skema dan nama berkas data tabel yang dipakai SM1.
 * Diisi dari Catalog Manager SM4 saat SM dirakit di startup.
 */
public interface TableCatalog {

    /**
     * @param table nama tabel
     * @return skema tabel
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calls SM4 (getTableSchema)
     * @calledBy RecordManagerImpl.validateRow
     */
    Schema getTableSchema(String table);

    /**
     * @param table nama tabel
     * @return nama berkas .tbl tabel, relatif terhadap folder data
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada
     * @calls SM4 (getDataFile)
     * @calledBy RecordManagerImpl.findPageWithSpace, RecordManagerImpl.writeSlot
     */
    String getDataFile(String table);
}
