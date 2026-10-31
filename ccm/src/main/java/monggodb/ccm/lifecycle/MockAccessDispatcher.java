package monggodb.ccm.lifecycle;

import java.util.List;

import monggodb.ccm.AccessMode;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMConfig;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TxId;

/** Mock delegasi akses CC1. Memakai state transaksi yang sama, tanpa mengimplementasikan protokol CC2/CC4. */
public class MockAccessDispatcher {
    private final MockTransactionState transactions;
    private final ProtocolType protocol;
    private final ProtocolHandler lockBased;
    private final ProtocolHandler timestampBased;

    public MockAccessDispatcher(MockTransactionState transactions) {
        this(transactions, new CCMConfig(),
                (txId, resource, mode, requestId) -> new AccessResponse(txId, requestId,
                        AccessDecision.ALLOWED, LSN.NONE, null, List.of(), "Izin dummy LOCK_BASED"),
                (txId, resource, mode, requestId) -> new AccessResponse(txId, requestId,
                        AccessDecision.ALLOWED, LSN.NONE, null, List.of(), "Izin dummy TIMESTAMP_BASED"));
    }

    /**
     * Handler dan pilihan protokol disiapkan pemanggil, bukan dihitung mock ini.
     * @param transactions state transaksi yang sama dengan pemanggil; transaksi harus ACTIVE sebelum dispatchAccess
     * @param config menentukan handler yang dipakai melalui config.protocol()
     * @param lockBased handler acquireLock milik CC2 atau lambda dummy untuk test
     * @param timestampBased handler validateTimestamp milik CC4 atau lambda dummy untuk test
     */
    public MockAccessDispatcher(MockTransactionState transactions, CCMConfig config,
                                ProtocolHandler lockBased, ProtocolHandler timestampBased) {
        if (transactions == null || config == null || lockBased == null || timestampBased == null) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "State transaksi, konfigurasi, dan handler wajib diisi");
        }
        this.transactions = transactions;
        this.protocol = config.protocol();
        this.lockBased = lockBased;
        this.timestampBased = timestampBased;
    }

    /** Meneruskan input dan respons tanpa menjalankan logging, rollback, atau algoritma protokol. */
    public synchronized AccessResponse dispatchAccess(TxId txId, ResourceId resource, AccessMode mode, String requestId) {
        transactions.ensureActive(txId);
        if (resource == null || mode == null || requestId == null || requestId.isBlank()) {
            throw new CCMException(CCMException.Code.INVALID_REQUEST, "Resource, mode, dan requestId wajib diisi");
        }
        ProtocolHandler handler = protocol == ProtocolType.LOCK_BASED ? lockBased : timestampBased;
        AccessResponse response = handler.dispatch(txId, resource, mode, requestId);
        if (response == null || !response.txId().equals(txId) || !response.requestId().equals(requestId)) {
            throw new CCMException(CCMException.Code.INVALID_STATE, "Handler mengembalikan respons untuk permintaan yang tidak sesuai");
        }
        // Handler dapat berjalan saat state berubah. Jangan teruskan izin dari transaksi yang sudah tidak ACTIVE.
        // Respons ABORTED tetap diteruskan karena handler boleh menandai transaksi FAILED.
        synchronized (transactions) {
            if (response.decision() != AccessDecision.ABORTED) transactions.ensureActive(txId);
            return response;
        }
    }

    /**
     * Callback untuk meneruskan akses ke metode protokol yang parameternya cocok.
     * Contoh membuat handler dari mock timestamp yang sudah tersedia (^ v ^):
     *      var timestampMock = new monggodb.ccm.timestamp.MockTimestampProtocol();
     *      MockAccessDispatcher.ProtocolHandler timestampHandler = timestampMock::validateTimestamp;
     */
    @FunctionalInterface
    public interface ProtocolHandler {
        /**
         * Menerima permintaan dari dispatcher mock.
         * @param txId transaksi peminta
         * @param resource tabel yang dilindungi
         * @param mode READ atau WRITE
         * @param requestId identitas permintaan
         * @return respons handler
         * @calls handler yang diinjeksi
         * @calledBy dispatchAccess
         */
        AccessResponse dispatch(TxId txId, ResourceId resource, AccessMode mode, String requestId);
    }
}
