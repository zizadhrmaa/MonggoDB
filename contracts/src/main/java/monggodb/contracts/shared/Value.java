package monggodb.contracts.shared;

import java.io.Serializable;
import java.util.Objects;

/**
 * Representasi satu nilai sel yang dikirim antarkomponen dan dapat diserialisasi.
 * Immutable. Value NULL tetap membawa {@code dataType} kolomnya, dan berbeda dari
 * Row bernilai null di LogRecord (yang berarti record tidak ada, FR-01).
 *
 * @param dataType tipe data nilai; tidak boleh null, juga untuk Value NULL
 * @param rawValue isi nilai bertipe {@link Integer}, {@link Float}, atau {@link String}
 *                 sesuai {@code dataType}; null jika {@code isNull} bernilai true
 * @param isNull   true jika nilai ini adalah NULL SQL
 */
public record Value(DataType dataType, Object rawValue, boolean isNull) implements Serializable {

    /**
     * Membuat Value dan memastikan {@code rawValue} konsisten dengan {@code dataType} dan {@code isNull}.
     * @throws NullPointerException jika {@code dataType} null
     * @throws IllegalArgumentException jika {@code rawValue} tidak sesuai {@code dataType} atau {@code isNull}
     */
    public Value {
        Objects.requireNonNull(dataType, "dataType tidak boleh null");
        if (isNull) {
            if (rawValue != null) {
                throw new IllegalArgumentException("rawValue harus null untuk Value NULL");
            }
        } else {
            if (rawValue == null) {
                throw new IllegalArgumentException("rawValue tidak boleh null jika isNull = false");
            }
            if (!dataType.javaType().isInstance(rawValue)) {
                throw new IllegalArgumentException("rawValue bertipe " + rawValue.getClass().getSimpleName()
                        + " tidak sesuai dengan " + dataType);
            }
        }
    }

    /**
     * Membuat Value INTEGER.
     * @param v nilai bilangan bulat
     * @return Value bertipe INTEGER
     */
    public static Value ofInteger(int v) {
        return new Value(DataType.INTEGER, v, false);
    }

    /**
     * Membuat Value FLOAT.
     * @param v nilai pecahan
     * @return Value bertipe FLOAT
     */
    public static Value ofFloat(float v) {
        return new Value(DataType.FLOAT, v, false);
    }

    /**
     * Membuat Value CHAR.
     * @param v teks, tidak boleh null
     * @return Value bertipe CHAR
     */
    public static Value ofChar(String v) {
        return new Value(DataType.CHAR, Objects.requireNonNull(v, "v"), false);
    }

    /**
     * Membuat Value VARCHAR.
     * @param v teks, tidak boleh null
     * @return Value bertipe VARCHAR
     */
    public static Value ofVarchar(String v) {
        return new Value(DataType.VARCHAR, Objects.requireNonNull(v, "v"), false);
    }

    /**
     * Membuat Value NULL yang tetap membawa tipe kolomnya.
     * @param dataType tipe kolom asal nilai
     * @return Value NULL bertipe {@code dataType}
     */
    public static Value ofNull(DataType dataType) {
        return new Value(dataType, null, true);
    }

    /**
     * Mengambil isi nilai INTEGER.
     * @return nilai bilangan bulat
     * @throws IllegalStateException jika bukan INTEGER atau bernilai NULL
     */
    public int asInteger() {
        requireType(DataType.INTEGER);
        return (Integer) rawValue;
    }

    /**
     * Mengambil isi nilai FLOAT.
     * @return nilai pecahan
     * @throws IllegalStateException jika bukan FLOAT atau bernilai NULL
     */
    public float asFloat() {
        requireType(DataType.FLOAT);
        return (Float) rawValue;
    }

    /**
     * Mengambil isi nilai teks (CHAR atau VARCHAR).
     * @return teks
     * @throws IllegalStateException jika bukan tipe teks atau bernilai NULL
     */
    public String asString() {
        if (isNull || !dataType.isText()) {
            throw new IllegalStateException("Value bukan teks non-NULL: " + this);
        }
        return (String) rawValue;
    }

    private void requireType(DataType expected) {
        if (isNull || dataType != expected) {
            throw new IllegalStateException("Value bukan " + expected + " non-NULL: " + this);
        }
    }

    @Override
    public String toString() {
        return isNull ? "NULL" : String.valueOf(rawValue);
    }
}
