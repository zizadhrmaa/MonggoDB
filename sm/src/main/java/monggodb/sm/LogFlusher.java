package monggodb.sm;

import monggodb.contracts.shared.LSN;

@FunctionalInterface
public interface LogFlusher {
    void flushLogUntil(LSN lsn);
}
