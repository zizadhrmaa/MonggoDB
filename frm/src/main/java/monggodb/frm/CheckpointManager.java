package monggodb.frm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import monggodb.contracts.shared.CheckpointResult;
import monggodb.contracts.shared.FRMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.RecoveryTarget;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.sm.StorageManager;


final class CheckpointManager {

    /** Nama snapshot awal yang dibuat lewat SM.createSnapshot. */
    static final String INITIAL_SNAPSHOT = "frm-initial";

    private final FrmContext ctx;
    private final ReentrantLock maintenanceLock = new ReentrantLock(true);
    private ScheduledExecutorService periodic;

    CheckpointManager(FrmContext ctx) {
        this.ctx = ctx;
    }

    ReentrantLock maintenanceLock() {
        return maintenanceLock;
    }

    /**
     * createInitialSnapshot: membuat snapshot awal untuk pemulihan historis jika belum pernah dibuat.
     * @return nama snapshot, atau null jika SM belum dipasang
     */
    String createInitialSnapshot() {
        if (ctx.meta.hasSnapshot()) return ctx.meta.snapshotName();
        StorageManager storage = ctx.sm;
        if (storage == null) return null;
        try {
            long time = System.currentTimeMillis();
            LSN at = ctx.log.lastLsn();
            storage.createSnapshot(INITIAL_SNAPSHOT);
            ctx.meta.setSnapshot(INITIAL_SNAPSHOT, at, time);
            ctx.meta.save();
            return INITIAL_SNAPSHOT;
        } catch (StorageException e) {
            throw new FRMException(FRMException.Code.RECOVERY_FAILED, "Gagal membuat snapshot awal: " + e.getMessage(), e);
        }
    }


    // setelah interface CCM ada di main.
    CheckpointResult checkpoint() {
        maintenanceLock.lock();
        try {
            StorageManager storage = ctx.sm;
            if (storage == null) {
                return new CheckpointResult(false, LSN.NONE, "Checkpoint gagal: Storage Manager belum dipasang ke FRM");
            }
            LSN end = writeCheckpoint(storage);
            return new CheckpointResult(true, end, "Checkpoint selesai pada " + end);
        } catch (RuntimeException e) {
            return new CheckpointResult(false, LSN.NONE, "Checkpoint gagal: " + e.getMessage());
        } finally {
            maintenanceLock.unlock();
        }
    }


    LSN writeCheckpoint(StorageManager storage) {
        LogRecord start = ctx.log.appendToBuffer(LogManager.marker(null, LogType.CHECKPOINT_START));
        ctx.log.forceToDisk(start.lsn());
        storage.flushAll();
        LogRecord end = ctx.log.appendToBuffer(LogManager.marker(null, LogType.CHECKPOINT_END));
        ctx.log.forceToDisk(end.lsn());
        return end.lsn();
    }


    static LSN findLastCheckpoint(LogManager log) {
        LSN end = LSN.NONE;
        Iterator<LogRecord> it = log.readLogFrom(log.lastLsn(), LogManager.Direction.BACKWARD);
        while (it.hasNext()) {
            LogRecord r = it.next();
            if (LogRecords.isNone(end) && r.type() == LogType.CHECKPOINT_END) {
                end = r.lsn();
            } else if (!LogRecords.isNone(end) && r.type() == LogType.CHECKPOINT_START) {
                return end;
            }
        }
        return LSN.NONE;
    }

    synchronized void startPeriodicCheckpoint(long intervalMs) {
        if (intervalMs <= 0 || periodic != null) return;
        periodic = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "frm-periodic-checkpoint");
            t.setDaemon(true);
            return t;
        });
        periodic.scheduleWithFixedDelay(this::periodicCheckpointLoop, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Satu putaran thread latar checkpoint berkala. */
    private void periodicCheckpointLoop() {
        CheckpointResult r = checkpoint();
        if (!r.success()) System.err.println("[FRM] " + r.message());
    }

    void stopPeriodicCheckpoint() {
        ScheduledExecutorService p;
        synchronized (this) {
            p = periodic;
            periodic = null;
        }
        if (p == null) return;
        p.shutdown();
        try {
            p.awaitTermination(ctx.admissionTimeoutMs + 60_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    RecoveryResult restoreTo(RecoveryTarget target) {
        maintenanceLock.lock();
        try {
            TxId maxTx = RecoveryManager.maxTxId(ctx.log.readAll());
            if (target == null) return LogRecords.failed(maxTx, ctx.log.lastLsn(), "Target pemulihan tidak boleh kosong");
            if (ctx.log.corruption() != null) {
                return LogRecords.failed(maxTx, ctx.log.lastLsn(), "Pemulihan historis ditolak, log rusak: " + ctx.log.corruption());
            }
            StorageManager storage = ctx.sm;
            if (storage == null || !ctx.meta.hasSnapshot()) {
                return LogRecords.failed(maxTx, ctx.log.lastLsn(), "Pemulihan historis ditolak: snapshot awal atau Storage Manager tidak tersedia");
            }
            ctx.log.forceToDisk(ctx.log.lastLsn());
            LSN snapLsn = ctx.meta.snapshotLsn();
            LSN from = new LSN(snapLsn.value() + 1);

            // Riwayat aktif: setelah snapshot dan tidak dibuang oleh restore sebelumnya.
            Map<TxId, LogRecord> commits = new LinkedHashMap<>();
            for (Map.Entry<TxId, RecoveryManager.TxInfo> e : RecoveryManager.analyze(ctx, from).entrySet()) {
                if (e.getValue().commit() != null) commits.put(e.getKey(), e.getValue().commit());
            }

            // Tentukan batas LSN; ditolak sebelum basis data aktif diubah jika di luar rentang.
            LSN bound = boundFor(target, commits, snapLsn);

            Set<TxId> included = new HashSet<>();
            for (LogRecord c : commits.values()) {
                if (c.lsn().compareTo(bound) <= 0) included.add(c.txId());
            }
            LSN end = ctx.log.lastLsn();

            storage.loadSnapshot(ctx.meta.snapshotName());
            Iterator<LogRecord> it = ctx.log.readLogFrom(from, LogManager.Direction.FORWARD);
            while (it.hasNext()) {
                LogRecord r = it.next();
                if (r.lsn().compareTo(bound) > 0) break;
                if (ctx.meta.isDiscarded(r.lsn())) continue;
                if (LogRecords.isDataChange(r.type()) && included.contains(r.txId())) RecoveryManager.redoRecord(storage, r);
            }
            storage.flushAll();
            ctx.meta.addDiscarded(bound.value() + 1, end.value());
            ctx.meta.save();
            LSN cp = writeCheckpoint(storage);

            Set<TxId> excluded = new TreeSet<>();
            for (TxId t : commits.keySet()) {
                if (!included.contains(t)) excluded.add(t);
            }
            return new RecoveryResult(true, RecoveryManager.maxTxId(ctx.log.readAll()), cp,
                    new ArrayList<>(new TreeSet<>(included)), new ArrayList<>(excluded),
                    "Basis data dipulihkan sampai " + bound + ": " + included.size() + " transaksi committed disertakan, "
                            + excluded.size() + " transaksi committed setelah target tidak disertakan");
        } catch (FRMException e) {
            return LogRecords.failed(RecoveryManager.maxTxId(ctx.log.readAll()), ctx.log.lastLsn(), e.getCode() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return LogRecords.failed(RecoveryManager.maxTxId(ctx.log.readAll()), ctx.log.lastLsn(), "Pemulihan historis gagal: " + e.getMessage());
        } finally {
            maintenanceLock.unlock();
        }
    }

    private LSN boundFor(RecoveryTarget target, Map<TxId, LogRecord> commits, LSN snapLsn) {
        if (target.kind() == RecoveryTarget.Kind.TX_ID) {
            LogRecord c = commits.get(target.txId());
            if (c == null) {
                throw new FRMException(FRMException.Code.RESTORE_OUT_OF_RANGE, "Transaksi " + target.txId()
                        + " tidak memiliki COMMIT di riwayat yang tersedia (belum commit, dibatalkan, sebelum snapshot, atau sudah dibuang restore sebelumnya)");
            }
            return c.lsn();
        }
        long t = target.time();
        if (t < ctx.meta.snapshotTime()) {
            throw new FRMException(FRMException.Code.RESTORE_OUT_OF_RANGE,
                    "Waktu target " + t + " lebih awal dari snapshot awal (" + ctx.meta.snapshotTime() + ")");
        }
        if (t > System.currentTimeMillis()) {
            throw new FRMException(FRMException.Code.RESTORE_OUT_OF_RANGE, "Waktu target " + t + " berada di masa depan");
        }
        LSN bound = snapLsn;
        for (LogRecord c : commits.values()) {
            if (c.timestamp() <= t && c.lsn().compareTo(bound) > 0) bound = c.lsn();
        }
        return bound;
    }
}
