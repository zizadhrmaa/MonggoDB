package monggodb.sm.serializer;

import monggodb.contracts.shared.Value;

/**
 * Hasil decoding satu nilai dari byte record.
 * Membawa nilai yang terbaca dan jumlah byte yang dipakai, supaya pemanggil bisa melanjutkan ke offset berikutnya.
 *
 * @param value     nilai hasil decoding, tidak pernah NULL SQL
 * @param bytesRead jumlah byte yang dibaca dari array sumber
 */
public record DecodedValue(Value value, int bytesRead) {
}
