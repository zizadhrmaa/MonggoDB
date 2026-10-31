package monggodb.sm.indexManager;

import java.util.List;

import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.IndexType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.Value;

/**
 * Struktur index di memori yang menyimpan pasangan key dan RID
 * Key NULL tidak pernah disimpan, insert dan delete bersifat idempoten, dan hasil pencarian tidak berisi RID duplikat
 */
public interface IndexStructure {

    /**
     * @return jenis index struktur
     * @calls tidak ada
     * @calledBy IndexManagerImpl
     */
    IndexType type();

    /**
     * @return tipe kolom yang diindeks
     * @calls tidak ada
     * @calledBy IndexManagerImpl
     */
    DataType keyType();

    /**
     * Menambah pasangan key dan RID. Key NULL diabaikan, dan penambahan RID yang sudah ada tidak mengubah isi.
     *
     * @param key nilai key
     * @param rid alamat record
     * @throws StorageException INVALID_REQUEST jika rid null, TYPE_MISMATCH jika tipe key tidak cocok
     * @calls IndexKey.requireCompatible
     * @calledBy IndexManagerImpl.insertEntry
     */
    void insert(Value key, RID rid);

    /**
     * Menghapus pasangan key dan RID. Key NULL atau entri yang tidak ada diabaikan.
     *
     * @param key nilai key
     * @param rid alamat record
     * @throws StorageException INVALID_REQUEST jika rid null, TYPE_MISMATCH jika tipe key tidak cocok
     * @calls IndexKey.requireCompatible
     * @calledBy IndexManagerImpl.deleteEntry
     */
    void delete(Value key, RID rid);

    /**
     * Mencari semua RID dengan key yang sama.
     *
     * @param key nilai key, tidak boleh null atau NULL SQL
     * @return daftar RID tanpa duplikat, kosong jika tidak ada
     * @throws StorageException INVALID_REQUEST jika key null atau NULL SQL, TYPE_MISMATCH jika tipe key tidak cocok
     * @calls IndexKey.requireCompatible
     * @calledBy IndexManagerImpl.lookupEqual
     */
    List<RID> search(Value key);

    /**
     * Menelusuri key dalam rentang tertentu. Batas null berarti sisi tersebut terbuka.
     *
     * @param lower batas bawah atau null
     * @param upper batas atas atau null
     * @param lowerInclusive true jika batas bawah ikut
     * @param upperInclusive true jika batas atas ikut
     * @return daftar RID tanpa duplikat, berurut naik menurut key
     * @throws StorageException INVALID_REQUEST jika batas berupa Value NULL, TYPE_MISMATCH jika tipe tidak cocok, INDEX_NOT_SUPPORTED untuk index HASH
     * @calls IndexKey.requireCompatible
     * @calledBy IndexManagerImpl.lookupRange
     */
    List<RID> rangeScan(Value lower, Value upper, boolean lowerInclusive, boolean upperInclusive);

    /**
     * Mengosongkan seluruh isi index.
     * @calls tidak ada
     * @calledBy IndexManagerImpl.clearIndexes
     */
    void clear();

    /**
     * @return jumlah pasangan key dan RID yang tersimpan
     * @calls tidak ada
     * @calledBy IndexManagerImpl
     */
    int size();

    /**
     * @return true jika tidak ada pasangan key dan RID
     * @calls size()
     * @calledBy IndexManagerImpl
     */
    boolean isEmpty();
}