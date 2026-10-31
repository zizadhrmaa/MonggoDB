package monggodb.sm.indexManager;

import java.util.Objects;

import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.Value;

/**
 * Nilai INTEGER dan FLOAT dibandingkan sebagai angka, sedangkan CHAR dan VARCHAR sebagai teks.
 */
final class IndexKey implements Comparable<IndexKey> {

    private enum Kind {
        NUMBER,
        TEXT
    }

    private final Kind kind;
    private final double number;
    private final String text;
    private final int hash;

    private IndexKey(double number) {
        this.kind = Kind.NUMBER;
        this.number = number;
        this.text = null;
        this.hash = Double.hashCode(number);
    }

    private IndexKey(String text) {
        this.kind = Kind.TEXT;
        this.number = 0.0;
        this.text = Objects.requireNonNull(text, "text");
        this.hash = text.hashCode();
    }

    /**
     * Membuat key dari Value non-NULL.
     *
     * @param value nilai key
     * @return key ternormalisasi
     * @throws StorageException INVALID_REQUEST jika value null atau NULL SQL
     * @calls tidak ada
     * @calledBy BPlusTree, HashIndex
     */
    static IndexKey of(Value value) {
        if (value == null || value.isNull()) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST, "Key index tidak boleh null atau NULL SQL");
        }
        return switch (value.dataType()) {
            case INTEGER -> new IndexKey(((Integer) value.rawValue()).doubleValue());
            case FLOAT -> new IndexKey(((Float) value.rawValue()).doubleValue());
            case CHAR, VARCHAR -> new IndexKey((String) value.rawValue());
        };
    }

    /**
     * Memvalidasi tipe key terhadap tipe kolom index, lalu membuat key ternormalisasi.
     *
     * @param columnType tipe kolom yang diindeks
     * @param value nilai key
     * @return key ternormalisasi
     * @throws StorageException INVALID_REQUEST jika value null atau NULL SQL, TYPE_MISMATCH jika tipe tidak cocok
     * @calls of(Value)
     * @calledBy BPlusTree, HashIndex
     */
    static IndexKey requireCompatible(DataType columnType, Value value) {
        if (value == null || value.isNull()) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST, "Key index tidak boleh null atau NULL SQL");
        }
        if (!isCompatible(columnType, value.dataType())) {
            throw new StorageException(StorageException.Code.TYPE_MISMATCH, "Key bertipe " + value.dataType() + " tidak cocok dengan index bertipe " + columnType);
        }
        return of(value);
    }

    /**
     * Menentukan apakah tipe key dapat dipakai pada kolom index bertipe tertentu.
     * INTEGER boleh untuk FLOAT, dan CHAR/VARCHAR saling menerima.
     *
     * @param columnType tipe kolom index
     * @param keyType tipe key yang dicari
     * @return true jika kompatibel
     * @calls tidak ada
     * @calledBy requireCompatible
     */
    static boolean isCompatible(DataType columnType, DataType keyType) {
        if (columnType == keyType) {
            return true;
        }
        if (columnType.isText() && keyType.isText()) {
            return true;
        }
        return columnType == DataType.FLOAT && keyType == DataType.INTEGER;
    }

    @Override
    public int compareTo(IndexKey other) {
        if (kind != other.kind) {
            throw new IllegalArgumentException("Key angka dan teks tidak dapat dibandingkan");
        }
        return kind == Kind.NUMBER ? Double.compare(number, other.number) : text.compareTo(other.text);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IndexKey other)) {
            return false;
        }
        if (kind != other.kind) {
            return false;
        }
        return kind == Kind.NUMBER ? Double.compare(number, other.number) == 0 : text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return kind == Kind.NUMBER ? Double.toString(number) : text;
    }
}