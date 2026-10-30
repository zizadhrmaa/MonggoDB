# Failure Recovery Manager (FRM)

Implementasi `monggodb.contracts.frm.FailureRecoveryManager` ada di `monggodb.frm.FailureRecoveryManagerImpl`, yang berperan sebagai facade.

| Kelas | PIC | Isi |
|---|---|---|
| `FailureRecoveryManagerImpl` | FR1 | facade: meneruskan setiap method antarmuka ke manajer yang tepat; setStorageManager untuk wiring oleh app |
| `LogManager` | FR2 | logBegin/logChange/logCommit, nextLSN, appendToBuffer, forceToDisk, readLogFrom (maju/mundur), deteksi entri terpotong/rusak saat dibuka |
| `LogSerializer` | FR2 | serialize/deserialize LogRecord dengan frame `[panjang][CRC32][payload]` |
| `RollbackManager` | FR3 | rollback, undoRecord |
| `CheckpointManager` | FR4 | checkpoint, writeCheckpoint, findLastCheckpoint, createInitialSnapshot, restoreTo, periodicCheckpointLoop, maintenanceLock |
| `RecoveryManager` | FR5 | recover, analyze, redoRecord |
| `TransactionTable` | – | status transaksi (aktif, committed, aborted) |
| `FrmContext` | – | objek bersama: log, metadata, tabel transaksi, referensi SM dan CCM |
| `FrmMetadata` | FR4 | snapshot awal (nama, LSN, waktu) dan rentang LSN yang dibuang oleh restoreTo |
| `driver.FrmDriver` | driver komponen (FR-08, FR-09) |
| `driver.ScenarioRunner` | runScenario: pembaca dan pelaksana berkas skenario |
| `driver.FileStorageManager` | SM tiruan berbasis berkas untuk uji persistensi antarproses |

## Rancangan

**Log.** Satu berkas `<logDir>/wal.log`. Setiap entri disimpan sebagai frame `[panjang payload: int][CRC32: int][payload]`. Before/after kosong ("record tidak ada") ditulis dengan penanda tersendiri, berbeda dari Row yang berisi Value NULL; setiap Value disimpan beserta DataType dan penanda isNull. LSN dibuat di bawah satu kunci sehingga unik dan naik walau banyak thread mencatat bersamaan. Timestamp entri tidak pernah turun.

**WAL.** `logChange` hanya menaruh entri di buffer. Entri baru persisten saat `flushLogUntil(pageLSN)` dipanggil SM sebelum menulis halaman, saat `logCommit` (memaksa log ke disk sebelum kembali), atau saat checkpoint/rollback. Jika COMMIT gagal disimpan, entri COMMIT dibuang dari buffer dan `FRMException(LOG_WRITE_FAILED)` dilempar, sehingga transaksi tidak pernah dianggap committed.

**Rollback.** RollbackManager membaca log mundur (readLogFrom) dan mengambil perubahan transaksi dari yang terbaru sampai BEGIN-nya. Setiap undo dicatat sebagai catatan kompensasi bertipe INSERT/UPDATE/DELETE biasa (undo INSERT = DELETE, undo UPDATE = UPDATE dengan before/after ditukar, undo DELETE = INSERT di RID yang sama), lalu ABORT dicatat dan dipaksa ke disk. ABORT berarti pembatalan sudah selesai. Sesuai kontrak, LSN yang dikirim ke SM saat undo adalah LSN catatan yang di-undo. Jika halaman ditulis sebelum catatan kompensasi persisten lalu crash, transaksi belum punya ABORT persisten sehingga recovery me-redo lalu meng-undo-nya kembali; hasilnya tetap benar.

**Crash recovery** (`recover`):
1. Cari CHECKPOINT_END terakhir yang didahului CHECKPOINT_START. Jika tidak ada, mulai dari awal log.
2. Analisis status transaksi sejak titik itu: COMMIT, ABORT, atau belum selesai.
3. Redo seluruh catatan data sejak checkpoint (repeating history), termasuk kompensasi dan perubahan transaksi yang belum selesai. Redo idempoten: INSERT memakai `insertRecordAt`, UPDATE memakai `updateRecord`, atau `insertRecordAt` jika record tidak ada, dan DELETE memakai `deleteRecord`.
4. Undo transaksi yang belum selesai dalam urutan LSN terbalik dengan catatan kompensasi, lalu catat ABORT.
5. Log dipaksa ke disk, `SM.flushAll`, lalu checkpoint dibuat agar restart berikutnya tidak mengulang dari awal.

`maxTxId` diambil dari seluruh log, bukan hanya sejak checkpoint. Entri terakhir yang terpotong dibuang saat log dibuka. Kerusakan di tengah log membuat `recover` mengembalikan `success = false` dan setiap penulisan log berikutnya ditolak dengan `LOG_CORRUPTED`.

**Checkpoint.** Langkahnya: `maintenanceLock` → `CCM.blockNewTransactions` → `waitUntilNoActive(timeout)` → CHECKPOINT_START (dipaksa ke disk) → `SM.flushAll` → CHECKPOINT_END (dipaksa ke disk) → `CCM.resumeTransactions`. Admission selalu dibuka kembali, juga saat timeout atau gagal. Checkpoint tidak menghapus log. Interval checkpoint berkala diberikan app dari `config/mdbms.properties` (`frm.checkpointIntervalSec`, default 60 detik). Timeout menunggu transaksi aktif default 30 detik (`DEFAULT_ADMISSION_TIMEOUT_MS`) dan dapat diubah lewat konstruktor.

**Pemulihan historis** (`restoreTo`). Snapshot awal `frm-initial` dibuat sekali lewat `SM.createSnapshot` saat `startup` pertama, setelah file log dibuka. Restore berjalan dengan urutan berikut:
1. Validasi target. Target yang di luar rentang ditolak dengan `RESTORE_OUT_OF_RANGE` sebelum data diubah.
2. `SM.loadSnapshot`.
3. Redo catatan data transaksi yang COMMIT-nya ≤ batas LSN, menurut urutan LSN.
4. `flushAll`.
5. Catat rentang LSN setelah batas sebagai "dibuang" di `frm.meta`.
6. Buat checkpoint, lalu buka kembali admission.

Untuk target TX_ID, batasnya adalah LSN COMMIT transaksi tersebut. Untuk target TIME, batasnya adalah COMMIT terakhir dengan timestamp ≤ target. Transaksi yang dimulai sebelum target tetapi commit setelahnya tidak disertakan. Riwayat yang dibuang tidak dapat lagi dijadikan target.

## Batasan yang dicatat di laporan

- Admission CCM (blockNewTransactions, waitUntilNoActive, resumeTransactions) belum dipasang karena interface CCM belum ada di main. Sampai saat itu checkpoint dan restoreTo tidak menahan transaksi baru, jadi pemanggil harus memastikan tidak ada transaksi aktif.
- `loadSnapshot` mengembalikan katalog sesuai snapshot. Tabel yang dibuat setelah snapshot hilang, dan catatan log untuk tabel yang tidak ada dilewati.
- Jika crash terjadi di tengah `restoreTo` (setelah `loadSnapshot`, sebelum checkpoint baru), hasilnya tidak dijamin konsisten.

## Menjalankan driver

```bash
./mvnw install -DskipTests                              # sekali, dan setiap kode berubah
./mvnw -pl frm exec:java                                # semua skenario bawaan
./mvnw -pl frm exec:java -Dexec.args="04 07"            # skenario bawaan berdasarkan awalan nama
./mvnw -pl frm exec:java -Dexec.args="path/ke/skenario.txt"   # skenario sendiri (path dari root repo)
```

Di PowerShell pakai `.\mvnw.cmd` dan kutip seluruh argumen: `"-Dexec.args=04 07"`.

Tanpa argumen, driver menjalankan semua skenario di `src/main/resources/scenarios/` (lihat `index.txt`). Setiap skenario dijalankan dari keadaan awal yang diketahui: folder kerja `target/frm-driver/<skenario>/` (relatif terhadap root repo) dikosongkan dulu. Driver menampilkan setiap perintah beserta hasilnya, harapan vs aktual untuk setiap cek, dan ringkasan LULUS/GAGAL.

Driver berperan sebagai CCM/QP: setiap perubahan dicatat dulu lewat `logChange`, baru diterapkan ke SM. SM memakai `FileStorageManager`, SM tiruan berbasis berkas di folder driver.

### Simulasi crash

Perintah `crash` membagi skenario menjadi beberapa bagian. Bagian sebelum `crash` dijalankan di **proses JVM terpisah** yang diakhiri `Runtime.halt` tanpa graceful shutdown, sehingga isi buffer log dan buffer SM yang belum ditulis hilang. Bagian berikutnya dimulai di proses baru dengan `SM` dimuat dari disk, lalu `FRM.startup` dan `FRM.recover`. Hasil recover menjadi hasil terakhir yang dapat dicek dengan `expect-result`. `crash tail` menambah entri terpotong di akhir log, sedangkan `crash middle` merusak satu byte di tengah log.

### Format skenario

```
## Judul skenario                        (baris pertama ##)
# komentar
table <tabel>                            header: buat tabel sebelum startup
seed <tabel> <page>:<slot> <nilai...>    header: data awal dengan LSN.NONE (seperti basis data contoh)

begin T1 | commit T1 | abort T1
insert T1 <tabel> <page>:<slot> <nilai...>
update T1 <tabel> <page>:<slot> <nilai...>
delete T1 <tabel> <page>:<slot>
flush | flush-table <tabel>              SM menulis halaman ke disk (termasuk data belum commit)
checkpoint | periodic-checkpoint <intervalMs> <lamaMs>
mark <nama> | restore tx T1 | restore time <nama|epochMs>
fail-log-write                           penulisan log berikutnya gagal
drop-table <tabel> | log [T1] | sleep <ms> | echo <teks>
crash [tail|middle]

expect <tabel> <page>:<slot> <nilai...> | expect <tabel> <page>:<slot> absent
expect-rows <tabel> <n>
expect-result <teks>                     hasil perintah terakhir memuat teks ini
expect-log T1 <TYPE...>                  urutan jenis entri log transaksi
expect-log-count <n> | expect-log-valid  LSN unik, naik, prevLSN konsisten
```

Nilai: `10` (INTEGER), `1.5` (FLOAT), `'teks'` (VARCHAR), `c'teks'` (CHAR), `NULL:INTEGER` (NULL bertipe).

| Skenario | Requirement |
|---|---|
| 01-log-wal | FR-01, FR-02, FR-03: isi log, NULL vs "record tidak ada", log terbaca setelah restart |
| 02-rollback | FR-04: undo terbalik, record diubah berkali-kali, ALREADY_ABORTED / TX_COMMITTED / TX_UNKNOWN |
| 03-commit-gagal | FR-01, FR-02: COMMIT gagal disimpan tidak dinyatakan committed |
| 04-crash-redo-undo | FR-07, FR-08: committed/aborted/aktif, redo data belum di disk, undo data uncommitted di disk, restart ulang tanpa record ganda |
| 05-crash-checkpoint | FR-06, FR-07: recovery dari checkpoint terakhir |
| 06-checkpoint-admission | FR-06: menunggu transaksi aktif, admission dibuka kembali, checkpoint berkala |
| 07-restore-historis | FR-05: target txId dan waktu, di luar rentang ditolak, hasil bertahan setelah restart |
| 08-log-terpotong-rusak | FR-07: entri terakhir terpotong vs kerusakan di tengah log |
| 09-drop-table | catatan untuk tabel yang sudah di-DROP dilewati |
