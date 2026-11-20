package au.com.parristech.ruizusync;
import android.app.Activity;
import au.com.parristech.ruizusync.ui.MainActivity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.view.*;
import android.widget.*;
import java.io.*;

@RunWith(AndroidJUnit4.class)
public final class MainActivityTest {
    private android.app.Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private boolean contains(View view,String label) {
        if(view instanceof TextView && label.contentEquals(((TextView)view).getText())) return true;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(contains(((ViewGroup)view).getChildAt(i),label)) return true;
        return false;
    }
    @Test public void testA55SizedUiAndRotation() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try (InputStream grant = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    getInstrumentation().getUiAutomation().executeShellCommand("pm grant "
                    + getInstrumentation().getTargetContext().getPackageName() + " android.permission.POST_NOTIFICATIONS"))) {
                while (grant.read() != -1) { /* Wait for the permission command to finish. */ }
            }
        }
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
        android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
        getInstrumentation().runOnMainSync(()-> {
            assertTrue(contains(activity.getWindow().getDecorView(),"Choose player folder"));
            assertTrue(contains(activity.getWindow().getDecorView(),"Start transfer"));
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
        Activity rotated = monitor.waitForActivityWithTimeout(5000);
        getInstrumentation().removeMonitor(monitor);
        assertNotNull("Activity should recreate on rotation", rotated);
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(()->assertTrue(contains(rotated.getWindow().getDecorView(),"Start transfer")));
        Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory=getInstrumentation().getTargetContext().getExternalFilesDir(null);
        try(FileOutputStream out=new FileOutputStream(new File(directory,"ruizu-ui.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        getInstrumentation().runOnMainSync(rotated::finish);
    }
}
