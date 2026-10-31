package monggodb.qp.execution;

import java.util.Objects;

import monggodb.contracts.ccm.ConcurrencyControlManager;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.sm.StorageManager;

/**
 * Konteks eksekusi satu statement: transaksi yang sedang berjalan, Storage Manager, dan
 * Concurrency Control Manager yang dipakai operator. Immutable.
 */
public final class ExecutionContext {

    /** Batas waktu default menunggu izin yang berstatus WAITING. */
    public static final long DEFAULT_ACCESS_TIMEOUT_MS = 30_000;

    /** Transaksi pemilik statement. */
    private final TxId txId;

    /** Storage Manager untuk membaca dan menulis record. */
    private final StorageManager storage;

    /** Concurrency Control Manager untuk meminta dan menutup izin akses. */
    private final ConcurrencyControlManager ccm;

    /** Batas waktu menunggu izin yang berstatus WAITING, dalam milidetik. */
    private final long accessTimeoutMs;

    /**
     * @param txId    transaksi pemilik statement
     * @param storage Storage Manager
     * @param ccm     Concurrency Control Manager
     */
    public ExecutionContext(TxId txId, StorageManager storage, ConcurrencyControlManager ccm) {
        this(txId, storage, ccm, DEFAULT_ACCESS_TIMEOUT_MS);
    }

    /**
     * @param txId transaksi pemilik statement
     * @param storage Storage Manager
     * @param ccm Concurrency Control Manager
     * @param accessTimeoutMs batas waktu menunggu izin WAITING dalam milidetik
     * @throws IllegalArgumentException jika accessTimeoutMs negatif
     */
    public ExecutionContext(TxId txId, StorageManager storage, ConcurrencyControlManager ccm, long accessTimeoutMs) {
        this.txId = Objects.requireNonNull(txId, "txId tidak boleh null");
        this.storage = Objects.requireNonNull(storage, "storage tidak boleh null");
        this.ccm = Objects.requireNonNull(ccm, "ccm tidak boleh null");
        if (accessTimeoutMs < 0) {
            throw new IllegalArgumentException("accessTimeoutMs tidak boleh negatif: " + accessTimeoutMs);
        }
        this.accessTimeoutMs = accessTimeoutMs;
    }

    /** @return transaksi pemilik statement */
    public TxId getTxId() {
        return txId;
    }

    /** @return Storage Manager */
    public StorageManager getStorage() {
        return storage;
    }

    /** @return Concurrency Control Manager */
    public ConcurrencyControlManager getCcm() {
        return ccm;
    }

    /** @return batas waktu menunggu izin WAITING dalam milidetik */
    public long getAccessTimeoutMs() {
        return accessTimeoutMs;
    }
}
