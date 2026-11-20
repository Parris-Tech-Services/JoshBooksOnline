package au.com.parristech.ruizusync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import au.com.parristech.ruizusync.core.ByteFormat;
import au.com.parristech.ruizusync.ui.MainActivity;

/**
 * Keeps the transfer alive in the background and mirrors its progress into a notification with a
 * Cancel action. The work itself lives in {@link TransferManager}; this service only keeps the
 * process in the foreground while the transfer runs.
 */
public final class TransferService extends Service implements TransferManager.Listener {

    public static final String ACTION_START = "au.com.parristech.ruizusync.action.START";
    public static final String ACTION_CANCEL = "au.com.parristech.ruizusync.action.CANCEL";

    private static final String CHANNEL_ID = "transfer";
    private static final int NOTIFICATION_ID = 4711;
    private static final long NOTIFICATION_INTERVAL_MS = 500L;

    private TransferManager manager;
    private NotificationManager notificationManager;
    private long lastNotificationUpdate;

    public static void start(Context context) { start(context, java.util.Collections.emptyList()); }
    public static boolean start(Context context, java.util.List<au.com.parristech.ruizusync.model.SourceItem> sources) {
        Intent intent = new Intent(context, TransferService.class).setAction(ACTION_START);
        android.content.ClipData grants = null;
        for (au.com.parristech.ruizusync.model.SourceItem source : sources) {
            if (!"content".equals(source.uri.getScheme())) continue;
            if (grants == null) grants = android.content.ClipData.newRawUri("Audio", source.uri);
            else grants.addItem(new android.content.ClipData.Item(source.uri));
        }
        if (grants != null) { intent.setClipData(grants); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel();
        manager = TransferManager.get(this);
        manager.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            manager.cancel();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(manager.snapshot()));
        if (!manager.isRunning()) {
            stopForegroundCompat();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTransferState(TransferManager.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.isFinished()) {
            postResultNotification(snapshot);
            stopForegroundCompat();
            stopSelf();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate < NOTIFICATION_INTERVAL_MS) {
            return;
        }
        lastNotificationUpdate = now;
        try {
            startForeground(NOTIFICATION_ID, buildNotification(snapshot));
        } catch (Throwable ignored) {
            // Keep transferring regardless.
        }
    }

    @Override public void onTimeout(int startId, int fgsType) { manager.cancel(); stopSelf(); }

    @Override
    public void onDestroy() {
        manager.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void postResultNotification(TransferManager.Snapshot snapshot) {
        if (notificationManager == null) {
            return;
        }
        try {
            Notification.Builder builder = newNotificationBuilder()
                    .setSmallIcon(R.drawable.ic_stat_sync)
                    .setContentTitle(TransferManager.PHASE_ERROR.equals(snapshot.phase) ? "RUIZU Sync stopped" : "RUIZU Sync finished")
                    .setContentText(snapshot.status == null ? "Transfer finished" : snapshot.status)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setLocalOnly(true)
                    .setContentIntent(activityIntent());
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
            // Notifications are only a convenience here.
        }
    }

    private Notification buildNotification(TransferManager.Snapshot snapshot) {
        Notification.Builder builder = newNotificationBuilder()
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle("RUIZU Sync")
                .setContentText(notificationText(snapshot))
                .setOngoing(snapshot.running)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setContentIntent(activityIntent());

        if (snapshot.currentTotal > 0 && snapshot.running) {
            int percent = (int) Math.round(snapshot.currentFraction * 100);
            builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        } else if (snapshot.running) {
            builder.setProgress(0, 0, true);
        } else {
            builder.setProgress(0, 0, false);
        }
        if (snapshot.running) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent()).build());
        }
        return builder.build();
    }

    private String notificationText(TransferManager.Snapshot snapshot) {
        if (TransferManager.PHASE_SCANNING.equals(snapshot.phase)) {
            return "Checking the player folder…";
        }
        if (snapshot.running) {
            int percent = (int) Math.round(snapshot.fraction * 100);
            return snapshot.completedFiles + "/" + snapshot.totalFiles + " files · " + percent + "% · "
                    + ByteFormat.speed(snapshot.bytesPerSecond);
        }
        return snapshot.status == null ? "Transfer finished" : snapshot.status;
    }

    private Notification.Builder newNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }

    private PendingIntent activityIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent, pendingIntentFlags());
    }

    private PendingIntent cancelIntent() {
        Intent intent = new Intent(this, TransferService.class).setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 1, intent, pendingIntentFlags());
    }

    private static int pendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Transfers",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Music transfer progress to the RUIZU player");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
            // Nothing to do.
        }
    }
}
