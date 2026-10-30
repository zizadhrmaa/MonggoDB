package monggodb.contracts.shared;

/**
 * CCM menyimpan identitas resource yang dilindungi.
 * Scan dan mutasi pada tabel yang sama memakai resource yang sama.
 *
 * @param table nama tabel
 * @param granularity granularitas perlindungan, hanya mendukung TABLE
 * @param rid alamat record, null karena granularitas TABLE
 */
public record ResourceId(String table, Granularity granularity, RID rid) {

    /** Granularitas resource CCM. */
    public enum Granularity {
        /** Seluruh tabel, granularitas ROW tidak dipakai. */
        TABLE
    }

    public ResourceId {
        if (table == null || table.isBlank()) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "table ResourceId tidak boleh kosong");
        }
        if (granularity == null || rid != null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "ResourceId harus memakai granularitas TABLE tanpa RID");
        }
    }

    /**
     * Membuat resource tabel tanpa RID.
     * @param table nama tabel
     */
    public ResourceId(String table) {
        this(table, Granularity.TABLE, null);
    }
}
