package monggodb.ccm.driver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import monggodb.ccm.driver.ScenarioReport.OperationStatus;
import monggodb.ccm.serializability.ScheduleOp;
import monggodb.ccm.serializability.ScheduleVerifier;
import monggodb.ccm.serializability.VerificationResult;
import monggodb.contracts.ccm.ConcurrencyControlManager;
import monggodb.contracts.frm.FailureRecoveryManager;
import monggodb.contracts.shared.AbortReason;
import monggodb.contracts.shared.AccessDecision;
import monggodb.contracts.shared.AccessResponse;
import monggodb.contracts.shared.CCMConfig;
import monggodb.contracts.shared.CCMException;
import monggodb.contracts.shared.ChangeRequest;
import monggodb.contracts.shared.DataType;
import monggodb.contracts.shared.LogType;
import monggodb.contracts.shared.RID;
import monggodb.contracts.shared.ResourceId;
import monggodb.contracts.shared.Row;
import monggodb.contracts.shared.TransactionResult;
import monggodb.contracts.shared.TransactionStatus;
import monggodb.contracts.shared.TxId;
import monggodb.contracts.shared.Value;

/** Menjalankan satu Scenario terhadap CCM dengan satu thread per transaksi (CC-10) */
public final class ScenarioRunner {

    /** Batas tunggu keputusan satu langkah sebelum langkah berikutnya dilepas */
    private static final long STEP_WAIT_MS = 1000;
    /** Jeda antarlangkah agar urutan pelepasan stabil */
    private static final long STEP_GAP_MS = 30;

    private final BiFunction<CCMConfig, FailureRecoveryManager, ConcurrencyControlManager> ccmFactory;
    private final ScheduleVerifier verifier;

    /**
     * @param ccmFactory pembuat CCM baru untuk setiap skenario (mock atau implementasi)
     * @param verifier verifier CC-09; null bila verifikasi dilewati
     */
    public ScenarioRunner(BiFunction<CCMConfig, FailureRecoveryManager, ConcurrencyControlManager> ccmFactory,
                          ScheduleVerifier verifier) {
        this.ccmFactory = ccmFactory;
        this.verifier = verifier;
    }

    /**
     * Menjalankan Scenario dengan giliran per langkah lalu membandingkan hasilnya dengan expected
     * @param scenario skenario yang dijalankan dengan satu protokol
     * @param frm FRM mock atau asli, baru untuk skenario ini
     * @param dataStore data store in-memory driver yang ikut di-undo FRM saat abort; null bila data akhir tidak dicek
     * @return status setiap operasi, schedule akhir, hasil verifikasi serializability, pengecekan data akhir,
     *         dan PASS/FAIL dibanding expected
     * @calls facade CCM; verifySchedule; FRM: readLog
     * @calledBy driver CCM
     */
    public ScenarioReport runScenario(Scenario scenario, FailureRecoveryManager frm, Map<String, Integer> dataStore) {
        return new Execution(scenario, frm, dataStore).run();
    }

    /** Langkah skenario beserta label pemiliknya */
    private record Planned(String label, ScenarioOp op) { }

    private static final class StepRun {
        final Planned planned;
        final CountDownLatch decided = new CountDownLatch(1);
        volatile AccessDecision first;
        volatile String outcome = "";

        StepRun(Planned planned) {
            this.planned = planned;
        }
    }

    /** Satu eksekusi skenario dengan CCM baru */
    private final class Execution {
        private final Scenario scenario;
        private final CCMConfig config;
        private final FailureRecoveryManager frm;
        private final ConcurrencyControlManager ccm;
        private final Map<String, Integer> dataStore;
        private final Map<String, TxId> txOf = new LinkedHashMap<>();
        private final Map<TxId, String> labelOf = new HashMap<>();
        private final Map<String, Integer> ridIndex = new HashMap<>();
        private final Map<String, Boolean> waiting = new ConcurrentHashMap<>();
        private final Map<String, AbortReason> abortReason = new ConcurrentHashMap<>();
        private final Map<String, Long> abortedAt = new ConcurrentHashMap<>();
        private final List<ScheduleOp> schedule = new CopyOnWriteArrayList<>();
        private final List<OperationStatus> operations = new CopyOnWriteArrayList<>();
        private final List<Long> accessSubmits = new CopyOnWriteArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private long seq;
        private long deadline;
        private boolean timedOut;

        Execution(Scenario scenario, FailureRecoveryManager frm, Map<String, Integer> dataStore) {
            this.scenario = scenario;
            this.config = new CCMConfig(scenario.protocol(), 1000, 5000);
            this.frm = frm;
            this.ccm = ccmFactory.apply(config, frm);
            this.dataStore = dataStore;
            if (dataStore != null) {
                synchronized (dataStore) {
                    dataStore.clear();
                    dataStore.putAll(scenario.initialData());
                }
            }
        }

        ScenarioReport run() {
            deadline = System.currentTimeMillis() + scenario.timeoutMs();
            frm.startup();
            ccm.initialize();
            List<StepRun> steps = scenario.transactions().stream()
                    .flatMap(tx -> tx.ops().stream().map(op -> new Planned(tx.label(), op)))
                    .sorted(Comparator.comparingInt(p -> p.op().step()))
                    .map(StepRun::new).toList();
            for (ScenarioTx tx : scenario.transactions()) {
                TxId txId = ccm.beginTransaction();
                txOf.put(tx.label(), txId);
                labelOf.put(txId, tx.label());
                waiting.put(tx.label(), false);
            }
            release(steps);

            report(steps);
            Map<String, TransactionStatus> finalStatus = finalStatus();
            checkExpected(finalStatus);
            if (!ccm.waitUntilNoActive(1000)) {
                failures.add("setelah skenario masih ada transaksi, akses, atau rollback yang belum selesai");
            }
            VerificationResult verification = verify();
            Boolean finalDataOk = checkFinalData(verification);
            ccm.shutdown();

            ScenarioReport.Verdict verdict = failures.isEmpty() ? ScenarioReport.Verdict.PASS : ScenarioReport.Verdict.FAIL;
            System.out.println("  Hasil         : " + verdict);
            failures.forEach(f -> System.out.println("                  - " + f));
            return new ScenarioReport(scenario.name(), operations, schedule, verification, finalDataOk, timedOut, verdict);
        }

        private void release(List<StepRun> steps) {
            Map<String, ExecutorService> workers = new HashMap<>();
            txOf.keySet().forEach(label -> workers.put(label, Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, scenario.name() + "-" + label);
                t.setDaemon(true);
                return t;
            })));
            try {
                for (StepRun s : steps) {
                    String label = s.planned.label();
                    if (s.planned.op().pauseBeforeMs() > 0) {
                        Thread.sleep(s.planned.op().pauseBeforeMs());
                    }
                    if (s.planned.op().kind() == ScenarioOp.Kind.READ || s.planned.op().kind() == ScenarioOp.Kind.WRITE) {
                        accessSubmits.add(System.currentTimeMillis());
                    }
                    workers.get(label).submit(() -> execute(s));
                    long until = System.currentTimeMillis() + STEP_WAIT_MS;
                    while (s.decided.getCount() > 0 && !waiting.get(label) && System.currentTimeMillis() < until) {
                        Thread.sleep(5);
                    }
                    Thread.sleep(STEP_GAP_MS);
                }
                for (ExecutorService w : workers.values()) {
                    w.shutdown();
                    timedOut |= !w.awaitTermination(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                }
                if (timedOut) {
                    failures.add("batas waktu skenario " + scenario.timeoutMs() + " ms terlewati (ada transaksi yang menunggu terus)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add("driver terinterupsi");
            } finally {
                workers.values().forEach(ExecutorService::shutdownNow);
            }
        }

        private void execute(StepRun s) {
            String label = s.planned.label();
            ScenarioOp op = s.planned.op();
            TxId txId = txOf.get(label);
            try {
                switch (op.kind()) {
                    case READ, WRITE -> access(s, txId);
                    case COMMIT -> {
                        TransactionResult r = ccm.commitTransaction(txId);
                        s.outcome = r.status() + (r.success() ? "" : " (commit ditolak)");
                        boolean committed = r.status() == TransactionStatus.COMMITTED;
                        s.first = committed ? AccessDecision.ALLOWED : AccessDecision.ABORTED;
                        operations.add(new OperationStatus(label, op, s.first, committed ? null : r.abortReason()));
                        s.decided.countDown();
                        finish(label, txId, r);
                    }
                    case ABORT -> {
                        TransactionResult r = ccm.abortTransaction(txId, AbortReason.USER_REQUEST);
                        s.outcome = r.status().toString();
                        s.first = AccessDecision.ABORTED;
                        operations.add(new OperationStatus(label, op, AccessDecision.ABORTED, r.abortReason()));
                        s.decided.countDown();
                        finish(label, txId, r);
                    }
                }
            } catch (CCMException e) {
                s.outcome += (s.outcome.isEmpty() ? "" : " -> ") + "ERROR " + e.getErrorCode() + ": " + e.getMessage();
            } catch (RuntimeException e) {
                s.outcome += (s.outcome.isEmpty() ? "" : " -> ") + "ERROR " + e;
            } finally {
                s.decided.countDown();
            }
        }

        private void access(StepRun s, TxId txId) {
            String label = s.planned.label();
            ScenarioOp op = s.planned.op();
            ResourceId resource = new ResourceId(op.item());
            ChangeRequest change = op.kind() == ScenarioOp.Kind.WRITE ? change(label, op) : null;
            AccessResponse r = change == null ? ccm.requestRead(txId, resource) : ccm.requestWrite(txId, resource, change);
            s.first = r.decision();
            s.outcome = r.decision() + (r.blockedBy().isEmpty() ? "" : " " + r.blockedBy().stream().map(this::label).toList());
            operations.add(new OperationStatus(label, op, r.decision(), r.abortReason()));
            s.decided.countDown();
            if (r.decision() == AccessDecision.WAITING) {
                waiting.put(label, true);
                try {
                    while (r.decision() == AccessDecision.WAITING && System.currentTimeMillis() < deadline
                            && !Thread.currentThread().isInterrupted()) {
                        r = ccm.waitForAccess(txId, r.requestId(), 100);
                    }
                } finally {
                    waiting.put(label, false);
                }
                s.outcome += " -> " + r.decision();
                if (r.decision() != AccessDecision.WAITING) {
                    operations.add(new OperationStatus(label, op, r.decision(), r.abortReason()));
                }
            }
            if (r.decision() == AccessDecision.ALLOWED) {
                if (change != null && dataStore != null) {
                    synchronized (dataStore) {
                        dataStore.put(op.item(), op.value());
                    }
                }
                ccm.completeAccess(txId, r.requestId(), true);
                record(txId, ScheduleOp.Kind.valueOf(op.kind().name()), resource, op.value());
            } else if (r.decision() == AccessDecision.ABORTED) {
                s.outcome += " " + r.abortReason();
                // Seperti QP, transaksi yang menerima ABORTED dibatalkan lewat abortTransaction
                finish(label, txId, ccm.abortTransaction(txId, r.abortReason()));
            }
        }

        /** ChangeRequest UPDATE dengan RID(0, indeks item) dan before dari data store */
        private ChangeRequest change(String label, ScenarioOp op) {
            RID rid;
            synchronized (ridIndex) {
                rid = new RID(0, ridIndex.computeIfAbsent(op.item(), k -> ridIndex.size()));
            }
            Integer before = scenario.initialData().get(op.item());
            if (dataStore != null) {
                synchronized (dataStore) {
                    before = dataStore.get(op.item());
                }
            }
            Row beforeRow = new Row(rid, List.of(before == null ? Value.ofNull(DataType.INTEGER) : Value.ofInteger(before)));
            return new ChangeRequest(scenario.name() + "-" + label + "-" + op.step(), LogType.UPDATE, op.item(), rid,
                    beforeRow, new Row(rid, List.of(Value.ofInteger(op.value()))));
        }

        private void finish(String label, TxId txId, TransactionResult r) {
            if (r.status() == TransactionStatus.COMMITTED) {
                record(txId, ScheduleOp.Kind.COMMIT, null, null);
            } else if (r.status() == TransactionStatus.ABORTED && abortReason.putIfAbsent(label, r.abortReason()) == null) {
                abortedAt.put(label, System.currentTimeMillis());
                record(txId, ScheduleOp.Kind.ABORT, null, null);
            }
        }

        private synchronized void record(TxId txId, ScheduleOp.Kind kind, ResourceId resource, Integer value) {
            schedule.add(new ScheduleOp(++seq, txId, kind, resource, kind == ScheduleOp.Kind.WRITE ? value : null));
        }

        private String label(TxId id) {
            return labelOf.getOrDefault(id, id.toString());
        }

        private void report(List<StepRun> steps) {
            for (StepRun s : steps) {
                ScenarioOp op = s.planned.op();
                String text = ScenarioFile.text(s.planned.label(), op);
                if (op.pauseBeforeMs() > 0) {
                    System.out.println("       jeda(" + op.pauseBeforeMs() + ")");
                }
                boolean pending = s.decided.getCount() > 0;
                System.out.printf("  [%2d] %-10s %-3s -> %s%n", op.step(), text, s.planned.label(),
                        pending ? "BELUM DIPROSES" : s.outcome);
                if (pending || s.outcome.contains("ERROR")) {
                    failures.add("langkah " + op.step() + " " + text + ": " + (pending ? "tidak pernah diproses" : s.outcome));
                }
                if (op.expectedDecision() != null && s.first != op.expectedDecision()) {
                    failures.add("langkah " + op.step() + " " + text + ": keputusan " + s.first
                            + ", seharusnya " + op.expectedDecision());
                }
            }
            System.out.println("  Schedule akhir: " + VerificationOutput.schedule(schedule, this::label));
        }

        private Map<String, TransactionStatus> finalStatus() {
            Map<String, TransactionStatus> status = new TreeMap<>();
            txOf.forEach((label, txId) -> {
                try {
                    status.put(label, ccm.getTransactionStatus(txId));
                } catch (CCMException e) {
                    failures.add(label + ": getTransactionStatus gagal " + e.getErrorCode());
                }
            });
            System.out.println("  Status akhir  : " + status.entrySet().stream().map(en -> en.getKey() + " " + en.getValue()
                    + (abortReason.containsKey(en.getKey()) ? " (" + abortReason.get(en.getKey()) + ")" : "")).toList());
            return status;
        }

        private void checkExpected(Map<String, TransactionStatus> finalStatus) {
            ScenarioExpected expected = scenario.expected();
            expected.finalStatus().forEach((label, want) -> {
                if (finalStatus.get(label) != want) {
                    failures.add(label + " berstatus " + finalStatus.get(label) + ", seharusnya " + want);
                }
            });
            expected.abortReason().forEach((label, want) -> {
                if (finalStatus.get(label) == TransactionStatus.ABORTED && abortReason.get(label) != want) {
                    failures.add(label + " dibatalkan karena " + abortReason.get(label) + ", seharusnya " + want);
                }
                if (want == AbortReason.DEADLOCK_VICTIM && abortedAt.containsKey(label)) {
                    long at = abortedAt.get(label);
                    long from = accessSubmits.stream().filter(t -> t <= at).max(Long::compare).orElse(at);
                    if (at - from > config.deadlockResolveTimeoutMs()) {
                        failures.add("deadlock selesai dalam " + (at - from) + " ms, melewati target "
                                + config.deadlockResolveTimeoutMs() + " ms");
                    }
                }
            });
        }



        private VerificationResult verify() {
            if (verifier == null) {
                System.out.println("  Verifikasi    : dilewati, verifier CC-09 tidak diberikan");
                return null;
            }
            try {
                VerificationResult result = verifier.verifySchedule(List.copyOf(schedule));
                System.out.println("  Verifikasi    : " + VerificationOutput.describe(result, this::label));
                if (result.serializable() != scenario.expected().serializable()) {
                    failures.add("serializable " + result.serializable() + ", seharusnya " + scenario.expected().serializable());
                }
                VerificationOutput.checkSerialEquivalence(List.copyOf(schedule), result, failures);
                return result;
            } catch (RuntimeException e) {
                failures.add("verifySchedule gagal: " + e);
                return null;
            }
        }

        /** Membandingkan data akhir dengan finalData atau eksekusi ulang serial (CC-09) */
        private Boolean checkFinalData(VerificationResult verification) {
            if (dataStore == null) {
                System.out.println("  Data akhir    : tidak dicek (tanpa data store)");
                return null;
            }
            Map<String, Integer> actual;
            synchronized (dataStore) {
                actual = new TreeMap<>(dataStore);
            }
            // Item tanpa nilai awal yang di-undo kembali ke null
            actual.values().removeIf(java.util.Objects::isNull);
            Map<String, Integer> want = new TreeMap<>(scenario.initialData());
            if (!scenario.expected().finalData().isEmpty()) {
                want.putAll(scenario.expected().finalData());
            } else if (verification != null && verification.serializable()) {
                for (TxId tx : verification.serialOrder()) {
                    for (ScheduleOp op : schedule) {
                        if (op.kind() == ScheduleOp.Kind.WRITE && op.txId().equals(tx)) {
                            want.put(op.resource().table(), op.value());
                        }
                    }
                }
            } else {
                System.out.println("  Data akhir    : " + actual + " (tidak dapat dibandingkan, urutan serial tidak ada)");
                failures.add("data akhir tidak dapat dibandingkan dengan eksekusi serial");
                return false;
            }
            boolean ok = actual.equals(want);
            System.out.println("  Data akhir    : " + actual + (ok ? " sesuai" : ", seharusnya " + want));
            if (!ok) {
                failures.add("data akhir " + actual + ", seharusnya " + want);
            }
            return ok;
        }
    }

}
