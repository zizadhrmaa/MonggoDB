package monggodb.qp.operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import monggodb.contracts.shared.ColumnRef;
import monggodb.contracts.shared.QPException;
import monggodb.contracts.shared.Value;
import monggodb.qp.execution.ExecutionContext;
import monggodb.qp.execution.ExecutionOperator;
import monggodb.qp.execution.ResultSchema;
import monggodb.qp.execution.Tuple;

/**
 * Operator Project: mengambil kolom-kolom tertentu dari Tuple child sesuai urutan SELECT.
 * Posisi setiap kolom ditentukan sekali saat open, lalu dipakai untuk setiap Tuple.
 * RID record asal dari child tetap dibawa.
 */
public class ProjectOperator implements ExecutionOperator {

    /** Operator sumber baris. */
    private final ExecutionOperator child;

    /** Kolom keluaran sesuai urutan SELECT. */
    private final List<ColumnRef> columns;

    /** Skema keluaran, tersedia setelah open pertama. */
    private ResultSchema schema;

    /** Posisi setiap kolom keluaran pada skema child. */
    private int[] sourceIndexes;

    /** True selama operator terbuka. */
    private boolean open;

    /**
     * @param child   operator sumber baris
     * @param columns kolom keluaran sesuai urutan SELECT
     * @throws QPException INVALID_PLAN jika child kosong atau daftar kolom kosong
     */
    public ProjectOperator(ExecutionOperator child, List<ColumnRef> columns) {
        if (child == null) {
            throw new QPException(QPException.Code.INVALID_PLAN, "Node Project tidak memiliki child");
        }
        if (columns == null || columns.isEmpty()) {
            throw new QPException(QPException.Code.INVALID_PLAN, "Node Project tidak memiliki kolom keluaran");
        }
        this.child = child;
        this.columns = List.copyOf(columns);
    }

    /** @return operator sumber baris */
    public ExecutionOperator getChild() {
        return child;
    }

    /** @return kolom keluaran sesuai urutan SELECT */
    public List<ColumnRef> getColumns() {
        return columns;
    }

    /**
     * Membuka child lalu menyusun skema keluaran dari skema child.
     * Jika kolom tidak bisa ditentukan, child langsung ditutup.
     *
     * @param ctx konteks eksekusi
     * @throws QPException COLUMN_NOT_FOUND atau AMBIGUOUS_COLUMN jika ada kolom yang tidak bisa ditentukan
     * @throws IllegalStateException jika operator sudah terbuka
     */
    @Override
    public void open(ExecutionContext ctx) {
        Objects.requireNonNull(ctx, "ctx tidak boleh null");
        if (open) {
            throw new IllegalStateException("ProjectOperator sudah terbuka");
        }
        child.open(ctx);
        try {
            ResultSchema childSchema = child.getSchema();
            int[] indexes = new int[columns.size()];
            for (int i = 0; i < indexes.length; i++) {
                indexes[i] = RowOperators.resolveColumnIndex(childSchema, columns.get(i));
            }
            this.schema = RowOperators.buildProjectedSchema(childSchema, columns);
            this.sourceIndexes = indexes;
        } catch (RuntimeException e) {
            try {
                child.close();
            } catch (RuntimeException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        this.open = true;
    }

    /**
     * @return Tuple berisi kolom keluaran sesuai urutan SELECT, atau null jika child habis
     * @throws IllegalStateException jika operator belum dibuka
     */
    @Override
    public Tuple next() {
        requireOpen();
        Tuple input = child.next();
        if (input == null) {
            return null;
        }
        List<Value> values = new ArrayList<>(sourceIndexes.length);
        for (int index : sourceIndexes) {
            values.add(input.getValue(index));
        }
        return new Tuple(values, schema, input.getRid());
    }

    /**
     * Meneruskan reset ke child tanpa meminta izin baru.
     *
     * @throws IllegalStateException jika operator belum dibuka
     */
    @Override
    public void reset() {
        requireOpen();
        child.reset();
    }

    /** Menutup child. Tidak melakukan apa-apa jika operator tidak sedang terbuka. */
    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        child.close();
    }

    /**
     * @return skema keluaran sesuai urutan SELECT
     * @throws IllegalStateException jika operator belum pernah dibuka
     */
    @Override
    public ResultSchema getSchema() {
        if (schema == null) {
            throw new IllegalStateException("Skema Project tersedia setelah ProjectOperator dibuka");
        }
        return schema;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("ProjectOperator belum dibuka");
        }
    }
}
