package monggodb.contracts.shared;

/** Pilihan protokol concurrency control di facade CCM. */
public enum ProtocolType {
    /** Strict two-phase locking, protokol default. */
    LOCK_BASED,
    /** Strict Timestamp Ordering dengan timestamp sama dengan TxId. */
    TIMESTAMP_BASED
}
