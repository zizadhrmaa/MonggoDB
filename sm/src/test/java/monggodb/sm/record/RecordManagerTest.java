package monggodb.sm.record;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.StorageException.Code;
import monggodb.contracts.shared.Value;
import monggodb.sm.BlockManager;
import monggodb.sm.BufferPoolManager;
import monggodb.sm.Page;
import monggodb.sm.serializer.RecordSerializerImpl;

/**
 * Pengujian mandiri SlottedPage dan RecordManager (SM1): validateRow, findPageWithSpace, dan writeSlot.
 */
public final class RecordManagerTest {

    private static final String TABLE = "students";
    private static final String FILE = "students.tbl";
    private static final int BLOCK_SIZE = 4096;

    private static int passed = 0;
    private static int failed = 0;

    /** Katalog satu tabel untuk pengujian, menggantikan Catalog Manager SM4. */
    private static final TableCatalog CATALOG = new TableCatalog() {
        private final Schema schema = new Schema(TABLE, List.of(
                new Column("nim", TABLE, DataType.INTEGER, 0, true, true, null),
                new Column("name", TABLE, DataType.VARCHAR, 50, false, false, null),
                new Column("gpa", TABLE, DataType.FLOAT, 0, false, false, null)));

        @Override
        public Schema getTableSchema(String table) {
            check(table);
            return schema;
        }

        @Override
        public String getDataFile(String table) {
            check(table);
            return FILE;
        }

        private void check(String table) {
            if (table == null || !table.equalsIgnoreCase(TABLE)) {
                throw new StorageException(Code.TABLE_NOT_FOUND, "Tabel '" + table + "' tidak ditemukan");
            }
        }
    };

    public static void main(String[] args) {
        System.out.println("=== Menjalankan Pengujian SM1 RecordManager ===");
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("sm1-record-test");

            testSlottedPageWriteAndRead();
            testSlottedPageInPlaceAndCapacity();
            testSlottedPageCompaction();
            testSlottedPageFull();
            testValidateRow(tempDir.resolve("validate_db"));
            testFindPageWithSpace(tempDir.resolve("find_db"));
            testWriteSlotAndPersistence(tempDir.resolve("write_db"));
            testWriteSlotExtendsFile(tempDir.resolve("extend_db"));
            testWriteSlotPageLsn(tempDir.resolve("lsn_db"));
            testWriteSlotErrors(tempDir.resolve("error_db"));

        } catch (Exception e) {
            System.err.println("Error tidak terduga saat pengujian: " + e.getMessage());
            e.printStackTrace();
            failed++;
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }

        System.out.println();
        System.out.println("================ RINGKASAN ================");
        System.out.printf("Total: %d lulus, %d gagal%n", passed, failed);
        System.out.println("===========================================");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testSlottedPageWriteAndRead() {
        try {
            byte[] data = new byte[BLOCK_SIZE];
            SlottedPage page = new SlottedPage(data);
            check("Page baru tidak punya slot", page.getSlotCount() == 0);
            check("Page baru: area record mulai di akhir page", page.getRecordAreaStart() == BLOCK_SIZE);
            check("Page baru: ruang kosong = ukuran page - header",
                    page.getFreeSpace() == BLOCK_SIZE - SlottedPage.HEADER_SIZE);

            page.write(0, SlotStatus.USED, bytes(10, 1));
            page.write(1, SlotStatus.USED, bytes(20, 2));
            check("Dua slot tertulis", page.getSlotCount() == 2);
            check("Isi slot 0 terbaca kembali", Arrays.equals(page.readRecord(0), bytes(10, 1)));
            check("Isi slot 1 terbaca kembali", Arrays.equals(page.readRecord(1), bytes(20, 2)));
            check("Status slot 0 USED", page.getSlotStatus(0) == SlotStatus.USED);
            check("Record pertama ditulis di akhir page", page.getSlotOffset(0) == BLOCK_SIZE - 10);
            check("Ruang kosong berkurang sesuai isi dan slot directory",
                    page.getFreeSpace() == BLOCK_SIZE - SlottedPage.HEADER_SIZE - 2 * SlottedPage.SLOT_SIZE - 30);

            page.write(4, SlotStatus.RESERVED, bytes(5, 0));
            check("Menulis slot 4 memperpanjang directory menjadi 5 slot", page.getSlotCount() == 5);
            check("Slot 2 dan 3 dibuat EMPTY",
                    page.getSlotStatus(2) == SlotStatus.EMPTY && page.getSlotStatus(3) == SlotStatus.EMPTY);
            check("Slot EMPTY tidak punya kapasitas", page.getSlotCapacity(2) == 0);
            check("Slot 4 RESERVED dengan kapasitas 5", page.getSlotStatus(4) == SlotStatus.RESERVED
                    && page.getSlotCapacity(4) == 5);

            SlottedPage reread = new SlottedPage(data.clone());
            check("Isi page bisa dibaca ulang dari byte yang sama", Arrays.equals(reread.readRecord(1), bytes(20, 2)));
        } catch (Exception e) {
            fail("testSlottedPageWriteAndRead", e);
        }
    }

    private static void testSlottedPageInPlaceAndCapacity() {
        try {
            SlottedPage page = new SlottedPage(new byte[BLOCK_SIZE]);
            page.write(0, SlotStatus.USED, bytes(30, 1));
            int offset = page.getSlotOffset(0);
            int freeBefore = page.getFreeSpace();

            page.write(0, SlotStatus.USED, bytes(12, 7));
            check("Record yang memendek ditulis di tempat", page.getSlotOffset(0) == offset);
            check("Kapasitas slot tetap 30", page.getSlotCapacity(0) == 30);
            check("Panjang slot menjadi 12", page.getSlotLength(0) == 12);
            check("Ruang kosong tidak berubah", page.getFreeSpace() == freeBefore);

            page.write(0, SlotStatus.EMPTY, new byte[0]);
            check("Hapus record: status EMPTY dan panjang 0",
                    page.getSlotStatus(0) == SlotStatus.EMPTY && page.getSlotLength(0) == 0);
            check("Hapus record: kapasitas tetap disisihkan untuk undo", page.getSlotCapacity(0) == 30);

            page.write(0, SlotStatus.USED, bytes(30, 9));
            check("Undo DELETE muat kembali di tempat yang sama",
                    page.getSlotOffset(0) == offset && Arrays.equals(page.readRecord(0), bytes(30, 9)));

            page.write(0, SlotStatus.USED, bytes(50, 4));
            check("Record yang memanjang mendapat area baru", page.getSlotOffset(0) != offset);
            check("Kapasitas mengikuti panjang baru", page.getSlotCapacity(0) == 50);
            check("Isi record memanjang terbaca kembali", Arrays.equals(page.readRecord(0), bytes(50, 4)));
        } catch (Exception e) {
            fail("testSlottedPageInPlaceAndCapacity", e);
        }
    }

    private static void testSlottedPageCompaction() {
        try {
            SlottedPage page = new SlottedPage(new byte[BLOCK_SIZE]);
            int big = 1300;
            page.write(0, SlotStatus.USED, bytes(big, 1));
            page.write(1, SlotStatus.USED, bytes(big, 2));
            page.write(2, SlotStatus.USED, bytes(big, 3));
            int free = page.getFreeSpace();
            check("Tiga record besar tertulis", page.getSlotCount() == 3);

            // Slot 1 memanjang sehingga harus pindah, padahal ruang bersambungnya tidak cukup.
            int grown = big + free;
            check("Slot 1 bisa ditulis ulang setelah pemadatan", page.canWrite(1, grown));
            check("Ruang bersambung belum cukup sebelum pemadatan", page.getContiguousFreeSpace() < grown);
            page.write(1, SlotStatus.USED, bytes(grown, 5));

            check("Slot 0 tetap utuh setelah pemadatan", Arrays.equals(page.readRecord(0), bytes(big, 1)));
            check("Slot 1 berisi record baru", Arrays.equals(page.readRecord(1), bytes(grown, 5)));
            check("Slot 2 tetap utuh setelah pemadatan", Arrays.equals(page.readRecord(2), bytes(big, 3)));
            check("Page penuh setelah pemadatan", page.getFreeSpace() == 0);
        } catch (Exception e) {
            fail("testSlottedPageCompaction", e);
        }
    }

    private static void testSlottedPageFull() {
        try {
            byte[] data = new byte[BLOCK_SIZE];
            SlottedPage page = new SlottedPage(data);
            int max = BLOCK_SIZE - SlottedPage.HEADER_SIZE - SlottedPage.SLOT_SIZE;
            check("Record terbesar satu page bisa ditulis", page.canWrite(0, max));
            check("Record lebih besar satu byte ditolak", !page.canWrite(0, max + 1));
            page.write(0, SlotStatus.USED, bytes(max, 6));
            check("Slot baru tidak muat di page penuh", !page.canWrite(1, 0));
            check("Slot negatif ditolak canWrite", !page.canWrite(-1, 1));

            byte[] before = data.clone();
            expectCode("Menulis ke page penuh melempar INVALID_REQUEST", Code.INVALID_REQUEST,
                    () -> {
                        page.write(1, SlotStatus.USED, bytes(1, 1));
                        return null;
                    });
            check("Page tidak berubah setelah penulisan yang gagal", Arrays.equals(before, data));
            expectCode("Membaca slot yang tidak ada melempar INVALID_RID", Code.INVALID_RID,
                    () -> page.readRecord(3));
        } catch (Exception e) {
            fail("testSlottedPageFull", e);
        }
    }

    private static void testValidateRow(Path dbDir) {
        try {
            RecordManager rm = newRecordManager(dbDir);
            rm.validateRow(TABLE, row(13524001, "Andi", 3.5f));
            check("Row yang sesuai skema lolos validasi", true);
            rm.validateRow("STUDENTS", new Row(null, List.of(Value.ofInteger(1), Value.ofNull(DataType.VARCHAR),
                    Value.ofInteger(3))));
            check("NULL di kolom nullable dan INTEGER ke FLOAT diterima", true);

            expectCode("Tabel tidak ada: TABLE_NOT_FOUND", Code.TABLE_NOT_FOUND,
                    () -> {
                        rm.validateRow("courses", row(1, "A", 1f));
                        return null;
                    });
            expectCode("Row null: INVALID_REQUEST", Code.INVALID_REQUEST,
                    () -> {
                        rm.validateRow(TABLE, null);
                        return null;
                    });
            expectCode("Jumlah nilai kurang: SCHEMA_MISMATCH", Code.SCHEMA_MISMATCH,
                    () -> {
                        rm.validateRow(TABLE, new Row(null, List.of(Value.ofInteger(1), Value.ofVarchar("A"))));
                        return null;
                    });
            expectCode("NULL di primary key: NULL_NOT_ALLOWED", Code.NULL_NOT_ALLOWED,
                    () -> {
                        rm.validateRow(TABLE, new Row(null, List.of(Value.ofNull(DataType.INTEGER),
                                Value.ofVarchar("A"), Value.ofFloat(1f))));
                        return null;
                    });
            expectCode("Teks di kolom INTEGER: TYPE_MISMATCH", Code.TYPE_MISMATCH,
                    () -> {
                        rm.validateRow(TABLE, new Row(null, List.of(Value.ofVarchar("x"),
                                Value.ofVarchar("A"), Value.ofFloat(1f))));
                        return null;
                    });
            expectCode("Teks lebih dari 50 byte: VALUE_TOO_LONG", Code.VALUE_TOO_LONG,
                    () -> {
                        rm.validateRow(TABLE, row(1, "a".repeat(51), 1f));
                        return null;
                    });
        } catch (Exception e) {
            fail("testValidateRow", e);
        }
    }

    private static void testFindPageWithSpace(Path dbDir) {
        try {
            BufferPoolManager pool = new BufferPoolManager(new BlockManager(dbDir, BLOCK_SIZE), 8);
            RecordManager rm = new RecordManagerImpl(pool, CATALOG, new RecordSerializerImpl());

            int first = rm.findPageWithSpace(TABLE, 100);
            check("Tabel kosong: page baru 0 dialokasikan", first == 0 && pool.getBlockCount(FILE) == 1);
            check("Tidak ada page yang tertinggal ter-pin", pool.getPinnedPageCount() == 0);

            int again = rm.findPageWithSpace(TABLE, 100);
            check("Page terakhir yang masih muat dipakai lagi", again == 0 && pool.getBlockCount(FILE) == 1);

            int max = BLOCK_SIZE - SlottedPage.HEADER_SIZE - SlottedPage.SLOT_SIZE;
            rm.writeSlot(TABLE, 0, 0, SlotStatus.USED, bytes(max - 100, 1), LSN.NONE);
            int next = rm.findPageWithSpace(TABLE, 200);
            check("Page terakhir penuh: page baru 1 dialokasikan", next == 1 && pool.getBlockCount(FILE) == 2);
            check("Record seukuran sisa ruang masih memakai page 1", rm.findPageWithSpace(TABLE, max) == 1);
            check("Tidak ada page yang tertinggal ter-pin setelah pencarian", pool.getPinnedPageCount() == 0);

            expectCode("Ukuran melebihi satu page: INVALID_REQUEST", Code.INVALID_REQUEST,
                    () -> rm.findPageWithSpace(TABLE, max + 1));
            expectCode("Ukuran negatif: INVALID_REQUEST", Code.INVALID_REQUEST,
                    () -> rm.findPageWithSpace(TABLE, -1));
            expectCode("Tabel tidak ada: TABLE_NOT_FOUND", Code.TABLE_NOT_FOUND,
                    () -> rm.findPageWithSpace("courses", 10));
        } catch (Exception e) {
            fail("testFindPageWithSpace", e);
        }
    }

    private static void testWriteSlotAndPersistence(Path dbDir) {
        try {
            BufferPoolManager pool = new BufferPoolManager(new BlockManager(dbDir, BLOCK_SIZE), 8);
            RecordSerializerImpl serializer = new RecordSerializerImpl();
            RecordManager rm = new RecordManagerImpl(pool, CATALOG, serializer);

            Row andi = row(13524001, "Andi", 3.5f);
            byte[] recordBytes = serializer.serializeRecord(andi, CATALOG.getTableSchema(TABLE));
            int pageId = rm.findPageWithSpace(TABLE, recordBytes.length);
            rm.writeSlot(TABLE, pageId, 0, SlotStatus.USED, recordBytes, LSN.NONE);
            check("Page ditandai kotor setelah writeSlot", pool.getDirtyPageCount() == 1);
            check("Page di-unpin setelah writeSlot", pool.getPinnedPageCount() == 0);

            pool.flushAll();
            BufferPoolManager reopened = new BufferPoolManager(new BlockManager(dbDir, BLOCK_SIZE), 8);
            Page page = reopened.fetchPage(FILE, pageId);
            try {
                SlottedPage slotted = new SlottedPage(page.getData());
                Row decoded = serializer.deserializeRecord(slotted.readRecord(0), CATALOG.getTableSchema(TABLE), null);
                check("Record terbaca kembali dari disk setelah flush", decoded.equals(andi));
                check("Status slot USED tersimpan di disk", slotted.getSlotStatus(0) == SlotStatus.USED);
            } finally {
                reopened.unpinPage(FILE, pageId, false);
            }
        } catch (Exception e) {
            fail("testWriteSlotAndPersistence", e);
        }
    }

    private static void testWriteSlotExtendsFile(Path dbDir) {
        try {
            BufferPoolManager pool = new BufferPoolManager(new BlockManager(dbDir, BLOCK_SIZE), 8);
            RecordManager rm = new RecordManagerImpl(pool, CATALOG, new RecordSerializerImpl());

            rm.writeSlot(TABLE, 3, 2, SlotStatus.USED, bytes(16, 8), new LSN(5));
            check("writeSlot ke page 3 memperpanjang file menjadi 4 page", pool.getBlockCount(FILE) == 4);
            check("Tidak ada page yang tertinggal ter-pin", pool.getPinnedPageCount() == 0);

            Page page = pool.fetchPage(FILE, 3);
            try {
                SlottedPage slotted = new SlottedPage(page.getData());
                check("Slot 2 di page 3 berisi record", Arrays.equals(slotted.readRecord(2), bytes(16, 8)));
                check("Slot 0 dan 1 di page 3 EMPTY", slotted.getSlotStatus(0) == SlotStatus.EMPTY
                        && slotted.getSlotStatus(1) == SlotStatus.EMPTY);
            } finally {
                pool.unpinPage(FILE, 3, false);
            }

            Page empty = pool.fetchPage(FILE, 1);
            try {
                check("Page sisipan yang kosong tidak punya slot", new SlottedPage(empty.getData()).getSlotCount() == 0);
            } finally {
                pool.unpinPage(FILE, 1, false);
            }
        } catch (Exception e) {
            fail("testWriteSlotExtendsFile", e);
        }
    }

    private static void testWriteSlotPageLsn(Path dbDir) {
        try {
            long[] flushedUntil = {0};
            BufferPoolManager pool = new BufferPoolManager(new BlockManager(dbDir, BLOCK_SIZE), 8,
                    lsn -> flushedUntil[0] = Math.max(flushedUntil[0], lsn.value()));
            RecordManager rm = new RecordManagerImpl(pool, CATALOG, new RecordSerializerImpl());

            rm.writeSlot(TABLE, 0, 0, SlotStatus.USED, bytes(10, 1), new LSN(20));
            rm.writeSlot(TABLE, 0, 1, SlotStatus.USED, bytes(10, 2), new LSN(10));
            pool.flushAll();
            check("Flush page memanggil flushLogUntil sampai pageLSN terbesar (20)", flushedUntil[0] == 20);

            flushedUntil[0] = 0;
            rm.writeSlot(TABLE, 0, 2, SlotStatus.USED, bytes(10, 3), LSN.NONE);
            pool.flushAll();
            check("Perubahan LSN.NONE tidak memanggil flushLogUntil", flushedUntil[0] == 0);
        } catch (Exception e) {
            fail("testWriteSlotPageLsn", e);
        }
    }

    private static void testWriteSlotErrors(Path dbDir) {
        try {
            BufferPoolManager pool = new BufferPoolManager(new BlockManager(dbDir, BLOCK_SIZE), 8);
            RecordManager rm = new RecordManagerImpl(pool, CATALOG, new RecordSerializerImpl());
            int max = BLOCK_SIZE - SlottedPage.HEADER_SIZE - SlottedPage.SLOT_SIZE;
            rm.writeSlot(TABLE, 0, 0, SlotStatus.USED, bytes(max, 1), LSN.NONE);
            pool.flushAll();

            expectCode("Record tidak muat: INVALID_REQUEST", Code.INVALID_REQUEST,
                    () -> {
                        rm.writeSlot(TABLE, 0, 1, SlotStatus.USED, bytes(1, 1), LSN.NONE);
                        return null;
                    });
            check("Page tidak ditandai kotor setelah penulisan yang gagal", pool.getDirtyPageCount() == 0);
            check("Page di-unpin setelah penulisan yang gagal", pool.getPinnedPageCount() == 0);

            expectCode("pageId negatif: INVALID_RID", Code.INVALID_RID,
                    () -> {
                        rm.writeSlot(TABLE, -1, 0, SlotStatus.USED, bytes(1, 1), LSN.NONE);
                        return null;
                    });
            expectCode("LSN null: INVALID_REQUEST", Code.INVALID_REQUEST,
                    () -> {
                        rm.writeSlot(TABLE, 0, 0, SlotStatus.USED, bytes(1, 1), null);
                        return null;
                    });
            expectCode("Tabel tidak ada: TABLE_NOT_FOUND", Code.TABLE_NOT_FOUND,
                    () -> {
                        rm.writeSlot("courses", 0, 0, SlotStatus.USED, bytes(1, 1), LSN.NONE);
                        return null;
                    });
        } catch (Exception e) {
            fail("testWriteSlotErrors", e);
        }
    }

    private static RecordManager newRecordManager(Path dbDir) {
        BufferPoolManager pool = new BufferPoolManager(new BlockManager(dbDir, BLOCK_SIZE), 8);
        return new RecordManagerImpl(pool, CATALOG, new RecordSerializerImpl());
    }

    private static Row row(int nim, String name, float gpa) {
        return new Row(null, List.of(Value.ofInteger(nim), Value.ofVarchar(name), Value.ofFloat(gpa)));
    }

    private static byte[] bytes(int length, int fill) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) fill);
        return result;
    }

    private static void expectCode(String desc, Code expected, Supplier<?> action) {
        try {
            action.get();
            check(desc + " (tidak ada exception)", false);
        } catch (StorageException e) {
            check(desc, e.getErrorCode() == expected);
        }
    }

    private static void check(String desc, boolean condition) {
        if (condition) {
            System.out.printf("[LULUS] %s%n", desc);
            passed++;
        } else {
            System.out.printf("[GAGAL] %s%n", desc);
            failed++;
        }
    }

    private static void fail(String testName, Throwable t) {
        System.err.printf("[ERROR] %s: %s%n", testName, t.getMessage());
        t.printStackTrace();
        failed++;
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.exists(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException ignored) {
        }
    }
}
