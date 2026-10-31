package monggodb.sm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.StorageException;

public class BlockAndBufferPoolTest {

    public static void main(String[] args) throws Exception {
        BlockAndBufferPoolTest suite = new BlockAndBufferPoolTest();
        suite.testReadWritePersistence();
        suite.testBufferHitWithoutDiskReload();
        suite.testLruEvictionAndWalFlush();
        suite.testAllPinnedEvictionThrows();
        suite.testFlushAllAndDestroy();
        suite.testSnapshots();
    }

    public void testReadWritePersistence() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_persist_");
        try {
            BlockManager bm = new BlockManager(tempDir);
            BufferPoolManager bpm = new BufferPoolManager(bm, 4);

            Page page = bpm.newPage("student.dat");
            int blockId = page.getPageId();
            byte[] payload = "IF3140-MDBMS".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(payload, 0, page.getData(), 0, payload.length);
            bpm.unpinPage("student.dat", blockId, true, new LSN(10L));
            bpm.flushPage("student.dat", blockId);

            BlockManager bmReload = new BlockManager(tempDir);
            BufferPoolManager bpmReload = new BufferPoolManager(bmReload, 4);
            Page reloaded = bpmReload.fetchPage("student.dat", blockId);

            for (int i = 0; i < payload.length; i++) {
                assert reloaded.getData()[i] == payload[i] : "Byte mismatch at index " + i;
            }
            bpmReload.unpinPage("student.dat", blockId, false);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    public void testBufferHitWithoutDiskReload() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_hit_");
        try {
            BlockManager bm = new BlockManager(tempDir);
            BufferPoolManager bpm = new BufferPoolManager(bm, 2);

            Page p1 = bpm.newPage("course.dat");
            p1.getData()[0] = 42;
            bpm.unpinPage("course.dat", 0, true, new LSN(5L));

            Page p2 = bpm.fetchPage("course.dat", 0);
            assert p1 == p2 : "Expected same cached Page instance";
            assert p2.getData()[0] == 42 : "Expected in-memory modification to remain";
            assert p2.getPinCount() == 1 : "Expected pinCount == 1";
            bpm.unpinPage("course.dat", 0, false);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    public void testLruEvictionAndWalFlush() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_lru_");
        try {
            AtomicReference<LSN> lastFlushedLsn = new AtomicReference<>(LSN.NONE);
            BlockManager bm = new BlockManager(tempDir);
            BufferPoolManager bpm = new BufferPoolManager(bm, 2, lastFlushedLsn::set);

            Page p0 = bpm.newPage("t.dat");
            p0.getData()[0] = 11;
            bpm.unpinPage("t.dat", p0.getPageId(), true, new LSN(100L));

            Page p1 = bpm.newPage("t.dat");
            p1.getData()[0] = 22;
            bpm.unpinPage("t.dat", p1.getPageId(), true, LSN.NONE);

            Page p0Again = bpm.fetchPage("t.dat", 0);
            bpm.unpinPage("t.dat", p0Again.getPageId(), false);

            Page p2 = bpm.newPage("t.dat");
            p2.getData()[0] = 33;
            bpm.unpinPage("t.dat", p2.getPageId(), true, new LSN(200L));

            assert lastFlushedLsn.get().equals(LSN.NONE) : "Page 1 had LSN.NONE, should not trigger WAL flush";

            Page p3 = bpm.newPage("t.dat");
            bpm.unpinPage("t.dat", p3.getPageId(), false);

            assert lastFlushedLsn.get().equals(new LSN(100L)) : "Evicting dirty page 0 must flush WAL up to LSN 100";
        } finally {
            deleteDirectory(tempDir);
        }
    }

    public void testAllPinnedEvictionThrows() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_pinned_");
        try {
            BlockManager bm = new BlockManager(tempDir);
            BufferPoolManager bpm = new BufferPoolManager(bm, 2);

            bpm.newPage("a.dat");
            bpm.newPage("a.dat");

            boolean threw = false;
            try {
                bpm.newPage("a.dat");
            } catch (IllegalStateException ex) {
                threw = true;
            }
            assert threw : "Expected IllegalStateException when all frames are pinned";
            assert bm.getBlockCount("a.dat") == 2 : "Failed allocation must not leave extra disk block";
        } finally {
            deleteDirectory(tempDir);
        }
    }

    public void testFlushAllAndDestroy() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_flushall_");
        try {
            BlockManager bm = new BlockManager(tempDir);
            BufferPoolManager bpm = new BufferPoolManager(bm, 3);

            Page p = bpm.newPage("dropme.dat");
            p.getData()[10] = 99;
            bpm.unpinPage("dropme.dat", 0, true, new LSN(77L));
            bpm.flushAll();

            byte[] onDisk = bm.readBlock("dropme.dat", 0);
            assert onDisk[10] == 99 : "Expected flushed byte on disk";

            bpm.discardFilePages("dropme.dat");
            bm.deleteFile("dropme.dat");
            assert bm.getBlockCount("dropme.dat") == 0 : "Expected deleted file to have 0 blocks";
        } finally {
            deleteDirectory(tempDir);
        }
    }

    public void testSnapshots() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_snapshot_");
        try {
            BlockManager bm = new BlockManager(tempDir);
            BufferPoolManager bpm = new BufferPoolManager(bm, 4);

            Page p = bpm.newPage("users.dat");
            p.getData()[0] = 7;
            bpm.unpinPage("users.dat", 0, true, new LSN(12L));

            bpm.createSnapshot("snap1");

            Page pMod = bpm.fetchPage("users.dat", 0);
            pMod.getData()[0] = 88;
            bpm.unpinPage("users.dat", 0, true, new LSN(15L));
            bpm.flushAll();

            bpm.loadSnapshot("snap1");

            Page restored = bpm.fetchPage("users.dat", 0);
            assert restored.getData()[0] == 7 : "Expected snapshot value 7 to be restored";
            bpm.unpinPage("users.dat", 0, false);

            boolean snapshotMissingThrew = false;
            try {
                bpm.loadSnapshot("non_existent_snapshot");
            } catch (StorageException e) {
                snapshotMissingThrew = e.getErrorCode() == StorageException.Code.SNAPSHOT_NOT_FOUND;
            }
            assert snapshotMissingThrew : "Expected SNAPSHOT_NOT_FOUND StorageException";
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void deleteDirectory(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(p -> {
                          try {
                              Files.deleteIfExists(p);
                          } catch (Exception ignored) {
                          }
                      });
            }
        }
    }
}
