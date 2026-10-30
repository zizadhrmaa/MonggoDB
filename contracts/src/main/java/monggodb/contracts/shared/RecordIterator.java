package monggodb.contracts.shared;

/**
 * Hasil scanTable. Record dibaca per page, tidak dimuat sekaligus ke memori.
 * Setiap Row yang dikembalikan sudah punya RID. Slot kosong, slot yang masih dipesan,
 * dan slot forwarding dilewati.
 * Iterator memegang page di buffer, jadi wajib di-close (pakai try-with-resources).
 */
public interface RecordIterator extends AutoCloseable {

    /** @return true jika masih ada record berikutnya */
    boolean hasNext();

    /**
     * @return record berikutnya
     * @throws java.util.NoSuchElementException jika record sudah habis
     */
    Row next();

    /**
     * Mulai lagi dari record pertama. Dipakai QP4 untuk membaca ulang inner relation di BNLJ.
     */
    void reset();

    /**
     * Melepas page yang masih dipegang. Boleh dipanggil lebih dari sekali.
     */
    @Override
    void close();
}
