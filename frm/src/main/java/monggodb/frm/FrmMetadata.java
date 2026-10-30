package monggodb.frm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import monggodb.contracts.shared.FRMException;
import monggodb.contracts.shared.LSN;

/**
 * Metadata FRM yang disimpan di samping file log: snapshot awal untuk pemulihan historis dan
 * rentang LSN yang dibuang oleh restoreTo (riwayat setelah target yang tidak lagi berlaku).
 */
final class FrmMetadata {

    record LsnRange(long from, long to) {
        boolean contains(LSN lsn) {
            return lsn.value() >= from && lsn.value() <= to;
        }
    }

    private final Path file;
    private String snapshotName;
    private LSN snapshotLsn = LSN.NONE;
    private long snapshotTime;
    private final List<LsnRange> discarded = new ArrayList<>();

    FrmMetadata(Path file) {
        this.file = file;
    }

    synchronized void load() {
        discarded.clear();
        snapshotName = null;
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            throw new FRMException(FRMException.Code.RECOVERY_FAILED, "Gagal membaca metadata FRM " + file + ": " + e.getMessage(), e);
        }
        snapshotName = p.getProperty("snapshot.name");
        snapshotLsn = new LSN(Long.parseLong(p.getProperty("snapshot.lsn", "0")));
        snapshotTime = Long.parseLong(p.getProperty("snapshot.time", "0"));
        String ranges = p.getProperty("discarded", "").trim();
        if (!ranges.isEmpty()) {
            for (String part : ranges.split(",")) {
                String[] ft = part.trim().split("-");
                discarded.add(new LsnRange(Long.parseLong(ft[0]), Long.parseLong(ft[1])));
            }
        }
    }

    synchronized void save() {
        Properties p = new Properties();
        if (snapshotName != null) {
            p.setProperty("snapshot.name", snapshotName);
            p.setProperty("snapshot.lsn", Long.toString(snapshotLsn.value()));
            p.setProperty("snapshot.time", Long.toString(snapshotTime));
        }
        StringBuilder sb = new StringBuilder();
        for (LsnRange r : discarded) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(r.from()).append('-').append(r.to());
        }
        p.setProperty("discarded", sb.toString());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "Metadata Failure Recovery Manager. Jangan diubah manual.");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new FRMException(FRMException.Code.LOG_WRITE_FAILED, "Gagal menyimpan metadata FRM: " + e.getMessage(), e);
        }
    }

    synchronized boolean hasSnapshot() {
        return snapshotName != null;
    }

    synchronized void setSnapshot(String name, LSN lsn, long time) {
        snapshotName = name;
        snapshotLsn = lsn;
        snapshotTime = time;
    }

    synchronized String snapshotName() {
        return snapshotName;
    }

    synchronized LSN snapshotLsn() {
        return snapshotLsn;
    }

    synchronized long snapshotTime() {
        return snapshotTime;
    }

    synchronized void addDiscarded(long from, long to) {
        if (to >= from) discarded.add(new LsnRange(from, to));
    }

    synchronized boolean isDiscarded(LSN lsn) {
        for (LsnRange r : discarded) {
            if (r.contains(lsn)) return true;
        }
        return false;
    }
}
