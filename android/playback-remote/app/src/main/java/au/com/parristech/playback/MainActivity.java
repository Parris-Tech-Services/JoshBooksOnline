package au.com.parristech.playback;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal transport controls for the audio staged on this device.
 *
 * Deliberately self-contained: it owns its own MediaPlayer and MediaSession, so the buttons
 * here, the Bluetooth headset buttons and the lock-screen card all drive the same playback.
 * It does not talk to Termux, adb or any other app.
 */
public final class MainActivity extends Activity {

    private static final int REQ_AUDIO = 1;

    private final List<Uri> queue = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    private MediaPlayer player;
    private MediaSession session;
    private int index = 0;

    private TextView titleView;
    private TextView statusView;
    private SeekBar seekBar;
    private Button playButton;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private boolean dragging = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        createSession();
        if (hasAudioPermission()) {
            loadQueue();
        } else {
            requestPermissions(new String[]{audioPermission()}, REQ_AUDIO);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(56, 72, 56, 56);
        root.setBackgroundColor(Color.parseColor("#101014"));

        TextView heading = new TextView(this);
        heading.setText("Playback Remote");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(24f);
        root.addView(heading);

        titleView = new TextView(this);
        titleView.setTextColor(Color.parseColor("#8fb6da"));
        titleView.setTextSize(15f);
        titleView.setPadding(0, 28, 0, 8);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#6f7a86"));
        statusView.setTextSize(13f);
        statusView.setPadding(0, 0, 0, 32);
        root.addView(statusView);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo((int) (player.getDuration() * (bar.getProgress() / 1000.0)));
                }
                updateStatus();
            }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout rowOne = row();
        rowOne.addView(button("\u23EA 15", v -> nudge(-15000)));
        rowOne.addView(button("\u23EE", v -> changeTrack(-1)));
        playButton = button("\u25B6", v -> togglePlay());
        rowOne.addView(playButton);
        rowOne.addView(button("\u23ED", v -> changeTrack(1)));
        rowOne.addView(button("30 \u23E9", v -> nudge(30000)));
        root.addView(rowOne);

        LinearLayout rowTwo = row();
        rowTwo.addView(button("Restart podcast", v -> restart()));
        rowTwo.addView(button("Add to Home", v -> pinShortcut()));
        root.addView(rowTwo);

        return root;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, 20, 0, 20);
        return row;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16f);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------------------------------------------------------- permissions + library

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                loadQueue();
            } else {
                titleView.setText("Audio permission denied");
                statusView.setText("Grant 'Music and audio' access to list tracks");
            }
        }
    }

    /** Reads the on-device audio that lives under a Music/ folder. */
    private void loadQueue() {
        queue.clear();
        names.clear();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION
        };
        try (Cursor cursor = getContentResolver().query(
                collection, projection, null, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                while (cursor.moveToNext() && queue.size() < 40) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    if (name == null || !name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|m4b)$")) {
                        continue;
                    }
                    queue.add(ContentUris.withAppendedId(collection, id));
                    names.add(name);
                }
            }
        } catch (Exception error) {
            titleView.setText("Could not read the library");
            statusView.setText(String.valueOf(error.getMessage()));
            return;
        }
        if (queue.isEmpty()) {
            titleView.setText("No audio found");
            statusView.setText("Put an MP3 or M4A in the phone's Music folder");
            return;
        }
        prepare(0);
    }

    // ---------------------------------------------------------------- playback

    private void prepare(int wanted) {
        index = Math.floorMod(wanted, queue.size());
        if (player != null) {
            player.release();
            player = null;
        }
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        try {
            player.setDataSource(this, queue.get(index));
            player.setOnPreparedListener(mp -> {
                titleView.setText(names.get(index));
                mp.start();
                updateStatus();
                publishState(true);
                startTicker();
            });
            player.setOnCompletionListener(mp -> changeTrack(1));
            player.setOnErrorListener((mp, what, extra) -> {
                statusView.setText("Playback error " + what + "/" + extra);
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            statusView.setText("Cannot open: " + error.getMessage());
        }
    }

    private void togglePlay() {
        if (player == null) {
            if (!queue.isEmpty()) {
                prepare(index);
            }
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            publishState(false);
        } else {
            player.start();
            publishState(true);
            startTicker();
        }
        updateStatus();
    }

    private void nudge(int millis) {
        if (player == null) {
            return;
        }
        int target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + millis));
        player.seekTo(target);
        updateStatus();
    }

    private void changeTrack(int delta) {
        if (queue.isEmpty()) {
            return;
        }
        prepare(index + delta);
    }

    private void restart() {
        if (!queue.isEmpty()) {
            prepare(index);
        }
    }

    private void startTicker() {
        ticker.removeCallbacksAndMessages(null);
        ticker.post(new Runnable() {
            @Override public void run() {
                updateStatus();
                if (player != null && player.isPlaying()) {
                    ticker.postDelayed(this, 500);
                }
            }
        });
    }

    private void updateStatus() {
        if (player == null) {
            return;
        }
        int duration = Math.max(0, player.getDuration());
        int position = Math.max(0, player.getCurrentPosition());
        if (!dragging && duration > 0) {
            seekBar.setProgress((int) (1000.0 * position / duration));
        }
        playButton.setText(player.isPlaying() ? "\u23F8" : "\u25B6");
        statusView.setText(String.format(Locale.US, "%s / %s  (%d of %d)",
                clock(position), clock(duration), index + 1, queue.size()));
    }

    private static String clock(int millis) {
        int totalSeconds = millis / 1000;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ---------------------------------------------------------------- media session

    private void createSession() {
        session = new MediaSession(this, "PlaybackRemote");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (player != null) { player.start(); publishState(true); updateStatus(); } }
            @Override public void onPause() { if (player != null) { player.pause(); publishState(false); updateStatus(); } }
            @Override public void onSkipToNext() { changeTrack(1); }
            @Override public void onSkipToPrevious() { changeTrack(-1); }
            @Override public void onSeekTo(long position) { nudge((int) (position - (player == null ? 0 : player.getCurrentPosition()))); }
            @Override public void onFastForward() { nudge(30000); }
            @Override public void onRewind() { nudge(-15000); }
        });
        session.setActive(true);
        publishState(false);
    }

    private void publishState(boolean playing) {
        if (session == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        player == null ? 0 : player.getCurrentPosition(), 1f)
                .build());
    }

    // ---------------------------------------------------------------- home screen

    private void pinShortcut() {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "Long-press the app icon to add it", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "This launcher cannot pin automatically - drag the icon", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "playback-remote")
                .setShortLabel("Playback Remote")
                .setLongLabel("Playback Remote controls")
                .setIcon(Icon.createWithResource(this, android.R.drawable.ic_media_play))
                .setIntent(new Intent(this, MainActivity.class)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER))
                .build();
        manager.requestPinShortcut(shortcut, null);
        Toast.makeText(this, "Confirm 'Add to Home screen'", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacksAndMessages(null);
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
