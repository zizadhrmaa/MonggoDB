package monggodb.ccm.lifecycle;

import monggodb.contracts.shared.CCMException;

/** State dummy admission CC1, terpisah dari transaksi dan tidak dibuka otomatis oleh reset recovery. */
public class MockAdmissionState {
    private int admissionCounter;

    /** @return jumlah pemilik yang menutup admission; positif berarti BEGIN baru ditolak facade */
    public synchronized int getAdmissionCounter() {
        return admissionCounter;
    }

    /** Menyiapkan fixture test, bukan implementasi blockNewTransactions atau resumeTransactions. */
    public synchronized void setAdmissionCounter(int count) {
        if (count < 0) throw new CCMException(CCMException.Code.INVALID_REQUEST, "Hitungan admission tidak boleh negatif");
        admissionCounter = count;
    }
}
