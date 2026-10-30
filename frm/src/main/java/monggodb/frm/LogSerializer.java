package monggodb.frm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.shared.Value;

/**
 * Mengubah LogRecord ke byte dan sebaliknya.
 * Satu entri di file log disimpan sebagai frame: [panjang payload: int][CRC32 payload: int][payload],
 * sehingga entri terpotong atau rusak dapat dikenali saat dibaca ulang.
 * Before/after kosong ("record tidak ada") ditulis dengan penanda 0, berbeda dari Row berisi Value NULL.
 */
public final class LogSerializer {

    /** Ukuran header frame: panjang payload dan CRC32. */
    public static final int HEADER_SIZE = 8;
    /** Batas atas panjang payload yang dianggap wajar; lebih dari ini dianggap rusak. */
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private static final byte FORMAT_VERSION = 1;

    private LogSerializer() {
    }

    /**
     * @param record entri yang sudah memiliki LSN
     * @return frame lengkap (header + payload)
     */
    public static byte[] serialize(LogRecord record) {
        byte[] payload = toPayload(record);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buf.putInt(payload.length);
        buf.putInt(checksum(payload));
        buf.put(payload);
        return buf.array();
    }

    /**
     * @param payload isi frame tanpa header
     * @return entri log hasil dekode
     * @throws IOException jika payload tidak valid
     */
    public static LogRecord deserialize(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        byte version = in.readByte();
        if (version != FORMAT_VERSION) throw new IOException("versi format log tidak dikenal: " + version);
        LSN lsn = new LSN(in.readLong());
        long tx = in.readLong();
        int typeOrd = in.readUnsignedByte();
        LogType[] types = LogType.values();
        if (typeOrd >= types.length) throw new IOException("jenis entri log tidak dikenal: " + typeOrd);
        long timestamp = in.readLong();
        LSN prev = new LSN(in.readLong());
        String table = in.readBoolean() ? in.readUTF() : null;
        RID rid = readRid(in);
        Row before = readRow(in);
        Row after = readRow(in);
        if (in.available() != 0) throw new IOException("sisa byte tidak terduga di entri log");
        return new LogRecord(lsn, tx < 0 ? null : new TxId(tx), types[typeOrd], table, rid, before, after, timestamp, prev);
    }

    /**
     * @param payload isi frame
     * @return CRC32 payload
     */
    public static int checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }

    /**
     * Menulis Row (boleh null) ke stream. Dipakai juga oleh SM tiruan berbasis berkas di driver.
     * @param out stream tujuan
     * @param row isi record, null berarti "record tidak ada"
     * @throws IOException jika penulisan gagal
     */
    public static void writeRow(DataOutputStream out, Row row) throws IOException {
        if (row == null) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        writeRid(out, row.getRid());
        out.writeInt(row.getValues().size());
        for (Value v : row.getValues()) {
            out.writeByte(v.dataType().ordinal());
            out.writeBoolean(v.isNull());
            if (v.isNull()) continue;
            switch (v.dataType()) {
                case INTEGER -> out.writeInt((Integer) v.rawValue());
                case FLOAT -> out.writeFloat((Float) v.rawValue());
                case CHAR, VARCHAR -> out.writeUTF((String) v.rawValue());
            }
        }
    }

    /**
     * Membaca Row yang ditulis {@link #writeRow}.
     * @param in stream sumber
     * @return Row, atau null untuk "record tidak ada"
     * @throws IOException jika data tidak valid
     */
    public static Row readRow(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        RID rid = readRid(in);
        int n = in.readInt();
        if (n < 0 || n > 4096) throw new IOException("jumlah atribut tidak wajar: " + n);
        DataType[] types = DataType.values();
        List<Value> values = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int ord = in.readUnsignedByte();
            if (ord >= types.length) throw new IOException("tipe data tidak dikenal: " + ord);
            DataType type = types[ord];
            if (in.readBoolean()) {
                values.add(Value.ofNull(type));
                continue;
            }
            values.add(switch (type) {
                case INTEGER -> Value.ofInteger(in.readInt());
                case FLOAT -> Value.ofFloat(in.readFloat());
                case CHAR -> Value.ofChar(in.readUTF());
                case VARCHAR -> Value.ofVarchar(in.readUTF());
            });
        }
        return new Row(rid, values);
    }

    private static byte[] toPayload(LogRecord r) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            out.writeLong(r.lsn().value());
            out.writeLong(r.txId() == null ? -1 : r.txId().value());
            out.writeByte(r.type().ordinal());
            out.writeLong(r.timestamp());
            out.writeLong(r.prevLSN().value());
            out.writeBoolean(r.table() != null);
            if (r.table() != null) out.writeUTF(r.table());
            writeRid(out, r.rid());
            writeRow(out, r.before());
            writeRow(out, r.after());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeRid(DataOutputStream out, RID rid) throws IOException {
        out.writeBoolean(rid != null);
        if (rid != null) {
            out.writeInt(rid.getPageId());
            out.writeInt(rid.getSlotId());
        }
    }

    private static RID readRid(DataInputStream in) throws IOException {
        return in.readBoolean() ? new RID(in.readInt(), in.readInt()) : null;
    }
}
