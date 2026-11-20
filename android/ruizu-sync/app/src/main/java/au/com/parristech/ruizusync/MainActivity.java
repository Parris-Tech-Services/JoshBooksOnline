package au.com.parristech.ruizusync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_MUSIC_FOLDER = 101;
    private static final int PICK_PODCAST_FOLDER = 102;
    private static final int PICK_MUSIC_FILES = 201;
    private static final int PICK_PODCAST_FILES = 202;
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_DOWNLOAD = 2L * 1024 * 1024 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private TextView status;
    private TextView musicStatus;
    private TextView podcastStatus;
    private EditText urlText;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("ruizu", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, dp(28), pad, pad);
        scroll.addView(layout);
        setContentView(scroll);

        TextView heading = text("RUIZU Sync", 27);
        layout.addView(heading);
        layout.addView(text("One-tap audio transfer from your Samsung to your RUIZU player.", 15));
        layout.addView(text("1. Connect USB. 2. Choose the RUIZU Music and Podcasts folders once. 3. Transfer audio.", 14));

        musicStatus = text("", 13);
        podcastStatus = text("", 13);
        layout.addView(musicStatus);
        layout.addView(podcastStatus);
        refreshFolders();
        addButton(layout, "Choose RUIZU Music folder", v -> chooseFolder(PICK_MUSIC_FOLDER));
        addButton(layout, "Choose RUIZU Podcasts folder", v -> chooseFolder(PICK_PODCAST_FOLDER));
        addButton(layout, "Add music from phone / Google Drive", v -> pickAudio(PICK_MUSIC_FILES));
        addButton(layout, "Add podcasts from phone / Google Drive", v -> pickAudio(PICK_PODCAST_FILES));

        layout.addView(text("Podcast RSS or direct downloadable audio link", 17));
        urlText = new EditText(this);
        urlText.setSingleLine(true);
        urlText.setHint("https://example.com/episode.mp3 or feed.xml");
        layout.addView(urlText);
        addButton(layout, "Browse podcast feed", v -> showPodcastFeed(urlText.getText().toString().trim()));
        addButton(layout, "Transfer audio link to Music", v -> download(urlText.getText().toString().trim(), "music", ""));
        addButton(layout, "Transfer audio link to Podcasts", v -> download(urlText.getText().toString().trim(), "podcasts", ""));

        layout.addView(text("Your libraries", 17));
        addButton(layout, "Open JoshBooks", v -> open("https://josh-books-online.vercel.app/library"));
        addButton(layout, "Open Google Drive", v -> open("https://drive.google.com/drive/u/0/my-drive"));
        addButton(layout, "Open Spotify", v -> open("https://open.spotify.com"));
        addButton(layout, "Open YouTube Music", v -> open("https://music.youtube.com"));
        addButton(layout, "Open YouTube", v -> open("https://www.youtube.com"));
        layout.addView(text("Spotify and YouTube Music app downloads cannot be exported as MP3s. Use your own downloadable audio files or an authorised podcast MP3/RSS feed instead.", 13));

        status = text("Ready. Existing RUIZU files will not be deleted.", 14);
        layout.addView(status);
        handleSharedIntent(getIntent());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setPadding(0, dp(9), 0, dp(9));
        return view;
    }
    private void addButton(LinearLayout l, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        l.addView(b);
        b.setOnClickListener(click);
    }
    private void report(String message) {
        runOnUiThread(() -> status.setText(message));
    }
    private void refreshFolders() {
        musicStatus.setText("Music destination: " + (preferences.contains("folder_music") ? "selected" : "not selected"));
        podcastStatus.setText("Podcasts destination: " + (preferences.contains("folder_podcasts") ? "selected" : "not selected"));
    }
    private void chooseFolder(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, request);
    }
    private void pickAudio(int request) {
        if (!hasFolder(request == PICK_MUSIC_FILES ? "music" : "podcasts")) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // Drive sometimes labels MP3 as application/octet-stream.
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, request);
    }
    private boolean hasFolder(String target) {
        if (preferences.contains("folder_" + target)) return true;
        report("Choose the RUIZU " + target + " folder first.");
        return false;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_MUSIC_FOLDER || request == PICK_PODCAST_FOLDER) {
            Uri chosen = data.getData();
            if (chosen == null) return;
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(chosen, flags);
                String target = request == PICK_MUSIC_FOLDER ? "music" : "podcasts";
                preferences.edit().putString("folder_" + target, chosen.toString()).apply();
                refreshFolders();
                report("Selected RUIZU " + target + " folder.");
            } catch (Exception error) { report("Folder permission failed: " + error.getMessage()); }
            return;
        }
        if (request == PICK_MUSIC_FILES || request == PICK_PODCAST_FILES) {
            String target = request == PICK_MUSIC_FILES ? "music" : "podcasts";
            ArrayList<Uri> list = new ArrayList<>();
            if (data.getData() != null) list.add(data.getData());
            ClipData clip = data.getClipData();
            if (clip != null) for (int n = 0; n < clip.getItemCount(); n++) list.add(clip.getItemAt(n).getUri());
            copyUris(list, target);
        }
    }

    @Override protected void onNewIntent(Intent incoming) {
        super.onNewIntent(incoming);
        setIntent(incoming);
        handleSharedIntent(incoming);
    }
    private void handleSharedIntent(Intent incoming) {
        if (incoming == null || incoming.getAction() == null) return;
        String action = incoming.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            if ("text/plain".equals(incoming.getType())) {
                String text = incoming.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null && urlText != null) urlText.setText(text.trim());
            } else {
                Uri uri = incoming.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) chooseSharedTarget(uri);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("Transfer shared audio to")
                    .setItems(new String[]{"Music", "Podcasts"}, (dialog, which) ->
                        copyUris(list, which == 0 ? "music" : "podcasts"))
                    .show();
            }
        }
    }
    private void chooseSharedTarget(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Transfer shared audio to")
            .setItems(new String[]{"Music", "Podcasts"}, (dialog, which) -> {
                ArrayList<Uri> items = new ArrayList<>();
                items.add(uri);
                copyUris(items, which == 0 ? "music" : "podcasts");
            }).show();
    }
    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "Audio.mp3" : uri.getLastPathSegment();
    }
    private long fileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {}
        return -1;
    }
    private Uri targetDir(String folder) {
        String value = preferences.getString("folder_" + folder, null);
        if (value == null) throw new IllegalStateException("Choose RUIZU " + folder + " folder first");
        Uri tree = Uri.parse(value);
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }
    private String availableName(String folder, String requested, long size) {
        Uri parent = targetDir(folder);
        Uri childUri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(preferences.getString("folder_" + folder, "")),
                DocumentsContract.getDocumentId(parent));
        ArrayList<String> taken = new ArrayList<>();
        try (Cursor c = getContentResolver().query(childUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE},
                null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                String name = c.getString(0);
                if (name == null) continue;
                taken.add(name.toLowerCase(Locale.ROOT));
                if (name.equalsIgnoreCase(requested) && size >= 0 && !c.isNull(1) && size == c.getLong(1)) return null;
            }
        }
        if (!taken.contains(requested.toLowerCase(Locale.ROOT))) return requested;
        for (int n = 2; n < 10000; n++) {
            String name = TransferUtils.withNumber(requested, n);
            if (!taken.contains(name.toLowerCase(Locale.ROOT))) return name;
        }
        throw new IllegalStateException("Too many duplicate filenames");
    }
    private void writeToRuizu(InputStream input, String target, String originalName, long length) throws Exception {
        String name = TransferUtils.safeName(originalName);
        if (!TransferUtils.isSupportedAudio(name)) throw new IOException("Unsupported file: " + name);
        String free = availableName(target, name, length);
        if (free == null) { report("Already on RUIZU (same filename and size): " + name); return; }
        String mime = free.toLowerCase(Locale.ROOT).endsWith(".mp3") ? "audio/mpeg" : "audio/*";
        Uri created = DocumentsContract.createDocument(getContentResolver(), targetDir(target), mime, free);
        if (created == null) throw new IOException("Could not create " + free + " on USB");
        boolean ok = false;
        try (OutputStream out = getContentResolver().openOutputStream(created, "w")) {
            if (out == null) throw new IOException("Cannot write RUIZU folder");
            byte[] buf = new byte[65536];
            long copied = 0;
            int n;
            while ((n = input.read(buf)) != -1) {
                copied += n;
                if (copied > MAX_DOWNLOAD) throw new IOException("File exceeds 2 GB limit");
                out.write(buf, 0, n);
            }
            out.flush();
            ok = true;
            report("Transferred: " + free);
        } finally {
            if (!ok) try { DocumentsContract.deleteDocument(getContentResolver(), created); } catch (Exception ignored) {}
        }
    }
    private void copyUris(List<Uri> uris, String target) {
        if (!hasFolder(target) || uris.isEmpty()) return;
        report("Transferring " + uris.size() + " file(s)...");
        io.execute(() -> {
            int passed = 0, failed = 0;
            for (Uri uri : uris) {
                try {
                    String name = displayName(uri);
                    if (!TransferUtils.isSupportedAudio(name)) throw new IOException("Not an audio file: " + name);
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("Cannot read " + name);
                        writeToRuizu(in, target, name, fileSize(uri));
                        passed++;
                    }
                } catch (Exception error) {
                    failed++;
                    report("Transfer failed: " + error.getMessage());
                }
            }
            report("Finished: " + passed + " processed; " + failed + " failed. Safe to eject after completion.");
        });
    }

    private HttpURLConnection connect(String raw) throws Exception {
        URL url = new URL(raw);
        for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS link required");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "RUIZUSync/0.1");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) throw new IOException("Redirect without destination");
                url = new URL(url, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                c.disconnect();
                throw new IOException("Download HTTP " + code);
            }
            if (c.getContentLengthLong() > MAX_DOWNLOAD) {
                c.disconnect();
                throw new IOException("Download exceeds 2 GB");
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }
    private void download(String link, String target, String fallbackTitle) {
        if (!hasFolder(target)) return;
        if (TransferUtils.isStreamingLink(link)) {
            report("Spotify / YouTube streams cannot be copied as MP3 files. Use a downloadable audio source.");
            return;
        }
        if (!link.startsWith("https://")) { report("Paste a direct HTTPS audio download link."); return; }
        report("Downloading audio...");
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = connect(link);
                String contentType = c.getContentType();
                String tail = c.getURL().getPath();
                tail = tail.substring(tail.lastIndexOf('/') + 1);
                tail = java.net.URLDecoder.decode(tail, StandardCharsets.UTF_8.name());
                String name = TransferUtils.isSupportedAudio(tail) ? tail : TransferUtils.safeName(fallbackTitle);
                if (!TransferUtils.isSupportedAudio(name)) {
                    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("audio/mpeg")) name += ".mp3";
                    else throw new IOException("Link is not an MP3 or supported downloadable audio file");
                }
                try (InputStream in = c.getInputStream()) {
                    writeToRuizu(in, target, name, c.getContentLengthLong());
                }
            } catch (Exception error) { report("Download failed: " + error.getMessage()); }
            finally { if (c != null) c.disconnect(); }
        });
    }
    private static final class Episode {
        final String title;
        final String url;
        Episode(String title, String url) { this.title = title; this.url = url; }
    }
    private void showPodcastFeed(String link) {
        if (!hasFolder("podcasts")) return;
        if (!link.startsWith("https://")) { report("Paste an HTTPS podcast RSS feed URL."); return; }
        report("Loading podcast episodes...");
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = connect(link);
                List<Episode> episodes = new ArrayList<>();
                XmlPullParser x = Xml.newPullParser();
                try (InputStream in = c.getInputStream()) {
                    x.setInput(in, "UTF-8");
                    String title = "";
                    String enclosure = null;
                    boolean inEpisode = false;
                    int event;
                    while ((event = x.next()) != XmlPullParser.END_DOCUMENT && episodes.size() < 100) {
                        if (event == XmlPullParser.START_TAG) {
                            String tag = x.getName();
                            if ("item".equalsIgnoreCase(tag) || "entry".equalsIgnoreCase(tag)) {
                                title = ""; enclosure = null; inEpisode = true;
                            } else if (inEpisode && "title".equalsIgnoreCase(tag)) {
                                title = x.nextText();
                            } else if (inEpisode && ("enclosure".equalsIgnoreCase(tag) || "link".equalsIgnoreCase(tag))) {
                                String candidate = x.getAttributeValue(null, "url");
                                if (candidate == null && "link".equalsIgnoreCase(tag)) candidate = x.getAttributeValue(null, "href");
                                if (candidate != null && candidate.startsWith("https://") && (TransferUtils.isSupportedAudio(Uri.parse(candidate).getPath())
                                        || "audio/mpeg".equalsIgnoreCase(x.getAttributeValue(null, "type")))) enclosure = candidate;
                            }
                        } else if (event == XmlPullParser.END_TAG && inEpisode
                                && ("item".equalsIgnoreCase(x.getName()) || "entry".equalsIgnoreCase(x.getName()))) {
                            if (enclosure != null) episodes.add(new Episode(title.isEmpty() ? "Podcast episode" : title, enclosure));
                            inEpisode = false;
                        }
                    }
                }
                runOnUiThread(() -> {
                    if (episodes.isEmpty()) { report("No downloadable MP3 episodes found in this feed."); return; }
                    String[] titles = new String[episodes.size()];
                    boolean[] selected = new boolean[episodes.size()];
                    for (int i = 0; i < titles.length; i++) titles[i] = episodes.get(i).title;
                    new AlertDialog.Builder(this).setTitle("Select podcast episodes")
                        .setMultiChoiceItems(titles, selected, (dialog, index, checked) -> selected[index] = checked)
                        .setPositiveButton("Transfer selected", (dialog, which) -> {
                            int queued = 0;
                            for (int i = 0; i < selected.length; i++) if (selected[i]) {
                                Episode ep = episodes.get(i);
                                download(ep.url, "podcasts", ep.title);
                                queued++;
                            }
                            report(queued + " podcast episode(s) queued.");
                        }).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception error) { report("Podcast feed failed: " + error.getMessage()); }
            finally { if (c != null) c.disconnect(); }
        });
    }
    private void open(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
