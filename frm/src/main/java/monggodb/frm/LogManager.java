package monggodb.frm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import monggodb.contracts.shared.FRMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.TxId;


final class LogManager {

    /** Hasil pemindaian file log saat dibuka. */
    record ScanReport(int records, boolean tornTailTruncated, String corruption) {
    }

    /** Arah pembacaan readLogFrom. */
    enum Direction { FORWARD, BACKWARD }

    private final Path file;
    private final TransactionTable txs;
    private FileChannel channel;

    /** Seluruh entri menurut urutan LSN; indeks >= durableCount masih di buffer. */
    private final List<LogRecord> records = new ArrayList<>();
    private int durableCount;
    private long durableSize;

    private final Map<TxId, LSN> lastLsnOf = new HashMap<>();
    private long nextLsn = 1;
    private long lastTimestamp;
    private String corruption;
    private boolean failNextForce;

    LogManager(Path file, TransactionTable txs) {
        this.file = file;
        this.txs = txs;
    }


    LSN logBegin(TxId txId) {
        if (txId == null) throw new IllegalArgumentException("txId tidak boleh null");
        LogRecord r = appendToBuffer(marker(txId, LogType.BEGIN));
        txs.begin(txId);
        return r.lsn();
    }

    LSN logChange(LogRecord record) {
        validateChange(record);
        TransactionTable.TxState st = txs.getOrCreate(record.txId());
        synchronized (st) {
            if (st.status != TransactionTable.TxStatus.ACTIVE) {
                throw new IllegalStateException("Transaksi " + record.txId() + " sudah " + st.status + ", perubahan ditolak");
            }
            return appendToBuffer(record).lsn();
        }
    }

    LSN logCommit(TxId txId) {
        TransactionTable.TxState st = txs.getOrCreate(txId);
        synchronized (st) {
            if (st.status != TransactionTable.TxStatus.ACTIVE) {
                throw new IllegalStateException("Transaksi " + txId + " sudah " + st.status + ", tidak dapat commit");
            }
            LogRecord commit = appendToBuffer(marker(txId, LogType.COMMIT));
            try {
                forceToDisk(commit.lsn());
            } catch (FRMException e) {
                // COMMIT yang gagal disimpan tidak boleh ikut tertulis oleh flush berikutnya.
                retract(commit.lsn());
                throw e;
            }
            st.status = TransactionTable.TxStatus.COMMITTED;
            return commit.lsn();
        }
    }

    /** Entri tanpa data (BEGIN, COMMIT, ABORT, CHECKPOINT_START/END) yang belum diberi LSN. */
    static LogRecord marker(TxId txId, LogType type) {
        return new LogRecord(LSN.NONE, txId, type, null, null, null, null, 0L, LSN.NONE);
    }

    private static void validateChange(LogRecord r) {
        if (r == null) throw new IllegalArgumentException("LogRecord tidak boleh null");
        if (r.txId() == null) throw new IllegalArgumentException("txId LogRecord tidak boleh null");
        if (!LogRecords.isDataChange(r.type())) {
            throw new IllegalArgumentException("logChange hanya menerima INSERT, UPDATE, atau DELETE, bukan " + r.type());
        }
        if (r.table() == null || r.rid() == null) throw new IllegalArgumentException("table dan rid wajib diisi");
        boolean ok = switch (r.type()) {
            case INSERT -> r.before() == null && r.after() != null;
            case UPDATE -> r.before() != null && r.after() != null;
            case DELETE -> r.before() != null && r.after() == null;
            default -> false;
        };
        if (!ok) {
            throw new IllegalArgumentException("before/after tidak sesuai untuk " + r.type()
                    + " (INSERT: before kosong, DELETE: after kosong, UPDATE: keduanya terisi)");
        }
    }



    synchronized ScanReport open() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            byte[] data = Files.readAllBytes(file);
            records.clear();
            lastLsnOf.clear();
            corruption = null;
            int pos = 0;
            boolean torn = false;
            while (pos < data.length) {
                int remaining = data.length - pos;
                if (remaining < LogSerializer.HEADER_SIZE) {
                    torn = true;
                    break;
                }
                ByteBuffer header = ByteBuffer.wrap(data, pos, LogSerializer.HEADER_SIZE);
                int len = header.getInt();
                int crc = header.getInt();
                if (len <= 0 || len > LogSerializer.MAX_PAYLOAD) {
                    if (allZero(data, pos)) {
                        torn = true;
                    } else {
                        corruption = "Panjang entri log tidak valid pada offset " + pos;
                    }
                    break;
                }
                int end = pos + LogSerializer.HEADER_SIZE + len;
                if (end > data.length) {
                    torn = true;
                    break;
                }
                byte[] payload = new byte[len];
                System.arraycopy(data, pos + LogSerializer.HEADER_SIZE, payload, 0, len);
                if (LogSerializer.checksum(payload) != crc) {
                    if (end == data.length) {
                        torn = true;
                    } else {
                        corruption = "Checksum entri log tidak cocok pada offset " + pos + " (bukan entri terakhir)";
                    }
                    break;
                }
                LogRecord rec;
                try {
                    rec = LogSerializer.deserialize(payload);
                } catch (IOException e) {
                    corruption = "Entri log pada offset " + pos + " tidak dapat dibaca: " + e.getMessage();
                    break;
                }
                if (rec.lsn().value() < nextLsn) {
                    corruption = "LSN tidak naik pada offset " + pos + " (" + rec.lsn() + ")";
                    break;
                }
                records.add(rec);
                nextLsn = rec.lsn().value() + 1;
                lastTimestamp = Math.max(lastTimestamp, rec.timestamp());
                if (rec.txId() != null) lastLsnOf.put(rec.txId(), rec.lsn());
                pos = end;
            }
            durableCount = records.size();
            durableSize = pos;
            if (torn && corruption == null) {
                channel.truncate(pos);
                channel.force(true);
            }
            return new ScanReport(records.size(), torn && corruption == null, corruption);
        } catch (IOException e) {
            throw new FRMException(FRMException.Code.LOG_WRITE_FAILED, "Gagal membuka file log " + file + ": " + e.getMessage(), e);
        }
    }

    /** @return pesan kerusakan log, atau null jika log sehat */
    synchronized String corruption() {
        return corruption;
    }

  
    synchronized LogRecord appendToBuffer(LogRecord record) {
        ensureWritable();
        LSN lsn = nextLSN();
        long ts = Math.max(System.currentTimeMillis(), lastTimestamp);
        lastTimestamp = ts;
        LSN prev = record.txId() == null ? LSN.NONE : lastLsnOf.getOrDefault(record.txId(), LSN.NONE);
        LogRecord stamped = LogRecords.stamped(record, lsn, ts, prev);
        records.add(stamped);
        if (record.txId() != null) lastLsnOf.put(record.txId(), lsn);
        return stamped;
    }


    synchronized void forceToDisk(LSN upTo) {
        if (upTo == null || LogRecords.isNone(upTo) || upTo.compareTo(flushedLsn()) <= 0) return;
        writeBuffer();
    }

    /** Menulis seluruh isi buffer log ke file dan fsync. */
    private void writeBuffer() {
        if (durableCount == records.size()) return;
        ensureWritable();
        try {
            if (failNextForce) {
                failNextForce = false;
                throw new IOException("kegagalan penulisan log disimulasikan");
            }
            List<byte[]> frames = new ArrayList<>();
            int total = 0;
            for (int i = durableCount; i < records.size(); i++) {
                byte[] f = LogSerializer.serialize(records.get(i));
                frames.add(f);
                total += f.length;
            }
            ByteBuffer buf = ByteBuffer.allocate(total);
            frames.forEach(buf::put);
            buf.flip();
            long pos = durableSize;
            while (buf.hasRemaining()) {
                pos += channel.write(buf, pos);
            }
            channel.force(true);
            durableSize = pos;
            durableCount = records.size();
        } catch (IOException e) {
            try {
                channel.truncate(durableSize);
            } catch (IOException ignored) {
                // file tetap dianggap sepanjang durableSize; sisa byte dikenali sebagai entri terpotong saat dibuka
            }
            throw new FRMException(FRMException.Code.LOG_WRITE_FAILED, "Gagal menyimpan log ke disk: " + e.getMessage(), e);
        }
    }


    synchronized boolean retract(LSN lsn) {
        for (int i = records.size() - 1; i >= durableCount; i--) {
            LogRecord r = records.get(i);
            if (r.lsn().equals(lsn)) {
                records.remove(i);
                if (r.txId() != null && lsn.equals(lastLsnOf.get(r.txId()))) {
                    if (LogRecords.isNone(r.prevLSN())) lastLsnOf.remove(r.txId());
                    else lastLsnOf.put(r.txId(), r.prevLSN());
                }
                return true;
            }
        }
        return false;
    }


    synchronized Iterator<LogRecord> readLogFrom(LSN from, Direction dir) {
        List<LogRecord> out = new ArrayList<>();
        if (dir == Direction.FORWARD) {
            for (LogRecord r : records) {
                if (r.lsn().compareTo(from) >= 0) out.add(r);
            }
        } else {
            for (int i = records.size() - 1; i >= 0; i--) {
                if (records.get(i).lsn().compareTo(from) <= 0) out.add(records.get(i));
            }
        }
        return out.iterator();
    }

    /** @return seluruh entri menurut urutan LSN */
    List<LogRecord> readAll() {
        List<LogRecord> out = new ArrayList<>();
        readLogFrom(LSN.NONE, Direction.FORWARD).forEachRemaining(out::add);
        return out;
    }

    /** @return LSN terbesar yang sudah persisten, atau LSN.NONE */
    synchronized LSN flushedLsn() {
        return durableCount == 0 ? LSN.NONE : records.get(durableCount - 1).lsn();
    }

    /** @return LSN terbesar di log (termasuk buffer), atau LSN.NONE */
    synchronized LSN lastLsn() {
        return records.isEmpty() ? LSN.NONE : records.get(records.size() - 1).lsn();
    }

    /** Membuat penulisan berikutnya gagal, untuk menguji penanganan LOG_WRITE_FAILED. */
    synchronized void failNextForce() {
        failNextForce = true;
    }

    /** Menulis sisa buffer lalu menutup file. */
    synchronized void close() {
        if (channel == null) return;
        try {
            if (corruption == null) writeBuffer();
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                throw new FRMException(FRMException.Code.LOG_WRITE_FAILED, "Gagal menutup file log: " + e.getMessage(), e);
            } finally {
                channel = null;
            }
        }
    }

    private LSN nextLSN() {
        return new LSN(nextLsn++);
    }

    private void ensureWritable() {
        if (channel == null) {
            throw new FRMException(FRMException.Code.LOG_WRITE_FAILED, "File log belum dibuka (FRM.startup belum dipanggil)");
        }
        if (corruption != null) {
            throw new FRMException(FRMException.Code.LOG_CORRUPTED, "Log rusak, penulisan ditolak: " + corruption);
        }
    }

    private static boolean allZero(byte[] data, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] != 0) return false;
        }
        return true;
    }
}
