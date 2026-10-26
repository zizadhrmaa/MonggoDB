# MonggoDB

mini Database Management System (mDBMS) untuk Tugas Besar IF3140 Sistem Basis Data 2026, dikerjakan oleh SUPERGROUP MonggoDB.

mDBMS terdiri dari lima komponen, masing-masing dikerjakan oleh satu group:

| Modul | Komponen | Peran singkat |
|---|---|---|
| `qp` | Query Processor | CLI, server multi-client, eksekusi query plan, transaksi |
| `qo` | Query Optimizer | Parsing, validasi terhadap katalog, optimasi plan, estimasi biaya |
| `sm` | Storage Manager | Penyimpanan di disk, buffer pool, index, katalog dan statistik |
| `ccm` | Concurrency Control Manager | Protokol lock dan timestamp, deadlock |
| `frm` | Failure Recovery Manager | Write-Ahead Log, rollback, checkpoint, recovery |

## Prasyarat

- JDK 21. Cek dengan `java -version`.
- Maven tidak perlu diinstal. Gunakan Maven wrapper (`mvnw` / `mvnw.cmd`) yang sudah ada di repo.

## Build

```bash
# Mac / Linux / Git Bash
./mvnw compile

# Windows (cmd atau PowerShell)
mvnw.cmd compile
```

Perintah lain yang sering dipakai:

```bash
./mvnw test                     # menjalankan unit test semua modul
./mvnw install -DskipTests      # build dan pasang semua modul ke repo Maven lokal
./mvnw -pl sm -am compile       # compile satu modul beserta dependensinya (di sini: sm dan contracts)
./mvnw javadoc:aggregate        # generate Javadoc gabungan ke target/reports/apidocs/
```

Kalau `./mvnw compile` gagal setelah clone, kabari ketua supergroup.

## Menjalankan mDBMS

> Kelas `main` belum ada di Milestone 1. Nama kelas di bawah adalah usulan dan diperbarui setelah dibuat.

```bash
./mvnw install -DskipTests

# Terminal 1: server (startup: SM dimuat, FRM recovery, CCM initialize, lalu server menerima koneksi)
./mvnw -pl app exec:java -Dexec.mainClass=monggodb.app.ServerMain

# Terminal 2 dan 3: client CLI (minimal dua client untuk menguji transaksi konkuren)
./mvnw -pl app exec:java -Dexec.mainClass=monggodb.app.ClientMain
```

Konfigurasi ada di [config/mdbms.properties](config/mdbms.properties): port server, folder data, ukuran buffer pool, protokol CCM, dan interval checkpoint.

## Menjalankan driver komponen

Setiap komponen punya driver di `<modul>/src/main/java/monggodb/<modul>/driver/`. Driver punya `main` sendiri, bisa jalan tanpa program utama mDBMS, dan memakai mock dari `contracts` untuk komponen lain.

```bash
./mvnw install -DskipTests
./mvnw -pl <modul> exec:java -Dexec.mainClass=monggodb.<modul>.driver.<NamaDriver>
```

| Driver | Requirement | PIC | Kelas main |
|---|---|---|---|
| Query Processor | QP-14 | QP1 | _belum ada_ |
| Query Optimizer | QO-16 | QO1 | _belum ada_ |
| Storage Manager | SM-15 | SM1 | _belum ada_ |
| Concurrency Control Manager | CC-10 | CC5 | _belum ada_ |
| Failure Recovery Manager | FR-09 | FR1 | _belum ada_ |

## Struktur repo

```
config/           konfigurasi runtime, hanya dibaca oleh app
contracts/        kode umum SUPERGROUP
  .../shared/       shared data type (Row, RID, Schema, node plan, exception, ...)
  .../<komponen>/   interface publik komponen (hanya signature dan Javadoc)
  .../<komponen>/mock/  mock interface, mengembalikan nilai dummy
qp/ qo/ sm/ ccm/ frm/   implementasi komponen, hanya bergantung pada contracts
  .../driver/       program driver komponen
sm/src/main/resources/sample/  seed database contoh (SM-14)
app/              wiring semua komponen, urutan startup, main server dan client, uji integrasi
data/             file runtime (.tbl, .idx, catalog.json, log). Tidak di-commit
docs/diagrams/    sumber diagram (PlantUML / draw.io)
docs/reports/     laporan milestone dan riwayat perubahan kontrak
```

## Keputusan arsitektur

1. **Dependensi modul.** Modul komponen hanya bergantung pada `contracts`. Hanya `app` yang bergantung pada semua komponen. Mock diletakkan di `contracts` supaya tidak ada dependensi siklus antarmodul.
2. **Konfigurasi.** Hanya `app` yang membaca `config/mdbms.properties`. Nilainya diteruskan ke komponen sebagai parameter, misalnya `CCM.initialize(protocol, lastTxId)` dan `SM.startup(dataDir, bufferSize)`. Driver komponen tidak bergantung pada file konfigurasi.
3. **Dependensi dua arah antarobjek.** SM memanggil FRM (log persisten sebelum halaman ditulis), FRM memanggil SM (undo/redo); CCM memanggil FRM (log, rollback), FRM memanggil CCM (menahan transaksi saat checkpoint). Ini diselesaikan dengan setter injection atau interface sempit (misalnya `LogFlusher`) yang dipasang `app` setelah semua objek dibuat, bukan lewat constructor.
4. **Driver FRM.** Uji persistensi dan crash recovery FRM (FR-09) memakai SM tiruan berbasis berkas yang dibuat FRM sendiri di modul `frm`, karena `frm` tidak boleh bergantung pada modul `sm`.

## Aturan kerja

- Jangan push langsung ke `main`. Semua perubahan lewat pull request.
- Nama branch: `<tipe>/<ID-requirement>-<deskripsi-singkat>`, contoh `feat/QP-01-client-cli`. Tanpa ID requirement: `contracts/sm-interface`, `mock/frm`, `chore/javadoc-tags`.
- Folder `qp/`, `qo/`, `sm/`, `ccm/`, `frm/` hanya diubah oleh group pemiliknya.
- Interface di `contracts/<komponen>/` diubah oleh group penyedia setelah disetujui group pemakai. Mock boleh diubah group penyedia kapan saja.
- Shared type di `contracts/shared/` diubah oleh pemilik tipe setelah disetujui semua group yang memakainya.
- Setelah kontrak dibekukan, setiap perubahan `contracts/` dicatat di [docs/reports/riwayat-kontrak.md](docs/reports/riwayat-kontrak.md).
- Penamaan: interface tanpa awalan `I` (`StorageManager`), mock berawalan `Mock` (`MockStorageManager`), implementasi berakhiran `Impl` (`StorageManagerImpl`).
- Hanya library standar Java. Dilarang memakai library yang mengimplementasikan fungsi inti DBMS.
- Generative AI tidak boleh dipakai untuk menulis kode implementasi.

## Format Javadoc

Setiap interface dan kelas diberi deskripsi 1 sampai 2 kalimat. Setiap metode di interface wajib memuat `@param`, `@return`, `@calls`, dan `@calledBy`. Setiap field shared type diberi deskripsi singkat. Tag `@calls` dan `@calledBy` sudah didaftarkan di `pom.xml`.

```java
/** Pintu masuk Storage Manager untuk operasi data. */
public interface StorageManager {
    /**
     * Mengambil satu record berdasarkan RID.
     * @param table nama tabel
     * @param rid alamat record
     * @return record yang ditemukan
     * @calls tidak ada
     * @calledBy QP3 (IndexScan), FR3 (undo)
     */
    Row fetchRecord(String table, RID rid);
}
```
