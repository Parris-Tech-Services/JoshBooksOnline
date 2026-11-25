package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import au.com.parristech.ruizusync.core.TermuxSyncRequest;

/**
 * Hands one {@link TermuxSyncRequest} to Termux, which runs it as Termux's own user.
 *
 * <p>This is the whole of the app's involvement in the episode pipeline: it starts a run and reads
 * the log the run leaves on the player. It never copies an episode, and it keeps no record of what
 * was copied — {@code pipeline.db} in Termux remains the single owner of that truth.
 */
public final class TermuxSync {

    /** What happened when the request was handed over. */
    public enum Result {
        /** Termux accepted the request; whether the run succeeds is reported by the log. */
        SENT,
        /** Termux is not installed, so there is no pipeline to run. */
        NOT_INSTALLED,
        /** Termux is installed but has not granted this app permission to run commands. */
        NO_PERMISSION,
        /** The request could not be delivered. */
        FAILED
    }

    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";

    private TermuxSync() {
    }

    public static boolean installed(Context context) {
        if (context == null) {
            return false;
        }
        Intent intent = new Intent(TermuxSyncRequest.ACTION_RUN_COMMAND);
        intent.setPackage(TermuxSyncRequest.TERMUX_PACKAGE);
        return intent.resolveActivity(context.getPackageManager()) != null;
    }

    /** Sends the request. Never throws: every failure is a value the caller can report honestly. */
    public static Result send(Context context, TermuxSyncRequest request) {
        if (context == null || request == null) {
            return Result.FAILED;
        }
        if (!installed(context)) {
            return Result.NOT_INSTALLED;
        }
        if (context.checkSelfPermission(TermuxSyncRequest.PERMISSION_RUN_COMMAND)
                != PackageManager.PERMISSION_GRANTED) {
            return Result.NO_PERMISSION;
        }
        try {
            Intent intent = new Intent(TermuxSyncRequest.ACTION_RUN_COMMAND);
            intent.setPackage(TermuxSyncRequest.TERMUX_PACKAGE);
            intent.putExtra(EXTRA_PATH, request.executable());
            intent.putExtra(EXTRA_ARGUMENTS, request.arguments());
            intent.putExtra(EXTRA_WORKDIR, TermuxSyncRequest.WORK_DIR);
            // The run must outlive the app: the user is expected to lock the phone.
            intent.putExtra(EXTRA_BACKGROUND, true);
            context.startService(intent);
            return Result.SENT;
        } catch (Throwable t) {
            return Result.FAILED;
        }
    }
}
