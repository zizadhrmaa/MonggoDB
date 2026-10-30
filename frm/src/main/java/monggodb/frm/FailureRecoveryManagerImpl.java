package monggodb.frm;

import java.nio.file.Path;
import java.util.List;

import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.shared.CheckpointResult;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.RecoveryTarget;
import monggodb.contracts.shared.RollbackResult;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.sm.StorageManager;

/**
 * Facade Failure Recovery Manager (FR1). Tidak mengerjakan logika berat; setiap panggilan diteruskan
 * ke manajer yang tepat:
 * <ul>
 *   <li>logBegin, logChange, logCommit, flushLogUntil, readLog: {@link LogManager}</li>
 *   <li>rollback: {@link RollbackManager}</li>
 *   <li>checkpoint, restoreTo, checkpoint berkala: {@link CheckpointManager}</li>
 *   <li>recover: {@link RecoveryManager}</li>
 * </ul>
 */
public class FailureRecoveryManagerImpl implements FailureRecoveryManager {

    /** Nama file log di dalam folder log. */
    public static final String LOG_FILE = "wal.log";
    /** Nama file metadata FRM di dalam folder log. */
    public static final String META_FILE = "frm.meta";
    /** Nama snapshot awal yang dibuat lewat SM.createSnapshot. */
    public static final String INITIAL_SNAPSHOT = CheckpointManager.INITIAL_SNAPSHOT;
    /** Batas waktu default menunggu transaksi aktif selesai saat checkpoint dan restoreTo. */
    public static final long DEFAULT_ADMISSION_TIMEOUT_MS = 30_000;

    private final FrmContext ctx;
    private final LogManager logManager;
    private final RollbackManager rollbackManager;
    private final CheckpointManager checkpointManager;
    private final RecoveryManager recoveryManager;

    /**
     * @param logDir folder tempat file log dan metadata FRM (misalnya data/frm)
     */
    public FailureRecoveryManagerImpl(Path logDir) {
        this(logDir, DEFAULT_ADMISSION_TIMEOUT_MS);
    }

    /**
     * @param logDir folder tempat file log dan metadata FRM
     * @param admissionTimeoutMs batas waktu menunggu transaksi aktif selesai saat checkpoint/restoreTo
     */
    public FailureRecoveryManagerImpl(Path logDir, long admissionTimeoutMs) {
        TransactionTable txs = new TransactionTable();
        this.logManager = new LogManager(logDir.resolve(LOG_FILE), txs);
        this.ctx = new FrmContext(logManager, new FrmMetadata(logDir.resolve(META_FILE)), txs, admissionTimeoutMs);
        this.rollbackManager = new RollbackManager(ctx);
        this.checkpointManager = new CheckpointManager(ctx);
        this.recoveryManager = new RecoveryManager(ctx, rollbackManager, checkpointManager);
    }

    /**
     * Memasang Storage Manager untuk undo, redo, flush, dan snapshot. Dipanggil app sebelum startup.
     * Bukan bagian antarmuka karena SM juga memegang referensi ke FRM (flushLogUntil).
     */
    public void setStorageManager(StorageManager storageManager) {
        ctx.sm = storageManager;
    }

    // ------------------------------------------------------------------ startup / shutdown

    @Override
    public void startup() {
        LogManager.ScanReport report = logManager.open();
        if (report.tornTailTruncated()) {
            System.err.println("[FRM] Entri terakhir log terpotong akibat crash dan dibuang.");
        }
        if (report.corruption() != null) {
            System.err.println("[FRM] PERINGATAN: " + report.corruption());
        }
        ctx.meta.load();
        ctx.txs.rebuild(logManager.readAll());
        checkpointManager.createInitialSnapshot();
    }

    @Override
    public void shutdown() {
        checkpointManager.stopPeriodicCheckpoint();
        logManager.close();
    }

    // ------------------------------------------------------------------ pencatatan (FR-01, FR-02)

    @Override
    public LSN logBegin(TxId txId) {
        return logManager.logBegin(txId);
    }

    @Override
    public LSN logChange(LogRecord record) {
        return logManager.logChange(record);
    }

    @Override
    public LSN logCommit(TxId txId) {
        return logManager.logCommit(txId);
    }

    @Override
    public void flushLogUntil(LSN lsn) {
        logManager.forceToDisk(lsn);
    }

    // ------------------------------------------------------------------ rollback, recovery, checkpoint

    @Override
    public RollbackResult rollback(TxId txId) {
        return rollbackManager.rollback(txId);
    }

    @Override
    public RecoveryResult recover() {
        return recoveryManager.recover();
    }

    @Override
    public CheckpointResult checkpoint() {
        return checkpointManager.checkpoint();
    }

    @Override
    public RecoveryResult restoreTo(RecoveryTarget target) {
        return checkpointManager.restoreTo(target);
    }

    @Override
    public void startPeriodicCheckpoint(long intervalMs) {
        checkpointManager.startPeriodicCheckpoint(intervalMs);
    }

    @Override
    public void stopPeriodicCheckpoint() {
        checkpointManager.stopPeriodicCheckpoint();
    }

    // ------------------------------------------------------------------ pembacaan log (FR-03)

    @Override
    public List<LogRecord> readLog(TxId txId) {
        if (txId == null) return logManager.readAll();
        return logManager.readAll().stream().filter(r -> txId.equals(r.txId())).toList();
    }

    // ------------------------------------------------------------------ dukungan pengujian

    /** Membuat penyimpanan log berikutnya gagal (LOG_WRITE_FAILED). Hanya untuk driver dan pengujian. */
    public void simulateNextLogWriteFailure() {
        logManager.failNextForce();
    }
}
