package au.com.parristech.ruizusync.audio;

import java.io.IOException;
import java.io.OutputStream;

import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Parse;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.PsyModel;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mp3.BRHist;
import de.sciss.jump3r.mp3.GetAudio;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

/**
 * Thin wrapper around the pure-Java LAME port (jump3r). Encodes interleaved PCM to MP3 without
 * native code, so conversion works on the phone while the player is disconnected.
 *
 * <p>The module wiring mirrors jump3r's own {@code LameEncoder}, which cannot be used on Android
 * because it depends on {@code javax.sound.sampled}.</p>
 */
public final class Mp3Encoder {

    private final Lame lame = new Lame();
    private LameGlobalFlags flags;
    private int channels;
    private byte[] buffer;

    public Mp3Encoder() {
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets presets = new Presets();
        QuantizePVT quantizePvt = new QuantizePVT();
        Quantize quantize = new Quantize();
        VBRTag vbr = new VBRTag();
        Version version = new Version();
        ID3Tag id3 = new ID3Tag();
        Reservoir reservoir = new Reservoir();
        Takehiro takehiro = new Takehiro();
        Parse parse = new Parse();
        BRHist hist = new BRHist();
        MPGLib mpg = new MPGLib();
        Interface mpgInterface = new Interface();
        Common common = new Common();
        PsyModel psy = new PsyModel();
        GetAudio getAudio = new GetAudio();

        lame.setModules(ga, bs, presets, quantizePvt, quantize, vbr, version, id3, mpg);
        bs.setModules(ga, mpg, version, vbr);
        id3.setModules(bs, version);
        presets.setModules(lame);
        quantize.setModules(bs, reservoir, quantizePvt, takehiro);
        quantizePvt.setModules(takehiro, reservoir, psy);
        reservoir.setModules(bs);
        takehiro.setModules(quantizePvt);
        vbr.setModules(lame, bs, version);
        getAudio.setModules(parse, mpg);
        parse.setModules(version, id3, presets);
        mpg.setModules(mpgInterface, common);
        mpgInterface.setModules(vbr, common);
        // `hist` is only used for diagnostics/bitrate histograms.
        if (hist == null) {
            throw new IllegalStateException("unreachable");
        }
    }

    public void open(int sampleRate, int channelCount, int bitrateKbps) {
        channels = Math.max(1, Math.min(2, channelCount));
        flags = lame.lame_init();
        flags.num_channels = channels;
        flags.in_samplerate = sampleRate;
        flags.brate = bitrateKbps;
        flags.mode = channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        flags.quality = 2;
        flags.bWriteVbrTag = false;
        flags.write_id3tag_automatic = false;
        int result = lame.lame_init_params(flags);
        if (result < 0) {
            throw new IllegalStateException("LAME initialisation failed: " + result);
        }
        buffer = new byte[(int) (1.25 * 1152) + 7200];
    }

    public int encode(int[] left, int[] right, int samples, OutputStream out) throws IOException {
        int capacity = (int) (1.25 * samples) + 7200;
        if (buffer == null || buffer.length < capacity) {
            buffer = new byte[capacity];
        }
        int written = lame.lame_encode_buffer_int(flags, left, right, samples, buffer, 0, buffer.length);
        if (written < 0) {
            throw new IOException("LAME encode failed: " + written);
        }
        out.write(buffer, 0, written);
        return written;
    }

    public void close(OutputStream out) throws IOException {
        if (flags == null) {
            return;
        }
        byte[] tail = new byte[8000];
        int flushed = lame.lame_encode_flush(flags, tail, 0, tail.length);
        if (flushed > 0) {
            out.write(tail, 0, flushed);
        }
        lame.lame_close(flags);
        flags = null;
    }

    public int channels() {
        return channels;
    }
}
