package au.com.parristech.ruizusync.episode;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import au.com.parristech.ruizusync.PlayerMarkerStore;
import au.com.parristech.ruizusync.PlayerFolderStore;
import au.com.parristech.ruizusync.SafTransfer;
import au.com.parristech.ruizusync.TransferManager;
import au.com.parristech.ruizusync.core.HashUtil;
import au.com.parristech.ruizusync.core.FileNameSanitizer;
import au.com.parristech.ruizusync.io.DestRepo;
import au.com.parristech.ruizusync.io.SourceRepo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Durable per-player catalogue and stage checkpoints. Only app-private staging files are deleted. */
public final class EpisodeQueue extends SQLiteOpenHelper {
    private static final long DAY = 86_400_000L, RESERVE = 512L * 1024 * 1024;
    private static final long MAX_SOURCE = 8L * 1024 * 1024 * 1024;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static volatile boolean writing;
    public static boolean isWriting() { return writing; }
    public static boolean isActive() { return ACTIVE.get(); }
    private final Context context;
    private final File work;
    public EpisodeQueue(Context c) {
        super(c.getApplicationContext(), "video-episodes.db", null, 1);
        context = c.getApplicationContext();
        work = new File(context.getFilesDir(), "episode-staging");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE targets (id TEXT PRIMARY KEY, tree TEXT NOT NULL, name TEXT NOT NULL)");
        db.execSQL("CREATE TABLE libraries (target TEXT NOT NULL, tree TEXT NOT NULL, last_scan INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', PRIMARY KEY(target,tree))");
        db.execSQL("CREATE TABLE episodes (target TEXT NOT NULL, id TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, bytes INTEGER NOT NULL, stage TEXT NOT NULL DEFAULT 'queued', sha TEXT, attempts INTEGER NOT NULL DEFAULT 0, next_retry INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', PRIMARY KEY(target,id))");
        db.execSQL("CREATE INDEX episode_due ON episodes(stage,next_retry)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing episode database migration");
    }
    public void bind(String id, String name, Uri tree) throws IOException {
        if (id == null || !id.equals(PlayerMarkerStore.read(context, tree).deviceId())
                || !PlayerFolderStore.hasPersistedPermission(context, tree)) {
            throw new IOException("Choose the identified RUIZU player root on the main screen first");
        }
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("tree", tree.toString()); v.put("name", name == null ? "Player" : name);
        getWritableDatabase().insertWithOnConflict("targets", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    /** A source must be explicitly assigned to a saved target and never be its own USB volume. */
    private void requireSource(String target, Uri source) throws IOException {
        if (target==null || source==null) throw new IOException("Choose an identified player and a source");
        try (Cursor c=getReadableDatabase().rawQuery("SELECT tree FROM targets WHERE id=?",new String[]{target})) {
            if(!c.moveToFirst()) throw new IOException("Bind the selected player first");
            Uri destination=Uri.parse(c.getString(0));
            if(destination.equals(source)) throw new IOException("Source cannot be the player destination");
            if("com.android.externalstorage.documents".equals(destination.getAuthority()) &&
                    destination.getAuthority().equals(source.getAuthority())) {
                String destId=DocumentsContract.getTreeDocumentId(destination);
                String sourceId=DocumentsContract.isTreeUri(source)
                    ? DocumentsContract.getTreeDocumentId(source) : DocumentsContract.getDocumentId(source);
                // SAF volume ids are not reliable player identities; they are nevertheless
                // sufficient to reject reading from the same storage volume as the destination.
                String destVolume=destId.split(":",2)[0];
                String sourceVolume=sourceId.split(":",2)[0];
                if(destVolume.equalsIgnoreCase(sourceVolume))
                    throw new IOException("Select a source outside the player's storage volume");
            }
        }
    }
    public void addLibrary(String target, Uri tree) throws IOException {
        if (tree==null || !DocumentsContract.isTreeUri(tree)) throw new IOException("Choose a player and folder");
        requireSource(target,tree);
        ContentValues v = new ContentValues(); v.put("target", target); v.put("tree", tree.toString());
        getWritableDatabase().insertWithOnConflict("libraries", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }
    public void refreshCatalogue() {
        getWritableDatabase().execSQL("UPDATE libraries SET last_scan=0");
    }
    public void retry() {
        getWritableDatabase().execSQL("UPDATE episodes SET next_retry=0, attempts=0, error='' WHERE stage!='verified'");
    }
    public void addFile(String target, Uri uri) throws Exception {
        requireSource(target,uri);
        String name = null; long bytes = -1, modified = 0;
        try (Cursor c = context.getContentResolver().query(uri,
            new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED},
            null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0); bytes = c.isNull(1) ? -1 : c.getLong(1);
                modified = c.isNull(2) ? 0 : c.getLong(2);
            }
        }
        if (!EpisodeCodec.supported(name)) throw new IOException("Choose a supported video file");
        insert(target, uri, name, bytes, modified);
    }
    private void insert(String target, Uri uri, String name, long size, long modified) throws Exception {
        if (target == null || size == 0 || !EpisodeCodec.supported(name)) return;
        String id = hex(MessageDigest.getInstance("SHA-256").digest(
            (uri + "\n" + size + "\n" + modified).getBytes(StandardCharsets.UTF_8)));
        ContentValues v = new ContentValues();
        v.put("target", target); v.put("id", id); v.put("uri", uri.toString());
        v.put("name", name); v.put("bytes", size);
        getWritableDatabase().insertWithOnConflict("episodes", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }
    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte value : bytes) b.append(String.format(Locale.ROOT, "%02x", value & 255));
        return b.toString();
    }
    private void discover(BooleanSupplier stopped) throws Exception {
        List<String[]> libraries = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT target,tree FROM libraries WHERE last_scan<=?",
            new String[]{Long.toString(System.currentTimeMillis() - DAY)})) {
            while (c.moveToNext()) libraries.add(new String[]{c.getString(0), c.getString(1)});
        }
        for (String[] library : libraries) {
            if (stopped.getAsBoolean()) return;
            String error = ""; long finished = System.currentTimeMillis();
            try {
                Uri tree = Uri.parse(library[1]);
                ArrayDeque<String> pending = new ArrayDeque<>();
                Set<String> seen = new HashSet<>();
                pending.add(DocumentsContract.getTreeDocumentId(tree));
                int visited = 0;
                while (!pending.isEmpty()) {
                    if (stopped.getAsBoolean()) return;
                    String dir = pending.removeFirst();
                    if (!seen.add(dir)) continue;
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir);
                    try (Cursor c = context.getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                        if (c == null) throw new IOException("Library provider cannot list folders");
                        while (c.moveToNext()) {
                            if (stopped.getAsBoolean()) return;
                            if (++visited > 100_000) throw new IOException("Library scan limit reached; choose a smaller folder");
                            String doc = c.getString(0);
                            if (doc == null) continue;
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) pending.add(doc);
                            else if (EpisodeCodec.supported(c.getString(1)))
                                insert(library[0], DocumentsContract.buildDocumentUriUsingTree(tree, doc),
                                    c.getString(1), c.isNull(3) ? -1 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4));
                        }
                    }
                }
            } catch (Exception e) {
                error = message(e); finished -= DAY - 15 * 60_000L;
            }
            ContentValues v = new ContentValues(); v.put("last_scan", finished); v.put("error", error);
            getWritableDatabase().update("libraries", v, "target=? AND tree=?", library);
        }
    }
    private static class Row {
        String target,id,uri,name,stage,sha; long bytes; int attempts;
        Row(Cursor c) {
            target=c.getString(0); id=c.getString(1); uri=c.getString(2); name=c.getString(3);
            bytes=c.getLong(4); stage=c.getString(5); sha=c.getString(6); attempts=c.getInt(7);
        }
    }
    private void update(Row r, String stage, String hash, String error, int attempts, long retry) {
        ContentValues v = new ContentValues(); v.put("stage", stage); v.put("sha", hash);
        v.put("error", error); v.put("attempts", attempts); v.put("next_retry", retry);
        getWritableDatabase().update("episodes", v, "target=? AND id=?", new String[]{r.target,r.id});
        r.stage=stage; r.sha=hash; r.attempts=attempts;
    }
    private Uri targetTree(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT tree FROM targets WHERE id=?",new String[]{id})) {
            if (!c.moveToFirst()) return null;
            Uri tree = Uri.parse(c.getString(0));
            return id.equals(PlayerMarkerStore.read(context,tree).deviceId()) &&
                PlayerFolderStore.hasPersistedPermission(context,tree) ? tree : null;
        }
    }
    public Map<String,String> targets() {
        Map<String,String> result=new LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name FROM targets ORDER BY name,id",null)) {
            while(c.moveToNext()) result.put(c.getString(0),c.getString(1));
        }
        return result;
    }
    public String status() {
        StringBuilder b = new StringBuilder();
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT targets.name,episodes.stage,count(*),sum(CASE WHEN episodes.error!='' THEN 1 ELSE 0 END) FROM episodes JOIN targets ON targets.id=episodes.target GROUP BY targets.name,episodes.stage ORDER BY targets.name,episodes.stage",null)) {
            while (c.moveToNext()) b.append(c.getString(0)).append(" · ").append(c.getString(1)).append(": ")
                .append(c.getInt(2)).append(c.getInt(3)>0 ? " ("+c.getInt(3)+" retrying)" : "").append('\n');
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name,error FROM episodes WHERE error!='' ORDER BY next_retry DESC LIMIT 5",null)) {
            while (c.moveToNext()) b.append(c.getString(0)).append(": ").append(c.getString(1)).append('\n');
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT error FROM libraries WHERE error!='' LIMIT 3",null)) {
            while (c.moveToNext()) b.append("Catalogue: ").append(c.getString(0)).append('\n');
        }
        return b.length()==0 ? "No episodes queued yet." : b.toString();
    }
    // Two passes are intentional: a slow or unavailable Drive must never delay a
    // prepared copy. The download limit applies only to preparation, not to USB.
    static final String DUE_STAGED_SQL =
        "SELECT target,id,uri,name,bytes,stage,sha,attempts FROM episodes " +
        "WHERE stage='staged' AND next_retry<=? ORDER BY rowid";
    static final String DUE_PREPARE_SQL =
        "SELECT target,id,uri,name,bytes,stage,sha,attempts FROM episodes " +
        "WHERE stage NOT IN ('staged','verified') AND next_retry<=? " +
        "ORDER BY CASE WHEN bytes>0 THEN 0 ELSE 1 END,bytes,rowid";

    private List<Row> dueRows(boolean staged) {
        List<Row> rows=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery(staged?DUE_STAGED_SQL:DUE_PREPARE_SQL,
                new String[]{Long.toString(System.currentTimeMillis())})) {
            while(c.moveToNext()) rows.add(new Row(c));
        }
        return rows;
    }

    private void processRows(List<Row> rows,BooleanSupplier stopped,int limit,boolean staged) {
        int processed=0;
        for(Row r:rows) {
            if(stopped.getAsBoolean()||processed>=limit) return;
            try {
                // Re-read identity rather than caching a previous USB connection.
                Uri tree=targetTree(r.target);
                if(staged&&tree==null) continue;
                if(!staged) prepare(r,stopped);
                if(!stopped.getAsBoolean()) {
                    tree=targetTree(r.target);
                    if(tree!=null) transfer(r,tree,stopped);
                }
            } catch(Exception e) {
                if(!stopped.getAsBoolean()) {
                    int attempts=r.attempts+1;
                    update(r,r.stage,r.sha,message(e),attempts,System.currentTimeMillis()+EpisodeCodec.retryDelay(attempts));
                }
            }
            processed++;
        }
    }

    public boolean run(BooleanSupplier stopped,int limit,boolean transferOnly) throws Exception {
        if(!ACTIVE.compareAndSet(false,true)) return false;
        try {
            if(!work.isDirectory()&&!work.mkdirs()) throw new IOException("Cannot create app staging folder");
            processRows(dueRows(true),stopped,Integer.MAX_VALUE,true);
            if(!transferOnly&&!stopped.getAsBoolean()) {
                discover(stopped);
                if(!stopped.getAsBoolean()) processRows(dueRows(false),stopped,limit,false);
            }
            return true;
        } finally { ACTIVE.set(false); }
    }
    private File file(Row r,String extension) { return new File(work,r.id+"-"+r.target+extension); }
    private static void check(BooleanSupplier stopped) throws InterruptedIOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Paused");
    }
    private void prepare(Row r,BooleanSupplier stopped) throws Exception {
        File source=file(r,".source"), part=file(r,".download"), output=file(r,".amv"), temp=file(r,".part.amv");
        if ("staged".equals(r.stage) && r.sha!=null && output.isFile() && r.sha.equals(hash(output))) return;
        if ("staged".equals(r.stage)) update(r,"queued",null,"",0,0);
        if (!"downloaded".equals(r.stage) || !source.isFile() || (r.bytes>=0 && source.length()!=r.bytes)) {
            if (new SourceRepo(context).isDrmProtected(Uri.parse(r.uri))) throw new IOException("DRM protected video is unsupported");
            if (r.bytes>MAX_SOURCE || (r.bytes>0 && work.getUsableSpace()-RESERVE<r.bytes)) throw new IOException("Not enough phone storage for source");
            update(r,"queued",null,"",r.attempts,0);
            try (InputStream in=context.getContentResolver().openInputStream(Uri.parse(r.uri));
                 FileOutputStream out=new FileOutputStream(part,false)) {
                if (in==null) throw new IOException("Source unavailable; check the Drive or Files permission");
                byte[] buf=new byte[128*1024]; long total=0; int n;
                while ((n=in.read(buf))!=-1) {
                    check(stopped); total+=n;
                    if (total>MAX_SOURCE || work.getUsableSpace()<RESERVE) throw new IOException("Phone storage limit reached");
                    out.write(buf,0,n);
                }
                if (total==0 || (r.bytes>=0 && total!=r.bytes)) throw new IOException("Source byte count mismatch");
                out.getFD().sync();
            }
            if (!part.renameTo(source)) throw new IOException("Could not checkpoint download");
            update(r,"downloaded",null,"",r.attempts,0);
        }
        check(stopped);
        JSONObject info=probe(source,false,stopped);
        double duration=info.getJSONObject("format").getDouble("duration");
        if (!Double.isFinite(duration) || duration<=0 || duration>86400) throw new IOException("Invalid source duration");
        stream(info,"video"); stream(info,"audio");
        if (work.getUsableSpace()<RESERVE+Math.ceil(duration*1024*1024)) throw new IOException("Not enough phone space to convert safely");
        if (r.name.toLowerCase(Locale.ROOT).endsWith(".amv")) {
            validate(source,duration,stopped);
            try (InputStream in=new FileInputStream(source); FileOutputStream out=new FileOutputStream(temp,false)) {
                byte[] buf=new byte[128*1024]; int n;
                while ((n=in.read(buf))!=-1) { check(stopped); out.write(buf,0,n); }
                out.getFD().sync();
            }
        } else {
            command(EpisodeCodec.encode(executable("ffmpeg"),source.getAbsolutePath(),temp.getAbsolutePath()),stopped,7200);
        }
        validate(temp,duration,stopped);
        String sha=hash(temp);
        if (!temp.renameTo(output)) throw new IOException("Could not checkpoint converted video");
        update(r,"staged",sha,"",0,0);
        source.delete();
    }
    private String executable(String name) throws IOException {
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libruizu_"+name+".so");
        if (!binary.isFile() || !binary.canExecute()) throw new IOException("Native AMV converter is missing from this APK");
        return binary.getAbsolutePath();
    }
    private String command(List<String> command,BooleanSupplier stopped,int timeoutSeconds) throws Exception {
        File stdout=File.createTempFile("codec-", ".out",work), stderr=File.createTempFile("codec-", ".err",work);
        Process p=null;
        try {
            p=new ProcessBuilder(command).redirectOutput(stdout).redirectError(stderr).start();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!p.waitFor(1,TimeUnit.SECONDS)) {
                check(stopped);
                if (System.nanoTime()>end) throw new IOException("Video processing timed out");
                if (work.getUsableSpace()<RESERVE) throw new IOException("Phone storage limit reached");
            }
            if (p.exitValue()!=0) throw new IOException("Video codec failed (exit "+p.exitValue()+")");
            if (stdout.length()>2*1024*1024) throw new IOException("Unexpected codec output");
            return new String(java.nio.file.Files.readAllBytes(stdout.toPath()),StandardCharsets.UTF_8);
        } finally {
            if (p!=null && p.isAlive()) { p.destroyForcibly(); p.waitFor(5,TimeUnit.SECONDS); }
            stdout.delete(); stderr.delete();
        }
    }
    private JSONObject probe(File f,boolean frames,BooleanSupplier stopped) throws Exception {
        List<String> args=new ArrayList<>(Arrays.asList(executable("ffprobe"),"-v","error"));
        if (frames) args.add("-count_frames");
        args.addAll(Arrays.asList("-show_streams","-show_format","-of","json",f.getAbsolutePath()));
        return new JSONObject(command(args,stopped,7200));
    }
    private static JSONObject stream(JSONObject info,String type) throws Exception {
        JSONArray streams=info.getJSONArray("streams");
        for(int i=0;i<streams.length();i++) {
            JSONObject s=streams.getJSONObject(i);
            if(type.equals(s.optString("codec_type"))) return s;
        }
        throw new IOException("Video requires both video and audio");
    }
    private void validate(File f,double duration,BooleanSupplier stopped) throws Exception {
        JSONObject info=probe(f,true,stopped),v=stream(info,"video"),a=stream(info,"audio");
        String[] rate=v.getString("avg_frame_rate").split("/");
        double fps=Double.parseDouble(rate[0])/Double.parseDouble(rate[1]);
        if(!EpisodeCodec.validProfile(v.getString("codec_name"),v.getInt("width"),v.getInt("height"),
            a.getString("codec_name"),a.getInt("sample_rate"),a.getInt("channels"),fps,v.getLong("nb_read_frames"),duration))
            throw new IOException("Converted AMV profile or duration failed validation");
        command(Arrays.asList(executable("ffmpeg"),"-nostdin","-v","error","-xerror","-i",f.getAbsolutePath(),
            "-map","0:v:0","-map","0:a:0","-f","null","-"),stopped,7200);
    }
    private static String hash(File f) throws Exception {
        try (InputStream in=new FileInputStream(f)) { return HashUtil.sha256Hex(in); }
    }
    private void requirePlayer(Row r,Uri tree) throws IOException {
        if(!r.target.equals(PlayerMarkerStore.read(context,tree).deviceId())) throw new IOException("Waiting for the correct RUIZU player");
    }
    private void transfer(Row r,Uri tree,BooleanSupplier stopped) throws Exception {
        TransferManager manager=TransferManager.get(context);
        synchronized(manager) {
            if(manager.isRunning() || writing) return;
            writing=true;
        }
        try {
            requirePlayer(r,tree); check(stopped);
            File output=file(r,".amv");
            if(!output.isFile() || r.sha==null || !hash(output).equals(r.sha)) {
                // Do not trap missing/corrupt staging files forever in the USB-only queue.
                update(r,"queued",null,"Staged video needs preparing again",0,0);
                throw new IOException("Staged video missing or checksum mismatch; preparation required");
            }
            DestRepo repo=new DestRepo(context,tree);
            DestRepo.Node dir=repo.findChild(repo.root(),"VIDEO");
            if(dir!=null && !dir.directory) throw new IOException("VIDEO is not a folder");
            requirePlayer(r,tree);
            if(dir==null) dir=repo.ensureDir(Collections.singletonList("VIDEO"));
            requirePlayer(r,tree);
            String name=r.name.replaceFirst("\\.[^.]+$","");
            if(name.length()>100) name=name.substring(0,100);
            name=FileNameSanitizer.fileName(name+".amv");
            DestRepo.Node existing=repo.findChild(dir,name);
            if(existing!=null && !r.sha.equals(repo.hash(existing))) {
                name=FileNameSanitizer.fileName(name.replaceFirst("\\.amv$","")+" ["+r.id.substring(0,10)+"].amv");
                existing=repo.findChild(dir,name);
            }
            if(existing!=null) {
                if(!r.sha.equals(repo.hash(existing))) throw new IOException("Filename conflict; existing media kept");
            } else {
                long free=repo.availableBytes();
                if(free>=0 && free<output.length()) throw new IOException("RUIZU has insufficient free space");
                final long[] lastCheck={0};
                BooleanSupplier cancelled=()->{
                    if(stopped.getAsBoolean()) return true;
                    long now=System.nanoTime();
                    if(now-lastCheck[0]<TimeUnit.SECONDS.toNanos(1)) return false;
                    lastCheck[0]=now;
                    return !r.target.equals(PlayerMarkerStore.read(context,tree).deviceId());
                };
                try (InputStream in=new FileInputStream(output)) {
                    new SafTransfer(context,cancelled,dir.docId,false,r.target)
                        .transfer(in,tree,name,output.length(),(bytes,total)->{});
                }
            }
            requirePlayer(r,tree);
            repo.invalidate();
            DestRepo.Node published=repo.findChild(dir,name);
            if(published==null || !r.sha.equals(repo.hash(published))) throw new IOException("Published file could not be verified");
            requirePlayer(r,tree);
            update(r,"verified",r.sha,"",0,0);
            output.delete();
        } finally { synchronized(manager) { writing=false; } }
    }
    private static String message(Exception e) {
        String m=e.getMessage(); return m==null ? e.getClass().getSimpleName() : m.substring(0,Math.min(300,m.length()));
    }
}
