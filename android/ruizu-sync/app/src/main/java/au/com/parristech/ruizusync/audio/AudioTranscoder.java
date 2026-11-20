package au.com.parristech.ruizusync.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import au.com.parristech.ruizusync.core.WavPcmReader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes any Android-supported audio container into PCM with MediaCodec and re-encodes it as MP3
 * with the pure-Java LAME port. Runs entirely on the phone; the original file is untouched.
 */
public final class AudioTranscoder {

    public interface Progress {
        void onProgress(double fraction);
    }

    private static final long TIMEOUT_US = 10000L;

    private AudioTranscoder() {
    }

    /** @return true when an MP3 was written. On failure the partial target file is deleted. */
    public static boolean convertToMp3(Context context, Uri source, File target, int bitrateKbps,
                                       Progress progress) throws Exception {
        if (isWav(context, source)) {
            try (InputStream raw = context.getContentResolver().openInputStream(source)) {
                if (raw == null) {
                    throw new IOException("Cannot open the source file");
                }
                return convertWav(new BufferedInputStream(raw, 64 * 1024), target, bitrateKbps, progress);
            }
        }
        return convertWithMediaCodec(context, source, target, bitrateKbps, progress);
    }

    private static boolean isWav(Context context, Uri source) {
        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) {
                return false;
            }
            byte[] head = new byte[12];
            int read = 0;
            while (read < head.length) {
                int n = raw.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return read == head.length && WavPcmReader.looksLikeWav(head);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean convertWav(InputStream in, File target, int bitrateKbps,
                                      Progress progress) throws Exception {
        WavPcmReader.Format format = WavPcmReader.readFormat(in);
        if (format.bitsPerSample != 16) {
            throw new IOException("Only 16-bit PCM WAV can be converted (found "
                    + format.bitsPerSample + "-bit)");
        }
        Mp3Encoder encoder = new Mp3Encoder();
        encoder.open(format.sampleRate, format.channels, bitrateKbps);
        FileOutputStream out = new FileOutputStream(target);
        long total = format.dataBytes;
        long[] done = {0L};
        boolean complete = false;
        try {
            WavPcmReader.streamPcm(in, format, (buffer, length) -> {
                int frameBytes = format.blockAlign();
                int frames = length / frameBytes;
                int[] left = new int[frames];
                int[] right = format.channels == 1 ? left : new int[frames];
                for (int i = 0; i < frames; i++) {
                    int base = i * frameBytes;
                    int l = (short) ((buffer[base] & 0xFF) | ((buffer[base + 1] & 0xFF) << 8));
                    if (format.channels == 1) {
                        left[i] = l;
                    } else {
                        int r = (short) ((buffer[base + 2] & 0xFF) | ((buffer[base + 3] & 0xFF) << 8));
                        left[i] = l;
                        right[i] = r;
                    }
                }
                if (frames > 0) {
                    encoder.encode(left, right, frames, out);
                }
                done[0] += length;
                if (progress != null && total > 0) {
                    progress.onProgress(Math.min(1.0, (double) done[0] / total));
                }
            });
            encoder.close(out);
            complete = true;
            return true;
        } finally {
            try {
                out.flush();
                out.close();
            } catch (Throwable ignored) {
                // Best effort.
            }
            if (!complete) {
                target.delete();
            }
        }
    }

    private static boolean convertWithMediaCodec(Context context, Uri source, File target,
                                                 int bitrateKbps, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        FileOutputStream out = null;
        Mp3Encoder encoder = null;
        boolean complete = false;
        try {
            extractor.setDataSource(context, source, null);
            int trackIndex = -1;
            MediaFormat trackFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    trackFormat = candidate;
                    break;
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw new IOException("No audio track in this file");
            }
            extractor.selectTrack(trackIndex);
            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            long durationUs = trackFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? trackFormat.getLong(MediaFormat.KEY_DURATION) : 0L;

            if (mime != null && mime.startsWith("audio/raw")) {
                return convertRawPcm(extractor, trackFormat, target, bitrateKbps, progress);
            }

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(trackFormat, null, null, 0);
            decoder.start();

            out = new FileOutputStream(target);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            MediaFormat outputFormat = null;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inIndex);
                        int size = input == null ? -1 : extractor.readSampleData(input, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = decoder.getOutputFormat();
                    encoder = openEncoder(encoder, outputFormat, bitrateKbps);
                    continue;
                }
                if (outIndex < 0) {
                    continue;
                }
                ByteBuffer output = decoder.getOutputBuffer(outIndex);
                if (output != null && info.size > 0) {
                    if (encoder == null) {
                        outputFormat = decoder.getOutputFormat();
                        encoder = openEncoder(null, outputFormat, bitrateKbps);
                    }
                    feed(output, info, outputFormat, encoder, out);
                    if (durationUs > 0 && progress != null) {
                        progress.onProgress(Math.min(1.0, (double) info.presentationTimeUs / durationUs));
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }

            if (encoder == null) {
                throw new IOException("The decoder produced no audio");
            }
            encoder.close(out);
            complete = true;
            return true;
        } finally {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
            } catch (Throwable ignored) {
                // Best effort.
            }
            if (!complete) {
                target.delete();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Throwable ignored) {
                    // Already stopped.
                }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static Mp3Encoder openEncoder(Mp3Encoder existing, MediaFormat format, int bitrateKbps) {
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        Mp3Encoder encoder = existing == null ? new Mp3Encoder() : existing;
        encoder.open(sampleRate, channels, bitrateKbps);
        return encoder;
    }

    private static boolean convertRawPcm(MediaExtractor extractor, MediaFormat format, File target,
                                         int bitrateKbps, Progress progress) throws Exception {
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? Math.max(1, Math.min(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))) : 2;
        Mp3Encoder encoder = new Mp3Encoder();
        encoder.open(sampleRate, channels, bitrateKbps);
        FileOutputStream out = new FileOutputStream(target);
        boolean complete = false;
        try {
            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024).order(ByteOrder.nativeOrder());
            int read;
            while ((read = extractor.readSampleData(buffer, 0)) >= 0) {
                if (read > 0) {
                    buffer.position(0);
                    buffer.limit(read);
                    feedPcm(buffer, read, channels, AudioFormat.ENCODING_PCM_16BIT, encoder, out);
                }
                extractor.advance();
            }
            encoder.close(out);
            complete = true;
            return true;
        } finally {
            try {
                out.flush();
                out.close();
            } catch (Throwable ignored) {
                // Best effort.
            }
            if (!complete) {
                target.delete();
            }
        }
    }

    private static void feed(ByteBuffer pcm, MediaCodec.BufferInfo info, MediaFormat format,
                             Mp3Encoder encoder, FileOutputStream out) throws IOException {
        int encoding = format != null && format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        int channels = format != null && format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? Math.max(1, Math.min(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))) : 2;
        feedPcm(pcm, info.size, channels, encoding, encoder, out, info.offset);
    }

    private static void feedPcm(ByteBuffer pcm, int size, int channels, int encoding,
                                Mp3Encoder encoder, FileOutputStream out) throws IOException {
        feedPcm(pcm, size, channels, encoding, encoder, out, 0);
    }

    private static void feedPcm(ByteBuffer pcm, int size, int channels, int encoding,
                                Mp3Encoder encoder, FileOutputStream out, int offset) throws IOException {
        pcm.position(offset);
        pcm.limit(offset + size);
        final int[] left;
        final int[] right;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            float[] samples = new float[size / 4];
            pcm.order(ByteOrder.nativeOrder()).asFloatBuffer().get(samples);
            int frames = samples.length / channels;
            left = new int[frames];
            right = channels == 1 ? left : new int[frames];
            for (int i = 0; i < frames; i++) {
                if (channels == 1) {
                    left[i] = clampFloat(samples[i]);
                } else {
                    left[i] = clampFloat(samples[i * 2]);
                    right[i] = clampFloat(samples[i * 2 + 1]);
                }
            }
            encoder.encode(left, right, frames, out);
        } else {
            short[] samples = new short[size / 2];
            pcm.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples);
            int frames = samples.length / channels;
            left = new int[frames];
            right = channels == 1 ? left : new int[frames];
            for (int i = 0; i < frames; i++) {
                if (channels == 1) {
                    left[i] = samples[i];
                } else {
                    left[i] = samples[i * 2];
                    right[i] = samples[i * 2 + 1];
                }
            }
            encoder.encode(left, right, frames, out);
        }
    }

    private static int clampFloat(float value) {
        if (value > 1f) {
            return 32767;
        }
        if (value < -1f) {
            return -32768;
        }
        return Math.round(value * 32767f);
    }
}
