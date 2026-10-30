package monggodb.contracts.shared;

/**
 * Konfigurasi startup CCM. Immutable, diberikan app lewat konstruktor implementasi CCM.
 * Target penyelesaian deadlock hanya dicek driver, bukan timeout untuk membatalkan transaksi.
 *
 * @param protocol protokol saat startup
 * @param deadlockCheckIntervalMs interval detector deadlock, hanya aktif pada LOCK_BASED
 * @param deadlockResolveTimeoutMs target waktu penyelesaian abort victim, dalam ms
 */

public record CCMConfig(ProtocolType protocol, long deadlockCheckIntervalMs,
                        long deadlockResolveTimeoutMs) {
    /** Menjaga invarian. */
    public CCMConfig {
        if (protocol == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "protocol CCMConfig tidak boleh null");
        }
        if (deadlockCheckIntervalMs <= 0 || deadlockResolveTimeoutMs <= 0) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "deadlockCheckIntervalMs dan deadlockResolveTimeoutMs harus positif");
        }
    }

    /** Konfigurasi default. */
    public CCMConfig() {
        this(ProtocolType.LOCK_BASED, 1000L, 5000L);
    }
}
