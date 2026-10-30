package monggodb.frm;

import monggodb.contracts.sm.StorageManager;

/**
 * Objek bersama milik satu instans FRM: log, metadata, tabel transaksi, serta referensi SM
 * yang dipasang app. Dipakai bersama oleh LogManager, RollbackManager, CheckpointManager, dan RecoveryManager.
 */
final class FrmContext {

    final LogManager log;
    final FrmMetadata meta;
    final TransactionTable txs;
    final long admissionTimeoutMs;

    volatile StorageManager sm;

    FrmContext(LogManager log, FrmMetadata meta, TransactionTable txs, long admissionTimeoutMs) {
        this.log = log;
        this.meta = meta;
        this.txs = txs;
        this.admissionTimeoutMs = admissionTimeoutMs;
    }
}
