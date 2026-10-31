package monggodb.qp.driver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
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
import monggodb.contracts.sm.StorageManager;

/**
 * SM tiruan di memori untuk driver: tabel dibuat lewat createTable dan diisi lewat addRow.
 * Mendukung skenario yang tidak tersedia di MockStorageManager, seperti tabel kosong, tabel besar,
 * nilai NULL, dan kegagalan baca di tengah scan. Operasi yang tidak dipakai driver melempar
 * UnsupportedOperationException.
 */
public class InMemoryStorageManager implements StorageManager {

    /** Jumlah slot per page untuk penomoran RID. */
    private static final int SLOTS_PER_PAGE = 100;

    private final Map<String, Schema> schemas = new LinkedHashMap<>();
    private final Map<String, List<Row>> tables = new LinkedHashMap<>();
    private final Map<String, Integer> failAt = new LinkedHashMap<>();
    private int scanCount;
    private int schemaCount;

    /**
     * Menambah satu record ke akhir tabel dengan RID berurutan.
     *
     * @param table  nama tabel
     * @param values nilai sesuai urutan kolom tabel
     * @return RID record baru
     */
    public RID addRow(String table, Value... values) {
        List<Row> rows = rows(table);
        int n = rows.size();
        RID rid = new RID(n / SLOTS_PER_PAGE, n % SLOTS_PER_PAGE);
        rows.add(new Row(rid, List.of(values)));
        return rid;
    }

    /**
     * Membuat iterator scan berikutnya pada tabel ini gagal saat membaca record ke-index.
     *
     * @param table nama tabel
     * @param index posisi record yang gagal dibaca, mulai dari 0
     */
    public void failScanAt(String table, int index) {
        rows(table);
        failAt.put(key(table), index);
    }

    /** @return jumlah panggilan scanTable */
    public int getScanCount() {
        return scanCount;
    }

    /** @return jumlah panggilan getTableSchema */
    public int getSchemaCount() {
        return schemaCount;
    }

    private static String key(String table) {
        return table == null ? null : table.toLowerCase(Locale.ROOT);
    }

    private List<Row> rows(String table) {
        List<Row> rows = tables.get(key(table));
        if (rows == null) {
            throw new StorageException(StorageException.Code.TABLE_NOT_FOUND, "Tabel '" + table + "' tidak ditemukan");
        }
        return rows;
    }

    @Override
    public void startup(Path dataDir) {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public RecordIterator scanTable(String table) {
        scanCount++;
        List<Row> rows = rows(table);
        Integer failIndex = failAt.remove(key(table));
        return new ListIterator(table, List.copyOf(rows), failIndex == null ? -1 : failIndex);
    }

    @Override
    public Optional<Row> fetchRecord(String table, RID rid) {
        for (Row row : rows(table)) {
            if (row.getRid().equals(rid)) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    @Override
    public RID allocateRid(String table, Row row) {
        throw new UnsupportedOperationException("allocateRid tidak dipakai driver");
    }

    @Override
    public void releaseRid(String table, RID rid) {
        throw new UnsupportedOperationException("releaseRid tidak dipakai driver");
    }

    @Override
    public void insertRecordAt(String table, RID rid, Row row, LSN lsn) {
        throw new UnsupportedOperationException("insertRecordAt tidak dipakai driver");
    }

    @Override
    public void updateRecord(String table, RID rid, Row newRow, LSN lsn) {
        throw new UnsupportedOperationException("updateRecord tidak dipakai driver");
    }

    @Override
    public boolean deleteRecord(String table, RID rid, LSN lsn) {
        return rows(table).removeIf(row -> row.getRid().equals(rid));
    }

    @Override
    public int clearTable(String table) {
        List<Row> rows = rows(table);
        int count = rows.size();
        rows.clear();
        return count;
    }

    @Override
    public void createSnapshot(String snapshotName) {
        throw new UnsupportedOperationException("createSnapshot tidak dipakai driver");
    }

    @Override
    public void loadSnapshot(String snapshotName) {
        throw new UnsupportedOperationException("loadSnapshot tidak dipakai driver");
    }

    @Override
    public void flushAll() {
    }

    @Override
    public List<String> listTables() {
        List<String> names = new ArrayList<>();
        for (Schema schema : schemas.values()) {
            names.add(schema.getTableName());
        }
        return names;
    }

    @Override
    public Schema getTableSchema(String table) {
        schemaCount++;
        rows(table);
        return schemas.get(key(table));
    }

    @Override
    public List<IndexInfo> getIndexes(String table) {
        rows(table);
        return List.of();
    }

    @Override
    public TableStats getStats(String table) {
        throw new UnsupportedOperationException("getStats tidak dipakai driver");
    }

    @Override
    public void createIndex(String indexName, String table, String column, IndexType type) {
        throw new UnsupportedOperationException("createIndex tidak dipakai driver");
    }

    @Override
    public List<RID> lookupEqual(String table, String indexName, Value key) {
        throw new UnsupportedOperationException("lookupEqual tidak dipakai driver");
    }

    @Override
    public List<RID> lookupRange(String table, String indexName, Value lower, Value upper,
                                 boolean lowerInclusive, boolean upperInclusive) {
        throw new UnsupportedOperationException("lookupRange tidak dipakai driver");
    }

    @Override
    public void createTable(Schema schema) {
        String table = key(schema.getTableName());
        if (tables.containsKey(table)) {
            throw new StorageException(StorageException.Code.TABLE_ALREADY_EXISTS,
                    "Tabel '" + schema.getTableName() + "' sudah ada");
        }
        schemas.put(table, schema);
        tables.put(table, new ArrayList<>());
    }

    @Override
    public List<String> dropTable(String table, boolean cascade) {
        throw new UnsupportedOperationException("dropTable tidak dipakai driver");
    }

    /** Iterator di atas salinan record tabel, bisa gagal di posisi tertentu untuk simulasi error baca. */
    private static class ListIterator implements RecordIterator {
        private final String table;
        private final List<Row> data;
        private final int failIndex;
        private int pos;
        private boolean closed;

        ListIterator(String table, List<Row> data, int failIndex) {
            this.table = table;
            this.data = data;
            this.failIndex = failIndex;
        }

        private void checkOpen() {
            if (closed) {
                throw new IllegalStateException("Iterator sudah ditutup");
            }
        }

        @Override
        public boolean hasNext() {
            checkOpen();
            return pos < data.size();
        }

        @Override
        public Row next() {
            checkOpen();
            if (pos >= data.size()) {
                throw new NoSuchElementException();
            }
            if (pos == failIndex) {
                throw new StorageException(StorageException.Code.IO_ERROR,
                        "Gagal membaca record ke-" + pos + " tabel " + table + " (simulasi driver)");
            }
            return data.get(pos++);
        }

        @Override
        public void reset() {
            checkOpen();
            pos = 0;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
