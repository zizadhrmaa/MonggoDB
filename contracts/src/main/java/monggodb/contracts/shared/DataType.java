package monggodb.contracts.shared;

/**
 * Tipe data yang didukung mDBMS untuk sebuah kolom atau nilai.
 * Menentukan cara validasi, encoding, dan decoding nilai oleh serializer SM3.
 */
public enum DataType {
    /** Bilangan bulat 32-bit, direpresentasikan sebagai {@link Integer}. */
    INTEGER,
    /** Bilangan pecahan 32-bit, direpresentasikan sebagai {@link Float}. */
    FLOAT,
    /** Teks dengan panjang tetap sesuai {@code Column.length}, direpresentasikan sebagai {@link String}. */
    CHAR,
    /** Teks dengan panjang variabel maksimal {@code Column.length}, direpresentasikan sebagai {@link String}. */
    VARCHAR;

    /**
     * Mengembalikan kelas Java yang dipakai sebagai {@code rawValue} untuk tipe ini.
     * @return {@code Integer.class}, {@code Float.class}, atau {@code String.class}
     */
    public Class<?> javaType() {
        return switch (this) {
            case INTEGER -> Integer.class;
            case FLOAT -> Float.class;
            case CHAR, VARCHAR -> String.class;
        };
    }

    /**
     * Menandai apakah tipe ini bertipe teks (CHAR atau VARCHAR).
     * @return true untuk CHAR dan VARCHAR
     */
    public boolean isText() {
        return this == CHAR || this == VARCHAR;
    }
}
