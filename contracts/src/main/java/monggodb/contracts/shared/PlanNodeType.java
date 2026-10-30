package monggodb.contracts.shared;

/** Jenis operasi pada query tree atau execution plan. */
public enum PlanNodeType {
    SCAN, INDEX_SCAN, FILTER, PROJECT, JOIN, SORT, LIMIT, INSERT, UPDATE, DELETE,
    CREATE_INDEX, CREATE_TABLE, DROP_TABLE
}
