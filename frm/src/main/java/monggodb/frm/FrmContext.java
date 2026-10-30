package monggodb.frm;

import monggodb.contracts.sm.StorageManager;


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
