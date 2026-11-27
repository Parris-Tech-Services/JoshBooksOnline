package au.com.parristech.ruizusync.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The app hands one shell command to Termux, which runs it as its own user with the phone's data.
 * A player path that reached the shell unquoted would be command injection on the user's account, so
 * the exact quoting and the refusal cases are pinned here rather than discovered on a device.
 */
public final class TermuxSyncRequestTest {

    @Test public void defaultRunNamesTheToolMinutesAndLog() {
        TermuxSyncRequest request = TermuxSyncRequest.run(TermuxSyncRequest.DEFAULT_MINUTES);
        assertEquals(TermuxSyncRequest.BASH_PATH, request.executable());
        assertArrayEquals(new String[]{"-lc", request.commandLine()}, request.arguments());

        String command = request.commandLine();
        assertTrue(command.contains("ruizu-episodes run --minutes 7 --quiet"));
        assertTrue(command.contains(TermuxSyncRequest.LOG_FILE_NAME));
        // The default log target must stay expandable: single quotes would silently create a
        // directory literally named "${RUIZU_PLAYER_ROOTS%%:*}" instead of using the player.
        assertTrue(command, command.contains("\"${RUIZU_PLAYER_ROOTS%%:*}/"
                + TermuxSyncRequest.LOG_FILE_NAME + "\""));
        // The exit status is captured inside the same redirect group, so it cannot be lost.
        assertTrue(command.contains("echo \"" + TermuxSyncRequest.EXIT_MARKER + "$?\""));
        assertTrue(command.trim().endsWith("2>&1"));
    }

    @Test public void explicitRootIsQuotedAsOneShellWord() {
        TermuxSyncRequest request = TermuxSyncRequest.run(3, "/mnt/media_rw/0000-006F");
        String command = request.commandLine();
        assertTrue(command.contains("--minutes 3"));
        assertTrue(command.contains("'/mnt/media_rw/0000-006F/" + TermuxSyncRequest.LOG_FILE_NAME + "'"));
    }

    @Test public void anApostropheInThePathCannotEndTheQuote() {
        // A player mounted under a folder the user named, e.g. "Sylvie's player".
        TermuxSyncRequest request = TermuxSyncRequest.run(1, "/mnt/x/Sylvie's player");
        // Shell-safe form of an apostrophe: close, escaped quote, reopen.
        assertTrue(request.commandLine().contains("'/mnt/x/Sylvie'\\''s player/"
                + TermuxSyncRequest.LOG_FILE_NAME + "'"));
    }

    @Test public void aShellMetacharacterStaysLiteral() {
        TermuxSyncRequest request = TermuxSyncRequest.run(1, "/mnt/x/; rm -rf ~ #");
        String command = request.commandLine();
        assertTrue(command, command.contains("'/mnt/x/; rm -rf ~ #/" + TermuxSyncRequest.LOG_FILE_NAME + "'"));
    }

    @Test public void newlineAndNulAreRefusedRatherThanEscaped() {
        for (String bad : new String[]{"/mnt/a\nb", "/mnt/a\rb", "/mnt/a\u0000b"}) {
            try {
                TermuxSyncRequest.run(1, bad);
                fail("expected a refusal for " + bad.replace("\n", "\\n"));
            } catch (IllegalArgumentException expected) {
                // A shell cannot quote these, so no command is built at all.
            }
        }
    }

    @Test public void aRelativeOrEmptyRootIsRefused() {
        for (String bad : new String[]{"", "media/player", null}) {
            try {
                TermuxSyncRequest.run(1, bad);
                fail("expected a refusal for " + bad);
            } catch (IllegalArgumentException expected) {
                // Never guess where the player is.
            }
        }
    }

    @Test public void minutesOutsideTheBoundedRangeAreRefused() {
        for (int bad : new int[]{0, -5, TermuxSyncRequest.MAX_MINUTES + 1}) {
            try {
                TermuxSyncRequest.run(bad);
                fail("expected a refusal for " + bad);
            } catch (IllegalArgumentException expected) {
                // A run with no bound could hold the phone open indefinitely.
            }
        }
    }
}
