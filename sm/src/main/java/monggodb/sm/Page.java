package monggodb.sm;

import java.util.Arrays;
import java.util.Objects;
import monggodb.contracts.shared.LSN;

public final class Page {
    private BlockId blockId;
    private final byte[] data;
    private int pinCount;
    private boolean dirty;
    private LSN pageLsn;

    public Page(int blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        this.data = new byte[blockSize];
        this.pageLsn = LSN.NONE;
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

    public LSN getPageLsn() {
        return pageLsn;
    }

    public void markDirty() {
        markDirty(LSN.NONE);
    }

    public void markDirty(long lsn) {
        markDirty(lsn < 0 ? LSN.NONE : new LSN(lsn));
    }

    public void markDirty(LSN lsn) {
        this.dirty = true;
        LSN candidate = lsn == null ? LSN.NONE : lsn;
        if (!LSN.NONE.equals(candidate) && candidate.compareTo(LSN.NONE) > 0) {
            if (LSN.NONE.equals(this.pageLsn) || candidate.compareTo(this.pageLsn) > 0) {
                this.pageLsn = candidate;
            }
        }
    }

    public void writeBytes(int offset, byte[] src) {
        writeBytes(offset, src, LSN.NONE);
    }

    public void writeBytes(int offset, byte[] src, long lsn) {
        writeBytes(offset, src, lsn < 0 ? LSN.NONE : new LSN(lsn));
    }

    public void writeBytes(int offset, byte[] src, LSN lsn) {
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
        this.pageLsn = LSN.NONE;
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
        this.pageLsn = LSN.NONE;
    }

    void clear() {
        this.blockId = null;
        Arrays.fill(this.data, (byte) 0);
        this.pinCount = 0;
        this.dirty = false;
        this.pageLsn = LSN.NONE;
    }
}
