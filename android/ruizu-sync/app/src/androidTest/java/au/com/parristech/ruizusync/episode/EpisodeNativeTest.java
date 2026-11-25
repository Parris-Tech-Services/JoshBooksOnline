package au.com.parristech.ruizusync.episode;

import android.content.ContentValues;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Executes the actual APK-bundled Android binaries, not the host FFmpeg. */
@RunWith(AndroidJUnit4.class)
public class EpisodeNativeTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void bundledNativeCodecProducesAndDecodesAmv() throws Exception {
        Context c=context();
        File dir=new File(c.getCacheDir(),"episode-native-test-"+UUID.randomUUID());
        assertTrue(dir.mkdirs());
        try {
            File ffmpeg=new File(c.getApplicationInfo().nativeLibraryDir,"libruizu_ffmpeg.so");
            File ffprobe=new File(c.getApplicationInfo().nativeLibraryDir,"libruizu_ffprobe.so");
            assertTrue(ffmpeg.canExecute()); assertTrue(ffprobe.canExecute());
            File video=new File(dir,"video.raw"),audio=new File(dir,"audio.pcm"),amv=new File(dir,"test.amv");
            try(FileOutputStream out=new FileOutputStream(video)) {
                byte[] frame=new byte[160*128*3/2];
                Arrays.fill(frame,(byte)128);
                for(int i=0;i<15;i++) out.write(frame);
            }
            try(FileOutputStream out=new FileOutputStream(audio)) { out.write(new byte[22050*2]); }
            List<String> args=new ArrayList<>(EpisodeCodec.encode(ffmpeg.getAbsolutePath(),video.getAbsolutePath(),amv.getAbsolutePath()));
            int input=args.indexOf("-i");
            args.addAll(input,Arrays.asList("-f","rawvideo","-pixel_format","yuv420p","-video_size","160x128","-framerate","15"));
            args.addAll(args.indexOf("-map"),Arrays.asList("-f","s16le","-ar","22050","-ac","1","-i",audio.getAbsolutePath()));
            args.set(args.indexOf("0:a:0"),"1:a:0");
            run(args,dir);
            assertTrue(amv.length()>0);
            JSONObject info=new JSONObject(run(Arrays.asList(ffprobe.getAbsolutePath(),"-v","error","-count_frames","-show_streams","-of","json",amv.getAbsolutePath()),dir));
            JSONArray streams=info.getJSONArray("streams");
            JSONObject v=null,a=null;
            for(int i=0;i<streams.length();i++) {
                JSONObject s=streams.getJSONObject(i);
                if("video".equals(s.optString("codec_type"))) v=s;
                if("audio".equals(s.optString("codec_type"))) a=s;
            }
            assertNotNull(v); assertNotNull(a);
            String[] rate=v.getString("avg_frame_rate").split("/");
            assertTrue(EpisodeCodec.validProfile(v.getString("codec_name"),v.getInt("width"),v.getInt("height"),
                a.getString("codec_name"),a.getInt("sample_rate"),a.getInt("channels"),
                Double.parseDouble(rate[0])/Double.parseDouble(rate[1]),v.getLong("nb_read_frames"),1));
            run(Arrays.asList(ffmpeg.getAbsolutePath(),"-nostdin","-v","error","-xerror","-i",amv.getAbsolutePath(),
                "-map","0:v:0","-map","0:a:0","-f","null","-"),dir);
        } finally {
            File[] files=dir.listFiles();
            if(files!=null) for(File f:files) f.delete();
            dir.delete();
        }
    }
    private static String run(List<String> args,File dir) throws Exception {
        File output=File.createTempFile("process-", ".log",dir);
        Process process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start();
        try {
            assertTrue("Native process timed out",process.waitFor(90,TimeUnit.SECONDS));
            String result=new String(java.nio.file.Files.readAllBytes(output.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(result,0,process.exitValue());
            return result;
        } finally { if(process.isAlive()) process.destroyForcibly(); output.delete(); }
    }

    @Test public void databaseCheckpointAndRetrySurviveReopening() {
        Context c=context();
        // Instrumentation uses a separate application ID; never wipe a user's installed app.
        assertTrue(c.getPackageName().endsWith(".saftest"));
        c.deleteDatabase("video-episodes.db");
        try {
            try(EpisodeQueue q=new EpisodeQueue(c)) {
                ContentValues target=new ContentValues();
                target.put("id","test-player"); target.put("tree","content://test/tree/root"); target.put("name","Test player");
                assertTrue(q.getWritableDatabase().insert("targets",null,target)>=0);
                ContentValues row=new ContentValues();
                row.put("target","test-player"); row.put("id","test-episode"); row.put("uri","content://test/document/episode");
                row.put("name","synthetic.mp4"); row.put("bytes",200); row.put("stage","staged");
                row.put("sha","fixture-hash"); row.put("attempts",2); row.put("next_retry",1234567L); row.put("error","fixture error");
                assertTrue(q.getWritableDatabase().insert("episodes",null,row)>=0);
            }
            try(EpisodeQueue q=new EpisodeQueue(c)) {
                assertTrue(q.status().contains("staged: 1 (1 retrying)"));
                q.retry();
                assertTrue(q.status().contains("staged: 1"));
                assertFalse(q.status().contains("retrying"));
                try(android.database.Cursor row=q.getReadableDatabase().rawQuery("SELECT sha,stage,next_retry FROM episodes",null)) {
                    assertTrue(row.moveToFirst());
                    assertEquals("fixture-hash",row.getString(0)); assertEquals("staged",row.getString(1)); assertEquals(0L,row.getLong(2));
                }
            }
        } finally { c.deleteDatabase("video-episodes.db"); }
    }

    @Test public void dueRowsSeparateReadyCopiesFromPreparationAndRespectRetry() {
        Context c=context();
        assertTrue(c.getPackageName().endsWith(".saftest"));
        c.deleteDatabase("video-episodes.db");
        try(EpisodeQueue q=new EpisodeQueue(c)) {
            android.database.sqlite.SQLiteDatabase db=q.getWritableDatabase();
            addFixture(db,"large", "queued",900,0);
            addFixture(db,"ready", "staged",700,0);
            addFixture(db,"small", "queued",100,0);
            addFixture(db,"complete", "verified",50,0);
            addFixture(db,"backoff", "staged",50,Long.MAX_VALUE);
            addFixture(db,"unknown", "queued",-1,0);
            assertEquals(Arrays.asList("ready"), ids(db,EpisodeQueue.DUE_STAGED_SQL));
            assertEquals(Arrays.asList("small","large","unknown"), ids(db,EpisodeQueue.DUE_PREPARE_SQL));
        } finally { c.deleteDatabase("video-episodes.db"); }
    }
    private static void addFixture(android.database.sqlite.SQLiteDatabase db,String id,String stage,long size,long retry) {
        ContentValues row=new ContentValues();
        row.put("target","test-player"); row.put("id",id); row.put("uri","content://test/"+id);
        row.put("name",id+".mp4"); row.put("bytes",size); row.put("stage",stage); row.put("next_retry",retry);
        assertTrue(db.insert("episodes",null,row)>=0);
    }
    private static List<String> ids(android.database.sqlite.SQLiteDatabase db,String sql) {
        List<String> ids=new ArrayList<>();
        try(android.database.Cursor c=db.rawQuery(sql,new String[]{Long.toString(System.currentTimeMillis())})) {
            while(c.moveToNext()) ids.add(c.getString(1));
        }
        return ids;
    }
}
