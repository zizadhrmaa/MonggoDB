package monggodb.qp.driver;

import java.util.ArrayList;
import java.util.List;

import monggodb.contracts.ccm.ConcurrencyControlManager;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.ChangeRequest;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TransactionResult;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/**
 * Pembungkus CCM untuk driver: meneruskan semua panggilan ke CCM asli atau mock, sambil mencatat
 * permintaan izin baca dan penutupan izin agar skenario bisa memeriksa jumlah dan urutannya.
 */
public class RecordingConcurrencyControlManager implements ConcurrencyControlManager {

    private final ConcurrencyControlManager delegate;
    private final List<ResourceId> readRequests = new ArrayList<>();
    private final List<Boolean> completions = new ArrayList<>();
    private volatile String lastReadRequestId;

    /**
     * @param delegate CCM yang menerima semua panggilan
     */
    public RecordingConcurrencyControlManager(ConcurrencyControlManager delegate) {
        this.delegate = delegate;
    }

    /** @return resource setiap panggilan requestRead sesuai urutan */
    public synchronized List<ResourceId> getReadRequests() {
        return List.copyOf(readRequests);
    }

    /** @return nilai success setiap panggilan completeAccess sesuai urutan */
    public synchronized List<Boolean> getCompletions() {
        return List.copyOf(completions);
    }

    /** @return requestId dari panggilan requestRead terakhir, atau null jika belum ada */
    public String getLastReadRequestId() {
        return lastReadRequestId;
    }

    @Override
    public RecoveryResult initialize() {
        return delegate.initialize();
    }

    @Override
    public TxId beginTransaction() {
        return delegate.beginTransaction();
    }

    @Override
    public TransactionStatus getTransactionStatus(TxId txId) {
        return delegate.getTransactionStatus(txId);
    }

    @Override
    public AccessResponse requestRead(TxId txId, ResourceId resource) {
        synchronized (this) {
            readRequests.add(resource);
        }
        AccessResponse response = delegate.requestRead(txId, resource);
        lastReadRequestId = response.requestId();
        return response;
    }

    @Override
    public AccessResponse requestWrite(TxId txId, ResourceId resource, ChangeRequest change) {
        return delegate.requestWrite(txId, resource, change);
    }

    @Override
    public AccessResponse waitForAccess(TxId txId, String requestId, long timeoutMs) {
        return delegate.waitForAccess(txId, requestId, timeoutMs);
    }

    @Override
    public void completeAccess(TxId txId, String requestId, boolean success) {
        synchronized (this) {
            completions.add(success);
        }
        delegate.completeAccess(txId, requestId, success);
    }

    @Override
    public TransactionResult commitTransaction(TxId txId) {
        return delegate.commitTransaction(txId);
    }

    @Override
    public TransactionResult abortTransaction(TxId txId, AbortReason reason) {
        return delegate.abortTransaction(txId, reason);
    }

    @Override
    public ProtocolType getProtocol() {
        return delegate.getProtocol();
    }

    @Override
    public void setProtocol(ProtocolType protocol) {
        delegate.setProtocol(protocol);
    }

    @Override
    public void blockNewTransactions() {
        delegate.blockNewTransactions();
    }

    @Override
    public boolean waitUntilNoActive(long timeoutMs) {
        return delegate.waitUntilNoActive(timeoutMs);
    }

    @Override
    public void resumeTransactions() {
        delegate.resumeTransactions();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }
}
