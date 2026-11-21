package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minimal OPML import/export for podcast subscriptions (AntennaPod, most podcast apps and
 * players can exchange this format). Pure Java, unit tested.
 */
public final class Opml {

    public static final int MAX_CHARACTERS = 8 * 1024 * 1024;
    public static final int MAX_FEEDS = 1000;
    private static final Pattern DECLARATION = Pattern.compile("<!\\s*(DOCTYPE|ENTITY)\\b", Pattern.CASE_INSENSITIVE);

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

    /** Parse a bounded OPML document before any subscription is saved. */
    public static List<Feed> parse(String xml) {
        if (xml == null) return new ArrayList<>();
        if (xml.length() > MAX_CHARACTERS) throw new IllegalArgumentException("OPML file is too large");
        if (xml.trim().isEmpty()) return new ArrayList<>();
        // Reject declarations before parsing on both Android and the JVM. The resolver below is
        // defence in depth; no external entities, DTDs or private file reads are needed for OPML.
        if (DECLARATION.matcher(xml).find()) {
            throw new IllegalArgumentException("OPML must not contain DTD or entity declarations");
        }
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setValidating(false);
            factory.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new org.xml.sax.SAXException("External XML resources are not allowed");
            });
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            org.w3c.dom.Document document = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            if (!"opml".equals(document.getDocumentElement().getTagName())) {
                throw new IllegalArgumentException("Choose an OPML subscription file");
            }
            java.util.LinkedHashMap<String, Feed> unique = new java.util.LinkedHashMap<>();
            org.w3c.dom.NodeList outlines = document.getElementsByTagName("outline");
            for (int i = 0; i < outlines.getLength(); i++) {
                org.w3c.dom.Element outline = (org.w3c.dom.Element) outlines.item(i);
                String url = outline.getAttribute("xmlUrl").trim();
                if (url.isEmpty()) continue;
                validateUrl(url);
                String title = outline.getAttribute("title");
                if (title.trim().isEmpty()) title = outline.getAttribute("text");
                unique.putIfAbsent(url, new Feed(title, url));
                if (unique.size() > MAX_FEEDS) throw new IllegalArgumentException("OPML contains too many feeds (maximum 1000)");
            }
            return new ArrayList<>(unique.values());
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            // Parser messages may contain private feed URLs or document fragments.
            throw new IllegalArgumentException("The OPML file is malformed or cannot be read");
        }
    }

    private static void validateUrl(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("OPML feeds must use HTTP or HTTPS URLs without embedded passwords");
            }
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("OPML contains an invalid feed URL");
        }
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
