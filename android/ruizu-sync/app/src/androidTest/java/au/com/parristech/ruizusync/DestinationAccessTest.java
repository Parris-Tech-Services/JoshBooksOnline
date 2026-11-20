package au.com.parristech.ruizusync;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DestinationAccessTest {
    @Test public void staleOrMalformedSavedDestinationIsNeverReportedUsable() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs=context.getSharedPreferences("ruizu",0);
        String old=prefs.getString("destination_tree",null);
        try {
            prefs.edit().putString("destination_tree","not-a-document").commit();
            assertNull(DestinationAccess.restore(context));
            Uri unavailable=DocumentsContract.buildTreeDocumentUri("missing.provider","USB:Music");
            prefs.edit().putString("destination_tree",unavailable.toString()).commit();
            assertNull(DestinationAccess.restore(context));
            assertFalse(DestinationAccess.writable(context,unavailable));
        } finally {
            if(old==null)prefs.edit().remove("destination_tree").commit();
            else prefs.edit().putString("destination_tree",old).commit();
        }
    }
}
