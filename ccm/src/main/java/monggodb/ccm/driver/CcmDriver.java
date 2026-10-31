package monggodb.ccm.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import monggodb.ccm.serializability.ScheduleOp;
import monggodb.ccm.serializability.ScheduleVerifier;
import monggodb.ccm.serializability.ScheduleVerifierImpl;
import monggodb.ccm.serializability.VerificationResult;
import monggodb.contracts.ccm.ConcurrencyControlManager;
import monggodb.contracts.ccm.mock.MockConcurrencyControlManager;
import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.frm.mock.MockFailureRecoveryManager;
import monggodb.contracts.shared.CCMConfig;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ProtocolType;
import monggodb.contracts.shared.TxId;

public final class CcmDriver {

    private static final String SCENARIOS = "/ccm-scenarios/";

    /** Satu baris ringkasan berstatus PASS, FAIL, atau LEWAT */
    private record Summary(String name, String protocol, String title, String status) { }

    private CcmDriver() {
    }

    public static void main(String[] args) throws IOException {
        List<String> refs = new ArrayList<>();
        for (String arg : args) {
            refs.add(resolve(arg));
        }
        if (refs.isEmpty()) {
            refs = builtInScenarios();
        }
        System.out.println("Driver Concurrency Control Manager MonggoDB");
        // Ganti dengan implementasi CCM begitu tersedia
        System.out.println("CCM: MockConcurrencyControlManager, FRM: MockFailureRecoveryManager, verifier: ScheduleVerifierImpl");
        int failed = run(refs, MockConcurrencyControlManager::new, dataStore -> new MockFailureRecoveryManager(),
                new ScheduleVerifierImpl(), false);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * Menjalankan berkas skenario lalu mencetak ringkasan
     * @param refs berkas skenario atau "classpath:/ccm-scenarios/..."
     * @param ccmFactory pembuat CCM baru untuk setiap skenario
     * @param frmFactory pembuat FRM baru untuk setiap skenario; menerima data store driver (null bila checkData false)
     *                   agar FRM tiruan dapat meng-undo data store saat rollback
     * @param verifier verifier CC-09; null bila verifikasi dilewati
     * @param checkData true bila FRM meng-undo data store sehingga data akhir dapat dicek
     * @return jumlah eksekusi yang FAIL
     */
    public static int run(List<String> refs, BiFunction<CCMConfig, FailureRecoveryManager, ConcurrencyControlManager> ccmFactory,
                          Function<Map<String, Integer>, FailureRecoveryManager> frmFactory, ScheduleVerifier verifier,
                          boolean checkData) {
        ScenarioRunner runner = new ScenarioRunner(ccmFactory, verifier);
        List<Summary> summaries = new ArrayList<>();
        for (String ref : refs) {
            String name = ref.substring(ref.replace('\\', '/').lastIndexOf('/') + 1);
            try {
                ScenarioFile file = new ScenarioFile(ref);
                if (file.manual()) {
                    summaries.add(runManual(file, verifier));
                    continue;
                }
                if (!file.checks().isEmpty()) {
                    for (ProtocolType protocol : file.protocols()) {
                        summaries.add(runChecks(file, protocol, ccmFactory, frmFactory));
                    }
                }
                for (Scenario scenario : file.scenarios()) {
                    System.out.println();
                    System.out.println("=== " + scenario.name() + " [" + scenario.protocol() + "]"
                            + (file.title().isEmpty() ? "" : ": " + file.title()));
                    Map<String, Integer> dataStore = checkData ? new HashMap<>() : null;
                    ScenarioReport report = runner.runScenario(scenario, frmFactory.apply(dataStore), dataStore);
                    summaries.add(new Summary(report.scenarioName(), scenario.protocol().name(), file.title(),
                            report.verdict().name()));
                }
            } catch (Exception e) {
                System.out.println();
                System.out.println("=== " + name + ": gagal dijalankan: " + e);
                summaries.add(new Summary(name, "-", "", "FAIL"));
            }
        }

        System.out.println();
        System.out.println("================ RINGKASAN ================");
        long failed = summaries.stream().filter(s -> s.status().equals("FAIL")).count();
        long skipped = summaries.stream().filter(s -> s.status().equals("LEWAT")).count();
        summaries.forEach(s -> System.out.printf("%-6s %-28s %-16s %s%n", s.status(), s.name(), s.protocol(), s.title()));
        System.out.println("===========================================");
        System.out.println((summaries.size() - failed - skipped) + "/" + summaries.size() + " eksekusi PASS"
                + (skipped > 0 ? ", " + skipped + " dilewati" : ""));
        return (int) failed;
    }

    // Schedule manual

    private static Summary runManual(ScenarioFile file, ScheduleVerifier verifier) {
        System.out.println();
        System.out.println("=== " + file.name() + " [manual]" + (file.title().isEmpty() ? "" : ": " + file.title()));
        List<ScheduleOp> schedule = file.manualSchedule();
        System.out.println("  Schedule      : " + VerificationOutput.schedule(schedule, tx -> "T" + tx.value()));
        if (verifier == null) {
            System.out.println("  Verifikasi    : dilewati, verifier CC-09 tidak diberikan");
            return new Summary(file.name(), "manual", file.title(), "LEWAT");
        }
        List<String> failures = new ArrayList<>();
        VerificationResult result = verifier.verifySchedule(schedule);
        System.out.println("  Verifikasi    : " + VerificationOutput.describe(result, tx -> "T" + tx.value()));
        if (result.serializable() != file.serializable()) {
            failures.add("serializable " + result.serializable() + ", seharusnya " + file.serializable());
        }
        VerificationOutput.checkSerialEquivalence(schedule, result, failures);
        return verdict(file, "manual", failures);
    }


    // Cek sistem 

    private static Summary runChecks(ScenarioFile file, ProtocolType protocol,
                                     BiFunction<CCMConfig, FailureRecoveryManager, ConcurrencyControlManager> ccmFactory,
                                     Function<Map<String, Integer>, FailureRecoveryManager> frmFactory) {
        System.out.println();
        System.out.println("=== " + file.name() + " [" + protocol + "]" + (file.title().isEmpty() ? "" : ": " + file.title()));
        FailureRecoveryManager frm = frmFactory.apply(null);
        ConcurrencyControlManager ccm = ccmFactory.apply(new CCMConfig(protocol, 1000, 5000), frm);
        List<String> failures = new ArrayList<>();
        frm.startup();
        ccm.initialize();
        for (String check : file.checks()) {
            String[] words = check.split("\\s+");
            try {
                switch (words[0]) {
                    case "begin-bersamaan" -> checkConcurrentBegin(ccm, Integer.parseInt(words[1]), failures);
                    case "ganti-protokol" -> checkProtocolSwitch(ccm, protocol, failures);
                    default -> failures.add("cek tidak dikenal: " + check);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add("cek " + check + " terinterupsi");
            } catch (RuntimeException e) {
                failures.add("cek " + check + " gagal: " + e);
            }
        }
        ccm.shutdown();
        return verdict(file, protocol.name(), failures);
    }

    private static void checkConcurrentBegin(ConcurrencyControlManager ccm, int n, List<String> failures)
            throws InterruptedException {
        List<TxId> ids = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    TxId id = ccm.beginTransaction();
                    ids.add(id);
                    ccm.commitTransaction(id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        long unique = ids.stream().distinct().count();
        boolean ok = finished && ids.size() == n && unique == n;
        System.out.println("  [cek] " + n + " beginTransaction bersamaan -> " + ids.size() + " TxId, " + unique + " unik");
        if (!ok) {
            failures.add("beginTransaction bersamaan: " + ids.size() + " dari " + n + " selesai, " + unique + " unik");
        }
    }

    private static void checkProtocolSwitch(ConcurrencyControlManager ccm, ProtocolType protocol, List<String> failures) {
        ProtocolType other = protocol == ProtocolType.LOCK_BASED ? ProtocolType.TIMESTAMP_BASED : ProtocolType.LOCK_BASED;
        TxId t = ccm.beginTransaction();
        String busy;
        try {
            ccm.setProtocol(other);
            busy = "diterima";
            failures.add("setProtocol diterima padahal masih ada transaksi aktif");
        } catch (CCMException e) {
            busy = e.getErrorCode().toString();
            if (e.getErrorCode() != CCMException.Code.PROTOCOL_BUSY) {
                failures.add("setProtocol saat sibuk melempar " + e.getErrorCode() + ", seharusnya PROTOCOL_BUSY");
            }
        }
        ccm.commitTransaction(t);
        ccm.setProtocol(other);
        boolean switched = ccm.getProtocol() == other;
        ccm.setProtocol(protocol);
        System.out.println("  [cek] ganti protokol saat aktif -> " + busy + "; saat idle -> "
                + (switched ? "berhasil" : "tidak berganti"));
        if (!switched) {
            failures.add("setProtocol saat idle tidak mengganti protokol");
        }
    }

    private static Summary verdict(ScenarioFile file, String protocol, List<String> failures) {
        String status = failures.isEmpty() ? "PASS" : "FAIL";
        System.out.println("  Hasil         : " + status);
        failures.forEach(f -> System.out.println("                  - " + f));
        return new Summary(file.name(), protocol, file.title(), status);
    }

    // Berkas skenario 

    private static String resolve(String arg) throws IOException {
        Path file = Path.of(arg);
        if (Files.isRegularFile(file)) {
            return file.toAbsolutePath().toString();
        }
        String prefix = "classpath:" + SCENARIOS + arg;
        List<String> matches = builtInScenarios().stream().filter(r -> r.startsWith(prefix)).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        throw new IllegalArgumentException(matches.isEmpty()
                ? "skenario tidak ditemukan: " + arg
                : "nama skenario ambigu: " + arg + " " + matches);
    }

    private static List<String> builtInScenarios() throws IOException {
        List<String> refs = new ArrayList<>();
        try (InputStream in = CcmDriver.class.getResourceAsStream(SCENARIOS + "index.txt")) {
            if (in == null) {
                throw new IOException("ccm-scenarios/index.txt tidak ditemukan di classpath");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    refs.add("classpath:" + SCENARIOS + l);
                }
            }
        }
        return refs;
    }
}
