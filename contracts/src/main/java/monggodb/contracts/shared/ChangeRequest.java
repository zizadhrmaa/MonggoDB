package monggodb.contracts.shared;

/**
 * Ringkasan satu mutasi record yang akan ditulis ke SM dan perlu dicatat sebagai log WAL oleh CCM.
 * Immutable.
 *
 * @param operationId identitas stabil satu mutasi record
 * @param type jenis mutasi, hanya {@link LogType#INSERT}, {@link LogType#UPDATE}, atau {@link LogType#DELETE}
 * @param table nama tabel
 * @param rid alamat record yang dimutasi
 * @param before nilai sebelum mutasi, wajib {@code null} untuk INSERT dan tidak boleh {@code null} untuk UPDATE/DELETE
 * @param after nilai sesudah mutasi, wajib {@code null} untuk DELETE dan tidak boleh {@code null} untuk INSERT/UPDATE
 */
public record ChangeRequest(String operationId, LogType type, String table,
                            RID rid, Row before, Row after) {

    /** Menjaga invarian. */
    public ChangeRequest {
        if (operationId == null || operationId.isBlank()) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "operationId ChangeRequest tidak boleh kosong");
        }
        if (table == null || table.isBlank()) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "table ChangeRequest tidak boleh kosong");
        }
        if (rid == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "rid ChangeRequest tidak boleh null");
        }
        if (type == null || (type != LogType.INSERT && type != LogType.UPDATE && type != LogType.DELETE)) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "type ChangeRequest harus INSERT, UPDATE, atau DELETE");
        }
        if (type == LogType.INSERT && (before != null || after == null)) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "INSERT harus memiliki before null dan after tidak null");
        }
        if (type == LogType.UPDATE && (before == null || after == null)) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "UPDATE harus memiliki before dan after tidak null");
        }
        if (type == LogType.DELETE && (before == null || after != null)) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST,
                    "DELETE harus memiliki before tidak null dan after null");
        }
    }
}
