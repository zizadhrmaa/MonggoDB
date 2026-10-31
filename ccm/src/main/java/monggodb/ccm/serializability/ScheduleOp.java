package monggodb.ccm.serializability;

import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TxId;

/**
 * Satu operasi schedule yang direkam driver atau ditulis manual untuk verifySchedule (CC-09)
 *
 * @param seq urutan operasi, diisi berurutan mulai 1 saat operasi benar-benar terjadi
 * @param txId transaksi pelaku
 * @param kind jenis operasi
 * @param resource resource yang diakses, null untuk COMMIT/ABORT
 */
public record ScheduleOp(long seq, TxId txId, Kind kind, ResourceId resource) {

    /** Jenis operasi schedule */
    public enum Kind { READ, WRITE, COMMIT, ABORT }

    public ScheduleOp {
        if (txId == null || kind == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "txId dan kind ScheduleOp tidak boleh null");
        }
        boolean access = kind == Kind.READ || kind == Kind.WRITE;
        if (access != (resource != null)) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "resource wajib untuk READ/WRITE dan null untuk COMMIT/ABORT");
        }
    }
}
