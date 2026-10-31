package monggodb.contracts.qo.mock;

import java.util.Map;

import monggodb.contracts.shared.ColumnStats;
import monggodb.contracts.shared.DeleteNode;
import monggodb.contracts.shared.FilterNode;
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
 * Hanya menyediakan metode pintu masuk {@link #getCost(PlanNode)} tanpa mengekspose metode helper internal.
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
     * @calls SM.getStats
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

        TableStats scanStats = DEFAULT_STATS;
        if (root instanceof ScanNode scan && storageManager != null && scan.getTable() != null) {
            try {
                TableStats s = storageManager.getStats(scan.getTable());
                if (s != null) {
                    scanStats = s;
                }
            } catch (StorageException ignored) {
                // fallback ke DEFAULT_STATS
            }
        }

        double nodeCost = switch (root.getType()) {
            case SCAN -> (double) scanStats.getBlockCount();
            case INDEX_SCAN -> 0.5;
            case JOIN -> {
                int m = Math.max(3, MdbmsConstants.BNLJ_BUFFER_BLOCKS);
                yield Math.ceil(1.0 / (double) (m - 2)) * 1.0 + 1.0;
            }
            case SORT -> 2.0;
            case FILTER, PROJECT, LIMIT, CREATE_INDEX, CREATE_TABLE, DROP_TABLE -> 0.0;
            case INSERT, UPDATE, DELETE -> 1.0;
        };

        long dummyRows = root instanceof ScanNode
                ? scanStats.getRecordCount()
                : Math.max(1L, root.getEstimatedRows());
        root.setEstimatedRows(dummyRows);
        root.setEstimatedCost(nodeCost);
        return childrenCost + nodeCost;
    }
}
