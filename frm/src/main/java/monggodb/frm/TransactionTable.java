package monggodb.frm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.TxId;


final class TransactionTable {

    enum TxStatus { ACTIVE, COMMITTED, ABORTED }

    /** Status satu transaksi. Diubah hanya sambil memegang kunci objek ini. */
    static final class TxState {
        TxStatus status = TxStatus.ACTIVE;
    }

    private final Map<TxId, TxState> states = new ConcurrentHashMap<>();

    /** @return status transaksi, atau null jika tidak dikenal */
    TxState get(TxId txId) {
        return txId == null ? null : states.get(txId);
    }

    TxState getOrCreate(TxId txId) {
        return states.computeIfAbsent(txId, id -> new TxState());
    }

    void begin(TxId txId) {
        states.put(txId, new TxState());
    }

    /** Membangun ulang status dari seluruh entri log menurut urutan LSN. */
    void rebuild(List<LogRecord> records) {
        states.clear();
        for (LogRecord r : records) {
            if (r.txId() == null) continue;
            TxState st = getOrCreate(r.txId());
            switch (r.type()) {
                case COMMIT -> st.status = TxStatus.COMMITTED;
                case ABORT -> st.status = TxStatus.ABORTED;
                default -> {
                }
            }
        }
    }
}
