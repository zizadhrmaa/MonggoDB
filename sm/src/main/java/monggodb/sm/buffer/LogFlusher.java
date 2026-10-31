package monggodb.sm.buffer;

import monggodb.contracts.shared.LSN;

@FunctionalInterface
public interface LogFlusher {
    void flushLogUntil(LSN lsn);
}
