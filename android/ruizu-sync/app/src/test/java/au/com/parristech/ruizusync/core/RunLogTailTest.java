package au.com.parristech.ruizusync.core;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The run log is written by another process on a volume the app only sees through SAF, so it can be
 * absent, half-written or foreign. The screen must never claim a result the log does not show.
 */
public final class RunLogTailTest {

    @Test public void noLogIsUnknownNotSuccess() {
        assertEquals(RunLogTail.State.UNKNOWN, RunLogTail.stateOf(null));
        assertEquals(RunLogTail.State.UNKNOWN, RunLogTail.stateOf(""));
        assertEquals(RunLogTail.State.UNKNOWN, RunLogTail.stateOf("   \n  \n"));
        assertEquals(-1, RunLogTail.exitCode(null));
        assertEquals("No run recorded on this player yet.", RunLogTail.describe(null));
    }

    @Test public void aLogWithoutTheMarkerIsStillRunning() {
        assertEquals(RunLogTail.State.RUNNING,
                RunLogTail.stateOf("copying Heroes S02E03.amv\n"));
        assertEquals(-1, RunLogTail.exitCode("copying Heroes S02E03.amv\n"));
        assertEquals("A run is in progress.", RunLogTail.describe("copying\n"));
    }

    @Test public void exitZeroIsFinished() {
        String log = "copied 2 episodes\n" + TermuxSyncRequest.EXIT_MARKER + "0\n";
        assertEquals(0, RunLogTail.exitCode(log));
        assertEquals(RunLogTail.State.FINISHED, RunLogTail.stateOf(log));
        assertEquals("Last run finished (exit 0).", RunLogTail.describe(log));
    }

    @Test public void nonZeroExitIsFailedAndReportedWithItsCode() {
        String log = "boom\n" + TermuxSyncRequest.EXIT_MARKER + "3\n";
        assertEquals(3, RunLogTail.exitCode(log));
        assertEquals(RunLogTail.State.FAILED, RunLogTail.stateOf(log));
        assertEquals("Last run finished with errors (exit 3).", RunLogTail.describe(log));
    }

    @Test public void theLastMarkerWinsSoARestartedRunCannotLookFinished() {
        String log = TermuxSyncRequest.EXIT_MARKER + "0\nsecond run started\n";
        assertEquals(RunLogTail.State.RUNNING, RunLogTail.stateOf(log));
    }

    @Test public void aCorruptMarkerIsNotGuessedAt() {
        String log = TermuxSyncRequest.EXIT_MARKER + "not-a-number\n";
        assertEquals(-1, RunLogTail.exitCode(log));
        assertEquals(RunLogTail.State.RUNNING, RunLogTail.stateOf(log));
    }

    @Test public void tailKeepsTheEndBoundedAndDropsTheMarker() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            log.append("line ").append(i).append('\n');
        }
        log.append(TermuxSyncRequest.EXIT_MARKER).append("0\n");
        String tail = RunLogTail.tail(log.toString());
        String[] lines = tail.split("\n");
        assertEquals(RunLogTail.MAX_LINES, lines.length);
        assertEquals("line 39", lines[lines.length - 1]);
        assertFalse(tail.contains(TermuxSyncRequest.EXIT_MARKER));
        assertTrue(tail.length() <= RunLogTail.MAX_CHARS);
    }

    @Test public void anEnormousSingleLineIsTruncated() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            log.append('x');
        }
        String tail = RunLogTail.tail(log.toString());
        assertTrue(tail.length() <= RunLogTail.MAX_LINE_CHARS + 1);
        assertTrue(tail.endsWith("…"));
    }
}
