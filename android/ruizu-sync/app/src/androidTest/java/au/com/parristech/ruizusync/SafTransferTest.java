package au.com.parristech.ruizusync;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;

@RunWith(AndroidJUnit4.class)
public final class SafTransferTest {
    private android.app.Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private final Uri tree=DocumentsContract.buildTreeDocumentUri(FakeUsbProvider.AUTHORITY,"root");
    private Context context;
    @Before public void setUp() throws Exception { context=getInstrumentation().getTargetContext();
        // Start provider in the instrumentation process before resetting its state.
        context.getContentResolver().query(DocumentsContract.buildDocumentUri(FakeUsbProvider.AUTHORITY,"root"),null,null,null,null).close();
        control("reset",null); context.getSharedPreferences("ruizu-pending",0).edit().clear().commit();
    }
    private android.os.Bundle control(String method,String value) {
        return context.getContentResolver().call(FakeUsbProvider.AUTHORITY,method,value,null);
    }
    private boolean transfer(byte[] bytes) throws Exception {
        return new SafTransfer(context,()->false).transfer(new ByteArrayInputStream(bytes),tree,"chapter.mp3",bytes.length,(n,t)->{});
    }
    private void expectFailure() throws Exception {
        try {transfer(new byte[]{1,2,3}); fail("Expected USB failure");}catch(IOException expected){}
    }
    @Test public void testVerifiedTransferAndIdenticalRetry() throws Exception {
        assertTrue(transfer(new byte[]{1,2,3})); assertFalse(transfer(new byte[]{1,2,3}));
        assertEquals(1,control("state",null).getInt("count")); assertTrue(control("state",null).getStringArrayList("names").contains("chapter.mp3"));
    }
    @Test public void testSameSizeDifferentContentPreserved() throws Exception {
        transfer(new byte[]{1,2,3}); transfer(new byte[]{3,2,1});
        assertFalse(transfer(new byte[]{3,2,1}));
        assertEquals(2,control("state",null).getInt("count")); assertTrue(control("state",null).getStringArrayList("names").contains("chapter (2).mp3"));
    }
    @Test public void testFullStorageCleansOnlyOwnOutput() throws Exception {
        transfer(new byte[]{4,5,6}); control("full","true"); expectFailure(); assertEquals(1,control("state",null).getInt("count"));
    }
    @Test public void testCorruptionNeverPublished() throws Exception {
        control("corrupt","true"); expectFailure(); assertEquals(0,control("state",null).getInt("count"));
    }
    @Test public void testRenameFailureNeverPublished() throws Exception {
        control("renameFails","true"); expectFailure(); assertEquals(0,control("state",null).getInt("count"));
    }
    @Test public void testDisconnectedFolderFails() throws Exception {
        control("disconnected","true"); expectFailure(); control("disconnected","false"); assertEquals(0,control("state",null).getInt("count"));
    }
    @Test public void testInterruptedTransferRecoveredOnRetry() throws Exception {
        InputStream source=new InputStream(){public int read() throws IOException {control("disconnected","true"); throw new IOException("Detached mid-copy");}};
        try {new SafTransfer(context,()->false).transfer(source,tree,"chapter.mp3",3,(n,t)->{});fail();}catch(IOException expected){}
        control("disconnected","false");
        assertEquals(1,control("state",null).getInt("count"));
        transfer(new byte[]{1,2,3}); assertEquals(1,control("state",null).getInt("count")); assertTrue(control("state",null).getStringArrayList("names").contains("chapter.mp3"));
    }
    @Test public void testRecoveryDoesNotDeleteRenamedPublishedOutput() throws Exception {
        transfer(new byte[]{1,2,3});
        String id=control("state",null).getStringArrayList("ids").get(0);
        Uri published=DocumentsContract.buildDocumentUriUsingTree(tree,id);
        context.getSharedPreferences("ruizu-pending",0).edit().putString(tree.toString(),published.toString())
            .putString(tree + ":name", ".ruizu-old.part").commit();
        assertFalse(transfer(new byte[]{1,2,3})); assertEquals(1,control("state",null).getInt("count"));
    }
    @Test public void testCancellationPreservesExistingLibrary() throws Exception {
        transfer(new byte[]{4,5,6});
        try {new SafTransfer(context,()->true).transfer(new ByteArrayInputStream(new byte[]{1}),tree,"other.mp3",1,(n,t)->{});fail();}catch(InterruptedIOException expected){}
        assertEquals(1,control("state",null).getInt("count"));
    }
}
