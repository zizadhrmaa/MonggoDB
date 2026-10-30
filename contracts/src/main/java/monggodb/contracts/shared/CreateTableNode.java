package monggodb.contracts.shared;

public class CreateTableNode {
    
}
package monggodb.contracts.shared;

/**
 * Merepresentasikan perintah CREATE TABLE untuk membuat tabel baru berdasarkan skema yang ditentukan.
 *
 * Schema menyimpan definisi kolom beserta constraint, termasuk PRIMARY KEY, NOT NULL, dan FOREIGN KEY.
 */
public class CreateTableNode extends PlanNode {

    /** Skema tabel baru yang akan dibuat. */
    private final Schema schema;

    /**
     * Membuat node perintah CREATE TABLE.
     *
     * @param schema skema tabel yang akan dibuat
     */
    public CreateTableNode(Schema schema) {
        super(PlanNodeType.CREATE_TABLE);
        this.schema = schema;
    }

    /**
     * Mengembalikan skema tabel yang akan dibuat.
     *
     * @return skema tabel
     */
    public Schema getSchema() {
        return schema;
    }
}