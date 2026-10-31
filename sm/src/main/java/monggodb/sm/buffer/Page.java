package monggodb.sm.buffer;

import java.util.Arrays;
import java.util.Objects;
import monggodb.contracts.shared.LSN;

public final class Page {
    private BlockId blockId;
    private final byte[] data;
    private boolean dirty;
    private int pinCount;
    private LSN pageLsn;

    public Page(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
        }
        this.blockId = null;
        this.data = new byte[pageSize];
        this.dirty = false;
        this.pinCount = 0;
        this.pageLsn = LSN.NONE;
    }

    public Page(BlockId blockId, byte[] initialData) {
        Objects.requireNonNull(initialData, "initialData must not be null");
        if (initialData.length == 0) {
            throw new IllegalArgumentException("initialData must not be empty");
        }
        this.blockId = blockId;
        this.data = Arrays.copyOf(initialData, initialData.length);
        this.dirty = false;
        this.pinCount = 0;
        this.pageLsn = LSN.NONE;
    }

    public synchronized BlockId getBlockId() {
        return blockId;
    }

    public synchronized PageKey getPageKey() {
        return blockId == null ? null : new PageKey(blockId.fileName(), blockId.blockId());
    }

    public synchronized String getFileName() {
        return blockId == null ? null : blockId.fileName();
    }

    public synchronized int getPageId() {
        return blockId == null ? -1 : blockId.blockId();
    }

    public byte[] getData() {
        return data;
    }

    public int getPageSize() {
        return data.length;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized int getPinCount() {
        return pinCount;
    }

    public synchronized LSN getPageLsn() {
        return pageLsn;
    }

    public synchronized void setPageLsn(LSN pageLsn) {
        this.pageLsn = pageLsn == null ? LSN.NONE : pageLsn;
    }

    public synchronized void markDirty(LSN lsn) {
        this.dirty = true;
        LSN candidate = lsn == null ? LSN.NONE : lsn;
        if (candidate.compareTo(LSN.NONE) > 0 && candidate.compareTo(this.pageLsn) > 0) {
            this.pageLsn = candidate;
        }
    }

    public synchronized void markDirty(long lsn) {
        markDirty(lsn <= 0 ? LSN.NONE : new LSN(lsn));
    }

    synchronized void clearDirty() {
        this.dirty = false;
    }

    synchronized void pin() {
        this.pinCount++;
    }

    synchronized void unpin() {
        if (this.pinCount <= 0) {
            throw new IllegalStateException("Cannot unpin page with pinCount == 0 (" + blockId + ")");
        }
        this.pinCount--;
    }

    synchronized void assign(BlockId newBlockId, byte[] blockBytes) {
        Objects.requireNonNull(blockBytes, "blockBytes must not be null");
        if (blockBytes.length != this.data.length) {
            throw new IllegalArgumentException(
                "Block size mismatch: expected " + this.data.length + " but got " + blockBytes.length
            );
        }
        this.blockId = newBlockId;
        System.arraycopy(blockBytes, 0, this.data, 0, this.data.length);
        this.dirty = false;
        this.pinCount = 0;
        this.pageLsn = LSN.NONE;
    }

    synchronized void reset() {
        this.blockId = null;
        Arrays.fill(this.data, (byte) 0);
        this.dirty = false;
        this.pinCount = 0;
        this.pageLsn = LSN.NONE;
    }
}
