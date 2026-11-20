package au.com.parristech.ruizusync.net;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class DownloadResumePolicyTest {

    private static final String URL = "https://podcast.example/episode.mp3?token=private-token";
    private static final String ETAG = "\"strong-123\"";
    private static final String DATE = "Wed, 21 Oct 2015 07:28:00 GMT";

    @Test public void matchingSourceStrongEtagCanResume() {
        DownloadResumePolicy.State state = DownloadResumePolicy.fromHeaders(URL, ETAG, DATE);
        assertNotNull(state);
        assertEquals("etag", state.validatorType);
        assertEquals(ETAG, state.validator);
        assertTrue(DownloadResumePolicy.canResume(state, URL, 4096));
        assertFalse(DownloadResumePolicy.canResume(state, URL, 0));
        assertFalse(DownloadResumePolicy.canResume(state, URL + "&other=1", 4096));
    }

    @Test public void weakEtagFallsBackToValidLastModified() {
        DownloadResumePolicy.State state = DownloadResumePolicy.fromHeaders(URL, "W/\"weak\"", DATE);
        assertNotNull(state);
        assertEquals("date", state.validatorType);
        assertTrue(DownloadResumePolicy.sameEntity(state, null, DATE));
        assertFalse(DownloadResumePolicy.sameEntity(state, null, "Thu, 22 Oct 2015 07:28:00 GMT"));
        assertFalse(DownloadResumePolicy.sameEntity(state, ETAG, null));
    }

    @Test public void noStrongValidatorDisablesResume() {
        assertNull(DownloadResumePolicy.fromHeaders(URL, "W/\"weak\"", null));
        assertNull(DownloadResumePolicy.fromHeaders(URL, "", ""));
        assertNull(DownloadResumePolicy.fromHeaders(URL, "\"no-end", "Wed, bad date GMT"));
    }

    @Test public void changedOrMissingResponseValidatorCannotAppend() {
        DownloadResumePolicy.State state = DownloadResumePolicy.fromHeaders(URL, ETAG, DATE);
        assertTrue(DownloadResumePolicy.sameEntity(state, ETAG, DATE));
        assertFalse(DownloadResumePolicy.sameEntity(state, "\"other\"", DATE));
        assertFalse(DownloadResumePolicy.sameEntity(state, null, DATE));
    }

    @Test public void exactOpenEnded206RangeAllowed() {
        DownloadResumePolicy.Range range = DownloadResumePolicy.parseRange(
                "bytes 4096-8191/8192", 10240);
        assertNotNull(range);
        assertEquals(4096, range.first);
        assertEquals(8191, range.last);
        assertEquals(8192, range.total);
        assertEquals(4096, range.responseBytes());
        assertTrue(DownloadResumePolicy.validResumeRange(range, 4096, 4096));
        assertTrue(DownloadResumePolicy.validResumeRange(range, 4096, -1));
    }

    @Test public void wrongRangeStartOrLengthCannotAppend() {
        DownloadResumePolicy.Range range = DownloadResumePolicy.parseRange(
                "bytes 4097-8191/8192", 10240);
        assertFalse(DownloadResumePolicy.validResumeRange(range, 4096, -1));
        DownloadResumePolicy.Range correct = DownloadResumePolicy.parseRange(
                "bytes 4096-8191/8192", 10240);
        assertFalse(DownloadResumePolicy.validResumeRange(correct, 4096, 4000));
        assertFalse(DownloadResumePolicy.validResumeRange(correct, 0, 4096));
    }

    @Test public void short206ResponseCannotBePublishedAsComplete() {
        DownloadResumePolicy.Range shortRange = DownloadResumePolicy.parseRange(
                "bytes 4096-6143/8192", 10240);
        assertNotNull(shortRange);
        assertFalse(DownloadResumePolicy.validResumeRange(shortRange, 4096, 2048));
    }

    @Test public void malformedOrOversizeRangesAreRejected() {
        String[] bad = {null, "", "bytes */8192", "bytes 0-9/*", "bytes 1-0/10",
                "bytes 0-10/10", "bytes 0-20/21", "bytes -1-9/10",
                "bytes 999999999999999999999999-10/12", "items 0-4/5", "bytes 0-4/6, 9-11/12"};
        for (String header : bad) {
            // "bytes 0-20/21" is syntactically sound; only a configured size cap rejects it.
            long limit = "bytes 0-20/21".equals(header) ? 20L : 100000L;
            assertNull("Bad range should be rejected: " + header,
                    DownloadResumePolicy.parseRange(header, limit));
        }
    }

    @Test public void metadataRoundTripsWithoutSavingPrivateUrl() throws Exception {
        File folder = Files.createTempDirectory("ruizu-resume").toFile();
        File metadata = new File(folder, "episode.part.meta");
        try {
            DownloadResumePolicy.State original = DownloadResumePolicy.fromHeaders(URL, ETAG, DATE);
            DownloadResumePolicy.save(metadata, original);
            DownloadResumePolicy.State loaded = DownloadResumePolicy.load(metadata);
            assertNotNull(loaded);
            assertTrue(DownloadResumePolicy.canResume(loaded, URL, 512));
            assertFalse(DownloadResumePolicy.canResume(loaded, URL + "2", 512));
            String saved = new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8);
            assertFalse("Signed/private podcast URL must not be written to disk", saved.contains(URL));
            assertFalse(saved.contains("private-token"));
        } finally {
            metadata.delete();
            folder.delete();
        }
    }

    @Test public void corruptMetadataIsIgnoredInsteadOfTrusted() throws Exception {
        File folder = Files.createTempDirectory("ruizu-bad-resume").toFile();
        File metadata = new File(folder, "episode.part.meta");
        try {
            Files.write(metadata.toPath(), "source=bad\nvalidator=\"stale\"\ntype=etag\n"
                    .getBytes(StandardCharsets.UTF_8));
            assertNull(DownloadResumePolicy.load(metadata));
            byte[] oversized = new byte[2049];
            Files.write(metadata.toPath(), oversized);
            assertNull(DownloadResumePolicy.load(metadata));
        } finally {
            metadata.delete();
            folder.delete();
        }
    }
}
