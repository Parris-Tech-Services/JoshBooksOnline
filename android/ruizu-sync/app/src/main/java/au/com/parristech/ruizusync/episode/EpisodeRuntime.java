package au.com.parristech.ruizusync.episode;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native scheduled work and a user-initiated foreground worker for long video conversions. */
public final class EpisodeRuntime {
    private static final int PERIODIC=7411, USB=7412, NOW=7413, NOTIFICATION=7414;
    private static final String PREF="episode_runtime", CHANNEL="ruizu_episodes";
    private EpisodeRuntime() {}
    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREF,0).getBoolean("automatic",false);
    }
    public static void setEnabled(Context c,boolean value) {
        c.getSharedPreferences(PREF,0).edit().putBoolean("automatic",value).apply();
        if(value) { ensureSchedule(c); schedule(c,NOW,false); }
        else {
            JobScheduler jobs=c.getSystemService(JobScheduler.class);
            if(jobs!=null) { jobs.cancel(PERIODIC); jobs.cancel(USB); jobs.cancel(NOW); }
        }
    }
    public static void ensureSchedule(Context c) {
        if(!enabled(c)) return;
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(jobs==null || jobs.getPendingJob(PERIODIC)!=null) return;
        jobs.schedule(new JobInfo.Builder(PERIODIC,new ComponentName(c,Scheduled.class))
            .setPeriodic(15*60_000L).setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build());
    }
    public static void onPlayerConnected(Context c) {
        if(enabled(c)) schedule(c,USB,true);
    }
    public static void requestDiscovery(Context c) {
        if(enabled(c)) schedule(c,NOW,false);
    }
    private static void schedule(Context c,int id,boolean usbOnly) {
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(jobs==null || jobs.getPendingJob(id)!=null) return;
        JobInfo.Builder b=new JobInfo.Builder(id,new ComponentName(c,Scheduled.class))
            .setPersisted(true).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true)
            .setMinimumLatency(1000);
        if(!usbOnly) b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
        jobs.schedule(b.build());
    }
    public static void runNow(Context c) {
        c.startForegroundService(new Intent(c,Foreground.class));
    }
    public static void pauseNow(Context c) {
        c.stopService(new Intent(c,Foreground.class));
    }
    /** System storage mount broadcast can schedule an offline transfer even when the UI is closed. */
    public static final class UsbMount extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction())) onPlayerConnected(context);
        }
    }
    public static final class Scheduled extends JobService {
        private final ConcurrentHashMap<Integer,AtomicBoolean> running=new ConcurrentHashMap<>();
        @Override public boolean onStartJob(JobParameters params) {
            if(!enabled(this)) return false;
            final int id=params.getJobId();
            AtomicBoolean stop=new AtomicBoolean();
            if(running.putIfAbsent(id,stop)!=null) return false;
            new Thread(()->{
                boolean retry=false;
                try(EpisodeQueue queue=new EpisodeQueue(this)) {
                    boolean ran=queue.run(()->stop.get() || !enabled(this),id==USB?Integer.MAX_VALUE:1,id==USB);
                    // A mount event that arrives during conversion must not be lost.
                    retry=!ran && id==USB;
                } catch(Exception e) {
                    android.util.Log.w("RUIZU","Episode scheduled worker paused",e);
                    retry=true;
                } finally {
                    if(running.remove(id,stop) && !stop.get()) jobFinished(params,retry && enabled(this));
                }
            },"ruizu-episode-job").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters params) {
            AtomicBoolean stop=running.remove(params.getJobId());
            if(stop!=null) stop.set(true);
            return enabled(this);
        }
        @Override public void onDestroy() {
            for(AtomicBoolean stop:running.values()) stop.set(true);
            running.clear(); super.onDestroy();
        }
    }
    public static final class Foreground extends Service {
        private final AtomicBoolean stopped=new AtomicBoolean();
        private final AtomicBoolean started=new AtomicBoolean();
        @Override public IBinder onBind(Intent intent) { return null; }
        @Override public int onStartCommand(Intent intent,int flags,int id) {
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Episode processing",NotificationManager.IMPORTANCE_LOW));
            Intent open=new Intent(this,EpisodeActivity.class);
            PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notice=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("RUIZU Sync: episode queue")
                .setContentText("Preparing and verifying videos. Open RUIZU Sync to pause or see progress.")
                .setContentIntent(content).setOngoing(true).build();
            if(Build.VERSION.SDK_INT>=35)
                startForeground(NOTIFICATION,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING|ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION,notice);
            if(started.compareAndSet(false,true)) {
                new Thread(()->{
                    try(EpisodeQueue queue=new EpisodeQueue(this)) {
                        // If a short scheduled job already owns the queue, wait for it to yield.
                        while(EpisodeQueue.isActive()&&!stopped.get()) Thread.sleep(1000);
                        if(!stopped.get()) queue.run(stopped::get,Integer.MAX_VALUE,false);
                    } catch(Exception e) { android.util.Log.w("RUIZU","Episode foreground worker paused",e); }
                    finally { stopSelf(); }
                },"ruizu-episode-foreground").start();
            }
            return START_NOT_STICKY;
        }
        @Override public void onTimeout(int startId,int fgsType) { stopped.set(true); stopSelf(); }
        @Override public void onDestroy() { stopped.set(true); super.onDestroy(); }
    }
}
