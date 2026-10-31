package monggodb.sm.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.ColumnStats;
import monggodb.contracts.shared.ForeignKeyRef;
import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TableStats;

/**
 * Implementasi pengelola katalog dan metadata tabel untuk Storage Manager (SM4).
 * Menyediakan penyimpanan katalog di memori dan persistensi berkas JSON ke disk secara atomik.
 */
public class CatalogManagerImpl implements CatalogManager {

    private static final String CATALOG_FILENAME = "catalog.json";
    private static final String TEMP_FILENAME = "catalog.json.tmp";
    private static final String SAMPLE_RESOURCE_PATH = "/sample/catalog.json";

    private Path dataDir;
    private final CatalogJsonParser parser;

    private final Map<String, Schema> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<IndexInfo>> tableIndexes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, TableStats> tableStats = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /**
     * Membuat instance CatalogManagerImpl baru dengan parser standar.
     */
    public CatalogManagerImpl() {
        this.parser = new CatalogJsonParser();
    }

    /**
     * Membuat instance CatalogManagerImpl dengan folder data yang sudah ditentukan.
     *
     * @param dataDir folder data basis data
     */
    public CatalogManagerImpl(Path dataDir) {
        this();
        this.dataDir = dataDir;
    }

    /**
     * Membuat instance CatalogManagerImpl dengan parser kustom.
     *
     * @param parser parser format katalog
     */
    public CatalogManagerImpl(CatalogJsonParser parser) {
        this.parser = parser != null ? parser : new CatalogJsonParser();
    }

    @Override
    public synchronized void loadCatalog(Path dataDir) throws StorageException {
        if (dataDir == null) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST, "dataDir tidak boleh null");
        }
        this.dataDir = dataDir;

        tables.clear();
        tableIndexes.clear();
        tableStats.clear();

        try {
            if (Files.notExists(dataDir)) {
                Files.createDirectories(dataDir);
            }

            Path catalogFile = dataDir.resolve(CATALOG_FILENAME);
            if (Files.exists(catalogFile)) {
                String jsonContent = Files.readString(catalogFile, StandardCharsets.UTF_8);
                CatalogData data = parser.parse(jsonContent);
                applyData(data);
            } else {
                // Periksa apakah terdapat seed basis data contoh di classpath resources
                try (InputStream sampleIn = getClass().getResourceAsStream(SAMPLE_RESOURCE_PATH)) {
                    if (sampleIn != null) {
                        String sampleJson = new String(sampleIn.readAllBytes(), StandardCharsets.UTF_8);
                        CatalogData sampleData = parser.parse(sampleJson);
                        applyData(sampleData);
                        saveCatalog();
                    }
                }
            }
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException(StorageException.Code.IO_ERROR,
                    "Gagal membaca berkas katalog dari " + dataDir.resolve(CATALOG_FILENAME), e);
        }
    }

    @Override
    public synchronized void saveCatalog() throws StorageException {
        if (this.dataDir == null) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST,
                    "dataDir belum diset, panggil loadCatalog terlebih dahulu");
        }

        Path targetFile = dataDir.resolve(CATALOG_FILENAME);
        Path tempFile = dataDir.resolve(TEMP_FILENAME);

        try {
            if (Files.notExists(dataDir)) {
                Files.createDirectories(dataDir);
            }

            CatalogData snapshot = buildDataSnapshot();
            String jsonContent = parser.format(snapshot);

            // Tulis berkas sementara terlebih dahulu
            Files.writeString(tempFile, jsonContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            // Rename secara atomik ke berkas target
            try {
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw new StorageException(StorageException.Code.IO_ERROR,
                    "Gagal menulis berkas katalog ke disk: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void registerTable(Schema schema) throws StorageException {
        Objects.requireNonNull(schema, "schema tidak boleh null");
        String tableName = schema.getTableName();

        if (tables.containsKey(tableName)) {
            throw new StorageException(StorageException.Code.TABLE_ALREADY_EXISTS,
                    "Tabel '" + tableName + "' sudah ada");
        }

        // Validasi Foreign Key
        for (Column col : schema.getColumns()) {
            ForeignKeyRef ref = col.getReferences();
            if (ref != null) {
                String refTable = ref.getTable();
                String refCol = ref.getColumn();

                Schema referencedSchema;
                if (refTable.equalsIgnoreCase(tableName)) {
                    referencedSchema = schema;
                } else {
                    referencedSchema = tables.get(refTable);
                    if (referencedSchema == null) {
                        throw new StorageException(StorageException.Code.TABLE_NOT_FOUND,
                                "Tabel yang dirujuk foreign key '" + refTable + "' tidak ditemukan");
                    }
                }

                if (referencedSchema.indexOf(refCol) == -1) {
                    throw new StorageException(StorageException.Code.COLUMN_NOT_FOUND,
                            "Kolom yang dirujuk foreign key '" + refCol + "' tidak ada di tabel " + refTable);
                }
            }
        }

        tables.put(tableName, schema);
        tableIndexes.putIfAbsent(tableName, new ArrayList<>());

        // Buat statistik awal untuk tabel kosong
        Map<String, ColumnStats> colStats = new LinkedHashMap<>();
        for (Column col : schema.getColumns()) {
            colStats.put(col.getName(), new ColumnStats(col.getName(), 0, 0, null, null));
        }
        tableStats.put(tableName, new TableStats(tableName, 0, 0, 0.0, 0.0, 4096, colStats));

        if (this.dataDir != null) {
            saveCatalog();
        }
    }

    @Override
    public synchronized List<String> dropTable(String table, boolean cascade) throws StorageException {
        if (table == null || !tables.containsKey(table)) {
            throw new StorageException(StorageException.Code.TABLE_NOT_FOUND,
                    "Tabel '" + table + "' tidak ditemukan");
        }

        List<String> dropped = new ArrayList<>();
        List<String> referencing = findReferencingTables(table);

        if (!referencing.isEmpty()) {
            if (!cascade) {
                throw new StorageException(StorageException.Code.TABLE_REFERENCED,
                        "Tabel '" + table + "' masih dirujuk oleh tabel: " + referencing);
            }
            for (String refTable : referencing) {
                if (tables.containsKey(refTable)) {
                    dropped.addAll(dropTable(refTable, true));
                }
            }
        }

        tables.remove(table);
        tableIndexes.remove(table);
        tableStats.remove(table);
        dropped.add(table);

        if (this.dataDir != null) {
            saveCatalog();
        }
        return dropped;
    }

    @Override
    public synchronized List<String> listTables() {
        return List.copyOf(tables.values().stream().map(Schema::getTableName).toList());
    }

    @Override
    public synchronized Schema getTableSchema(String table) throws StorageException {
        checkTableExists(table);
        return tables.get(table);
    }

    @Override
    public synchronized boolean hasTable(String table) {
        return table != null && tables.containsKey(table);
    }

    @Override
    public synchronized void registerIndex(IndexInfo indexInfo) throws StorageException {
        Objects.requireNonNull(indexInfo, "indexInfo tidak boleh null");
        String table = indexInfo.getIndexTable();
        checkTableExists(table);

        Schema schema = tables.get(table);
        if (schema.indexOf(indexInfo.getIndexColumn()) == -1) {
            throw new StorageException(StorageException.Code.COLUMN_NOT_FOUND,
                    "Kolom '" + indexInfo.getIndexColumn() + "' tidak ada di tabel " + table);
        }

        for (List<IndexInfo> list : tableIndexes.values()) {
            for (IndexInfo existing : list) {
                if (existing.getIndexName().equalsIgnoreCase(indexInfo.getIndexName())) {
                    throw new StorageException(StorageException.Code.INDEX_ALREADY_EXISTS,
                            "Index '" + indexInfo.getIndexName() + "' sudah ada");
                }
            }
        }

        tableIndexes.computeIfAbsent(table, k -> new ArrayList<>()).add(indexInfo);

        if (this.dataDir != null) {
            saveCatalog();
        }
    }

    @Override
    public synchronized void dropIndex(String table, String indexName) throws StorageException {
        checkTableExists(table);
        List<IndexInfo> list = tableIndexes.get(table);
        if (list == null) {
            throw new StorageException(StorageException.Code.INDEX_NOT_FOUND,
                    "Index '" + indexName + "' tidak ada di tabel " + table);
        }

        boolean removed = list.removeIf(idx -> idx.getIndexName().equalsIgnoreCase(indexName));
        if (!removed) {
            throw new StorageException(StorageException.Code.INDEX_NOT_FOUND,
                    "Index '" + indexName + "' tidak ada di tabel " + table);
        }

        if (this.dataDir != null) {
            saveCatalog();
        }
    }

    @Override
    public synchronized List<IndexInfo> getIndexes(String table) throws StorageException {
        checkTableExists(table);
        List<IndexInfo> list = tableIndexes.get(table);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public synchronized TableStats getStats(String table) throws StorageException {
        checkTableExists(table);
        TableStats stats = tableStats.get(table);
        if (stats == null) {
            Schema schema = tables.get(table);
            Map<String, ColumnStats> colStats = new LinkedHashMap<>();
            for (Column col : schema.getColumns()) {
                colStats.put(col.getName(), new ColumnStats(col.getName(), 0, 0, null, null));
            }
            stats = new TableStats(schema.getTableName(), 0, 0, 0.0, 0.0, 4096, colStats);
            tableStats.put(table, stats);
        }
        return stats;
    }

    @Override
    public synchronized void updateStats(String table, TableStats stats) throws StorageException {
        checkTableExists(table);
        Objects.requireNonNull(stats, "stats tidak boleh null");
        tableStats.put(table, stats);
    }

    private void checkTableExists(String table) {
        if (table == null || !tables.containsKey(table)) {
            throw new StorageException(StorageException.Code.TABLE_NOT_FOUND,
                    "Tabel '" + table + "' tidak ditemukan");
        }
    }

    private List<String> findReferencingTables(String targetTable) {
        List<String> referencing = new ArrayList<>();
        for (Schema s : tables.values()) {
            if (s.getTableName().equalsIgnoreCase(targetTable)) {
                continue;
            }
            for (Column c : s.getColumns()) {
                ForeignKeyRef ref = c.getReferences();
                if (ref != null && ref.getTable().equalsIgnoreCase(targetTable)) {
                    referencing.add(s.getTableName());
                    break;
                }
            }
        }
        return referencing;
    }

    private void applyData(CatalogData data) {
        if (data == null) {
            return;
        }
        if (data.tables() != null) {
            for (Schema s : data.tables()) {
                tables.put(s.getTableName(), s);
                tableIndexes.putIfAbsent(s.getTableName(), new ArrayList<>());
            }
        }
        if (data.indexes() != null) {
            for (IndexInfo idx : data.indexes()) {
                tableIndexes.computeIfAbsent(idx.getIndexTable(), k -> new ArrayList<>()).add(idx);
            }
        }
        if (data.stats() != null) {
            for (Map.Entry<String, TableStats> entry : data.stats().entrySet()) {
                tableStats.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private CatalogData buildDataSnapshot() {
        List<Schema> tableList = new ArrayList<>(tables.values());
        List<IndexInfo> indexList = new ArrayList<>();
        for (List<IndexInfo> list : tableIndexes.values()) {
            indexList.addAll(list);
        }
        Map<String, TableStats> statsMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        statsMap.putAll(tableStats);
        return new CatalogData(tableList, indexList, statsMap);
    }
}

