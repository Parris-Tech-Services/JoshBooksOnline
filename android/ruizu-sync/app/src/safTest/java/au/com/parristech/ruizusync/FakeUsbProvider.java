package au.com.parristech.ruizusync;
import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.util.*;

/** Test APK only. Filesystem-backed DocumentsProvider with injectable USB faults. */
public final class FakeUsbProvider extends DocumentsProvider {
    static volatile boolean disconnected, full, corrupt, renameFails, blankNames, unsyncable;
    static final String AUTHORITY = "au.com.parristech.ruizusync.test.usb";
    static final Map<String,String> names = new LinkedHashMap<>();
    // Simulate real DocumentsProvider hierarchy for VIDEO/ and other folders.
    static final Map<String,String> parents = new HashMap<>();
    static final Map<String,String> mimeTypes = new HashMap<>();
    static File root;
    public boolean onCreate() { root = new File(getContext().getCacheDir(),"usb-test"); root.mkdirs(); return true; }
    static synchronized void reset() {
        disconnected=full=corrupt=renameFails=blankNames=unsyncable=false;
        names.clear(); parents.clear(); mimeTypes.clear();
        if (root != null && root.listFiles() != null) for (File f:root.listFiles()) f.delete();
    }
    private void connected() throws FileNotFoundException { if(disconnected) throw new FileNotFoundException("USB detached"); }
    @Override public synchronized Bundle call(String method, String argument, Bundle extras) {
        if (!Arrays.asList("reset", "disconnected", "full", "corrupt", "renameFails", "blankNames", "unsyncable",
                "addName", "mkTree", "state").contains(method)) {
            return super.call(method, argument, extras);
        }
        if (method.equals("reset")) reset();
        if (method.equals("disconnected")) disconnected=Boolean.parseBoolean(argument);
        if (method.equals("full")) full=Boolean.parseBoolean(argument);
        if (method.equals("corrupt")) corrupt=Boolean.parseBoolean(argument);
        if (method.equals("renameFails")) renameFails=Boolean.parseBoolean(argument);
        if (method.equals("unsyncable")) unsyncable=Boolean.parseBoolean(argument);
        // Simulates a sender that grants read access but exposes no display name, which is the
        // shape of another app sharing a MediaStore row whose projection is refused.
        if (method.equals("blankNames")) blankNames=Boolean.parseBoolean(argument);
        // A tree root that exists as a real directory, so a test can address two tree ids on the one
        // volume id both RUIZU units report. The id itself stays opaque to the provider.
        if (method.equals("mkTree") && argument != null) {
            names.put(argument,argument);
            parents.put(argument,"root");
            mimeTypes.put(argument,DocumentsContract.Document.MIME_TYPE_DIR);
            new File(root,argument).mkdirs();
        }
        if (method.equals("addName") && argument != null) {
            int tab = argument.indexOf('\t');
            if (tab > 0) {
                String docId=argument.substring(0,tab);
                names.put(docId,argument.substring(tab+1));
                parents.put(docId,"root");
                mimeTypes.put(docId,"audio/mpeg");
            }
        }
        Bundle result=new Bundle(); result.putInt("count",names.size());
        result.putStringArrayList("names",new ArrayList<>(names.values()));
        result.putStringArrayList("ids",new ArrayList<>(names.keySet()));
        return result;
    }
    @Override public synchronized boolean isChildDocument(String parent, String document) {
        if (parent == null || document == null || !names.containsKey(document)) return false;
        String ancestor = parents.get(document);
        for (int depth = 0; ancestor != null && depth < 16; depth++) {
            if (ancestor.equals(parent)) return true;
            ancestor = parents.get(ancestor);
        }
        return false;
    }
    public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID}); }
    private MatrixCursor cursor() {
        return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS});
    }
    private void row(MatrixCursor c,String id,String name) {
        if (blankNames && !id.equals("root")) name="";
        String mime=id.equals("root")?DocumentsContract.Document.MIME_TYPE_DIR:
                mimeTypes.getOrDefault(id,"audio/mpeg");
        int flags=DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            flags|=DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags|=DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        }
        c.addRow(new Object[]{id,name,mime,new File(root,id).length(),flags});
    }
    public synchronized Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        connected(); MatrixCursor c=cursor(); row(c,id,names.getOrDefault(id,"root")); return c;
    }
    public synchronized Cursor queryChildDocuments(String id,String[] projection,String sort) throws FileNotFoundException {
        connected(); MatrixCursor c=cursor();
        for(Map.Entry<String,String> entry:names.entrySet()) {
            if(id.equals(parents.get(entry.getKey()))) row(c,entry.getKey(),entry.getValue());
        }
        return c;
    }
    public synchronized String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        connected();
        if (!parent.equals("root") && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeTypes.get(parent))) {
            throw new FileNotFoundException("Parent is not a directory: "+parent);
        }
        String id=UUID.randomUUID().toString();
        try {
            boolean created=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
                    ? new File(root,id).mkdir() : new File(root,id).createNewFile();
            if (!created) throw new IOException("Could not create document");
        } catch(IOException e) { throw new FileNotFoundException(e.getMessage()); }
        names.put(id,name); parents.put(id,parent); mimeTypes.put(id,mime); return id;
    }
    public synchronized void deleteDocument(String id) throws FileNotFoundException {
        connected();
        if (!new File(root,id).delete()) throw new FileNotFoundException("Could not delete "+id);
        names.remove(id); parents.remove(id); mimeTypes.remove(id);
    }
    public synchronized String renameDocument(String id,String name) throws FileNotFoundException {
        connected(); if(renameFails) throw new FileNotFoundException("Rename failed"); names.put(id,name); return id;
    }
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        connected(); if(full && mode.contains("w")) throw new FileNotFoundException("Storage full");
        // A write-only null device accepts bytes but refuses fsync. The real temporary
        // file remains untouched, simulating a provider without durable descriptor writes.
        if (unsyncable && mode.contains("w") && names.get(id).endsWith(".part")) {
            return ParcelFileDescriptor.open(new File("/dev/null"),ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        File file=new File(root,id);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeTypes.get(id))) {
            throw new FileNotFoundException("Cannot open directory as file");
        }
        if(corrupt && mode.equals("r") && names.get(id).endsWith(".part")) {
            try(FileOutputStream out=new FileOutputStream(file)){out.write(99);}catch(IOException e){throw new FileNotFoundException();}
        }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
    }
}
