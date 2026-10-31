package monggodb.frm.driver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.shared.CheckpointResult;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.FRMException;
import monggodb.contracts.shared.LSN;
import monggodb.contracts.shared.LogRecord;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.RecoveryResult;
import monggodb.contracts.shared.RecoveryTarget;
import monggodb.contracts.shared.RollbackResult;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.shared.Value;
import monggodb.contracts.sm.StorageManager;
import monggodb.frm.FailureRecoveryManagerImpl;
import monggodb.frm.LogRecords;

/**
 * runScenario: menjalankan satu berkas skenario uji FRM dan membandingkan hasil aktual dengan harapan.
 * Driver berperan sebagai CCM/QP: mencatat perubahan ke FRM sebelum menerapkannya ke SM (WAL).
 * Skenario dipecah oleh perintah {@code crash}; setiap bagian sebelum crash dijalankan di proses
 * terpisah yang dihentikan paksa, lalu bagian berikutnya dimulai di proses baru dengan startup + recover.
 * Format berkas dijelaskan di frm/README.md.
 */
final class ScenarioRunner {

    /** Ringkasan hasil satu skenario. */
    record Outcome(String name, String title, int passed, List<String> failures) {
        boolean ok() {
            return failures.isEmpty();
        }
    }

    private record Line(int no, List<String> tokens, String text) {
        String cmd() {
            return tokens.get(0).toLowerCase();
        }
    }

    private final String ref;
    private final String name;
    private String title = "";
    private final List<Line> header = new ArrayList<>();
    private final List<List<Line>> segments = new ArrayList<>();
    private final List<String> crashDamage = new ArrayList<>();

    // keadaan satu segmen
    private StorageManager sm;
    private FileStorageManager fileSm;
    private FailureRecoveryManagerImpl frm;
    private String lastResult = "";
    private Path workDir;
    private final List<String> passes = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    ScenarioRunner(String ref) throws IOException {
        this.ref = ref;
        String base = ref.substring(ref.lastIndexOf('/') + 1).replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        this.name = base.endsWith(".txt") ? base.substring(0, base.length() - 4) : base;
        parse(readText(ref));
    }

    String name() {
        return name;
    }

    // ------------------------------------------------------------------ parsing

    private static List<String> readText(String ref) throws IOException {
        if (ref.startsWith("classpath:")) {
            String res = ref.substring("classpath:".length());
            try (InputStream in = ScenarioRunner.class.getResourceAsStream(res)) {
                if (in == null) throw new IOException("Resource skenario tidak ditemukan: " + res);
                return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
            }
        }
        return Files.readAllLines(Path.of(ref), StandardCharsets.UTF_8);
    }

    private void parse(List<String> text) {
        List<Line> current = new ArrayList<>();
        boolean started = false;
        for (int i = 0; i < text.size(); i++) {
            String raw = text.get(i).strip();
            if (raw.startsWith("##")) {
                if (title.isEmpty()) title = raw.substring(2).strip();
                continue;
            }
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            Line line = new Line(i + 1, tokenize(raw), raw);
            String cmd = line.cmd();
            if (!started && (cmd.equals("table") || cmd.equals("seed"))) {
                header.add(line);
                continue;
            }
            started = true;
            if (cmd.equals("crash")) {
                segments.add(current);
                crashDamage.add(line.tokens().size() > 1 ? line.tokens().get(1).toLowerCase() : "none");
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        segments.add(current);
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#') break;
            int start = i;
            boolean quoted = false;
            while (i < s.length() && (quoted || !Character.isWhitespace(s.charAt(i)))) {
                if (s.charAt(i) == '\'') quoted = !quoted;
                i++;
            }
            out.add(s.substring(start, i));
        }
        return out;
    }

    // ------------------------------------------------------------------ orkestrasi (proses induk)

    Outcome run(Path dir) throws Exception {
        this.workDir = dir;
        deleteRecursively(dir);
        Files.createDirectories(dir);
        System.out.println();
        System.out.println("=== " + name + (title.isEmpty() ? "" : ": " + title));
        int passed = 0;
        List<String> allFailures = new ArrayList<>();
        for (int seg = 0; seg < segments.size(); seg++) {
            boolean last = seg == segments.size() - 1;
            if (last) {
                runSegment(seg);
                passed += passes.size();
                allFailures.addAll(failures);
            } else {
                System.out.println("  -- bagian " + (seg + 1) + " dijalankan di proses terpisah, diakhiri crash --");
                int code = spawnChild(seg);
                Path result = dir.resolve("segment-" + seg + ".result");
                if (!Files.exists(result)) {
                    allFailures.add("bagian " + (seg + 1) + ": proses anak berhenti tanpa hasil (exit " + code + ")");
                    continue;
                }
                for (String l : Files.readAllLines(result, StandardCharsets.UTF_8)) {
                    if (l.startsWith("PASS")) passed++;
                    else if (l.startsWith("FAIL")) allFailures.add(l.substring(5));
                }
                damageLog(crashDamage.get(seg));
                System.out.println("  -- CRASH: proses dihentikan paksa tanpa shutdown"
                        + (crashDamage.get(seg).equals("none") ? "" : ", log dirusak (" + crashDamage.get(seg) + ")")
                        + "; sistem dijalankan ulang --");
            }
        }
        return new Outcome(name, title, passed, allFailures);
    }

    private int spawnChild(int seg) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = locationOf(ScenarioRunner.class) + File.pathSeparator + locationOf(FailureRecoveryManager.class);
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", cp, FrmDriver.class.getName(),
                "--segment", ref, workDir.toAbsolutePath().toString(), Integer.toString(seg));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) System.out.println(l);
        }
        return p.waitFor();
    }

    private static String locationOf(Class<?> c) {
        try {
            return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Tidak dapat menentukan classpath untuk " + c.getName(), e);
        }
    }

    /** Merusak file log setelah crash: "tail" menambah entri terpotong, "middle" merusak byte di tengah. */
    private void damageLog(String kind) throws IOException {
        if (kind.equals("none")) return;
        Path log = workDir.resolve("frm").resolve(FailureRecoveryManagerImpl.LOG_FILE);
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "rw")) {
            long len = f.length();
            if (kind.equals("tail")) {
                f.seek(len);
                f.writeInt(200);
                f.writeInt(12345);
                f.write(new byte[]{1, 2, 3, 4, 5, 6, 7});
            } else if (kind.equals("middle")) {
                long pos = len / 2;
                f.seek(pos);
                int b = f.read();
                f.seek(pos);
                f.write(b ^ 0xFF);
            } else {
                throw new IllegalArgumentException("Jenis kerusakan log tidak dikenal: " + kind);
            }
        }
    }

    // ------------------------------------------------------------------ eksekusi satu segmen

    /** Dipanggil di proses anak: jalankan segmen, simpan hasil, lalu hentikan paksa (crash). */
    void runSegmentAndCrash(Path dir, int seg) throws IOException {
        this.workDir = dir;
        try {
            runSegment(seg);
        } catch (Throwable t) {
            failures.add("bagian " + (seg + 1) + " gagal dijalankan: " + t);
        }
        List<String> out = new ArrayList<>();
        passes.forEach(p -> out.add("PASS " + p));
        failures.forEach(f -> out.add("FAIL " + f));
        Files.write(dir.resolve("segment-" + seg + ".result"), out, StandardCharsets.UTF_8);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private void runSegment(int seg) throws IOException {
        passes.clear();
        failures.clear();
        boot(seg == 0);
        try {
            for (Line line : segments.get(seg)) {
                execute(line);
            }
        } finally {
            if (seg == segments.size() - 1) {
                frm.shutdown();
            }
        }
    }

    private void boot(boolean first) {
        fileSm = new FileStorageManager(workDir.resolve("sm"));
        sm = fileSm;
        if (first) applyHeader();
        frm = new FailureRecoveryManagerImpl(workDir.resolve("frm"), 300);
        frm.setStorageManager(sm);
        fileSm.setLogFlusher(frm::flushLogUntil);
        frm.startup();
        RecoveryResult rr = frm.recover();
        lastResult = describe(rr);
        System.out.println("  [boot] startup + recover -> " + lastResult);
    }

    /** Membuat tabel dan data awal (seperti basis data contoh SM) dengan LSN.NONE sebelum FRM.startup. */
    private void applyHeader() {
        for (Line l : header) {
            String table = l.tokens().get(1);
            if (l.cmd().equals("table")) {
                fileSm.createTable(table);
            } else {
                RID rid = rid(l.tokens().get(2));
                sm.insertRecordAt(table, rid, row(rid, l.tokens(), 3), LSN.NONE);
            }
        }
        sm.flushAll();
    }

    private void execute(Line line) {
        List<String> t = line.tokens();
        String cmd = line.cmd();
        if (cmd.startsWith("expect")) {
            check(line);
            return;
        }
        String result;
        try {
            result = switch (cmd) {
                case "begin" -> "OK " + frm.logBegin(tx(t.get(1)));
                case "insert" -> {
                    RID rid = rid(t.get(3));
                    Row after = row(rid, t, 4);
                    LSN lsn = frm.logChange(LogRecords.change(tx(t.get(1)), LogType.INSERT, t.get(2), rid, null, after));
                    sm.insertRecordAt(t.get(2), rid, after, lsn);
                    yield "OK " + lsn;
                }
                case "update" -> {
                    RID rid = rid(t.get(3));
                    Row before = sm.fetchRecord(t.get(2), rid).orElseThrow(() -> new IllegalStateException("record " + rid + " tidak ada"));
                    Row after = row(rid, t, 4);
                    LSN lsn = frm.logChange(LogRecords.change(tx(t.get(1)), LogType.UPDATE, t.get(2), rid, before, after));
                    sm.updateRecord(t.get(2), rid, after, lsn);
                    yield "OK " + lsn;
                }
                case "delete" -> {
                    RID rid = rid(t.get(3));
                    Row before = sm.fetchRecord(t.get(2), rid).orElseThrow(() -> new IllegalStateException("record " + rid + " tidak ada"));
                    LSN lsn = frm.logChange(LogRecords.change(tx(t.get(1)), LogType.DELETE, t.get(2), rid, before, null));
                    sm.deleteRecord(t.get(2), rid, lsn);
                    yield "OK " + lsn;
                }
                case "commit" -> {
                    try {
                        yield "OK " + frm.logCommit(tx(t.get(1)));
                    } catch (FRMException e) {
                        yield "FAILED " + e.getCode() + ": " + e.getMessage();
                    }
                }
                case "abort" -> {
                    RollbackResult r = frm.rollback(tx(t.get(1)));
                    yield r.status() + ": " + r.message();
                }
                case "flush" -> {
                    sm.flushAll();
                    yield "OK";
                }
                case "flush-table" -> {
                    fileSm.flushTable(t.get(1));
                    yield "OK";
                }
                case "checkpoint" -> {
                    CheckpointResult r = frm.checkpoint();
                    yield (r.success() ? "OK " : "FAILED ") + r.message();
                }
                case "mark" -> {
                    sleep(30);
                    long now = System.currentTimeMillis();
                    saveMark(t.get(1), now);
                    sleep(30);
                    yield "OK " + now;
                }
                case "sleep" -> {
                    sleep(Long.parseLong(t.get(1)));
                    yield "OK";
                }
                case "restore" -> {
                    RecoveryTarget target = t.get(1).equalsIgnoreCase("tx")
                            ? new RecoveryTarget(RecoveryTarget.Kind.TX_ID, 0L, tx(t.get(2)))
                            : new RecoveryTarget(RecoveryTarget.Kind.TIME, loadMark(t.get(2)), null);
                    yield describe(frm.restoreTo(target));
                }
                case "drop-table" -> {
                    fileSm.dropTable(t.get(1), false);
                    yield "OK";
                }
                case "periodic-checkpoint" -> {
                    long before = countCheckpoints();
                    frm.startPeriodicCheckpoint(Long.parseLong(t.get(1)));
                    sleep(Long.parseLong(t.get(2)));
                    frm.stopPeriodicCheckpoint();
                    long made = countCheckpoints() - before;
                    yield (made > 0 ? "OK " : "FAILED ") + made + " checkpoint berkala dibuat";
                }
                case "fail-log-write" -> {
                    frm.simulateNextLogWriteFailure();
                    yield "OK";
                }
                case "log" -> {
                    List<LogRecord> recs = t.size() > 1 ? frm.readLog(tx(t.get(1))) : frm.readLog(null);
                    if (recs.isEmpty()) System.out.println("        (log kosong)");
                    recs.forEach(r -> System.out.println("        " + r));
                    yield "OK " + recs.size() + " entri";
                }
                case "echo" -> line.text().substring(4).strip();
                default -> throw new IllegalArgumentException("perintah tidak dikenal: " + cmd);
            };
        } catch (RuntimeException e) {
            result = "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        lastResult = result;
        System.out.printf("  [%3d] %-44s -> %s%n", line.no(), line.text(), result);
    }

    private void check(Line line) {
        List<String> t = line.tokens();
        String expected;
        String actual;
        try {
            switch (line.cmd()) {
                case "expect" -> {
                    RID rid = rid(t.get(2));
                    actual = sm.fetchRecord(t.get(1), rid).map(r -> r.getValues().toString()).orElse("absent");
                    expected = t.get(3).equalsIgnoreCase("absent") ? "absent" : row(rid, t, 3).getValues().toString();
                }
                case "expect-rows" -> {
                    actual = Integer.toString(fileSm.count(t.get(1)));
                    expected = t.get(2);
                }
                case "expect-result" -> {
                    expected = line.text().substring(line.text().indexOf(' ') + 1).strip();
                    actual = lastResult;
                    if (actual.contains(expected)) actual = expected;
                }
                case "expect-log" -> {
                    TxId tx = tx(t.get(1));
                    actual = frm.readLog(tx).stream().map(r -> r.type().name()).toList().toString();
                    expected = t.subList(2, t.size()).stream().map(String::toUpperCase).toList().toString();
                }
                case "expect-log-count" -> {
                    actual = Integer.toString(frm.readLog(null).size());
                    expected = t.get(1);
                }
                case "expect-log-valid" -> {
                    expected = "LSN unik, naik, prevLSN konsisten";
                    actual = validateLog(frm.readLog(null));
                }
                default -> throw new IllegalArgumentException("perintah expect tidak dikenal: " + line.cmd());
            }
        } catch (RuntimeException e) {
            expected = line.text();
            actual = "ERROR " + e.getMessage();
        }
        boolean ok = expected.equals(actual);
        String msg = "baris " + line.no() + " (" + line.text() + ")";
        if (ok) {
            passes.add(msg);
            System.out.printf("  [%3d] %-44s    PASS%n", line.no(), line.text());
        } else {
            failures.add(msg + ": diharapkan " + expected + ", aktual " + actual);
            System.out.printf("  [%3d] %-44s    FAIL%n          diharapkan: %s%n          aktual    : %s%n",
                    line.no(), line.text(), expected, actual);
        }
    }

    private static String validateLog(List<LogRecord> recs) {
        java.util.Map<TxId, LSN> last = new java.util.HashMap<>();
        long prev = 0;
        for (LogRecord r : recs) {
            if (r.lsn().value() <= prev) return "LSN tidak naik di " + r.lsn();
            prev = r.lsn().value();
            if (r.txId() != null) {
                LSN expectedPrev = last.getOrDefault(r.txId(), LSN.NONE);
                if (!expectedPrev.equals(r.prevLSN())) return "prevLSN salah di " + r.lsn() + ": " + r.prevLSN() + " != " + expectedPrev;
                last.put(r.txId(), r.lsn());
            }
        }
        return "LSN unik, naik, prevLSN konsisten";
    }

    // ------------------------------------------------------------------ utilitas

    private static String describe(RecoveryResult r) {
        if (!r.success()) return "FAILED " + r.message();
        return "OK redone=" + r.redoneTx() + " undone=" + r.undoneTx() + " maxTx=" + r.maxTxId();
    }

    private static TxId tx(String s) {
        String digits = s.toUpperCase().startsWith("T") ? s.substring(1) : s;
        return new TxId(Long.parseLong(digits));
    }

    private static RID rid(String s) {
        String[] ps = s.split(":");
        return new RID(Integer.parseInt(ps[0]), Integer.parseInt(ps[1]));
    }

    private static Row row(RID rid, List<String> t, int from) {
        List<Value> values = new ArrayList<>();
        for (int i = from; i < t.size(); i++) values.add(value(t.get(i)));
        return new Row(rid, values);
    }

    /** 10 = INTEGER, 1.5 = FLOAT, 'abc' = VARCHAR, c'abc' = CHAR, NULL:INTEGER = NULL bertipe. */
    private static Value value(String s) {
        if (s.toUpperCase().startsWith("NULL:")) return Value.ofNull(DataType.valueOf(s.substring(5).toUpperCase()));
        if (s.startsWith("c'") && s.endsWith("'")) return Value.ofChar(s.substring(2, s.length() - 1));
        if (s.startsWith("'") && s.endsWith("'")) return Value.ofVarchar(s.substring(1, s.length() - 1));
        if (s.contains(".")) return Value.ofFloat(Float.parseFloat(s));
        return Value.ofInteger(Integer.parseInt(s));
    }

    private void saveMark(String mark, long time) {
        Path f = workDir.resolve("marks.properties");
        Properties p = new Properties();
        try {
            if (Files.exists(f)) {
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                }
            }
            p.setProperty(mark, Long.toString(time));
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, null);
            }
        } catch (IOException e) {
            throw new IllegalStateException("gagal menyimpan mark " + mark, e);
        }
    }

    private long countCheckpoints() {
        return frm.readLog(null).stream().filter(r -> r.type() == LogType.CHECKPOINT_END).count();
    }

    /** Nama mark yang dibuat perintah {@code mark}, atau angka epoch ms langsung. */
    private long loadMark(String mark) {
        if (mark.chars().allMatch(Character::isDigit)) return Long.parseLong(mark);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(workDir.resolve("marks.properties"))) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("mark " + mark + " belum dibuat", e);
        }
        String v = p.getProperty(mark);
        if (v == null) throw new IllegalStateException("mark " + mark + " belum dibuat");
        return Long.parseLong(v);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
