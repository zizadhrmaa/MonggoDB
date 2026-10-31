package monggodb.ccm.lifecycle;

import java.util.HashMap;
import java.util.Map;

import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/** Mock state transaksi internal CC1. Tidak mencatat log atau menjalankan commit/rollback. */
public class MockTransactionState {
    private final Map<TxId, TransactionStatus> statuses = new HashMap<>();
    private long lastTxId;

    /** Mengalokasikan ID sekaligus timestamp logis, tanpa mendaftarkan transaksi. */
    public synchronized TxId nextTxId() {
        if (lastTxId == Long.MAX_VALUE) throw new CCMException(CCMException.Code.INVALID_STATE, "TxId sudah habis");
        return new TxId(++lastTxId);
    }

    /** Memvalidasi transisi lifecycle; COMMITTED dan ABORTED bersifat final. */
    public synchronized void transitionState(TxId txId, TransactionStatus expectedState, TransactionStatus nextState) {
        TransactionStatus current = statuses.get(txId);
        if (current == null) throw new CCMException(CCMException.Code.TX_UNKNOWN, "Transaksi tidak dikenal: " + txId);
        if (expectedState == null || nextState == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "Status asal dan tujuan wajib diisi");
        }
        boolean allowed = switch (current) {
            case ACTIVE -> nextState == TransactionStatus.PARTIALLY_COMMITTED || nextState == TransactionStatus.FAILED;
            case PARTIALLY_COMMITTED -> nextState == TransactionStatus.COMMITTED || nextState == TransactionStatus.FAILED;
            case FAILED -> nextState == TransactionStatus.ABORTED;
            case COMMITTED, ABORTED -> false;
        };
        if (current != expectedState || !allowed) {
            throw new CCMException(CCMException.Code.INVALID_STATE, "Transisi status tidak valid: " + current + " ke " + nextState);
        }
        statuses.put(txId, nextState);
    }

    /** Menolak transaksi tidak dikenal atau bukan ACTIVE. */
    public synchronized TransactionStatus ensureActive(TxId txId) {
        TransactionStatus current = statuses.get(txId);
        if (current == null) throw new CCMException(CCMException.Code.TX_UNKNOWN, "Transaksi tidak dikenal: " + txId);
        if (current != TransactionStatus.ACTIVE) throw new CCMException(CCMException.Code.INVALID_STATE, "Transaksi tidak ACTIVE: " + txId);
        return current;
    }

    /** Mereset state CC1 setelah recovery; tidak menyentuh admission atau state protokol CC lain. */
    public synchronized void resetAfterRecovery(RecoveryResult result) {
        if (result == null) throw new CCMException(CCMException.Code.INVALID_REQUEST, "Hasil recovery wajib diisi");
        if (!result.success()) throw new CCMException(CCMException.Code.RECOVERY_FAILED, "Recovery tidak berhasil");
        if (result.maxTxId() != null) lastTxId = Math.max(lastTxId, result.maxTxId().value());
        statuses.clear();
    }

    /** Menyiapkan fixture test dan menaikkan batas counter agar ID fixture tidak dialokasikan ulang. */
    public synchronized void setTransactionStatus(TxId txId, TransactionStatus status) {
        if (txId == null || status == null) throw new CCMException(CCMException.Code.INVALID_REQUEST, "txId dan status wajib diisi");
        statuses.put(txId, status);
        lastTxId = Math.max(lastTxId, txId.value());
    }

    /** @return snapshot state dummy yang tidak bisa diubah pemanggil */
    public synchronized Map<TxId, TransactionStatus> getStatuses() {
        return Map.copyOf(statuses);
    }
}
