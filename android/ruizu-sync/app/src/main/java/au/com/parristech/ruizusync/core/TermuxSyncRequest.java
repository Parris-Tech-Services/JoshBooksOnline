package au.com.parristech.ruizusync.core;

/**
 * The request that asks Termux to run one bounded {@code ruizu-episodes} run.
 *
 * <p>The episode pipeline is the only writer of episodes to {@code VIDEO/} and its database is the
 * only record of what was copied, so the app never encodes, copies or remembers episodes itself.
 * This class builds the single shell command the app hands to Termux, and nothing else: it holds no
 * state and touches no file.
 *
 * <p>Pure Java with no Android types, so the exact command line is covered by the JVM test suite
 * instead of being discovered on a device.
 */
public final class TermuxSyncRequest {

    /** Termux's Android package. */
    public static final String TERMUX_PACKAGE = "com.termux";

    /** Intent action Termux listens for to run a command on another app's behalf. */
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    /** Permission the app must hold, granted by Termux, before it may run a command. */
    public static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    /** Absolute path of the shell used to run the tool, so PATH and redirection both work. */
    public static final String BASH_PATH = "/data/data/com.termux/files/usr/bin/bash";

    /** Termux home, which is where the tool keeps its database and staging folder. */
    public static final String WORK_DIR = "/data/data/com.termux/files/home";

    /** The installed command name; {@code install.sh} puts it on PATH. */
    public static final String TOOL = "ruizu-episodes";

    /**
     * Where a run writes its output. It lives on the player so the app can read it back through
     * the Storage Access Framework without any extra permission and without a second record of
     * what happened.
     */
    public static final String LOG_FILE_NAME = ".ruizu-sync-run.log";

    /** Last line of a run's log: the tool's exit status, which the app reports as success or failure. */
    public static final String EXIT_MARKER = "__ruizu-exit:";

    /** The player path the tool already defaults to, overridable through its own environment. */
    public static final String DEFAULT_PLAYER_EXPRESSION = "${RUIZU_PLAYER_ROOTS:-/mnt/media_rw/0000-006F}";

    /** A run long enough to copy and prepare a couple of episodes without holding the phone. */
    public static final int DEFAULT_MINUTES = 7;

    /** Upper bound offered to the user; Android stops scheduled work after about ten minutes. */
    public static final int MAX_MINUTES = 600;

    private static final int MAX_ROOT_CHARS = 200;

    private final String commandLine;

    private TermuxSyncRequest(String commandLine) {
        this.commandLine = commandLine;
    }

    /** A run against whichever player the tool finds, using its own default root. */
    public static TermuxSyncRequest run(int minutes) {
        checkMinutes(minutes);
        // Double quotes, not single: this expression has to expand in the shell, and the first
        // component of a path list is the mounted player. The file name is a constant, so the only
        // thing that can vary is the root the user's own environment chose.
        String log = "\"${RUIZU_PLAYER_ROOTS%%:*}/" + LOG_FILE_NAME + "\"";
        return new TermuxSyncRequest(body(minutes, log));
    }

    /** A run that writes its log under an explicit player root, used by the tests. */
    public static TermuxSyncRequest run(int minutes, String playerRoot) {
        checkMinutes(minutes);
        String root = requireAbsolutePath(playerRoot);
        return new TermuxSyncRequest(body(minutes, quote(root + "/" + LOG_FILE_NAME)));
    }

    /** The absolute executable Termux should run. */
    public String executable() {
        return BASH_PATH;
    }

    /** Arguments for that executable: a login shell so the tool is on PATH. */
    public String[] arguments() {
        return new String[]{"-lc", commandLine};
    }

    /** The shell command itself, exactly as Termux will receive it. */
    public String commandLine() {
        return commandLine;
    }

    private static String body(int minutes, String quotedLog) {
        // The exit status is written into the log inside the same redirect group, so "the run
        // finished" and "this was its result" can never be separated by a dying shell.
        return "{ " + TOOL + " run --minutes " + minutes + " --quiet; echo \"" + EXIT_MARKER + "$?\"; }"
                + " > " + quotedLog + " 2>&1";
    }

    private static void checkMinutes(int minutes) {
        if (minutes < 1 || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("minutes must be between 1 and " + MAX_MINUTES);
        }
    }

    private static String requireAbsolutePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("player root is required");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("player root must be an absolute path");
        }
        if (path.length() > MAX_ROOT_CHARS) {
            throw new IllegalArgumentException("player root is too long");
        }
        return path;
    }

    /**
     * Single-quotes a shell word so no character in it can be read as syntax. A NUL or a newline is
     * refused rather than escaped: both would end the word in a real shell.
     */
    static String quote(String word) {
        if (word.indexOf('\u0000') >= 0 || word.indexOf('\n') >= 0 || word.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("path contains a character a shell cannot quote");
        }
        return "'" + word.replace("'", "'\\''") + "'";
    }
}
