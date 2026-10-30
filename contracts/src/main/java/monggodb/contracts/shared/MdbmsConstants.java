package monggodb.contracts.shared;

/**
 * Kumpulan konstanta bersama tingkat supergroup yang dipakai lintas komponen.
 * Digunakan oleh Query Optimizer dan Query Processor untuk menjaga konsistensi parameter algoritma.
 */
public final class MdbmsConstants {

    /**
     * Jumlah blok buffer M pada algoritma Block Nested Loop Join (BNLJ).
     * Dipakai QO3 untuk rumus estimasi biaya, QO5 untuk menghitung blockLimit, dan dibaca QP4 saat eksekusi join.
     */
    public static final int BNLJ_BUFFER_BLOCKS = 3;

    /** Konstruktor privat agar kelas konstanta tidak dapat diinstansiasi. */
    private MdbmsConstants() {
    }
}

