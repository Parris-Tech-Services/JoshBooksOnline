package au.com.parristech.ruizusync;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import au.com.parristech.ruizusync.core.ByteFormat;
import au.com.parristech.ruizusync.core.DestEntry;
import au.com.parristech.ruizusync.core.DestIndex;
import au.com.parristech.ruizusync.core.DuplicateCheck;
import au.com.parristech.ruizusync.core.DuplicateMatcher;
import au.com.parristech.ruizusync.core.DuplicateMode;
import au.com.parristech.ruizusync.core.FileNameSanitizer;
import au.com.parristech.ruizusync.core.FolderPlanner;
import au.com.parristech.ruizusync.core.HashUtil;
import au.com.parristech.ruizusync.core.MediaTypes;
import au.com.parristech.ruizusync.core.SourceFileInfo;
import au.com.parristech.ruizusync.core.TransferStats;
import au.com.parristech.ruizusync.io.DestRepo;
import au.com.parristech.ruizusync.io.SourceRepo;
import au.com.parristech.ruizusync.model.SourceItem;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs one transfer at a time on a worker thread: scans the player, plans folders, checks for
 * duplicates and copies bytes with progress. Survives activity rotation because it lives in the
 * application process rather than in the activity.
 */
public final class TransferManager {

    public static final String PHASE_IDLE = "idle";
    public static final String PHASE_SCANNING = "scanning";
    public static final String PHASE_RUNNING = "running";
    public static final String PHASE_DONE = "done";
    public static final String PHASE_CANCELLED = "cancelled";
    public static final String PHASE_ERROR = "error";

    private static final int MAX_LOG_LINES = 400;
    private static final long SNAPSHOT_INTERVAL_MS = 120L;

    public interface Listener {
        void onTransferState(Snapshot snapshot);
    }

    public static final class Request {
        public final List<SourceItem> sources;
        public final Uri destTree;
        public final FolderPlanner.OrganiseMode organiseMode;
        public final DuplicateMode duplicateMode;

        public Request(List<SourceItem> sources, Uri destTree,
                       FolderPlanner.OrganiseMode organiseMode, DuplicateMode duplicateMode) {
            this.sources = sources == null ? null : new ArrayList<>(sources);
            this.destTree = destTree;
            this.organiseMode = organiseMode == null ? FolderPlanner.OrganiseMode.FLAT : organiseMode;
            this.duplicateMode = duplicateMode == null ? DuplicateMode.SKIP_EXACT : duplicateMode;
        }
    }

    /** Immutable view of the current state, safe to hold on the UI thread. */
    public static final class Snapshot {
        public final String phase;
        public final String status;
        public final String error;
        public final String currentFile;
        public final int totalFiles;
        public final int completedFiles;
        public final int copied;
        public final int duplicates;
        public final int unsupported;
        public final int failed;
        public final int drmBlocked;
        public final int renamed;
        public final long totalBytes;
        public final long doneBytes;
        public final long currentBytes;
        public final long currentTotal;
        public final double fraction;
        public final double currentFraction;
        public final double bytesPerSecond;
        public final long etaSeconds;
        public final boolean running;
        public final List<String> log;

        private Snapshot(String phase, String status, String error, String currentFile,
                         int totalFiles, int completedFiles, int copied, int duplicates,
                         int unsupported, int failed, int drmBlocked, int renamed,
                         long totalBytes, long doneBytes, long currentBytes, long currentTotal,
                         double fraction, double currentFraction, double bytesPerSecond,
                         long etaSeconds, boolean running, List<String> log) {
            this.phase = phase;
            this.status = status;
            this.error = error;
            this.currentFile = currentFile;
            this.totalFiles = totalFiles;
            this.completedFiles = completedFiles;
            this.copied = copied;
            this.duplicates = duplicates;
            this.unsupported = unsupported;
            this.failed = failed;
            this.drmBlocked = drmBlocked;
            this.renamed = renamed;
            this.totalBytes = totalBytes;
            this.doneBytes = doneBytes;
            this.currentBytes = currentBytes;
            this.currentTotal = currentTotal;
            this.fraction = fraction;
            this.currentFraction = currentFraction;
            this.bytesPerSecond = bytesPerSecond;
            this.etaSeconds = etaSeconds;
            this.running = running;
            this.log = log;
        }

        public boolean isFinished() {
            return !PHASE_IDLE.equals(phase) && !PHASE_SCANNING.equals(phase) && !PHASE_RUNNING.equals(phase);
        }
    }

    private static volatile TransferManager instance;

    public static TransferManager get(Context context) {
        TransferManager local = instance;
        if (local == null) {
            synchronized (TransferManager.class) {
                local = instance;
                if (local == null) {
                    local = new TransferManager(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final Object logLock = new Object();

    private volatile TransferStats stats = new TransferStats();
    private volatile Snapshot snapshot;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile String phase = PHASE_IDLE;
    private volatile String statusText = "Ready";
    private volatile String errorText;
    private volatile long lastEmitMillis;
    private Thread worker;

    private TransferManager(Context context) {
        this.context = context;
        this.snapshot = buildSnapshot();
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        final Snapshot current = snapshot;
        mainHandler.post(() -> listener.onTransferState(current));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return running;
    }

    public Snapshot snapshot() {
        Snapshot current = snapshot;
        return current == null ? buildSnapshot() : current;
    }

    public String phase() {
        return phase;
    }

    public void start(Request request) {
        synchronized (this) {
            if (running) {
                throw new IllegalStateException("A transfer is already running");
            }
            if (request == null || request.sources == null || request.sources.isEmpty()) {
                throw new IllegalArgumentException("No files selected");
            }
            if (request.sources.size() > 5000) throw new IllegalArgumentException("Select at most 5000 files per batch");
            if (request.duplicateMode == DuplicateMode.OVERWRITE) throw new IllegalArgumentException("Existing player files cannot be overwritten");
            if (request.destTree == null) {
                throw new IllegalArgumentException("No player folder selected");
            }
            running = true;
            cancelRequested = false;
            errorText = null;
            lastEmitMillis = 0L;
            phase = PHASE_SCANNING;
            statusText = "Checking the player folder…";
            synchronized (logLock) {
                logLines.clear();
            }
            stats = new TransferStats();
            deliver(true);
            worker = new Thread(() -> execute(request), "ruizu-transfer");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void cancel() {
        if (!running) {
            return;
        }
        cancelRequested = true;
        statusText = "Cancelling…";
        deliver(true);
    }

    private void execute(Request request) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ruizu-sync:transfer");
                wakeLock.acquire(2L * 60L * 60L * 1000L);
            }
        } catch (Throwable ignored) {
            // Copying without a wake lock is slower but still correct.
        }
        try {
            runTransfer(request);
        } catch (CancelledException e) {
            phase = PHASE_CANCELLED;
            statusText = buildSummary(true);
            log("Cancelled by user");
        } catch (Throwable t) {
            phase = PHASE_ERROR;
            errorText = describe(t);
            statusText = "Stopped: " + errorText;
            log("Error: " + errorText);
        } finally {
            running = false;
            if (wakeLock != null) {
                try {
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                } catch (Throwable ignored) {
                    // Nothing to do.
                }
            }
            deliver(true);
        }
    }

    private void runTransfer(Request request) throws Exception {
        SourceRepo sourceRepo = new SourceRepo(context);
        DestRepo destRepo = new DestRepo(context, request.destTree);
        log("Player folder: " + destRepo.rootName());
        long freeBytes = destRepo.availableBytes();
        if (freeBytes > 0) {
            log("Free space on the player: " + ByteFormat.bytes(freeBytes));
        }

        long knownBytes = 0;
        for (SourceItem item : request.sources) {
            if (item.size > 0) {
                knownBytes += item.size;
            }
        }
        stats.start(request.sources.size(), knownBytes);
        phase = PHASE_RUNNING;
        statusText = "Copying files…";
        deliver(true);

        for (SourceItem item : request.sources) {
            checkCancelled();
            try {
                transferOne(sourceRepo, destRepo, request, item);
            } catch (CancelledException e) {
                throw e;
            } catch (Throwable t) {
                stats.finishFile(TransferStats.Outcome.FAILED);
                log("Failed: " + item.displayName + " — " + describe(t));
            }
            deliver(false);
        }
        checkCancelled();
        phase = PHASE_DONE;
        statusText = buildSummary(false);
        log(statusText);
        deliver(true);
    }

    private void transferOne(SourceRepo sourceRepo, DestRepo destRepo, Request request, SourceItem item) throws Exception {
        stats.beginFile(item.displayName, item.size);

        if (item.problem != null && !item.problem.isEmpty()) {
            stats.finishFile(TransferStats.Outcome.UNSUPPORTED);
            log("Skipped: " + item.displayName + " — " + item.problem);
            return;
        }
        if (!item.supportedFormat()) {
            stats.finishFile(TransferStats.Outcome.UNSUPPORTED);
            log("Skipped unsupported format: " + item.displayName);
            return;
        }
        if (sourceRepo.isDrmProtected(item.uri)) {
            stats.finishFile(TransferStats.Outcome.DRM_BLOCKED);
            log("Skipped DRM-protected file (no DRM bypass): " + item.displayName);
            return;
        }

        String artist = null;
        String album = null;
        if (request.organiseMode == FolderPlanner.OrganiseMode.ARTIST_ALBUM) {
            SourceRepo.TrackTags tags = sourceRepo.readTags(item.uri);
            artist = tags.artist;
            album = tags.album;
        }
        List<String> segments = FolderPlanner.folderSegments(request.organiseMode, artist, album,
                item.sourceFolder, item.treeRelDirs);
        DestRepo.Node targetDir = destRepo.ensureDir(segments);

        String desiredName = TransferUtils.safeName(FileNameSanitizer.fileName(item.displayName));
        long[] previous = {0};
        boolean copied;
        try (InputStream in = sourceRepo.open(item.uri)) {
            copied = new SafTransfer(context, () -> cancelRequested, targetDir.docId,
                    request.duplicateMode != DuplicateMode.KEEP_BOTH).transfer(
                in, request.destTree, desiredName, item.size, (bytes, total) -> {
                    stats.addBytes(bytes - previous[0]); previous[0] = bytes; deliver(false);
                });
        } catch (java.io.InterruptedIOException error) {
            if (cancelRequested) throw new CancelledException();
            throw error;
        } finally {
            destRepo.invalidate();
        }
        stats.finishFile(copied ? TransferStats.Outcome.COPIED : TransferStats.Outcome.DUPLICATE_SKIPPED);
        log(copied ? "SHA-256 verified: " + desiredName : "Identical content already present: " + desiredName);
    }

    private void checkCancelled() throws CancelledException {
        if (cancelRequested) {
            throw new CancelledException();
        }
    }

    private void deliver(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastEmitMillis < SNAPSHOT_INTERVAL_MS) {
            return;
        }
        lastEmitMillis = now;
        final Snapshot snap = buildSnapshot();
        snapshot = snap;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onTransferState(snap);
                } catch (Throwable ignored) {
                    // A misbehaving listener must not break the transfer.
                }
            }
        });
    }

    private void log(String line) {
        String stamped = line;
        synchronized (logLock) {
            logLines.addLast(stamped);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
    }

    private Snapshot buildSnapshot() {
        TransferStats s = stats;
        List<String> lines;
        synchronized (logLock) {
            int size = logLines.size();
            int from = Math.max(0, size - 60);
            List<String> all = new ArrayList<>(logLines);
            lines = Collections.unmodifiableList(new ArrayList<>(all.subList(from, all.size())));
        }
        return new Snapshot(phase, statusText, errorText, s.getCurrentFileName(),
                s.getTotalFiles(), s.getCompletedFiles(), s.getCopiedFiles(), s.getDuplicateFiles(),
                s.getUnsupportedFiles(), s.getFailedFiles(), s.getDrmFiles(), s.getRenamedFiles(),
                s.getTotalBytes(), s.getDoneBytes(), s.getCurrentBytes(), s.getCurrentTotal(),
                s.fraction(), s.getCurrentFileFraction(), s.bytesPerSecond(), s.etaSeconds(),
                running, lines);
    }

    private String buildSummary(boolean cancelled) {
        TransferStats s = stats;
        StringBuilder sb = new StringBuilder();
        if (cancelled) {
            sb.append("Cancelled · ");
        }
        sb.append(s.getCopiedFiles()).append(" of ").append(s.getTotalFiles())
                .append(s.getTotalFiles() == 1 ? " file copied" : " files copied");
        if (s.getDuplicateFiles() > 0) {
            sb.append(" · ").append(s.getDuplicateFiles()).append(" duplicate")
                    .append(s.getDuplicateFiles() == 1 ? "" : "s").append(" skipped");
        }
        if (s.getUnsupportedFiles() > 0) {
            sb.append(" · ").append(s.getUnsupportedFiles()).append(" unsupported skipped");
        }
        if (s.getDrmFiles() > 0) {
            sb.append(" · ").append(s.getDrmFiles()).append(" DRM-protected skipped");
        }
        if (s.getFailedFiles() > 0) {
            sb.append(" · ").append(s.getFailedFiles()).append(" failed");
        }
        if (s.getRenamedFiles() > 0) {
            sb.append(" · ").append(s.getRenamedFiles()).append(" renamed");
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }
}
