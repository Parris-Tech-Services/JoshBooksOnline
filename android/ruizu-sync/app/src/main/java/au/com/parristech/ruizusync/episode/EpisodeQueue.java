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
import au.com.parristech.ruizusync.core.EpisodePlan;
import au.com.parristech.ruizusync.core.DriveEpisodeProtocol;
import au.com.parristech.ruizusync.core.DriveEpisodeSource;
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
        super(c.getApplicationContext(), "video-episodes.db", null, 5);
        context = c.getApplicationContext();
        work = new File(context.getFilesDir(), "episode-staging");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE targets (id TEXT PRIMARY KEY, tree TEXT NOT NULL, name TEXT NOT NULL, per_show INTEGER NOT NULL DEFAULT 1 CHECK(per_show BETWEEN 1 AND 20))");
        db.execSQL("CREATE TABLE libraries (target TEXT NOT NULL, tree TEXT NOT NULL, last_scan INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', PRIMARY KEY(target,tree))");
        db.execSQL("CREATE TABLE episodes (target TEXT NOT NULL, id TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, bytes INTEGER NOT NULL, stage TEXT NOT NULL DEFAULT 'queued', sha TEXT, attempts INTEGER NOT NULL DEFAULT 0, next_retry INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', approved INTEGER NOT NULL DEFAULT 0 CHECK(approved IN (0,1)), label TEXT NOT NULL DEFAULT '', show_name TEXT NOT NULL DEFAULT '', player_name TEXT NOT NULL DEFAULT '', PRIMARY KEY(target,id))");
        db.execSQL("CREATE INDEX episode_due ON episodes(stage,next_retry)");
        createDriveTables(db);
        createLegacyTables(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion==1 && newVersion>=2) {
            // No speculative approval of older individual documents. Explicit saved trees
            // will be rediscovered; the user can reselect individual files. Keep all history.
            db.execSQL("ALTER TABLE episodes ADD COLUMN approved INTEGER NOT NULL DEFAULT 0 CHECK(approved IN (0,1))");
            db.execSQL("UPDATE libraries SET last_scan=0");
            oldVersion=2;
        }
        if(oldVersion==2 && newVersion>=3) {
            db.execSQL("ALTER TABLE targets ADD COLUMN per_show INTEGER NOT NULL DEFAULT 1 CHECK(per_show BETWEEN 1 AND 20)");
            db.execSQL("ALTER TABLE episodes ADD COLUMN label TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE episodes ADD COLUMN show_name TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE episodes ADD COLUMN player_name TEXT NOT NULL DEFAULT ''");
            // Do not guess show identities for previously approved pending rows. Re-discover
            // saved trees and require explicit reselection for older individual sources.
            db.execSQL("UPDATE episodes SET approved=0 WHERE stage!='verified'");
            db.execSQL("UPDATE libraries SET last_scan=0");
            oldVersion=3;
        }
        if(oldVersion==3 && newVersion>=4) { createDriveTables(db); oldVersion=4; }
        if(oldVersion==4 && newVersion==5) { createLegacyTables(db); oldVersion=5; }
        if(oldVersion!=newVersion) throw new IllegalStateException("Missing episode database migration");
    }
    private static void createLegacyTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE legacy_had (target TEXT NOT NULL, label TEXT NOT NULL COLLATE NOCASE, state TEXT NOT NULL, PRIMARY KEY(target,label))");
    }
    /** Import Claude's export format v1 for one already-bound player. This is history,
     * not proof of a native write. Never imports source permissions or writes USB.
     */
    public int importLegacyHistory(String target,InputStream input) throws Exception {
        if(target==null||input==null) throw new IOException("Choose a bound player and export");
        if(!ACTIVE.compareAndSet(false,true)) throw new IOException("Pause the active worker before importing history");
        try {
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            byte[] chunk=new byte[16384]; int n;
            while((n=input.read(chunk))!=-1) {
                if(buffer.size()+n>8*1024*1024) throw new IOException("Legacy export exceeds 8 MB");
                buffer.write(chunk,0,n);
            }
            JSONObject export=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            if(!"ruizu-episodes-export".equals(export.getString("format"))
                    ||export.getInt("version")!=1||export.getInt("schema_version")!=1)
                throw new IOException("Unsupported legacy episode export version");
            JSONArray players=export.getJSONArray("players");
            JSONObject player=null;
            for(int i=0;i<players.length();i++) {
                JSONObject candidate=players.getJSONObject(i);
                if(target.equals(candidate.getString("marker_id"))) {
                    if(player!=null) throw new IOException("Duplicate player in export");
                    player=candidate;
                }
            }
            if(player==null) throw new IOException("Export does not contain the selected player's marker ID");
            int cap=player.getInt("per_show");
            if(cap<1||cap>20) throw new IOException("Invalid legacy episode cap");
            JSONArray copies=player.getJSONArray("copies");
            Map<String,String> had=new LinkedHashMap<>();
            for(int i=0;i<copies.length();i++) {
                JSONObject copy=copies.getJSONObject(i);
                String label=copy.getString("label"),state=copy.getString("state");
                if(label.isEmpty()||label.length()>150||label.matches(".*[\\x00-\\x1f\\x7f/\\\\:*?\"<>|].*"))
                    throw new IOException("Invalid episode label in legacy export");
                if(!Arrays.asList("verified","present","removed","copying").contains(state))
                    throw new IOException("Unknown legacy copy state");
                String key=label.toLowerCase(Locale.ROOT);
                if(had.putIfAbsent(key,state)!=null) throw new IOException("Duplicate episode label in legacy export");
            }
            SQLiteDatabase db=getWritableDatabase();
            db.beginTransaction();
            int imported=0;
            try {
                try(Cursor c=db.rawQuery("SELECT id FROM targets WHERE id=?",new String[]{target})) {
                    if(!c.moveToFirst()) throw new IOException("Bind the selected player first");
                }
                for(Map.Entry<String,String> row:had.entrySet()) {
                    if("copying".equals(row.getValue())) continue;
                    ContentValues value=new ContentValues(); value.put("target",target); value.put("label",row.getKey());
                    // A prior CLI verification is historical evidence, not native verification.
                    value.put("state","removed".equals(row.getValue())?"removed":"previously_had");
                    db.insertWithOnConflict("legacy_had",null,value,SQLiteDatabase.CONFLICT_IGNORE);
                    imported++;
                }
                setPerShow(target,cap);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return imported;
        } finally { ACTIVE.set(false); }
    }
    private Set<String> importedHad(String target) {
        Set<String> result=new HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT label FROM legacy_had WHERE target=?",new String[]{target})) {
            while(c.moveToNext()) result.add(c.getString(0).toLowerCase(Locale.ROOT));
        }
        return result;
    }
    private static final long DRIVE_TTL=7*DAY, DOWNLOAD_BUDGET=1500L*1024*1024;
    private long remainingDownload=DOWNLOAD_BUDGET;
    private static void createDriveTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE drive_roots (target TEXT NOT NULL, root TEXT NOT NULL, show_name TEXT NOT NULL, PRIMARY KEY(target,root))");
        db.execSQL("CREATE TABLE drive_folders (target TEXT NOT NULL, root TEXT NOT NULL, folder TEXT NOT NULL, path TEXT NOT NULL, last_scan INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', PRIMARY KEY(target,root,folder))");
        db.execSQL("CREATE INDEX drive_scan_due ON drive_folders(last_scan)");
    }
    public void addSharedDriveFolder(String target,String link,String showName) throws IOException {
        String root=DriveEpisodeProtocol.folderId(link);
        String show=EpisodePlan.cleanText(showName);
        if(show.isEmpty()) throw new IOException("Enter the approved show name");
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT id FROM targets WHERE id=?",new String[]{target})) {
                if(!c.moveToFirst()) throw new IOException("Bind the selected player first");
            }
            ContentValues approved=new ContentValues();
            approved.put("target",target); approved.put("root",root); approved.put("show_name",show);
            db.insertWithOnConflict("drive_roots",null,approved,SQLiteDatabase.CONFLICT_IGNORE);
            // Refuse silently re-labelling an already approved root. Revoke it explicitly first.
            try(Cursor c=db.rawQuery("SELECT show_name FROM drive_roots WHERE target=? AND root=?",new String[]{target,root})) {
                if(!c.moveToFirst()||!show.equals(c.getString(0))) throw new IOException("This folder is already approved under a different show name");
            }
            addDriveFolder(db,target,root,root,"");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static void addDriveFolder(SQLiteDatabase db,String target,String root,String id,String path) {
        ContentValues v=new ContentValues(); v.put("target",target); v.put("root",root);
        v.put("folder",id); v.put("path",path);
        db.insertWithOnConflict("drive_folders",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    private boolean unmetered() {
        android.net.ConnectivityManager manager=context.getSystemService(android.net.ConnectivityManager.class);
        return manager!=null&&manager.getActiveNetwork()!=null&&!manager.isActiveNetworkMetered();
    }
    private static boolean directDrive(Row r) {
        return "ruizu-drive".equals(Uri.parse(r.uri).getScheme());
    }
    /** Cached frontier: a large show is incrementally walked, not restarted after 40 listings. */
    private void discoverDrive(BooleanSupplier stopped) throws Exception {
        if(!unmetered()) return;
        DriveEpisodeSource client=new DriveEpisodeSource();
        for(int scanned=0;scanned<40;scanned++) {
            if(stopped.getAsBoolean()||!unmetered()) return;
            String[] next=null;
            try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT f.target,f.root,f.folder,f.path,r.show_name FROM drive_folders f JOIN drive_roots r ON r.target=f.target AND r.root=f.root WHERE f.last_scan<=? ORDER BY f.last_scan,f.rowid LIMIT 1",
                new String[]{Long.toString(System.currentTimeMillis()-DRIVE_TTL)})) {
                if(c.moveToFirst()) next=new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)};
            }
            if(next==null) return;
            String error=""; long finished=System.currentTimeMillis();
            try {
                List<DriveEpisodeProtocol.Entry> entries=client.list(next[2],stopped);
                for(DriveEpisodeProtocol.Entry entry:entries) {
                    if(stopped.getAsBoolean()||!unmetered()) return;
                    String relative=next[3]+"/"+entry.name;
                    if(relative.length()>2000) throw new IOException("Drive folder nesting exceeds limit");
                    if(entry.folder) addDriveFolder(getWritableDatabase(),next[0],next[1],entry.id,relative);
                    else if(EpisodeCodec.supported(entry.name)) {
                        Uri uri=Uri.parse("ruizu-drive://file/"+DriveEpisodeProtocol.fileId(entry.id));
                        insert(next[0],uri,entry.name,-1,0,next[4],relative);
                    }
                }
            } catch(Exception e) {
                error=message(e); finished-=DRIVE_TTL-15*60_000L;
            }
            ContentValues v=new ContentValues(); v.put("last_scan",finished); v.put("error",error);
            getWritableDatabase().update("drive_folders",v,"target=? AND root=? AND folder=?",new String[]{next[0],next[1],next[2]});
        }
    }
    public void bind(String id, String name, Uri tree) throws IOException {
        if (id == null || !id.equals(PlayerMarkerStore.read(context, tree).deviceId())
                || !PlayerFolderStore.hasPersistedPermission(context, tree)) {
            throw new IOException("Choose the identified RUIZU player root on the main screen first");
        }
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("tree", tree.toString()); v.put("name", name == null ? "Player" : name);
        SQLiteDatabase db=getWritableDatabase();
        db.insertWithOnConflict("targets",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        db.update("targets",v,"id=?",new String[]{id});
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
    /** Stops all future video work for one player without deleting any media or ledger rows.
     * Exclusive with run(), so success means no previously selected row can publish afterwards.
     * Do not release shared SAF grants: music and other players may legitimately use them.
     */
    public void revokeSources(String target) throws IOException {
        if (target==null) throw new IOException("Choose a saved player");
        if (!ACTIVE.compareAndSet(false,true))
            throw new IOException("Pause the current worker and disable automatic processing before revoking sources");
        try {
            SQLiteDatabase db=getWritableDatabase();
            db.beginTransaction();
            try {
                try (Cursor c=db.rawQuery("SELECT id FROM targets WHERE id=?",new String[]{target})) {
                    if(!c.moveToFirst()) throw new IOException("Unknown player");
                }
                db.delete("libraries","target=?",new String[]{target});
                db.delete("drive_folders","target=?",new String[]{target});
                db.delete("drive_roots","target=?",new String[]{target});
                ContentValues values=new ContentValues();
                values.put("approved",0);
                db.update("episodes",values,"target=?",new String[]{target});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } finally { ACTIVE.set(false); }
    }
    public void setPerShow(String target,int count) throws IOException {
        if(count<1||count>20) throw new IOException("Episodes per show must be 1 to 20");
        ContentValues v=new ContentValues(); v.put("per_show",count);
        if(getWritableDatabase().update("targets",v,"id=?",new String[]{target})!=1)
            throw new IOException("Choose a saved player");
    }
    public void refreshCatalogue() {
        getWritableDatabase().execSQL("UPDATE libraries SET last_scan=0");
        getWritableDatabase().execSQL("UPDATE drive_folders SET last_scan=0");
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
        insert(target, uri, name, bytes, modified, null, name);
    }
    /** Preserve the Python pipeline's labels rather than copying raw release filenames. */
    static String[] identity(String name,String approvedShow,String relativePath) {
        if(name.endsWith(EpisodePlan.PLAYER_SUFFIX)) {
            String label=name.substring(0,name.length()-EpisodePlan.PLAYER_SUFFIX.length());
            return new String[]{EpisodePlan.identifyLabel(label).getKey(),label};
        }
        String base=name.replaceFirst("\\.[^.]+$","");
        String show=approvedShow;
        if(show==null||show.trim().isEmpty()||"Selected folder".equals(show)) {
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:^|[ ._-])(?:s\\d{1,2}[ ._-]?e\\d{1,3}|\\d{1,2}x\\d{1,3})(?:[^0-9]|$)").matcher(base);
            if(m.find()&&m.start()>0) show=base.substring(0,m.start()).replace('.',' ').replace('_',' ').trim();
            else {
                String label=EpisodePlan.cleanText(base);
                return new String[]{label,label};
            }
        }
        show=EpisodePlan.cleanText(show);
        String label=EpisodePlan.identify(show,relativePath==null?name:relativePath).label;
        return new String[]{show,label};
    }
    private void insert(String target, Uri uri, String name, long size, long modified,String show,String relativePath) throws Exception {
        if (target == null || size == 0 || !EpisodeCodec.supported(name)) return;
        String id = hex(MessageDigest.getInstance("SHA-256").digest(
            (uri + "\n" + size + "\n" + modified).getBytes(StandardCharsets.UTF_8)));
        ContentValues v = new ContentValues();
        v.put("target", target); v.put("id", id); v.put("uri", uri.toString());
        v.put("name", name); v.put("bytes", size);
        String[] identity=identity(name,show,relativePath);
        if(identity[0].isEmpty()||identity[1].isEmpty()) return;
        v.put("show_name",identity[0]); v.put("label",identity[1]);
        v.put("approved",1);
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertWithOnConflict("episodes",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            // Reselecting exactly the same source version restores approval without resetting
            // its verified status, staged hash or retry history.
            ContentValues approved=new ContentValues();
            approved.put("approved",1); approved.put("show_name",identity[0]); approved.put("label",identity[1]);
            db.update("episodes",approved,"target=? AND id=?",new String[]{target,id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
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
                Map<String,String> paths=new HashMap<>();
                String root=DocumentsContract.getTreeDocumentId(tree);
                String show=new SourceRepo(context).treeDisplayName(tree);
                pending.add(root); paths.put(root,"");
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
                            String name=c.getString(1);
                            if(name==null) continue;
                            String relative=paths.get(dir)+"/"+name;
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                                paths.put(doc,relative); pending.add(doc);
                            } else if (EpisodeCodec.supported(name))
                                insert(library[0], DocumentsContract.buildDocumentUriUsingTree(tree, doc),
                                    name, c.isNull(3) ? -1 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4),show,relative);
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
    static class Row {
        String target,id,uri,name,stage,sha,label,show,playerName; long bytes; int attempts;
        Row(Cursor c) {
            target=c.getString(0); id=c.getString(1); uri=c.getString(2); name=c.getString(3);
            bytes=c.getLong(4); stage=c.getString(5); sha=c.getString(6); attempts=c.getInt(7);
            label=c.getString(8); show=c.getString(9); playerName=c.getString(10);
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
        try (Cursor c=getReadableDatabase().rawQuery(
            "SELECT targets.name,count(*) FROM episodes JOIN targets ON targets.id=episodes.target WHERE episodes.approved=0 AND episodes.stage!='verified' GROUP BY targets.id,targets.name",null)) {
            while(c.moveToNext()) b.append(c.getString(0)).append(" · paused, source approval required: ").append(c.getInt(1)).append('\n');
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT targets.name,count(*) FROM legacy_had JOIN targets ON legacy_had.target=targets.id GROUP BY targets.id,targets.name",null)) {
            while(c.moveToNext()) b.append(c.getString(0)).append(" · imported history: ").append(c.getInt(1)).append('\n');
        }
        try(Cursor c=getReadableDatabase().rawQuery("SELECT error FROM drive_folders WHERE error!='' LIMIT 3",null)) {
            while(c.moveToNext()) b.append("Shared Drive: ").append(c.getString(0)).append('\n');
        }
        return b.length()==0 ? "No episodes queued yet." : b.toString();
    }
    // Two passes are intentional: a slow or unavailable Drive must never delay a
    // prepared copy. The download limit applies only to preparation, not to USB.
    private static final String ROW_COLUMNS="target,id,uri,name,bytes,stage,sha,attempts,label,show_name,player_name";
    static final String DUE_STAGED_SQL =
        "SELECT "+ROW_COLUMNS+" FROM episodes " +
        "WHERE approved=1 AND stage='staged' AND next_retry<=? ORDER BY rowid";
    static final String DUE_PREPARE_SQL =
        "SELECT "+ROW_COLUMNS+" FROM episodes " +
        "WHERE approved=1 AND stage NOT IN ('staged','verified','present','removed','conflict') AND next_retry<=? " +
        "ORDER BY CASE WHEN bytes>0 THEN 0 ELSE 1 END,bytes,rowid";

    private static boolean terminal(String stage) {
        return "verified".equals(stage)||"present".equals(stage)||"removed".equals(stage)||"conflict".equals(stage);
    }
    private List<Row> rowsFor(String target) {
        List<Row> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+ROW_COLUMNS+" FROM episodes WHERE target=? ORDER BY rowid",new String[]{target})) {
            while(c.moveToNext()) rows.add(new Row(c));
        }
        return rows;
    }
    private int perShow(String target) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT per_show FROM targets WHERE id=?",new String[]{target})) {
            return c.moveToFirst()?c.getInt(0):1;
        }
    }
    private String labelKey(Row r) { return r.label.toLowerCase(Locale.ROOT); }
    private String useKey(Row r) { return r.target+"\n"+r.show.toLowerCase(Locale.ROOT); }

    private List<Row> dueRows(boolean staged) {
        List<Row> rows=new ArrayList<>();
        try (Cursor c=getReadableDatabase().rawQuery(staged?DUE_STAGED_SQL:DUE_PREPARE_SQL,
                new String[]{Long.toString(System.currentTimeMillis())})) {
            while(c.moveToNext()) rows.add(new Row(c));
        }
        return rows;
    }

    /** Use Claude's shared planning core; an approved label is eligible only once per player. */
    List<Row> plan(boolean staged,Map<String,Integer> used) {
        List<Row> result=new ArrayList<>(), due=dueRows(staged);
        Map<String,List<Row>> grouped=new LinkedHashMap<>();
        for(Row row:due) {
            if(!row.label.isEmpty()&&!row.show.isEmpty()) grouped.computeIfAbsent(row.target,k->new ArrayList<>()).add(row);
        }
        for(Map.Entry<String,List<Row>> target:grouped.entrySet()) {
            Map<String,Row> byLabel=new LinkedHashMap<>();
            Set<String> had=importedHad(target.getKey());
            List<EpisodePlan.Candidate> candidates=new ArrayList<>();
            for(Row row:rowsFor(target.getKey())) {
                if(terminal(row.stage)&&!row.label.isEmpty()) {
                    had.add(labelKey(row));
                    candidates.add(new EpisodePlan.Candidate(labelKey(row),row.show,
                        EpisodePlan.identifyLabel(row.label).getValue(),row.bytes,EpisodePlan.State.PENDING));
                }
            }
            for(Row row:target.getValue()) {
                String key=labelKey(row);
                if(byLabel.putIfAbsent(key,row)==null)
                    candidates.add(new EpisodePlan.Candidate(key,row.show,EpisodePlan.identifyLabel(row.label).getValue(),
                        row.bytes,staged?EpisodePlan.State.STAGED:EpisodePlan.State.PENDING));
            }
            int cap=perShow(target.getKey());
            Map<String,Integer> planned=new HashMap<>(used);
            for(EpisodePlan.Candidate c:EpisodePlan.nextForPlayer(EpisodePlan.byShow(candidates),had,EpisodePlan.Allowlist.all(),cap)) {
                Row row=byLabel.get(c.key);
                if(row==null) continue;
                String key=useKey(row);
                int count=planned.getOrDefault(key,0);
                if(count<cap) { result.add(row); planned.put(key,count+1); }
            }
        }
        return result;
    }

    private void processRows(List<Row> rows,BooleanSupplier stopped,int limit,boolean staged,Map<String,Integer> used) {
        int processed=0;
        for(Row r:rows) {
            if(stopped.getAsBoolean()||processed>=limit) return;
            try {
                // Re-read identity rather than caching a previous USB connection.
                Uri tree=targetTree(r.target);
                if(!staged&&directDrive(r)&&(!unmetered()||remainingDownload<=0)) continue;
                used.put(useKey(r),used.getOrDefault(useKey(r),0)+1);
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
            remainingDownload=DOWNLOAD_BUDGET;
            Map<String,Integer> used=new HashMap<>();
            reconcilePlayers(stopped);
            processRows(plan(true,used),stopped,Integer.MAX_VALUE,true,used);
            if(!transferOnly&&!stopped.getAsBoolean()) {
                discover(stopped);
                discoverDrive(stopped);
                reconcilePlayers(stopped);
                if(!stopped.getAsBoolean()) processRows(plan(false,used),stopped,limit,false,used);
            }
            return true;
        } finally { ACTIVE.set(false); }
    }
    private String canonicalName(Row row) { return EpisodePlan.playerFileName(row.label); }
    private String legacyName(Row row) {
        String name=row.name.replaceFirst("\\.[^.]+$","");
        if(name.length()>100) name=name.substring(0,100);
        return FileNameSanitizer.fileName(name+".amv");
    }
    private void recordPlayerName(Row row,String name) {
        ContentValues value=new ContentValues(); value.put("player_name",name);
        getWritableDatabase().update("episodes",value,"target=? AND id=?",new String[]{row.target,row.id});
        row.playerName=name;
    }
    /** Reconcile only against a positively identified and readable player. A name match
     * counts as present, never hash-verified. Never overwrite or remove existing media.
     */
    private void reconcilePlayers(BooleanSupplier stopped) {
        for(String target:targets().keySet()) {
            if(stopped.getAsBoolean()) return;
            Uri tree=targetTree(target);
            if(tree==null) continue;
            try {
                DestRepo repo=new DestRepo(context,tree);
                DestRepo.Node dir=repo.findChild(repo.root(),"VIDEO");
                if(dir!=null&&!dir.directory) continue;
                for(Row row:rowsFor(target)) {
                    if(stopped.getAsBoolean()) return;
                    requirePlayer(row,tree);
                    if(row.label.isEmpty()||"removed".equals(row.stage)) continue;
                    DestRepo.Node found=dir==null?null:repo.findChild(dir,row.playerName.isEmpty()?canonicalName(row):row.playerName);
                    if(found==null&&dir!=null) found=repo.findChild(dir,legacyName(row));
                    requirePlayer(row,tree);
                    if(found!=null&&!found.directory) {
                        if(!terminal(row.stage)) {
                            recordPlayerName(row,found.name);
                            if(found.size==0) update(row,"conflict",null,"Existing zero-byte video retained; inspect it manually",0,0);
                            else if(EpisodePlan.classifyPlayerFile(Math.max(1,found.size),-1,()->false)==EpisodePlan.Found.HAD)
                                update(row,"present",null,"",0,0);
                        }
                    } else if("verified".equals(row.stage)||"present".equals(row.stage)) {
                        update(row,"removed",row.sha,"Previously present; not queued again",0,0);
                    }
                }
            } catch(Exception e) {
                // A disconnected or unreadable provider is not evidence of removal.
                android.util.Log.w("RUIZU","Player reconciliation postponed",e);
            }
        }
    }
    private File file(Row r,String extension) { return new File(work,r.id+extension); }
    private void updateShared(Row r,String stage,String sha) {
        ContentValues v=new ContentValues(); v.put("stage",stage); v.put("sha",sha);
        v.put("error",""); v.put("attempts",0); v.put("next_retry",0);
        getWritableDatabase().update("episodes",v,
            "id=? AND approved=1 AND stage NOT IN ('verified','present','removed','conflict')",new String[]{r.id});
        r.stage=stage; r.sha=sha; r.attempts=0;
    }
    /** Reuse an existing validated shared AMV. Migrate older app-private per-player
     * staging only after comparing its recorded checksum; never touch player files.
     */
    private boolean reuseShared(Row r) throws Exception {
        File output=file(r,".amv");
        if(!output.isFile()&&r.sha!=null) {
            File legacy=new File(work,r.id+"-"+r.target+".amv");
            if(legacy.isFile()&&r.sha.equals(hash(legacy))) {
                if(!legacy.renameTo(output)) throw new IOException("Could not adopt previous staged video");
            }
        }
        if(!output.isFile()) return false;
        String actual=hash(output);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT sha FROM episodes WHERE id=? AND sha IS NOT NULL",new String[]{r.id})) {
            while(c.moveToNext()) {
                if(actual.equals(c.getString(0))) {
                    updateShared(r,"staged",actual);
                    return true;
                }
            }
        }
        return false;
    }
    private boolean sharedDownloadExists(Row r,File source) {
        if(!source.isFile()||(r.bytes>=0&&source.length()!=r.bytes)) return false;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM episodes WHERE id=? AND stage='downloaded' LIMIT 1",new String[]{r.id})) {
            return c.moveToFirst();
        }
    }
    private void releaseSharedIfSatisfied(Row r,File output) {
        Set<String> wanted=new HashSet<>(), verified=new HashSet<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT target,stage FROM episodes WHERE id=? AND approved=1",new String[]{r.id})) {
            while(c.moveToNext()) {
                String target=c.getString(0),stage=c.getString(1);
                if("present".equals(stage)||"removed".equals(stage)||"conflict".equals(stage)) continue;
                wanted.add(target);
                if("verified".equals(stage)) verified.add(target);
            }
        }
        if(EpisodePlan.canReleaseStaged(wanted,verified)) output.delete();
    }
    private static void check(BooleanSupplier stopped) throws InterruptedIOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Paused");
    }
    private void prepare(Row r,BooleanSupplier stopped) throws Exception {
        File source=file(r,".source"), part=file(r,".download"), output=file(r,".amv"), temp=file(r,".part.amv");
        if(reuseShared(r)) return;
        if ("staged".equals(r.stage)) update(r,"queued",null,"",0,0);
        if (!sharedDownloadExists(r,source)) {
            if (!directDrive(r)&&new SourceRepo(context).isDrmProtected(Uri.parse(r.uri))) throw new IOException("DRM protected video is unsupported");
            if (r.bytes>MAX_SOURCE || (r.bytes>0 && work.getUsableSpace()-RESERVE<r.bytes)) throw new IOException("Not enough phone storage for source");
            update(r,"queued",null,"",r.attempts,0);
            if(directDrive(r)) {
                if(!unmetered()) throw new IOException("Waiting for an unmetered network");
                try {
                    new DriveEpisodeSource().download(Uri.parse(r.uri).getLastPathSegment(),part,remainingDownload,RESERVE,
                        ()->stopped.getAsBoolean()||!unmetered());
                } finally {
                    // Conservative budget accounting includes any existing partial bytes.
                    remainingDownload=Math.max(0,remainingDownload-part.length());
                }
            } else try (InputStream in=context.getContentResolver().openInputStream(Uri.parse(r.uri));
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
            updateShared(r,"downloaded",null);
            if(directDrive(r)) DriveEpisodeSource.finish(part);
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
        try(RandomAccessFile durable=new RandomAccessFile(temp,"rw")) { durable.getFD().sync(); }
        String sha=hash(temp);
        if (!temp.renameTo(output)) throw new IOException("Could not checkpoint converted video");
        updateShared(r,"staged",sha);
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
            if(!reuseShared(r)) {
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
            for(String filename:repo.existingNames(dir)) {
                if(filename.endsWith(".ruizu-partial")) throw new IOException("Another episode writer has an incomplete file; stop it and recover first");
            }
            if(r.label.isEmpty()) throw new IOException("Source approval and episode label required");
            String name=canonicalName(r);
            DestRepo.Node existing=repo.findChild(dir,name);
            if(existing!=null) {
                if(existing.directory || !r.sha.equals(repo.hash(existing))) {
                    recordPlayerName(r,existing.name);
                    update(r,"conflict",null,"Existing file retained; content differs from the staged episode",0,0);
                    return;
                }
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
            recordPlayerName(r,name);
            update(r,"verified",r.sha,"",0,0);
            releaseSharedIfSatisfied(r,output);
        } finally { synchronized(manager) { writing=false; } }
    }
    private static String message(Exception e) {
        String m=e.getMessage(); return m==null ? e.getClass().getSimpleName() : m.substring(0,Math.min(300,m.length()));
    }
}
