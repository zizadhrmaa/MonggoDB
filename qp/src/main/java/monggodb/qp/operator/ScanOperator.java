package monggodb.qp.operator;

import java.util.Locale;
import java.util.Objects;

import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.QPException;
import monggodb.contracts.shared.RecordIterator;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.sm.StorageManager;
import monggodb.qp.execution.AccessControl;
import monggodb.qp.execution.ExecutionContext;
import monggodb.qp.execution.ExecutionOperator;
import monggodb.qp.execution.ResultSchema;
import monggodb.qp.execution.Tuple;

/**
 * Operator Table Scan: membaca semua record satu tabel secara berurutan lewat scanTable SM.
 * Satu izin baca tabel diminta saat open dan ditutup saat close. reset mengulang scan tanpa izin baru.
 * Setiap Tuple membawa RID record asal.
 */
public class ScanOperator implements ExecutionOperator {

    /** Nama tabel dalam huruf kecil. */
    private final String tableName;

    /** Alias tabel dalam huruf kecil, atau null. */
    private final String alias;

    /** Skema keluaran, tersedia setelah open pertama. */
    private ResultSchema schema;

    /** Konteks eksekusi selama operator terbuka. */
    private ExecutionContext ctx;

    /** Iterator record dari SM selama operator terbuka. */
    private RecordIterator iterator;

    /** requestId izin baca yang sedang dipegang. */
    private String requestId;

    /** True jika pembacaan dari SM gagal sejak open, sehingga izin ditutup dengan success = false. */
    private boolean failed;

    /** True selama operator terbuka. */
    private boolean open;

    /**
     * @param tableName nama tabel
     * @param alias     alias tabel, atau null
     * @throws QPException INVALID_PLAN jika nama tabel kosong
     */
    public ScanOperator(String tableName, String alias) {
        if (tableName == null || tableName.isBlank()) {
            throw new QPException(QPException.Code.INVALID_PLAN, "Node Scan tidak memiliki nama tabel");
        }
        this.tableName = tableName.toLowerCase(Locale.ROOT);
        this.alias = alias == null || alias.isBlank() ? null : alias.toLowerCase(Locale.ROOT);
    }

    /** @return nama tabel dalam huruf kecil */
    public String getTableName() {
        return tableName;
    }

    /** @return alias tabel dalam huruf kecil, atau null */
    public String getAlias() {
        return alias;
    }

    /**
     * Meminta izin baca tabel, mengambil skema tabel, lalu membuka scan SM.
     * Jika pengambilan skema atau pembukaan scan gagal, izin langsung ditutup dengan success = false.
     *
     * @param ctx konteks eksekusi
     * @throws monggodb.qp.execution.AccessDeniedException jika CCM menjawab ABORTED atau izin WAITING sampai batas waktu
     * @throws monggodb.contracts.shared.StorageException jika tabel tidak ada di SM
     * @throws IllegalStateException jika operator sudah terbuka
     */
    @Override
    public void open(ExecutionContext ctx) {
        Objects.requireNonNull(ctx, "ctx tidak boleh null");
        if (open) {
            throw new IllegalStateException("ScanOperator tabel " + tableName + " sudah terbuka");
        }
        AccessResponse access = AccessControl.acquireReadAccess(ctx, new ResourceId(tableName));
        ResultSchema tableSchema;
        RecordIterator records;
        try {
            StorageManager storage = ctx.getStorage();
            tableSchema = RowOperators.buildTableSchema(storage.getTableSchema(tableName), alias);
            records = storage.scanTable(tableName);
        } catch (RuntimeException e) {
            try {
                AccessControl.finishAccess(ctx, access.requestId(), false);
            } catch (RuntimeException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        this.ctx = ctx;
        this.requestId = access.requestId();
        this.schema = tableSchema;
        this.iterator = records;
        this.failed = false;
        this.open = true;
    }

    /**
     * @return Tuple berikutnya berkolom tabel.kolom (atau alias.kolom), atau null jika record habis
     * @throws IllegalStateException jika operator belum dibuka
     */
    @Override
    public Tuple next() {
        requireOpen();
        try {
            if (!iterator.hasNext()) {
                return null;
            }
            return RowOperators.toTuple(iterator.next(), schema, tableName);
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /**
     * Mengulang scan dari record pertama tanpa meminta izin baru.
     *
     * @throws IllegalStateException jika operator belum dibuka
     */
    @Override
    public void reset() {
        requireOpen();
        try {
            iterator.reset();
        } catch (RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /**
     * Menutup iterator SM dan izin baca. Izin ditutup dengan success = false jika pembacaan pernah gagal.
     * Tidak melakukan apa-apa jika operator tidak sedang terbuka.
     */
    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        ExecutionContext closingCtx = ctx;
        String closingRequestId = requestId;
        RecordIterator closingIterator = iterator;
        ctx = null;
        requestId = null;
        iterator = null;
        try {
            closingIterator.close();
        } finally {
            AccessControl.finishAccess(closingCtx, closingRequestId, !failed);
        }
    }

    /**
     * @return skema keluaran sesuai urutan kolom tabel
     * @throws IllegalStateException jika operator belum pernah dibuka
     */
    @Override
    public ResultSchema getSchema() {
        if (schema == null) {
            throw new IllegalStateException("Skema tabel " + tableName + " tersedia setelah ScanOperator dibuka");
        }
        return schema;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("ScanOperator tabel " + tableName + " belum dibuka");
        }
    }
}
