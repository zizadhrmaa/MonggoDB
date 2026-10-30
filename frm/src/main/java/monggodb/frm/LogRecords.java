package monggodb.frm;

import java.util.List;

import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.TxId;

/**
 * Fungsi bantu untuk tipe bersama FRM. Disimpan di modul frm karena contracts hanya berisi kontrak.
 */
public final class LogRecords {

    private LogRecords() {
    }

    /** @return true untuk INSERT, UPDATE, dan DELETE */
    public static boolean isDataChange(LogType type) {
        return type == LogType.INSERT || type == LogType.UPDATE || type == LogType.DELETE;
    }

    /** @return true jika lsn null atau {@link LSN#NONE} */
    public static boolean isNone(LSN lsn) {
        return lsn == null || LSN.NONE.equals(lsn);
    }

    /** @return catatan perubahan data seperti yang dikirim CCM ke logChange (lsn, timestamp, prevLSN belum diisi) */
    public static LogRecord change(TxId txId, LogType type, String table, RID rid, Row before, Row after) {
        return new LogRecord(LSN.NONE, txId, type, table, rid, before, after, 0L, LSN.NONE);
    }

    /** @return salinan entri dengan field milik FRM (lsn, timestamp, prevLSN) terisi */
    static LogRecord stamped(LogRecord r, LSN lsn, long timestamp, LSN prevLSN) {
        return new LogRecord(lsn, r.txId(), r.type(), r.table(), r.rid(), r.before(), r.after(), timestamp, prevLSN);
    }

    /** @return hasil recover/restoreTo yang gagal */
    static RecoveryResult failed(TxId maxTxId, LSN lastLSN, String message) {
        return new RecoveryResult(false, maxTxId, lastLSN, List.of(), List.of(), message);
    }
}
