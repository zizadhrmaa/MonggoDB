package monggodb.contracts.frm;

import java.util.List;

import monggodb.contracts.shared.CheckpointResult;
import monggodb.contracts.shared.FRMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.RecoveryTarget;
import monggodb.contracts.shared.RollbackResult;
import monggodb.contracts.shared.TxId;

/**
 * Pintu masuk Failure Recovery Manager: Write-Ahead Log, rollback, checkpoint, crash recovery,
 * dan pemulihan historis. Storage Manager dan CCM dipasang ke implementasi oleh app saat startSystem,
 * bukan lewat antarmuka ini.
 */
public interface FailureRecoveryManager {

    /**
     * Membuka file log, lalu membuat snapshot awal jika belum ada. Urutan ini wajib karena
     * createSnapshot milik SM memanggil flushAll, yang memanggil flushLogUntil.
     * @throws FRMException LOG_WRITE_FAILED jika file log tidak dapat dibuka
     * @calls SM: createSnapshot (lewat createInitialSnapshot)
     * @calledBy app (startSystem QP1), setelah SM.startup dan sebelum CCM.initialize
     */
    void startup();

    /**
     * Menulis sisa buffer log ke disk lalu menutup file log. Dipanggil paling akhir.
     * @throws FRMException LOG_WRITE_FAILED jika penulisan gagal
     * @calls tidak ada
     * @calledBy app (shutdownSystem QP1), setelah stopPeriodicCheckpoint dan SM.shutdown
     */
    void shutdown();

    /**
     * Mencatat awal transaksi.
     * @param txId transaksi yang dimulai
     * @return LSN entri BEGIN
     * @throws FRMException LOG_WRITE_FAILED atau LOG_CORRUPTED jika pencatatan gagal; transaksi tidak boleh dimulai
     * @calls tidak ada
     * @calledBy CCM (CC3)
     */
    LSN logBegin(TxId txId);

    /**
     * Mencatat satu perubahan data (INSERT/UPDATE/DELETE) sebelum diterapkan. FRM mengisi lsn,
     * timestamp, dan prevLSN. Entri belum tentu persisten saat kembali; SM wajib memanggil
     * {@link #flushLogUntil} sebelum menulis halaman yang memuat perubahan ini.
     * @param record catatan dari CCM berisi txId, type, table, rid, before, dan after
     * @return LSN entri; dipakai SM sebagai pageLSN
     * @throws FRMException LOG_WRITE_FAILED jika pencatatan gagal; perubahan tidak boleh dilanjutkan
     * @calls tidak ada
     * @calledBy CCM (CC3)
     */
    LSN logChange(LogRecord record);

    /**
     * Mencatat COMMIT, lalu memaksa log tersimpan permanen sebelum kembali.
     * @param txId transaksi yang commit
     * @return LSN entri COMMIT
     * @throws FRMException LOG_WRITE_FAILED jika log gagal disimpan; transaksi tidak boleh dinyatakan committed
     * @calls tidak ada
     * @calledBy CCM (CC3)
     */
    LSN logCommit(TxId txId);

    /**
     * Memastikan log sampai LSN tertentu sudah persisten. {@link LSN#NONE} langsung kembali.
     * @param lsn batas LSN, biasanya pageLSN halaman yang akan ditulis
     * @throws FRMException LOG_WRITE_FAILED jika penulisan gagal; halaman tidak boleh ditulis
     * @calls tidak ada
     * @calledBy SM (SM2), sebelum menulis halaman ke disk, termasuk saat page replacement
     */
    void flushLogUntil(LSN lsn);

    /**
     * Membatalkan semua perubahan satu transaksi dalam urutan terbalik, lalu mencatat ABORT.
     * <p>Isi log setelah rollback: setiap perubahan yang dibatalkan menghasilkan satu entri baru milik
     * transaksi yang sama, memakai jenis yang sudah ada di LogType dengan before/after dibalik:
     * <ul>
     *   <li>pembatalan INSERT dicatat sebagai DELETE (before = after entri asli, after kosong)</li>
     *   <li>pembatalan UPDATE dicatat sebagai UPDATE (before dan after entri asli ditukar)</li>
     *   <li>pembatalan DELETE dicatat sebagai INSERT di RID yang sama (before kosong, after = before entri asli)</li>
     * </ul>
     * Entri ini dicatat dari perubahan terakhir ke yang pertama, lalu ditutup ABORT. Contoh: BEGIN, INSERT r1,
     * UPDATE r2, lalu rollback menghasilkan UPDATE r2 (dibalik), DELETE r1, ABORT. Entri pembatalan ini
     * diperlukan agar recovery dapat mengulang riwayat log apa adanya. Pemanggil tidak perlu membuatnya;
     * CCM tetap hanya memanggil rollback.
     * @param txId transaksi yang dibatalkan
     * @return DONE, ALREADY_ABORTED, TX_COMMITTED, TX_UNKNOWN, atau FAILED
     * @calls SM: fetchRecord, insertRecordAt, updateRecord, deleteRecord
     * @calledBy CCM (CC3)
     */
    RollbackResult rollback(TxId txId);

    /**
     * Pemulihan otomatis saat startup: analisis, redo (mengulang riwayat sejak checkpoint terakhir),
     * lalu undo transaksi yang belum selesai.
     * @return hasil pemulihan; success = false jika gagal (log rusak atau SM gagal)
     * @calls SM: fetchRecord, insertRecordAt, updateRecord, deleteRecord, flushAll
     * @calledBy CCM (CC3) dari initialize, setelah FRM.startup dan SM.startup
     */
    RecoveryResult recover();

    /**
     * Membuat checkpoint. Tidak pernah berjalan bersamaan dengan checkpoint lain atau restoreTo.
     * Jika waitUntilNoActive timeout, admission tetap dibuka kembali dan hasilnya gagal.
     * @return hasil checkpoint
     * @calls CCM: blockNewTransactions, waitUntilNoActive, resumeTransactions; SM: flushAll
     * @calledBy driver FRM, checkpoint berkala (internal), QP5 (setelah DROP TABLE)
     */
    CheckpointResult checkpoint();

    /**
     * Memulihkan basis data ke waktu atau txId tertentu dari snapshot awal dan log, lalu flush,
     * membuat checkpoint baru, dan membuka kembali admission (juga saat gagal).
     * @param target batas pemulihan
     * @return hasil pemulihan; success = false jika target di luar rentang atau proses gagal
     * @calls CCM: blockNewTransactions, waitUntilNoActive, resumeTransactions; SM: loadSnapshot, insertRecordAt, updateRecord, deleteRecord, flushAll
     * @calledBy driver FRM
     */
    RecoveryResult restoreTo(RecoveryTarget target);

    /**
     * Membaca isi log (persisten dan yang masih di buffer) menurut urutan LSN, opsional difilter per
     * transaksi. Tidak mengubah log.
     * @param txId transaksi yang dicari; null berarti seluruh isi log
     * @return daftar entri; kosong jika log kosong atau transaksi tidak punya entri
     * @calls tidak ada
     * @calledBy driver FRM, driver CCM
     */
    List<LogRecord> readLog(TxId txId);

    /**
     * Menyalakan checkpoint berkala.
     * @param intervalMs interval dalam milidetik; 0 atau negatif berarti tidak dinyalakan
     * @calls tidak ada
     * @calledBy app (startSystem QP1), setelah CCM.initialize selesai
     */
    void startPeriodicCheckpoint(long intervalMs);

    /**
     * Menghentikan checkpoint berkala dan menunggu checkpoint yang sedang berjalan sampai selesai. Idempoten.
     * @calls tidak ada
     * @calledBy app (shutdownSystem QP1), setelah server ditutup dan sebelum CCM.shutdown
     */
    void stopPeriodicCheckpoint();
}
