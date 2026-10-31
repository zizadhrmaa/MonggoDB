package monggodb.contracts.shared;

/** Jenis perintah SQL yang disimpan dalam ParsedQuery. */
public enum StatementType {
    SELECT, INSERT, UPDATE, DELETE, CREATE_INDEX, CREATE_TABLE, DROP_TABLE, BEGIN, COMMIT, ABORT
}
