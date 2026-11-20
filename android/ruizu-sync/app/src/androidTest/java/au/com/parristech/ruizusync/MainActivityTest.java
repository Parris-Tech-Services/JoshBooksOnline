package au.com.parristech.ruizusync;
import android.app.Activity;
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
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(()-> {
            assertTrue(contains(activity.getWindow().getDecorView(),"Choose RUIZU Music folder"));
            assertTrue(contains(activity.getWindow().getDecorView(),"Cancel transfers"));
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
        getInstrumentation().waitForIdleSync();
        Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory=getInstrumentation().getContext().getExternalFilesDir(null);
        try(FileOutputStream out=new FileOutputStream(new File(directory,"ruizu-ui.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        getInstrumentation().runOnMainSync(activity::finish);
    }
}
