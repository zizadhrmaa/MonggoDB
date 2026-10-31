package monggodb.sm.indexManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.Value;

/**
 * Hash index di memori dengan bucket tetap dan chaining untuk menangani collision.
 * Hash index hanya mendukung pencarian equality (range scan ditolak)
 */
public final class HashIndex implements IndexStructure {

    /** Jumlah bucket default */
    public static final int DEFAULT_BUCKET_COUNT = 16;

    private final DataType keyType;
    private final int bucketCount;
    private final List<Map<IndexKey, LinkedHashSet<RID>>> buckets;
    private int size;

    /**
     * @param keyType tipe kolom yang diindeks
     */
    public HashIndex(DataType keyType) {
        this(keyType, DEFAULT_BUCKET_COUNT);
    }

    /**
     * @param keyType     tipe kolom yang diindeks
     * @param bucketCount jumlah bucket, minimal 1
     * @throws IllegalArgumentException jika bucketCount kurang dari 1
     */
    public HashIndex(DataType keyType, int bucketCount) {
        this.keyType = Objects.requireNonNull(keyType, "keyType");
        if (bucketCount < 1) {
            throw new IllegalArgumentException("bucketCount minimal 1");
        }
        this.bucketCount = bucketCount;
        this.buckets = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            buckets.add(new LinkedHashMap<>());
        }
    }

    @Override
    public IndexType type() {
        return IndexType.HASH;
    }

    @Override
    public DataType keyType() {
        return keyType;
    }

    @Override
    public void insert(Value key, RID rid) {
        if (key == null || key.isNull()) {
            return;
        }
        if (rid == null) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST, "RID tidak boleh null");
        }
        IndexKey indexKey = IndexKey.requireCompatible(keyType, key);
        Map<IndexKey, LinkedHashSet<RID>> bucket = bucketOf(indexKey);
        if (bucket.computeIfAbsent(indexKey, ignored -> new LinkedHashSet<>()).add(rid)) {
            size++;
        }
    }

    @Override
    public void delete(Value key, RID rid) {
        if (key == null || key.isNull()) {
            return;
        }
        if (rid == null) {
            throw new StorageException(StorageException.Code.INVALID_REQUEST, "RID tidak boleh null");
        }
        IndexKey indexKey = IndexKey.requireCompatible(keyType, key);
        Map<IndexKey, LinkedHashSet<RID>> bucket = bucketOf(indexKey);
        LinkedHashSet<RID> rids = bucket.get(indexKey);
        if (rids == null) {
            return;
        }
        if (rids.remove(rid)) {
            size--;
        }
        if (rids.isEmpty()) {
            bucket.remove(indexKey);
        }
    }

    @Override
    public List<RID> search(Value key) {
        IndexKey indexKey = IndexKey.requireCompatible(keyType, key);
        LinkedHashSet<RID> rids = bucketOf(indexKey).get(indexKey);
        return rids == null ? List.of() : List.copyOf(rids);
    }

    @Override
    public List<RID> rangeScan(Value lower, Value upper, boolean lowerInclusive, boolean upperInclusive) {
        throw new StorageException(StorageException.Code.INDEX_NOT_SUPPORTED, "Hash Index tidak mendukung range scan");
    }

    @Override
    public void clear() {
        for (Map<IndexKey, LinkedHashSet<RID>> bucket : buckets) {
            bucket.clear();
        }
        size = 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    private Map<IndexKey, LinkedHashSet<RID>> bucketOf(IndexKey key) {
        return buckets.get(Math.floorMod(key.hashCode(), bucketCount));
    }
}