package monggodb.contracts.qo.mock;

import java.util.ArrayList;
import java.util.List;

import monggodb.contracts.shared.BinaryExpr;
import monggodb.contracts.shared.BinaryExpr.BinaryOp;
import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.ColumnRef;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.ExpressionNode;
import monggodb.contracts.shared.InsertNode;
import monggodb.contracts.shared.LiteralExpr;
import monggodb.contracts.shared.ParsedQuery;
import monggodb.contracts.shared.PlanNode;
import monggodb.contracts.shared.ProjectNode;
import monggodb.contracts.shared.QOException;
import monggodb.contracts.shared.QOException.Code;
import monggodb.contracts.shared.ScanNode;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.UpdateNode;
import monggodb.contracts.shared.Value;
import monggodb.contracts.sm.StorageManager;

/**
 * Mock ringkas untuk komponen Binder (QO3 - QO-07) pada Milestone 1.
 * Menggunakan skema tiruan bawaan tabel {@code students} (atau {@link StorageManager} jika diberikan)
 * untuk memvalidasi keberadaan tabel/kolom, menjabarkan {@code SELECT *}, dan memeriksa tipe dasar.
 */
public class MockQueryBinder {

    private static final String DEFAULT_TABLE = "students";
    private static final Schema DEFAULT_SCHEMA = new Schema(DEFAULT_TABLE, List.of(
            new Column("nim", DEFAULT_TABLE, DataType.INTEGER, 0, true, true, null),
            new Column("name", DEFAULT_TABLE, DataType.VARCHAR, 50, false, false, null),
            new Column("gpa", DEFAULT_TABLE, DataType.FLOAT, 0, false, false, null)));

    private final StorageManager storageManager;

    /** Membuat MockQueryBinder dengan skema dummy tabel {@code students}. */
    public MockQueryBinder() {
        this(null);
    }

    /**
     * Membuat MockQueryBinder yang mengambil skema dari StorageManager tertentu.
     *
     * @param storageManager instance StorageManager, atau null untuk memakai skema dummy bawaan
     */
    public MockQueryBinder(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /**
     * Memvalidasi query terhadap skema mock dan menjabarkan {@code SELECT *} pada node proyeksi terluar.
     *
     * @param query query yang akan divalidasi
     * @return ParsedQuery yang telah divalidasi secara mock
     * @throws QOException jika tabel atau kolom tidak valid
     * @calledBy QO1 (parseQuery)
     * @calls SM.getTableSchema
     */
    public ParsedQuery bindQuery(ParsedQuery query) throws QOException {
        if (query == null) {
            throw new QOException(Code.SEMANTIC_ERROR, "ParsedQuery tidak boleh null", 0, "");
        }
        PlanNode head = query.getHead();
        if (head instanceof InsertNode insertNode) {
            validateInsert(insertNode, getSchema(insertNode.getTable()));
        } else if (head instanceof UpdateNode updateNode) {
            validateUpdate(updateNode, getSchema(updateNode.getTable()));
        } else if (head instanceof ProjectNode projectNode) {
            String table = extractTableName(projectNode.getChild());
            Schema schema = getSchema(table);
            List<ColumnRef> boundCols = new ArrayList<>();
            for (ColumnRef colRef : projectNode.getColumns()) {
                if ("*".equals(colRef.getColumn())) {
                    for (Column col : schema.getColumns()) {
                        ColumnRef expanded = new ColumnRef(schema.getTableName(), col.getName());
                        expanded.bind(schema.getTableName(), false, col.getDataType());
                        boundCols.add(expanded);
                    }
                } else {
                    boundCols.add(resolveColumn(colRef, List.of(schema.getTableName())));
                }
            }
            projectNode.setColumns(boundCols);
        }
        return query;
    }

    /**
     * Mengikat referensi kolom ke tabel pada daftar {@code fromTables}.
     *
     * @param columnRef  referensi kolom yang belum terikat
     * @param fromTables daftar nama tabel pada klausa FROM
     * @return ColumnRef yang telah terikat
     * @throws QOException jika kolom tidak ditemukan atau ambigu
     * @calledBy bindQuery
     * @calls tidak ada
     */
    public ColumnRef resolveColumn(ColumnRef columnRef, List<String> fromTables) throws QOException {
        if (columnRef == null) {
            throw new QOException(Code.SEMANTIC_ERROR, "ColumnRef tidak boleh null", 0, "");
        }
        List<String> tables = (fromTables == null || fromTables.isEmpty()) ? List.of(DEFAULT_TABLE) : fromTables;
        Column matched = null;
        String matchedTable = null;

        for (String table : tables) {
            if (columnRef.getTable() != null
                    && !columnRef.getTable().isEmpty()
                    && !columnRef.getTable().equalsIgnoreCase(table)) {
                continue;
            }
            Schema schema = getSchema(table);
            Column col = schema.getColumn(columnRef.getColumn());
            if (col != null) {
                if (matched != null) {
                    throw new QOException(Code.SEMANTIC_ERROR, "Kolom '" + columnRef.getColumn() + "' ambigu", 0, columnRef.getColumn());
                }
                matched = col;
                matchedTable = schema.getTableName();
            }
        }

        if (matched == null) {
            throw new QOException(Code.SEMANTIC_ERROR, "Kolom '" + columnRef.getColumn() + "' tidak ditemukan", 0, columnRef.getColumn());
        }
        columnRef.bind(matchedTable, false, matched.getDataType());
        return columnRef;
    }

    /**
     * Menentukan tipe data hasil suatu ekspresi secara mock.
     *
     * @param expression ekspresi yang diperiksa
     * @param fromTables daftar tabel pada klausa FROM
     * @return tipe data hasil ekspresi, atau null untuk ekspresi kondisi
     * @throws QOException jika tipe operand tidak kompatibel
     * @calledBy bindQuery
     * @calls tidak ada
     */
    public DataType inferExpressionType(ExpressionNode expression, List<String> fromTables) throws QOException {
        if (expression == null) {
            return null;
        }
        if (expression instanceof ColumnRef colRef) {
            return resolveColumn(colRef, fromTables).getDataType();
        }
        if (expression instanceof LiteralExpr lit) {
            DataType dt = lit.getValue() != null ? lit.getValue().dataType() : null;
            lit.setResultType(dt);
            return dt;
        }
        if (expression instanceof BinaryExpr bin) {
            DataType left = inferExpressionType(bin.getLeft(), fromTables);
            DataType right = inferExpressionType(bin.getRight(), fromTables);
            BinaryOp op = bin.getOp();
            if (op == BinaryOp.ADD || op == BinaryOp.SUB || op == BinaryOp.MUL || op == BinaryOp.DIV) {
                if (left == null || right == null || left.isText() || right.isText()) {
                    throw new QOException(Code.SEMANTIC_ERROR, "Operasi aritmatika hanya untuk tipe angka", 0, op.name());
                }
                DataType res = (left == DataType.FLOAT || right == DataType.FLOAT) ? DataType.FLOAT : DataType.INTEGER;
                bin.setResultType(res);
                return res;
            }
            if (left != null && right != null && left.isText() != right.isText()) {
                throw new QOException(Code.SEMANTIC_ERROR, "Perbandingan antara angka dan teks ditolak", 0, op.name());
            }
        }
        return null;
    }

    /**
     * Memvalidasi jumlah dan tipe nilai pada {@link InsertNode} terhadap skema.
     *
     * @param node   node Insert
     * @param schema skema tabel tujuan
     * @throws QOException jika jumlah nilai atau tipe tidak sesuai
     * @calledBy bindQuery
     * @calls tidak ada
     */
    public void validateInsert(InsertNode node, Schema schema) throws QOException {
        List<Value> values = node.getValues();
        if (values == null || values.size() != schema.size()) {
            throw new QOException(Code.SEMANTIC_ERROR, "Jumlah nilai INSERT tidak sesuai skema " + schema.getTableName(), 0, schema.getTableName());
        }
        for (int i = 0; i < schema.size(); i++) {
            Column col = schema.getColumns().get(i);
            Value val = values.get(i);
            if (val == null || val.isNull()) {
                if (col.isNotNull()) {
                    throw new QOException(Code.SEMANTIC_ERROR, "Kolom '" + col.getName() + "' tidak boleh NULL", 0, col.getName());
                }
                continue;
            }
            if (col.getDataType() == DataType.INTEGER && val.dataType() == DataType.FLOAT) {
                throw new QOException(Code.SEMANTIC_ERROR, "Nilai FLOAT ditolak untuk kolom INTEGER '" + col.getName() + "'", 0, col.getName());
            }
            if (col.getDataType().isText() != val.dataType().isText()) {
                throw new QOException(Code.SEMANTIC_ERROR, "Tipe nilai tidak cocok untuk kolom '" + col.getName() + "'", 0, col.getName());
            }
        }
    }

    /**
     * Memvalidasi kolom dan tipe nilai baru pada {@link UpdateNode} terhadap skema.
     *
     * @param node   node Update
     * @param schema skema tabel tujuan
     * @throws QOException jika kolom tidak ditemukan atau tipe nilai tidak sesuai
     * @calledBy bindQuery
     * @calls tidak ada
     */
    public void validateUpdate(UpdateNode node, Schema schema) throws QOException {
        Column col = schema.getColumn(node.getColumn().getColumn());
        if (col == null) {
            throw new QOException(Code.SEMANTIC_ERROR, "Kolom '" + node.getColumn().getColumn() + "' tidak ada", 0, node.getColumn().getColumn());
        }
        node.getColumn().bind(schema.getTableName(), false, col.getDataType());
        if (node.getValue() instanceof LiteralExpr lit && lit.getValue().isNull()) {
            if (col.isNotNull()) {
                throw new QOException(Code.SEMANTIC_ERROR, "Kolom '" + col.getName() + "' tidak boleh NULL", 0, col.getName());
            }
            return;
        }
        DataType exprType = inferExpressionType(node.getValue(), List.of(schema.getTableName()));
        if (col.getDataType() == DataType.INTEGER && exprType == DataType.FLOAT) {
            throw new QOException(Code.SEMANTIC_ERROR, "Nilai FLOAT ditolak untuk kolom INTEGER '" + col.getName() + "'", 0, col.getName());
        }
        if (exprType != null && col.getDataType().isText() != exprType.isText()) {
            throw new QOException(Code.SEMANTIC_ERROR, "Tipe nilai UPDATE tidak cocok untuk kolom '" + col.getName() + "'", 0, col.getName());
        }
    }

    private Schema getSchema(String table) throws QOException {
        if (storageManager != null) {
            try {
                return storageManager.getTableSchema(table);
            } catch (StorageException e) {
                throw new QOException(Code.SEMANTIC_ERROR, e.getMessage(), 0, table);
            }
        }
        if (table == null || !table.equalsIgnoreCase(DEFAULT_TABLE)) {
            throw new QOException(Code.SEMANTIC_ERROR, "Tabel '" + table + "' tidak ditemukan", 0, table != null ? table : "");
        }
        return DEFAULT_SCHEMA;
    }

    private static String extractTableName(PlanNode node) {
        if (node instanceof ScanNode scan) {
            return scan.getTable();
        }
        return DEFAULT_TABLE;
    }
}
