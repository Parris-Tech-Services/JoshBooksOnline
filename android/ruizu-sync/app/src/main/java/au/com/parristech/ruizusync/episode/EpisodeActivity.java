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
        help.setText("Select a player once, then choose your video library. RUIZU Sync will remember every episode, prepare AMV on the phone, and verify each copy. No Termux or separate app.");
        root.addView(help);
        players=new Spinner(this); root.addView(players,new LinearLayout.LayoutParams(-1,-2));
        button(root,"Bind player selected on main screen",()->{
            AppState app=AppState.get();
            String id=app.destinationDeviceId(),name=app.destinationDeviceName();
            Uri tree=app.destinationTree();
            task(q->{ q.bind(id,name,tree); reload(q,id); });
        });
        button(root,"Choose video library folder",()->pick(true));
        button(root,"Add individual videos (including Drive)",()->pick(false));
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
            new AlertDialog.Builder(this).setTitle("Queue this library?")
                .setMessage("All supported videos in this folder and its subfolders will be discovered incrementally for the selected player. Existing media will never be replaced.")
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
