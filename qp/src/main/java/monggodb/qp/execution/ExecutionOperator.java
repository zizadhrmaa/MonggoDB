package monggodb.qp.execution;

/**
 * Operator pada pohon eksekusi dengan model iterator (Volcano): open, next berulang sampai null, lalu close.
 * close wajib dipanggil di blok finally dan aman dipanggil lebih dari sekali.
 */
public interface ExecutionOperator {

    /**
     * Menyiapkan operator dan child-nya, termasuk meminta izin baca tabel yang dibaca.
     * Jika open melempar exception, semua sumber daya yang sempat diambil sudah dilepas.
     *
     * @param ctx konteks eksekusi statement
     */
    void open(ExecutionContext ctx);

    /** @return Tuple berikutnya, atau null jika hasil sudah habis */
    Tuple next();

    /** Mengulang hasil dari awal tanpa open ulang dan tanpa meminta izin baru. Boleh dipanggil berulang. */
    void reset();

    /** Melepas semua resource dan izin yang dipegang operator dan child-nya. */
    void close();

    /**
     * @return skema keluaran operator, tersedia setelah open walaupun hasilnya 0 baris dan tetap tersedia setelah close
     * @throws IllegalStateException jika skema belum bisa ditentukan karena operator belum pernah dibuka
     */
    ResultSchema getSchema();
}
