package au.com.parristech.ruizusync.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The marker file is the only thing that tells two players apart, so its format has to round-trip
 * exactly and must never be guessed at when it is absent, damaged or foreign.
 */
public final class PlayerIdentityTest {

    @Test public void markerRoundTripsExactly() {
        PlayerIdentity created = PlayerIdentity.create("  Sylvie's   RUIZU ", 1795161069542L);
        assertNotNull(created.id);
        assertFalse(created.id.isEmpty());
        assertEquals("Sylvie's RUIZU", created.name);
        assertEquals(1795161069542L, created.firstSeenMs);

        PlayerIdentity parsed = PlayerIdentity.parse(created.toJson());
        assertNotNull(parsed);
        assertEquals(created, parsed);
        assertEquals(created.id, parsed.id);
        assertEquals(created.name, parsed.name);
        assertEquals(created.firstSeenMs, parsed.firstSeenMs);
    }

    @Test public void createdIdentitiesDiffer() {
        PlayerIdentity first = PlayerIdentity.create("Player", 1L);
        PlayerIdentity second = PlayerIdentity.create("Player", 1L);
        assertNotEquals(first.id, second.id);
    }

    @Test public void shortIdAndDescriptionIdentifyTheUnit() {
        PlayerIdentity identity = PlayerIdentity.of("abcdef12-3456-7890-abcd-ef1234567890", "Josh's", 7L);
        assertNotNull(identity);
        assertEquals("abcdef12", identity.shortId());
        assertEquals("Josh's (abcdef12)", identity.describe());
    }

    @Test public void absentMarkerIsNotAnIdentity() {
        assertNull(PlayerIdentity.parse(null));
        assertNull(PlayerIdentity.parse(""));
        assertNull(PlayerIdentity.parse("   "));
    }

    @Test public void malformedMarkersAreRejectedWithoutThrowing() {
        String[] malformed = {
                "not json at all",
                "{",
                "{}",
                "{\"id\":}",
                "{\"id\":\"\"}",
                "{\"id\":\"has space\"}",
                "{\"id\":\"ok\",}",
                "{\"id\":\"ok\" \"name\":\"x\"}",
                "{\"id\":\"ok\",\"id\":\"duplicate\"}",
                "{\"version\":2,\"id\":\"ok\"}",
                "{\"id\":\"a/b\"}",
                "[{\"id\":\"array\"}]",
                "null",
                "{\"id\":\"ok\"} trailing",
                "{\"name\":\"no id here\"}",
        };
        for (String raw : malformed) {
            assertNull("should reject: " + raw, PlayerIdentity.parse(raw));
        }
    }

    @Test public void oversizedMarkerIsRejected() {
        StringBuilder sb = new StringBuilder(PlayerIdentity.MAX_MARKER_CHARS + 64);
        sb.append("{\"id\":\"ok\",\"name\":\"");
        while (sb.length() < PlayerIdentity.MAX_MARKER_CHARS + 32) {
            sb.append('a');
        }
        sb.append("\"}");
        assertNull(PlayerIdentity.parse(sb.toString()));
    }

    @Test public void longNameIsBoundedNotRejected() {
        StringBuilder name = new StringBuilder();
        while (name.length() < 200) {
            name.append('x');
        }
        PlayerIdentity identity = PlayerIdentity.of("ok-id", name.toString(), 0L);
        assertNotNull(identity);
        assertTrue(identity.name.length() <= PlayerIdentity.MAX_NAME_CHARS);
        assertNotNull(PlayerIdentity.parse(identity.toJson()));
    }

    @Test public void escapedCharactersSurviveRoundTrip() {
        PlayerIdentity identity = PlayerIdentity.of("quoted-id", "Player \"A\" \\ back\\slash", 12L);
        assertNotNull(identity);
        PlayerIdentity parsed = PlayerIdentity.parse(identity.toJson());
        assertNotNull(parsed);
        assertEquals("Player \"A\" \\ back\\slash", parsed.name);
    }

    @Test public void unicodeNameSurvivesRoundTrip() {
        PlayerIdentity identity = PlayerIdentity.of("unicode-id", "RUIZU — Sylvie's 🎵", 5L);
        assertNotNull(identity);
        PlayerIdentity parsed = PlayerIdentity.parse(identity.toJson());
        assertNotNull(parsed);
        assertEquals("RUIZU — Sylvie's 🎵", parsed.name);
    }

    @Test public void missingOptionalFieldsFallBackHonestly() {
        PlayerIdentity parsed = PlayerIdentity.parse("{\"id\":\"only-an-id\"}");
        assertNotNull(parsed);
        assertEquals("only-an-id", parsed.id);
        assertEquals("Unnamed player", parsed.name);
        assertEquals(0L, parsed.firstSeenMs);
    }

    @Test public void unusableIdsAreRejected() {
        assertNull(PlayerIdentity.of(null, "x", 0L));
        assertNull(PlayerIdentity.of("   ", "x", 0L));
        assertNull(PlayerIdentity.of("with space", "x", 0L));
        assertNull(PlayerIdentity.of("path/traversal", "x", 0L));
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < PlayerIdentity.MAX_ID_CHARS + 1; i++) {
            tooLong.append('a');
        }
        assertNull(PlayerIdentity.of(tooLong.toString(), "x", 0L));
    }

    @Test public void markerFileNameIsStable() {
        // Changing this silently orphans every player that already carries a marker.
        assertEquals(".ruizu-sync-device.json", PlayerIdentity.MARKER_FILE_NAME);
    }
}
