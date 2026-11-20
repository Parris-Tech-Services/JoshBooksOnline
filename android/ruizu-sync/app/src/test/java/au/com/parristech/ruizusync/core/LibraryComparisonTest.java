package au.com.parristech.ruizusync.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LibraryComparisonTest {
    private LibraryComparison.Track track(String name, long size, String folder) {
        return new LibraryComparison.Track(name, size, "Artist", "Album", folder);
    }
    @Test public void sameNameAndSizeIsLikelyPresent() {
        LibraryComparison.Result r = LibraryComparison.compare(
            Collections.singletonList(track("Song.mp3", 120, "__Music")),
            Collections.singletonList(new DestEntry("Music/Artist/Song.mp3", "Song.mp3", 120)));
        assertEquals(1, r.likelyPresent);
        assertEquals(0, r.missing);
        assertEquals(LibraryComparison.Status.LIKELY_PRESENT, r.matches.get(0).status);
    }
    @Test public void renamedFormatsArePossibleNotConfirmed() {
        LibraryComparison.Result r = LibraryComparison.compare(
            Arrays.asList(track("Song.mp3", 100, "Music"), track("Talk.m4a", -1, "Podcasts")),
            Arrays.asList(new DestEntry("Music/Song.flac", "Song.flac", 300),
                new DestEntry("Podcasts/Talk.m4a", "Talk.m4a", 200)));
        assertEquals(2, r.possible);
        assertEquals(0, r.likelyPresent);
    }
    @Test public void actualMissingAndSystemLibExcluded() {
        LibraryComparison.Result r = LibraryComparison.compare(
            Arrays.asList(track("Missing.mp3", 123, "Music"),
                track("Present.mp3", 777, "Music")),
            Arrays.asList(new DestEntry("MUSIC.LIB", "MUSIC.LIB", 999),
                new DestEntry("Podcasts/Present.mp3", "Present.mp3", 777)));
        assertEquals(1, r.missing);
        assertEquals(1, r.likelyPresent);
        assertEquals(1, r.playerAudioFiles);
        assertEquals(1, r.playerPodcastFiles);
        assertEquals(1, r.ignoredSystemFiles);
    }
    @Test public void caseInsensitiveAndDuplicatePathsDoNotDoubleCount() {
        LibraryComparison.Result r = LibraryComparison.compare(
            Collections.singletonList(track("SPEECH.MP3", 500, "Podcasts")),
            Arrays.asList(new DestEntry("Podcasts/Speech.mp3", "Speech.mp3", 400),
                new DestEntry("Podcasts/Backup/speech.mp3", "speech.mp3", 500)));
        assertEquals(1, r.likelyPresent);
        assertEquals("Podcasts/Backup/speech.mp3", r.matches.get(0).playerPath);
        assertEquals(2, r.playerAudioFiles);
    }
    @Test public void emptySourceStillReportsPlayerInventory() {
        LibraryComparison.Result r = LibraryComparison.compare(Collections.<LibraryComparison.Track>emptyList(),
            Arrays.asList(new DestEntry("Music/Melody.mp3", "Melody.mp3", 500),
                new DestEntry("Podcasts/Interview.m4a", "Interview.m4a", 111)));
        assertEquals(2, r.playerAudioFiles);
        assertTrue(r.report(10).contains("add files"));
    }
    @Test public void noDestructiveOpsAndHandlesUnknownData() {
        List<DestEntry> files = Collections.singletonList(new DestEntry("Music/A.mp3","A.mp3",-1));
        LibraryComparison.Result r = LibraryComparison.compare(
            Collections.singletonList(track("A.mp3", -1, "")), files);
        assertEquals(1, r.possible);
        assertTrue(r.report(10).contains("does not delete"));
    }
}
