package monggodb.contracts.shared;

/**
 * Alamat fisik sebuah record, berupa nomor page dan nomor slot di page itu.
 * RID tidak menyimpan nama tabel, jadi selalu dikirim bersama nama tabelnya.
 * RID tidak berubah saat record di-UPDATE, dan undo DELETE mengembalikan record ke RID yang sama.
 */
public final class RID {

    /** Nomor page di file data tabel, mulai dari 0. */
    private final int pageId;

    /** Nomor slot di dalam page, mulai dari 0. */
    private final int slotId;

    /**
     * @param pageId nomor page
     * @param slotId nomor slot
     * @throws IllegalArgumentException jika pageId atau slotId negatif
     */
    public RID(int pageId, int slotId) {
        if (pageId < 0 || slotId < 0) {
            throw new IllegalArgumentException("RID tidak valid: (" + pageId + ", " + slotId + ")");
        }
        this.pageId = pageId;
        this.slotId = slotId;
    }

    /** @return nomor page */
    public int getPageId() {
        return pageId;
    }

    /** @return nomor slot */
    public int getSlotId() {
        return slotId;
    }

    /** @return true jika pageId dan slotId sama */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RID)) {
            return false;
        }
        RID other = (RID) o;
        return pageId == other.pageId && slotId == other.slotId;
    }

    @Override
    public int hashCode() {
        return 31 * pageId + slotId;
    }

    @Override
    public String toString() {
        return "(" + pageId + ", " + slotId + ")";
    }
}
