package au.com.parristech.ruizusync;
import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.util.*;

/** Test APK only. Filesystem-backed DocumentsProvider with injectable USB faults. */
public final class FakeUsbProvider extends DocumentsProvider {
    static volatile boolean disconnected, full, corrupt, renameFails;
    static final String AUTHORITY = "au.com.parristech.ruizusync.test.usb";
    static final Map<String,String> names = new LinkedHashMap<>();
    static File root;
    public boolean onCreate() { root = new File(getContext().getCacheDir(),"usb-test"); root.mkdirs(); return true; }
    static synchronized void reset() {
        disconnected=full=corrupt=renameFails=false; names.clear();
        if (root != null && root.listFiles() != null) for (File f:root.listFiles()) f.delete();
    }
    private void connected() throws FileNotFoundException { if(disconnected) throw new FileNotFoundException("USB detached"); }
    @Override public synchronized Bundle call(String method, String argument, Bundle extras) {
        if (!Arrays.asList("reset", "disconnected", "full", "corrupt", "renameFails", "state").contains(method)) {
            return super.call(method, argument, extras);
        }
        if (method.equals("reset")) reset();
        if (method.equals("disconnected")) disconnected=Boolean.parseBoolean(argument);
        if (method.equals("full")) full=Boolean.parseBoolean(argument);
        if (method.equals("corrupt")) corrupt=Boolean.parseBoolean(argument);
        if (method.equals("renameFails")) renameFails=Boolean.parseBoolean(argument);
        Bundle result=new Bundle(); result.putInt("count",names.size());
        result.putStringArrayList("names",new ArrayList<>(names.values()));
        result.putStringArrayList("ids",new ArrayList<>(names.keySet()));
        return result;
    }
    @Override public synchronized boolean isChildDocument(String parent, String document) {
        return parent.equals("root") && names.containsKey(document);
    }
    public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID}); }
    private MatrixCursor cursor() {
        return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS});
    }
    private void row(MatrixCursor c,String id,String name) {
        c.addRow(new Object[]{id,name,id.equals("root")?DocumentsContract.Document.MIME_TYPE_DIR:"audio/mpeg",new File(root,id).length(),
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_RENAME|DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE});
    }
    public synchronized Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        connected(); MatrixCursor c=cursor(); row(c,id,names.getOrDefault(id,"root")); return c;
    }
    public synchronized Cursor queryChildDocuments(String id,String[] projection,String sort) throws FileNotFoundException {
        connected(); MatrixCursor c=cursor(); for(Map.Entry<String,String> entry:names.entrySet()) row(c,entry.getKey(),entry.getValue()); return c;
    }
    public synchronized String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        connected(); String id=UUID.randomUUID().toString();
        try {new File(root,id).createNewFile();}catch(IOException e){throw new FileNotFoundException();}
        names.put(id,name); return id;
    }
    public synchronized void deleteDocument(String id) throws FileNotFoundException {connected(); new File(root,id).delete(); names.remove(id);}
    public synchronized String renameDocument(String id,String name) throws FileNotFoundException {
        connected(); if(renameFails) throw new FileNotFoundException("Rename failed"); names.put(id,name); return id;
    }
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        connected(); if(full && mode.contains("w")) throw new FileNotFoundException("Storage full");
        File file=new File(root,id);
        if(corrupt && mode.equals("r") && names.get(id).endsWith(".part")) {
            try(FileOutputStream out=new FileOutputStream(file)){out.write(99);}catch(IOException e){throw new FileNotFoundException();}
        }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
    }
}
