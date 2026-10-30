package monggodb.sm;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import monggodb.contracts.shared.LSN;

public final class BufferPoolManager {
    public static final int DEFAULT_POOL_SIZE = 64;

    private final BlockManager blockManager;
    private final Page[] frames;
    private final Map<BlockId, Integer> pageTable;
    private final Deque<Integer> freeFrames;
    private final LinkedHashSet<Integer> lruOrder;
    private LogFlusher logFlusher;
    private long cacheHits;
    private long cacheMisses;
    private long evictions;
    private long flushes;

    public BufferPoolManager(Path dataDirectory) {
        this(new BlockManager(dataDirectory, BlockManager.DEFAULT_BLOCK_SIZE), DEFAULT_POOL_SIZE, null);
    }

    public BufferPoolManager(Path dataDirectory, int blockSize, int poolSize) {
        this(new BlockManager(dataDirectory, blockSize), poolSize, null);
    }

    public BufferPoolManager(BlockManager blockManager, int poolSize) {
        this(blockManager, poolSize, null);
    }

    public BufferPoolManager(BlockManager blockManager, int poolSize, LogFlusher logFlusher) {
        this.blockManager = Objects.requireNonNull(blockManager, "blockManager must not be null");
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        this.frames = new Page[poolSize];
        this.pageTable = new HashMap<>(poolSize * 2);
        this.freeFrames = new ArrayDeque<>(poolSize);
        this.lruOrder = new LinkedHashSet<>(poolSize * 2);
        for (int i = 0; i < poolSize; i++) {
            this.frames[i] = new Page(blockManager.getBlockSize());
            this.freeFrames.addLast(i);
        }
        this.logFlusher = logFlusher;
    }

    public synchronized void setLogFlusher(LogFlusher logFlusher) {
        this.logFlusher = logFlusher;
    }

    public synchronized LogFlusher getLogFlusher() {
        return logFlusher;
    }

    public BlockManager getBlockManager() {
        return blockManager;
    }

    public int getPoolSize() {
        return frames.length;
    }

    public int getBlockSize() {
        return blockManager.getBlockSize();
    }

    public synchronized Page fetchPage(String fileName, int pageId) {
        BlockId blockId = new BlockId(fileName, pageId);
        Integer frameIdx = pageTable.get(blockId);
        if (frameIdx != null) {
            cacheHits++;
            Page page = frames[frameIdx];
            page.pin();
            touchLru(frameIdx);
            return page;
        }

        cacheMisses++;
        int targetFrameIdx = acquireFrame();
        Page targetPage = frames[targetFrameIdx];
        blockManager.readBlock(blockId.fileName(), blockId.blockId(), targetPage.getData());
        targetPage.bind(blockId);
        pageTable.put(blockId, targetFrameIdx);
        touchLru(targetFrameIdx);
        return targetPage;
    }

    public synchronized Page getPage(String fileName, int pageId) {
        return fetchPage(fileName, pageId);
    }

    public synchronized Page newPage(String fileName) {
        int targetFrameIdx = acquireFrame();
        int newBlockId = blockManager.appendBlock(fileName);
        BlockId blockId = new BlockId(fileName, newBlockId);
        Page targetPage = frames[targetFrameIdx];
        targetPage.clear();
        targetPage.bind(blockId);
        pageTable.put(blockId, targetFrameIdx);
        touchLru(targetFrameIdx);
        return targetPage;
    }

    public synchronized Page allocatePage(String fileName) {
        return newPage(fileName);
    }

    public synchronized void writePage(String fileName, int pageId, byte[] data) {
        writePage(fileName, pageId, data, LSN.NONE);
    }

    public synchronized void writePage(String fileName, int pageId, byte[] data, long pageLsn) {
        writePage(fileName, pageId, data, pageLsn < 0 ? LSN.NONE : new LSN(pageLsn));
    }

    public synchronized void writePage(String fileName, int pageId, byte[] data, LSN pageLsn) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length != blockManager.getBlockSize()) {
            throw new IllegalArgumentException("Data length must match blockSize: " + blockManager.getBlockSize());
        }
        Page page = fetchPage(fileName, pageId);
        try {
            page.writeBytes(0, data, pageLsn);
        } finally {
            unpinPage(fileName, pageId, true, pageLsn);
        }
    }

    public synchronized void unpinPage(String fileName, int pageId, boolean isDirty) {
        unpinPage(fileName, pageId, isDirty, LSN.NONE);
    }

    public synchronized void unpinPage(String fileName, int pageId, boolean isDirty, long pageLsn) {
        unpinPage(fileName, pageId, isDirty, pageLsn < 0 ? LSN.NONE : new LSN(pageLsn));
    }

    public synchronized void unpinPage(String fileName, int pageId, boolean isDirty, LSN pageLsn) {
        BlockId blockId = new BlockId(fileName, pageId);
        Integer frameIdx = pageTable.get(blockId);
        if (frameIdx == null) {
            throw new IllegalArgumentException("Page is not in buffer pool: " + blockId);
        }
        Page page = frames[frameIdx];
        if (isDirty) {
            page.markDirty(pageLsn);
        }
        page.unpin();
        touchLru(frameIdx);
    }

    public synchronized void markDirty(String fileName, int pageId) {
        markDirty(fileName, pageId, LSN.NONE);
    }

    public synchronized void markDirty(String fileName, int pageId, long pageLsn) {
        markDirty(fileName, pageId, pageLsn < 0 ? LSN.NONE : new LSN(pageLsn));
    }

    public synchronized void markDirty(String fileName, int pageId, LSN pageLsn) {
        BlockId blockId = new BlockId(fileName, pageId);
        Integer frameIdx = pageTable.get(blockId);
        if (frameIdx == null) {
            throw new IllegalArgumentException("Page is not in buffer pool: " + blockId);
        }
        frames[frameIdx].markDirty(pageLsn);
    }

    public synchronized boolean flushPage(String fileName, int pageId) {
        BlockId blockId = new BlockId(fileName, pageId);
        Integer frameIdx = pageTable.get(blockId);
        if (frameIdx == null) {
            return false;
        }
        flushFrame(frames[frameIdx]);
        return true;
    }

    public synchronized void flushFile(String fileName) {
        BlockId probe = new BlockId(fileName, 0);
        String normalizedName = probe.fileName();
        for (Page page : frames) {
            BlockId id = page.getBlockId();
            if (id != null && id.fileName().equals(normalizedName)) {
                flushFrame(page);
            }
        }
    }

    public synchronized void flushAll() {
        for (Page page : frames) {
            if (page.getBlockId() != null) {
                flushFrame(page);
            }
        }
    }

    public synchronized void evictPage(String fileName, int pageId) {
        BlockId blockId = new BlockId(fileName, pageId);
        Integer frameIdx = pageTable.get(blockId);
        if (frameIdx == null) {
            return;
        }
        Page page = frames[frameIdx];
        if (page.isPinned()) {
            throw new IllegalStateException("Cannot evict pinned page: " + blockId);
        }
        flushFrame(page);
        pageTable.remove(blockId);
        lruOrder.remove(frameIdx);
        page.clear();
        freeFrames.addLast(frameIdx);
    }

    public synchronized void discardFilePages(String fileName) {
        BlockId probe = new BlockId(fileName, 0);
        String normalizedName = probe.fileName();
        for (int i = 0; i < frames.length; i++) {
            Page page = frames[i];
            BlockId id = page.getBlockId();
            if (id != null && id.fileName().equals(normalizedName)) {
                if (page.isPinned()) {
                    throw new IllegalStateException("Cannot discard pinned page: " + id);
                }
                pageTable.remove(id);
                lruOrder.remove(i);
                page.clear();
                freeFrames.addLast(i);
            }
        }
    }

    public synchronized void clearFile(String fileName) {
        discardFilePages(fileName);
        blockManager.truncateFile(fileName);
    }

    public synchronized void deleteFile(String fileName) {
        discardFilePages(fileName);
        blockManager.deleteFile(fileName);
    }

    public synchronized void resetBufferPool() {
        for (Page page : frames) {
            if (page.isPinned()) {
                throw new IllegalStateException("Cannot reset buffer pool while page is pinned: " + page.getBlockId());
            }
        }
        pageTable.clear();
        lruOrder.clear();
        freeFrames.clear();
        for (int i = 0; i < frames.length; i++) {
            frames[i].clear();
            freeFrames.addLast(i);
        }
    }

    public synchronized void createSnapshot(String snapshotName) {
        flushAll();
        blockManager.copyAllFilesTo(snapshotName);
    }

    public synchronized void createSnapshot(Path snapshotDirectory) {
        flushAll();
        blockManager.copyAllFilesTo(snapshotDirectory);
    }

    public synchronized void loadSnapshot(String snapshotName) {
        resetBufferPool();
        blockManager.restoreAllFilesFrom(snapshotName);
    }

    public synchronized void loadSnapshot(Path snapshotDirectory) {
        resetBufferPool();
        blockManager.restoreAllFilesFrom(snapshotDirectory);
    }

    public synchronized int getBlockCount(String fileName) {
        return blockManager.getBlockCount(fileName);
    }

    public synchronized boolean isPageCached(String fileName, int pageId) {
        return pageTable.containsKey(new BlockId(fileName, pageId));
    }

    public synchronized int getCachedPageCount() {
        return pageTable.size();
    }

    public synchronized int getFreeFrameCount() {
        return freeFrames.size();
    }

    public synchronized int getPinnedPageCount() {
        int count = 0;
        for (Page page : frames) {
            if (page.isPinned()) {
                count++;
            }
        }
        return count;
    }

    public synchronized int getDirtyPageCount() {
        int count = 0;
        for (Page page : frames) {
            if (page.isDirty()) {
                count++;
            }
        }
        return count;
    }

    public synchronized long getCacheHits() {
        return cacheHits;
    }

    public synchronized long getCacheMisses() {
        return cacheMisses;
    }

    public synchronized long getEvictions() {
        return evictions;
    }

    public synchronized long getFlushes() {
        return flushes;
    }

    private int acquireFrame() {
        if (!freeFrames.isEmpty()) {
            return freeFrames.removeFirst();
        }
        Integer victimIdx = selectVictimFrame();
        if (victimIdx == null) {
            throw new IllegalStateException("Buffer pool is full and all frames are pinned");
        }
        Page victim = frames[victimIdx];
        BlockId oldBlockId = victim.getBlockId();
        flushFrame(victim);
        pageTable.remove(oldBlockId);
        lruOrder.remove(victimIdx);
        victim.clear();
        evictions++;
        return victimIdx;
    }

    private Integer selectVictimFrame() {
        Iterator<Integer> it = lruOrder.iterator();
        while (it.hasNext()) {
            Integer frameIdx = it.next();
            if (!frames[frameIdx].isPinned()) {
                return frameIdx;
            }
        }
        return null;
    }

    private void flushFrame(Page page) {
        if (!page.isDirty() || page.getBlockId() == null) {
            return;
        }
        if (logFlusher != null && !LSN.NONE.equals(page.getPageLsn())) {
            logFlusher.flushLogUntil(page.getPageLsn());
        }
        BlockId id = page.getBlockId();
        blockManager.writeBlock(id.fileName(), id.blockId(), page.getData());
        page.clearDirty();
        flushes++;
    }

    private void touchLru(int frameIdx) {
        lruOrder.remove(frameIdx);
        lruOrder.add(frameIdx);
    }
}
