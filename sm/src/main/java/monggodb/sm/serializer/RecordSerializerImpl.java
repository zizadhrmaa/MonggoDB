package monggodb.sm.serializer;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.Value;

public class RecordSerializerImpl implements RecordSerializer {

    private static final int NUMERIC_SIZE = 4;
    private static final int VARCHAR_PREFIX_SIZE = 2;
    public static final int MAX_TEXT_LENGTH = 0xFFFF;

    @Override
    public byte[] serializeRecord(Row row, Schema schema) {
        requireNotNull(row, "row");
        requireNotNull(schema, "schema");
        int n = checkArity(row, schema);

        for (int i = 0; i < n; i++) {
            validate(row.getValue(i), columnAt(schema, i));
        }

        ByteBuffer buf = ByteBuffer.allocate(getSerializedSize(row, schema));
        byte[] bitmap = new byte[bitmapSize(n)];
        for (int i = 0; i < n; i++) {
            if (row.getValue(i).isNull()) {
                bitmap[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        buf.put(bitmap);
        for (int i = 0; i < n; i++) {
            Value value = row.getValue(i);
            if (!value.isNull()) {
                writeValue(buf, value, columnAt(schema, i));
            }
        }
        return buf.array();
    }

    @Override
    public Row deserializeRecord(byte[] recordBytes, Schema schema, RID rid) {
        requireNotNull(schema, "schema");
        if (recordBytes == null) {
            throw malformed("recordBytes null");
        }
        int n = schema.size();
        int bitmapSize = bitmapSize(n);
        if (recordBytes.length < bitmapSize) {
            throw malformed("record lebih pendek dari null bitmap (" + recordBytes.length + " < " + bitmapSize + ")");
        }

        List<Value> values = new ArrayList<>(n);
        int offset = bitmapSize;
        for (int i = 0; i < n; i++) {
            Column column = columnAt(schema, i);
            boolean isNull = (recordBytes[i / 8] & (1 << (i % 8))) != 0;
            if (isNull) {
                values.add(Value.ofNull(column.getDataType()));
            } else {
                DecodedValue decoded = decodeValue(recordBytes, column, offset);
                values.add(decoded.value());
                offset += decoded.bytesRead();
            }
        }
        if (offset != recordBytes.length) {
            throw malformed("tersisa " + (recordBytes.length - offset) + " byte setelah kolom terakhir");
        }
        return new Row(rid, values);
    }

    @Override
    public int getSerializedSize(Row row, Schema schema) {
        requireNotNull(row, "row");
        requireNotNull(schema, "schema");
        int n = checkArity(row, schema);
        int size = bitmapSize(n);
        for (int i = 0; i < n; i++) {
            size += getSerializedSize(row.getValue(i), columnAt(schema, i));
        }
        return size;
    }


    @Override
    public byte[] encodeValue(Value value, Column column) {
        validate(value, column);
        if (value.isNull()) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(getSerializedSize(value, column));
        writeValue(buf, value, column);
        return buf.array();
    }

    @Override
    public DecodedValue decodeValue(byte[] bytes, Column column, int offset) {
        requireNotNull(column, "column");
        if (bytes == null) {
            throw malformed("bytes null");
        }
        if (offset < 0 || offset > bytes.length) {
            throw malformed("offset " + offset + " di luar batas (panjang " + bytes.length + ")");
        }

        return switch (column.getDataType()) {
            case INTEGER -> {
                requireAvailable(bytes, offset, NUMERIC_SIZE, column);
                int v = ByteBuffer.wrap(bytes, offset, NUMERIC_SIZE).getInt();
                yield new DecodedValue(Value.ofInteger(v), NUMERIC_SIZE);
            }
            case FLOAT -> {
                requireAvailable(bytes, offset, NUMERIC_SIZE, column);
                float v = ByteBuffer.wrap(bytes, offset, NUMERIC_SIZE).getFloat();
                yield new DecodedValue(Value.ofFloat(v), NUMERIC_SIZE);
            }
            case CHAR -> {
                int length = checkTextLength(column);
                requireAvailable(bytes, offset, length, column);
                int end = offset + length;
                while (end > offset && bytes[end - 1] == 0) {
                    end--;
                }
                String s = decodeUtf8(bytes, offset, end - offset, column);
                yield new DecodedValue(Value.ofChar(s), length);
            }
            case VARCHAR -> {
                int maxLength = checkTextLength(column);
                requireAvailable(bytes, offset, VARCHAR_PREFIX_SIZE, column);
                int length = ByteBuffer.wrap(bytes, offset, VARCHAR_PREFIX_SIZE).getShort() & 0xFFFF;
                if (length > maxLength) {
                    throw malformed("panjang VARCHAR " + length + " melebihi batas kolom " + column.getName() + " (" + maxLength + ")");
                }
                requireAvailable(bytes, offset + VARCHAR_PREFIX_SIZE, length, column);
                String s = decodeUtf8(bytes, offset + VARCHAR_PREFIX_SIZE, length, column);
                yield new DecodedValue(Value.ofVarchar(s), VARCHAR_PREFIX_SIZE + length);
            }
        };
    }

    @Override
    public int getSerializedSize(Value value, Column column) {
        requireNotNull(value, "value");
        requireNotNull(column, "column");
        if (value.isNull()) {
            return 0;
        }
        return switch (column.getDataType()) {
            case INTEGER, FLOAT -> NUMERIC_SIZE;
            case CHAR -> checkTextLength(column);
            case VARCHAR -> {
                if (!value.dataType().isText()) {
                    throw new StorageException(StorageException.Code.TYPE_MISMATCH, "Kolom " + column.getName() + " bertipe VARCHAR, tetapi nilai bertipe " + value.dataType());
                }
                yield VARCHAR_PREFIX_SIZE + utf8(value).length;
            }
        };
    }

    @Override
    public void validate(Value value, Column column) {
        requireNotNull(column, "column");
        if (value == null) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST, "Nilai untuk kolom " + column.getName() + " tidak boleh null Java; gunakan Value.ofNull");
        }
        if (value.isNull()) {
            if (column.isNotNull() || column.isPrimaryKey()) {
                throw new StorageException(StorageException.Code.NULL_NOT_ALLOWED, "Kolom " + column.getName() + " tidak boleh NULL");
            }
            return;
        }

        DataType target = column.getDataType();
        if (!isCompatible(value.dataType(), target)) {
            throw new StorageException(StorageException.Code.TYPE_MISMATCH, "Kolom " + column.getName() + " bertipe " + target + ", tetapi nilai bertipe " + value.dataType());
        }

        if (target.isText()) {
            int maxLength = checkTextLength(column);
            int actual = utf8(value).length;
            if (actual > maxLength) {
                throw new StorageException(StorageException.Code.VALUE_TOO_LONG, "Nilai untuk kolom " + column.getName() + " sepanjang " + actual + " byte melebihi batas " + maxLength + " byte");
            }
        }
    }

    private void writeValue(ByteBuffer buf, Value value, Column column) {
        switch (column.getDataType()) {
            case INTEGER -> buf.putInt(value.asInteger());
            case FLOAT -> buf.putFloat(value.dataType() == DataType.INTEGER ? (float) value.asInteger() : value.asFloat());
            case CHAR -> {
                byte[] text = utf8(value);
                buf.put(text);
                buf.put(new byte[checkTextLength(column) - text.length]);
            }
            case VARCHAR -> {
                byte[] text = utf8(value);
                buf.putShort((short) text.length);
                buf.put(text);
            }
        }
    }

    /** INTEGER boleh masuk kolom FLOAT, dan CHAR/VARCHAR saling diterima. */
    private static boolean isCompatible(DataType source, DataType target) {
        if (source == target) {
            return true;
        }
        if (source.isText() && target.isText()) {
            return true;
        }
        return source == DataType.INTEGER && target == DataType.FLOAT;
    }

    private static int checkTextLength(Column column) {
        int length = column.getLength();
        if (length <= 0 || length > MAX_TEXT_LENGTH) {
            throw new StorageException(StorageException.Code.UNSUPPORTED_DATA_TYPE, "Panjang kolom " + column.getName() + " (" + length + ") harus 1 sampai " + MAX_TEXT_LENGTH);
        }
        return length;
    }

    private static byte[] utf8(Value value) {
        return value.asString().getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length, Column column) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw malformed("byte UTF-8 tidak valid pada kolom " + column.getName());
        }
    }

    private static void requireAvailable(byte[] bytes, int offset, int needed, Column column) {
        if (bytes.length - offset < needed) {
            throw malformed("byte tidak cukup untuk kolom " + column.getName() + " (butuh " + needed + ", tersisa " + (bytes.length - offset) + ")");
        }
    }

    private static int checkArity(Row row, Schema schema) {
        int n = schema.size();
        if (row.size() != n) {
            throw new StorageException(StorageException.Code.SCHEMA_MISMATCH, "Tabel " + schema.getTableName() + " punya " + n + " kolom, tetapi row berisi " + row.size() + " nilai");
        }
        return n;
    }

    private static int bitmapSize(int columnCount) {
        return (columnCount + 7) / 8;
    }

    private static Column columnAt(Schema schema, int index) {
        return schema.getColumns().get(index);
    }

    private static void requireNotNull(Object o, String name) {
        if (o == null) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST, name + " tidak boleh null");
        }
    }

    private static StorageException malformed(String message) {
        return new StorageException(StorageException.Code.MALFORMED_RECORD, "Record rusak: " + message);
    }
}
