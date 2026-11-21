package au.com.parristech.ruizusync.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;

/** Runs as the test APK's UID, so grants originate from the actual provider owner. */
public final class ShareAudioActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ArrayList<Uri> audio = new ArrayList<>();
        String base = "content://" + getPackageName() + ".sharedaudio/audio/";
        audio.add(Uri.parse(base + "101"));
        audio.add(Uri.parse(base + "102"));
        ClipData clip = ClipData.newRawUri("Audio fixture", audio.get(0));
        clip.addItem(new ClipData.Item(audio.get(1)));
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setClassName(getIntent().getStringExtra("targetPackage"),
                        "au.com.parristech.ruizusync.ui.MainActivity")
                .setType("audio/mpeg")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, audio)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        share.setClipData(clip);
        startActivity(share);
        finish();
    }
}
