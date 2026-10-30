package monggodb.contracts.qo.mock;

import java.util.Map;

import monggodb.contracts.shared.BinaryExpr;
import monggodb.contracts.shared.BinaryExpr.BinaryOp;
import monggodb.contracts.shared.ColumnStats;
import monggodb.contracts.shared.DeleteNode;
import monggodb.contracts.shared.ExpressionNode;
import monggodb.contracts.shared.FilterNode;
import monggodb.contracts.shared.IsNullExpr;
import monggodb.contracts.shared.JoinNode;
import monggodb.contracts.shared.LimitNode;
import monggodb.contracts.shared.MdbmsConstants;
import monggodb.contracts.shared.PlanNode;
import monggodb.contracts.shared.ProjectNode;
import monggodb.contracts.shared.ScanNode;
import monggodb.contracts.shared.SortNode;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TableStats;
import monggodb.contracts.shared.UpdateNode;
import monggodb.contracts.shared.Value;
import monggodb.contracts.sm.StorageManager;

/**
 * Mock ringkas untuk komponen Cost Model (QO3 - QO-08) pada Milestone 1.
 * Mengembalikan estimasi biaya, kardinalitas, dan selektivitas dummy yang konsisten
 * menggunakan statistik tiruan tabel {@code students} (atau {@link StorageManager} jika diberikan).
 */
public class MockCostModel {

    private static final TableStats DEFAULT_STATS = new TableStats(
            "students",
            3,
            1,
            20.0,
            204.0,
            4096,
            Map.of(
                    "nim", new ColumnStats("nim", 3, 0, Value.ofInteger(13524001), Value.ofInteger(13524003)),
                    "name", new ColumnStats("name", 3, 0, null, null),
                    "gpa", new ColumnStats("gpa", 3, 0, Value.ofFloat(3.2f), Value.ofFloat(3.8f))));

    private final StorageManager storageManager;

    /** Membuat MockCostModel dengan statistik dummy bawaan. */
    public MockCostModel() {
        this(null);
    }

    /**
     * Membuat MockCostModel yang membaca statistik dari StorageManager tertentu.
     *
     * @param storageManager instance StorageManager, atau null untuk memakai statistik dummy bawaan
     */
    public MockCostModel(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /**
     * Menghitung total biaya plan (akumulasi seluruh node) secara mock.
     *
     * @param root akar pohon PlanNode
     * @return total estimasi biaya plan
     * @calledBy QO5, QO1 (explain)
     * @calls estimateNodeCost, estimateCardinality
     */
    public double getCost(PlanNode root) {
        if (root == null) {
            return 0.0;
        }
        double childrenCost = switch (root.getType()) {
            case FILTER -> getCost(((FilterNode) root).getChild());
            case PROJECT -> getCost(((ProjectNode) root).getChild());
            case SORT -> getCost(((SortNode) root).getChild());
            case LIMIT -> getCost(((LimitNode) root).getChild());
            case UPDATE -> getCost(((UpdateNode) root).getChild());
            case DELETE -> getCost(((DeleteNode) root).getChild());
            case JOIN -> getCost(((JoinNode) root).getLeft()) + getCost(((JoinNode) root).getRight());
            default -> 0.0;
        };
        double rows = estimateCardinality(root);
        double nodeCost = estimateNodeCost(root);
        root.setEstimatedRows(Math.round(rows));
        root.setEstimatedCost(nodeCost);
        return childrenCost + nodeCost;
    }

    /**
     * Menghitung biaya satu node secara mock.
     *
     * @param node node pada query plan
     * @return estimasi biaya node
     * @calledBy getCost
     * @calls SM.getStats
     */
    public double estimateNodeCost(PlanNode node) {
        if (node == null) {
            return 0.0;
        }
        return switch (node.getType()) {
            case SCAN -> {
                TableStats stats = getStats(((ScanNode) node).getTable());
                yield (double) stats.getBlockCount();
            }
            case INDEX_SCAN -> 0.5;
            case JOIN -> {
                int m = Math.max(3, MdbmsConstants.BNLJ_BUFFER_BLOCKS);
                double br = 1.0;
                double bs = 1.0;
                yield Math.ceil(br / (double) (m - 2)) * bs + br;
            }
            case SORT -> 2.0;
            case FILTER, PROJECT, LIMIT, CREATE_INDEX, CREATE_TABLE, DROP_TABLE -> 0.0;
            case INSERT, UPDATE, DELETE -> 1.0;
        };
    }

    /**
     * Mengestimasi jumlah baris hasil suatu node secara mock.
     *
     * @param node node pada query plan
     * @return estimasi jumlah baris hasil
     * @calledBy estimateNodeCost, QO5
     * @calls estimateSelectivity
     */
    public double estimateCardinality(PlanNode node) {
        if (node == null) {
            return 0.0;
        }
        return switch (node.getType()) {
            case SCAN -> (double) getStats(((ScanNode) node).getTable()).getRecordCount();
            case INDEX_SCAN -> 1.0;
            case FILTER -> {
                FilterNode fn = (FilterNode) node;
                yield estimateCardinality(fn.getChild()) * estimateSelectivity(fn.getCondition(), DEFAULT_STATS);
            }
            case PROJECT -> estimateCardinality(((ProjectNode) node).getChild());
            case SORT -> estimateCardinality(((SortNode) node).getChild());
            case LIMIT -> Math.min(((LimitNode) node).getLimit(), estimateCardinality(((LimitNode) node).getChild()));
            case JOIN -> {
                JoinNode jn = (JoinNode) node;
                yield estimateCardinality(jn.getLeft()) * estimateCardinality(jn.getRight());
            }
            default -> 1.0;
        };
    }

    /**
     * Mengestimasi selektivitas suatu kondisi seleksi secara mock.
     *
     * @param expression ekspresi kondisi
     * @param stats      statistik tabel
     * @return nilai selektivitas dalam rentang {@code [0.0, 1.0]}
     * @calledBy estimateCardinality
     * @calls tidak ada
     */
    public double estimateSelectivity(ExpressionNode expression, TableStats stats) {
        if (expression == null) {
            return 1.0;
        }
        if (expression instanceof IsNullExpr isNull) {
            return isNull.isNegated() ? 1.0 : 0.0;
        }
        if (expression instanceof BinaryExpr bin) {
            if (bin.getOp() == BinaryOp.AND) {
                return estimateSelectivity(bin.getLeft(), stats) * estimateSelectivity(bin.getRight(), stats);
            }
            long nr = stats != null && stats.getRecordCount() > 0 ? stats.getRecordCount() : 3L;
            return bin.getOp() == BinaryOp.EQ ? (1.0 / (double) nr) : (1.0 / 3.0);
        }
        return 1.0;
    }

    private TableStats getStats(String table) {
        if (storageManager != null && table != null) {
            try {
                TableStats stats = storageManager.getStats(table);
                if (stats != null) {
                    return stats;
                }
            } catch (StorageException ignored) {
                // fallback ke DEFAULT_STATS
            }
        }
        return DEFAULT_STATS;
    }
}
