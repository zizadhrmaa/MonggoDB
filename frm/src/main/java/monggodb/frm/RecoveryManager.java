package monggodb.frm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.sm.StorageManager;


final class RecoveryManager {

    /** Status satu transaksi hasil analyze, beserta entri COMMIT-nya jika ada. */
    record TxInfo(TransactionTable.TxStatus status, LogRecord commit) {
    }

    private final FrmContext ctx;
    private final RollbackManager rollback;
    private final CheckpointManager checkpoints;

    RecoveryManager(FrmContext ctx, RollbackManager rollback, CheckpointManager checkpoints) {
        this.ctx = ctx;
        this.rollback = rollback;
        this.checkpoints = checkpoints;
    }

    RecoveryResult recover() {
        checkpoints.maintenanceLock().lock();
        try {
            TxId maxTx = maxTxId(ctx.log.readAll());
            String corruption = ctx.log.corruption();
            if (corruption != null) {
                return LogRecords.failed(maxTx, ctx.log.lastLsn(), "Pemulihan gagal, log rusak: " + corruption);
            }
            StorageManager storage = ctx.sm;
            if (storage == null) {
                return LogRecords.failed(maxTx, ctx.log.lastLsn(), "Pemulihan gagal: Storage Manager belum dipasang ke FRM");
            }

            LSN from = new LSN(CheckpointManager.findLastCheckpoint(ctx.log).value() + 1);
            Map<TxId, TxInfo> status = analyze(ctx, from);

            // Redo: ulangi seluruh riwayat (termasuk kompensasi dan perubahan transaksi yang belum selesai).
            Set<TxId> redone = new TreeSet<>();
            List<LogRecord> loserChanges = new ArrayList<>();
            int replayed = 0;
            Iterator<LogRecord> it = ctx.log.readLogFrom(from, LogManager.Direction.FORWARD);
            while (it.hasNext()) {
                LogRecord r = it.next();
                if (!LogRecords.isDataChange(r.type()) || ctx.meta.isDiscarded(r.lsn())) continue;
                redoRecord(storage, r);
                replayed++;
                TransactionTable.TxStatus s = status.get(r.txId()).status();
                if (s == TransactionTable.TxStatus.COMMITTED) redone.add(r.txId());
                if (s == TransactionTable.TxStatus.ACTIVE) loserChanges.add(r);
            }

            // Undo: batalkan transaksi yang belum selesai dalam urutan LSN terbalik, dengan catatan kompensasi.
            for (int i = loserChanges.size() - 1; i >= 0; i--) {
                rollback.undoRecord(storage, loserChanges.get(i));
            }
            Set<TxId> undone = new TreeSet<>();
            for (Map.Entry<TxId, TxInfo> e : status.entrySet()) {
                if (e.getValue().status() != TransactionTable.TxStatus.ACTIVE) continue;
                ctx.log.appendToBuffer(LogManager.marker(e.getKey(), LogType.ABORT));
                undone.add(e.getKey());
            }
            ctx.log.forceToDisk(ctx.log.lastLsn());
            ctx.txs.rebuild(ctx.log.readAll());

            // Simpan hasil ke disk lalu buat checkpoint agar restart berikutnya tidak mengulang dari awal.
            LSN cp = replayed == 0 && undone.isEmpty() ? ctx.log.lastLsn() : checkpoints.writeCheckpoint(storage);
            return new RecoveryResult(true, maxTx, cp, new ArrayList<>(redone), new ArrayList<>(undone),
                    "Pemulihan selesai: " + redone.size() + " transaksi di-redo, " + undone.size() + " transaksi di-undo");
        } catch (RuntimeException e) {
            return LogRecords.failed(maxTxId(ctx.log.readAll()), ctx.log.lastLsn(), "Pemulihan gagal: " + e.getMessage());
        } finally {
            checkpoints.maintenanceLock().unlock();
        }
    }

  
    static Map<TxId, TxInfo> analyze(FrmContext ctx, LSN from) {
        Map<TxId, TxInfo> status = new LinkedHashMap<>();
        Iterator<LogRecord> it = ctx.log.readLogFrom(from, LogManager.Direction.FORWARD);
        while (it.hasNext()) {
            LogRecord r = it.next();
            if (r.txId() == null || ctx.meta.isDiscarded(r.lsn())) continue;
            switch (r.type()) {
                case COMMIT -> status.put(r.txId(), new TxInfo(TransactionTable.TxStatus.COMMITTED, r));
                case ABORT -> status.put(r.txId(), new TxInfo(TransactionTable.TxStatus.ABORTED, null));
                default -> status.putIfAbsent(r.txId(), new TxInfo(TransactionTable.TxStatus.ACTIVE, null));
            }
        }
        return status;
    }

    /** redoRecord: menerapkan ulang satu perubahan ke SM secara idempoten dengan LSN catatan tersebut. */
    static void redoRecord(StorageManager storage, LogRecord r) {
        apply(storage, r, r.lsn());
    }

 
    static void apply(StorageManager storage, LogRecord r, LSN lsn) {
        RID rid = r.rid();
        try {
            switch (r.type()) {
                case INSERT -> storage.insertRecordAt(r.table(), rid, r.after().withRid(rid), lsn);
                case UPDATE -> {
                    Row row = r.after().withRid(rid);
                    if (storage.fetchRecord(r.table(), rid).isPresent()) {
                        storage.updateRecord(r.table(), rid, row, lsn);
                    } else {
                        storage.insertRecordAt(r.table(), rid, row, lsn);
                    }
                }
                case DELETE -> storage.deleteRecord(r.table(), rid, lsn);
                default -> throw new IllegalArgumentException("Bukan perubahan data: " + r.type());
            }
        } catch (StorageException e) {
            if (e.getErrorCode() != StorageException.Code.TABLE_NOT_FOUND) throw e;
        }
    }

    /** @return ID transaksi terbesar di log, atau null jika log tidak memuat transaksi */
    static TxId maxTxId(List<LogRecord> all) {
        TxId max = null;
        for (LogRecord r : all) {
            if (r.txId() != null && (max == null || r.txId().compareTo(max) > 0)) max = r.txId();
        }
        return max;
    }
}
