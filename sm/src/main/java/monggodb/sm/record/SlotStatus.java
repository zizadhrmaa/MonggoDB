package monggodb.sm.record;

import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.StorageException.Code;

/**
 * Status satu slot di slotted page. Disimpan sebagai satu byte di slot directory.
 * Kode EMPTY sengaja 0, supaya slot di page yang baru dialokasikan (isinya byte nol) terbaca kosong.
 */
public enum SlotStatus {
    /** Slot tidak berisi record: belum pernah dipakai, atau record-nya sudah dihapus. */
    EMPTY(0),
    /** Slot sudah dipesan lewat allocateRid tapi belum diisi. Ruangnya sudah disisihkan. */
    RESERVED(1),
    /** Slot berisi record yang valid. */
    USED(2),
    /** Slot berisi forwarding pointer ke lokasi baru record yang dipindah. */
    FORWARD(3);

    /** Kode byte status di slot directory. */
    private final int code;

    SlotStatus(int code) {
        this.code = code;
    }

    /** @return kode byte status ini di slot directory */
    public int getCode() {
        return code;
    }

    /**
     * @param code kode byte dari slot directory
     * @return status dengan kode tersebut
     * @throws StorageException MALFORMED_RECORD jika kode tidak dikenal
     */
    public static SlotStatus fromCode(int code) {
        for (SlotStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new StorageException(Code.MALFORMED_RECORD, "Status slot tidak dikenal: " + code);
    }
}
