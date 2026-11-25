package au.com.parristech.ruizusync.core;

/**
 * Reads the log a {@code ruizu-episodes} run leaves on the player.
 *
 * <p>The log is written by a different process on a volume the app only sees through the Storage
 * Access Framework, so it can be absent, half-written, truncated or replaced by something else.
 * Everything here is therefore total: no input makes it throw, and an unfinished log is reported as
 * running rather than as a result.
 */
public final class RunLogTail {

    /** How the app describes a log. */
    public enum State {
        /** No log there yet, or nothing usable in it. */
        UNKNOWN,
        /** The tool is still working; the exit marker has not been written. */
        RUNNING,
        /** The run finished and reported success. */
        FINISHED,
        /** The run finished and reported a non-zero status. */
        FAILED
    }

    /** Lines kept for display. */
    public static final int MAX_LINES = 8;

    /** Characters kept for display, so a runaway log cannot fill the screen. */
    public static final int MAX_CHARS = 1200;

    /** Characters kept from a single line. */
    public static final int MAX_LINE_CHARS = 200;

    private RunLogTail() {
    }

    /** Exit status recorded in the log, or -1 when the run has not reported one. */
    public static int exitCode(String logText) {
        String line = lastExitLine(logText);
        if (line == null) {
            return -1;
        }
        try {
            return Integer.parseInt(line.substring(line.lastIndexOf(TermuxSyncRequest.EXIT_MARKER)
                    + TermuxSyncRequest.EXIT_MARKER.length()).trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static State stateOf(String logText) {
        if (logText == null || logText.trim().isEmpty()) {
            return State.UNKNOWN;
        }
        int code = exitCode(logText);
        if (code < 0) {
            return State.RUNNING;
        }
        return code == 0 ? State.FINISHED : State.FAILED;
    }

    /** The last few meaningful lines, bounded, with the exit marker removed. */
    public static String tail(String logText) {
        if (logText == null || logText.isEmpty()) {
            return "";
        }
        String[] lines = logText.split("\r?\n");
        StringBuilder kept = new StringBuilder();
        int taken = 0;
        for (int i = lines.length - 1; i >= 0 && taken < MAX_LINES; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith(TermuxSyncRequest.EXIT_MARKER)) {
                continue;
            }
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + "…";
            }
            kept.insert(0, taken == 0 ? line : line + "\n");
            taken++;
        }
        String text = kept.toString();
        return text.length() <= MAX_CHARS ? text : text.substring(text.length() - MAX_CHARS);
    }

    /** One honest sentence for the user; never claims an outcome the log does not show. */
    public static String describe(String logText) {
        State state = stateOf(logText);
        switch (state) {
            case FINISHED:
                return "Last run finished (exit 0).";
            case FAILED:
                return "Last run finished with errors (exit " + exitCode(logText) + ").";
            case RUNNING:
                return "A run is in progress.";
            default:
                return "No run recorded on this player yet.";
        }
    }

    /**
     * The marker, but only when it is the last meaningful line of the log.
     *
     * <p>A run appends its marker when it ends, and the next run truncates and rewrites the same
     * file. If the phone died between those two moments the log can hold an old marker followed by a
     * newer run's output, and treating that as a result would report a finished run that is in fact
     * still going.
     */
    private static String lastExitLine(String logText) {
        if (logText == null) {
            return null;
        }
        String[] lines = logText.split("\r?\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            return line.startsWith(TermuxSyncRequest.EXIT_MARKER) ? line : null;
        }
        return null;
    }
}
