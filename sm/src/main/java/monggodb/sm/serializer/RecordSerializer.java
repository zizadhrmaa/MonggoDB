package monggodb.sm.serializer;

import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.Value;

public interface RecordSerializer {

    /**
     * Mengubah Row menjadi representasi biner sesuai Schema, setelah setiap nilai divalidasi.
     *
     * @param row    record yang akan diserialisasi; RID diabaikan
     * @param schema skema tabel pemilik record
     * @return byte record
     * @throws StorageException SCHEMA_MISMATCH jika jumlah nilai tidak sama dengan jumlah kolom,
     *                          atau kode dari {@link #validate(Value, Column)}
     * @calls validate, getSerializedSize, encodeValue
     * @calledBy SM1 (insertRecord, insertRecordAt, updateRecord, sisip ulang saat recovery)
     */
    byte[] serializeRecord(Row row, Schema schema);

    /**
     * Mengubah byte record menjadi Row. Nilai CHAR dikembalikan tanpa padding byte 0.
     *
     * @param recordBytes byte record hasil {@link #serializeRecord(Row, Schema)}
     * @param schema      skema tabel pemilik record
     * @param rid         alamat record yang dipasang ke Row hasil; boleh null
     * @return Row dengan nilai sesuai urutan kolom Schema
     * @throws StorageException MALFORMED_RECORD jika byte tidak sesuai Schema
     * @calls decodeValue
     * @calledBy SM1 (fetchRecord, scanTable / TableRecordIterator)
     */
    Row deserializeRecord(byte[] recordBytes, Schema schema, RID rid);

    /**
     * Meng-encode satu nilai non-NULL berdasarkan definisi kolom. Value NULL menghasilkan array kosong
     * karena NULL hanya ditandai di null bitmap.
     *
     * @param value  nilai yang akan di-encode
     * @param column definisi kolom tujuan
     * @return byte nilai
     * @throws StorageException kode dari {@link #validate(Value, Column)}
     * @calls validate, getSerializedSize
     * @calledBy serializeRecord, SM5 (serializeNode)
     */
    byte[] encodeValue(Value value, Column column);

    /**
     * Men-decode satu nilai non-NULL dari byte mulai offset tertentu.
     *
     * @param bytes  array sumber
     * @param column definisi kolom nilai
     * @param offset posisi awal nilai di {@code bytes}
     * @return nilai dan jumlah byte yang dibaca
     * @throws StorageException MALFORMED_RECORD jika byte tidak cukup atau tidak valid
     * @calls tidak ada
     * @calledBy deserializeRecord, SM5 (deserializeNode)
     */
    DecodedValue decodeValue(byte[] bytes, Column column, int offset);

    /**
     * Menghitung ukuran byte satu nilai setelah di-encode. Value NULL berukuran 0.
     *
     * @param value  nilai yang diukur
     * @param column definisi kolom nilai
     * @return ukuran dalam byte
     * @calls tidak ada
     * @calledBy serializeRecord, encodeValue, SM5 (serializeNode)
     */
    int getSerializedSize(Value value, Column column);

    /**
     * Menghitung ukuran byte satu record setelah diserialisasi, termasuk null bitmap.
     *
     * @param row    record yang diukur
     * @param schema skema tabel pemilik record
     * @return ukuran dalam byte
     * @throws StorageException SCHEMA_MISMATCH jika jumlah nilai tidak sama dengan jumlah kolom
     * @calls getSerializedSize(Value, Column)
     * @calledBy SM1 (findPageWithSpace), SM4 (recomputeStats)
     */
    int getSerializedSize(Row row, Schema schema);

    /**
     * Memastikan tipe nilai dan panjang teks sesuai definisi kolom sebelum diserialisasi.
     * Nilai INTEGER diterima untuk kolom FLOAT, dan CHAR/VARCHAR saling diterima.
     *
     * @param value  nilai yang diperiksa
     * @param column definisi kolom tujuan
     * @throws StorageException NULL_NOT_ALLOWED, TYPE_MISMATCH, VALUE_TOO_LONG, UNSUPPORTED_DATA_TYPE (panjang kolom teks tidak valid), atau INVALID_REQUEST jika {@code value} null
     * @calls tidak ada
     * @calledBy serializeRecord, encodeValue, SM1 (validateRow)
     */
    void validate(Value value, Column column);
}
