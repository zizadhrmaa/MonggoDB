package monggodb.sm.buffer;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.shared.LSN;

public class BufferPoolManager implements BufferDiskManager {
    public static final int DEFAULT_POOL_SIZE = 64;

    private final BlockManager blockManager;
    private final int poolSize;
    private final Page[] frames;
    private final Map<BlockId, Integer> pageTable;
    private final Deque<Integer> freeList;
    private final Deque<Integer> lruList;
    private volatile LogFlusher logFlusher;

    public BufferPoolManager(Path dataDirectory) {
        this(new BlockManager(dataDirectory), DEFAULT_POOL_SIZE, null);
    }

    public BufferPoolManager(Path dataDirectory, int poolSize) {
        this(new BlockManager(dataDirectory), poolSize, null);
    }

    public BufferPoolManager(BlockManager blockManager) {
        this(blockManager, DEFAULT_POOL_SIZE, null);
    }

    public BufferPoolManager(BlockManager blockManager, int poolSize) {
        this(blockManager, poolSize, null);
    }

    public BufferPoolManager(BlockManager blockManager, int poolSize, LogFlusher logFlusher) {
        this.blockManager = Objects.requireNonNull(blockManager, "blockManager must not be null");
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        this.poolSize = poolSize;
        this.frames = new Page[poolSize];
        this.pageTable = new HashMap<>();
        this.freeList = new ArrayDeque<>(poolSize);
        this.lruList = new ArrayDeque<>(poolSize);
        this.logFlusher = logFlusher;

        int blockSize = blockManager.getBlockSize();
        for (int i = 0; i < poolSize; i++) {
            this.frames[i] = new Page(blockSize);
            this.freeList.addLast(i);
        }
    }

    public BlockManager getBlockManager() {
        return blockManager;
    }

    public int getPoolSize() {
        return poolSize;
    }

    @Override
    public void setLogFlusher(LogFlusher logFlusher) {
        this.logFlusher = logFlusher;
    }

    @Override
    public void setFailureRecoveryManager(FailureRecoveryManager frm) {
        this.logFlusher = frm == null ? null : frm::flushLogUntil;
    }

    @Override
    public void readBlock(String fileName, int blockId, byte[] dest) {
        blockManager.readBlock(fileName, blockId, dest);
    }

    @Override
    public byte[] readBlock(String fileName, int blockId) {
        return blockManager.readBlock(fileName, blockId);
    }

    @Override
    public void writeBlock(String fileName, int blockId, byte[] data) {
        blockManager.writeBlock(fileName, blockId, data);
    }

    @Override
    public int appendBlock(String fileName) {
        return blockManager.appendBlock(fileName);
    }

    @Override
    public int appendBlock(String fileName, byte[] data) {
        return blockManager.appendBlock(fileName, data);
    }

    @Override
    public int getBlockCount(String fileName) {
        return blockManager.getBlockCount(fileName);
    }

    @Override
    public synchronized void deleteFile(String fileName) {
        discardFilePages(fileName);
        blockManager.deleteFile(fileName);
    }

    @Override
    public synchronized Page getPage(String fileName, int blockId) {
        return getPage(new BlockId(fileName, blockId));
    }

    public synchronized Page getPage(PageKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return getPage(new BlockId(key.fileName(), key.pageId()));
    }

    public synchronized Page getPage(BlockId blockId) {
        Objects.requireNonNull(blockId, "blockId must not be null");
        Integer existingFrame = pageTable.get(blockId);
        if (existingFrame != null) {
            Page page = frames[existingFrame];
            page.pin();
            lruList.remove(existingFrame);
            return page;
        }

        int frameIndex = acquireFrameIndex();
        Page victim = frames[frameIndex];
        BlockId oldBlockId = victim.getBlockId();
        if (oldBlockId != null) {
            if (victim.isDirty()) {
                flushFrameInternal(victim);
            }
            pageTable.remove(oldBlockId);
        }

        byte[] diskBytes = blockManager.readBlock(blockId.fileName(), blockId.blockId());
        victim.assign(blockId, diskBytes);
        victim.pin();
        pageTable.put(blockId, frameIndex);
        return victim;
    }

    @Override
    public synchronized Page fetchPage(String fileName, int blockId) {
        return getPage(fileName, blockId);
    }

    public synchronized Page fetchPage(BlockId blockId) {
        return getPage(blockId);
    }

    @Override
    public synchronized Page allocatePage(String fileName) {
        int frameIndex = acquireFrameIndex();
        Page frame = frames[frameIndex];
        BlockId oldBlockId = frame.getBlockId();
        if (oldBlockId != null) {
            if (frame.isDirty()) {
                flushFrameInternal(frame);
            }
            pageTable.remove(oldBlockId);
            frame.reset();
        }

        byte[] emptyBlock = new byte[blockManager.getBlockSize()];
        int newBlockIndex = blockManager.appendBlock(fileName, emptyBlock);
        BlockId newBlockId = new BlockId(fileName, newBlockIndex);

        frame.assign(newBlockId, emptyBlock);
        frame.pin();
        pageTable.put(newBlockId, frameIndex);
        return frame;
    }

    @Override
    public synchronized Page newPage(String fileName) {
        return allocatePage(fileName);
    }

    @Override
    public synchronized void writePage(String fileName, int pageId, byte[] data, LSN pageLsn) {
        Objects.requireNonNull(data, "data must not be null");
        Page page = getPage(fileName, pageId);
        try {
            if (data.length != page.getPageSize()) {
                throw new IllegalArgumentException(
                    "Data length (" + data.length + ") does not match page size (" + page.getPageSize() + ")"
                );
            }
            System.arraycopy(data, 0, page.getData(), 0, data.length);
            unpinPage(page.getBlockId(), true, pageLsn);
        } catch (RuntimeException e) {
            unpinPage(page.getBlockId(), false, LSN.NONE);
            throw e;
        }
    }

    public synchronized void writePage(String fileName, int pageId, byte[] data, long pageLsn) {
        writePage(fileName, pageId, data, pageLsn <= 0 ? LSN.NONE : new LSN(pageLsn));
    }

    @Override
    public synchronized void unpinPage(String fileName, int blockId, boolean isDirty) {
        unpinPage(new BlockId(fileName, blockId), isDirty, LSN.NONE);
    }

    @Override
    public synchronized void unpinPage(String fileName, int blockId, boolean isDirty, LSN pageLsn) {
        unpinPage(new BlockId(fileName, blockId), isDirty, pageLsn);
    }

    public synchronized void unpinPage(String fileName, int blockId, boolean isDirty, long pageLsn) {
        unpinPage(new BlockId(fileName, blockId), isDirty, pageLsn <= 0 ? LSN.NONE : new LSN(pageLsn));
    }

    public synchronized void unpinPage(BlockId blockId, boolean isDirty) {
        unpinPage(blockId, isDirty, LSN.NONE);
    }

    public synchronized void unpinPage(BlockId blockId, boolean isDirty, LSN pageLsn) {
        Objects.requireNonNull(blockId, "blockId must not be null");
        Integer frameIndex = pageTable.get(blockId);
        if (frameIndex == null) {
            throw new IllegalArgumentException("Page is not in buffer pool: " + blockId);
        }
        Page page = frames[frameIndex];
        if (isDirty) {
            page.markDirty(pageLsn);
        }
        page.unpin();
        if (page.getPinCount() == 0) {
            lruList.remove(frameIndex);
            lruList.addLast(frameIndex);
        }
    }

    public synchronized void unpinPage(BlockId blockId, boolean isDirty, long pageLsn) {
        unpinPage(blockId, isDirty, pageLsn <= 0 ? LSN.NONE : new LSN(pageLsn));
    }

    @Override
    public synchronized boolean flushPage(String fileName, int blockId) {
        return flushPage(new BlockId(fileName, blockId));
    }

    public synchronized boolean flushPage(BlockId blockId) {
        Objects.requireNonNull(blockId, "blockId must not be null");
        Integer frameIndex = pageTable.get(blockId);
        if (frameIndex == null) {
            return false;
        }
        Page page = frames[frameIndex];
        if (page.isDirty()) {
            flushFrameInternal(page);
        }
        return true;
    }

    @Override
    public synchronized void flushFile(String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        String target = fileName.trim();
        for (int i = 0; i < poolSize; i++) {
            Page page = frames[i];
            BlockId id = page.getBlockId();
            if (id != null && id.fileName().equals(target) && page.isDirty()) {
                flushFrameInternal(page);
            }
        }
    }

    @Override
    public synchronized void flushAll() {
        for (int i = 0; i < poolSize; i++) {
            Page page = frames[i];
            if (page.getBlockId() != null && page.isDirty()) {
                flushFrameInternal(page);
            }
        }
    }

    @Override
    public synchronized void discardFilePages(String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        String target = fileName.trim();
        for (int i = 0; i < poolSize; i++) {
            Page page = frames[i];
            BlockId id = page.getBlockId();
            if (id != null && id.fileName().equals(target)) {
                if (page.getPinCount() > 0) {
                    throw new IllegalStateException("Cannot discard pinned page: " + id);
                }
                pageTable.remove(id);
                lruList.remove(i);
                page.reset();
                if (!freeList.contains(i)) {
                    freeList.addLast(i);
                }
            }
        }
    }

    @Override
    public synchronized void resetBufferPool() {
        for (int i = 0; i < poolSize; i++) {
            if (frames[i].getPinCount() > 0) {
                throw new IllegalStateException("Cannot reset buffer pool while page is pinned: " + frames[i].getBlockId());
            }
        }
        pageTable.clear();
        lruList.clear();
        freeList.clear();
        for (int i = 0; i < poolSize; i++) {
            frames[i].reset();
            freeList.addLast(i);
        }
    }

    @Override
    public synchronized void createSnapshot(String snapshotName) {
        flushAll();
        blockManager.copyAllFilesTo(snapshotName);
    }

    public synchronized void createSnapshot(Path targetDirectory) {
        flushAll();
        blockManager.copyAllFilesTo(targetDirectory);
    }

    @Override
    public synchronized void loadSnapshot(String snapshotName) {
        resetBufferPool();
        blockManager.restoreAllFilesFrom(snapshotName);
    }

    public synchronized void loadSnapshot(Path sourceDirectory) {
        resetBufferPool();
        blockManager.restoreAllFilesFrom(sourceDirectory);
    }

    public synchronized boolean isCached(String fileName, int blockId) {
        return pageTable.containsKey(new BlockId(fileName, blockId));
    }

    public synchronized int getCachedPageCount() {
        return pageTable.size();
    }

    private int acquireFrameIndex() {
        if (!freeList.isEmpty()) {
            return freeList.removeFirst();
        }
        return selectVictimFrame();
    }

    private int selectVictimFrame() {
        Iterator<Integer> iterator = lruList.iterator();
        while (iterator.hasNext()) {
            int candidate = iterator.next();
            if (frames[candidate].getPinCount() == 0) {
                iterator.remove();
                return candidate;
            }
        }
        throw new IllegalStateException("Buffer pool exhausted: all " + poolSize + " frames are currently pinned");
    }

    private void flushFrameInternal(Page page) {
        BlockId blockId = page.getBlockId();
        if (blockId == null) {
            return;
        }
        LSN lsn = page.getPageLsn();
        LogFlusher flusher = this.logFlusher;
        if (flusher != null && lsn != null && lsn.compareTo(LSN.NONE) > 0) {
            flusher.flushLogUntil(lsn);
        }
        blockManager.writeBlock(blockId.fileName(), blockId.blockId(), page.getData());
        page.clearDirty();
    }
}
