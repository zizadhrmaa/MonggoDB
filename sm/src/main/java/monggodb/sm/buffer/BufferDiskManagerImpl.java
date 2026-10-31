package monggodb.sm.buffer;

import java.nio.file.Path;

public final class BufferDiskManagerImpl extends BufferPoolManager {

    public BufferDiskManagerImpl(Path dataDirectory) {
        super(dataDirectory);
    }

    public BufferDiskManagerImpl(Path dataDirectory, int poolSize) {
        super(dataDirectory, poolSize);
    }

    public BufferDiskManagerImpl(BlockManager blockManager) {
        super(blockManager);
    }

    public BufferDiskManagerImpl(BlockManager blockManager, int poolSize) {
        super(blockManager, poolSize);
    }

    public BufferDiskManagerImpl(BlockManager blockManager, int poolSize, LogFlusher logFlusher) {
        super(blockManager, poolSize, logFlusher);
    }
}
