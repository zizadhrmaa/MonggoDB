package monggodb.contracts.qo.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import monggodb.contracts.qo.QueryOptimizer;
import monggodb.contracts.shared.ParsedQuery;
import monggodb.contracts.shared.QOException;
import monggodb.contracts.shared.ScanNode;
import monggodb.contracts.shared.StatementType;

/**
 * Mock Query Optimizer untuk pengujian integrasi dengan QP sebelum QO asli tersedia.
 * Tidak melakukan parsing, binding, maupun optimasi beneran, hasilnya berupa data dummy atau hasil yang dikonfigurasi oleh pengujian.
 */
public class MockQueryOptimizer implements QueryOptimizer {

    /** Hasil parsing khusus untuk SQL yang didaftarkan oleh pengujian. */
    private final Map<String, ParsedQuery> parseResults = new HashMap<>();

    /** Hasil parsing default ketika SQL tidak memiliki fixture khusus. */
    private ParsedQuery defaultParseResult = new ParsedQuery(StatementType.SELECT, new ScanNode("mock_table", null), 0.0);

    /** Hasil optimasi dummy yang dapat ditentukan oleh pengujian. */
    private ParsedQuery optimizeResult;

    /** Error sekali pakai untuk panggilan parseQuery berikutnya. */
    private QOException nextParseError;

    /** Error sekali pakai untuk panggilan optimizeQuery berikutnya. */
    private QOException nextOptimizeError;

    /**
     * Mengatur hasil parsing untuk SQL tertentu tanpa perlu memparse SQL tersebut.
     * @param sql teks SQL yang dipakai sebagai kunci fixture
     * @param result hasil dummy untuk SQL tersebut
     */
    public void whenParse(String sql, ParsedQuery result) {
        parseResults.put(Objects.requireNonNull(sql, "sql"), Objects.requireNonNull(result, "result"));
    }

    /**
     * Mengatur hasil parsing default.
     * @param result hasil dummy default
     */
    public void setDefaultParseResult(ParsedQuery result) {
        defaultParseResult = Objects.requireNonNull(result, "result");
    }

    /**
     * Mengatur hasil optimasi dummy untuk statement yang dapat dioptimasi.
     * Jika tidak disetel, optimizeQuery mengembalikan query masukannya.
     * @param result hasil optimasi dummy, atau null untuk kembali ke passthrough
     */
    public void setOptimizeResult(ParsedQuery result) {
        optimizeResult = result;
    }

    /**
     * Menyimulasikan kegagalan satu panggilan parseQuery berikutnya.
     * @param error exception yang akan dilempar
     */
    public void failNextParse(QOException error) {
        nextParseError = Objects.requireNonNull(error, "error");
    }

    /**
     * Menyimulasikan kegagalan satu panggilan optimizeQuery berikutnya.
     * @param error exception yang akan dilempar
     */
    public void failNextOptimize(QOException error) {
        nextOptimizeError = Objects.requireNonNull(error, "error");
    }

    /**
     * Mengembalikan ParsedQuery dummy sesuai fixture, tanpa memparse SQL asli.
     * @param sql query SQL masukan
     * @return ParsedQuery dummy yang telah dikonfigurasi, atau SELECT dummy
     * @throws QOException jika error disimulasikan atau masukan kosong
     * @calls tidak ada (mock tidak memakai SM)
     * @calledBy QP1 (executeQuery)
     */
    @Override
    public ParsedQuery parseQuery(String sql) throws QOException {
        if (nextParseError != null) {
            QOException error = nextParseError;
            nextParseError = null;
            throw error;
        }
        if (sql == null || sql.isBlank()) {
            throw new QOException(QOException.Code.SYNTAX_ERROR, "SQL tidak boleh kosong (mock)", 0, sql);
        }
        return parseResults.getOrDefault(sql, defaultParseResult);
    }

    /**
     * Mengembalikan plan dummy, tanpa menjalankan aturan optimasi atau cost model.
     * BEGIN, COMMIT, ABORT, dan DDL selalu dikembalikan tanpa perubahan.
     * @param query ParsedQuery yang diterima dari pemanggil
     * @return ParsedQuery dummy, atau objek input jika tidak ada fixture
     * @throws QOException jika error disimulasikan atau query null
     * @calls tidak ada (mock tidak memakai SM)
     * @calledBy QP1 (executeQuery)
     */
    @Override
    public ParsedQuery optimizeQuery(ParsedQuery query) throws QOException {
        if (nextOptimizeError != null) {
            QOException error = nextOptimizeError;
            nextOptimizeError = null;
            throw error;
        }
        if (query == null) {
            throw new QOException(QOException.Code.SEMANTIC_ERROR, "ParsedQuery tidak boleh null (mock)", -1, null);
        }
        return switch (query.getStatementType()) {
            case BEGIN, COMMIT, ABORT, CREATE_INDEX, CREATE_TABLE, DROP_TABLE -> query;
            default -> optimizeResult != null && optimizeResult.getStatementType() == query.getStatementType() ? optimizeResult : query;
        };
    }

    /**
     * Menghasilkan teks dummy yang menyebut plan dan cost sebelum/sesudah.
     * @param initial query sebelum optimasi
     * @param optimized query sesudah optimasi
     * @return penjelasan dummy untuk pengujian alur EXPLAIN
     * @calls tidak ada
     * @calledBy QP1 (pemrosesan statement EXPLAIN)
     */
    @Override
    public String explain(ParsedQuery initial, ParsedQuery optimized) {
        return "MOCK EXPLAIN (bukan plan optimasi sebenarnya)\n" + "Before: " + describe(initial) + "\n" + "After: " + describe(optimized);
    }

    /** Menyusun ringkasan query untuk output dummy EXPLAIN. */
    private static String describe(ParsedQuery query) {
        if (query == null) {
            return "null";
        }
        String node = query.getHead() == null ? "none" : query.getHead().getType().name();
        return "statement=" + query.getStatementType() + ", root=" + node + ", cost=" + query.getCost();
    }
}
