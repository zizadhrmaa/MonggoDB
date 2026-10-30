package monggodb.contracts.shared;

/**
 * Hasil FRM.checkpoint.
 *
 * @param success true jika CHECKPOINT_END sudah tersimpan permanen
 * @param checkpointLSN LSN entri CHECKPOINT_END; {@link LSN#NONE} jika gagal
 * @param message penjelasan yang siap ditampilkan
 */
public record CheckpointResult(boolean success, LSN checkpointLSN, String message) {
}
