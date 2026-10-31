package monggodb.sm.indexManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.Value;

/**
 * B+Tree di memori dengan leaf yang saling terhubung untuk range scan.
 * Insert memecah leaf dan internal node saat penuh, sedangkan delete hanya mencabut RID dari leaf
 */
public final class BTreeIndex implements IndexStructure {

    /** order default: maksimal 3 key per node */
    public static final int DEFAULT_ORDER = 4;

    private final DataType keyType;
    private final int maxKeys;
    private Node root;
    private int size;

    /**
     * @param keyType tipe kolom yang diindeks
     */
    public BTreeIndex(DataType keyType) {
        this(keyType, DEFAULT_ORDER);
    }

    /**
     * @param keyType tipe kolom yang diindeks
     * @param order jumlah maksimal anak internal, minimal 3
     * @throws IllegalArgumentException jika order kurang dari 3
     */
    public BTreeIndex(DataType keyType, int order) {
        this.keyType = Objects.requireNonNull(keyType, "keyType");
        if (order < 3) {
            throw new IllegalArgumentException("order minimal 3");
        }
        this.maxKeys = order - 1;
        this.root = new LeafNode();
    }

    @Override
    public IndexType type() {
        return IndexType.BTREE;
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
        Split split = insert(root, indexKey, rid);
        if (split != null) {
            InternalNode newRoot = new InternalNode();
            newRoot.keys.add(split.separator());
            newRoot.children.add(root);
            newRoot.children.add(split.right());
            root = newRoot;
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
        LeafNode leaf = findLeaf(indexKey);
        int position = lowerBound(leaf.entries, indexKey);
        if (position >= leaf.entries.size() || leaf.entries.get(position).key.compareTo(indexKey) != 0) {
            return;
        }

        Entry entry = leaf.entries.get(position);
        if (entry.rids.remove(rid)) {
            size--;
        }
        if (entry.rids.isEmpty()) {
            leaf.entries.remove(position);
        }
    }

    @Override
    public List<RID> search(Value key) {
        IndexKey indexKey = IndexKey.requireCompatible(keyType, key);
        LeafNode leaf = findLeaf(indexKey);
        int position = lowerBound(leaf.entries, indexKey);
        if (position < leaf.entries.size() && leaf.entries.get(position).key.compareTo(indexKey) == 0) {
            return List.copyOf(leaf.entries.get(position).rids);
        }
        return List.of();
    }

    @Override
    public List<RID> rangeScan(Value lower, Value upper, boolean lowerInclusive, boolean upperInclusive) {
        IndexKey lowerKey = lower == null ? null : IndexKey.requireCompatible(keyType, lower);
        IndexKey upperKey = upper == null ? null : IndexKey.requireCompatible(keyType, upper);
        if (lowerKey != null && upperKey != null && lowerKey.compareTo(upperKey) > 0) {
            return List.of();
        }

        LeafNode leaf = lowerKey == null ? leftmostLeaf() : findLeaf(lowerKey);
        LinkedHashSet<RID> result = new LinkedHashSet<>();
        while (leaf != null) {
            for (Entry entry : leaf.entries) {
                // cek apakah entry dalam rentang yang valid
                int lowerCompare = lowerKey == null ? 1 : entry.key.compareTo(lowerKey);
                if (lowerCompare < 0 || (lowerCompare == 0 && !lowerInclusive)) {
                    continue;
                }

                int upperCompare = upperKey == null ? -1 : entry.key.compareTo(upperKey);
                if (upperCompare > 0 || (upperCompare == 0 && !upperInclusive)) {
                    return List.copyOf(result);
                }

                result.addAll(entry.rids);
            }
            leaf = leaf.next;
        }
        return List.copyOf(result);
    }

    @Override
    public void clear() {
        root = new LeafNode();
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

    private Split insert(Node node, IndexKey key, RID rid) {
        if (node instanceof LeafNode leaf) {
            int position = lowerBound(leaf.entries, key);
            if (position < leaf.entries.size() && leaf.entries.get(position).key.compareTo(key) == 0) {
                if (leaf.entries.get(position).rids.add(rid)) {
                    size++;
                }
                return null;
            }

            // Jika entry tidak ditemukan, tambahkan entry baru
            Entry entry = new Entry(key);
            entry.rids.add(rid);
            leaf.entries.add(position, entry);
            size++;
            return leaf.entries.size() > maxKeys ? splitLeaf(leaf) : null;
        }

        InternalNode internal = (InternalNode) node;
        int childIndex = upperBound(internal.keys, key);
        Split split = insert(internal.children.get(childIndex), key, rid);
        if (split == null) {
            return null;
        }

        internal.keys.add(childIndex, split.separator());
        internal.children.add(childIndex + 1, split.right());
        return internal.keys.size() > maxKeys ? splitInternal(internal) : null;
    }

    private Split splitLeaf(LeafNode leaf) {
        int middle = leaf.entries.size() / 2;
        LeafNode right = new LeafNode();
        right.entries.addAll(leaf.entries.subList(middle, leaf.entries.size()));
        leaf.entries.subList(middle, leaf.entries.size()).clear();
        right.next = leaf.next;
        leaf.next = right;
        return new Split(right.entries.get(0).key, right);
    }

    private Split splitInternal(InternalNode internal) {
        int middle = internal.keys.size() / 2;
        IndexKey separator = internal.keys.get(middle);
        InternalNode right = new InternalNode();
        right.keys.addAll(internal.keys.subList(middle + 1, internal.keys.size()));
        right.children.addAll(internal.children.subList(middle + 1, internal.children.size()));
        internal.keys.subList(middle, internal.keys.size()).clear();
        internal.children.subList(middle + 1, internal.children.size()).clear();
        return new Split(separator, right);
    }

    private LeafNode findLeaf(IndexKey key) {
        Node node = root;
        while (node instanceof InternalNode internal) {
            node = internal.children.get(upperBound(internal.keys, key));
        }
        return (LeafNode) node;
    }

    private LeafNode leftmostLeaf() {
        Node node = root;
        while (node instanceof InternalNode internal) {
            node = internal.children.get(0);
        }
        return (LeafNode) node;
    }

    private static int upperBound(List<IndexKey> keys, IndexKey key) {
        int low = 0;
        int high = keys.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).compareTo(key) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int lowerBound(List<Entry> entries, IndexKey key) {
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (entries.get(middle).key.compareTo(key) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private abstract static class Node {
    }

    private static final class LeafNode extends Node {
        private final List<Entry> entries = new ArrayList<>();
        private LeafNode next;
    }

    private static final class InternalNode extends Node {
        private final List<IndexKey> keys = new ArrayList<>();
        private final List<Node> children = new ArrayList<>();
    }

    private static final class Entry {
        private final IndexKey key;
        private final LinkedHashSet<RID> rids = new LinkedHashSet<>();

        private Entry(IndexKey key) {
            this.key = key;
        }
    }

    private record Split(IndexKey separator, Node right) {
    }
}