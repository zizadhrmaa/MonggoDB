package monggodb.contracts.qo;

import monggodb.contracts.shared.ParsedQuery;
import monggodb.contracts.shared.QOException;

/**
 * Interface utama Query Optimizer yang menyediakan parsing, optimasi, dan penjelasan rencana eksekusi query SQL.
 * Interface ini digunakan oleh Query Processor untuk memperoleh query plan yang telah divalidasi dan dioptimasi.
 */
public interface QueryOptimizer {

    /**
     * Memparse query SQL melalui tokenisasi, parsing, dan validasi semantik untuk menghasilkan query tree awal yang tervalidasi.
     *
     * @param sql Query SQL yang akan diproses.
     * @return ParsedQuery yang berisi query tree awal hasil parsing dan validasi semantik.
     * @throws QOException jika terjadi kesalahan sintaks atau semantik.
     * @calledBy QP1 (executeQuery)
     * @calls SM.getTableSchema
     */
    ParsedQuery parseQuery(String sql) throws QOException;

    /**
     * Mengoptimasi query tree dan memilih rencana eksekusi dengan estimasi biaya terendah. Plan subquery dioptimasi tersendiri.
     * Untuk perintah BEGIN, COMMIT, ABORT, CREATE INDEX, CREATE TABLE, dan DROP TABLE, input dikembalikan apa adanya tanpa optimasi.
     *
     * @param query ParsedQuery yang telah divalidasi.
     * @return ParsedQuery yang berisi rencana eksekusi hasil optimasi.
     * @throws QOException jika terjadi kesalahan dalam proses optimasi.
     * @calledBy QP1 (executeQuery)
     * @calls SM.getStats, SM.getIndexes
     */
    ParsedQuery optimizeQuery(ParsedQuery query) throws QOException;

    /**
     * Menghasilkan representasi teks dari query plan sebelum dan sesudah optimasi, beserta estimasi biayanya.
     *
     * @param initial ParsedQuery sebelum optimasi.
     * @param optimized ParsedQuery setelah optimasi.
     * @return String yang menjelaskan kedua query plan beserta estimasi biayanya.
     * @calledBy QP1 (pemrosesan statement EXPLAIN)
     * @calls Tidak ada
     */
    String explain(ParsedQuery initial, ParsedQuery optimized);
}
