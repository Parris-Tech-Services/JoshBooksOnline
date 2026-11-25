package au.com.parristech.ruizusync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import au.com.parristech.ruizusync.core.EpisodePlan.Allowlist;
import au.com.parristech.ruizusync.core.EpisodePlan.Candidate;
import au.com.parristech.ruizusync.core.EpisodePlan.Found;
import au.com.parristech.ruizusync.core.EpisodePlan.Identity;
import au.com.parristech.ruizusync.core.EpisodePlan.Kind;
import au.com.parristech.ruizusync.core.EpisodePlan.State;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class EpisodePlanTest {

    /**
     * Contract with tools/ruizu-episode-pipeline (names.identify), generated from the Python
     * implementation. Both players already hold files named with these labels; a native name
     * that differs would make the queue copy every episode again under a new name.
     * Columns: show, path, kind, season, episode, label.
     */
    private static final String[][] PYTHON_LABELS = {
        {"How I Met Your Mother", "Season 2/How.I.Met.Your.Mother.S02E11.720p.mkv", "episode", "2", "11", "How I Met Your Mother S02E11"},
        {"How I Met Your Mother", "How I Met Your Mother S01E01.mkv", "episode", "1", "1", "How I Met Your Mother S01E01"},
        {"Angel", "Season 3/Angel 3x07 Fredless.avi", "episode", "3", "7", "Angel S03E07"},
        {"Buffy", "Season 6/Buffy.6xEX.Fan_Meeting.x264.mkv", "extra", "6", "null", "Buffy S06 - Fan Meeting"},
        {"Buffy", "Season 5/Buffy.5xEX.Buffy_Abroad.x264.mkv", "extra", "5", "null", "Buffy S05 - Buffy Abroad"},
        {"Heroes", "Heroes - Season 2 (Volume 2 - Generations)/Heroes S02E03 - Chapter Three 'Kindred'.avi", "episode", "2", "3", "Heroes S02E03"},
        {"The Originals", "Season 1/the.originals.s01e01.hdtv.x264.mp4", "episode", "1", "1", "The Originals S01E01"},
        {"Scrubs", "Season 1/Pilot 1280x720 x264.mkv", "extra", "1", "null", "Scrubs S01 - Pilot 1280x720"},
        {"Glee", "Glee - Behind the Scenes.mp4", "extra", "null", "null", "Glee - Behind the Scenes"},
        {"Glee", "Season 4/glee s4 e12 hdtv.avi", "episode", "4", "12", "Glee S04E12"},
        {"Show/../..", "Season 1/Bad<>:\"|?*\\name.mkv", "extra", "1", "null", "Show S01 - Bad name"},
        {"Once upon a time", "Season 1/Once.Upon.A.Time.S01E22.A.Land.Without.Magic.WEB-DL.mkv", "episode", "1", "22", "Once upon a time S01E22"},
        {"Veronica Mars", "S1/Veronica Mars - 1x01 - Pilot.avi", "episode", "1", "1", "Veronica Mars S01E01"},
        {"Call the Midwife", "Series 2/Call the Midwife S02E08.mp4", "episode", "2", "8", "Call the Midwife S02E08"},
        {"New Girl", "Season 7/New.Girl.S07E08.1080p.WEBRip.x265.mkv", "episode", "7", "8", "New Girl S07E08"},
    };

    @Test public void labelsMatchThePythonPipelineExactly() {
        for (String[] row : PYTHON_LABELS) {
            Identity id = EpisodePlan.identify(row[0], row[1]);
            String where = row[0] + " | " + row[1];
            assertEquals(where, row[2], id.kind.name().toLowerCase());
            assertEquals(where, row[3], String.valueOf(id.season));
            assertEquals(where, row[4], String.valueOf(id.episode));
            assertEquals(where, row[5], id.label);
        }
    }

    @Test public void playerNamesMatchWhatIsAlreadyOnThePlayers() {
        assertEquals("How I Met Your Mother S02E11 - RUIZU.amv", EpisodePlan.playerFileName("How I Met Your Mother S02E11"));
    }

    @Test public void untrustedNamesCannotEscapeTheFolderOrBreakFat() {
        String label = EpisodePlan.identify("Show/../..", "Season 1/a<b>c:d|e?.mkv").label;
        for (char bad : "/\\:*?\"<>|".toCharArray()) assertFalse(label, label.indexOf(bad) >= 0);
        assertTrue(EpisodePlan.cleanText(new String(new char[400]).replace('\0', 'x')).length() <= EpisodePlan.MAX_LABEL);
    }

    @Test public void labelsOnThePlayerAreUnderstood() {
        Map.Entry<String, Identity> extra = EpisodePlan.identifyLabel("Buffy S05 - Buffy Abroad");
        assertEquals("Buffy", extra.getKey());
        assertEquals(Kind.EXTRA, extra.getValue().kind);
        assertEquals(Integer.valueOf(5), extra.getValue().season);
        Map.Entry<String, Identity> numbered = EpisodePlan.identifyLabel("The Originals S01E01");
        assertEquals("The Originals", numbered.getKey());
        assertEquals(Integer.valueOf(1), numbered.getValue().episode);
        Map.Entry<String, Identity> bare = EpisodePlan.identifyLabel("Glee - Behind the Scenes");
        assertEquals("Glee", bare.getKey());
        assertNull(bare.getValue().season);
    }

    @Test public void resolutionIsNotAnEpisodeNumber() {
        assertNull(EpisodePlan.episodeNumbers("Pilot 1280x720 x264.mkv"));
    }

    // ---- planning (ported from tests/test_units.py PlannerTest) ----

    private static Candidate ep(String show, int season, Integer number, long bytes, State state) {
        Identity id = number == null
            ? new Identity(Kind.EXTRA, season, null, String.format("%s S%02d - extra", show, season))
            : new Identity(Kind.EPISODE, season, number, String.format("%s S%02dE%02d", show, season, number));
        return new Candidate(id.label, show, id, bytes, state);
    }

    private static List<String> labels(List<Candidate> candidates) {
        List<String> out = new ArrayList<>();
        for (Candidate c : candidates) out.add(c.identity.label);
        return out;
    }

    private final List<Candidate> library = Arrays.asList(
        ep("Scrubs", 1, 2, 300, State.PENDING), ep("Scrubs", 1, 1, 300, State.PENDING),
        ep("Glee", 1, 1, 100, State.PENDING), ep("Glee", 1, 2, 100, State.STAGED),
        ep("Girls", 1, 1, 50, State.PENDING));

    @Test public void roundRobinAcrossAllowedShowsSmallestFirstWithinEachRound() {
        List<Candidate> next = EpisodePlan.nextForPlayer(EpisodePlan.byShow(library), Collections.emptySet(),
            Allowlist.of(Arrays.asList("Scrubs", "glee")), 2);
        assertEquals(Arrays.asList("Glee S01E01", "Scrubs S01E01", "Glee S01E02", "Scrubs S01E02"), labels(next));
    }

    @Test public void aShowThatIsNotAllowedIsNeverPlanned() {
        List<Candidate> next = EpisodePlan.nextForPlayer(EpisodePlan.byShow(library), Collections.emptySet(),
            Allowlist.of(Collections.singletonList("Scrubs")), 5);
        for (Candidate c : next) assertEquals("Scrubs", c.show);
        assertEquals(3, EpisodePlan.nextForPlayer(EpisodePlan.byShow(library), Collections.emptySet(), Allowlist.all(), 1).size());
    }

    @Test public void perShowCapsWhatOneSyncAddsAndKeepsViewingOrder() {
        List<Candidate> season = new ArrayList<>();
        for (int n = 22; n >= 1; n--) season.add(ep("HIMYM", 2, n, 1000 - n, State.PENDING));  // later episodes smaller
        List<Candidate> next = EpisodePlan.nextForPlayer(EpisodePlan.byShow(season), Collections.emptySet(), Allowlist.all(), 1);
        assertEquals(Collections.singletonList("HIMYM S02E01"), labels(next));
    }

    @Test public void anEpisodeAPlayerAlreadyHadIsNeverOfferedAgain() {
        Set<String> had = new HashSet<>(Collections.singletonList("Scrubs S01E01"));
        List<Candidate> next = EpisodePlan.nextForPlayer(EpisodePlan.byShow(library), had,
            Allowlist.of(Collections.singletonList("Scrubs")), 1);
        assertEquals(Collections.singletonList("Scrubs S01E02"), labels(next));
    }

    @Test public void aPermanentlyFailedFileIsSkippedSoTheShowDoesNotStall() {
        List<Candidate> eps = Arrays.asList(ep("Scrubs", 1, 1, 1, State.FAILED), ep("Scrubs", 1, 2, 1, State.PENDING));
        List<Candidate> next = EpisodePlan.nextForPlayer(EpisodePlan.byShow(eps), Collections.emptySet(), Allowlist.all(), 1);
        assertEquals(Collections.singletonList("Scrubs S01E02"), labels(next));
    }

    @Test public void extrasAreUsedOnlyWhenAShowHasNoNumberedEpisodes() {
        Map<String, List<Candidate>> shows = EpisodePlan.byShow(Arrays.asList(
            ep("Buffy", 6, null, 1, State.PENDING), ep("Scrubs", 1, null, 1, State.PENDING), ep("Scrubs", 1, 1, 1, State.PENDING)));
        assertEquals(Kind.EXTRA, shows.get("Buffy").get(0).identity.kind);
        assertEquals(Collections.singletonList("Scrubs S01E01"), labels(shows.get("Scrubs")));
    }

    @Test public void duplicatesAreNeverPlanned() {
        Identity dup = new Identity(Kind.DUPLICATE, 1, 1, "Glee S01E01 duplicate");
        Map<String, List<Candidate>> shows = EpisodePlan.byShow(Collections.singletonList(new Candidate("d", "Glee", dup, 1, State.STAGED)));
        assertTrue(shows.isEmpty());
    }

    // ---- reconciliation, staging ----

    @Test public void filesAlreadyOnThePlayerAreAdoptedNotCopiedAgain() {
        assertEquals(Found.NOT_ON_PLAYER, EpisodePlan.classifyPlayerFile(-1, 500, () -> true));
        assertEquals(Found.HAD, EpisodePlan.classifyPlayerFile(500, 500, () -> { throw new AssertionError("no prefix read needed"); }));
        assertEquals(Found.HAD, EpisodePlan.classifyPlayerFile(900, -1, () -> true));  // written by hand or the old scripts
    }

    @Test public void onlyAProvenTruncatedCopyOfOurOwnFileIsReplaced() {
        assertEquals(Found.REPLACE_TRUNCATED, EpisodePlan.classifyPlayerFile(200, 500, () -> true));
        assertEquals(Found.HAD, EpisodePlan.classifyPlayerFile(200, 500, () -> false));  // someone else's file: left alone
    }

    @Test public void prefixProofComparesBytes() throws Exception {
        byte[] source = new byte[200_000];
        for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 31);
        byte[] truncated = Arrays.copyOf(source, 150_001);
        assertTrue(EpisodePlan.isOwnPrefix(new ByteArrayInputStream(source), new ByteArrayInputStream(truncated), truncated.length));
        truncated[150_000] ^= 1;
        assertFalse(EpisodePlan.isOwnPrefix(new ByteArrayInputStream(source), new ByteArrayInputStream(truncated), truncated.length));
        assertFalse("longer than the source is never a prefix", EpisodePlan.isOwnPrefix(
            new ByteArrayInputStream(new byte[10]), new ByteArrayInputStream(new byte[20]), 20));
    }

    @Test public void aStagedEpisodeIsKeptUntilEveryPlayerThatWantsItHasIt() {
        Set<String> want = new HashSet<>(Arrays.asList("player-a", "player-b"));
        assertFalse(EpisodePlan.canReleaseStaged(want, new HashSet<>(Collections.singletonList("player-a"))));
        assertTrue(EpisodePlan.canReleaseStaged(want, want));
    }
}
