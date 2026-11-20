package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only library inventory and comparison. The caller supplies selected source audio
 * and SAF-scanned player files; no mutation or network connection occurs here.
 *
 * Presence is intentionally conservative: name + known byte size means "likely present",
 * not cryptographic identity. Title-only/different-size matches are never silently skipped.
 */
public final class LibraryComparison {
    private LibraryComparison() {}

    public enum Status { LIKELY_PRESENT, POSSIBLE_MATCH, MISSING }

    public static final class Track {
        public final String name;
        public final long bytes;
        public final String artist;
        public final String album;
        public final String sourceFolder;
        public Track(String name, long bytes, String artist, String album, String sourceFolder) {
            this.name = safe(name);
            this.bytes = bytes;
            this.artist = safe(artist);
            this.album = safe(album);
            this.sourceFolder = safe(sourceFolder);
        }
        public boolean podcast() {
            return sourceFolder.toLowerCase(Locale.ROOT).contains("podcast");
        }
    }

    public static final class Match {
        public final Track source;
        public final Status status;
        public final String playerPath;
        Match(Track source, Status status, String playerPath) {
            this.source = source;
            this.status = status;
            this.playerPath = playerPath;
        }
    }

    public static final class Result {
        public final List<Match> matches;
        public final int playerAudioFiles;
        public final int playerMusicFiles;
        public final int playerPodcastFiles;
        public final int ignoredSystemFiles;
        public final int likelyPresent;
        public final int possible;
        public final int missing;
        public final Map<String, Integer> playerFolders;

        Result(List<Match> matches, int playerAudioFiles, int playerMusicFiles,
               int playerPodcastFiles, int ignoredSystemFiles, int likelyPresent,
               int possible, int missing, Map<String, Integer> playerFolders) {
            this.matches = Collections.unmodifiableList(matches);
            this.playerAudioFiles = playerAudioFiles;
            this.playerMusicFiles = playerMusicFiles;
            this.playerPodcastFiles = playerPodcastFiles;
            this.ignoredSystemFiles = ignoredSystemFiles;
            this.likelyPresent = likelyPresent;
            this.possible = possible;
            this.missing = missing;
            this.playerFolders = Collections.unmodifiableMap(playerFolders);
        }

        public String report(int maxRows) {
            StringBuilder out = new StringBuilder();
            out.append("PLAYER INVENTORY (read-only)\n")
                .append(playerAudioFiles).append(" audio files found (")
                .append(playerMusicFiles).append(" under Music/other folders, ")
                .append(playerPodcastFiles).append(" under Podcasts).\n")
                .append(ignoredSystemFiles).append(" system or non-audio files excluded.\n\n");
            out.append("TOP-LEVEL FOLDERS\n");
            int shownFolders = 0;
            for (Map.Entry<String, Integer> folder : playerFolders.entrySet()) {
                if (shownFolders++ >= 30) {
                    out.append("… more folders omitted\n");
                    break;
                }
                out.append(folder.getKey()).append(": ").append(folder.getValue()).append(" audio\n");
            }
            if (matches.isEmpty()) {
                out.append("\nTo find missing songs, albums, artists or podcasts, first add files ")
                    .append("or a folder from your phone or Google Drive using the system picker. ")
                    .append("Then run Compare again.\n");
                return out.toString();
            }
            out.append("\nSOURCE COMPARISON\n")
                .append(likelyPresent).append(" likely already on player (same file name and size)\n")
                .append(possible).append(" possible matches (title only or size unknown/different)\n")
                .append(missing).append(" missing from the player by filename\n")
                .append("Source artist/album names come from available audio tags; ")
                .append("missing tags do not mean missing songs.\n");
            appendRows(out, Status.MISSING, "MISSING SONGS / EPISODES", maxRows);
            appendRows(out, Status.POSSIBLE_MATCH, "CHECK POSSIBLE MATCHES", maxRows);
            appendRows(out, Status.LIKELY_PRESENT, "LIKELY ALREADY PRESENT", maxRows);
            out.append("\nThe scan compares filenames and sizes, not audio fingerprints. ")
                .append("It does not delete, rename or alter anything on the player.");
            return out.toString();
        }

        private void appendRows(StringBuilder out, Status status, String heading, int maxRows) {
            out.append("\n").append(heading).append("\n");
            int shown = 0;
            for (Match match : matches) {
                if (match.status != status) continue;
                if (shown++ >= maxRows) {
                    out.append("… additional entries omitted\n");
                    break;
                }
                Track t = match.source;
                if (!t.artist.isEmpty()) out.append(t.artist).append(" / ");
                if (!t.album.isEmpty()) out.append(t.album).append(" / ");
                out.append(t.name);
                if (match.status == Status.POSSIBLE_MATCH && !match.playerPath.isEmpty()) {
                    out.append(" (player: ").append(match.playerPath).append(")");
                }
                out.append('\n');
            }
            if (shown == 0) out.append("None\n");
        }
    }

    public static Result compare(List<Track> sources, List<DestEntry> playerFiles) {
        Map<String, List<DestEntry>> exactNames = new HashMap<>();
        Map<String, List<DestEntry>> stems = new HashMap<>();
        Map<String, Integer> folders = new LinkedHashMap<>();
        int music = 0, podcasts = 0, ignored = 0;
        if (playerFiles != null) for (DestEntry file : playerFiles) {
            if (!isAudio(file.name)) {
                ignored++;
                continue;
            }
            String folder = firstPathSegment(file.relPath);
            if (folder.isEmpty()) folder = "(player root)";
            folders.put(folder, folders.containsKey(folder) ? folders.get(folder) + 1 : 1);
            if (folder.toLowerCase(Locale.ROOT).contains("podcast")) podcasts++;
            else music++;
            put(exactNames, normalize(file.name), file);
            put(stems, stem(file.name), file);
        }
        List<Match> matches = new ArrayList<>();
        int present = 0, possible = 0, missing = 0;
        if (sources != null) for (Track source : sources) {
            String key = normalize(source.name);
            List<DestEntry> sameName = exactNames.get(key);
            DestEntry confirmed = null;
            if (source.bytes >= 0 && sameName != null) {
                for (DestEntry item : sameName) {
                    if (item.size == source.bytes) { confirmed = item; break; }
                }
            }
            if (confirmed != null) {
                present++;
                matches.add(new Match(source, Status.LIKELY_PRESENT, confirmed.relPath));
            } else {
                DestEntry candidate = first(sameName);
                if (candidate == null) candidate = first(stems.get(stem(source.name)));
                if (candidate != null) {
                    possible++;
                    matches.add(new Match(source, Status.POSSIBLE_MATCH, candidate.relPath));
                } else {
                    missing++;
                    matches.add(new Match(source, Status.MISSING, ""));
                }
            }
        }
        return new Result(matches, music + podcasts, music, podcasts, ignored,
                present, possible, missing, folders);
    }

    private static void put(Map<String, List<DestEntry>> map, String key, DestEntry file) {
        List<DestEntry> files = map.get(key);
        if (files == null) {
            files = new ArrayList<>();
            map.put(key, files);
        }
        files.add(file);
    }
    private static DestEntry first(List<DestEntry> files) {
        return files == null || files.isEmpty() ? null : files.get(0);
    }
    private static String firstPathSegment(String relPath) {
        if (relPath == null) return "";
        int slash = relPath.indexOf('/');
        return slash < 0 ? "" : relPath.substring(0, slash);
    }
    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
    private static String normalize(String name) {
        return safe(name).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    private static String stem(String name) {
        String val = normalize(name);
        int dot = val.lastIndexOf('.');
        if (dot >= 0) val = val.substring(0, dot);
        return val.replaceAll("[^\\p{L}\\p{N}]+", "");
    }
    public static boolean isAudio(String name) {
        String lower = normalize(name);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".m4b")
            || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".aac")
            || lower.endsWith(".wma") || lower.endsWith(".ape") || lower.endsWith(".ogg")
            || lower.endsWith(".opus") || lower.endsWith(".aiff") || lower.endsWith(".aif");
    }
}
