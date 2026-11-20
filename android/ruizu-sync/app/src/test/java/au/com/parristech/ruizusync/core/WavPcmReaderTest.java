package au.com.parristech.ruizusync.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for the dependency-free WAV parser used by the MP3 conversion path. */
public class WavPcmReaderTest {

    @Test
    public void readsFormatAndStreamsPcm() throws IOException {
        byte[] wav = makeWav(44100, 1, 16, 1000);
        WavPcmReader.Format format = WavPcmReader.readFormat(new ByteArrayInputStream(wav));
        assertEquals(44100, format.sampleRate);
        assertEquals(1, format.channels);
        assertEquals(16, format.bitsPerSample);
        assertEquals(2000L, format.dataBytes);

        final int[] bytes = {0};
        final int[] chunks = {0};
        WavPcmReader.streamPcm(new ByteArrayInputStream(wav), format, (buffer, length) -> {
            bytes[0] += length;
            chunks[0]++;
        });
        assertEquals(2000, bytes[0]);
        assertTrue(chunks[0] >= 1);
    }

    @Test
    public void handlesStereo() throws IOException {
        byte[] wav = makeWav(22050, 2, 16, 500);
        WavPcmReader.Format format = WavPcmReader.readFormat(new ByteArrayInputStream(wav));
        assertEquals(2, format.channels);
        assertEquals(22050, format.sampleRate);
        assertEquals(4, format.blockAlign());
        final int[] bytes = {0};
        WavPcmReader.streamPcm(new ByteArrayInputStream(wav), format, (buffer, length) -> bytes[0] += length);
        assertEquals(2000, bytes[0]);
    }

    @Test
    public void rejectsNonPcmWav() {
        byte[] wav = makeWav(44100, 1, 16, 10);
        wav[20] = 3; // audio format 3 = IEEE float
        try {
            WavPcmReader.readFormat(new ByteArrayInputStream(wav));
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("pcm"));
        }
    }

    @Test
    public void looksLikeWavChecksRiffMagic() {
        byte[] wav = makeWav(44100, 1, 16, 1);
        assertTrue(WavPcmReader.looksLikeWav(wav));
        byte[] notWav = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' '};
        assertFalse(WavPcmReader.looksLikeWav(notWav));
        assertFalse(WavPcmReader.looksLikeWav(null));
    }

    // ---- helpers ---------------------------------------------------------------------------

    static byte[] makeWav(int sampleRate, int channels, int bits, int frames) {
        int dataSize = frames * channels * (bits / 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeInt(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 1);
        writeShort(out, channels);
        writeInt(out, sampleRate);
        writeInt(out, sampleRate * channels * bits / 8);
        writeShort(out, channels * bits / 8);
        writeShort(out, bits);
        writeAscii(out, "data");
        writeInt(out, dataSize);
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                short sample = (short) ((i * 37 + c * 11) % 8000);
                out.write(sample & 0xFF);
                out.write((sample >> 8) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        for (int i = 0; i < text.length(); i++) {
            out.write(text.charAt(i));
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
