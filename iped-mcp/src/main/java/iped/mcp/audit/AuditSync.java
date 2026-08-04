package iped.mcp.audit;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Co-locates the audit trail with the case, automatically.
 *
 * <p>
 * The workstation area that {@link AuditTrail} writes to is a write-ahead buffer, not the home of
 * the trail. Under a single isolated workstation the case folder is the only durable storage
 * available — it is what gets archived and handed over — so co-location is not a delivery
 * convenience, it is the durability mechanism (R7).
 *
 * <p>
 * Three behaviours, in the order they matter:
 *
 * <ol>
 * <li>Synchronization is <b>automatic</b>: periodic during the session and on close. The examiner
 * never has to ask for it (FR-072).</li>
 * <li>A case on non-writable media degrades explicitly: the workstation copy stays authoritative
 * and the session warns at open that the trail cannot be co-located (FR-073).</li>
 * <li>A previous trail on the workstation with no counterpart in the case folder is reported at
 * open (FR-074). This is the highest-value behaviour of the three: the others reduce the chance of
 * losing the trail, this one guarantees a loss is <i>noticed</i> rather than discovered at the
 * moment the trail is needed.</li>
 * </ol>
 */
public class AuditSync implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditSync.class);

    /** Synchronization state of the trail with respect to one case. */
    public enum SyncState {
        /** Only on the workstation — the case folder could not be written. */
        STAGED,
        /** Co-located inside the case folder. */
        SYNCED
    }

    /** One case the trail is being synchronized into. */
    public static class Target {
        public final String caseId;
        public final String caseBinding;
        public final File caseAuditDir;
        public SyncState state;
        public String degradationReason;

        Target(String caseId, String caseBinding, File caseAuditDir, SyncState state, String degradationReason) {
            this.caseId = caseId;
            this.caseBinding = caseBinding;
            this.caseAuditDir = caseAuditDir;
            this.state = state;
            this.degradationReason = degradationReason;
        }
    }

    private final AuditTrail trail;
    private final String auditFolderNameInCase;
    private final int intervalSeconds;
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private ScheduledExecutorService scheduler;

    public AuditSync(AuditTrail trail, String auditFolderNameInCase, int intervalSeconds) {
        this.trail = trail;
        this.auditFolderNameInCase = auditFolderNameInCase;
        this.intervalSeconds = intervalSeconds;
    }

    /** Starts the periodic synchronization. Idempotent. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iped-mcp-audit-sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::syncQuietly, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Registers a case as a synchronization target and performs the first synchronization.
     *
     * @return the resulting target, whose state tells whether co-location succeeded
     */
    public synchronized Target bindCase(String caseId, String caseBinding, File casePath) {
        File auditDir = new File(casePath, auditFolderNameInCase);
        Target target;
        String failure = probeWritable(auditDir);
        if (failure == null) {
            target = new Target(caseId, caseBinding, auditDir, SyncState.SYNCED, null);
        } else {
            target = new Target(caseId, caseBinding, auditDir, SyncState.STAGED, failure);
            LOGGER.warn("Audit trail cannot be co-located with case {}: {}", caseId, failure);
        }
        targets.put(caseId, target);
        syncTarget(target);
        return target;
    }

    public synchronized void unbindCase(String caseId) {
        Target target = targets.remove(caseId);
        if (target != null) {
            syncTarget(target);
        }
    }

    /**
     * Checks whether the audit subfolder can be created and written inside the case.
     *
     * @return {@code null} when writable, or the reason it is not
     */
    private static String probeWritable(File auditDir) {
        try {
            Files.createDirectories(auditDir.toPath());
            File probe = new File(auditDir, ".write-probe");
            Files.write(probe.toPath(), new byte[0]);
            Files.deleteIfExists(probe.toPath());
            return null;
        } catch (IOException | SecurityException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Synchronizes into every bound case. Failures are logged, never propagated. */
    public synchronized void syncQuietly() {
        for (Target target : targets.values()) {
            syncTarget(target);
        }
    }

    private void syncTarget(Target target) {
        if (target.state == SyncState.STAGED) {
            return;
        }
        try {
            Path source = trail.getPath();
            if (!Files.exists(source)) {
                return;
            }
            Path destination = target.caseAuditDir.toPath().resolve(source.getFileName().toString());
            Path temp = target.caseAuditDir.toPath().resolve(source.getFileName() + ".part");
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Degrade rather than fail: the workstation copy stays authoritative (FR-073).
            target.state = SyncState.STAGED;
            target.degradationReason = e.getMessage();
            LOGGER.warn("Audit synchronization into {} failed; workstation copy remains authoritative",
                    target.caseAuditDir, e);
        }
    }

    public synchronized Map<String, Target> getTargets() {
        return new LinkedHashMap<>(targets);
    }

    /**
     * Finds trails on the workstation that are bound to a case but have no counterpart inside the
     * case folder (FR-074).
     *
     * @param auditArea
     *            the workstation staging area
     * @param caseBinding
     *            strong binding of the case: canonical path plus index identity
     * @param casePath
     *            the case folder
     * @param auditFolderNameInCase
     *            name of the audit subfolder inside the case
     * @return the file names of the orphan trails, empty when there is none
     */
    public static List<String> findOrphanTrails(File auditArea, String caseBinding, File casePath,
            String auditFolderNameInCase) {
        List<String> orphans = new ArrayList<>();
        File[] staged = auditArea.listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (staged == null) {
            return orphans;
        }
        File caseAuditDir = new File(casePath, auditFolderNameInCase);
        for (File file : staged) {
            if (!mentionsCase(file, caseBinding)) {
                continue;
            }
            File counterpart = new File(caseAuditDir, file.getName());
            if (!counterpart.exists()) {
                orphans.add(file.getName());
            }
        }
        return orphans;
    }

    private static boolean mentionsCase(File trailFile, String caseBinding) {
        if (caseBinding == null) {
            return false;
        }
        try {
            for (AuditRecord record : AuditTrail.read(trailFile)) {
                if (caseBinding.equals(record.getCaseBinding())) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not inspect staged trail {} while looking for orphans", trailFile, e);
        }
        return false;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        syncQuietly();
    }
}
