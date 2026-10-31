package monggodb.frm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RollbackResult;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.sm.StorageManager;


final class RollbackManager {

    private final FrmContext ctx;

    RollbackManager(FrmContext ctx) {
        this.ctx = ctx;
    }

    RollbackResult rollback(TxId txId) {
        TransactionTable.TxState st = ctx.txs.get(txId);
        if (st == null) {
            return new RollbackResult(RollbackResult.Status.TX_UNKNOWN, "Transaksi " + txId + " tidak dikenal FRM");
        }
        synchronized (st) {
            if (st.status == TransactionTable.TxStatus.COMMITTED) {
                return new RollbackResult(RollbackResult.Status.TX_COMMITTED, "Transaksi " + txId + " sudah commit dan tidak dapat dibatalkan");
            }
            if (st.status == TransactionTable.TxStatus.ABORTED) {
                return new RollbackResult(RollbackResult.Status.ALREADY_ABORTED, "Transaksi " + txId + " sudah dibatalkan sebelumnya");
            }
            List<LogRecord> changes = changesNewestFirst(txId);
            StorageManager storage = ctx.sm;
            if (storage == null && !changes.isEmpty()) {
                return new RollbackResult(RollbackResult.Status.FAILED, "Storage Manager belum dipasang ke FRM");
            }
            try {
                for (LogRecord r : changes) undoRecord(storage, r);
                LogRecord abort = ctx.log.appendToBuffer(LogManager.marker(txId, LogType.ABORT));
                ctx.log.forceToDisk(abort.lsn());
                st.status = TransactionTable.TxStatus.ABORTED;
                return new RollbackResult(RollbackResult.Status.DONE,
                        "Transaksi " + txId + " dibatalkan, " + changes.size() + " perubahan di-undo");
            } catch (RuntimeException e) {
                return new RollbackResult(RollbackResult.Status.FAILED,
                        "Rollback " + txId + " gagal: " + e.getMessage() + ". Lock transaksi harus tetap ditahan.");
            }
        }
    }


    private List<LogRecord> changesNewestFirst(TxId txId) {
        List<LogRecord> out = new ArrayList<>();
        Iterator<LogRecord> it = ctx.log.readLogFrom(ctx.log.lastLsn(), LogManager.Direction.BACKWARD);
        while (it.hasNext()) {
            LogRecord r = it.next();
            if (!txId.equals(r.txId())) continue;
            if (r.type() == LogType.BEGIN) break;
            if (LogRecords.isDataChange(r.type())) out.add(r);
        }
        return out;
    }


    void undoRecord(StorageManager storage, LogRecord original) {
        LogRecord clr = ctx.log.appendToBuffer(inverse(original));
        RecoveryManager.apply(storage, clr, original.lsn());
    }

    private static LogRecord inverse(LogRecord r) {
        return switch (r.type()) {
            case INSERT -> LogRecords.change(r.txId(), LogType.DELETE, r.table(), r.rid(), r.after(), null);
            case UPDATE -> LogRecords.change(r.txId(), LogType.UPDATE, r.table(), r.rid(), r.after(), r.before());
            case DELETE -> LogRecords.change(r.txId(), LogType.INSERT, r.table(), r.rid(), null, r.before());
            default -> throw new IllegalArgumentException("Bukan perubahan data: " + r.type());
        };
    }
}
