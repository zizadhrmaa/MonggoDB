package monggodb.sm;

import java.util.Arrays;
import java.util.Objects;

public final class Page {
    private BlockId blockId;
    private final byte[] data;
    private int pinCount;
    private boolean dirty;
    private long pageLsn;

    public Page(int blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        this.data = new byte[blockSize];
        this.pageLsn = -1L;
    }

    public BlockId getBlockId() {
        return blockId;
    }

    public String getFileName() {
        return blockId != null ? blockId.fileName() : null;
    }

    public int getPageId() {
        return blockId != null ? blockId.blockId() : -1;
    }

    public byte[] getData() {
        return data;
    }

    public int getPinCount() {
        return pinCount;
    }

    public boolean isPinned() {
        return pinCount > 0;
    }

    public boolean isDirty() {
        return dirty;
    }

    public long getPageLsn() {
        return pageLsn;
    }

    public void markDirty() {
        this.dirty = true;
        if (this.pageLsn < 0) {
            this.pageLsn = Long.MAX_VALUE;
        }
    }

    public void markDirty(long lsn) {
        this.dirty = true;
        if (lsn >= 0) {
            if (this.pageLsn == Long.MAX_VALUE || lsn > this.pageLsn) {
                this.pageLsn = lsn;
            }
        } else if (this.pageLsn < 0) {
            this.pageLsn = Long.MAX_VALUE;
        }
    }

    public void writeBytes(int offset, byte[] src) {
        writeBytes(offset, src, -1L);
    }

    public void writeBytes(int offset, byte[] src, long lsn) {
        Objects.requireNonNull(src, "src must not be null");
        if (offset < 0 || offset + src.length > data.length) {
            throw new IndexOutOfBoundsException("Write out of bounds: offset=" + offset + ", length=" + src.length);
        }
        System.arraycopy(src, 0, data, offset, src.length);
        markDirty(lsn);
    }

    public byte[] readBytes(int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Read out of bounds: offset=" + offset + ", length=" + length);
        }
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    void bind(BlockId newBlockId) {
        this.blockId = newBlockId;
        this.pinCount = 1;
        this.dirty = false;
        this.pageLsn = -1L;
    }

    void pin() {
        this.pinCount++;
    }

    void unpin() {
        if (this.pinCount <= 0) {
            throw new IllegalStateException("Page is not pinned: " + blockId);
        }
        this.pinCount--;
    }

    void clearDirty() {
        this.dirty = false;
        this.pageLsn = -1L;
    }

    void clear() {
        this.blockId = null;
        Arrays.fill(this.data, (byte) 0);
        this.pinCount = 0;
        this.dirty = false;
        this.pageLsn = -1L;
    }
}
