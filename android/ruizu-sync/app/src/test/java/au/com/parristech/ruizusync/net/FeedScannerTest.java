package au.com.parristech.ruizusync.net;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Unit tests for the dependency-free RSS/Atom podcast scanner. */
public class FeedScannerTest {

    private static final String RSS_FEED = "<?xml version=\"1.0\"?>"
            + "<rss version=\"2.0\"><channel><title>Show</title>"
            + "<item><title><![CDATA[Episode 1]]></title>"
            + "<enclosure url=\"https://cdn.example.com/ep1.mp3\" type=\"audio/mpeg\" length=\"12345\"/></item>"
            + "<item><title>Episode &amp; 2</title>"
            + "<enclosure url=\"https://cdn.example.com/ep2.mp3\" type=\"audio/mpeg\"/></item>"
            + "<item><title>No enclosure here</title></item>"
            + "</channel></rss>";

    @Test
    public void readsRssEnclosures() {
        List<FeedScanner.Episode> episodes = FeedScanner.scan(RSS_FEED);
        assertEquals(2, episodes.size());
        assertEquals("Episode 1", episodes.get(0).title);
        assertEquals("https://cdn.example.com/ep1.mp3", episodes.get(0).audioUrl);
        assertEquals(12345, episodes.get(0).size);
        assertEquals("audio/mpeg", episodes.get(0).mimeType);
        assertEquals("Episode & 2", episodes.get(1).title);
        assertEquals(-1, episodes.get(1).size);
    }

    @Test
    public void readsAtomEnclosures() {
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<entry><title>Atom episode</title>"
                + "<link rel=\"enclosure\" type=\"audio/mpeg\" href=\"https://cdn.example.com/atom.mp3\"/>"
                + "</entry></feed>";
        List<FeedScanner.Episode> episodes = FeedScanner.scan(atom);
        assertEquals(1, episodes.size());
        assertEquals("Atom episode", episodes.get(0).title);
        assertEquals("https://cdn.example.com/atom.mp3", episodes.get(0).audioUrl);
    }

    @Test
    public void rejectsInsecureStreamingAndPlaylistUrls() {
        String feed = "<rss><channel>"
                + "<item><title>Insecure</title><enclosure url=\"http://example.com/a.mp3\" type=\"audio/mpeg\"/></item>"
                + "<item><title>Spotify</title><enclosure url=\"https://open.spotify.com/track/abc\" type=\"audio/mpeg\"/></item>"
                + "<item><title>HLS playlist</title><enclosure url=\"https://example.com/live.m3u8\" type=\"application/vnd.apple.mpegurl\"/></item>"
                + "<item><title>Web page</title><enclosure url=\"https://example.com/page\" type=\"text/html\"/></item>"
                + "<item><title>Plain playlist</title><enclosure url=\"https://example.com/list.m3u\" type=\"audio/x-mpegurl\"/></item>"
                + "</channel></rss>";
        assertTrue(FeedScanner.scan(feed).isEmpty());
    }

    @Test
    public void acceptsSingleQuotedAttributes() {
        String feed = "<rss><channel><item><title>Quoted</title>"
                + "<enclosure url='https://cdn.example.com_q.mp3' type='audio/mpeg'/>"
                + "</item></channel></rss>";
        // The underscore keeps this a valid https URL while proving single quotes parse.
        assertEquals(1, FeedScanner.scan(feed).size());
        String proper = "<rss><channel><item><title>Quoted</title>"
                + "<enclosure url='https://cdn.example.com/q.mp3' type='audio/mpeg'/>"
                + "</item></channel></rss>";
        List<FeedScanner.Episode> episodes = FeedScanner.scan(proper);
        assertEquals(1, episodes.size());
        assertEquals("https://cdn.example.com/q.mp3", episodes.get(0).audioUrl);
    }

    @Test
    public void skipsPlaylistAndHtmlTypesEvenWhenUrlLooksAudio() {
        String feed = "<rss><channel>"
                + "<item><title>Wrong type</title><enclosure url=\"https://example.com/a.mp3\" type=\"text/html\"/></item>"
                + "<item><title>Good</title><enclosure url=\"https://example.com/b.mp3\" type=\"audio/mpeg\"/></item>"
                + "</channel></rss>";
        List<FeedScanner.Episode> episodes = FeedScanner.scan(feed);
        assertEquals(1, episodes.size());
        assertEquals("https://example.com/b.mp3", episodes.get(0).audioUrl);
    }

    @Test
    public void capsEpisodeCount() {
        StringBuilder feed = new StringBuilder("<rss><channel>");
        for (int i = 0; i < 150; i++) {
            feed.append("<item><title>Episode ").append(i).append("</title>")
                    .append("<enclosure url=\"https://example.com/e").append(i)
                    .append(".mp3\" type=\"audio/mpeg\"/></item>");
        }
        feed.append("</channel></rss>");
        assertEquals(FeedScanner.MAX_EPISODES, FeedScanner.scan(feed.toString()).size());
    }

    @Test
    public void cleanTextCollapsesWhitespaceAndEntities() {
        assertEquals("a b", FeedScanner.cleanText("  a\n b "));
        assertEquals("A & B", FeedScanner.cleanText("A &amp; B"));
        assertEquals("Quoted \"x\"", FeedScanner.cleanText("Quoted &quot;x&quot;"));
        assertEquals("", FeedScanner.cleanText(null));
    }

    @Test
    public void emptyAndMalformedFeedsAreSafe() {
        assertTrue(FeedScanner.scan(null).isEmpty());
        assertTrue(FeedScanner.scan("").isEmpty());
        assertTrue(FeedScanner.scan("not xml at all").isEmpty());
        assertTrue(FeedScanner.scan("<rss><channel><item><title>Unclosed</title>").isEmpty());
    }
}
