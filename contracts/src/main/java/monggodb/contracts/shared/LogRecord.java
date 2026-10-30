package monggodb.contracts.shared;

/**
 * Satu entri log. Immutable.
 * CCM hanya mengisi txId, type, table, rid, before, dan after; lsn dan prevLSN diisi {@link LSN#NONE}, timestamp 0.
 * FRM mengisi lsn, timestamp, dan prevLSN saat entri ditambahkan ke log.
 *
 * @param lsn nomor urut entri, diisi FRM; {@link LSN#NONE} sebelum ditambahkan ke log
 * @param txId transaksi pemilik entri, diisi CCM; null untuk entri checkpoint
 * @param type jenis entri, diisi CCM
 * @param table nama tabel, diisi CCM; null untuk entri non-data
 * @param rid alamat record yang berubah, diisi CCM; null untuk entri non-data
 * @param before isi record sebelum perubahan, diisi CCM; null berarti "record tidak ada" (beda dari Value NULL di dalam Row)
 * @param after isi record sesudah perubahan, diisi CCM; aturan sama dengan before
 * @param timestamp waktu entri ditambahkan (epoch ms, tidak pernah turun), diisi FRM, untuk FR-05
 * @param prevLSN entri sebelumnya dari transaksi yang sama, diisi FRM; {@link LSN#NONE} jika tidak ada
 */
public record LogRecord(LSN lsn, TxId txId, LogType type, String table, RID rid,
                        Row before, Row after, long timestamp, LSN prevLSN) {

    public LogRecord {
        if (lsn == null) lsn = LSN.NONE;
        if (prevLSN == null) prevLSN = LSN.NONE;
        if (type == null) throw new IllegalArgumentException("type LogRecord tidak boleh null");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(lsn).append(' ').append(type);
        if (txId != null) sb.append(' ').append(txId);
        if (table != null) {
            sb.append(' ').append(table).append(rid);
            sb.append(" before=").append(before == null ? "<tidak ada>" : before);
            sb.append(" after=").append(after == null ? "<tidak ada>" : after);
        }
        return sb.toString();
    }
}
