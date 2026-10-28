package monggodb.sm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class BlockAndBufferPoolTest {

    public static void main(String[] args) throws Exception {
        BlockAndBufferPoolTest suite = new BlockAndBufferPoolTest();
        suite.testBlockPersistenceAcrossRestart();
        suite.testBufferHitAndReuseWithoutDiskRead();
        suite.testLruEvictionAndWalFlushBeforeDiskWrite();
        suite.testAllPinnedThrowsWithoutCorruptingData();
        suite.testFlushAllAndConsistency();
        suite.testSnapshotCopyAndRestore();
    }

    public void testBlockPersistenceAcrossRestart() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_persist_");
        try {
            BlockManager bm1 = new BlockManager(tempDir, 4096);
            int b0 = bm1.appendBlock("students.tbl");
            int b1 = bm1.appendBlock("students.tbl");
            require(b0 == 0, "First block should be 0");
            require(b1 == 1, "Second block should be 1");
            require(bm1.getBlockCount("students.tbl") == 2, "Block count should be 2");

            byte[] payload0 = new byte[4096];
            byte[] text0 = "record-block-0".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(text0, 0, payload0, 0, text0.length);
            bm1.writeBlock("students.tbl", 0, payload0);

            byte[] payload1 = new byte[4096];
            byte[] text1 = "record-block-1".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(text1, 0, payload1, 128, text1.length);
            bm1.writeBlock("students.tbl", 1, payload1);

            BlockManager bm2 = new BlockManager(tempDir, 4096);
            require(bm2.getBlockCount("students.tbl") == 2, "Block count after restart should be 2");
            require(Arrays.equals(payload0, bm2.readBlock("students.tbl", 0)), "Block 0 content mismatch");
            require(Arrays.equals(payload1, bm2.readBlock("students.tbl", 1)), "Block 1 content mismatch");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void testBufferHitAndReuseWithoutDiskRead() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_hit_");
        try {
            BufferPoolManager bpm = new BufferPoolManager(tempDir, 4096, 4);
            Page created = bpm.newPage("courses.tbl");
            int pageId = created.getPageId();
            byte[] bytes = "IF3140".getBytes(StandardCharsets.UTF_8);
            created.writeBytes(0, bytes, 10L);
            bpm.unpinPage("courses.tbl", pageId, true, 10L);

            Page fetched = bpm.fetchPage("courses.tbl", pageId);
            require(bpm.getCacheHits() == 1, "Expected 1 cache hit");
            require(bpm.getCacheMisses() == 0, "Expected 0 cache misses");
            require(Arrays.equals(bytes, fetched.readBytes(0, bytes.length)), "Buffered bytes mismatch");
            bpm.unpinPage("courses.tbl", pageId, false);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void testLruEvictionAndWalFlushBeforeDiskWrite() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_lru_wal_");
        try {
            List<Long> flushedLsns = new ArrayList<>();
            AtomicLong maxFlushedLsn = new AtomicLong(-1L);
            BlockManager bm = new BlockManager(tempDir, 4096);
            BufferPoolManager bpm = new BufferPoolManager(bm, 2, lsn -> {
                flushedLsns.add(lsn);
                maxFlushedLsn.set(Math.max(maxFlushedLsn.get(), lsn));
            });

            Page p0 = bpm.newPage("takes.tbl");
            p0.writeBytes(0, new byte[]{1, 2, 3, 4}, 25L);
            bpm.unpinPage("takes.tbl", 0, true, 25L);

            Page p1 = bpm.newPage("takes.tbl");
            p1.writeBytes(0, new byte[]{5, 6, 7, 8}, 40L);
            bpm.unpinPage("takes.tbl", 1, true, 40L);

            Page p0Again = bpm.fetchPage("takes.tbl", 0);
            bpm.unpinPage("takes.tbl", 0, false);
            require(p0Again.getPageId() == 0, "Expected page 0");

            Page p2 = bpm.newPage("takes.tbl");
            p2.writeBytes(0, new byte[]{9, 10, 11, 12}, 55L);
            bpm.unpinPage("takes.tbl", 2, true, 55L);

            require(bpm.getEvictions() == 1, "Expected 1 eviction");
            require(!bpm.isPageCached("takes.tbl", 1), "Page 1 should have been evicted as LRU");
            require(bpm.isPageCached("takes.tbl", 0), "Page 0 should remain in buffer");
            require(bpm.isPageCached("takes.tbl", 2), "Page 2 should be in buffer");
            require(flushedLsns.equals(List.of(40L)), "WAL must flush LSN 40 before evicting dirty page 1");

            byte[] diskPage1 = bm.readBlock("takes.tbl", 1);
            require(diskPage1[0] == 5 && diskPage1[1] == 6 && diskPage1[2] == 7 && diskPage1[3] == 8,
                "Evicted dirty page 1 must be persisted on disk");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void testAllPinnedThrowsWithoutCorruptingData() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_pinned_");
        try {
            BufferPoolManager bpm = new BufferPoolManager(tempDir, 4096, 2);
            bpm.newPage("t.tbl");
            bpm.newPage("t.tbl");

            boolean threw = false;
            try {
                bpm.newPage("t.tbl");
            } catch (IllegalStateException expected) {
                threw = true;
            }
            require(threw, "Expected IllegalStateException when all frames are pinned");
            require(bpm.getBlockCount("t.tbl") == 2, "Failed allocation must not append extra block on disk");

            bpm.unpinPage("t.tbl", 0, false);
            bpm.unpinPage("t.tbl", 1, false);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void testFlushAllAndConsistency() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_flush_");
        try {
            AtomicLong walLsn = new AtomicLong(-1L);
            BufferPoolManager bpm = new BufferPoolManager(new BlockManager(tempDir, 4096), 4, walLsn::set);

            Page p0 = bpm.newPage("users.tbl");
            p0.writeBytes(10, "alice".getBytes(StandardCharsets.UTF_8), 100L);
            bpm.unpinPage("users.tbl", 0, true, 100L);

            Page p1 = bpm.newPage("users.tbl");
            p1.writeBytes(20, "bob".getBytes(StandardCharsets.UTF_8), 200L);
            bpm.unpinPage("users.tbl", 1, true, 200L);

            bpm.flushAll();
            require(walLsn.get() == 200L, "WAL should be flushed up to LSN 200");
            require(bpm.getDirtyPageCount() == 0, "No dirty pages should remain after flushAll");

            BufferPoolManager reopened = new BufferPoolManager(tempDir, 4096, 4);
            Page r0 = reopened.fetchPage("users.tbl", 0);
            Page r1 = reopened.fetchPage("users.tbl", 1);
            require("alice".equals(new String(r0.readBytes(10, 5), StandardCharsets.UTF_8)), "Page 0 data mismatch");
            require("bob".equals(new String(r1.readBytes(20, 3), StandardCharsets.UTF_8)), "Page 1 data mismatch");
            reopened.unpinPage("users.tbl", 0, false);
            reopened.unpinPage("users.tbl", 1, false);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public void testSnapshotCopyAndRestore() throws Exception {
        Path tempDir = Files.createTempDirectory("sm2_test_snap_");
        Path snapDir = Files.createTempDirectory("sm2_test_snap_backup_");
        try {
            BlockManager bm = new BlockManager(tempDir, 4096);
            BufferPoolManager bpm = new BufferPoolManager(bm, 4);

            Page p0 = bpm.newPage("items.tbl");
            p0.writeBytes(0, "initial".getBytes(StandardCharsets.UTF_8), 1L);
            bpm.unpinPage("items.tbl", 0, true, 1L);
            bpm.flushAll();

            bm.copyAllFilesTo(snapDir);

            Page p0Mod = bpm.fetchPage("items.tbl", 0);
            p0Mod.writeBytes(0, "mutated".getBytes(StandardCharsets.UTF_8), 2L);
            bpm.unpinPage("items.tbl", 0, true, 2L);
            bpm.flushAll();

            bpm.resetBufferPool();
            bm.restoreAllFilesFrom(snapDir);

            Page restored = bpm.fetchPage("items.tbl", 0);
            require("initial".equals(new String(restored.readBytes(0, 7), StandardCharsets.UTF_8)),
                "Restored snapshot should match initial state");
            bpm.unpinPage("items.tbl", 0, false);
        } finally {
            deleteRecursively(tempDir);
            deleteRecursively(snapDir);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
