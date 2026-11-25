package au.com.parristech.ruizusync.episode;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class EpisodeCodecTest {
    @Test public void videoFormatsOnly() {
        assertTrue(EpisodeCodec.supported("Episode.MKV"));
        assertTrue(EpisodeCodec.supported("Episode.amv"));
        assertFalse(EpisodeCodec.supported("Episode.mp3"));
        assertFalse(EpisodeCodec.supported("Episode.mp4.exe"));
        assertFalse(EpisodeCodec.supported(null));
    }
    @Test public void argumentsAreLiteralAndProfileMatchesPlayer() {
        List<String> args=EpisodeCodec.encode("ffmpeg","a path;not a shell.mp4","out.amv");
        assertEquals("a path;not a shell.mp4",args.get(args.indexOf("-i")+1));
        assertEquals("amv",args.get(args.indexOf("-c:v")+1));
        assertEquals("adpcm_ima_amv",args.get(args.indexOf("-c:a")+1));
        assertEquals("1470",args.get(args.indexOf("-block_size")+1));
        assertEquals("22050",args.get(args.indexOf("-ar")+1));
        assertTrue(args.contains("-xerror"));
    }
    @Test public void validationRejectsTruncationAndWrongProfile() {
        assertTrue(EpisodeCodec.validProfile("amv",160,128,"adpcm_ima_amv",22050,1,15,900,60));
        assertFalse(EpisodeCodec.validProfile("amv",160,128,"adpcm_ima_amv",22050,1,15,200,60));
        assertFalse(EpisodeCodec.validProfile("amv",320,240,"adpcm_ima_amv",22050,1,15,900,60));
        assertFalse(EpisodeCodec.validProfile("amv",160,128,"mp3",22050,1,15,900,60));
        assertFalse(EpisodeCodec.validProfile("amv",160,128,"adpcm_ima_amv",22050,1,30,900,60));
    }
    @Test public void backoffIsBounded() {
        assertEquals(60000L,EpisodeCodec.retryDelay(1));
        assertEquals(120000L,EpisodeCodec.retryDelay(2));
        assertTrue(EpisodeCodec.retryDelay(100)<=86400000L);
    }
}
