package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Decides which folders a track is copied into on the player. Pure Java, unit tested. */
public final class FolderPlanner {

    public static final String UNKNOWN_ARTIST = "Unknown Artist";
    private static final int MAX_TREE_DEPTH = 8;

    /** How the destination folders are chosen. */
    public enum OrganiseMode {
        FLAT("Flat (player root)"),
        ARTIST_ALBUM("Artist / Album"),
        SOURCE_FOLDER("By source folder"),
        MUSIC_PODCASTS("Music / Podcasts folders"),
        KEEP_SOURCE_TREE("Keep source folder tree");

        public final String label;

        OrganiseMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private FolderPlanner() {
    }

    /**
     * @param mode           destination layout
     * @param artist         tag artist, may be null/blank
     * @param album          tag album, may be null/blank
     * @param sourceFolder   name of the folder the user picked as the source
     * @param sourceRelDirs  folder path relative to the picked source tree, may be null
     * @return sanitised folder segments relative to the player root; empty means "copy to root"
     */
    public static List<String> folderSegments(OrganiseMode mode,
                                              String artist,
                                              String album,
                                              String sourceFolder,
                                              List<String> sourceRelDirs) {
        return folderSegments(mode, artist, album, sourceFolder, sourceRelDirs, null);
    }

    /**
     * @param prefix optional destination subfolder prepended to the planned layout, used by
     *               saved folder-pair rules such as Drive/Audiobooks -> RUIZU/Music/Audiobooks
     */
    public static List<String> folderSegments(OrganiseMode mode,
                                              String artist,
                                              String album,
                                              String sourceFolder,
                                              List<String> sourceRelDirs,
                                              List<String> prefix) {
        List<String> base = baseSegments(mode, artist, album, sourceFolder, sourceRelDirs);
        List<String> prefixSegments = FileNameSanitizer.pathSegments(prefix);
        if (prefixSegments.isEmpty()) {
            return base;
        }
        List<String> combined = new ArrayList<>(prefixSegments);
        combined.addAll(base);
        return combined;
    }

    private static List<String> baseSegments(OrganiseMode mode,
                                             String artist,
                                             String album,
                                             String sourceFolder,
                                             List<String> sourceRelDirs) {
        switch (mode) {
            case ARTIST_ALBUM:
                return artistAlbum(artist, album);
            case SOURCE_FOLDER: {
                String folder = FileNameSanitizer.segment(sourceFolder);
                return folder.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(folder);
            }
            case MUSIC_PODCASTS: {
                // Podcast episodes are imported with sourceFolder "Podcasts"; everything else
                // goes under "Music", which mirrors the player's own folder split.
                String folder = FileNameSanitizer.segment(sourceFolder);
                return Collections.singletonList(
                        "Podcasts".equalsIgnoreCase(folder) ? "Podcasts" : "Music");
            }
            case KEEP_SOURCE_TREE: {
                List<String> sanitized = FileNameSanitizer.pathSegments(sourceRelDirs);
                if (sanitized.size() > MAX_TREE_DEPTH) {
                    sanitized = new ArrayList<>(sanitized.subList(0, MAX_TREE_DEPTH));
                }
                return sanitized;
            }
            case FLAT:
            default:
                return Collections.emptyList();
        }
    }

    private static List<String> artistAlbum(String artist, String album) {
        String safeArtist = FileNameSanitizer.segment(artist);
        String safeAlbum = FileNameSanitizer.segment(album);
        List<String> out = new ArrayList<>(2);
        if (safeArtist.isEmpty() && safeAlbum.isEmpty()) {
            return out;
        }
        out.add(safeArtist.isEmpty() ? UNKNOWN_ARTIST : safeArtist);
        if (!safeAlbum.isEmpty()) {
            out.add(safeAlbum);
        }
        return out;
    }
}
