package au.com.parristech.ruizusync;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import au.com.parristech.ruizusync.audio.AudioTranscoder;
import au.com.parristech.ruizusync.core.ByteFormat;
import au.com.parristech.ruizusync.SafTransfer;
import au.com.parristech.ruizusync.core.ConversionPolicy;
import au.com.parristech.ruizusync.core.DestEntry;
import au.com.parristech.ruizusync.core.DestIndex;
import au.com.parristech.ruizusync.core.DuplicateCheck;
import au.com.parristech.ruizusync.core.DuplicateMatcher;
import au.com.parristech.ruizusync.core.DuplicateMode;
import au.com.parristech.ruizusync.core.FileNameSanitizer;
import au.com.parristech.ruizusync.core.FolderPlanner;
import au.com.parristech.ruizusync.core.HashUtil;
import au.com.parristech.ruizusync.core.MediaTypes;
import au.com.parristech.ruizusync.core.PlaylistWriter;
import au.com.parristech.ruizusync.core.SourceFileInfo;
import au.com.parristech.ruizusync.core.TransferStats;
import au.com.parristech.ruizusync.io.DestRepo;
import au.com.parristech.ruizusync.io.SourceRepo;
import au.com.parristech.ruizusync.model.SourceItem;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs one transfer at a time on a worker thread: scans the player, plans folders, checks for
 * duplicates, copies bytes with progress, retries transient failures, optionally verifies each
 * file by SHA-256, writes M3U playlists and persists the queue so it can be resumed.
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
    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_DELAYS_MS = {600L, 1800L};
    private static final int PERSIST_EVERY_FILES = 10;

    public interface Listener {
        void onTransferState(Snapshot snapshot);
    }

    public static final class Request {
        public final List<SourceItem> sources;
        public final Uri destTree;
        public final FolderPlanner.OrganiseMode organiseMode;
        public final DuplicateMode duplicateMode;
        public final boolean verifyAfterCopy;
        public final boolean duplicatesInTargetFolderOnly;
        public final boolean writePlaylists;
        public final String playlistName;
        public final List<String> destinationPrefix;
        public final boolean convertToMp3;
        public final java.util.Set<String> convertFormats;

        public Request(List<SourceItem> sources, Uri destTree,
                       FolderPlanner.OrganiseMode organiseMode, DuplicateMode duplicateMode) {
            this(sources, destTree, organiseMode, duplicateMode, false, false, false,
                    "", null, false, null);
        }

        public Request(List<SourceItem> sources, Uri destTree,
                       FolderPlanner.OrganiseMode organiseMode, DuplicateMode duplicateMode,
                       boolean verifyAfterCopy, boolean duplicatesInTargetFolderOnly,
                       boolean writePlaylists) {
            this(sources, destTree, organiseMode, duplicateMode, verifyAfterCopy,
                    duplicatesInTargetFolderOnly, writePlaylists, "", null, false, null);
        }

        public Request(List<SourceItem> sources, Uri destTree,
                       FolderPlanner.OrganiseMode organiseMode, DuplicateMode duplicateMode,
                       boolean verifyAfterCopy, boolean duplicatesInTargetFolderOnly,
                       boolean writePlaylists, String playlistName, List<String> destinationPrefix,
                       boolean convertToMp3, java.util.Set<String> convertFormats) {
            this.sources = sources;
            this.destTree = destTree;
            this.organiseMode = organiseMode == null ? FolderPlanner.OrganiseMode.FLAT : organiseMode;
            this.duplicateMode = duplicateMode == null ? DuplicateMode.SKIP_FAST : duplicateMode;
            this.verifyAfterCopy = verifyAfterCopy;
            this.duplicatesInTargetFolderOnly = duplicatesInTargetFolderOnly;
            this.writePlaylists = writePlaylists;
            this.playlistName = playlistName == null ? "" : playlistName;
            this.destinationPrefix = destinationPrefix == null
                    ? java.util.Collections.<String>emptyList() : destinationPrefix;
            this.convertToMp3 = convertToMp3;
            this.convertFormats = convertFormats == null
                    ? ConversionPolicy.LOSSY_OR_ODD_FORMATS : convertFormats;
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
        public final int retried;
        public final int playlists;
        public final int failedSources;
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
                         int unsupported, int failed, int drmBlocked, int renamed, int retried,
                         int playlists, int failedSources,
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
            this.retried = retried;
            this.playlists = playlists;
            this.failedSources = failedSources;
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

    private static final class CopyResult {
        final DestRepo.Node node;
        final long bytes;

        CopyResult(DestRepo.Node node, long bytes) {
            this.node = node;
            this.bytes = bytes;
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
    private final List<SourceItem> failedSources = new CopyOnWriteArrayList<>();

    private volatile TransferStats stats = new TransferStats();
    private volatile Snapshot snapshot;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile String phase = PHASE_IDLE;
    private volatile String statusText = "Ready";
    private volatile String errorText;
    private volatile long lastEmitMillis;
    private volatile long remainingFreeBytes = -1;
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

    /** Items that failed during the last run, so the UI can offer "Retry failed". */
    public List<SourceItem> failedSources() {
        return Collections.unmodifiableList(new ArrayList<>(failedSources));
    }

    public void clearFailedSources() {
        failedSources.clear();
    }

    public void start(Request request) {
        synchronized (this) {
            if (running) {
                throw new IllegalStateException("A transfer is already running");
            }
            if (request == null || request.sources == null || request.sources.isEmpty()) {
                throw new IllegalArgumentException("No files selected");
            }
            if (request.destTree == null) {
                throw new IllegalArgumentException("No player folder selected");
            }
            if (request.duplicateMode == null || !request.duplicateMode.supported()) {
                // Existing player audio is never deleted or replaced. Reject rather than
                // silently ignoring the request, so no caller can believe it overwrote.
                throw new IllegalArgumentException(
                        "Overwriting player audio is not supported: existing files are never "
                                + "replaced (" + request.duplicateMode + ")");
            }
            running = true;
            cancelRequested = false;
            errorText = null;
            lastEmitMillis = 0L;
            remainingFreeBytes = -1;
            failedSources.clear();
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
            HistoryStore.record(context, new HistoryStore.Run(System.currentTimeMillis(), statusText,
                    stats.getCopiedFiles(), stats.getFailedFiles(), stats.getDuplicateFiles(),
                    stats.getUnsupportedFiles(), stats.getRetriedFiles(), stats.getPlaylistsWritten(),
                    snapshotLogText()));
            deliver(true);
        }
    }

    private void runTransfer(Request request) throws Exception {
        SourceRepo sourceRepo = new SourceRepo(context);
        DestRepo destRepo = new DestRepo(context, request.destTree);
        log("Player folder: " + destRepo.rootName());
        long freeBytes = destRepo.availableBytes();
        remainingFreeBytes = freeBytes;
        if (freeBytes > 0) {
            log("Free space on the player: " + ByteFormat.bytes(freeBytes));
        }

        log("Checking which files are already on the player…");
        List<DestRepo.Node> existing = destRepo.walkFiles(Integer.MAX_VALUE);
        DestIndex index = new DestIndex();
        Map<String, DestRepo.Node> nodesByPath = new HashMap<>();
        Map<String, DestRepo.Node> folderNodes = new HashMap<>();
        Set<String> foldersTouched = new LinkedHashSet<>();
        List<String> copiedPaths = new ArrayList<>();
        for (DestRepo.Node node : existing) {
            index.add(new DestEntry(node.relPath, node.name, node.size));
            nodesByPath.put(node.relPath.toLowerCase(Locale.ROOT), node);
        }
        folderNodes.put("", destRepo.root());
        log(existing.size() + " existing file" + (existing.size() == 1 ? "" : "s") + " found on the player");

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

        int processed = 0;
        for (SourceItem item : request.sources) {
            checkCancelled();
            try {
                transferOne(sourceRepo, destRepo, request, index, nodesByPath, folderNodes,
                        foldersTouched, copiedPaths, item);
            } catch (CancelledException e) {
                throw e;
            } catch (Throwable t) {
                stats.finishFile(TransferStats.Outcome.FAILED);
                failedSources.add(item);
                log("Failed: " + item.displayName + " — " + describe(t));
            }
            processed++;
            if (processed % PERSIST_EVERY_FILES == 0) {
                PendingTransferStore.save(context, request, processed);
            }
            deliver(false);
        }

        if (request.writePlaylists) {
            writePlaylists(destRepo, index, foldersTouched, folderNodes);
        }
        if (!request.playlistName.isEmpty()) {
            writeNamedPlaylist(destRepo, copiedPaths, request.playlistName);
        }

        checkCancelled();
        phase = PHASE_DONE;
        statusText = buildSummary(false);
        log(statusText);
        PendingTransferStore.clear(context);
        deliver(true);
    }

    private void transferOne(SourceRepo sourceRepo, DestRepo destRepo, Request request, DestIndex index,
                             Map<String, DestRepo.Node> nodesByPath, Map<String, DestRepo.Node> folderNodes,
                             Set<String> foldersTouched, List<String> copiedPaths, SourceItem item)
            throws Exception {
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
                item.sourceFolder, item.treeRelDirs, request.destinationPrefix);
        DestRepo.Node targetDir = destRepo.ensureDir(segments);
        folderNodes.put(targetDir.relPath.toLowerCase(Locale.ROOT), targetDir);

        Uri sourceUri = item.uri;
        String sourceDisplayName = item.displayName;
        long effectiveSize = item.size;
        File convertedFile = null;
        try {
            if (request.convertToMp3
                    && ConversionPolicy.shouldConvert(item.displayName, true, request.convertFormats)) {
                convertedFile = new File(context.getCacheDir(), "convert-" + System.nanoTime() + ".mp3");
                try {
                    log("Converting to MP3: " + item.displayName);
                    boolean converted = AudioTranscoder.convertToMp3(context, item.uri, convertedFile,
                            ConversionPolicy.DEFAULT_BITRATE_KBPS, null);
                    if (converted && convertedFile.length() > 0) {
                        sourceUri = Uri.fromFile(convertedFile);
                        sourceDisplayName = ConversionPolicy.convertedName(item.displayName);
                        effectiveSize = convertedFile.length();
                    } else {
                        log("Conversion produced no audio; copying the original instead");
                    }
                } catch (Throwable conversionError) {
                    log("Conversion failed, copying the original: " + describe(conversionError));
                }
            }
            transferCopyPhase(sourceRepo, destRepo, request, index, nodesByPath, folderNodes,
                    foldersTouched, copiedPaths, item, targetDir, segments, sourceUri,
                    sourceDisplayName, effectiveSize);
        } finally {
            if (convertedFile != null) {
                convertedFile.delete();
            }
        }
    }

    private void transferCopyPhase(SourceRepo sourceRepo, DestRepo destRepo, Request request,
                                   DestIndex index, Map<String, DestRepo.Node> nodesByPath,
                                   Map<String, DestRepo.Node> folderNodes, Set<String> foldersTouched,
                                   List<String> copiedPaths, SourceItem item, DestRepo.Node targetDir,
                                   List<String> segments, Uri sourceUri, String sourceDisplayName,
                                   long effectiveSize) throws Exception {
        String desiredName = FileNameSanitizer.fileName(sourceDisplayName);
        DuplicateCheck duplicate = DuplicateMatcher.check(
                new SourceFileInfo(desiredName, effectiveSize, () -> hashSource(sourceRepo, sourceUri)),
                index,
                request.duplicateMode.exactHash(),
                entry -> {
                    DestRepo.Node node = nodesByPath.get(entry.relPath.toLowerCase(Locale.ROOT));
                    return node == null ? null : destRepo.hash(node);
                },
                request.duplicatesInTargetFolderOnly ? targetDir.relPath : null);

        if (duplicate.isDuplicate()) {
            if (request.duplicateMode.skips()) {
                stats.finishFile(TransferStats.Outcome.DUPLICATE_SKIPPED);
                log("Skipped duplicate: " + item.displayName + " (" + duplicate.describe() + ")");
                return;
            }
            if (request.duplicateMode == DuplicateMode.OVERWRITE) {
                // Protect all existing audio even if the user selected overwrite.
                log("Overwrite not permitted; existing player audio is preserved, saving a new copy");
            }
        }

        CopyResult result;
        try {
            result = copyWithRetry(sourceRepo, destRepo, item, sourceUri, sourceDisplayName,
                    effectiveSize, request.destTree, targetDir, request.verifyAfterCopy);
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            stats.finishFile(TransferStats.Outcome.FAILED);
            failedSources.add(item);
            log("Failed: " + item.displayName + " — " + describe(e));
            return;
        }

        if (result.node == null) {
            stats.finishFile(TransferStats.Outcome.DUPLICATE_SKIPPED);
            log("Identical verified audio is already on the RUIZU: " + item.displayName);
            return;
        }
        stats.finishFile(TransferStats.Outcome.COPIED);
        copiedPaths.add(result.node.relPath);
        index.add(new DestEntry(result.node.relPath, result.node.name, result.bytes));
        nodesByPath.put(result.node.relPath.toLowerCase(Locale.ROOT), result.node);
        foldersTouched.add(targetDir.relPath.toLowerCase(Locale.ROOT));
        if (remainingFreeBytes > 0) {
            remainingFreeBytes = Math.max(0, remainingFreeBytes - result.bytes);
        }
        log("Copied " + result.node.name + " → " + (segments.isEmpty() ? "/" : String.join("/", segments)));
    }

    private CopyResult copyWithRetry(SourceRepo sourceRepo, DestRepo destRepo, SourceItem item,
                                     Uri sourceUri, String sourceDisplayName, long effectiveSize,
                                     Uri destTree, DestRepo.Node targetDir, boolean verify) throws Exception {
        String desiredName = FileNameSanitizer.fileName(sourceDisplayName);
        IOException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            checkCancelled();
            if (attempt > 0) {
                stats.markRetry();
                long delay = RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
                log("Retrying " + item.displayName + " in " + delay + " ms (attempt " + (attempt + 1) + "/" + (MAX_RETRIES + 1) + ")");
                if (!sleep(delay)) {
                    throw new CancelledException();
                }
            }
            String targetName = destRepo.uniqueName(targetDir, desiredName);
            if (!targetName.equals(desiredName) && attempt == 0) {
                stats.markRenamed();
                log("Name already used, saving as: " + targetName);
            }
            try {
                return copyOnce(sourceRepo, destRepo, item, sourceUri, sourceDisplayName, effectiveSize,
                        destTree, targetDir, targetName, verify);
            } catch (CancelledException e) {
                throw e;
            } catch (IOException e) {
                lastError = e;
                if (!isRetryable(e)) {
                    break;
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Copy failed");
    }

    private CopyResult copyOnce(SourceRepo sourceRepo, DestRepo destRepo, SourceItem item,
                                Uri sourceUri, String sourceDisplayName, long effectiveSize,
                                Uri destTree, DestRepo.Node targetDir, String targetName,
                                boolean verify) throws Exception {
        // Verified publishing stages in an owned .part file, closes/reads back with SHA-256,
        // then renames. Existing music, podcasts and .LIB files are never deleted or overwritten.
        checkFreeSpace(effectiveSize);
        final long[] previous = {0L};
        final boolean published;
        try (InputStream in = sourceRepo.open(sourceUri)) {
            published = new SafTransfer(context, () -> cancelRequested, targetDir.docId,
                    false).transfer(in, destTree, targetName, effectiveSize, (bytes, total) -> {
                        stats.addBytes(bytes - previous[0]);
                        previous[0] = bytes;
                        deliver(false);
                    });
        } catch (java.io.InterruptedIOException error) {
            if (cancelRequested) throw new CancelledException();
            throw error;
        } finally {
            destRepo.invalidate();
        }
        if (!published) return new CopyResult(null, 0L);
        DestRepo.Node created = destRepo.findChild(targetDir, targetName);
        if (created == null) {
            throw new IOException("Verified audio was published but the USB provider did not "
                    + "refresh: " + targetName + ". Rescan before trying again.");
        }
        long bytes = created.size >= 0 ? created.size : Math.max(0L, effectiveSize);
        // SafTransfer stages into an owned .part file, reads it back and compares the SHA-256
        // against the source before renaming, so reaching this point proves the bytes on the
        // player match the source. Record it explicitly: the transfer log is the user's
        // evidence that a copy really succeeded, and this line makes it checkable.
        // This always holds - SafTransfer verifies regardless of request.verifyAfterCopy, so
        // that flag is currently advisory and cannot actually disable verification.
        log("SHA-256 verified: " + targetName);
        return new CopyResult(created, bytes);
    }

    private void checkFreeSpace(long neededBytes) throws IOException {
        long free = remainingFreeBytes;
        if (free >= 0 && neededBytes > 0 && neededBytes > free) {
            throw new IOException("Not enough free space: need " + ByteFormat.bytes(neededBytes)
                    + " but only " + ByteFormat.bytes(free) + " is available");
        }
    }

    private static boolean isRetryable(IOException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return !message.contains("not enough free space") && !message.contains("2 gb")
                && !message.contains("https link required");
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is available on every Android runtime", impossible);
        }
    }

    private boolean sleep(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cancelRequested) {
                return false;
            }
            try {
                Thread.sleep(Math.min(100L, Math.max(1L, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !cancelRequested;
    }

    private void writePlaylists(DestRepo destRepo, DestIndex index, Set<String> foldersTouched,
                                Map<String, DestRepo.Node> folderNodes) {
        for (String folderKey : foldersTouched) {
            DestRepo.Node dir = folderNodes.get(folderKey);
            if (dir == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (DestEntry entry : index.filesInFolder(dir.relPath)) {
                names.add(entry.name);
            }
            if (names.isEmpty()) {
                continue;
            }
            String where = dir.relPath.isEmpty() ? "/" : dir.relPath;
            try {
                String content = PlaylistWriter.buildM3u(names);
                DestRepo.Node existing = destRepo.findChild(dir, PlaylistWriter.DEFAULT_NAME);
                if (existing != null) {
                    log("Existing playlist preserved in " + where + "; not overwriting it");
                    continue;
                }
                DestRepo.Node playlist = destRepo.createFile(dir, PlaylistWriter.DEFAULT_NAME,
                        PlaylistWriter.mimeType());
                try (OutputStream out = destRepo.openWrite(playlist)) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                stats.markPlaylistWritten();
                log("Updated playlist " + PlaylistWriter.DEFAULT_NAME + " in " + where);
            } catch (Throwable t) {
                log("Could not write a playlist in " + where + ": " + describe(t));
            }
        }
    }

    private void writeNamedPlaylist(DestRepo destRepo, List<String> copiedPaths, String name) {
        if (copiedPaths.isEmpty()) {
            return;
        }
        String fileName = FileNameSanitizer.fileName(name + ".m3u");
        try {
            DestRepo.Node existing = destRepo.findChild(destRepo.root(), fileName);
            if (existing != null) {
                fileName = destRepo.uniqueName(destRepo.root(), fileName);
                log("Preserved existing playlist; writing " + fileName);
            }
            DestRepo.Node playlist = destRepo.createFile(destRepo.root(), fileName,
                    PlaylistWriter.mimeType());
            try (OutputStream out = destRepo.openWrite(playlist)) {
                out.write(PlaylistWriter.buildM3uFromPaths(copiedPaths)
                        .getBytes(StandardCharsets.UTF_8));
            }
            stats.markPlaylistWritten();
            log("Wrote playlist " + fileName + " (" + copiedPaths.size() + " tracks)");
        } catch (Throwable t) {
            log("Could not write playlist " + fileName + ": " + describe(t));
        }
    }

    private String hashSource(SourceRepo sourceRepo, Uri uri) {
        try (InputStream in = sourceRepo.open(uri)) {
            return HashUtil.sha256Hex(in);
        } catch (Throwable t) {
            log("Could not hash " + uri.getLastPathSegment() + ": " + describe(t));
            return null;
        }
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
        synchronized (logLock) {
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
    }

    private String snapshotLogText() {
        List<String> tail = new ArrayList<>();
        synchronized (logLock) {
            for (String line : logLines) {
                tail.add(line);
            }
        }
        int from = Math.max(0, tail.size() - 80);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tail.size(); i++) {
            sb.append(tail.get(i)).append('\n');
        }
        return sb.toString();
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
                s.getRetriedFiles(), s.getPlaylistsWritten(), failedSources.size(),
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
        if (s.getRetriedFiles() > 0) {
            sb.append(" · ").append(s.getRetriedFiles()).append(" retried");
        }
        if (s.getPlaylistsWritten() > 0) {
            sb.append(" · ").append(s.getPlaylistsWritten()).append(" playlist")
                    .append(s.getPlaylistsWritten() == 1 ? "" : "s");
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
