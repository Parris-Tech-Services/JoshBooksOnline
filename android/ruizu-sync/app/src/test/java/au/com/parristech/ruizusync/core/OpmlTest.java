package au.com.parristech.ruizusync.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class OpmlTest {
    private static String wrap(String body) { return "<opml version=\"2.0\"><body>" + body + "</body></opml>"; }
    private static String outline(String url) { return "<outline text=\"Show\" xmlUrl=\"" + url + "\"/>"; }

    @Test public void unicodeAndEntitiesRoundTripWithoutChangingMeaning() {
        List<Opml.Feed> expected = Arrays.asList(
                new Opml.Feed("Écouter & <祈り> \"voices\" 'today'", "https://example.org/feed?a=1&b=2"),
                new Opml.Feed("Second", "https://example.org/Feed"));
        List<Opml.Feed> actual = Opml.parse(Opml.generate(expected));
        assertEquals(2, actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).title, actual.get(i).title);
            assertEquals(expected.get(i).url, actual.get(i).url);
        }
    }
    @Test public void duplicatesAreRemovedAndNestedOrderIsPreserved() {
        List<Opml.Feed> feeds = Opml.parse(wrap(outline("https://x.test/a")
                + "<outline text=\"Group\">" + outline("https://x.test/b") + "</outline>"
                + outline("https://x.test/a")));
        assertEquals(2, feeds.size());
        assertEquals("https://x.test/a", feeds.get(0).url);
        assertEquals("https://x.test/b", feeds.get(1).url);
    }
    @Test public void escapedNumericEntitiesAndGreaterThanInTitleAreDecoded() {
        List<Opml.Feed> feeds = Opml.parse(wrap("<outline text=\"A > B &#x1F3A7;\" xmlUrl=\"https://x.test/rss?a=1&amp;b=2\"/>"));
        assertEquals("A > B 🎧", feeds.get(0).title);
        assertEquals("https://x.test/rss?a=1&b=2", feeds.get(0).url);
    }
    @Test public void malformedOrNonOpmlDocumentsAreRejected() {
        reject("<opml><body><outline></opml>");
        reject("<html><outline xmlUrl=\"https://x.test/rss\"/></html>");
        reject(wrap("<outline xmlUrl=\"https://x.test/rss?a=1&b=2\"/>"));
    }
    @Test public void externalEntitiesAndEntityExpansionAreRejected() {
        reject("<!DOCTYPE opml SYSTEM \"file:///private\"><opml/>");
        reject("<!DOCTYPE opml [<!ENTITY bomb \"expanded\">]><opml><body>&bomb;</body></opml>");
    }
    @Test public void oversizedInputAndFeedCountsAreRejected() {
        reject(" ".repeat(Opml.MAX_CHARACTERS + 1));
        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= Opml.MAX_FEEDS; i++) body.append(outline("https://x.test/" + i));
        reject(wrap(body.toString()));
    }
    @Test public void invalidFeedSchemesAndEmbeddedCredentialsAreRejected() {
        for (String url : Arrays.asList("file:///tmp/feed", "javascript:alert(1)", "https://user:password@x.test/rss", "not-a-url")) {
            reject(wrap(outline(url)));
        }
    }
    @Test public void commentsCannotInventSubscriptions() {
        assertTrue(Opml.parse(wrap("<!--" + outline("https://x.test/hidden") + "-->")).isEmpty());
    }
    private static void reject(String xml) {
        try { Opml.parse(xml); fail("unsafe or malformed OPML must be rejected"); }
        catch (IllegalArgumentException expected) {
            assertFalse("errors must not echo private input", expected.getMessage().contains("password@"));
        }
    }
}
