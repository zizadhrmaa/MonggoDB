package monggodb.frm.driver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
import monggodb.frm.LogRecords;
import monggodb.frm.LogSerializer;

/**
 * SM tiruan berbasis berkas untuk menguji persistensi dan crash recovery FRM antarproses.
 * Satu tabel dianggap satu halaman: perubahan hanya di memori (buffer) sampai halaman ditulis lewat
 * {@link #flushAll()} atau {@link #flushTable(String)}. Sebelum menulis, SM memanggil flushLogUntil(pageLSN)
 * sesuai aturan WAL. Crash (proses dihentikan paksa) menghilangkan seluruh isi buffer yang belum ditulis.
 */
public class FileStorageManager implements StorageManager {

    private static final Comparator<RID> RID_ORDER = Comparator.comparingInt(RID::getPageId).thenComparingInt(RID::getSlotId);

    /** Satu halaman = satu tabel. */
    private static final class Page {
        final TreeMap<RID, Row> rows = new TreeMap<>(RID_ORDER);
        boolean dirty;
        LSN pageLsn = LSN.NONE;
    }

    private final Path tableDir;
    private final Path snapshotDir;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private Consumer<LSN> logFlusher = lsn -> { };
    private int pagesWritten;

    /**
     * Membuka (atau membuat) penyimpanan di folder tertentu dan memuat seluruh tabel dari disk.
     * @param dataDir folder data SM tiruan
     */
    public FileStorageManager(Path dataDir) {
        this.tableDir = dataDir.resolve("tables");
        this.snapshotDir = dataDir.resolve("snapshots");
        try {
            Files.createDirectories(tableDir);
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            throw io("membuat folder data", e);
        }
        reloadFromDisk();
    }

    /**
     * Memasang callback WAL (biasanya {@code frm::flushLogUntil}).
     * @param flusher dipanggil dengan pageLSN sebelum halaman ditulis ke disk
     */
    public synchronized void setLogFlusher(Consumer<LSN> flusher) {
        this.logFlusher = flusher;
    }

    /**
     * DDL: membuat tabel kosong dan langsung menulisnya ke disk. Tidak dicatat di log.
     * @param table nama tabel
     */
    public synchronized void createTable(String table) {
        if (pages.containsKey(table)) return;
        Page p = new Page();
        pages.put(table, p);
        writePage(table, p);
    }

    @Override
    public void createTable(Schema schema) {
        createTable(schema.getTableName());
    }

    /** DDL: menghapus tabel dari memori dan disk. Tidak dicatat di log; foreign key tidak dimodelkan. */
    @Override
    public synchronized List<String> dropTable(String table, boolean cascade) {
        get(table);
        pages.remove(table);
        try {
            Files.deleteIfExists(tableFile(table));
        } catch (IOException e) {
            throw io("menghapus tabel " + table, e);
        }
        return List.of(table);
    }

    /**
     * Menulis satu halaman (tabel) ke disk walaupun berisi perubahan yang belum commit (steal).
     * @param table nama tabel
     */
    public synchronized void flushTable(String table) {
        Page p = get(table);
        if (p.dirty) writePage(table, p);
    }

    /**
     * @param table nama tabel
     * @return jumlah record di buffer
     */
    public synchronized int count(String table) {
        return get(table).rows.size();
    }

    /** @return jumlah halaman yang sudah ditulis ke disk sejak dibuat */
    public synchronized int getPagesWritten() {
        return pagesWritten;
    }

    @Override
    public synchronized Optional<Row> fetchRecord(String table, RID rid) {
        return Optional.ofNullable(get(table).rows.get(rid));
    }

    @Override
    public synchronized void insertRecordAt(String table, RID rid, Row row, LSN lsn) {
        Page p = get(table);
        p.rows.put(rid, row.withRid(rid));
        touch(p, lsn);
    }

    @Override
    public synchronized void updateRecord(String table, RID rid, Row newRow, LSN lsn) {
        Page p = get(table);
        if (!p.rows.containsKey(rid)) {
            throw new StorageException(StorageException.Code.IO_ERROR, "Record " + rid + " tidak ada di tabel " + table);
        }
        p.rows.put(rid, newRow.withRid(rid));
        touch(p, lsn);
    }

    @Override
    public synchronized boolean deleteRecord(String table, RID rid, LSN lsn) {
        Page p = get(table);
        boolean removed = p.rows.remove(rid) != null;
        if (removed) touch(p, lsn);
        return removed;
    }

    @Override
    public synchronized void flushAll() {
        for (Map.Entry<String, Page> e : pages.entrySet()) {
            if (e.getValue().dirty) writePage(e.getKey(), e.getValue());
        }
    }

    @Override
    public synchronized void createSnapshot(String snapshotName) {
        flushAll();
        Path dst = snapshotDir.resolve(snapshotName);
        try {
            deleteRecursively(dst);
            Files.createDirectories(dst);
            try (Stream<Path> files = Files.list(tableDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(f, dst.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw io("membuat snapshot " + snapshotName, e);
        }
    }

    @Override
    public synchronized void loadSnapshot(String snapshotName) {
        Path src = snapshotDir.resolve(snapshotName);
        if (!Files.isDirectory(src)) {
            throw new StorageException(StorageException.Code.SNAPSHOT_NOT_FOUND, "Snapshot " + snapshotName + " tidak ada");
        }
        try {
            deleteRecursively(tableDir);
            Files.createDirectories(tableDir);
            try (Stream<Path> files = Files.list(src)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(f, tableDir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw io("memuat snapshot " + snapshotName, e);
        }
        reloadFromDisk();
    }

    @Override
    public void startup(Path dataDir) {
        // Folder data sudah dibuka di konstruktor.
    }

    @Override
    public void shutdown() {
        flushAll();
    }

    @Override
    public synchronized RecordIterator scanTable(String table) {
        List<Row> rows = new ArrayList<>(get(table).rows.values());
        return new RecordIterator() {
            private int pos;

            @Override
            public boolean hasNext() {
                return pos < rows.size();
            }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                return rows.get(pos++);
            }

            @Override
            public void reset() {
                pos = 0;
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public synchronized int clearTable(String table) {
        Page p = get(table);
        int n = p.rows.size();
        p.rows.clear();
        p.dirty = true;
        return n;
    }

    @Override
    public synchronized List<String> listTables() {
        return new ArrayList<>(pages.keySet());
    }

    // Metode SM di bawah tidak dipakai FRM, jadi tidak didukung SM tiruan ini.

    @Override
    public RID allocateRid(String table, Row row) {
        throw unsupported("allocateRid");
    }

    @Override
    public void releaseRid(String table, RID rid) {
        throw unsupported("releaseRid");
    }

    @Override
    public Schema getTableSchema(String table) {
        throw unsupported("getTableSchema");
    }

    @Override
    public List<IndexInfo> getIndexes(String table) {
        throw unsupported("getIndexes");
    }

    @Override
    public TableStats getStats(String table) {
        throw unsupported("getStats");
    }

    @Override
    public void createIndex(String indexName, String table, String column, IndexType type) {
        throw unsupported("createIndex");
    }

    @Override
    public List<RID> lookupEqual(String table, String indexName, Value key) {
        throw unsupported("lookupEqual");
    }

    @Override
    public List<RID> lookupRange(String table, String indexName, Value lower, Value upper,
                                 boolean lowerInclusive, boolean upperInclusive) {
        throw unsupported("lookupRange");
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(method + " tidak didukung SM tiruan driver FRM");
    }

    private void touch(Page p, LSN lsn) {
        p.dirty = true;
        if (lsn != null && lsn.compareTo(p.pageLsn) > 0) p.pageLsn = lsn;
    }

    private void writePage(String table, Page p) {
        // WAL: log sampai pageLSN harus persisten sebelum halaman ditulis.
        if (!LogRecords.isNone(p.pageLsn)) logFlusher.accept(p.pageLsn);
        Path file = tableFile(table);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(p.rows.size());
            for (Row r : p.rows.values()) LogSerializer.writeRow(out, r);
        } catch (IOException e) {
            throw io("menulis tabel " + table, e);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw io("menulis tabel " + table, e);
        }
        p.dirty = false;
        pagesWritten++;
    }

    private void reloadFromDisk() {
        pages.clear();
        try (Stream<Path> files = Files.list(tableDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".tbl")) continue;
                Page p = new Page();
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) {
                        Row r = LogSerializer.readRow(in);
                        p.rows.put(r.getRid(), r);
                    }
                }
                pages.put(name.substring(0, name.length() - 4), p);
            }
        } catch (IOException e) {
            throw io("memuat tabel dari disk", e);
        }
    }

    private Page get(String table) {
        Page p = pages.get(table);
        if (p == null) throw new StorageException(StorageException.Code.TABLE_NOT_FOUND, "Tabel " + table + " tidak ada");
        return p;
    }

    private Path tableFile(String table) {
        return tableDir.resolve(table + ".tbl");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private static StorageException io(String what, IOException e) {
        return new StorageException(StorageException.Code.IO_ERROR, "Gagal " + what + ": " + e.getMessage(), e);
    }
}
