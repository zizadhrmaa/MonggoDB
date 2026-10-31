package monggodb.sm.buffer;

import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.shared.LSN;

public interface BufferDiskManager {

    void flushAll();

    void readBlock(String fileName, int blockId, byte[] dest);

    byte[] readBlock(String fileName, int blockId);

    void writeBlock(String fileName, int blockId, byte[] data);

    int appendBlock(String fileName);

    int appendBlock(String fileName, byte[] data);

    int getBlockCount(String fileName);

    void deleteFile(String fileName);

    Page fetchPage(String fileName, int pageId);

    Page getPage(String fileName, int pageId);

    Page newPage(String fileName);

    Page allocatePage(String fileName);

    void writePage(String fileName, int pageId, byte[] data, LSN pageLsn);

    void unpinPage(String fileName, int pageId, boolean isDirty, LSN pageLsn);

    void unpinPage(String fileName, int pageId, boolean isDirty);

    boolean flushPage(String fileName, int pageId);

    void flushFile(String fileName);

    void discardFilePages(String fileName);

    void resetBufferPool();

    void createSnapshot(String snapshotName);

    void loadSnapshot(String snapshotName);

    void setLogFlusher(LogFlusher logFlusher);

    void setFailureRecoveryManager(FailureRecoveryManager frm);
}
