package au.com.parristech.ruizusync;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import au.com.parristech.ruizusync.core.*;
import au.com.parristech.ruizusync.model.SourceItem;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TransferManagerTest {
    @Test public void actualManagerUsesVerifiedSafAndRejectsOverwrite() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getContentResolver().call(Uri.parse("content://" + FakeUsbProvider.AUTHORITY),"reset",null,null);
        File source=new File(context.getCacheDir(),"integration-source.mp3");
        try(FileOutputStream out=new FileOutputStream(source)){out.write(new byte[]{1,2,3});}
        SourceItem item=new SourceItem(Uri.fromFile(source),"integration-source.mp3",3,"audio/mpeg","",null,null);
        Uri tree=DocumentsContract.buildTreeDocumentUri(FakeUsbProvider.AUTHORITY,"root");
        TransferManager manager=TransferManager.get(context);
        CountDownLatch finished=new CountDownLatch(1);
        TransferManager.Listener listener=snapshot->{if(snapshot.isFinished() && !snapshot.running)finished.countDown();};
        manager.addListener(listener);
        try {
            manager.start(new TransferManager.Request(Collections.singletonList(item),tree,FolderPlanner.OrganiseMode.FLAT,DuplicateMode.SKIP_EXACT));
            assertTrue("Manager did not finish",finished.await(20,TimeUnit.SECONDS));
            assertEquals(manager.snapshot().status,1,manager.snapshot().copied);
            assertEquals(0,manager.snapshot().failed);
            assertTrue(manager.snapshot().log.toString().contains("SHA-256 verified"));
            try {manager.start(new TransferManager.Request(Collections.singletonList(item),tree,FolderPlanner.OrganiseMode.FLAT,DuplicateMode.OVERWRITE));fail("Overwrite must be rejected");}
            catch(IllegalArgumentException expected){}
        } finally {manager.removeListener(listener); source.delete();}
    }
    @Test public void convertedAmvUsesExistingVerifiedSafTransferIntoVideo() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getContentResolver().call(Uri.parse("content://" + FakeUsbProvider.AUTHORITY),"reset",null,null);
        File source=new File(context.getCacheDir(),"playable-film.amv");
        try(FileOutputStream out=new FileOutputStream(source)){out.write(new byte[]{4,5,6,7,8});}
        SourceItem item=new SourceItem(Uri.fromFile(source),"playable-film.amv",5,
                "video/x-amv","",null,null);
        Uri tree=DocumentsContract.buildTreeDocumentUri(FakeUsbProvider.AUTHORITY,"root");
        TransferManager manager=TransferManager.get(context);
        CountDownLatch finished=new CountDownLatch(1);
        TransferManager.Listener listener=snapshot->{if(snapshot.isFinished() && !snapshot.running)finished.countDown();};
        try {
            manager.start(new TransferManager.Request(Collections.singletonList(item),tree,
                    FolderPlanner.OrganiseMode.ARTIST_ALBUM,DuplicateMode.SKIP_EXACT));
            // Register after start: addListener replays the current snapshot. Registering
            // beforehand can replay the preceding test’s DONE snapshot and count down
            // before this new transfer has begun.
            manager.addListener(listener);
            assertTrue("AMV transfer did not finish",finished.await(20,TimeUnit.SECONDS));
            assertEquals(manager.snapshot().status,1,manager.snapshot().copied);
            assertEquals(0,manager.snapshot().failed);
            assertTrue("AMV must go into VIDEO",manager.snapshot().log.toString().contains("→ VIDEO"));
            assertTrue("Must use SHA-256 readback",manager.snapshot().log.toString().contains("SHA-256 verified"));
        } finally {manager.removeListener(listener); source.delete();}
    }
}
