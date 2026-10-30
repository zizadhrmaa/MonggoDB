package monggodb.contracts.ccm;

import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ChangeRequest;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TransactionResult;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/**
 * Antarmuka CCM untuk transaksi, akses data, protokol, dan maintenance (dengan memasang CCMConfig dan FRM).
 * Semua metode dengan txId melempar CCMException(TX_UNKNOWN) jika transaksi tidak dikenal.
 * Akses SM hanya boleh dilakukan setelah ALLOWED, lalu ditutup dengan completeAccess.
 * Permintaan akses transaksi ABORTED mengembalikan ABORTED dengan alasan aslinya.
 */
public interface ConcurrencyControlManager {

    /**
     * Menjalankan recovery setelah SM dan FRM startup, sebelum menerima transaksi.
     * Mereset state runtime dan melanjutkan TxId di atas maxTxId log (null jika kosong).
     * @return hasil recovery yang berhasil
     * @throws CCMException RECOVERY_FAILED jika FRM mengembalikan success false atau melempar FRMException;
     *                      beginTransaction tetap ditolak dengan NOT_READY sampai recovery berhasil
     * @calls FRM: recover (melalui CC3)
     * @calledBy app (startSystem QP1), driver CCM
     */
    RecoveryResult initialize();

    /**
     * Memulai transaksi ACTIVE dan mencatat BEGIN sekali.
     * TxId dan timestamp dialokasikan atomik dengan nilai sama yang terus meningkat.
     * @return identitas transaksi baru yang tidak pernah dipakai ulang
     * @throws CCMException NOT_READY jika recovery belum berhasil, ADMISSION_PAUSED jika admission ditutup,
     *                      atau LOG_FAILED jika pencatatan BEGIN gagal
     * @calls FRM: logBegin (melalui CC3)
     * @calledBy QP5, QP1 (autocommit)
     */
    TxId beginTransaction();

    /**
     * Membaca status transaksi tanpa mengubahnya. COMMITTED dan ABORTED bersifat final.
     * @param txId transaksi yang diperiksa
     * @return status aktual transaksi
     * @throws CCMException TX_UNKNOWN jika transaksi tidak dikenal
     * @calls tidak ada
     * @calledBy QP1, QP5, driver CCM
     */
    TransactionStatus getTransactionStatus(TxId txId);

    /**
     * Meminta izin baca tabel tanpa membuat log perubahan.
     * @param txId transaksi peminta
     * @param resource tabel yang dibaca
     * @return keputusan akses dan requestId untuk menunggu atau menutup izin
     * @throws CCMException TX_UNKNOWN jika transaksi tidak dikenal, INVALID_REQUEST jika input tidak valid,
     *                      atau INVALID_STATE jika transaksi bukan ACTIVE dan belum ABORTED
     * @calls tidak ada
     * @calledBy QP3 (scan), QP4 (JOIN), QP5
     */
    AccessResponse requestRead(TxId txId, ResourceId resource);

    /**
     * Meminta izin perubahan data dan mencatat log sebelum SM mengubah data.
     * Tabel change harus sama dengan resource, termasuk RID dan before/after mengikuti ChangeRequest.
     * @param txId transaksi peminta
     * @param resource tabel yang dilindungi
     * @param change mutasi INSERT, UPDATE, atau DELETE
     * @return keputusan akses dan requestId; ALLOWED menyertakan LSN mutasi, sebelum ada log memakai LSN.NONE
     * @throws CCMException TX_UNKNOWN jika transaksi tidak dikenal, INVALID_REQUEST jika input atau mutasi
     *                      tidak valid, INVALID_STATE jika transaksi bukan ACTIVE dan belum ABORTED,
     *                      LOG_FAILED jika pencatatan gagal, atau ROLLBACK_FAILED jika undo gagal
     * @calls FRM: logChange, rollback jika gagal (melalui CC3)
     * @calledBy QP3 (DELETE), QP5 (INSERT/UPDATE)
     */
    AccessResponse requestWrite(TxId txId, ResourceId resource, ChangeRequest change);

    /**
     * Menunggu permintaan WAITING dengan requestId yang sama.
     * @param txId transaksi peminta
     * @param requestId identitas permintaan yang ditunggu
     * @param timeoutMs batas waktu tunggu dalam ms
     * @return keputusan akses terbaru; ALLOWED tetap wajib diikuti completeAccess
     * @throws CCMException TX_UNKNOWN jika transaksi tidak dikenal, atau INVALID_REQUEST jika permintaan
     *                      atau batas waktu tidak valid
     * @calls tidak ada
     * @calledBy QP1, QP5, operator peminta
     */
    AccessResponse waitForAccess(TxId txId, String requestId, long timeoutMs);

    /**
     * Menutup izin dan mencatat operasi setelah akses SM selesai, tanpa melepas lock transaksi.
     * Akses tetap dilindungi sampai selesai, kegagalan memicu abort ACCESS_FAILED.
     * @param txId transaksi pemilik izin
     * @param requestId identitas izin yang sebelumnya ALLOWED
     * @param success true jika operasi SM berhasil
     * @throws CCMException TX_UNKNOWN jika transaksi tidak dikenal, INVALID_REQUEST jika izin tidak valid,
     *                      atau ROLLBACK_FAILED jika undo akibat kegagalan akses gagal
     * @calls FRM: rollback (melalui CC3) jika operasi gagal
     * @calledBy QP3, QP5 setelah akses SM; QP4 melalui scan
     */
    void completeAccess(TxId txId, String requestId, boolean success);

    /**
     * Mengubah ACTIVE ke PARTIALLY_COMMITTED, lalu COMMITTED setelah log persisten, baru melepas resource.
     * COMMITTED mengembalikan success true tanpa log ulang, ABORTED/FAILED mengembalikan false tanpa perubahan.
     * @param txId transaksi yang diselesaikan
     * @return hasil penyelesaian aktual transaksi
     * @throws CCMException TX_UNKNOWN jika transaksi tidak dikenal, INVALID_STATE jika masih ada akses
     *                      berlangsung atau status tidak mengizinkan commit, LOG_FAILED jika pencatatan gagal,
     *                      atau ROLLBACK_FAILED jika undo gagal
     * @calls FRM: logCommit, rollback jika diperlukan (melalui CC3)
     * @calledBy QP5, QP1 (autocommit)
     */
    TransactionResult commitTransaction(TxId txId);

    /**
     * Mengubah transaksi ke FAILED, lalu ABORTED dan melepas resource setelah undo selesai.
     * Idempoten: panggilan kedua menunggu lalu mengembalikan TransactionResult yang sama tanpa rollback ulang.
     * @param txId transaksi yang dibatalkan
     * @param reason alasan pembatalan
     * @return hasil penyelesaian aktual transaksi
     * @throws CCMException TX_UNKNOWN jika transaksi tidak dikenal, INVALID_REQUEST jika alasan tidak valid,
     *                      atau ROLLBACK_FAILED jika undo gagal
     * @calls FRM: rollback (melalui CC3)
     * @calledBy QP5 (ABORT), QP1 (disconnect/error), executor abort CCM untuk victim CC5 atau pelanggaran CC4
     */
    TransactionResult abortTransaction(TxId txId, AbortReason reason);

    /**
     * Membaca protokol aktif.
     * @return protokol concurrency control aktif
     * @calls tidak ada
     * @calledBy QP1, driver CCM
     */
    ProtocolType getProtocol();

    /**
     * Mengganti protokol hanya jika tidak ada transaksi atau akses yang belum selesai.
     * @param protocol protokol yang dipilih
     * @throws CCMException PROTOCOL_BUSY jika masih ada transaksi atau akses belum selesai,
     *                      atau INVALID_REQUEST jika protokol tidak valid
     * @calls tidak ada
     * @calledBy driver CCM
     */
    void setProtocol(ProtocolType protocol);

    /**
     * Menaikkan hitungan penutupan admission, BEGIN ditolak dengan ADMISSION_PAUSED selama hitungan positif.
     * Commit/abort tetap berjalan. Pasangkan setiap panggilan dengan resumeTransactions di finally.
     * @calls tidak ada
     * @calledBy FR4 (checkpoint, restoreTo), QP5 (DROP TABLE)
     */
    void blockNewTransactions();

    /**
     * Menunggu transaksi belum final (termasuk FAILED/PARTIALLY_COMMITTED), akses, dan rollback selesai.
     * Timeout tidak membuka admission.
     * @param timeoutMs batas waktu tunggu dalam ms
     * @return true jika seluruh pekerjaan selesai; false jika timeout
     * @throws CCMException INVALID_REQUEST jika batas waktu tidak valid
     * @calls tidak ada
     * @calledBy FR4, QP5 (DROP TABLE), setelah blockNewTransactions
     */
    boolean waitUntilNoActive(long timeoutMs);

    /**
     * Menurunkan hitungan penutupan admission sekali untuk setiap blockNewTransactions.
     * Admission terbuka saat hitungan nol dan data aman, kecuali sudah shutdown.
     * @throws CCMException INVALID_STATE jika tidak ada penutupan admission yang dapat dipasangkan
     * @calls tidak ada
     * @calledBy FR4 setelah checkpoint atau restoreTo, QP5 setelah DROP TABLE, biasanya di blok finally
     */
    void resumeTransactions();

    /**
     * Menutup admission (BEGIN ditolak dengan ADMISSION_PAUSED), menghentikan detector,
     * dan menunggu rollback yang sedang berjalan selesai. Idempoten.
     * @calls tidak ada
     * @calledBy app (shutdownSystem QP1), setelah server ditutup, sesi aktif dibatalkan,
     *           dan FRM.stopPeriodicCheckpoint; sebelum SM.shutdown dan FRM.shutdown
     */
    void shutdown();
}
