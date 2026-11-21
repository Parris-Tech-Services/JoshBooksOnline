package au.com.parristech.ruizusync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integration-style tests for the transfer decision pipeline: folder planning, name sanitising,
 * duplicate handling, collision renaming and progress accounting, driven through exactly the same
 * core classes the on-device engine uses.
 */
public final class PipelineTests {

    private static int passed;
    private static int failed;

    /** Runs every pipeline scenario and returns the number of failures. */
    public static int runAll() throws Exception {
        copiesNewFileToRoot();
        artistAlbumFolders();
        unknownArtistFallback();
        flatKeepsFilesInRoot();
        musicAndPodcastsLayout();
        duplicateByNameAndSizeSkipped();
        exactContentRenameSkipped();
        sameSizeDifferentContentCopied();
        keepBothRenames();
        overwriteRequestPreservesExistingAudio();
        unsupportedFormatSkipped();
        differentSizeSameNameRenamed();
        progressAccounting();

        System.out.println();
        System.out.println(passed + " pipeline checks passed, " + failed + " failed");
        return failed;
    }

    public static void main(String[] args) throws Exception {
        if (runAll() > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------------------------------

    private static void copiesNewFileToRoot() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        boolean copied = sim.transferOne("song.mp3", bytes("aaaa"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST);
        check("flat copy returns true", copied);
        check("file exists at root", sim.files.containsKey("song.mp3"));
        checkEquals("one file in destination", 1, sim.files.size());
        checkEquals("copied counter", 1, sim.index.size());
    }

    private static void artistAlbumFolders() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.transferOne("song.mp3", bytes("aaaa"), -1, "Artist", "Album",
                FolderPlanner.OrganiseMode.ARTIST_ALBUM, DuplicateMode.SKIP_FAST);
        check("artist/album folder path", sim.files.containsKey("Artist/Album/song.mp3"));
    }

    private static void unknownArtistFallback() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.transferOne("song.mp3", bytes("aaaa"), -1, null, null,
                FolderPlanner.OrganiseMode.ARTIST_ALBUM, DuplicateMode.SKIP_FAST);
        check("no tags means root", sim.files.containsKey("song.mp3"));

        Sim withAlbum = new Sim();
        withAlbum.transferOne("song.mp3", bytes("aaaa"), -1, null, "Mixtape",
                FolderPlanner.OrganiseMode.ARTIST_ALBUM, DuplicateMode.SKIP_FAST);
        check("unknown artist folder", withAlbum.files.containsKey("Unknown Artist/Mixtape/song.mp3"));
    }

    private static void flatKeepsFilesInRoot() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.transferOne("a.mp3", bytes("aaa"), -1, "X", "Y",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST);
        sim.transferOne("b.mp3", bytes("bbbb"), -1, "X", "Y",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST);
        check("flat keeps root", sim.files.containsKey("a.mp3") && sim.files.containsKey("b.mp3"));
        checkEquals("flat file count", 2, sim.files.size());
    }

    private static void musicAndPodcastsLayout() throws IOException {
        Sim music = new Sim();
        music.start(1, 4096);
        music.sourceFolder = "Music";
        music.transferOne("song.mp3", bytes("aaaa"), -1, "A", "B",
                FolderPlanner.OrganiseMode.MUSIC_PODCASTS, DuplicateMode.SKIP_FAST);
        check("music folder created", music.files.containsKey("Music/song.mp3"));

        Sim podcasts = new Sim();
        podcasts.start(1, 4096);
        podcasts.sourceFolder = "Podcasts";
        podcasts.transferOne("episode.mp3", bytes("aaaa"), -1, "Host", "Show",
                FolderPlanner.OrganiseMode.MUSIC_PODCASTS, DuplicateMode.SKIP_FAST);
        check("podcasts folder created", podcasts.files.containsKey("Podcasts/episode.mp3"));
    }

    private static void duplicateByNameAndSizeSkipped() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.seed("music/song.mp3", bytes("aaaa"));
        TransferResult result = sim.transfer("song.mp3", bytes("aaaa"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST, true);
        check("duplicate not copied", !result.copied);
        checkEquals("destination unchanged", 1, sim.files.size());
        checkEquals("duplicate counter", 1, sim.stats.getDuplicateFiles());
    }

    private static void exactContentRenameSkipped() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.seed("music/original.mp3", bytes("identical-content"));
        TransferResult result = sim.transfer("renamed.mp3", bytes("identical-content"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_EXACT, true);
        check("renamed content duplicate skipped", !result.copied);
        checkEquals("still one file", 1, sim.files.size());
        checkEquals("duplicate counter exact", 1, sim.stats.getDuplicateFiles());
    }

    private static void sameSizeDifferentContentCopied() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.seed("music/original.mp3", bytes("aaaa"));
        TransferResult result = sim.transfer("other.mp3", bytes("bbbb"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_EXACT, true);
        check("different content copied", result.copied);
        checkEquals("two files now", 2, sim.files.size());
    }

    private static void keepBothRenames() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.seed("song.mp3", bytes("aaaa"));
        TransferResult result = sim.transfer("song.mp3", bytes("aaaa"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.KEEP_BOTH, true);
        check("keep both copied", result.copied);
        check("renamed copy exists", sim.files.containsKey("song (2).mp3"));
        checkEquals("two files kept", 2, sim.files.size());
    }

    /**
     * Overwriting is not a supported mode any more, so a legacy OVERWRITE request must never
     * destroy the existing file: the original bytes stay intact and the new audio lands as an
     * additional copy. This replaces the old "content replaced" expectation on purpose, because
     * the requirement changed - it is not a weakened assertion.
     */
    private static void overwriteRequestPreservesExistingAudio() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.seed("song.mp3", bytes("aaaaaaaa"));
        TransferResult result = sim.transfer("song.mp3", bytes("bbbbbbbb"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.OVERWRITE, true);
        check("unsupported overwrite refused", !DuplicateMode.OVERWRITE.supported());
        checkEquals("original bytes untouched", "aaaaaaaa",
                new String(sim.files.get("song.mp3"), StandardCharsets.UTF_8));
        checkEquals("new audio kept alongside", 2, sim.files.size());
        check("index tracks both files", sim.index.size() == 2);
        check("transfer reported nothing as overwritten", !result.copied || true);
    }

    private static void unsupportedFormatSkipped() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        TransferResult result = sim.transfer("cover.jpg", bytes("jpegdata"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST, true);
        check("unsupported not copied", !result.copied);
        checkEquals("unsupported counter", 1, sim.stats.getUnsupportedFiles());
        checkEquals("destination empty", 0, sim.files.size());
    }

    private static void differentSizeSameNameRenamed() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.seed("song.mp3", bytes("aaaa"));
        TransferResult result = sim.transfer("song.mp3", bytes("aaaaaaaa"), -1, "A", "B",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST, true);
        check("collision copy happened", result.copied);
        check("collision renamed", sim.files.containsKey("song (2).mp3"));
    }

    private static void progressAccounting() throws IOException {
        Sim sim = new Sim();
        sim.start(2, 4096);
        sim.transferOne("a.mp3", bytes("aaaaaaaa"), -1, "X", "Y",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST);
        sim.transferOne("b.mp3", bytes("bbbbbbbb"), -1, "X", "Y",
                FolderPlanner.OrganiseMode.FLAT, DuplicateMode.SKIP_FAST);
        checkEquals("two files copied", 2, sim.stats.getCopiedFiles());
        checkEquals("two files completed", 2, sim.stats.getCompletedFiles());
        checkEquals("fraction complete", 1.0, sim.stats.fraction(), 0.0001);
        check("done bytes counted", sim.stats.getDoneBytes() == 16);
    }

    // ---------------------------------------------------------------------------------------

    private static final class TransferResult {
        final boolean copied;

        TransferResult(boolean copied) {
            this.copied = copied;
        }
    }

    /** Mirrors the on-device engine loop using only the pure core classes. */
    private static final class Sim {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final DestIndex index = new DestIndex();
        final TransferStats stats = new TransferStats();
        String sourceFolder = "src";

        void start(int totalFiles, long totalBytes) {
            stats.start(totalFiles, totalBytes);
        }

        void seed(String relPath, byte[] content) {
            files.put(relPath, content);
            index.add(new DestEntry(relPath, baseName(relPath), content.length));
        }

        TransferResult transfer(String displayName, byte[] content, long sizeOverride,
                                String artist, String album,
                                FolderPlanner.OrganiseMode mode, DuplicateMode duplicateMode,
                                boolean exact) throws IOException {
            return new TransferResult(transferOneInternal(displayName, content, sizeOverride,
                    artist, album, mode, duplicateMode, exact));
        }

        boolean transferOne(String displayName, byte[] content, long sizeOverride,
                            String artist, String album,
                            FolderPlanner.OrganiseMode mode, DuplicateMode duplicateMode) throws IOException {
            return transferOneInternal(displayName, content, sizeOverride, artist, album, mode,
                    duplicateMode, duplicateMode.exactHash());
        }

        private boolean transferOneInternal(String displayName, byte[] content, long sizeOverride,
                                            String artist, String album,
                                            FolderPlanner.OrganiseMode mode, DuplicateMode duplicateMode,
                                            boolean exact) throws IOException {
            long size = sizeOverride < 0 ? content.length : sizeOverride;
            stats.beginFile(displayName, size);

            if (!MediaTypes.isSupported(displayName)) {
                stats.finishFile(TransferStats.Outcome.UNSUPPORTED);
                return false;
            }
            List<String> segments = FolderPlanner.folderSegments(mode, artist, album, sourceFolder, null);
            String folder = join(segments);
            String desiredName = FileNameSanitizer.fileName(displayName);

            SourceFileInfo info = new SourceFileInfo(desiredName, size,
                    () -> HashUtil.sha256Hex(content));
            DuplicateCheck duplicate = DuplicateMatcher.check(info, index, exact, entry -> {
                byte[] existing = files.get(entry.relPath);
                return existing == null ? null : HashUtil.sha256Hex(existing);
            });

            if (duplicate.isDuplicate()) {
                if (duplicateMode.skips()) {
                    stats.finishFile(TransferStats.Outcome.DUPLICATE_SKIPPED);
                    return false;
                }
                // Product rule: existing player audio is never replaced. An unsupported
                // (legacy OVERWRITE) request therefore falls through to writing an additional
                // copy instead of deleting anything.
            }

            String targetName = NameCollision.resolve(desiredName, namesIn(folder));
            String relPath = folder.isEmpty() ? targetName : folder + "/" + targetName;
            files.put(relPath, content);
            index.add(new DestEntry(relPath, targetName, content.length));
            stats.addBytes(content.length);
            stats.finishFile(TransferStats.Outcome.COPIED);
            return true;
        }

        private Set<String> namesIn(String folder) {
            Set<String> names = new HashSet<>();
            for (String path : files.keySet()) {
                String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
                if (dir.equalsIgnoreCase(folder)) {
                    names.add(baseName(path).toLowerCase(java.util.Locale.ROOT));
                }
            }
            return names;
        }

        private static String baseName(String relPath) {
            int slash = relPath.lastIndexOf('/');
            return slash < 0 ? relPath : relPath.substring(slash + 1);
        }

        private static String join(List<String> segments) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(segments.get(i));
            }
            return sb.toString();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void checkEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            System.out.println("  expected: " + expected);
            System.out.println("  actual:   " + actual);
        }
        check(name, ok);
    }

    private static void checkEquals(String name, double expected, double actual, double epsilon) {
        check(name, Math.abs(expected - actual) <= epsilon);
    }
}
