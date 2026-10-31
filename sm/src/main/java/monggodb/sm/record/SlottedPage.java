package monggodb.sm.record;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.StorageException.Code;

/**
 * Tampilan slotted page di atas byte satu page. Header dan slot directory ada di awal page,
 * isi record ditulis dari akhir page ke arah depan. Semua angka disimpan big-endian.
 * <p>
 * Header (4 byte): jumlah slot (2 byte) dan awal area record (2 byte; 0 berarti page baru yang
 * belum pernah ditulis). Setiap slot (7 byte): status (1 byte), offset (2 byte), kapasitas (2 byte),
 * dan panjang record (2 byte).
 * <p>
 * Kapasitas adalah ruang milik slot, panjang adalah jumlah byte record yang sedang terisi.
 * Menulis record yang tidak lebih panjang dari kapasitas selalu dilakukan di tempat, jadi ruang
 * slot yang record-nya dihapus tetap tersedia untuk undo DELETE di RID yang sama.
 */
public final class SlottedPage {

    /** Ukuran header page dalam byte. */
    public static final int HEADER_SIZE = 4;

    /** Ukuran satu entri slot directory dalam byte. */
    public static final int SLOT_SIZE = 7;

    /** Ukuran page terbesar yang offset-nya masih muat di 2 byte. */
    private static final int MAX_PAGE_SIZE = 0xFFFF;

    /** Byte page yang dibaca dan diubah langsung. */
    private final byte[] data;

    /** Pembungkus data untuk membaca dan menulis angka big-endian. */
    private final ByteBuffer buffer;

    /**
     * @param data byte satu page, biasanya {@code Page.getData()} dari buffer pool
     * @throws IllegalArgumentException jika ukuran page tidak bisa dipakai sebagai slotted page
     */
    public SlottedPage(byte[] data) {
        Objects.requireNonNull(data, "data tidak boleh null");
        if (data.length <= HEADER_SIZE || data.length > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Ukuran page tidak didukung: " + data.length);
        }
        this.data = data;
        this.buffer = ByteBuffer.wrap(data);
    }

    /** @return jumlah slot di slot directory */
    public int getSlotCount() {
        return readU16(0);
    }

    /** @return offset awal area record; sama dengan ukuran page jika belum ada record */
    public int getRecordAreaStart() {
        int start = readU16(2);
        return start == 0 ? data.length : start;
    }

    /**
     * @param slotId nomor slot
     * @return status slot
     * @throws StorageException INVALID_RID jika slot tidak ada
     */
    public SlotStatus getSlotStatus(int slotId) {
        checkSlot(slotId);
        return SlotStatus.fromCode(data[slotPosition(slotId)] & 0xFF);
    }

    /**
     * @param slotId nomor slot
     * @return offset area milik slot di page
     * @throws StorageException INVALID_RID jika slot tidak ada
     */
    public int getSlotOffset(int slotId) {
        checkSlot(slotId);
        return readU16(slotPosition(slotId) + 1);
    }

    /**
     * @param slotId nomor slot
     * @return kapasitas area milik slot dalam byte
     * @throws StorageException INVALID_RID jika slot tidak ada
     */
    public int getSlotCapacity(int slotId) {
        checkSlot(slotId);
        return readU16(slotPosition(slotId) + 3);
    }

    /**
     * @param slotId nomor slot
     * @return panjang record yang terisi di slot dalam byte
     * @throws StorageException INVALID_RID jika slot tidak ada
     */
    public int getSlotLength(int slotId) {
        checkSlot(slotId);
        return readU16(slotPosition(slotId) + 5);
    }

    /**
     * @param slotId nomor slot
     * @return salinan byte record di slot
     * @throws StorageException INVALID_RID jika slot tidak ada, MALFORMED_RECORD jika letak record di luar page
     */
    public byte[] readRecord(int slotId) {
        int offset = getSlotOffset(slotId);
        int length = getSlotLength(slotId);
        if (length > getSlotCapacity(slotId) || offset + length > data.length) {
            throw new StorageException(Code.MALFORMED_RECORD, "Letak record di slot " + slotId + " rusak");
        }
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    /** @return ruang kosong yang bersambung antara slot directory dan area record */
    public int getContiguousFreeSpace() {
        return getRecordAreaStart() - directoryEnd();
    }

    /** @return seluruh ruang kosong di page, termasuk celah yang bisa dipadatkan */
    public int getFreeSpace() {
        int used = 0;
        for (int i = 0; i < getSlotCount(); i++) {
            used += getSlotCapacity(i);
        }
        return data.length - directoryEnd() - used;
    }

    /**
     * Mengecek apakah record sepanjang {@code length} byte bisa ditulis ke slot, termasuk jika
     * slot directory perlu diperpanjang atau page perlu dipadatkan dulu.
     *
     * @param slotId nomor slot tujuan, boleh di luar slot directory
     * @param length panjang record dalam byte
     * @return true jika {@link #write(int, SlotStatus, byte[])} akan berhasil
     */
    public boolean canWrite(int slotId, int length) {
        if (slotId < 0 || length < 0) {
            return false;
        }
        int count = getSlotCount();
        if (slotId < count) {
            int capacity = getSlotCapacity(slotId);
            return length <= capacity || length <= getFreeSpace() + capacity;
        }
        long growth = (long) (slotId + 1 - count) * SLOT_SIZE;
        return growth + length <= getFreeSpace();
    }

    /**
     * Menulis isi dan status slot. Jika record muat di kapasitas slot, record ditulis di tempat.
     * Jika tidak, record mendapat area baru, dan page dipadatkan dulu bila ruang bersambungnya kurang.
     * Slot di antara akhir slot directory dan slotId dibuat sebagai slot EMPTY.
     *
     * @param slotId nomor slot tujuan
     * @param status status baru slot
     * @param bytes  isi record; array kosong untuk slot tanpa isi
     * @throws StorageException INVALID_REQUEST jika record tidak muat di page ini
     */
    public void write(int slotId, SlotStatus status, byte[] bytes) {
        Objects.requireNonNull(status, "status tidak boleh null");
        Objects.requireNonNull(bytes, "bytes tidak boleh null");
        if (!canWrite(slotId, bytes.length)) {
            throw new StorageException(Code.INVALID_REQUEST,
                    "Record sepanjang " + bytes.length + " byte tidak muat di slot " + slotId);
        }
        int count = getSlotCount();
        if (slotId < count && bytes.length <= getSlotCapacity(slotId)) {
            int offset = getSlotOffset(slotId);
            System.arraycopy(bytes, 0, data, offset, bytes.length);
            setSlot(slotId, status, offset, getSlotCapacity(slotId), bytes.length);
            return;
        }

        int growth = slotId < count ? 0 : (slotId + 1 - count) * SLOT_SIZE;
        if (getContiguousFreeSpace() < growth + bytes.length) {
            compact(slotId < count ? slotId : -1);
        }
        if (growth > 0) {
            for (int i = count; i <= slotId; i++) {
                setSlot(i, SlotStatus.EMPTY, 0, 0, 0);
            }
            writeU16(0, slotId + 1);
        }
        int offset = getRecordAreaStart() - bytes.length;
        System.arraycopy(bytes, 0, data, offset, bytes.length);
        writeU16(2, offset);
        setSlot(slotId, status, offset, bytes.length, bytes.length);
    }

    /**
     * Menggeser area semua slot ke akhir page supaya ruang kosongnya bersambung.
     * Area slot {@code excludeSlot} dilepas karena slot itu akan mendapat area baru.
     *
     * @param excludeSlot slot yang areanya dilepas, atau -1 jika tidak ada
     */
    private void compact(int excludeSlot) {
        byte[] original = data.clone();
        int end = data.length;
        for (int i = 0; i < getSlotCount(); i++) {
            int capacity = getSlotCapacity(i);
            if (i == excludeSlot) {
                setSlot(i, getSlotStatus(i), 0, 0, 0);
                continue;
            }
            if (capacity == 0) {
                continue;
            }
            end -= capacity;
            System.arraycopy(original, getSlotOffset(i), data, end, capacity);
            setSlot(i, getSlotStatus(i), end, capacity, getSlotLength(i));
        }
        writeU16(2, end);
    }

    private void setSlot(int slotId, SlotStatus status, int offset, int capacity, int length) {
        int position = slotPosition(slotId);
        data[position] = (byte) status.getCode();
        writeU16(position + 1, offset);
        writeU16(position + 3, capacity);
        writeU16(position + 5, length);
    }

    private void checkSlot(int slotId) {
        if (slotId < 0 || slotId >= getSlotCount()) {
            throw new StorageException(Code.INVALID_RID, "Slot " + slotId + " tidak ada di page ini");
        }
    }

    private int directoryEnd() {
        return HEADER_SIZE + getSlotCount() * SLOT_SIZE;
    }

    private static int slotPosition(int slotId) {
        return HEADER_SIZE + slotId * SLOT_SIZE;
    }

    private int readU16(int position) {
        return buffer.getShort(position) & 0xFFFF;
    }

    private void writeU16(int position, int value) {
        buffer.putShort(position, (short) value);
    }
}
