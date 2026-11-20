package au.com.parristech.ruizusync.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import au.com.parristech.ruizusync.TransferUtils;

/**
 * Extracts downloadable audio episodes from RSS 2.0 and Atom podcast feeds.
 *
 * <p>Deliberately dependency-free (plain Java + regex over the feed text) so it is unit tested on
 * the JVM and behaves identically on device. Only HTTPS enclosure URLs are accepted; playlist
 * formats and known streaming services are skipped because they cannot be copied as files.</p>
 */
public final class FeedScanner {

    public static final int MAX_EPISODES = 100;

    private static final Pattern ITEM_TAG = Pattern.compile("<item\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY_TAG = Pattern.compile("<entry\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCLOSURE_TAG = Pattern.compile("<enclosure\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_TAG =
            Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);");

    public static final class Episode {
        public final String title;
        public final String audioUrl;
        /** Size from the feed, or -1 when the feed does not say. */
        public final long size;
        /** MIME type from the feed, or "" when unknown. */
        public final String mimeType;

        Episode(String title, String audioUrl, long size, String mimeType) {
            this.title = title;
            this.audioUrl = audioUrl;
            this.size = size;
            this.mimeType = mimeType;
        }

        @Override
        public String toString() {
            return title + " -> " + audioUrl;
        }
    }

    private FeedScanner() {
    }

    public static List<Episode> scan(String xml) {
        if (xml == null || xml.isEmpty()) {
            return Collections.emptyList();
        }
        StreamScanner scanner = new StreamScanner();
        scanner.feed(xml);
        return scanner.episodes();
    }

    /**
     * Incremental feed scanner. Podcast feeds (for example long-running shows with thousands of
     * episodes) can be tens of megabytes, so the app streams the document and stops as soon as
     * enough episodes are found instead of buffering the whole file.
     */
    public static final class StreamScanner {
        private static final int COMPACT_THRESHOLD = 64 * 1024;

        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder lowerBuffer = new StringBuilder();
        private final List<Episode> episodes = new ArrayList<>();
        private int cursor;

        public boolean done() {
            return episodes.size() >= MAX_EPISODES;
        }

        public int count() {
            return episodes.size();
        }

        public void feed(CharSequence chunk) {
            if (chunk == null || chunk.length() == 0 || done()) {
                return;
            }
            buffer.append(chunk);
            appendLower(lowerBuffer, chunk);
            drain();
        }

        public void feed(char[] chars, int offset, int length) {
            if (length <= 0 || done()) {
                return;
            }
            buffer.append(chars, offset, length);
            for (int i = offset; i < offset + length; i++) {
                char c = chars[i];
                lowerBuffer.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
            }
            drain();
        }

        public List<Episode> episodes() {
            drain();
            return Collections.unmodifiableList(new ArrayList<>(episodes));
        }

        private void drain() {
            while (!done()) {
                int itemStart = indexOfFrom(ITEM_TAG, lowerBuffer, cursor);
                int entryStart = indexOfFrom(ENTRY_TAG, lowerBuffer, cursor);
                final int start;
                final String tag;
                if (itemStart >= 0 && (entryStart < 0 || itemStart < entryStart)) {
                    start = itemStart;
                    tag = "item";
                } else if (entryStart >= 0) {
                    start = entryStart;
                    tag = "entry";
                } else {
                    break;
                }
                int end = lowerBuffer.indexOf("</" + tag, start);
                if (end < 0) {
                    break; // wait for the rest of this item
                }
                String block = buffer.substring(start, end);
                cursor = end + 3;
                String title = cleanText(extractTitle(block));
                Episode episode = "item".equals(tag) ? fromEnclosure(block, title) : fromAtomLink(block, title);
                if (episode != null) {
                    episodes.add(episode);
                }
            }
            compact();
        }

        private void compact() {
            if (cursor < COMPACT_THRESHOLD) {
                return;
            }
            buffer.delete(0, cursor);
            lowerBuffer.delete(0, cursor);
            cursor = 0;
        }
    }

    private static void appendLower(StringBuilder lower, CharSequence chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
    }

    // ---------------------------------------------------------------------------------------

    private static Episode fromEnclosure(String block, String title) {
        Matcher matcher = ENCLOSURE_TAG.matcher(block);
        while (matcher.find()) {
            String tagText = matcher.group();
            String url = attribute(tagText, "url");
            if (url == null) {
                url = attribute(tagText, "href");
            }
            if (!isAllowedAudioUrl(url)) {
                continue;
            }
            String type = attribute(tagText, "type");
            if (!isAudioish(type)) {
                continue;
            }
            long size = parseSize(attribute(tagText, "length"));
            return new Episode(episodeTitle(title), url.trim(), size, type == null ? "" : type.trim());
        }
        return null;
    }

    private static Episode fromAtomLink(String block, String title) {
        Matcher matcher = LINK_TAG.matcher(block);
        String fallbackUrl = null;
        String fallbackType = "";
        while (matcher.find()) {
            String tagText = matcher.group();
            String url = attribute(tagText, "href");
            if (!isAllowedAudioUrl(url)) {
                continue;
            }
            String rel = attribute(tagText, "rel");
            String type = attribute(tagText, "type");
            if (rel != null && "enclosure".equalsIgnoreCase(rel.trim())) {
                return new Episode(episodeTitle(title), url.trim(), -1, type == null ? "" : type.trim());
            }
            if (fallbackUrl == null && isAudioish(type)
                    && (type == null || type.trim().isEmpty() || type.toLowerCase(Locale.ROOT).startsWith("audio/"))) {
                fallbackUrl = url.trim();
                fallbackType = type == null ? "" : type.trim();
            }
        }
        if (fallbackUrl == null) {
            return null;
        }
        return new Episode(episodeTitle(title), fallbackUrl, -1, fallbackType);
    }

    static boolean isAllowedAudioUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) {
            return false;
        }
        if (TransferUtils.isStreamingLink(trimmed)) {
            return false;
        }
        String path = lower;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        return !path.endsWith(".m3u") && !path.endsWith(".m3u8") && !path.endsWith(".pls")
                && !path.endsWith(".xspf") && !path.endsWith(".mpd");
    }

    static boolean isAudioish(String type) {
        if (type == null || type.trim().isEmpty()) {
            return true;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("audio/x-mpegurl") || normalized.startsWith("application/vnd.apple.mpegurl")
                || normalized.startsWith("application/x-scpls")) {
            return false;
        }
        return normalized.startsWith("audio/") || normalized.equals("application/ogg")
                || normalized.equals("application/octet-stream");
    }

    private static String extractTitle(String block) {
        Matcher matcher = TITLE_TAG.matcher(block);
        if (!matcher.find()) {
            return "";
        }
        String title = matcher.group(1);
        Matcher cdata = CDATA.matcher(title);
        if (cdata.find()) {
            title = cdata.group(1);
        }
        return title;
    }

    static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        String text = decodeEntities(raw);
        text = text.replace("<![CDATA[", "").replace("]]>", "");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > 200) {
            text = text.substring(0, 200).trim();
        }
        return text;
    }

    private static String decodeEntities(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            if (entity.startsWith("#")) {
                try {
                    int code = entity.startsWith("#x") || entity.startsWith("#X")
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1));
                    replacement = code > 0 ? new String(Character.toChars(code)) : matcher.group();
                } catch (RuntimeException invalid) {
                    replacement = matcher.group();
                }
            } else {
                switch (entity.toLowerCase(Locale.ROOT)) {
                    case "amp":
                        replacement = "&";
                        break;
                    case "lt":
                        replacement = "<";
                        break;
                    case "gt":
                        replacement = ">";
                        break;
                    case "quot":
                        replacement = "\"";
                        break;
                    case "apos":
                        replacement = "'";
                        break;
                    case "nbsp":
                        replacement = " ";
                        break;
                    default:
                        replacement = matcher.group();
                        break;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static long parseSize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }
        try {
            long size = Long.parseLong(value.trim());
            return size > 0 ? size : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static String episodeTitle(String title) {
        return title == null || title.trim().isEmpty() ? "Podcast episode" : title.trim();
    }

    private static String attribute(String tagText, String name) {
        Pattern pattern = Pattern.compile(
                "(?i)(?<![\\w-])" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
        Matcher matcher = pattern.matcher(tagText);
        if (!matcher.find()) {
            return null;
        }
        if (matcher.group(2) != null) {
            return matcher.group(2);
        }
        if (matcher.group(3) != null) {
            return matcher.group(3);
        }
        return matcher.group(4);
    }

    private static int indexOfFrom(Pattern pattern, CharSequence text, int from) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find(from) ? matcher.start() : -1;
    }
}
