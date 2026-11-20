package au.com.parristech.ruizusync.audio;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * On-device smoke test for the conversion pipeline: synthesises a WAV tone, runs it through
 * {@link AudioTranscoder}, and reports whether a plausible MP3 came out. Used by the CI emulator
 * job (intent extra {@code selftest=convert}) and by manual diagnostics.
 */
public final class ConversionSelfTest {

    private ConversionSelfTest() {
    }

    /** @return a short human readable result, e.g. "OK 24 KB". */
    public static String run(Context context) {
        File wav = new File(context.getCacheDir(), "selftest-tone.wav");
        File mp3 = new File(context.getCacheDir(), "selftest-tone.mp3");
        mp3.delete();
        try {
            writeToneWav(wav, 2.0);
            boolean converted = AudioTranscoder.convertToMp3(context, Uri.fromFile(wav), mp3, 128, null);
            long size = mp3.length();
            if (!converted || size <= 1000) {
                return "FAIL (no audio, " + size + " bytes)";
            }
            byte[] head = new byte[2];
            try (java.io.FileInputStream in = new java.io.FileInputStream(mp3)) {
                if (in.read(head) != 2) {
                    return "FAIL (empty output)";
                }
            }
            boolean frameSync = (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
            boolean id3 = head[0] == 'I' && head[1] == 'D';
            return (frameSync || id3 ? "OK " : "FAIL (no MP3 header) ") + (size / 1024) + " KB";
        } catch (Throwable t) {
            return "FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            wav.delete();
        }
    }

    static void writeToneWav(File target, double seconds) throws IOException {
        int sampleRate = 44100;
        int channels = 1;
        int bytesPerSample = 2;
        int frames = (int) (sampleRate * seconds);
        int dataSize = frames * channels * bytesPerSample;
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write("RIFF".getBytes("US-ASCII"));
            writeInt(out, 36 + dataSize);
            out.write("WAVE".getBytes("US-ASCII"));
            out.write("fmt ".getBytes("US-ASCII"));
            writeInt(out, 16);
            writeShort(out, 1); // PCM
            writeShort(out, channels);
            writeInt(out, sampleRate);
            writeInt(out, sampleRate * channels * bytesPerSample);
            writeShort(out, channels * bytesPerSample);
            writeShort(out, bytesPerSample * 8);
            out.write("data".getBytes("US-ASCII"));
            writeInt(out, dataSize);
            byte[] buffer = new byte[8192];
            int written = 0;
            while (written < dataSize) {
                int count = Math.min(buffer.length, dataSize - written);
                for (int i = 0; i < count; i += 2) {
                    int frame = written + i;
                    double value = Math.sin(2 * Math.PI * 440 * frame / (double) (sampleRate * channels));
                    short sample = (short) Math.round(value * 8000);
                    buffer[i] = (byte) (sample & 0xFF);
                    buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
                }
                out.write(buffer, 0, count);
                written += count;
            }
        }
    }

    private static void writeInt(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
