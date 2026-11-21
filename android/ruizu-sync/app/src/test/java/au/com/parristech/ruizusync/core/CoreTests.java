package au.com.parristech.ruizusync.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plain JVM tests for the pure logic (no Android dependencies). Run with tools/test.sh. */
public final class CoreTests {

    private static int passed;
    private static int failed;

    /** Runs every check and returns the number of failures (0 means all passed). */
    public static int runAll() throws Exception {
        byteFormatting();
        byteFormattingDurations();
        mediaTypes();
        mediaTypesFromMime();
        sharedUriNames();
        sourceBuckets();
        fileNameSanitizer();
        fileNameSanitizerPaths();
        folderPlanner();
        folderPlannerMusicAndPodcasts();
        folderPlannerDestinationPrefix();
        nameCollision();
        playlistWriter();
        playlistWriterPaths();
        opmlImportExport();
        conversionPolicy();
        destIndexBasics();
        destIndexRemove();
        destIndexFolders();
        duplicateMatcherNameAndSize();
        duplicateMatcherExactHash();
        duplicateMatcherScoped();
        duplicateMatcherSkipsHashingWhenNoSizeMatch();
        transferStatsCounters();
        transferStatsUnknownSizes();
        statsRetryAndPlaylistCounters();
        hashing();
        duplicateModeFlags();

        System.out.println();
        System.out.println(passed + " core checks passed, " + failed + " failed");
        return failed;
    }

    public static void main(String[] args) throws Exception {
        if (runAll() > 0) {
            System.exit(1);
        }
    }

    // ---- ByteFormat -----------------------------------------------------------------------

    private static void byteFormatting() {
        checkEquals("bytes 0", "0 B", ByteFormat.bytes(0));
        checkEquals("bytes 512", "512 B", ByteFormat.bytes(512));
        checkEquals("bytes 1024", "1.00 KB", ByteFormat.bytes(1024));
        checkEquals("bytes 1536", "1.50 KB", ByteFormat.bytes(1536));
        checkEquals("bytes 15 KB", "15.0 KB", ByteFormat.bytes(15 * 1024));
        checkEquals("bytes 1 MB", "1.00 MB", ByteFormat.bytes(1024 * 1024));
        checkEquals("bytes 5 GB", "5.00 GB", ByteFormat.bytes(5L * 1024 * 1024 * 1024));
        checkEquals("bytes negative", "--", ByteFormat.bytes(-1));
        checkEquals("speed unknown", "--", ByteFormat.speed(0));
        checkEquals("speed 1 MB", "1.00 MB/s", ByteFormat.speed(1024.0 * 1024.0));
    }

    private static void byteFormattingDurations() {
        checkEquals("duration 0", "0s", ByteFormat.duration(0));
        checkEquals("duration 45", "45s", ByteFormat.duration(45));
        checkEquals("duration 60", "1m 00s", ByteFormat.duration(60));
        checkEquals("duration 90", "1m 30s", ByteFormat.duration(90));
        checkEquals("duration 3661", "1h 01m", ByteFormat.duration(3661));
        checkEquals("duration unknown", "--", ByteFormat.duration(-3));
    }

    // ---- MediaTypes -----------------------------------------------------------------------

    private static void mediaTypes() {
        checkEquals("ext lower", "mp3", MediaTypes.extensionOf("Song.MP3"));
        checkEquals("ext none", "", MediaTypes.extensionOf("noextension"));
        checkEquals("ext dotfile", "", MediaTypes.extensionOf(".hidden"));
        checkEquals("ext multi dot", "flac", MediaTypes.extensionOf("a.b.FLAC"));
        checkEquals("ext null", "", MediaTypes.extensionOf(null));
        check("mp3 supported", MediaTypes.isSupported("track.mp3"));
        check("m4b supported", MediaTypes.isSupported("book.m4b"));
        check("exe not supported", !MediaTypes.isSupported("virus.exe"));
        check("jpg not supported", !MediaTypes.isSupported("cover.jpg"));
        checkEquals("mime mp3", "audio/mpeg", MediaTypes.mimeTypeFor("x.mp3"));
        checkEquals("mime unknown", "application/octet-stream", MediaTypes.mimeTypeFor("x.xyz"));

        boolean hasAudioWildcard = false;
        boolean hasOctetStream = false;
        for (String type : MediaTypes.pickerMimeTypes()) {
            hasAudioWildcard |= "audio/*".equals(type);
            hasOctetStream |= "application/octet-stream".equals(type);
        }
        check("picker includes audio/*", hasAudioWildcard);
        check("picker includes octet-stream", hasOctetStream);
        check("supported list mentions MP3", MediaTypes.supportedList().contains("MP3"));
    }

    private static void mediaTypesFromMime() {
        checkEquals("mime mp3", "mp3", MediaTypes.extensionForMime("audio/mpeg"));
        checkEquals("mime with params", "mp3", MediaTypes.extensionForMime("audio/mpeg; charset=utf-8"));
        checkEquals("mime m4a", "m4a", MediaTypes.extensionForMime("audio/x-m4a"));
        checkEquals("mime ogg app", "ogg", MediaTypes.extensionForMime("application/ogg"));
        checkEquals("mime html", "", MediaTypes.extensionForMime("text/html"));
        checkEquals("mime null", "", MediaTypes.extensionForMime(null));

        checkEquals("name gains extension", "Episode 42.mp3",
                MediaTypes.normalizeAudioName("Episode 42", "audio/mpeg"));
        checkEquals("name keeps extension", "episode.mp3",
                MediaTypes.normalizeAudioName("episode.mp3", "audio/mpeg"));
        checkEquals("name empty uses Audio", "Audio.m4a",
                MediaTypes.normalizeAudioName("", "audio/x-m4a"));
        checkEquals("name unknown type unchanged", "Track 1",
                MediaTypes.normalizeAudioName("Track 1", "text/html"));
        checkEquals("name null safe", "",
                MediaTypes.normalizeAudioName(null, "text/html"));
    }

    // ---- SharedUriNames -------------------------------------------------------------------

    /**
     * Regression cover for the reported "8/8 shared files unsupported, shown as numeric ids".
     *
     * <p>Every case below is a real shape produced when another app shares audio into RUIZU Sync.
     * The bug was that a MediaStore row id was used as the file name, so no extension was found and
     * a perfectly ordinary MP3 was reported as an unsupported format.</p>
     */
    private static void sharedUriNames() {
        check("row id recognised", SharedUriNames.isBareRowId("1000110460"));
        check("short number is a name, not an id", !SharedUriNames.isBareRowId("99"));
        check("mixed text is not an id", !SharedUriNames.isBareRowId("track1"));
        check("empty is not an id", !SharedUriNames.isBareRowId(""));
        check("null is not an id", !SharedUriNames.isBareRowId(null));

        // 1. The grant travelled with the share: the provider's name is the truth.
        SharedUriNames.Resolved named = SharedUriNames.resolve(
                "Blessed Be Your Name.mp3", "audio/mpeg", "1000110460");
        checkEquals("provider name kept", "Blessed Be Your Name.mp3", named.name);
        check("provider name flagged as supplied", named.fromProvider);
        check("provider name supported", named.supported);
        check("provider name not marked reconstructed", !named.reconstructed);

        // 2. A MediaStore audio row with the projection refused (the reported failure): the row id
        //    arrives as the name and the type comes from the provider. This must be transferable.
        SharedUriNames.Resolved mediaId = SharedUriNames.resolve(
                "1000110460", "audio/mpeg", "1000110460");
        check("audio row id becomes a supported name", mediaId.supported);
        check("audio row id is not shown as the bare id", !mediaId.name.equals("1000110460"));
        check("audio row id name keeps traceability", mediaId.name.contains("1000110460"));
        check("audio row id name has the audio extension", mediaId.name.endsWith(".mp3"));
        check("audio row id marked reconstructed", mediaId.reconstructed);

        // 3. No name at all from the sender, only a MIME type.
        SharedUriNames.Resolved noName = SharedUriNames.resolve(null, "audio/x-m4a", "1734");
        checkEquals("mime-only share gets an m4a name", "Shared audio 1734.m4a", noName.name);
        check("mime-only share supported", noName.supported);

        // 4. An opaque Google Drive style document id plus a known type.
        SharedUriNames.Resolved drive = SharedUriNames.resolve(
                null, "audio/flac", "acc=1;doc=encoded=abc123");
        checkEquals("drive doc id gets a flac name", "Shared audio.flac", drive.name);
        check("drive doc id supported", drive.supported);

        // 5. A row id with no type at all: report honestly rather than invent a format.
        SharedUriNames.Resolved unknown = SharedUriNames.resolve("1000110460", null, "1000110460");
        check("unknown type is not claimed supported", !unknown.supported);
        check("unknown type is not invented", !unknown.name.contains(".mp3"));

        // 6. Nothing usable whatsoever.
        SharedUriNames.Resolved nothing = SharedUriNames.resolve(null, "", null);
        checkEquals("nothing resolvable gives no name", "", nothing.name);
        check("nothing resolvable is unsupported", !nothing.supported);

        // 7. A genuine unsupported file keeps its real name so the user can see what was skipped.
        SharedUriNames.Resolved pdf = SharedUriNames.resolve("liner notes.pdf", "application/pdf", "7");
        checkEquals("non-audio name kept verbatim", "liner notes.pdf", pdf.name);
        check("non-audio stays unsupported", !pdf.supported);
        check("non-audio name not reconstructed", !pdf.reconstructed);

        // 8. A numeric name that is really a file name must not be treated as a row id.
        SharedUriNames.Resolved numericName = SharedUriNames.resolve("99.mp3", "audio/mpeg", "99");
        checkEquals("numeric filename kept", "99.mp3", numericName.name);
        check("numeric filename supported", numericName.supported);
    }

    // ---- SourceBuckets --------------------------------------------------------------------

    /** The summary must not call a file "unsupported format" when it is simply unreadable. */
    private static void sourceBuckets() {
        checkEquals("supported audio is supported", SourceBuckets.Bucket.SUPPORTED,
                SourceBuckets.of(true, null));
        checkEquals("supported audio ignores a stale problem", SourceBuckets.Bucket.SUPPORTED,
                SourceBuckets.of(true, "leftover"));
        checkEquals("unknown extension is an unsupported format",
                SourceBuckets.Bucket.UNSUPPORTED_FORMAT, SourceBuckets.of(false, null));
        checkEquals("blank problem is not a problem", SourceBuckets.Bucket.UNSUPPORTED_FORMAT,
                SourceBuckets.of(false, "   "));
        checkEquals("a stated problem is unreadable, not unsupported",
                SourceBuckets.Bucket.UNREADABLE,
                SourceBuckets.of(false, "The sending app shared no readable file name"));
    }

    // ---- FileNameSanitizer ----------------------------------------------------------------

    private static void fileNameSanitizer() {
        checkEquals("slash replaced", "AC-DC", FileNameSanitizer.segment("AC/DC"));
        checkEquals("colon replaced", "a-b", FileNameSanitizer.segment("  a:b  "));
        checkEquals("reserved con", "_CON", FileNameSanitizer.segment("CON"));
        checkEquals("reserved con lower", "_con", FileNameSanitizer.segment("con"));
        checkEquals("trailing dot trimmed", "trail", FileNameSanitizer.segment("trail. "));
        checkEquals("empty", "", FileNameSanitizer.segment(""));
        checkEquals("null", "", FileNameSanitizer.segment(null));
        checkEquals("control chars", "a b", FileNameSanitizer.segment("a\u0001b"));
        checkEquals("unicode kept", "Sigur Rós", FileNameSanitizer.segment("Sigur Rós"));

        String longName = repeat("x", 400);
        check("long segment capped", FileNameSanitizer.segment(longName).length() <= 120);

        checkEquals("filename keeps ext", "song.mp3", FileNameSanitizer.fileName("song.mp3"));
        checkEquals("filename strips path", "song.mp3", FileNameSanitizer.fileName("a/b/song.mp3"));
        checkEquals("filename fallback", "track", FileNameSanitizer.fileName("..."));
        checkEquals("filename reserved", "_CON.mp3", FileNameSanitizer.fileName("CON.mp3"));
        checkEquals("filename bad chars", "a-b.mp3", FileNameSanitizer.fileName("a:b.mp3"));
    }

    private static void fileNameSanitizerPaths() {
        List<String> out = FileNameSanitizer.pathSegments(Arrays.asList("Rock", "", "AC/DC", null, "  "));
        checkEquals("path segments", Arrays.asList("Rock", "AC-DC"), out);
        checkEquals("path null", Collections.<String>emptyList(), FileNameSanitizer.pathSegments(null));
    }

    // ---- FolderPlanner --------------------------------------------------------------------

    private static void folderPlanner() {
        checkEquals("flat", Collections.<String>emptyList(),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.FLAT, "A", "B", "src", null));
        checkEquals("artist album", Arrays.asList("Artist", "Album"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.ARTIST_ALBUM, "Artist", "Album", "", null));
        checkEquals("artist only", Arrays.asList("Artist"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.ARTIST_ALBUM, "Artist", " ", "", null));
        checkEquals("album only", Arrays.asList("Unknown Artist", "Album"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.ARTIST_ALBUM, null, "Album", "", null));
        checkEquals("no tags", Collections.<String>emptyList(),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.ARTIST_ALBUM, null, null, "", null));
        checkEquals("artist sanitised", Arrays.asList("AC-DC", "Live"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.ARTIST_ALBUM, "AC/DC", "Live", "", null));
        checkEquals("source folder", Arrays.asList("My Music"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.SOURCE_FOLDER, null, null, "My Music", null));
        checkEquals("source folder empty", Collections.<String>emptyList(),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.SOURCE_FOLDER, null, null, "", null));

        List<String> deep = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            deep.add("level" + i);
        }
        List<String> kept = FolderPlanner.folderSegments(
                FolderPlanner.OrganiseMode.KEEP_SOURCE_TREE, null, null, "", deep);
        checkEquals("tree depth capped", 8, kept.size());
        checkEquals("tree first segment", "level0", kept.get(0));

        List<String> dirty = new ArrayList<>(Arrays.asList("Pod/2019", "  ", "Ep:1"));
        checkEquals("tree sanitised", Arrays.asList("Pod-2019", "Ep-1"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.KEEP_SOURCE_TREE, null, null, "", dirty));
    }

    private static void folderPlannerMusicAndPodcasts() {
        checkEquals("music mode for podcasts source", Arrays.asList("Podcasts"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.MUSIC_PODCASTS,
                        null, null, "Podcasts", null));
        checkEquals("music mode case insensitive", Arrays.asList("Podcasts"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.MUSIC_PODCASTS,
                        null, null, "podcasts", null));
        checkEquals("music mode for music source", Arrays.asList("Music"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.MUSIC_PODCASTS,
                        null, null, "Music", null));
        checkEquals("music mode default", Arrays.asList("Music"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.MUSIC_PODCASTS,
                        null, null, "", null));
        checkEquals("music mode ignores tags", Arrays.asList("Music"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.MUSIC_PODCASTS,
                        "Artist", "Album", null, null));
    }

    private static void folderPlannerDestinationPrefix() {
        checkEquals("prefix + flat", Arrays.asList("Music", "Audiobooks"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.FLAT, null, null, "", null,
                        Arrays.asList("Music", "Audiobooks")));
        checkEquals("prefix sanitised", Arrays.asList("Music", "Audio-books"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.FLAT, null, null, "", null,
                        Arrays.asList("Music", "Audio:books")));
        checkEquals("empty prefix ignored", Arrays.asList("Artist", "Album"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.ARTIST_ALBUM, "Artist", "Album",
                        "", null, Collections.<String>emptyList()));
        checkEquals("null prefix ignored", Collections.<String>emptyList(),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.FLAT, null, null, "", null, null));
        checkEquals("prefix + artist album", Arrays.asList("Podcasts", "Artist", "Album"),
                FolderPlanner.folderSegments(FolderPlanner.OrganiseMode.ARTIST_ALBUM, "Artist", "Album",
                        "", null, Collections.singletonList("Podcasts")));
    }

    private static void playlistWriterPaths() {
        String playlist = PlaylistWriter.buildM3uFromPaths(
                Arrays.asList("Rock/Album/01.mp3", "Podcasts/ep1.mp3"));
        check("relpath playlist header", playlist.startsWith("#EXTM3U\n"));
        check("relpath keeps folder", playlist.contains("Rock/Album/01.mp3\n"));
        check("relpath title from base name", playlist.contains("#EXTINF:-1,01\n"));
        check("relpath podcasts", playlist.contains("Podcasts/ep1.mp3\n"));
        checkEquals("relpath empty", "#EXTM3U\n",
                PlaylistWriter.buildM3uFromPaths(Collections.<String>emptyList()));
        checkEquals("relpath null", "#EXTM3U\n", PlaylistWriter.buildM3uFromPaths(null));
        String skipped = PlaylistWriter.buildM3uFromPaths(Arrays.asList("", null, "a/b.mp3"));
        check("relpath skips blanks", skipped.contains("a/b.mp3") && skipped.split("\n").length == 3);
    }

    private static void opmlImportExport() {
        String opml = "<?xml version=\"1.0\"?><opml version=\"2.0\"><body>"
                + "<outline text=\"Show A\" title=\"Show A\" type=\"rss\" xmlUrl=\"https://a.example/feed\"/>"
                + "<outline text=\"Group\">"
                + "<outline text=\"Show B\" xmlUrl='https://b.example/feed'/>"
                + "</outline>"
                + "<outline text=\"No url\"/>"
                + "</body></opml>";
        List<Opml.Feed> feeds = Opml.parse(opml);
        checkEquals("opml feed count", 2, feeds.size());
        checkEquals("opml title", "Show A", feeds.get(0).title);
        checkEquals("opml url", "https://a.example/feed", feeds.get(0).url);
        checkEquals("opml single quotes", "https://b.example/feed", feeds.get(1).url);

        String generated = Opml.generate(feeds);
        check("opml generated header", generated.contains("<opml version=\"2.0\">"));
        check("opml generated url", generated.contains("xmlUrl=\"https://a.example/feed\""));
        List<Opml.Feed> roundTrip = Opml.parse(generated);
        checkEquals("opml round trip", 2, roundTrip.size());
        checkEquals("opml round trip title", "Show B", roundTrip.get(1).title);
        check("opml detects document", Opml.looksLikeOpml(generated));
        check("opml reject html", !Opml.looksLikeOpml("<html></html>"));
        checkEquals("opml escapes title", true,
                Opml.generate(Collections.singletonList(new Opml.Feed("A & B", "https://x/y")))
                        .contains("A &amp; B"));
        checkEquals("opml empty", 0, Opml.parse("").size());
        checkEquals("opml null", 0, Opml.parse(null).size());
    }

    private static void conversionPolicy() {
        check("convert ogg when enabled", ConversionPolicy.shouldConvert("song.ogg", true, null));
        check("never convert mp3", !ConversionPolicy.shouldConvert("song.mp3", true, null));
        check("no convert when disabled", !ConversionPolicy.shouldConvert("song.flac", false, null));
        check("convert only listed", ConversionPolicy.shouldConvert("song.ogg", true,
                Collections.singleton("ogg")));
        check("skip unlisted", !ConversionPolicy.shouldConvert("song.flac", true,
                Collections.singleton("ogg")));
        check("no extension never converts", !ConversionPolicy.shouldConvert("noext", true, null));
        checkEquals("converted name", "song.mp3", ConversionPolicy.convertedName("song.ogg"));
        checkEquals("converted name keeps base", "My Song.mp3",
                ConversionPolicy.convertedName("My Song.flac"));
        check("lossy format list has ogg", ConversionPolicy.LOSSY_OR_ODD_FORMATS.contains("ogg"));
        checkEquals("describe formats", "OGG, FLAC",
                ConversionPolicy.describe(new java.util.LinkedHashSet<>(Arrays.asList("ogg", "flac"))));
    }

    // ---- NameCollision --------------------------------------------------------------------

    private static void nameCollision() {
        Set<String> names = new HashSet<>();
        names.add("song.mp3");
        checkEquals("no collision", "other.mp3",
                NameCollision.resolve("other.mp3", names));
        checkEquals("collision suffixed", "song (2).mp3",
                NameCollision.resolve("song.mp3", names));
        names.add("song (2).mp3");
        checkEquals("collision twice", "song (3).mp3",
                NameCollision.resolve("song.mp3", names));

        Set<String> upper = new HashSet<>();
        upper.add("song.mp3"); // DestIndex keeps existing names lower-cased
        checkEquals("collision case insensitive", "SONG (2).MP3",
                NameCollision.resolve("SONG.MP3", upper));

        Set<String> noExt = new HashSet<>();
        noExt.add("readme");
        checkEquals("collision without ext", "readme (2)",
                NameCollision.resolve("readme", noExt));
        checkEquals("empty names", "x.mp3", NameCollision.resolve("x.mp3", Collections.<String>emptySet()));
        checkEquals("null names", "x.mp3", NameCollision.resolve("x.mp3", null));
    }

    // ---- DestIndex ------------------------------------------------------------------------

    private static void destIndexBasics() {
        DestIndex index = new DestIndex();
        checkEquals("index starts empty", 0, index.size());
        checkEquals("same size on empty", 0, index.sameSize(100).size());

        index.add(new DestEntry("Music/a.mp3", "a.mp3", 100));
        index.add(new DestEntry("root.mp3", "root.mp3", 200));
        checkEquals("index size", 2, index.size());
        checkEquals("same size hit", 1, index.sameSize(100).size());
        checkEquals("folder path", "Music", index.sameSize(100).get(0).folderRelPath);
        checkEquals("root folder path", "", index.sameSize(200).get(0).folderRelPath);
        check("names in folder", index.namesInFolder("music").contains("a.mp3"));
        check("names lower case storage", index.namesInFolder("Music").contains("a.mp3"));
        check("names in root", index.namesInFolder("").contains("root.mp3"));
        check("at path", index.at("music/A.MP3") != null);
        checkEquals("unknown size has no candidates", 0, index.sameSize(-1).size());
    }

    private static void destIndexRemove() {
        DestIndex index = new DestIndex();
        DestEntry a = new DestEntry("Music/a.mp3", "a.mp3", 100);
        index.add(a);
        index.add(new DestEntry("Music/b.mp3", "b.mp3", 100));

        index.remove("music/A.MP3");
        checkEquals("remove drops path", 1, index.size());
        checkEquals("remove updates size bucket", 1, index.sameSize(100).size());
        check("remove updates names", !index.namesInFolder("Music").contains("a.mp3"));
        check("remove keeps siblings", index.namesInFolder("Music").contains("b.mp3"));
        check("remove is idempotent", index.at("missing") == null);

        index.remove("nothing/here.mp3");
        checkEquals("remove unknown path is safe", 1, index.size());

        index.remove("Music/b.mp3");
        checkEquals("remove last entry", 0, index.size());
        checkEquals("last size bucket cleared", 0, index.sameSize(100).size());
    }

    private static void destIndexFolders() {
        DestIndex index = new DestIndex();
        index.add(new DestEntry("Music/b.mp3", "b.mp3", 100));
        index.add(new DestEntry("Music/a.mp3", "a.mp3", 100));
        index.add(new DestEntry("Podcasts/c.mp3", "c.mp3", 100));
        index.add(new DestEntry("root.mp3", "root.mp3", 100));

        checkEquals("filesInFolder sorted", Arrays.asList("a.mp3", "b.mp3"),
                names(index.filesInFolder("Music")));
        checkEquals("filesInFolder case-insensitive", 2, index.filesInFolder("music").size());
        checkEquals("filesInFolder podcasts", 1, index.filesInFolder("Podcasts").size());
        checkEquals("filesInFolder root", 1, index.filesInFolder("").size());
        checkEquals("filesInFolder unknown", 0, index.filesInFolder("Nope").size());

        checkEquals("sameSizeInFolder", 2, index.sameSizeInFolder(100, "Music").size());
        checkEquals("sameSizeInFolder root", 1, index.sameSizeInFolder(100, "").size());
        checkEquals("sameSizeInFolder unknown", 0, index.sameSizeInFolder(100, "Nope").size());
    }

    private static List<String> names(List<DestEntry> entries) {
        List<String> names = new ArrayList<>();
        for (DestEntry entry : entries) {
            names.add(entry.name);
        }
        return names;
    }

    private static void duplicateMatcherScoped() throws IOException {
        DestIndex index = new DestIndex();
        index.add(new DestEntry("Music/a.mp3", "a.mp3", 100));

        DuplicateCheck wholePlayer = DuplicateMatcher.check(
                new SourceFileInfo("a.mp3", 100, null), index, false, null);
        check("scoped: whole player finds duplicate", wholePlayer.isDuplicate());

        DuplicateCheck otherFolder = DuplicateMatcher.check(
                new SourceFileInfo("a.mp3", 100, null), index, false, null, "Podcasts");
        check("scoped: other folder is not duplicate", !otherFolder.isDuplicate());

        DuplicateCheck sameFolder = DuplicateMatcher.check(
                new SourceFileInfo("a.mp3", 100, null), index, false, null, "Music");
        check("scoped: same folder is duplicate", sameFolder.isDuplicate());

        DuplicateCheck rootFolder = DuplicateMatcher.check(
                new SourceFileInfo("a.mp3", 100, null), index, false, null, "");
        check("scoped: root is not duplicate", !rootFolder.isDuplicate());
    }

    private static void playlistWriter() {
        String playlist = PlaylistWriter.buildM3u(Arrays.asList("Track 01.mp3", "Track 02.flac"));
        check("playlist header", playlist.startsWith("#EXTM3U\n"));
        check("playlist extinf", playlist.contains("#EXTINF:-1,Track 01\n"));
        check("playlist entry one", playlist.contains("Track 01.mp3\n"));
        check("playlist entry two", playlist.contains("Track 02.flac\n"));
        checkEquals("playlist display name", "Track 01", PlaylistWriter.displayName("Track 01.mp3"));
        checkEquals("playlist empty", "#EXTM3U\n", PlaylistWriter.buildM3u(Collections.<String>emptyList()));
        checkEquals("playlist null safe", "#EXTM3U\n", PlaylistWriter.buildM3u(null));
        checkEquals("playlist mime", "audio/x-mpegurl", PlaylistWriter.mimeType());
    }

    private static void statsRetryAndPlaylistCounters() {
        TransferStats stats = new TransferStats();
        stats.start(1, 10);
        checkEquals("retries start at zero", 0, stats.getRetriedFiles());
        stats.markRetry();
        stats.markRetry();
        checkEquals("retry counter", 2, stats.getRetriedFiles());
        stats.markPlaylistWritten();
        checkEquals("playlist counter", 1, stats.getPlaylistsWritten());

        TransferStats verify = new TransferStats();
        verify.start(1, 10);
        verify.beginFile("a.mp3", 10);
        verify.finishFile(TransferStats.Outcome.VERIFY_FAILED);
        checkEquals("verify failure counts as failed", 1, verify.getFailedFiles());
        checkEquals("verify failure completes the file", 1, verify.getCompletedFiles());
    }

    // ---- DuplicateMatcher -----------------------------------------------------------------

    private static void duplicateMatcherNameAndSize() throws IOException {
        DestIndex index = new DestIndex();
        DestEntry existing = new DestEntry("Music/a.mp3", "a.mp3", 100);
        index.add(existing);

        DuplicateCheck sameName = DuplicateMatcher.check(
                new SourceFileInfo("a.mp3", 100, () -> {
                    throw new AssertionError("hash must not be computed for name+size mode");
                }), index, false, null);
        check("name+size duplicate", sameName.isDuplicate());
        checkEquals("reason name+size", DuplicateCheck.Reason.SAME_NAME_AND_SIZE, sameName.reason);
        checkEquals("match path", "Music/a.mp3", sameName.match.relPath);

        DuplicateCheck caseInsensitive = DuplicateMatcher.check(
                new SourceFileInfo("A.MP3", 100, null), index, false, null);
        check("name match is case insensitive", caseInsensitive.isDuplicate());

        DuplicateCheck differentSize = DuplicateMatcher.check(
                new SourceFileInfo("a.mp3", 101, null), index, false, null);
        check("different size is not duplicate", !differentSize.isDuplicate());

        DuplicateCheck differentNameSameSizeFast = DuplicateMatcher.check(
                new SourceFileInfo("b.mp3", 100, null), index, false, null);
        check("different name fast mode not duplicate", !differentNameSameSizeFast.isDuplicate());

        DuplicateCheck unknownSize = DuplicateMatcher.check(
                new SourceFileInfo("a.mp3", -1, null), index, false, null);
        check("unknown size is not duplicate", !unknownSize.isDuplicate());
    }

    private static void duplicateMatcherExactHash() throws IOException {
        DestIndex index = new DestIndex();
        DestEntry existing = new DestEntry("Old/a.mp3", "a.mp3", 100);
        index.add(existing);
        final String hash = "deadbeef";
        final int[] calls = {0};

        DestHasher hasher = entry -> {
            calls[0]++;
            return hash;
        };

        DuplicateCheck sameContent = DuplicateMatcher.check(
                new SourceFileInfo("renamed.mp3", 100, () -> hash), index, true, hasher);
        check("exact hash duplicate", sameContent.isDuplicate());
        checkEquals("reason content", DuplicateCheck.Reason.SAME_CONTENT, sameContent.reason);
        check("destination hashed once", calls[0] == 1);
        checkEquals("hash cached", hash, existing.hash());

        DuplicateCheck mismatch = DuplicateMatcher.check(
                new SourceFileInfo("renamed2.mp3", 100, () -> "cafebabe"), index, true, hasher);
        check("hash mismatch not duplicate", !mismatch.isDuplicate());

        DuplicateCheck unavailable = DuplicateMatcher.check(
                new SourceFileInfo("renamed3.mp3", 100, () -> null), index, true, hasher);
        check("null source hash not duplicate", !unavailable.isDuplicate());
    }

    private static void duplicateMatcherSkipsHashingWhenNoSizeMatch() throws IOException {
        DestIndex index = new DestIndex();
        index.add(new DestEntry("Music/a.mp3", "a.mp3", 100));
        final int[] calls = {0};
        DestHasher hasher = entry -> {
            calls[0]++;
            return "x";
        };
        DuplicateMatcher.check(new SourceFileInfo("a.mp3", 999, () -> {
            throw new AssertionError("no size match means no hashing");
        }), index, true, hasher);
        check("dest hasher not used for other sizes", calls[0] == 0);
    }

    // ---- TransferStats --------------------------------------------------------------------

    private static void transferStatsCounters() {
        TransferStats stats = new TransferStats();
        stats.start(2, 1000);
        checkEquals("initial fraction", 0.0, stats.fraction(), 0.0001);

        stats.beginFile("one.mp3", 400);
        stats.addBytes(400);
        stats.finishFile(TransferStats.Outcome.COPIED);
        checkEquals("after first file fraction", 0.4, stats.fraction(), 0.0001);
        checkEquals("copied count", 1, stats.getCopiedFiles());
        checkEquals("completed count", 1, stats.getCompletedFiles());

        stats.beginFile("two.mp3", 600);
        stats.finishFile(TransferStats.Outcome.DUPLICATE_SKIPPED);
        checkEquals("duplicate count", 1, stats.getDuplicateFiles());
        checkEquals("finished fraction", 1.0, stats.fraction(), 0.0001);
        check("eta zero when done", stats.etaSeconds() == 0);
    }

    private static void transferStatsUnknownSizes() {
        TransferStats stats = new TransferStats();
        stats.start(4, 0);
        stats.beginFile("a.mp3", -1);
        stats.finishFile(TransferStats.Outcome.COPIED);
        checkEquals("file based fraction", 0.25, stats.fraction(), 0.0001);
        stats.beginFile("b.mp3", -1);
        stats.finishFile(TransferStats.Outcome.UNSUPPORTED);
        stats.beginFile("c.mp3", -1);
        stats.finishFile(TransferStats.Outcome.FAILED);
        checkEquals("unsupported count", 1, stats.getUnsupportedFiles());
        checkEquals("failed count", 1, stats.getFailedFiles());
        check("eta unknown without byte totals", stats.etaSeconds() == -1);

        stats.beginFile("d.mp3", -1);
        stats.finishFile(TransferStats.Outcome.DRM_BLOCKED);
        checkEquals("drm count", 1, stats.getDrmFiles());
        checkEquals("all complete", 1.0, stats.fraction(), 0.0001);
        checkEquals("current file cleared", "", stats.getCurrentFileName());
    }

    // ---- Hashing --------------------------------------------------------------------------

    private static void hashing() throws IOException {
        checkEquals("sha256 empty", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HashUtil.sha256Hex(new byte[0]));
        byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        checkEquals("sha256 abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HashUtil.sha256Hex(abc));
        checkEquals("streaming equals array",
                HashUtil.sha256Hex(abc),
                HashUtil.sha256Hex(new ByteArrayInputStream(abc)));

        byte[] big = new byte[200 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        checkEquals("streaming equals array (large)",
                HashUtil.sha256Hex(big),
                HashUtil.sha256Hex(new ByteArrayInputStream(big)));
    }

    // ---- DuplicateMode --------------------------------------------------------------------

    private static void duplicateModeFlags() {
        check("fast skips", DuplicateMode.SKIP_FAST.skips());
        check("exact skips", DuplicateMode.SKIP_EXACT.skips());
        check("fast no hash", !DuplicateMode.SKIP_FAST.exactHash());
        check("exact hashes", DuplicateMode.SKIP_EXACT.exactHash());
        check("overwrite not skip", !DuplicateMode.OVERWRITE.skips());
        check("keep both not skip", !DuplicateMode.KEEP_BOTH.skips());
        check("label stable", DuplicateMode.OVERWRITE.label.contains("Overwrite"));
        check("overwrite unsupported", !DuplicateMode.OVERWRITE.supported());
        check("keep both supported", DuplicateMode.KEEP_BOTH.supported());
        check("selectable hides overwrite", !DuplicateMode.selectable().contains(DuplicateMode.OVERWRITE));
        checkEquals("selectable count", 3, DuplicateMode.selectable().size());
        checkEquals("legacy overwrite migrates to exact skip", DuplicateMode.SKIP_EXACT,
                DuplicateMode.safe(DuplicateMode.OVERWRITE));
        checkEquals("null migrates to exact skip", DuplicateMode.SKIP_EXACT, DuplicateMode.safe(null));
        checkEquals("supported mode preserved", DuplicateMode.KEEP_BOTH,
                DuplicateMode.safe(DuplicateMode.KEEP_BOTH));
        checkEquals("stored legacy name migrates", DuplicateMode.SKIP_EXACT,
                DuplicateMode.fromStoredName("OVERWRITE", DuplicateMode.SKIP_FAST));
        checkEquals("unknown stored name falls back", DuplicateMode.SKIP_FAST,
                DuplicateMode.fromStoredName("WHATEVER", DuplicateMode.SKIP_FAST));
        // The UI selects by adapter position, so selectable() order and contents are a contract.
        checkEquals("selectable order 0", DuplicateMode.SKIP_FAST, DuplicateMode.selectable().get(0));
        checkEquals("selectable order 1", DuplicateMode.SKIP_EXACT, DuplicateMode.selectable().get(1));
        checkEquals("selectable order 2", DuplicateMode.KEEP_BOTH, DuplicateMode.selectable().get(2));
        checkEquals("keep both adapter index", 2, DuplicateMode.selectable().indexOf(DuplicateMode.KEEP_BOTH));
        checkEquals("skip exact adapter index", 1, DuplicateMode.selectable().indexOf(DuplicateMode.SKIP_EXACT));
        check("safe is idempotent", DuplicateMode.safe(DuplicateMode.safe(DuplicateMode.OVERWRITE))
                == DuplicateMode.SKIP_EXACT);
    }

    // ---- tiny assertion helpers -----------------------------------------------------------

    private static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
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
