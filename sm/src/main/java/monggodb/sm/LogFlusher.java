package monggodb.sm;

@FunctionalInterface
public interface LogFlusher {
    void flushLogUntil(long lsn);
}
