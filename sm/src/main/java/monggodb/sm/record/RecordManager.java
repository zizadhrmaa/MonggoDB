package monggodb.sm.record;

import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.StorageException;

/**
 * Operasi internal SM1 untuk menulis record ke slotted page: memeriksa Row terhadap skema,
 * memilih page yang muat, dan menulis isi slot.
 * Dipakai metode publik StorageManager seperti allocateRid, insertRecordAt, updateRecord, dan deleteRecord.
 */
public interface RecordManager {

    /**
     * Memastikan jumlah, urutan, dan tipe nilai Row sesuai skema tabel.
     *
     * @param table nama tabel
     * @param row   record yang diperiksa; RID-nya diabaikan
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada, INVALID_REQUEST jika row null,
     *                          SCHEMA_MISMATCH jika jumlah nilai tidak sama dengan jumlah kolom,
     *                          atau NULL_NOT_ALLOWED, TYPE_MISMATCH, VALUE_TOO_LONG dari serializer
     * @calls TableCatalog.getTableSchema, SM3 (validate)
     * @calledBy allocateRid, insertRecordAt, updateRecord
     */
    void validateRow(String table, Row row);

    /**
     * Memilih page yang punya ruang untuk satu slot baru berisi record sebesar {@code sizeInBytes}.
     * Page terakhir tabel dicoba lebih dulu; kalau tidak muat, page baru dialokasikan di akhir file.
     * Ruang tidak dipesan di sini, jadi pemanggil langsung menulis slot ke page yang dikembalikan.
     *
     * @param table       nama tabel
     * @param sizeInBytes ukuran record dalam byte
     * @return nomor page yang muat
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada,
     *                          INVALID_REQUEST jika ukuran negatif atau lebih besar dari satu page
     * @calls TableCatalog.getDataFile, SM2 (getBlockCount, fetchPage, newPage, unpinPage)
     * @calledBy allocateRid, relocateRecord
     */
    int findPageWithSpace(String table, int sizeInBytes);

    /**
     * Menulis isi dan status satu slot, lalu menandai page kotor dengan
     * pageLSN = max(pageLSN lama, lsn). Kalau page belum ada di file, file diperpanjang
     * sampai page tersebut, misalnya saat redo FRM setelah crash.
     *
     * @param table       nama tabel
     * @param pageId      nomor page
     * @param slotId      nomor slot, boleh di luar slot directory saat ini
     * @param status      status baru slot
     * @param recordBytes isi slot; array kosong untuk slot EMPTY
     * @param lsn         LSN catatan log perubahan ini, atau LSN.NONE jika tanpa log
     * @throws StorageException TABLE_NOT_FOUND jika tabel tidak ada, INVALID_RID jika pageId atau slotId negatif,
     *                          INVALID_REQUEST jika ada argumen null atau record tidak muat di page
     * @calls TableCatalog.getDataFile, SM2 (getBlockCount, newPage, fetchPage, markDirty, unpinPage)
     * @calledBy insertRecordAt, updateRecord, deleteRecord, relocateRecord
     */
    void writeSlot(String table, int pageId, int slotId, SlotStatus status, byte[] recordBytes, LSN lsn);
}
