package au.com.parristech.ruizusync.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageVolume;
import android.text.InputType;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import au.com.parristech.ruizusync.AppState;
import au.com.parristech.ruizusync.R;
import au.com.parristech.ruizusync.TransferUtils;
import au.com.parristech.ruizusync.TransferManager;
import au.com.parristech.ruizusync.TransferService;
import au.com.parristech.ruizusync.core.ByteFormat;
import au.com.parristech.ruizusync.core.DuplicateMode;
import au.com.parristech.ruizusync.core.FolderPlanner;
import au.com.parristech.ruizusync.core.MediaTypes;
import au.com.parristech.ruizusync.io.DestRepo;
import au.com.parristech.ruizusync.io.SourceRepo;
import au.com.parristech.ruizusync.model.SourceItem;
import au.com.parristech.ruizusync.net.FeedScanner;
import au.com.parristech.ruizusync.net.RemoteAudio;
import au.com.parristech.ruizusync.usb.UsbVolumes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single screen: pick sources (phone / USB / Google Drive through the system picker), pick the
 * player folder, choose folder layout and duplicate handling, then watch progress.
 */
public final class MainActivity extends Activity implements TransferManager.Listener {

    private static final int REQ_ADD_FILES = 1001;
    private static final int REQ_ADD_FOLDER = 1002;
    private static final int REQ_DEST_TREE = 1003;
    private static final int REQ_NOTIFICATIONS = 2001;
    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;

    private AppState state;
    private TransferManager manager;
    private SourceRepo sourceRepo;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private BroadcastReceiver mediaReceiver;

    private TextView usbStatus;
    private TextView sourceSummary;
    private TextView destSummary;
    private TextView estimate;
    private Spinner organiseSpinner;
    private Spinner duplicateSpinner;
    private Button addFilesButton;
    private Button addFolderButton;
    private Button chooseDestButton;
    private Button useUsbButton;
    private Button startButton;
    private Button cancelButton;
    private View progressCard;
    private ProgressBar overallBar;
    private ProgressBar fileBar;
    private EditText feedInput;
    private Button podcastFeedButton;
    private Button audioLinkMusicButton;
    private Button audioLinkPodcastButton;
    private TextView overallText;
    private TextView currentFileText;
    private TextView countersText;
    private TextView speedText;
    private TextView logText;

    private List<UsbVolumes.Info> usbVolumes = Collections.emptyList();
    private long destFreeBytes = -1;
    private boolean busy;
    private boolean sawRunning;
    private String notifiedPhase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = AppState.get();
        manager = TransferManager.get(this);
        sourceRepo = new SourceRepo(this);
        buildUi();
        renderSelections();
        render(manager.snapshot());
        refreshUsb();
        ensureNotificationPermission();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        manager.addListener(this);
        registerMediaReceiver();
        renderSelections();
        render(manager.snapshot());
    }

    @Override
    protected void onStop() {
        manager.removeListener(this);
        unregisterMediaReceiver();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onTransferState(TransferManager.Snapshot snapshot) {
        render(snapshot);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    // ---------------------------------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.surface));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView title = label("RUIZU Sync", 25, true);
        title.setTextColor(getColor(R.color.text));
        root.addView(title);
        TextView subtitle = label("Copy music and podcasts to your RUIZU player", 14, false);
        subtitle.setTextColor(getColor(R.color.text_muted));
        root.addView(subtitle, margins(0, dp(2), 0, dp(12)));

        LinearLayout usbCard = card();
        usbStatus = label("", 13, false);
        usbStatus.setTextColor(getColor(R.color.text_muted));
        usbCard.addView(usbStatus);
        useUsbButton = secondaryButton("Use connected USB drive", v -> chooseDestinationFromUsb());
        usbCard.addView(useUsbButton, margins(0, dp(10), 0, 0));
        root.addView(usbCard);

        root.addView(sectionHeader("1 · Music source"));
        LinearLayout sourceCard = card();
        LinearLayout sourceButtons = new LinearLayout(this);
        sourceButtons.setOrientation(LinearLayout.HORIZONTAL);
        addFilesButton = secondaryButton("Add files", v -> pickFiles());
        addFolderButton = secondaryButton("Add folder", v -> pickSourceFolder());
        sourceButtons.addView(addFilesButton, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        folderParams.leftMargin = dp(8);
        sourceButtons.addView(addFolderButton, folderParams);
        sourceCard.addView(sourceButtons);
        sourceSummary = label("No files selected", 13, false);
        sourceSummary.setTextColor(getColor(R.color.text_muted));
        sourceCard.addView(sourceSummary, margins(0, dp(10), 0, 0));
        Button clearSources = linkButton("Clear selection", v -> {
            state.clearSources();
            renderSelections();
        });
        sourceCard.addView(clearSources, margins(0, dp(6), 0, 0));
        root.addView(sourceCard);

        root.addView(sectionHeader("2 · Player folder"));
        LinearLayout destCard = card();
        destSummary = label("No player folder chosen", 13, false);
        destSummary.setTextColor(getColor(R.color.text_muted));
        destCard.addView(destSummary);
        chooseDestButton = primaryButton("Choose player folder", v -> chooseDestination(null));
        destCard.addView(chooseDestButton, margins(0, dp(10), 0, 0));
        Button clearDest = linkButton("Clear player folder", v -> {
            state.clearDestination();
            destFreeBytes = -1;
            renderSelections();
        });
        destCard.addView(clearDest, margins(0, dp(6), 0, 0));
        root.addView(destCard);

        root.addView(sectionHeader("3 · Options"));
        LinearLayout optionsCard = card();
        optionsCard.addView(label("Folders on the player", 13, true));
        organiseSpinner = new Spinner(this);
        ArrayAdapter<FolderPlanner.OrganiseMode> organiseAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, FolderPlanner.OrganiseMode.values());
        organiseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        organiseSpinner.setAdapter(organiseAdapter);
        organiseSpinner.setSelection(state.organiseMode().ordinal());
        organiseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                state.setOrganiseMode(FolderPlanner.OrganiseMode.values()[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current value.
            }
        });
        optionsCard.addView(organiseSpinner, margins(0, dp(4), 0, dp(14)));

        optionsCard.addView(label("Duplicates", 13, true));
        duplicateSpinner = new Spinner(this);
        ArrayAdapter<DuplicateMode> duplicateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new DuplicateMode[]{DuplicateMode.SKIP_EXACT, DuplicateMode.KEEP_BOTH});
        duplicateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        duplicateSpinner.setAdapter(duplicateAdapter);
        duplicateSpinner.setSelection(state.duplicateMode() == DuplicateMode.KEEP_BOTH ? 1 : 0);
        duplicateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                state.setDuplicateMode(position == 1 ? DuplicateMode.KEEP_BOTH : DuplicateMode.SKIP_EXACT);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current value.
            }
        });
        optionsCard.addView(duplicateSpinner, margins(0, dp(4), 0, 0));
        root.addView(optionsCard);

        root.addView(sectionHeader("4 · Podcasts & audio links"));
        LinearLayout linkCard = card();
        TextView linkHelp = label(
                "Paste a public podcast RSS feed to pick episodes, or a direct HTTPS audio file link.",
                13, false);
        linkHelp.setTextColor(getColor(R.color.text_muted));
        linkCard.addView(linkHelp);
        feedInput = new EditText(this);
        feedInput.setHint("https://example.com/feed.xml or https://example.com/episode.mp3");
        feedInput.setSingleLine(true);
        feedInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        feedInput.setTextSize(13);
        linkCard.addView(feedInput, margins(0, dp(8), 0, dp(10)));
        podcastFeedButton = secondaryButton("Browse podcast feed", v -> browsePodcastFeed());
        linkCard.addView(podcastFeedButton, margins(0, 0, 0, dp(8)));
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        audioLinkMusicButton = secondaryButton("Link → Music", v -> addAudioLink("Music"));
        audioLinkPodcastButton = secondaryButton("Link → Podcasts", v -> addAudioLink("Podcasts"));
        linkRow.addView(audioLinkMusicButton, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams podcastParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        podcastParams.leftMargin = dp(8);
        linkRow.addView(audioLinkPodcastButton, podcastParams);
        linkCard.addView(linkRow);
        root.addView(linkCard);

        estimate = label("", 13, false);
        estimate.setTextColor(getColor(R.color.text_muted));
        root.addView(estimate, margins(0, dp(14), 0, dp(8)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        startButton = primaryButton("Start transfer", v -> startTransfer());
        cancelButton = secondaryButton("Cancel", v -> manager.cancel());
        cancelButton.setEnabled(false);
        actionRow.addView(startButton, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        cancelParams.leftMargin = dp(8);
        actionRow.addView(cancelButton, cancelParams);
        root.addView(actionRow);

        progressCard = buildProgressCard();
        progressCard.setVisibility(View.GONE);
        root.addView(progressCard, margins(0, dp(16), 0, 0));

        root.addView(sectionHeader("Library links"));
        LinearLayout libraryCard = card();
        LinearLayout libraryRowOne = new LinearLayout(this);
        libraryRowOne.setOrientation(LinearLayout.HORIZONTAL);
        Button joshBooks = secondaryButton("JoshBooks",
                v -> openUrl("https://josh-books-online.vercel.app/library"));
        Button drive = secondaryButton("Google Drive",
                v -> openUrl("https://drive.google.com/drive/u/0/my-drive"));
        libraryRowOne.addView(joshBooks, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams driveParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        driveParams.leftMargin = dp(8);
        libraryRowOne.addView(drive, driveParams);
        libraryCard.addView(libraryRowOne);
        LinearLayout libraryRowTwo = new LinearLayout(this);
        libraryRowTwo.setOrientation(LinearLayout.HORIZONTAL);
        Button spotify = secondaryButton("Spotify", v -> openUrl("https://open.spotify.com"));
        Button youtube = secondaryButton("YouTube",
                v -> openUrl("https://music.youtube.com"));
        libraryRowTwo.addView(spotify, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams youtubeParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        youtubeParams.leftMargin = dp(8);
        libraryRowTwo.addView(youtube, youtubeParams);
        libraryCard.addView(libraryRowTwo, margins(0, dp(8), 0, 0));
        TextView libraryNote = label(
                "Spotify and YouTube streams cannot be exported as audio files. Use Drive or an "
                        + "authorised podcast RSS / direct audio link instead.",
                12, false);
        libraryNote.setTextColor(getColor(R.color.text_muted));
        libraryCard.addView(libraryNote, margins(0, dp(8), 0, 0));
        root.addView(libraryCard);

        TextView supportedNote = label("Supported formats: " + MediaTypes.supportedList(), 12, false);
        supportedNote.setTextColor(getColor(R.color.text_muted));
        root.addView(supportedNote, margins(0, dp(18), 0, dp(4)));
        TextView drmNote = label(
                "DRM-protected files are skipped. This app never removes or circumvents DRM.",
                12, false);
        drmNote.setTextColor(getColor(R.color.text_muted));
        root.addView(drmNote, margins(0, 0, 0, dp(4)));
        TextView ejectNote = label(
                "Tip: let Android finish mounting the player before choosing the folder, and eject "
                        + "safely before unplugging the cable.",
                12, false);
        ejectNote.setTextColor(getColor(R.color.text_muted));
        root.addView(ejectNote);

        setContentView(scroll);
        if (android.os.Build.VERSION.SDK_INT >= 30) scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private View buildProgressCard() {
        LinearLayout card = card();

        overallText = label("Waiting", 15, true);
        overallText.setTextColor(getColor(R.color.text));
        card.addView(overallText);
        overallBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overallBar.setMax(100);
        overallBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.brand)));
        card.addView(overallBar, margins(0, dp(6), 0, dp(10)));

        currentFileText = label("", 13, false);
        currentFileText.setTextColor(getColor(R.color.text));
        card.addView(currentFileText);
        fileBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fileBar.setMax(100);
        fileBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.brand_light)));
        card.addView(fileBar, margins(0, dp(4), 0, dp(10)));

        countersText = label("", 12, false);
        countersText.setTextColor(getColor(R.color.text_muted));
        card.addView(countersText);

        speedText = label("", 12, false);
        speedText.setTextColor(getColor(R.color.text_muted));
        card.addView(speedText, margins(0, dp(2), 0, dp(10)));

        logText = label("", 11, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(getColor(R.color.text_muted));
        logText.setMaxLines(14);
        card.addView(logText);

        return card;
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private void renderSelections() {
        List<SourceItem> sources = state.sources();
        int supported = 0;
        int unsupported = 0;
        long knownBytes = 0;
        for (SourceItem item : sources) {
            if (item.supportedFormat()) {
                supported++;
            } else {
                unsupported++;
            }
            if (item.size > 0) {
                knownBytes += item.size;
            }
        }

        if (sources.isEmpty()) {
            sourceSummary.setText("No files selected");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(sources.size()).append(sources.size() == 1 ? " file selected" : " files selected");
            if (knownBytes > 0) {
                sb.append(" · ").append(ByteFormat.bytes(knownBytes));
            }
            if (unsupported > 0) {
                sb.append("\n").append(unsupported).append(unsupported == 1 ? " file" : " files")
                        .append(" in an unsupported format will be skipped");
            }
            sb.append('\n');
            int shown = 0;
            for (SourceItem item : sources) {
                if (shown >= 3) {
                    sb.append("… +").append(sources.size() - 3).append(" more");
                    break;
                }
                if (shown > 0) {
                    sb.append('\n');
                }
                sb.append("• ").append(item.displayName);
                shown++;
            }
            sourceSummary.setText(sb.toString());
        }

        Uri dest = state.destinationTree();
        if (dest == null) {
            destSummary.setText("No player folder chosen");
        } else {
            StringBuilder sb = new StringBuilder();
            String label = state.destinationLabel();
            sb.append(label.isEmpty() ? "Player folder selected" : label);
            if (destFreeBytes > 0) {
                sb.append("\nFree space: ").append(ByteFormat.bytes(destFreeBytes));
            }
            sb.append("\n").append(dest.toString());
            destSummary.setText(sb.toString());
        }

        if (sources.isEmpty()) {
            estimate.setText("Select music or podcasts to begin");
        } else if (dest == null) {
            estimate.setText(ByteFormat.bytes(Math.max(knownBytes, 0)) + " selected · choose the player folder");
        } else if (manager.isRunning()) {
            estimate.setText(supported + " of " + sources.size() + " files queued");
        } else {
            estimate.setText(supported + " of " + sources.size() + " files ready to copy · "
                    + ByteFormat.bytes(Math.max(knownBytes, 0)));
        }

        boolean running = manager.isRunning();
        startButton.setEnabled(!running && !busy && !sources.isEmpty() && dest != null);
        cancelButton.setEnabled(running);
        addFilesButton.setEnabled(!busy);
        addFolderButton.setEnabled(!busy);
        chooseDestButton.setEnabled(!busy);
    }

    private void render(TransferManager.Snapshot snapshot) {
        if (snapshot == null || overallBar == null) {
            return;
        }
        boolean active = !TransferManager.PHASE_IDLE.equals(snapshot.phase);
        progressCard.setVisibility(active ? View.VISIBLE : View.GONE);

        if (active) {
            int percent = (int) Math.round(snapshot.fraction * 100);
            overallBar.setProgress(Math.max(0, Math.min(100, percent)));
            overallText.setText(percent + "% · " + snapshot.completedFiles + "/" + snapshot.totalFiles + " files");

            if (snapshot.running && snapshot.currentFile != null && !snapshot.currentFile.isEmpty()) {
                currentFileText.setText(snapshot.currentFile);
                if (snapshot.currentTotal > 0) {
                    fileBar.setVisibility(View.VISIBLE);
                    fileBar.setIndeterminate(false);
                    fileBar.setProgress((int) Math.round(snapshot.currentFraction * 100));
                } else {
                    fileBar.setVisibility(View.VISIBLE);
                    fileBar.setIndeterminate(true);
                }
            } else if (snapshot.running) {
                currentFileText.setText(snapshot.status);
                fileBar.setVisibility(View.VISIBLE);
                fileBar.setIndeterminate(true);
            } else {
                currentFileText.setText(snapshot.status);
                fileBar.setVisibility(View.GONE);
            }

            StringBuilder counters = new StringBuilder();
            counters.append(ByteFormat.bytes(snapshot.doneBytes));
            if (snapshot.totalBytes > 0) {
                counters.append(" of ").append(ByteFormat.bytes(snapshot.totalBytes));
            }
            counters.append(" copied");
            if (snapshot.duplicates > 0) {
                counters.append(" · ").append(snapshot.duplicates).append(" duplicate")
                        .append(snapshot.duplicates == 1 ? "" : "s").append(" skipped");
            }
            if (snapshot.unsupported > 0) {
                counters.append(" · ").append(snapshot.unsupported).append(" unsupported");
            }
            if (snapshot.drmBlocked > 0) {
                counters.append(" · ").append(snapshot.drmBlocked).append(" DRM");
            }
            if (snapshot.failed > 0) {
                counters.append(" · ").append(snapshot.failed).append(" failed");
            }
            countersText.setText(counters.toString());

            StringBuilder speed = new StringBuilder();
            if (snapshot.running && TransferManager.PHASE_SCANNING.equals(snapshot.phase)) {
                speedText.setText(snapshot.status);
            } else if (snapshot.running) {
                if (snapshot.bytesPerSecond > 0) {
                    speed.append(ByteFormat.speed(snapshot.bytesPerSecond));
                }
                if (snapshot.etaSeconds >= 0) {
                    if (speed.length() > 0) {
                        speed.append(" · ");
                    }
                    speed.append("about ").append(ByteFormat.duration(snapshot.etaSeconds)).append(" left");
                }
                speedText.setText(speed.length() == 0 ? "Starting…" : speed.toString());
            } else {
                String status = snapshot.status == null ? "" : snapshot.status;
                if (snapshot.error != null && !snapshot.error.trim().isEmpty()) {
                    status = status + "\nError: " + snapshot.error;
                }
                speedText.setText(status);
            }

            logText.setText(joinTail(snapshot.log, 14));
        }

        if (snapshot.running) {
            sawRunning = true;
        }
        if (snapshot.isFinished() && sawRunning && !snapshot.phase.equals(notifiedPhase)) {
            notifiedPhase = snapshot.phase;
            sawRunning = false;
            if (!TransferManager.PHASE_CANCELLED.equals(snapshot.phase)) {
                toast(snapshot.status == null ? "Transfer finished" : snapshot.status);
            }
        }

        boolean running = manager.isRunning();
        startButton.setEnabled(!running && !busy && !state.sources().isEmpty() && state.destinationTree() != null);
        cancelButton.setEnabled(running);
    }

    // ---------------------------------------------------------------------------------------
    // Source picking
    // ---------------------------------------------------------------------------------------

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, MediaTypes.pickerMimeTypes());
        try {
            startActivityForResult(intent, REQ_ADD_FILES);
        } catch (ActivityNotFoundException e) {
            toast("No file picker is available on this device");
        }
    }

    private void pickSourceFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_ADD_FOLDER);
        } catch (ActivityNotFoundException e) {
            toast("No folder picker is available on this device");
        }
    }

    private void chooseDestinationFromUsb() {
        if (usbVolumes.isEmpty()) {
            toast("No USB storage detected — connect the player first");
            return;
        }
        chooseDestination(usbVolumes.get(0).volume);
    }

    private void chooseDestination(StorageVolume volume) {
        Intent intent = volume == null ? null : UsbVolumes.openTreeIntent(volume);
        if (intent == null) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_DEST_TREE);
        } catch (ActivityNotFoundException e) {
            toast("No folder picker is available on this device");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_ADD_FILES) {
            addPickedFiles(collectUris(data));
        } else if (requestCode == REQ_ADD_FOLDER) {
            Uri tree = data.getData();
            if (tree != null) {
                takePersistable(tree, true);
                scanSourceFolder(tree);
            }
        } else if (requestCode == REQ_DEST_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                takePersistable(tree, false);
                state.setDestination(tree, "");
                destFreeBytes = -1;
                refreshDestination(tree);
                renderSelections();
            }
        }
    }

    private List<Uri> collectUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void addPickedFiles(List<Uri> uris) {
        if (uris.isEmpty() || isFinishing()) {
            return;
        }
        setBusy(true, "Reading selected files…");
        for (Uri uri : uris) {
            takePersistable(uri, true);
        }
        ioExecutor.execute(() -> {
            List<SourceItem> items = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    items.add(sourceRepo.describe(uri));
                } catch (Throwable ignored) {
                    items.add(new SourceItem(uri, "Unreadable file", -1, "", "", null,
                            "Could not read this file"));
                }
            }
            mainHandler.post(() -> {
                if (isFinishing()) {
                    return;
                }
                state.addSources(items);
                setBusy(false, null);
                renderSelections();
                toast(items.size() + (items.size() == 1 ? " file added" : " files added"));
            });
        });
    }

    private void scanSourceFolder(Uri treeUri) {
        setBusy(true, "Scanning the folder…");
        final Context appContext = getApplicationContext();
        ioExecutor.execute(() -> {
            try {
                SourceRepo repo = new SourceRepo(appContext);
                SourceRepo.Listing listing = repo.scanTree(treeUri);
                mainHandler.post(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    state.addSourceTree(treeUri);
                    state.addSources(listing.items);
                    setBusy(false, null);
                    renderSelections();
                    StringBuilder message = new StringBuilder();
                    message.append(listing.items.size())
                            .append(listing.items.size() == 1 ? " track added from " : " tracks added from ")
                            .append(listing.treeName);
                    if (listing.ignoredNonAudio > 0) {
                        message.append(" (").append(listing.ignoredNonAudio)
                                .append(" non-audio file").append(listing.ignoredNonAudio == 1 ? "" : "s")
                                .append(" ignored)");
                    }
                    if (listing.truncated) {
                        message.append(" — stopped at 5000 files");
                    }
                    toast(message.toString());
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    setBusy(false, null);
                    toast("Could not read that folder: " + describe(t));
                });
            }
        });
    }

    private void refreshDestination(Uri treeUri) {
        final Context appContext = getApplicationContext();
        ioExecutor.execute(() -> {
            String label = "";
            long free = -1;
            Throwable error = null;
            try {
                SourceRepo repo = new SourceRepo(appContext);
                label = repo.treeDisplayName(treeUri);
                DestRepo destRepo = new DestRepo(appContext, treeUri);
                free = destRepo.availableBytes();
                if (!destRepo.rootName().isEmpty()) {
                    label = destRepo.rootName();
                }
            } catch (Throwable t) {
                error = t;
            }
            final String finalLabel = label;
            final long finalFree = free;
            final Throwable finalError = error;
            mainHandler.post(() -> {
                if (isFinishing()) {
                    return;
                }
                if (finalError != null) {
                    destFreeBytes = -1;
                    state.clearDestination();
                    renderSelections();
                    toast("Cannot use that folder: " + describe(finalError));
                    return;
                }
                state.setDestination(treeUri, finalLabel);
                destFreeBytes = finalFree;
                renderSelections();
            });
        });
    }

    private void browsePodcastFeed() {
        String link = feedInputUrl();
        if (link == null) {
            return;
        }
        setBusy(true, "Loading podcast feed…");
        ioExecutor.execute(() -> {
            try {
                String xml = RemoteAudio.fetchText(link, RemoteAudio.MAX_FEED_BYTES);
                final List<FeedScanner.Episode> episodes = FeedScanner.scan(xml);
                mainHandler.post(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    setBusy(false, null);
                    if (episodes.isEmpty()) {
                        toast("No downloadable audio episodes in that feed");
                        return;
                    }
                    showEpisodePicker(episodes);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    setBusy(false, null);
                    toast("Feed failed: " + describe(t));
                });
            }
        });
    }

    private void showEpisodePicker(List<FeedScanner.Episode> episodes) {
        int count = Math.min(episodes.size(), 50);
        String[] titles = new String[count];
        final boolean[] selected = new boolean[count];
        for (int i = 0; i < count; i++) {
            titles[i] = episodes.get(i).title;
        }
        new AlertDialog.Builder(this)
                .setTitle("Select podcast episodes")
                .setMultiChoiceItems(titles, selected, (dialog, which, checked) -> selected[which] = checked)
                .setPositiveButton("Add selected", (dialog, which) -> {
                    List<SourceItem> items = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        if (!selected[i]) {
                            continue;
                        }
                        FeedScanner.Episode episode = episodes.get(i);
                        items.add(sourceRepo.describeRemote(Uri.parse(episode.audioUrl), "Podcasts",
                                episode.title, episode.size, episode.mimeType));
                    }
                    if (items.isEmpty()) {
                        toast("No episodes selected");
                        return;
                    }
                    state.addSources(items);
                    renderSelections();
                    toast(items.size() + (items.size() == 1 ? " episode added" : " episodes added"));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addAudioLink(String category) {
        String link = feedInputUrl();
        if (link == null) {
            return;
        }
        if (TransferUtils.isStreamingLink(link)) {
            toast("Spotify and YouTube streams cannot be copied as audio files");
            return;
        }
        setBusy(true, "Checking audio link…");
        ioExecutor.execute(() -> {
            final SourceItem item = sourceRepo.describe(Uri.parse(link), category);
            mainHandler.post(() -> {
                if (isFinishing()) {
                    return;
                }
                setBusy(false, null);
                if (item.problem != null) {
                    toast(item.problem);
                    return;
                }
                if (!item.supportedFormat()) {
                    toast("That link does not look like a supported audio file");
                    return;
                }
                state.addSource(item);
                renderSelections();
                toast("Audio link added to " + category);
            });
        });
    }

    private String feedInputUrl() {
        if (feedInput == null) {
            return null;
        }
        String value = feedInput.getText().toString().trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith("https://")) {
            toast("Paste an HTTPS podcast feed or audio link first");
            return null;
        }
        return value;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("No app can open that link");
        }
    }

    private void startTransfer() {
        List<SourceItem> sources = state.sources();
        if (sources.isEmpty()) {
            toast("Add some music or podcasts first");
            return;
        }
        Uri dest = state.destinationTree();
        if (dest == null) {
            toast("Choose the player folder first");
            return;
        }
        for (Uri sourceTree : state.sourceTrees()) {
            if (sourceTree.equals(dest)) {
                toast("The player folder is also a source folder — choose a different target");
                return;
            }
        }
        long knownBytes = 0;
        for (SourceItem item : sources) {
            if (item.size > 0) {
                knownBytes += item.size;
            }
        }
        if (destFreeBytes > 0 && knownBytes > destFreeBytes) {
            new AlertDialog.Builder(this)
                    .setTitle("The player may be full")
                    .setMessage("You selected " + ByteFormat.bytes(knownBytes)
                            + " but the player folder reports " + ByteFormat.bytes(destFreeBytes)
                            + " free. Continue anyway?")
                    .setPositiveButton("Continue", (dialog, which) -> beginTransfer(sources, dest))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        beginTransfer(sources, dest);
    }

    private void beginTransfer(List<SourceItem> sources, Uri dest) {
        TransferManager.Request request = new TransferManager.Request(
                sources, dest, state.organiseMode(), state.duplicateMode());
        try {
            manager.start(request);
        } catch (RuntimeException e) {
            toast(describe(e));
            return;
        }
        TransferService.start(this, sources);
        sawRunning = true;
        notifiedPhase = null;
        render(manager.snapshot());
    }

    // ---------------------------------------------------------------------------------------
    // USB detection
    // ---------------------------------------------------------------------------------------

    private void refreshUsb() {
        usbVolumes = UsbVolumes.detect(this);
        if (usbStatus == null) {
            return;
        }
        if (usbVolumes.isEmpty()) {
            usbStatus.setText("No USB storage mounted yet. Connect the player with its cable, unlock "
                    + "the phone, wait for Android to mount it, then choose the player folder. If the "
                    + "player only offers MTP, Android cannot expose it as storage — see the notes.");
            useUsbButton.setEnabled(false);
        } else {
            StringBuilder sb = new StringBuilder("USB storage detected: ");
            for (int i = 0; i < usbVolumes.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(usbVolumes.get(i).label);
            }
            sb.append(". Use it as the player folder.");
            usbStatus.setText(sb.toString());
            useUsbButton.setEnabled(true);
        }
    }

    private void registerMediaReceiver() {
        if (mediaReceiver != null) {
            return;
        }
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshUsb();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mediaReceiver, filter);
            }
        } catch (Throwable ignored) {
            mediaReceiver = null;
        }
    }

    private void unregisterMediaReceiver() {
        if (mediaReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(mediaReceiver);
        } catch (Throwable ignored) {
            // Already unregistered.
        }
        mediaReceiver = null;
    }

    // ---------------------------------------------------------------------------------------
    // Permissions, share intents, helpers
    // ---------------------------------------------------------------------------------------

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        } catch (Throwable ignored) {
            // Progress is still visible in the app.
        }
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && feedInput != null) {
                feedInput.setText(shared.trim());
                toast("Link pasted — browse the feed or add it as an audio link");
            }
            return;
        }
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                uris.add(uri);
            }
        } else {
            ArrayList<Uri> shared = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) {
                uris.addAll(shared);
            }
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (!uris.isEmpty()) {
            addPickedFiles(uris);
        }
    }

    private void takePersistable(Uri uri, boolean readOnly) {
        int mode = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (!readOnly) {
            mode |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, mode);
        } catch (Throwable ignored) {
            // Session access still works while the app is alive.
        }
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (estimate != null) {
            if (value && message != null) {
                estimate.setText(message);
            }
        }
        if (addFilesButton != null) {
            addFilesButton.setEnabled(!value);
            addFolderButton.setEnabled(!value);
            chooseDestButton.setEnabled(!value);
            if (podcastFeedButton != null) {
                podcastFeedButton.setEnabled(!value);
                audioLinkMusicButton.setEnabled(!value);
                audioLinkPodcastButton.setEnabled(!value);
                feedInput.setEnabled(!value);
            }
        }
        if (!value) {
            renderSelections();
        }
    }

    private static String joinTail(List<String> lines, int max) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int from = Math.max(0, lines.size() - max);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    private void toast(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---- small view helpers ---------------------------------------------------------------

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setTextColor(getColor(R.color.text));
        return view;
    }

    private TextView sectionHeader(String text) {
        TextView view = label(text, 13, true);
        view.setTextColor(getColor(R.color.brand_light));
        view.setAllCaps(true);
        view.setLetterSpacing(0.08f);
        return view;
    }

    private Button primaryButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(getColor(R.color.on_brand));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand)));
        button.setOnClickListener(listener);
        button.setMinHeight(dp(44));
        return button;
    }

    private Button secondaryButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(getColor(R.color.text));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.card_alt)));
        button.setOnClickListener(listener);
        button.setMinHeight(dp(44));
        return button;
    }

    private Button linkButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(getColor(R.color.brand_light));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.card)));
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(dp(36));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.card));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), getColor(R.color.stroke));
        layout.setBackground(background);
        layout.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.topMargin = dp(8);
        layout.setLayoutParams(params);
        return layout;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = right;
        params.bottomMargin = bottom;
        return params;
    }
}
