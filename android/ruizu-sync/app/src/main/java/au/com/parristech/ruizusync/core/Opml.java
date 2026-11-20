package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal OPML import/export for podcast subscriptions (AntennaPod, most podcast apps and
 * players can exchange this format). Pure Java, unit tested.
 */
public final class Opml {

    private static final Pattern OUTLINE_TAG = Pattern.compile("<outline\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE =
            Pattern.compile("(?i)(?<![\\w-])([a-z:]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    public static final class Feed {
        public final String title;
        public final String url;

        public Feed(String title, String url) {
            this.title = title == null || title.trim().isEmpty() ? url : title.trim();
            this.url = url == null ? "" : url.trim();
        }

        @Override
        public String toString() {
            return title + " <" + url + ">";
        }
    }

    private Opml() {
    }

    /** Parses every {@code <outline ... xmlUrl="...">} entry; ignores other outlines. */
    public static List<Feed> parse(String xml) {
        List<Feed> feeds = new ArrayList<>();
        if (xml == null || xml.isEmpty()) {
            return feeds;
        }
        Matcher matcher = OUTLINE_TAG.matcher(xml);
        while (matcher.find()) {
            String tag = matcher.group();
            String url = attribute(tag, "xmlUrl");
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            String title = attribute(tag, "title");
            if (title == null) {
                title = attribute(tag, "text");
            }
            feeds.add(new Feed(title, url));
        }
        return feeds;
    }

    public static String generate(List<Feed> feeds) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<opml version=\"2.0\">\n");
        sb.append("  <head><title>RUIZU Sync subscriptions</title></head>\n");
        sb.append("  <body>\n");
        if (feeds != null) {
            for (Feed feed : feeds) {
                if (feed == null || feed.url == null || feed.url.isEmpty()) {
                    continue;
                }
                sb.append("    <outline type=\"rss\" text=\"").append(escape(feed.title))
                        .append("\" title=\"").append(escape(feed.title))
                        .append("\" xmlUrl=\"").append(escape(feed.url)).append("\"/>\n");
            }
        }
        sb.append("  </body>\n");
        sb.append("</opml>\n");
        return sb.toString();
    }

    private static String attribute(String tag, String name) {
        Matcher matcher = ATTRIBUTE.matcher(tag);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(name)) {
                return matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            }
        }
        return null;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    public static String describe(List<Feed> feeds) {
        if (feeds == null || feeds.isEmpty()) {
            return "0 feeds";
        }
        return feeds.size() + (feeds.size() == 1 ? " feed" : " feeds");
    }

    public static boolean looksLikeOpml(String xml) {
        if (xml == null) {
            return false;
        }
        String lower = xml.trim().toLowerCase(Locale.ROOT);
        return lower.contains("<opml");
    }
}
