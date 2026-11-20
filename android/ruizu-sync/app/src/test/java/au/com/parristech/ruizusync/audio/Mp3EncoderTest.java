package au.com.parristech.ruizusync.audio;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertTrue;

/** Verifies the pure-Java LAME encoder produces a real MP3 stream on the JVM. */
public class Mp3EncoderTest {

    @Test
    public void encodesPcmToValidMp3() throws Exception {
        Mp3Encoder encoder = new Mp3Encoder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encoder.open(44100, 1, 192);
        int frames = 1152;
        int[] pcm = new int[frames];
        for (int block = 0; block < 40; block++) {
            for (int i = 0; i < frames; i++) {
                int n = block * frames + i;
                pcm[i] = (int) (Math.sin(2 * Math.PI * 440 * n / 44100.0) * 8000);
            }
            encoder.encode(pcm, pcm, frames, out);
        }
        encoder.close(out);

        byte[] mp3 = out.toByteArray();
        assertTrue("MP3 data produced", mp3.length > 5000);
        boolean id3 = mp3.length > 3 && mp3[0] == 'I' && mp3[1] == 'D' && mp3[2] == '3';
        boolean frameSync = (mp3[0] & 0xFF) == 0xFF && (mp3[1] & 0xE0) == 0xE0;
        assertTrue("starts with an MP3 ID3 tag or frame sync", id3 || frameSync);
    }
}
