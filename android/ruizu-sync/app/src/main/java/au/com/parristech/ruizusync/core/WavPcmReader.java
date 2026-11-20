package au.com.parristech.ruizusync.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RIFF/WAVE reader for PCM audio, used to convert WAV files to MP3 without relying on
 * the platform extractor (raw PCM extraction support varies by device/firmware). Pure Java, unit
 * tested.
 */
public final class WavPcmReader {

    public interface PcmConsumer {
        void onPcm(byte[] buffer, int length) throws IOException;
    }

    public static final class Format {
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;
        /** Data-chunk size in bytes, or -1 when the writer did not declare it. */
        public final long dataBytes;

        Format(int sampleRate, int channels, int bitsPerSample, long dataBytes) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.dataBytes = dataBytes;
        }

        public int blockAlign() {
            return channels * (Math.max(8, bitsPerSample) / 8);
        }
    }

    private WavPcmReader() {
    }

    public static boolean looksLikeWav(byte[] header) {
        return header != null && header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
    }

    /**
     * Parses chunk headers up to the {@code data} chunk. The stream is positioned at the first
     * sample byte when this returns.
     */
    public static Format readFormat(InputStream in) throws IOException {
        byte[] riff = readExactly(in, 12);
        if (!looksLikeWav(riff)) {
            throw new IOException("Not a WAV file");
        }
        int sampleRate = -1;
        int channels = -1;
        int bits = -1;
        boolean pcm = false;
        while (true) {
            byte[] header = readExactly(in, 8);
            String id = new String(header, 0, 4, StandardCharsets.US_ASCII);
            long size = le32(header, 4);
            if ("fmt ".equals(id)) {
                int length = (int) Math.min(size, 64L);
                byte[] fmt = readExactly(in, length);
                pcm = le16(fmt, 0) == 1;
                channels = le16(fmt, 2);
                sampleRate = (int) le32(fmt, 4);
                bits = le16(fmt, 14);
                if (size > length) {
                    skipFully(in, size - length);
                }
                if (size % 2 == 1) {
                    skipFully(in, 1);
                }
                continue;
            }
            if ("data".equals(id)) {
                if (!pcm) {
                    throw new IOException("Only PCM WAV can be converted");
                }
                if (sampleRate <= 0 || channels <= 0 || bits <= 0) {
                    throw new IOException("WAV format header is missing");
                }
                return new Format(sampleRate, channels, bits, size == 0xFFFFFFFFL ? -1L : size);
            }
            skipFully(in, size + (size % 2));
        }
    }

    /** Streams 16-bit PCM in whole frames to the consumer. */
    public static void streamPcm(InputStream in, Format format, PcmConsumer consumer) throws IOException {
        int frameBytes = format.blockAlign();
        if (frameBytes <= 0) {
            throw new IOException("Invalid WAV frame size");
        }
        int framesPerChunk = Math.max(1, (64 * 1024) / frameBytes);
        byte[] buffer = new byte[framesPerChunk * frameBytes];
        long remaining = format.dataBytes;
        while (remaining != 0) {
            int want = buffer.length;
            if (remaining > 0) {
                want = (int) Math.min(buffer.length, remaining);
                want -= want % frameBytes;
                if (want == 0) {
                    break;
                }
            }
            int read = readUpTo(in, buffer, want);
            if (read <= 0) {
                break;
            }
            consumer.onPcm(buffer, read);
            if (remaining > 0) {
                remaining -= read;
            }
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = readUpTo(in, data, length);
        if (read != length) {
            throw new EOFException("Unexpected end of WAV file");
        }
        return data;
    }

    private static int readUpTo(InputStream in, byte[] buffer, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, total, length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] scratch = new byte[4096];
        while (remaining > 0) {
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of WAV file");
            }
            remaining -= read;
        }
    }

    static int le16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    static long le32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }
}
