package monggodb.sm.indexManager;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.Value;

/**
 * Implementasi IndexManager yang menyimpan struktur index di memori.
 * Metadata index dan skema tabel diambil dari {@link IndexCatalog}, sehingga mudah diganti katalog tiruan pada driver.
 */
public final class IndexManagerImpl implements IndexManager {

    private final IndexCatalog catalog;
    private final Map<String, IndexStructure> structures = new HashMap<>();

    /**
     * @param catalog sumber metadata index dan skema tabel
     */
    public IndexManagerImpl(IndexCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public Optional<IndexInfo> findIndex(String table, String indexName) {
        requireName(table, "table");
        requireName(indexName, "indexName");
        for (IndexInfo info : indexesOf(table)) {
            if (info.getIndexName().equalsIgnoreCase(indexName)) {
                return Optional.of(info);
            }
        }
        return Optional.empty();
    }

    @Override
    public void registerIndex(IndexInfo info) {
        Objects.requireNonNull(info, "info");
        structures.put(structureKey(info.getIndexTable(), info.getIndexName()), createStructure(info));
    }

    @Override
    public void insertEntry(String table, Row row, RID rid) {
        requireName(table, "table");
        if (row == null || rid == null) {
            throw invalidRequest("row dan rid tidak boleh null");
        }
        Schema schema = catalog.getTableSchema(table);
        for (IndexInfo info : indexesOf(table)) {
            int column = requireColumn(schema, info);
            if (column >= row.size()) {
                throw new StorageException(StorageException.Code.SCHEMA_MISMATCH,
                        "Row berisi " + row.size() + " nilai, tetapi kolom index " + info.getIndexColumn()
                                + " berada di posisi " + column);
            }
            Value value = row.getValue(column);
            if (value == null || value.isNull()) {
                continue;
            }
            structure(info).insert(value, rid);
        }
    }

    @Override
    public void deleteEntry(String table, Row row, RID rid) {
        requireName(table, "table");
        if (row == null || rid == null) {
            throw invalidRequest("row dan rid tidak boleh null");
        }
        Schema schema = catalog.getTableSchema(table);
        for (IndexInfo info : indexesOf(table)) {
            int column = requireColumn(schema, info);
            if (column >= row.size()) {
                throw new StorageException(StorageException.Code.SCHEMA_MISMATCH,
                        "Row berisi " + row.size() + " nilai, tetapi kolom index " + info.getIndexColumn()
                                + " berada di posisi " + column);
            }
            Value value = row.getValue(column);
            if (value == null || value.isNull()) {
                continue;
            }
            structure(info).delete(value, rid);
        }
    }

    @Override
    public void clearIndexes(String table) {
        requireName(table, "table");
        for (IndexInfo info : indexesOf(table)) {
            structure(info).clear();
        }
    }

    @Override
    public List<RID> lookupEqual(String table, String indexName, Value key) {
        requireName(table, "table");
        requireName(indexName, "indexName");
        if (key == null || key.isNull()) {
            throw invalidRequest("Key lookup tidak boleh null atau NULL SQL");
        }
        IndexInfo info = findIndex(table, indexName)
                .orElseThrow(() -> indexNotFound(table, indexName));
        return structure(info).search(key);
    }

    @Override
    public List<RID> lookupRange(String table, String indexName, Value lower, Value upper,
                                 boolean lowerInclusive, boolean upperInclusive) {
        requireName(table, "table");
        requireName(indexName, "indexName");
        if (lower != null && lower.isNull()) {
            throw invalidRequest("Batas bawah tidak boleh NULL SQL");
        }
        if (upper != null && upper.isNull()) {
            throw invalidRequest("Batas atas tidak boleh NULL SQL");
        }
        IndexInfo info = findIndex(table, indexName)
                .orElseThrow(() -> indexNotFound(table, indexName));
        return structure(info).rangeScan(lower, upper, lowerInclusive, upperInclusive);
    }

    private IndexStructure structure(IndexInfo info) {
        return structures.computeIfAbsent(
                structureKey(info.getIndexTable(), info.getIndexName()),
                ignored -> createStructure(info));
    }

    private IndexStructure createStructure(IndexInfo info) {
        Schema schema = catalog.getTableSchema(info.getIndexTable());
        int column = requireColumn(schema, info);
        DataType keyType = schema.getColumns().get(column).getDataType();
        return info.getIndexType() == IndexType.HASH
                ? new HashIndex(keyType)
                : new BPlusTree(keyType);
    }

    private List<IndexInfo> indexesOf(String table) {
        List<IndexInfo> indexes = catalog.getIndexes(table);
        return indexes == null ? List.of() : indexes;
    }

    private static int requireColumn(Schema schema, IndexInfo info) {
        int column = schema.indexOf(info.getIndexColumn());
        if (column < 0) {
            throw new StorageException(StorageException.Code.COLUMN_NOT_FOUND,
                    "Kolom '" + info.getIndexColumn() + "' untuk index '" + info.getIndexName()
                            + "' tidak ada di tabel " + info.getIndexTable());
        }
        return column;
    }

    private static String structureKey(String table, String indexName) {
        return table.toLowerCase(Locale.ROOT) + '\u0000' + indexName.toLowerCase(Locale.ROOT);
    }

    private static void requireName(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalidRequest(name + " tidak boleh null atau kosong");
        }
    }

    private static StorageException invalidRequest(String message) {
        return new StorageException(StorageException.Code.INVALID_REQUEST, message);
    }

    private static StorageException indexNotFound(String table, String indexName) {
        return new StorageException(StorageException.Code.INDEX_NOT_FOUND,
                "Index '" + indexName + "' tidak ada di tabel " + table);
    }
}
