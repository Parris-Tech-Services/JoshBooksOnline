package au.com.parristech.ruizusync.core;

import java.util.List;

/**
 * Builds M3U playlists, which RUIZU players read directly. Pure Java, unit tested.
 *
 * <p>Paths are written relative to the playlist file so the player can relocate its storage.</p>
 */
public final class PlaylistWriter {

    public static final String DEFAULT_NAME = "RUIZU Sync.m3u";
    private static final String MIME_TYPE = "audio/x-mpegurl";

    private PlaylistWriter() {
    }

    public static String mimeType() {
        return MIME_TYPE;
    }

    /**
     * @param fileNames files in the same folder as the playlist, typically sorted by name
     * @return UTF-8 M3U text with an #EXTM3U header and one #EXTINF entry per file
     */
    public static String buildM3u(List<String> fileNames) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        if (fileNames == null) {
            return sb.toString();
        }
        for (String fileName : fileNames) {
            if (fileName == null || fileName.isEmpty()) {
                continue;
            }
            sb.append("#EXTINF:-1,").append(displayName(fileName)).append('\n');
            sb.append(fileName).append('\n');
        }
        return sb.toString();
    }

    /** Title shown by the player: the file name without its extension. */
    public static String displayName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isEmpty() ? "Track" : base;
    }
}
