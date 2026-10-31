package monggodb.qp.driver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import monggodb.contracts.ccm.mock.MockConcurrencyControlManager;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.Column;
import monggodb.contracts.shared.ColumnRef;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.MdbmsException;
import monggodb.contracts.shared.QPException;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.Schema;
import monggodb.contracts.shared.StorageException;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.shared.Value;
import monggodb.contracts.sm.StorageManager;
import monggodb.contracts.sm.mock.MockStorageManager;
import monggodb.qp.execution.AccessDeniedException;
import monggodb.qp.execution.ExecutionContext;
import monggodb.qp.execution.ExecutionOperator;
import monggodb.qp.execution.ResultColumn;
import monggodb.qp.execution.ResultSchema;
import monggodb.qp.execution.Tuple;
import monggodb.qp.operator.ProjectOperator;
import monggodb.qp.operator.RowOperators;
import monggodb.qp.operator.ScanOperator;

/**
 * Driver operator baris Query Processor (QP-05). Menjalankan skenario Table Scan dan Project
 * dengan MockStorageManager, InMemoryStorageManager, dan MockConcurrencyControlManager,
 * lalu mencetak status lulus atau gagal setiap skenario.
 *
 * <pre>
 * RowOperatorDriver menjalankan semua skenario
 */
public final class RowOperatorDriver {

    private RowOperatorDriver() {
    }

    public static void main(String[] args) {
        Map<String, Scenario> scenarios = scenarios();
        List<String> names = new ArrayList<>();
        for (String name : scenarios.keySet()) {
            if (args.length == 0 || Arrays.stream(args).anyMatch(name::startsWith)) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            System.out.println("Skenario tidak ditemukan: " + String.join(" ", args));
            System.exit(1);
        }

        System.out.println("Driver operator baris Query Processor MonggoDB");
        List<Outcome> outcomes = new ArrayList<>();
        for (String name : names) {
            Scenario scenario = scenarios.get(name);
            Checker checker = new Checker();
            try {
                scenario.body().run(checker);
            } catch (Throwable t) {
                checker.failures.add("exception tak terduga: " + t);
            }
            outcomes.add(new Outcome(name, scenario.title(), checker.passed, checker.failures));
        }

        System.out.println();
        System.out.println("================ RINGKASAN ================");
        int failed = 0;
        for (Outcome o : outcomes) {
            System.out.printf("%-6s %-8s %3d cek lulus, %d gagal  %s%n",
                    o.ok() ? "LULUS" : "GAGAL", o.name(), o.passed(), o.failures().size(), o.title());
            for (String f : o.failures()) {
                System.out.println("         - " + f);
            }
            if (!o.ok()) {
                failed++;
            }
        }
        System.out.println("===========================================");
        System.out.println((outcomes.size() - failed) + "/" + outcomes.size() + " skenario lulus");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static Map<String, Scenario> scenarios() {
        Map<String, Scenario> s = new LinkedHashMap<>();
        s.put("QP05-01", new Scenario("SELECT * menampilkan semua kolom sesuai urutan tabel", RowOperatorDriver::selectAll));
        s.put("QP05-02", new Scenario("SELECT a, b mengikuti urutan penulisan", RowOperatorDriver::selectColumns));
        s.put("QP05-03", new Scenario("Kolom dengan prefix tabel.kolom", RowOperatorDriver::prefixedColumns));
        s.put("QP05-04", new Scenario("Alias tabel sebagai prefix kolom", RowOperatorDriver::tableAlias));
        s.put("QP05-05", new Scenario("Tabel kosong menampilkan header dengan 0 baris", RowOperatorDriver::emptyTable));
        s.put("QP05-06", new Scenario("Tabel besar tertampil lengkap", RowOperatorDriver::largeTable));
        s.put("QP05-07", new Scenario("Nilai NULL diteruskan apa adanya", RowOperatorDriver::nullValues));
        s.put("QP05-08", new Scenario("resolveColumnIndex: tidak ada, ambigu, kolom bersama NATURAL JOIN", RowOperatorDriver::resolveColumns));
        s.put("QP05-09", new Scenario("reset mengulang dari awal tanpa izin baru", RowOperatorDriver::resetWithoutNewAccess));
        s.put("QP05-10", new Scenario("close idempoten dan izin dilepas sebelum COMMIT", RowOperatorDriver::closeReleasesAccess));
        s.put("QP05-11", new Scenario("CCM ABORTED saat open: berhenti tanpa membaca SM", RowOperatorDriver::abortedOnOpen));
        s.put("QP05-12", new Scenario("CCM WAITING lalu ALLOWED", RowOperatorDriver::waitingThenAllowed));
        s.put("QP05-13", new Scenario("CCM WAITING sampai batas waktu", RowOperatorDriver::waitingTimeout));
        s.put("QP05-14", new Scenario("Error di tengah next: izin ditutup sekali dengan success = false", RowOperatorDriver::errorDuringNext));
        s.put("QP05-15", new Scenario("Tabel tidak ada: StorageException diteruskan dan izin ditutup", RowOperatorDriver::tableNotFound));
        s.put("QP05-16", new Scenario("Kolom Project tidak ada: izin child tetap ditutup", RowOperatorDriver::projectColumnNotFound));
        s.put("QP05-17", new Scenario("Skema tersedia setelah open dan tetap ada setelah close", RowOperatorDriver::schemaAvailability));
        s.put("QP05-18", new Scenario("Plan tidak valid ditolak dengan INVALID_PLAN", RowOperatorDriver::invalidPlan));
        return s;
    }

    private static void selectAll(Checker c) {
        Env env = new Env(new MockStorageManager());
        ScanOperator scan = RowOperators.createScanOperator("students", null);
        List<Tuple> rows = drain(scan, env.ctx);
        c.equal(List.of("students.nim", "students.name", "students.gpa"), scan.getSchema().getLabels(), "header");
        c.equal(List.of(DataType.INTEGER, DataType.VARCHAR, DataType.FLOAT), types(scan.getSchema()), "tipe kolom");
        c.equal(3, rows.size(), "jumlah baris");
        c.equal(List.of(13524001, "Andi", 3.5f), raw(rows.get(0)), "baris pertama");
        c.equal(List.of(13524003, "Citra", 3.8f), raw(rows.get(2)), "baris terakhir");
        c.equal(List.of(new RID(0, 0), new RID(0, 1), new RID(0, 2)), rids(rows), "RID asal ikut di Tuple");
        c.equal(List.of(new ResourceId("students")), env.ccm.getReadRequests(), "satu izin baca tabel");
        c.equal(List.of(true), env.ccm.getCompletions(), "izin ditutup dengan success = true");
    }

    private static void selectColumns(Checker c) {
        Env env = new Env(new MockStorageManager());
        ProjectOperator project = RowOperators.createProjectOperator(
                RowOperators.createScanOperator("students", null), List.of(col(null, "name"), col(null, "nim")));
        List<Tuple> rows = drain(project, env.ctx);
        c.equal(List.of("students.name", "students.nim"), project.getSchema().getLabels(), "header urut SELECT");
        c.equal(3, rows.size(), "jumlah baris");
        c.equal(List.of("Andi", 13524001), raw(rows.get(0)), "baris pertama");
        c.equal(new RID(0, 2), rows.get(2).getRid(), "RID asal dibawa Project");
        c.equal(1, env.ccm.getReadRequests().size(), "satu izin baca tabel");

        ProjectOperator bound = RowOperators.createProjectOperator(RowOperators.createScanOperator("students", null),
                List.of(bound("students", "gpa", DataType.FLOAT), bound("students", "nim", DataType.INTEGER)));
        List<Tuple> boundRows = drain(bound, env.ctx);
        c.equal(List.of("students.gpa", "students.nim"), bound.getSchema().getLabels(), "header kolom hasil binding");
        c.equal(List.of(3.2f, 13524002), raw(boundRows.get(1)), "baris kedua kolom hasil binding");

        ProjectOperator repeated = RowOperators.createProjectOperator(RowOperators.createScanOperator("students", null),
                List.of(col(null, "nim"), col(null, "nim")));
        List<Tuple> repeatedRows = drain(repeated, env.ctx);
        c.equal(List.of(13524001, 13524001), raw(repeatedRows.get(0)), "kolom yang sama boleh ditulis dua kali");
    }

    private static void prefixedColumns(Checker c) {
        Env env = new Env(new MockStorageManager());
        ProjectOperator project = RowOperators.createProjectOperator(RowOperators.createScanOperator("students", null),
                List.of(col("students", "gpa"), col("STUDENTS", "NIM")));
        List<Tuple> rows = drain(project, env.ctx);
        c.equal(List.of("students.gpa", "students.nim"), project.getSchema().getLabels(), "header dengan prefix");
        c.equal(List.of(3.5f, 13524001), raw(rows.get(0)), "baris pertama");

        ProjectOperator wrongPrefix = RowOperators.createProjectOperator(RowOperators.createScanOperator("students", null),
                List.of(col("mahasiswa", "nim")));
        MdbmsException e = c.expectThrows(QPException.class, () -> drain(wrongPrefix, env.ctx), "prefix tabel lain");
        c.equal("COLUMN_NOT_FOUND", code(e), "kode error prefix tabel lain");
    }

    private static void tableAlias(Checker c) {
        Env env = new Env(new MockStorageManager());
        ScanOperator scan = RowOperators.createScanOperator("students", "S");
        drain(scan, env.ctx);
        c.equal(List.of("s.nim", "s.name", "s.gpa"), scan.getSchema().getLabels(), "header memakai alias");
        c.equal(List.of(new ResourceId("students")), env.ccm.getReadRequests(), "izin baca tetap atas nama tabel");

        ProjectOperator project = RowOperators.createProjectOperator(RowOperators.createScanOperator("students", "s"),
                List.of(col("s", "nim"), col("students", "name")));
        List<Tuple> rows = drain(project, env.ctx);
        c.equal(List.of("s.nim", "s.name"), project.getSchema().getLabels(), "kolom dirujuk lewat alias dan nama tabel");
        c.equal(List.of(13524002, "Budi"), raw(rows.get(1)), "baris kedua");

        ProjectOperator unknown = RowOperators.createProjectOperator(RowOperators.createScanOperator("students", "s"),
                List.of(col("t", "nim")));
        MdbmsException e = c.expectThrows(QPException.class, () -> drain(unknown, env.ctx), "alias yang tidak ada");
        c.equal("COLUMN_NOT_FOUND", code(e), "kode error alias yang tidak ada");
    }

    private static void emptyTable(Checker c) {
        Env env = new Env(memoryStorage());
        ScanOperator scan = RowOperators.createScanOperator("empty_table", null);
        List<Tuple> rows = drain(scan, env.ctx);
        c.equal(0, rows.size(), "0 baris dari Table Scan");
        c.equal(List.of("empty_table.id", "empty_table.note"), scan.getSchema().getLabels(), "header Table Scan");

        ProjectOperator project = RowOperators.createProjectOperator(
                RowOperators.createScanOperator("empty_table", null), List.of(col(null, "note")));
        List<Tuple> projected = drain(project, env.ctx);
        c.equal(0, projected.size(), "0 baris dari Project");
        c.equal(List.of("empty_table.note"), project.getSchema().getLabels(), "header Project");
        c.equal(List.of(true, true), env.ccm.getCompletions(), "semua izin ditutup");
    }

    private static void largeTable(Checker c) {
        InMemoryStorageManager storage = memoryStorage();
        int total = 10_000;
        for (int i = 0; i < total; i++) {
            storage.addRow("big_table", Value.ofInteger(i), Value.ofVarchar("baris-" + i));
        }
        Env env = new Env(storage);
        List<Tuple> rows = drain(RowOperators.createScanOperator("big_table", null), env.ctx);
        c.equal(total, rows.size(), "jumlah baris");
        boolean ordered = true;
        Set<RID> distinct = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ordered &= rows.get(i).getValue(0).asInteger() == i;
            distinct.add(rows.get(i).getRid());
        }
        c.check(ordered, "semua baris terbaca sesuai urutan penyimpanan");
        c.equal(total, distinct.size(), "setiap baris membawa RID berbeda");
    }

    private static void nullValues(Checker c) {
        InMemoryStorageManager storage = memoryStorage();
        storage.addRow("courses", Value.ofChar("IF9999"), Value.ofNull(DataType.VARCHAR), Value.ofNull(DataType.INTEGER));
        Env env = new Env(storage);
        List<Tuple> rows = drain(RowOperators.createScanOperator("courses", null), env.ctx);
        Tuple last = rows.get(rows.size() - 1);
        c.check(last.getValue(1).isNull() && last.getValue(1).dataType() == DataType.VARCHAR, "NULL VARCHAR terbaca dengan tipenya");
        c.check(last.getValue(2).isNull() && last.getValue(2).dataType() == DataType.INTEGER, "NULL INTEGER terbaca dengan tipenya");

        List<Tuple> projected = drain(RowOperators.createProjectOperator(
                RowOperators.createScanOperator("courses", null), List.of(col(null, "credits"), col(null, "code"))), env.ctx);
        c.equal(Arrays.asList(null, "IF9999"), raw(projected.get(projected.size() - 1)), "NULL diteruskan Project");
    }

    private static void resolveColumns(Checker c) {
        ResultSchema joined = new ResultSchema(List.of(
                new ResultColumn("id", DataType.INTEGER, List.of("a", "b")),
                new ResultColumn("name", DataType.VARCHAR, List.of("a")),
                new ResultColumn("name", DataType.VARCHAR, List.of("b"))));
        c.equal(0, RowOperators.resolveColumnIndex(joined, col("a", "id")), "kolom bersama lewat prefix a");
        c.equal(0, RowOperators.resolveColumnIndex(joined, col("b", "id")), "kolom bersama lewat prefix b");
        c.equal(0, RowOperators.resolveColumnIndex(joined, col(null, "id")), "kolom bersama tanpa prefix");
        c.equal(1, RowOperators.resolveColumnIndex(joined, col("A", "NAME")), "prefix membedakan kolom bernama sama");
        c.equal(2, RowOperators.resolveColumnIndex(joined, col("b", "name")), "kolom name milik b");

        MdbmsException ambiguous = c.expectThrows(QPException.class,
                () -> RowOperators.resolveColumnIndex(joined, col(null, "name")), "kolom tanpa prefix yang ambigu");
        c.equal("AMBIGUOUS_COLUMN", code(ambiguous), "kode error kolom ambigu");
        MdbmsException missing = c.expectThrows(QPException.class,
                () -> RowOperators.resolveColumnIndex(joined, col(null, "age")), "kolom yang tidak ada");
        c.equal("COLUMN_NOT_FOUND", code(missing), "kode error kolom yang tidak ada");
        MdbmsException wrongPrefix = c.expectThrows(QPException.class,
                () -> RowOperators.resolveColumnIndex(joined, col("c", "id")), "prefix yang tidak ada");
        c.equal("COLUMN_NOT_FOUND", code(wrongPrefix), "kode error prefix yang tidak ada");

        ResultSchema projected = RowOperators.buildProjectedSchema(joined, List.of(col("b", "id"), col("b", "name")));
        c.equal(List.of("a.id", "b.name"), projected.getLabels(), "qualifier dibawa dari skema asal");
        c.equal(List.of("a", "b"), projected.getColumn(0).getQualifiers(), "semua qualifier kolom bersama tetap dibawa");
    }

    private static void resetWithoutNewAccess(Checker c) {
        Env env = new Env(new MockStorageManager());
        ScanOperator scan = RowOperators.createScanOperator("students", null);
        scan.open(env.ctx);
        try {
            scan.next();
            scan.next();
            scan.reset();
            scan.reset();
            c.equal(3, readAll(scan).size(), "Table Scan membaca ulang semua baris setelah reset");
        } finally {
            scan.close();
        }

        ProjectOperator project = RowOperators.createProjectOperator(
                RowOperators.createScanOperator("students", null), List.of(col(null, "name")));
        project.open(env.ctx);
        try {
            readAll(project);
            project.reset();
            List<Tuple> again = readAll(project);
            c.equal(3, again.size(), "Project membaca ulang semua baris setelah reset");
            c.equal(List.of("Andi"), raw(again.get(0)), "baris pertama setelah reset");
        } finally {
            project.close();
        }
        c.equal(2, env.ccm.getReadRequests().size(), "reset tidak meminta izin baca baru");
        c.equal(List.of(true, true), env.ccm.getCompletions(), "setiap izin ditutup sekali");
    }

    private static void closeReleasesAccess(Checker c) {
        Env env = new Env(new MockStorageManager());
        ScanOperator scan = RowOperators.createScanOperator("students", null);
        scan.open(env.ctx);
        c.expectThrows(IllegalStateException.class, () -> scan.open(env.ctx), "open saat masih terbuka");
        scan.next();
        MdbmsException commitWhileOpen = c.expectThrows(CCMException.class,
                () -> env.mock.commitTransaction(env.txId), "COMMIT ditolak selama izin baca masih terbuka");
        c.equal("INVALID_STATE", code(commitWhileOpen), "kode error COMMIT saat akses berlangsung");
        scan.close();
        scan.close();
        c.equal(List.of(true), env.ccm.getCompletions(), "close kedua tidak menutup izin lagi");
        c.expectThrows(IllegalStateException.class, scan::next, "next setelah close");
        c.check(env.mock.commitTransaction(env.txId).success(), "COMMIT berhasil setelah close");
    }

    private static void abortedOnOpen(Checker c) {
        InMemoryStorageManager storage = memoryStorage();
        Env env = new Env(storage);
        env.mock.setNextAccessDecision(AccessDecision.ABORTED, AbortReason.DEADLOCK_VICTIM, List.of());
        ScanOperator scan = RowOperators.createScanOperator("courses", null);
        AccessDeniedException e = c.expectThrows(AccessDeniedException.class, () -> scan.open(env.ctx), "open dengan jawaban ABORTED");
        c.equal(AccessDeniedException.Code.ABORTED, e == null ? null : e.getErrorCode(), "kode ABORTED");
        c.equal(AbortReason.DEADLOCK_VICTIM, e == null ? null : e.getAbortReason(), "alasan pembatalan diteruskan");
        c.equal(0, storage.getSchemaCount(), "getTableSchema tidak dipanggil");
        c.equal(0, storage.getScanCount(), "scanTable tidak dipanggil");
        scan.close();
        c.equal(List.of(), env.ccm.getCompletions(), "tidak ada izin yang perlu ditutup");
        c.equal(TransactionStatus.FAILED, env.mock.getTransactionStatus(env.txId), "operator tidak membatalkan transaksi sendiri");
    }

    private static void waitingThenAllowed(Checker c) throws InterruptedException {
        InMemoryStorageManager storage = memoryStorage();
        Env env = new Env(storage);
        env.mock.setNextAccessDecision(AccessDecision.WAITING);
        AtomicReference<Throwable> resolverError = new AtomicReference<>();
        Thread resolver = new Thread(() -> {
            try {
                String requestId;
                long deadline = System.currentTimeMillis() + 5_000;
                while ((requestId = env.ccm.getLastReadRequestId()) == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                Thread.sleep(100);
                env.mock.resolveAccess(env.txId, requestId, AccessDecision.ALLOWED, null);
            } catch (Throwable t) {
                resolverError.set(t);
            }
        });
        resolver.start();
        List<Tuple> rows = drain(RowOperators.createScanOperator("courses", null), env.ctx);
        resolver.join();
        c.check(resolverError.get() == null, "izin WAITING diselesaikan menjadi ALLOWED" + errorSuffix(resolverError.get()));
        c.equal(2, rows.size(), "semua baris terbaca setelah izin diberikan");
        c.equal(1, env.ccm.getReadRequests().size(), "menunggu dengan requestId yang sama, tanpa requestRead baru");
        c.equal(List.of(true), env.ccm.getCompletions(), "izin ditutup dengan success = true");
    }

    private static void waitingTimeout(Checker c) {
        InMemoryStorageManager storage = memoryStorage();
        Env env = new Env(storage, 200);
        env.mock.setNextAccessDecision(AccessDecision.WAITING);
        ScanOperator scan = RowOperators.createScanOperator("courses", null);
        AccessDeniedException e = c.expectThrows(AccessDeniedException.class, () -> scan.open(env.ctx), "open dengan izin WAITING terus");
        c.equal(AccessDeniedException.Code.WAIT_TIMEOUT, e == null ? null : e.getErrorCode(), "kode WAIT_TIMEOUT");
        c.equal(0, storage.getScanCount(), "scanTable tidak dipanggil");
        c.equal(List.of(), env.ccm.getCompletions(), "tidak ada izin yang perlu ditutup");
    }

    private static void errorDuringNext(Checker c) {
        InMemoryStorageManager storage = memoryStorage();
        storage.failScanAt("courses", 1);
        Env env = new Env(storage);
        ScanOperator scan = RowOperators.createScanOperator("courses", null);
        scan.open(env.ctx);
        try {
            c.check(scan.next() != null, "baris pertama terbaca");
            MdbmsException e = c.expectThrows(StorageException.class, scan::next, "baris kedua gagal dibaca");
            c.equal("IO_ERROR", code(e), "error SM diteruskan apa adanya");
        } finally {
            scan.close();
        }
        scan.close();
        c.equal(List.of(false), env.ccm.getCompletions(), "izin ditutup sekali dengan success = false");
    }

    private static void tableNotFound(Checker c) {
        Env env = new Env(new MockStorageManager());
        ScanOperator scan = RowOperators.createScanOperator("dosen", null);
        MdbmsException e = c.expectThrows(StorageException.class, () -> scan.open(env.ctx), "open tabel yang tidak ada");
        c.equal("TABLE_NOT_FOUND", code(e), "kode error dari SM");
        scan.close();
        c.equal(List.of(false), env.ccm.getCompletions(), "izin yang sempat diberikan ditutup dengan success = false");
    }

    private static void projectColumnNotFound(Checker c) {
        Env env = new Env(new MockStorageManager());
        ProjectOperator project = RowOperators.createProjectOperator(
                RowOperators.createScanOperator("students", null), List.of(col(null, "umur")));
        MdbmsException e = c.expectThrows(QPException.class, () -> project.open(env.ctx), "open dengan kolom yang tidak ada");
        c.equal("COLUMN_NOT_FOUND", code(e), "kode error kolom tidak ada");
        project.close();
        c.equal(List.of(true), env.ccm.getCompletions(), "izin baca child ditutup");
        c.check(env.mock.commitTransaction(env.txId).success(), "COMMIT berhasil karena tidak ada izin tertinggal");
    }

    private static void schemaAvailability(Checker c) {
        Env env = new Env(memoryStorage());
        ScanOperator scan = RowOperators.createScanOperator("empty_table", null);
        ProjectOperator project = RowOperators.createProjectOperator(scan, List.of(col(null, "id")));
        c.expectThrows(IllegalStateException.class, scan::getSchema, "skema Table Scan sebelum open");
        c.expectThrows(IllegalStateException.class, project::getSchema, "skema Project sebelum open");
        project.open(env.ctx);
        try {
            c.equal(List.of("empty_table.id"), project.getSchema().getLabels(), "skema Project setelah open");
            c.check(project.next() == null, "tabel kosong langsung habis");
        } finally {
            project.close();
        }
        c.equal(List.of("empty_table.id", "empty_table.note"), scan.getSchema().getLabels(), "skema Table Scan setelah close");
        c.equal(List.of("empty_table.id"), project.getSchema().getLabels(), "skema Project setelah close");
    }

    private static void invalidPlan(Checker c) {
        MdbmsException noTable = c.expectThrows(QPException.class,
                () -> RowOperators.createScanOperator(" ", null), "Scan tanpa nama tabel");
        c.equal("INVALID_PLAN", code(noTable), "kode error Scan tanpa nama tabel");
        MdbmsException noChild = c.expectThrows(QPException.class,
                () -> RowOperators.createProjectOperator(null, List.of(col(null, "nim"))), "Project tanpa child");
        c.equal("INVALID_PLAN", code(noChild), "kode error Project tanpa child");
        MdbmsException noColumns = c.expectThrows(QPException.class,
                () -> RowOperators.createProjectOperator(RowOperators.createScanOperator("students", null), List.of()),
                "Project tanpa kolom");
        c.equal("INVALID_PLAN", code(noColumns), "kode error Project tanpa kolom");
        ResultSchema schema = new ResultSchema(List.of(new ResultColumn("nim", DataType.INTEGER, List.of("students"))));
        MdbmsException noName = c.expectThrows(QPException.class,
                () -> RowOperators.resolveColumnIndex(schema, col("students", null)), "referensi kolom tanpa nama");
        c.equal("INVALID_PLAN", code(noName), "kode error referensi kolom tanpa nama");
    }

    private static InMemoryStorageManager memoryStorage() {
        InMemoryStorageManager storage = new InMemoryStorageManager();
        storage.createTable(new Schema("courses", List.of(
                new Column("code", "courses", DataType.CHAR, 6, true, true, null),
                new Column("title", "courses", DataType.VARCHAR, 50, false, false, null),
                new Column("credits", "courses", DataType.INTEGER, 0, false, false, null))));
        storage.createTable(new Schema("empty_table", List.of(
                new Column("id", "empty_table", DataType.INTEGER, 0, true, true, null),
                new Column("note", "empty_table", DataType.VARCHAR, 20, false, false, null))));
        storage.createTable(new Schema("big_table", List.of(
                new Column("id", "big_table", DataType.INTEGER, 0, true, true, null),
                new Column("label", "big_table", DataType.VARCHAR, 20, false, false, null))));
        storage.addRow("courses", Value.ofChar("IF3140"), Value.ofVarchar("Sistem Basis Data"), Value.ofInteger(3));
        storage.addRow("courses", Value.ofChar("IF3130"), Value.ofVarchar("Jaringan Komputer"), Value.ofInteger(3));
        return storage;
    }

    private static List<Tuple> drain(ExecutionOperator op, ExecutionContext ctx) {
        op.open(ctx);
        try {
            return readAll(op);
        } finally {
            op.close();
        }
    }

    private static List<Tuple> readAll(ExecutionOperator op) {
        List<Tuple> rows = new ArrayList<>();
        Tuple tuple;
        while ((tuple = op.next()) != null) {
            rows.add(tuple);
        }
        return rows;
    }

    private static ColumnRef col(String table, String column) {
        return new ColumnRef(table, column);
    }

    private static ColumnRef bound(String table, String column, DataType dataType) {
        ColumnRef ref = new ColumnRef(table, column);
        ref.bind(table, false, dataType);
        return ref;
    }

    private static List<Object> raw(Tuple tuple) {
        List<Object> values = new ArrayList<>();
        for (Value value : tuple.getValues()) {
            values.add(value.rawValue());
        }
        return values;
    }

    private static List<RID> rids(List<Tuple> rows) {
        List<RID> rids = new ArrayList<>();
        for (Tuple row : rows) {
            rids.add(row.getRid());
        }
        return rids;
    }

    private static List<DataType> types(ResultSchema schema) {
        List<DataType> types = new ArrayList<>();
        for (ResultColumn column : schema.getColumns()) {
            types.add(column.getDataType());
        }
        return types;
    }

    private static String code(Throwable t) {
        return t instanceof MdbmsException ? ((MdbmsException) t).getCode() : null;
    }

    private static String errorSuffix(Throwable t) {
        return t == null ? "" : " (" + t + ")";
    }

    /** Lingkungan satu skenario: mock CCM yang sudah siap, satu transaksi aktif, dan konteks eksekusi. */
    private static final class Env {
        final MockConcurrencyControlManager mock = new MockConcurrencyControlManager();
        final RecordingConcurrencyControlManager ccm = new RecordingConcurrencyControlManager(mock);
        final TxId txId;
        final ExecutionContext ctx;

        Env(StorageManager storage) {
            this(storage, ExecutionContext.DEFAULT_ACCESS_TIMEOUT_MS);
        }

        Env(StorageManager storage, long accessTimeoutMs) {
            mock.initialize();
            txId = mock.beginTransaction();
            ctx = new ExecutionContext(txId, storage, ccm, accessTimeoutMs);
        }
    }

    /** Langkah skenario yang boleh melempar exception apa pun. */
    private interface ScenarioBody {
        void run(Checker checker) throws Exception;
    }

    /** Aksi yang diharapkan melempar exception. */
    private interface Action {
        void run() throws Exception;
    }

    private record Scenario(String title, ScenarioBody body) {
    }

    private record Outcome(String name, String title, int passed, List<String> failures) {
        boolean ok() {
            return failures.isEmpty();
        }
    }

    /** Pencatat hasil pemeriksaan satu skenario. */
    private static final class Checker {
        private int passed;
        private final List<String> failures = new ArrayList<>();

        void check(boolean condition, String description) {
            if (condition) {
                passed++;
            } else {
                failures.add(description);
            }
        }

        void equal(Object expected, Object actual, String description) {
            if (Objects.equals(expected, actual)) {
                passed++;
            } else {
                failures.add(description + ": diharapkan " + expected + ", didapat " + actual);
            }
        }

        <E extends Throwable> E expectThrows(Class<E> type, Action action, String description) {
            try {
                action.run();
            } catch (Throwable t) {
                if (type.isInstance(t)) {
                    passed++;
                    return type.cast(t);
                }
                failures.add(description + ": diharapkan " + type.getSimpleName() + ", didapat " + t);
                return null;
            }
            failures.add(description + ": diharapkan " + type.getSimpleName() + ", tidak ada exception");
            return null;
        }
    }
}
