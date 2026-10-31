package monggodb.ccm.lock;

import java.util.Map;
import java.util.Set;

import monggodb.ccm.AccessMode;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/**
 * Kontrak internal CC2 untuk protokol penguncian Strict 2PL bertingkat TABLE (CC-06)
 * serta koordinasi state antrian tunggu dan detector deadlock (CC-05, CC-07).
 */
public interface LockProtocol {

    /**
     * Meminta penguncian shared (READ) atau exclusive (WRITE) pada resource bertingkat TABLE,
     * menangani permintaan lock ulang (re-entrant), upgrade S ke X, dan antrian tunggu FIFO.
     *
     * @param txId identitas transaksi peminta
     * @param resource tabel yang diminta untuk dikunci
     * @param mode READ untuk lock S atau WRITE untuk lock X
     * @param requestId identitas stabil permintaan untuk korelasi waitForAccess dan completeAccess
     * @return keputusan akses ALLOWED, WAITING beserta daftar penghalang, atau ABORTED (DEADLOCK_VICTIM)
     * @throws CCMException INVALID_REQUEST bila argumen null, requestId kosong, atau granularity bukan TABLE
     * @calls buildWaitForGraph, detectDeadlock (saat permintaan menjadi WAITING)
     * @calledBy dispatchAccess
     */
    AccessResponse acquireLock(TxId txId, ResourceId resource, AccessMode mode, String requestId);

    /**
     * Melepas seluruh lock S/X dan menghapus entri antrian tunggu milik transaksi setelah mencapai
     * status akhir COMMITTED atau ABORTED, lalu membangunkan penunggu pada tiap resource terdampak.
     *
     * @param txId identitas transaksi yang selesai
     * @param finalStatus status akhir transaksi (COMMITTED atau ABORTED)
     * @throws CCMException INVALID_REQUEST bila txId atau finalStatus null, atau bukan status final
     * @calls wakeWaiters
     * @calledBy commitTransaction, rollbackTransaction
     */
    void releaseResources(TxId txId, TransactionStatus finalStatus);

    /**
     * Memeriksa antrian tunggu pada resource sesuai kompatibilitas lock S/X, urutan FIFO, dan prioritas
     * upgrade S ke X, lalu memberikan akses kepada penunggu yang memenuhi syarat.
     *
     * @param resource resource yang kepemilikan lock atau penanda aksesnya berubah
     * @throws CCMException INVALID_REQUEST bila resource null
     * @calls tidak ada
     * @calledBy releaseResources, onAccessCompleted
     */
    void wakeWaiters(ResourceId resource);

    /**
     * Menghapus penanda akses Storage Manager yang sedang berlangsung untuk pasangan (txId, requestId)
     * tanpa melepas lock 2PL transaksi, lalu memanggil wakeWaiters pada resource terkait dari requestId tersebut.
     *
     * @param txId identitas transaksi pemilik akses
     * @param requestId identitas akses yang sebelumnya diizinkan (ALLOWED) pada acquireLock
     * @param success true bila operasi Storage Manager berhasil
     * @throws CCMException INVALID_REQUEST bila txId atau requestId tidak valid atau akses tidak sedang berlangsung
     * @calls wakeWaiters
     * @calledBy completeAccess
     */
    void onAccessCompleted(TxId txId, String requestId, boolean success);

    /**
     * Mengosongkan seluruh lock table, antrian tunggu, penanda akses aktif, dan penanda kepemilikan lock
     * ketika pemulihan selesai dijalankan saat inisialisasi CCM.
     *
     * @calls tidak ada
     * @calledBy resetAfterRecovery
     */
    void resetLockState();

    /**
     * Menghentikan thread pendeteksi deadlock berkala dan menunggu hingga thread berhenti.
     * Bersifat idempoten saat dipanggil berulang kali.
     *
     * @calls tidak ada
     * @calledBy shutdown
     */
    void stopDetector();

    /**
     * Mengambil salinan atomik hubungan tunggu-menunggu dari lock table di bawah mutex yang sama
     * untuk diteruskan ke buildWaitForGraph dan detectDeadlock milik CC5.
     *
     * @return peta transaksi penunggu ke himpunan transaksi pemegang lock yang menghalanginya
     * @calls tidak ada
     * @calledBy acquireLock, thread detector berkala
     */
    Map<TxId, Set<TxId>> snapshotWaitForGraph();
}

