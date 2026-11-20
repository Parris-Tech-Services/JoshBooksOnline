package au.com.parristech.ruizusync;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One serial queue for all destinations prevents duplicate races. No background restarts without sources. */
public final class TransferService extends Service {
    private static final String CHANNEL = "transfers";
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static final ConcurrentHashMap<Integer, Runnable> TASKS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static volatile String status = "Ready. Existing RUIZU files are preserved.";
    private volatile int latestStartId;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    public static boolean cancelled() { return CANCELLED.get(); }
    public static String status() { return status; }
    public static void report(String value) { status = value; }
    public static void cancel() { CANCELLED.set(true); report("Cancelling; waiting for I/O and cleanup..."); }
    public static void submit(Context context, Runnable task) { submit(context, task, java.util.Collections.emptyList()); }
    public static synchronized void submit(Context context, Runnable task, java.util.List<android.net.Uri> sources) {
        if (PENDING.get() >= 250) { report("Queue full; wait for transfers to finish"); return; }
        if (PENDING.getAndIncrement() == 0) CANCELLED.set(false);
        int id = NEXT.incrementAndGet();
        TASKS.put(id, task);
        try {
            Intent intent = new Intent(context, TransferService.class).putExtra("task", id);
            if (!sources.isEmpty()) {
                ClipData clips = ClipData.newRawUri("Shared audio", sources.get(0));
                for (int i = 1; i < sources.size(); i++) clips.addItem(new ClipData.Item(sources.get(i)));
                intent.setClipData(clips);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            context.startForegroundService(intent);
        }
        catch (RuntimeException error) {
            TASKS.remove(id); PENDING.decrementAndGet();
            report("Could not start transfer; keep the app open and try again");
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RUIZUSync:transfer");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Audio transfers", NotificationManager.IMPORTANCE_LOW));
    }
    private void showNotification() {
        Intent cancel = new Intent(this, TransferService.class).setAction("cancel");
        PendingIntent action = PendingIntent.getService(this, 0, cancel, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("RUIZU Sync")
            .setContentText("Transferring and verifying audio. Tap to view progress.")
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_delete, "Cancel", action).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        showNotification();
        if (intent != null && "cancel".equals(intent.getAction())) { cancel(); if (PENDING.get() == 0) stopSelf(); return START_NOT_STICKY; }
        Runnable task = intent == null ? null : TASKS.remove(intent.getIntExtra("task", -1));
        if (task == null) { if (PENDING.get() == 0) stopSelf(); return START_NOT_STICKY; }
        worker.execute(() -> {
            try { if (!cancelled()) task.run(); }
            catch (RuntimeException error) { report("Transfer failed; reconnect and retry"); }
            finally {
                synchronized (TransferService.class) {
                    if (PENDING.decrementAndGet() == 0) {
                        if (cancelled()) report("Cancelled. Reconnect USB and reselect files to retry safely.");
                        stopSelfResult(latestStartId);
                    }
                }
            }
        });
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId, int fgsType) { cancel(); stopSelf(); }
    @Override public void onDestroy() { worker.shutdown(); if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
