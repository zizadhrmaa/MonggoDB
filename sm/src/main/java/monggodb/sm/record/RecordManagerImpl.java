package monggodb.sm.record;

import java.util.Objects;

import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.StorageException.Code;
import monggodb.sm.BufferPoolManager;
import monggodb.sm.Page;
import monggodb.sm.serializer.RecordSerializer;

/**
 * Implementasi RecordManager di atas buffer pool SM2 dan format {@link SlottedPage}.
 * Setiap page yang diambil dari buffer pool selalu di-unpin lagi, termasuk saat terjadi error.
 */
public class RecordManagerImpl implements RecordManager {

    /** Buffer pool SM2 tempat page tabel dibaca dan ditulis. */
    private final BufferPoolManager bufferPool;

    /** Sumber skema dan nama berkas data tabel. */
    private final TableCatalog catalog;

    /** Serializer SM3 untuk memeriksa setiap nilai terhadap kolomnya. */
    private final RecordSerializer serializer;

    /**
     * @param bufferPool buffer pool SM2
     * @param catalog    sumber skema dan nama berkas data tabel
     * @param serializer serializer SM3
     */
    public RecordManagerImpl(BufferPoolManager bufferPool, TableCatalog catalog, RecordSerializer serializer) {
        this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool tidak boleh null");
        this.catalog = Objects.requireNonNull(catalog, "catalog tidak boleh null");
        this.serializer = Objects.requireNonNull(serializer, "serializer tidak boleh null");
    }

    @Override
    public void validateRow(String table, Row row) {
        Schema schema = catalog.getTableSchema(table);
        if (row == null) {
            throw new StorageException(Code.INVALID_REQUEST, "Row untuk tabel '" + table + "' tidak boleh null");
        }
        if (row.size() != schema.size()) {
            throw new StorageException(Code.SCHEMA_MISMATCH,
                    "Jumlah nilai " + row.size() + " tidak sesuai jumlah kolom " + schema.size()
                            + " di tabel " + schema.getTableName());
        }
        for (int i = 0; i < schema.size(); i++) {
            serializer.validate(row.getValue(i), schema.getColumns().get(i));
        }
    }

    @Override
    public synchronized int findPageWithSpace(String table, int sizeInBytes) {
        String file = catalog.getDataFile(table);
        int maxSize = bufferPool.getBlockSize() - SlottedPage.HEADER_SIZE - SlottedPage.SLOT_SIZE;
        if (sizeInBytes < 0 || sizeInBytes > maxSize) {
            throw new StorageException(Code.INVALID_REQUEST,
                    "Ukuran record " + sizeInBytes + " byte harus 0 sampai " + maxSize + " byte agar muat di satu page");
        }

        int blockCount = bufferPool.getBlockCount(file);
        if (blockCount > 0) {
            int lastPage = blockCount - 1;
            Page page = bufferPool.fetchPage(file, lastPage);
            try {
                SlottedPage slotted = new SlottedPage(page.getData());
                if (slotted.canWrite(slotted.getSlotCount(), sizeInBytes)) {
                    return lastPage;
                }
            } finally {
                bufferPool.unpinPage(file, lastPage, false);
            }
        }

        Page page = bufferPool.newPage(file);
        int pageId = page.getPageId();
        bufferPool.unpinPage(file, pageId, false);
        return pageId;
    }

    @Override
    public synchronized void writeSlot(String table, int pageId, int slotId, SlotStatus status,
                                       byte[] recordBytes, LSN lsn) {
        String file = catalog.getDataFile(table);
        if (pageId < 0 || slotId < 0) {
            throw new StorageException(Code.INVALID_RID, "RID (" + pageId + ", " + slotId + ") tidak valid");
        }
        if (status == null || recordBytes == null || lsn == null) {
            throw new StorageException(Code.INVALID_REQUEST, "status, recordBytes, dan lsn tidak boleh null");
        }

        ensurePageExists(file, pageId);
        Page page = bufferPool.fetchPage(file, pageId);
        boolean written = false;
        try {
            SlottedPage slotted = new SlottedPage(page.getData());
            if (!slotted.canWrite(slotId, recordBytes.length)) {
                throw new StorageException(Code.INVALID_REQUEST,
                        "Record sepanjang " + recordBytes.length + " byte tidak muat di page " + pageId
                                + " tabel " + table);
            }
            // Ditandai kotor sebelum byte diubah, supaya flush yang berjalan bersamaan
            // tetap mem-flush log sampai lsn ini lebih dulu.
            bufferPool.markDirty(file, pageId, lsn);
            slotted.write(slotId, status, recordBytes);
            written = true;
        } finally {
            bufferPool.unpinPage(file, pageId, written, lsn);
        }
    }

    /**
     * Menambah page kosong di akhir file sampai page {@code pageId} ada.
     * Dibutuhkan saat redo menulis ke RID yang page-nya belum sempat ditulis ke disk sebelum crash.
     */
    private void ensurePageExists(String file, int pageId) {
        while (bufferPool.getBlockCount(file) <= pageId) {
            Page page = bufferPool.newPage(file);
            bufferPool.unpinPage(file, page.getPageId(), false);
        }
    }
}
