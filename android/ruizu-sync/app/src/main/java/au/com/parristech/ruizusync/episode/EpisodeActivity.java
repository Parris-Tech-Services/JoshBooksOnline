package au.com.parristech.ruizusync.episode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.ViewGroup;
import android.widget.*;
import au.com.parristech.ruizusync.AppState;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Video queue screen inside the canonical RUIZU Sync application, not a separate package. */
public final class EpisodeActivity extends Activity {
    private static final int TREE=8101, FILES=8102;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView status, notice;
    private Spinner players;
    private final List<String> ids=new ArrayList<>();
    private String pendingTarget;
    private boolean visible;
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            if(!visible) return;
            task(q->{
                String s=q.status(); boolean active=EpisodeQueue.isActive();
                main.post(()->{ if(visible) status.setText((active?"Processing…\n":"Idle / waiting for player or scheduled work\n")+s); });
            });
            main.postDelayed(this,2500);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(state!=null) pendingTarget=state.getString("pending");
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density); root.setPadding(pad,pad*2,pad,pad*2);
        scroll.addView(root);
        TextView title=new TextView(this); title.setText("Video episode queue"); title.setTextSize(24); root.addView(title);
        TextView help=new TextView(this);
        help.setText("Bind each player once, then approve only the show folders or videos intended for it. Nothing is discovered outside those sources. RUIZU Sync remembers the queue, prepares AMV and verifies each copy. Stop any other video-sync tools before running this queue.");
        root.addView(help);
        players=new Spinner(this); root.addView(players,new LinearLayout.LayoutParams(-1,-2));
        button(root,"Bind player selected on main screen",()->{
            AppState app=AppState.get();
            String id=app.destinationDeviceId(),name=app.destinationDeviceName();
            Uri tree=app.destinationTree();
            task(q->{ q.bind(id,name,tree); reload(q,id); });
        });
        button(root,"Approve a show or video folder for this player",()->pick(true));
        button(root,"Approve a shared Google Drive show",this::chooseSharedDrive);
        button(root,"Episodes per show (default 1)",this::chooseEpisodeCap);
        button(root,"Add individual videos (including Drive)",()->pick(false));
        button(root,"Revoke all sources for selected player",()->{
            int index=players.getSelectedItemPosition();
            if(index<0||index>=ids.size()) { notice.setText("Choose a saved player first."); return; }
            final String target=ids.get(index);
            new AlertDialog.Builder(this).setTitle("Revoke video sources?")
                .setMessage("Stops future downloads and transfers for this player. Existing player media and transfer history stay intact. Pause an active run before revoking. You can approve sources again later.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Revoke sources",(d,w)->task(q->{
                    q.revokeSources(target);
                    main.post(()->notice.setText("Sources revoked for the selected player. Existing files were not changed."));
                })).show();
        });
        TextView provider=new TextView(this);
        provider.setText("Cloud providers that do not expose folder access can still supply individual videos via the system picker. Only choose media you are entitled to copy.");
        root.addView(provider);
        CheckBox automatic=new CheckBox(this); automatic.setText("Automatically process on Wi-Fi and sync when player connects");
        automatic.setChecked(EpisodeRuntime.enabled(this)); root.addView(automatic);
        automatic.setOnCheckedChangeListener((view,value)->EpisodeRuntime.setEnabled(this,value));
        button(root,"Process queue now",()->{
            try { EpisodeRuntime.runNow(this); } catch(RuntimeException e) { showError(e); }
        });
        button(root,"Pause current foreground run",()->EpisodeRuntime.pauseNow(this));
        button(root,"Refresh saved library catalogues",()->task(q->{q.refreshCatalogue(); EpisodeRuntime.requestDiscovery(this); main.post(()->notice.setText("Catalogue refresh requested. Use Process queue now to run immediately."));}));
        button(root,"Retry failed episodes",()->task(q->{q.retry(); EpisodeRuntime.requestDiscovery(this); main.post(()->notice.setText("Retries reset."));}));
        notice=new TextView(this); root.addView(notice);
        status=new TextView(this); status.setText("Loading persistent queue…"); root.addView(status);
        button(root,"Back to music and podcasts",this::finish);
        setContentView(scroll);
        task(q->reload(q,getPreferences(0).getString("selected",null)));
        EpisodeRuntime.ensureSchedule(this);
    }
    @Override protected void onStart() { super.onStart(); visible=true; main.post(refresh); }
    @Override protected void onStop() { visible=false; main.removeCallbacks(refresh); super.onStop(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pending",pendingTarget); super.onSaveInstanceState(state);
    }
    private void button(LinearLayout root,String text,Runnable action) {
        Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(v->action.run());
        root.addView(b,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    private interface Task { void run(EpisodeQueue q) throws Exception; }
    private void task(Task action) {
        if(io.isShutdown()) return;
        io.execute(()->{try(EpisodeQueue q=new EpisodeQueue(this)){action.run(q);}
            catch(Exception e){main.post(()->showError(e));}});
    }
    private void showError(Exception e) {
        if(!isFinishing()&&!isDestroyed()&&notice!=null)
            notice.setText(e.getMessage()==null?"Episode operation failed":e.getMessage());
    }
    private void reload(EpisodeQueue q,String selected) {
        Map<String,String> saved=q.targets();
        main.post(()->{
            if(isFinishing()||isDestroyed()) return;
            ids.clear(); ids.addAll(saved.keySet()); List<String> labels=new ArrayList<>();
            for(String id:ids) labels.add(saved.get(id)+" ("+id.substring(0,Math.min(8,id.length()))+")");
            if(labels.isEmpty()) labels.add("No saved player — bind the player first");
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); players.setAdapter(adapter);
            int index=ids.indexOf(selected); if(index>=0) players.setSelection(index);
        });
    }
    private String selectedPlayer() {
        int index=players.getSelectedItemPosition();
        return index>=0&&index<ids.size()?ids.get(index):null;
    }
    private void chooseSharedDrive() {
        final String target=selectedPlayer();
        if(target==null) {notice.setText("Bind an identified player first.");return;}
        LinearLayout fields=new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        EditText show=new EditText(this); show.setHint("Approved show name"); fields.addView(show);
        EditText link=new EditText(this); link.setHint("Shared Drive show folder link"); link.setSingleLine(true); fields.addView(link);
        new AlertDialog.Builder(this).setTitle("Approve one show for selected player")
            .setMessage("Use a folder already shared with link access. Select one show, not your entire TV library. The app will discover its subfolders on unmetered internet. No Google password is required.")
            .setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Approve",(d,w)->{
                final String title=show.getText().toString(),address=link.getText().toString();
                task(q->{q.addSharedDriveFolder(target,address,title); EpisodeRuntime.requestDiscovery(this);
                    main.post(()->notice.setText("Approved shared show. Choose Process queue now to discover immediately."));});
            }).show();
    }
    private void chooseEpisodeCap() {
        final String target=selectedPlayer();
        if(target==null) {notice.setText("Bind an identified player first.");return;}
        EditText count=new EditText(this); count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); count.setText("1");
        new AlertDialog.Builder(this).setTitle("New episodes per show per run").setView(count)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
                final String raw=count.getText().toString();
                task(q->{q.setPerShow(target,Integer.parseInt(raw));main.post(()->notice.setText("Episode cap saved."));});
            }).show();
    }
    private void pick(boolean folder) {
        int index=players.getSelectedItemPosition();
        if(index<0||index>=ids.size()) { notice.setText("Bind an identified RUIZU first."); return; }
        pendingTarget=ids.get(index); getPreferences(0).edit().putString("selected",pendingTarget).apply();
        Intent i=new Intent(folder?Intent.ACTION_OPEN_DOCUMENT_TREE:Intent.ACTION_OPEN_DOCUMENT);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if(!folder) {
            i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","application/octet-stream"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        }
        try {startActivityForResult(i,folder?TREE:FILES);} catch(ActivityNotFoundException e){showError(e);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if((request!=TREE&&request!=FILES)||result!=RESULT_OK||data==null||pendingTarget==null) return;
        final String target=pendingTarget;
        final List<Uri> uris=new ArrayList<>();
        if(data.getClipData()!=null) {
            for(int i=0;i<data.getClipData().getItemCount();i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if(data.getData()!=null) uris.add(data.getData());
        // Take durable grants before the activity result returns; never assume a transient grant survives reboot.
        List<Uri> granted=new ArrayList<>();
        for(Uri uri:uris) {
            try {getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);granted.add(uri);}
            catch(SecurityException e){notice.setText("Provider did not grant persistent access; select a different file or folder.");}
        }
        if(granted.isEmpty()) return;
        if(request==TREE) {
            new AlertDialog.Builder(this).setTitle("Approve this folder for player "+target.substring(0,Math.min(8,target.length()))+"?")
                .setMessage("Only approve a folder if every video in it and its subfolders is intended for this player, including future additions. For a restricted player, select each approved show separately, not the entire library. Existing media will never be replaced.")
                .setNegativeButton("Cancel",null).setPositiveButton("Save library",(d,w)->save(target,granted,true)).show();
        } else save(target,granted,false);
    }
    private void save(String target,List<Uri> uris,boolean folder) {
        task(q->{
            int count=0; String lastError="";
            for(Uri uri:uris) {
                try {if(folder) q.addLibrary(target,uri); else q.addFile(target,uri); count++;}
                catch(Exception e){lastError=e.getMessage();}
            }
            final String report=count+" source"+(count==1?"":"s")+" saved. "+lastError;
            EpisodeRuntime.requestDiscovery(this);
            main.post(()->notice.setText(report));
        });
    }
}
