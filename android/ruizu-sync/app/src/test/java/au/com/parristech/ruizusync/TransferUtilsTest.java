package au.com.parristech.ruizusync;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferUtilsTest {
    @Test public void acceptsAudioCaseInsensitive() {
        assertTrue(TransferUtils.isSupportedAudio("Music.MP3"));
        assertTrue(TransferUtils.isSupportedAudio("chapter.m4b"));
        assertFalse(TransferUtils.isSupportedAudio("chapter.pdf"));
    }
    @Test public void avoidsUnsafePathNames() {
        assertEquals("a_b_c.mp3", TransferUtils.safeName("a/b:c.mp3"));
        assertEquals("track (2).mp3", TransferUtils.withNumber("track.mp3", 2));
    }
    @Test public void longNamesKeepExtensionAndUnicode() {
        String name = TransferUtils.safeName("🎵".repeat(100) + ".mp3");
        assertTrue(name.endsWith(".mp3"));
        assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 180);
        assertFalse(name.contains("\\uFFFD"));
    }
    @Test public void reservedFatNamesAndTrailingDots() {
        assertEquals("_CON.mp3", TransferUtils.safeName("CON.mp3"));
        assertEquals("chapter.mp3", TransferUtils.safeName("chapter.mp3. "));
    }
    @Test public void numberedDuplicateCandidates() {
        assertTrue(TransferUtils.isConflictName("Track.mp3", "track (2).mp3"));
        assertFalse(TransferUtils.isConflictName("Track.mp3", "other (2).mp3"));
    }
    @Test public void streamingLinksNotMistakenForFiles() {
        assertTrue(TransferUtils.isStreamingLink("https://open.spotify.com/track/abc"));
        assertTrue(TransferUtils.isStreamingLink("https://music.youtube.com/watch?v=123"));
        assertFalse(TransferUtils.isStreamingLink("https://example.com/podcast.mp3"));
    }
}
