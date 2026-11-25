package au.com.parristech.ruizusync.episode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Shared command contract for the hardware-tested RUIZU AMV format. No shell expansion. */
public final class EpisodeCodec {
    private EpisodeCodec() {}
    public static final String PROFILE = "scale=160:128:force_original_aspect_ratio=decrease,pad=160:128:(ow-iw)/2:(oh-ih)/2:black";
    public static boolean supported(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|avi|mov|m4v|webm|mpg|mpeg|amv)$");
    }
    public static List<String> encode(String executable, String input, String output) {
        return Arrays.asList(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
            "-y", "-i", input, "-map", "0:v:0", "-map", "0:a:0", "-vf", PROFILE,
            "-r", "15", "-c:v", "amv", "-c:a", "adpcm_ima_amv", "-block_size", "1470",
            "-ar", "22050", "-ac", "1", "-threads", "4", "-f", "amv", output);
    }
    public static boolean validProfile(String video, int width, int height, String audio,
                                       int rate, int channels, double fps, long frames, double seconds) {
        return "amv".equals(video) && width == 160 && height == 128 &&
            "adpcm_ima_amv".equals(audio) && rate == 22050 && channels == 1 &&
            Math.abs(fps - 15) < .02 && frames > 0 &&
            (seconds <= 0 || (frames >= seconds * 15 * .95 && frames <= seconds * 15 * 1.05 + 15));
    }
    public static long retryDelay(int attempts) {
        return Math.min(24 * 60 * 60_000L, 60_000L << Math.min(10, Math.max(0, attempts - 1)));
    }
}
