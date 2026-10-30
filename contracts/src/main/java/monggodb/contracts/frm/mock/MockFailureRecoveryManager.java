package monggodb.contracts.frm.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.shared.CheckpointResult;
import monggodb.contracts.shared.FRMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.RecoveryTarget;
import monggodb.contracts.shared.RollbackResult;
import monggodb.contracts.shared.TxId;

/**
 * Mock FRM untuk driver komponen lain. Log hanya disimpan di memori, tidak ada yang ditulis ke disk,
 * dan rollback/recover tidak menyentuh Storage Manager. LSN tetap unik dan naik sehingga SM dan CCM
 * dapat menguji alur WAL-nya. {@link #failNextWrite()} mensimulasikan kegagalan pencatatan log.
 */
public class MockFailureRecoveryManager implements FailureRecoveryManager {

    private enum Status { ACTIVE, COMMITTED, ABORTED }

    private final List<LogRecord> log = new ArrayList<>();
    private final Map<TxId, Status> status = new HashMap<>();
    private final Map<TxId, LSN> lastLsnOf = new HashMap<>();
    private long nextLsn = 1;
    private LSN flushedLsn = LSN.NONE;
    private boolean failNextWrite;

    /** Membuat pencatatan berikutnya (logBegin/logChange/logCommit/flushLogUntil) melempar LOG_WRITE_FAILED. */
    public synchronized void failNextWrite() {
        failNextWrite = true;
    }

    /** @return LSN terbesar yang dianggap sudah persisten */
    public synchronized LSN getFlushedLsn() {
        return flushedLsn;
    }

    @Override
    public void startup() {
        // tidak ada file yang dibuka
    }

    @Override
    public void shutdown() {
        // tidak ada file yang ditutup
    }

    @Override
    public synchronized LSN logBegin(TxId txId) {
        status.put(txId, Status.ACTIVE);
        return append(new LogRecord(LSN.NONE, txId, LogType.BEGIN, null, null, null, null, 0L, LSN.NONE));
    }

    @Override
    public synchronized LSN logChange(LogRecord record) {
        if (record == null || !(record.type() == LogType.INSERT || record.type() == LogType.UPDATE || record.type() == LogType.DELETE)) {
            throw new IllegalArgumentException("logChange hanya menerima INSERT, UPDATE, atau DELETE");
        }
        status.putIfAbsent(record.txId(), Status.ACTIVE);
        return append(record);
    }

    @Override
    public synchronized LSN logCommit(TxId txId) {
        LSN lsn = append(new LogRecord(LSN.NONE, txId, LogType.COMMIT, null, null, null, null, 0L, LSN.NONE));
        flushedLsn = lsn;
        status.put(txId, Status.COMMITTED);
        return lsn;
    }

    @Override
    public synchronized void flushLogUntil(LSN lsn) {
        if (lsn == null || LSN.NONE.equals(lsn)) return;
        checkFailure();
        if (lsn.compareTo(flushedLsn) > 0) flushedLsn = new LSN(Math.min(lsn.value(), nextLsn - 1));
    }

    @Override
    public synchronized RollbackResult rollback(TxId txId) {
        Status s = status.get(txId);
        if (s == null) return new RollbackResult(RollbackResult.Status.TX_UNKNOWN, "Transaksi " + txId + " tidak dikenal");
        if (s == Status.COMMITTED) return new RollbackResult(RollbackResult.Status.TX_COMMITTED, "Transaksi " + txId + " sudah commit");
        if (s == Status.ABORTED) return new RollbackResult(RollbackResult.Status.ALREADY_ABORTED, "Transaksi " + txId + " sudah dibatalkan");
        append(new LogRecord(LSN.NONE, txId, LogType.ABORT, null, null, null, null, 0L, LSN.NONE));
        status.put(txId, Status.ABORTED);
        return new RollbackResult(RollbackResult.Status.DONE, "Transaksi " + txId + " dibatalkan (mock, data tidak diubah)");
    }

    @Override
    public synchronized RecoveryResult recover() {
        TxId max = status.keySet().stream().max(TxId::compareTo).orElse(null);
        return new RecoveryResult(true, max, new LSN(nextLsn - 1), List.of(), List.of(), "Recovery mock: tidak ada yang dipulihkan");
    }

    @Override
    public synchronized CheckpointResult checkpoint() {
        append(new LogRecord(LSN.NONE, null, LogType.CHECKPOINT_START, null, null, null, null, 0L, LSN.NONE));
        LSN end = append(new LogRecord(LSN.NONE, null, LogType.CHECKPOINT_END, null, null, null, null, 0L, LSN.NONE));
        flushedLsn = end;
        return new CheckpointResult(true, end, "Checkpoint mock selesai");
    }

    @Override
    public synchronized RecoveryResult restoreTo(RecoveryTarget target) {
        return new RecoveryResult(true, null, new LSN(nextLsn - 1), List.of(), List.of(), "restoreTo mock: data tidak diubah");
    }

    @Override
    public synchronized List<LogRecord> readLog(TxId txId) {
        if (txId == null) return List.copyOf(log);
        return log.stream().filter(r -> txId.equals(r.txId())).toList();
    }

    @Override
    public void startPeriodicCheckpoint(long intervalMs) {
        // mock tidak menjalankan thread latar
    }

    @Override
    public void stopPeriodicCheckpoint() {
        // mock tidak menjalankan thread latar
    }

    private LSN append(LogRecord record) {
        checkFailure();
        LSN lsn = new LSN(nextLsn++);
        LSN prev = record.txId() == null ? LSN.NONE : lastLsnOf.getOrDefault(record.txId(), LSN.NONE);
        log.add(new LogRecord(lsn, record.txId(), record.type(), record.table(), record.rid(),
                record.before(), record.after(), System.currentTimeMillis(), prev));
        if (record.txId() != null) lastLsnOf.put(record.txId(), lsn);
        return lsn;
    }

    private void checkFailure() {
        if (failNextWrite) {
            failNextWrite = false;
            throw new FRMException(FRMException.Code.LOG_WRITE_FAILED, "Gagal menulis log (simulasi mock)");
        }
    }
}
