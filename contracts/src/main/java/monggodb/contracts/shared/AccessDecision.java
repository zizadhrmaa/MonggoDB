package monggodb.contracts.shared;

/** Keputusan permintaan akses, perhatikan jika transaksi tidak dikenal atau input tidak valid, maka perlu memakai CCMException. */
public enum AccessDecision {
    /** Operasi SM boleh dijalankan, lalu wajib ditutup dengan completeAccess. */
    ALLOWED,
    /** Akses belum diizinkan, lanjutkan melalui waitForAccess. */
    WAITING,
    /** Transaksi dibatalkan. */
    ABORTED
}
