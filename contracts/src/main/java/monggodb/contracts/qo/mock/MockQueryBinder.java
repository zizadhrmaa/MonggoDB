package monggodb.contracts.qo.mock;

import java.util.ArrayList;
import java.util.List;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.ColumnRef;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.InsertNode;
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
 * Hanya menyediakan metode pintu masuk {@link #bindQuery(ParsedQuery)} tanpa mengekspose metode helper internal.
 */
public class MockQueryBinder {

    private static final String DEFAULT_TABLE = "students";
    private static final Schema DEFAULT_SCHEMA = new Schema(DEFAULT_TABLE, List.of(
            new Column("nim", DEFAULT_TABLE, DataType.INTEGER, 0, true, true, null),
            new Column("name", DEFAULT_TABLE, DataType.VARCHAR, 50, false, false, null),
            new Column("gpa", DEFAULT_TABLE, DataType.FLOAT, 0, false, false, null)));

    private final StorageManager storageManager;

    /** Membuat MockQueryBinder dengan skema dummy bawaan tabel {@code students}. */
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
     * Memvalidasi query terhadap skema mock dan menjabarkan {@code SELECT *} secara ringkas.
     *
     * @param query query hasil parser yang belum terikat
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
            Schema schema = getSchema(insertNode.getTable());
            List<Value> values = insertNode.getValues();
            if (values == null || values.size() != schema.size()) {
                throw new QOException(Code.SEMANTIC_ERROR, "Jumlah nilai INSERT tidak sesuai skema " + schema.getTableName(), 0, schema.getTableName());
            }
        } else if (head instanceof UpdateNode updateNode) {
            Schema schema = getSchema(updateNode.getTable());
            Column col = schema.getColumn(updateNode.getColumn().getColumn());
            if (col == null) {
                throw new QOException(Code.SEMANTIC_ERROR, "Kolom '" + updateNode.getColumn().getColumn() + "' tidak ditemukan", 0, updateNode.getColumn().getColumn());
            }
            updateNode.getColumn().bind(schema.getTableName(), false, col.getDataType());
        } else if (head instanceof ProjectNode projectNode) {
            String table = projectNode.getChild() instanceof ScanNode scan ? scan.getTable() : DEFAULT_TABLE;
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
                    Column col = schema.getColumn(colRef.getColumn());
                    if (col == null) {
                        throw new QOException(Code.SEMANTIC_ERROR, "Kolom '" + colRef.getColumn() + "' tidak ditemukan", 0, colRef.getColumn());
                    }
                    colRef.bind(schema.getTableName(), false, col.getDataType());
                    boundCols.add(colRef);
                }
            }
            projectNode.setColumns(boundCols);
        }
        return query;
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
}
