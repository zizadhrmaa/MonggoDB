package monggodb.ccm.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import monggodb.ccm.serializability.ScheduleOp;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;

/**
 * Pembaca berkas skenario yang menghasilkan satu Scenario per protokol
 *
 * <pre>
 * ## judul skenario
 * protokol lock timestamp        protokol yang dijalankan (default keduanya)
 * awal A=1 B=2                   nilai awal item
 * akhir A=5                      data akhir yang diharapkan (opsional)
 * batas 10000                    batas waktu skenario dalam ms
 * R1(A) W2(A=5) C1 A2            schedule: Read, Write, Commit, Abort; angka = label transaksi
 * jeda(2500)                     driver menunggu sebelum melepas langkah sesudahnya
 * harap T2 ABORTED DEADLOCK_VICTIM [@lock|@timestamp]
 * harap-langkah 2 WAITING [@lock|@timestamp]   keputusan pertama langkah ke-2
 * serializable ya|tidak
 * mode manual                    schedule hanya diverifikasi, tidak dijalankan ke CCM
 * cek begin-bersamaan 20         beginTransaction bersamaan menghasilkan TxId unik
 * cek ganti-protokol             setProtocol ditolak saat ada transaksi aktif
 * </pre>
 */
final class ScenarioFile {

    private static final Pattern ACCESS = Pattern.compile("([RW])(\\d+)\\((\\w+)(?:=(-?\\d+))?\\)");
    private static final Pattern END = Pattern.compile("([CA])(\\d+)");
    private static final Pattern PAUSE = Pattern.compile("jeda\\((\\d+)\\)");

    /** Langkah mentah sebelum dipecah per transaksi */
    private record Step(int step, String label, ScenarioOp.Kind kind, String item, Integer value, long pauseMs) { }

    private record Expect(String label, TransactionStatus status, AbortReason reason, ProtocolType only) { }

    private record StepExpect(int step, AccessDecision decision, ProtocolType only) { }

    private final String name;
    private String title = "";
    private final List<ProtocolType> protocols = new ArrayList<>();
    private final Map<String, Integer> initial = new LinkedHashMap<>();
    private final Map<String, Integer> finalData = new LinkedHashMap<>();
    private final List<Step> steps = new ArrayList<>();
    private final List<Expect> expects = new ArrayList<>();
    private final List<StepExpect> stepExpects = new ArrayList<>();
    private final List<String> checks = new ArrayList<>();
    private boolean serializable = true;
    private boolean manual;
    private long timeoutMs = 10000;
    private long pendingPause;

    ScenarioFile(String ref) throws IOException {
        String file = ref.replace('\\', '/');
        file = file.substring(file.lastIndexOf('/') + 1);
        name = file.endsWith(".txt") ? file.substring(0, file.length() - 4) : file;
        parse(read(ref));
        if (protocols.isEmpty()) {
            protocols.add(ProtocolType.LOCK_BASED);
            protocols.add(ProtocolType.TIMESTAMP_BASED);
        }
    }

    String name() {
        return name;
    }

    String title() {
        return title;
    }

    List<ProtocolType> protocols() {
        return List.copyOf(protocols);
    }

    List<String> checks() {
        return List.copyOf(checks);
    }

    boolean manual() {
        return manual;
    }

    boolean serializable() {
        return serializable;
    }

    /** Skenario per protokol, kosong untuk berkas cek atau mode manual */
    List<Scenario> scenarios() {
        if (manual || steps.isEmpty()) {
            return List.of();
        }
        List<Scenario> result = new ArrayList<>();
        for (ProtocolType protocol : protocols) {
            Map<String, List<ScenarioOp>> byLabel = new TreeMap<>((a, b) ->
                    Integer.compare(Integer.parseInt(a.substring(1)), Integer.parseInt(b.substring(1))));
            for (Step s : steps) {
                AccessDecision expected = stepExpects.stream()
                        .filter(e -> e.step() == s.step() && (e.only() == null || e.only() == protocol))
                        .map(StepExpect::decision).reduce((a, b) -> b).orElse(null);
                byLabel.computeIfAbsent(s.label(), k -> new ArrayList<>())
                        .add(new ScenarioOp(s.kind(), s.item(), s.value(), s.step(), expected, s.pauseMs()));
            }
            Map<String, TransactionStatus> status = new LinkedHashMap<>();
            Map<String, AbortReason> reasons = new LinkedHashMap<>();
            for (Expect e : expects) {
                if (e.only() == null || e.only() == protocol) {
                    status.put(e.label(), e.status());
                    if (e.reason() != null) {
                        reasons.put(e.label(), e.reason());
                    }
                }
            }
            List<ScenarioTx> txs = byLabel.entrySet().stream().map(en -> new ScenarioTx(en.getKey(), en.getValue())).toList();
            result.add(new Scenario(name, protocol, txs, initial,
                    new ScenarioExpected(status, reasons, serializable, finalData), timeoutMs));
        }
        return result;
    }

    /** Schedule mode manual dengan TxId dari nomor label */
    List<ScheduleOp> manualSchedule() {
        List<ScheduleOp> schedule = new ArrayList<>();
        for (Step s : steps) {
            schedule.add(new ScheduleOp(s.step(), new TxId(Long.parseLong(s.label().substring(1))),
                    ScheduleOp.Kind.valueOf(s.kind().name()), s.item() == null ? null : new ResourceId(s.item()), s.value()));
        }
        return schedule;
    }

    private static List<String> read(String ref) throws IOException {
        if (ref.startsWith("classpath:")) {
            try (InputStream in = ScenarioFile.class.getResourceAsStream(ref.substring("classpath:".length()))) {
                if (in == null) {
                    throw new IOException("skenario tidak ditemukan: " + ref);
                }
                return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
            }
        }
        return Files.readAllLines(Path.of(ref), StandardCharsets.UTF_8);
    }

    private void parse(List<String> lines) {
        for (int no = 1; no <= lines.size(); no++) {
            String line = lines.get(no - 1).strip();
            if (line.startsWith("## ")) {
                title = line.substring(3).strip();
                continue;
            }
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            try {
                parseLine(line);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(name + " baris " + no + ": " + e.getMessage(), e);
            }
        }
        if (steps.isEmpty() && checks.isEmpty()) {
            throw new IllegalArgumentException(name + ": skenario tidak berisi schedule maupun cek");
        }
    }

    private void parseLine(String line) {
        List<String> words = new ArrayList<>(List.of(line.split("\\s+")));
        ProtocolType only = null;
        String last = words.get(words.size() - 1);
        if (last.startsWith("@")) {
            only = protocol(last.substring(1));
            words.remove(words.size() - 1);
        }
        switch (words.get(0)) {
            case "protokol" -> words.subList(1, words.size()).forEach(w -> protocols.add(protocol(w)));
            case "awal" -> putAll(initial, words.subList(1, words.size()));
            case "akhir" -> putAll(finalData, words.subList(1, words.size()));
            case "batas" -> timeoutMs = Long.parseLong(words.get(1));
            case "harap" -> expects.add(new Expect(words.get(1), TransactionStatus.valueOf(words.get(2)),
                    words.size() > 3 ? AbortReason.valueOf(words.get(3)) : null, only));
            case "harap-langkah" -> stepExpects.add(new StepExpect(Integer.parseInt(words.get(1)),
                    AccessDecision.valueOf(words.get(2)), only));
            case "serializable" -> serializable = words.get(1).equals("ya");
            case "mode" -> manual = words.get(1).equals("manual");
            case "cek" -> checks.add(String.join(" ", words.subList(1, words.size())));
            default -> words.forEach(this::parseStep);
        }
    }

    private static void putAll(Map<String, Integer> target, List<String> pairs) {
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            target.put(kv[0], Integer.parseInt(kv[1]));
        }
    }

    private void parseStep(String token) {
        int step = steps.size() + 1;
        Matcher m = PAUSE.matcher(token);
        if (m.matches()) {
            pendingPause += Long.parseLong(m.group(1));
            return;
        }
        long pause = pendingPause;
        pendingPause = 0;
        m = ACCESS.matcher(token);
        if (m.matches()) {
            boolean write = m.group(1).equals("W");
            if (write == (m.group(4) == null)) {
                throw new IllegalArgumentException(write ? "WRITE wajib membawa nilai: " + token
                        : "READ tidak membawa nilai: " + token);
            }
            steps.add(new Step(step, "T" + m.group(2), write ? ScenarioOp.Kind.WRITE : ScenarioOp.Kind.READ,
                    m.group(3), write ? Integer.valueOf(m.group(4)) : null, pause));
            return;
        }
        m = END.matcher(token);
        if (!m.matches()) {
            throw new IllegalArgumentException("langkah tidak dikenal: " + token);
        }
        steps.add(new Step(step, "T" + m.group(2),
                m.group(1).equals("C") ? ScenarioOp.Kind.COMMIT : ScenarioOp.Kind.ABORT, null, null, pause));
    }

    private static ProtocolType protocol(String word) {
        return switch (word) {
            case "lock" -> ProtocolType.LOCK_BASED;
            case "timestamp" -> ProtocolType.TIMESTAMP_BASED;
            default -> throw new IllegalArgumentException("protokol tidak dikenal: " + word);
        };
    }

    /** Teks satu langkah, misalnya W2(A=5) */
    static String text(String label, ScenarioOp op) {
        String n = label.substring(1);
        return switch (op.kind()) {
            case READ -> "R" + n + "(" + op.item() + ")";
            case WRITE -> "W" + n + "(" + op.item() + "=" + op.value() + ")";
            case COMMIT -> "C" + n;
            case ABORT -> "A" + n;
        };
    }
}
