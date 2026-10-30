package monggodb.frm.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Driver komponen Failure Recovery Manager (FR-08, FR-09). Menjalankan skenario uji pencatatan log,
 * pembacaan log, rollback, checkpoint, pemulihan historis, dan crash recovery tanpa CLI Query Processor,
 * memakai SM tiruan berbasis berkas (FileStorageManager).
 *
 * <pre>
 * FrmDriver                          menjalankan semua skenario bawaan (resources/scenarios)
 * FrmDriver 04 07-restore           menjalankan skenario bawaan berdasarkan awalan nama
 * FrmDriver file1.txt file2.txt      menjalankan skenario dari berkas
 * FrmDriver --workdir dir ...        folder kerja (default target/frm-driver)
 * </pre>
 */
public final class FrmDriver {

    private FrmDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--segment")) {
            // Mode internal: proses anak yang menjalankan satu bagian skenario lalu crash.
            new ScenarioRunner(args[1]).runSegmentAndCrash(Path.of(args[2]), Integer.parseInt(args[3]));
            return;
        }

        Path workRoot = Path.of("target", "frm-driver");
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--workdir") && i + 1 < args.length) {
                workRoot = Path.of(args[++i]);
            } else {
                refs.add(resolve(args[i]));
            }
        }
        if (refs.isEmpty()) refs = builtInScenarios();

        System.out.println("Driver Failure Recovery Manager MonggoDB");
        System.out.println("Folder kerja: " + workRoot.toAbsolutePath() + " (dikosongkan ulang setiap skenario)");

        List<ScenarioRunner.Outcome> outcomes = new ArrayList<>();
        for (String ref : refs) {
            ScenarioRunner runner = new ScenarioRunner(ref);
            try {
                outcomes.add(runner.run(workRoot.resolve(runner.name())));
            } catch (Exception e) {
                outcomes.add(new ScenarioRunner.Outcome(runner.name(), "", 0, List.of("skenario gagal dijalankan: " + e)));
            }
        }

        System.out.println();
        System.out.println("================ RINGKASAN ================");
        int failed = 0;
        for (ScenarioRunner.Outcome o : outcomes) {
            System.out.printf("%-6s %-28s %3d cek lulus, %d gagal  %s%n",
                    o.ok() ? "LULUS" : "GAGAL", o.name(), o.passed(), o.failures().size(), o.title());
            for (String f : o.failures()) System.out.println("         - " + f);
            if (!o.ok()) failed++;
        }
        System.out.println("===========================================");
        System.out.println((outcomes.size() - failed) + "/" + outcomes.size() + " skenario lulus");
        if (failed > 0) System.exit(1);
    }

    /** Berkas yang ada dipakai apa adanya; selain itu dicocokkan dengan awalan nama skenario bawaan ("04", "04-crash"). */
    private static String resolve(String arg) throws IOException {
        Path file = Path.of(arg);
        if (Files.isRegularFile(file)) return file.toAbsolutePath().toString();
        String prefix = "classpath:/scenarios/" + arg;
        List<String> matches = builtInScenarios().stream().filter(r -> r.startsWith(prefix)).toList();
        if (matches.size() == 1) return matches.get(0);
        throw new IllegalArgumentException(matches.isEmpty()
                ? "skenario tidak ditemukan: " + arg
                : "nama skenario ambigu: " + arg + " " + matches);
    }

    private static List<String> builtInScenarios() throws IOException {
        List<String> refs = new ArrayList<>();
        try (InputStream in = FrmDriver.class.getResourceAsStream("/scenarios/index.txt")) {
            if (in == null) throw new IOException("scenarios/index.txt tidak ditemukan di classpath");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) refs.add("classpath:/scenarios/" + l);
            }
        }
        return refs;
    }
}
