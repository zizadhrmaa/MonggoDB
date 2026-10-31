package monggodb.contracts.sm.mock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.ColumnStats;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.IndexInfo;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.RecordIterator;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.StorageException.Code;
import monggodb.contracts.shared.TableStats;
import monggodb.contracts.shared.Value;
import monggodb.contracts.sm.StorageManager;

/**
 * Mock Storage Manager untuk dipakai grup lain sebelum SM asli jadi.
 * Isinya satu tabel contoh "students" (nim INTEGER, name VARCHAR(50), gpa FLOAT) dengan 3 baris
 * dan satu index BTREE "idx_students_nim". Mock ini tidak menyimpan perubahan: insert, update,
 * dan delete hanya dicek lalu diabaikan, jadi hasil baca selalu sama.
 * Error mengikuti kontrak, misalnya tabel lain selalu TABLE_NOT_FOUND.
 */
public class MockStorageManager implements StorageManager {

    private static final String TABLE = "students";
    private static final String INDEX = "idx_students_nim";

    private final Schema schema = new Schema(TABLE, List.of(
            new Column("nim", TABLE, DataType.INTEGER, 0, true, true, null),
            new Column("name", TABLE, DataType.VARCHAR, 50, false, false, null),
            new Column("gpa", TABLE, DataType.FLOAT, 0, false, false, null)));

    private final List<Row> rows = List.of(
            row(0, 0, 13524001, "Andi", 3.5f),
            row(0, 1, 13524002, "Budi", 3.2f),
            row(0, 2, 13524003, "Citra", 3.8f));

    private static Row row(int page, int slot, int nim, String name, float gpa) {
        return new Row(new RID(page, slot),
                List.of(Value.ofInteger(nim), Value.ofVarchar(name), Value.ofFloat(gpa)));
    }

    private void checkTable(String table) {
        if (table == null || !table.equalsIgnoreCase(TABLE)) {
            throw new StorageException(Code.TABLE_NOT_FOUND, "Tabel '" + table + "' tidak ditemukan");
        }
    }

    private void checkIndex(String table, String indexName) {
        checkTable(table);
        if (indexName == null || !indexName.equalsIgnoreCase(INDEX)) {
            throw new StorageException(Code.INDEX_NOT_FOUND,
                    "Index '" + indexName + "' tidak ada di tabel " + table);
        }
    }

    private void checkNotNull(Value v) {
        if (v != null && v.isNull()) {
            throw new StorageException(Code.INVALID_REQUEST, "Pencarian index dengan NULL tidak diizinkan");
        }
    }

    private boolean hasRid(RID rid) {
        for (Row r : rows) {
            if (r.getRid().equals(rid)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void startup(Path dataDir) {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public RecordIterator scanTable(String table) {
        checkTable(table);
        return new ListIterator(rows);
    }

    @Override
    public Optional<Row> fetchRecord(String table, RID rid) {
        checkTable(table);
        for (Row r : rows) {
            if (r.getRid().equals(rid)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    @Override
    public RID allocateRid(String table, Row row) {
        checkTable(table);
        if (row.size() != schema.size()) {
            throw new StorageException(Code.SCHEMA_MISMATCH,
                    "Jumlah nilai " + row.size() + " tidak sesuai jumlah kolom " + schema.size());
        }
        return new RID(1, 0);
    }

    @Override
    public void releaseRid(String table, RID rid) {
        checkTable(table);
    }

    @Override
    public void insertRecordAt(String table, RID rid, Row row, LSN lsn) {
        checkTable(table);
    }

    @Override
    public void updateRecord(String table, RID rid, Row newRow, LSN lsn) {
        checkTable(table);
        if (!hasRid(rid)) {
            throw new StorageException(Code.RECORD_NOT_FOUND, "Tidak ada record di RID " + rid);
        }
        if (newRow.size() != schema.size()) {
            throw new StorageException(Code.SCHEMA_MISMATCH,
                    "Jumlah nilai " + newRow.size() + " tidak sesuai jumlah kolom " + schema.size());
        }
    }

    @Override
    public boolean deleteRecord(String table, RID rid, LSN lsn) {
        checkTable(table);
        return hasRid(rid);
    }

    @Override
    public int clearTable(String table) {
        checkTable(table);
        return rows.size();
    }

    @Override
    public void createSnapshot(String snapshotName) {
    }

    @Override
    public void loadSnapshot(String snapshotName) {
    }

    @Override
    public void flushAll() {
    }

    @Override
    public List<String> listTables() {
        return List.of(TABLE);
    }

    @Override
    public Schema getTableSchema(String table) {
        checkTable(table);
        return schema;
    }

    @Override
    public List<IndexInfo> getIndexes(String table) {
        checkTable(table);
        return List.of(new IndexInfo(INDEX, TABLE, "nim", IndexType.BTREE));
    }

    @Override
    public TableStats getStats(String table) {
        checkTable(table);
        Map<String, ColumnStats> cols = Map.of(
                "nim", new ColumnStats("nim", 3, 0, Value.ofInteger(13524001), Value.ofInteger(13524003)),
                "name", new ColumnStats("name", 3, 0, null, null),
                "gpa", new ColumnStats("gpa", 3, 0, Value.ofFloat(3.2f), Value.ofFloat(3.8f)));
        return new TableStats(TABLE, rows.size(), 1, 20.0, 204.0, 4096, cols);
    }

    @Override
    public void createIndex(String indexName, String table, String column, IndexType type) {
        checkTable(table);
        if (schema.indexOf(column) == -1) {
            throw new StorageException(Code.COLUMN_NOT_FOUND, "Kolom '" + column + "' tidak ada di tabel " + table);
        }
        if (INDEX.equalsIgnoreCase(indexName)) {
            throw new StorageException(Code.INDEX_ALREADY_EXISTS, "Index '" + indexName + "' sudah ada");
        }
    }

    @Override
    public List<RID> lookupEqual(String table, String indexName, Value key) {
        checkIndex(table, indexName);
        if (key == null) {
            throw new StorageException(Code.INVALID_REQUEST, "Key tidak boleh kosong");
        }
        checkNotNull(key);
        List<RID> result = new ArrayList<>();
        for (Row r : rows) {
            if (r.getValue(0).equals(key)) {
                result.add(r.getRid());
            }
        }
        return result;
    }

    @Override
    public List<RID> lookupRange(String table, String indexName, Value lower, Value upper,
                                 boolean lowerInclusive, boolean upperInclusive) {
        checkIndex(table, indexName);
        checkNotNull(lower);
        checkNotNull(upper);
        List<RID> result = new ArrayList<>();
        for (Row r : rows) {
            int nim = r.getValue(0).asInteger();
            boolean okLower = lower == null
                    || (lowerInclusive ? nim >= lower.asInteger() : nim > lower.asInteger());
            boolean okUpper = upper == null
                    || (upperInclusive ? nim <= upper.asInteger() : nim < upper.asInteger());
            if (okLower && okUpper) {
                result.add(r.getRid());
            }
        }
        return result;
    }

    @Override
    public void createTable(Schema newSchema) {
        if (newSchema.getTableName().equalsIgnoreCase(TABLE)) {
            throw new StorageException(Code.TABLE_ALREADY_EXISTS, "Tabel '" + TABLE + "' sudah ada");
        }
    }

    @Override
    public List<String> dropTable(String table, boolean cascade) {
        checkTable(table);
        return List.of(TABLE);
    }

    /** Iterator sederhana di atas list, dipakai scanTable. */
    private static class ListIterator implements RecordIterator {
        private final List<Row> data;
        private int pos = 0;
        private boolean closed = false;

        ListIterator(List<Row> data) {
            this.data = data;
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
