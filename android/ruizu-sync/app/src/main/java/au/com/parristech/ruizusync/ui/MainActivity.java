package au.com.parristech.ruizusync.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;
import android.widget.Toast;

import au.com.parristech.ruizusync.AppState;
import au.com.parristech.ruizusync.audio.ConversionSelfTest;
import au.com.parristech.ruizusync.HistoryStore;
import au.com.parristech.ruizusync.PlaylistStore;
import au.com.parristech.ruizusync.SyncPlanStore;
import au.com.parristech.ruizusync.SubscriptionStore;
import au.com.parristech.ruizusync.PendingTransferStore;
import au.com.parristech.ruizusync.PlayerFolderStore;
import au.com.parristech.ruizusync.PlayerMarkerStore;
import au.com.parristech.ruizusync.R;
import au.com.parristech.ruizusync.TransferUtils;
import au.com.parristech.ruizusync.TransferManager;
import au.com.parristech.ruizusync.TransferService;
import au.com.parristech.ruizusync.core.ByteFormat;
import au.com.parristech.ruizusync.core.PlayerBinding;
import au.com.parristech.ruizusync.core.SourceBuckets;
import au.com.parristech.ruizusync.core.DestEntry;
import au.com.parristech.ruizusync.core.LibraryComparison;
import au.com.parristech.ruizusync.core.DuplicateMode;
import au.com.parristech.ruizusync.core.FolderPlanner;
import au.com.parristech.ruizusync.core.FileNameSanitizer;
import au.com.parristech.ruizusync.core.MediaTypes;
import au.com.parristech.ruizusync.core.Opml;
import au.com.parristech.ruizusync.io.DestRepo;
import au.com.parristech.ruizusync.io.SourceRepo;
import au.com.parristech.ruizusync.model.SourceItem;
import au.com.parristech.ruizusync.net.FeedScanner;
import au.com.parristech.ruizusync.net.RemoteAudio;
import au.com.parristech.ruizusync.net.DownloadResumePolicy;
import au.com.parristech.ruizusync.usb.UsbVolumes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int REQ_IMPORT_OPML = 1004;
    private static final int REQ_EXPORT_OPML = 1005;
    private static final int REQ_ADD_AMV = 1006;
    private static final int REQ_NOTIFICATIONS = 2001;
    private static final int MAX_AUTO_DOWNLOADS = 5;
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
    private CheckBox scopeCheck;
    private CheckBox playlistCheck;
    private CheckBox convertCheck;
    private Button retryButton;
    private Button copyLogButton;
    private Button auditButton;
    private Button safelyRemoveButton;
    private TextView lastRunText;
    private ScrollView contentScroll;
    private EditText playlistNameInput;
    private Spinner playlistSpinner;
    private ArrayAdapter<String> playlistAdapter;
    private TextView subscriptionStatus;
    private TextView planStatus;
    private CheckBox planEnabledCheck;
    private boolean planOffered;

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
        syncOptionViews();
        refreshPlaylistSpinner();
        refreshSubscriptionStatus();
        refreshPlanStatus();
        updateLastRunText();
        renderSelections();
        render(manager.snapshot());
        refreshUsb();
        ensureNotificationPermission();
        handleShareIntent(getIntent());
        maybeOfferResume();
        maybeRunSelfTest(getIntent());
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
        maybeRunSelfTest(intent);
    }

    // ---------------------------------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        contentScroll = scroll;
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
        sourceCard.addView(secondaryButton("Add converted AMV movie", v -> pickConvertedMovie()),
                margins(0, dp(8), 0, 0));
        TextView videoHelp = label("Movies: select an AMV converted to 160×128 / 15 fps. "
                + "RUIZU Sync will safely copy it into VIDEO on the player. "
                + "For a film in Google Drive, use the RUIZU Movie phone shortcut to convert it first.",
                12, false);
        videoHelp.setTextColor(getColor(R.color.text_muted));
        sourceCard.addView(videoHelp, margins(0, dp(6), 0, 0));
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
        // Only supported modes are offered: overwriting player audio is never allowed, so
        // OVERWRITE stays out of the list and any legacy saved value is migrated by AppState.
        final java.util.List<DuplicateMode> duplicateModes = DuplicateMode.selectable();
        ArrayAdapter<DuplicateMode> duplicateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, duplicateModes);
        duplicateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        duplicateSpinner.setAdapter(duplicateAdapter);
        duplicateSpinner.setSelection(Math.max(0, duplicateModes.indexOf(state.duplicateMode())));
        duplicateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                state.setDuplicateMode(duplicateModes.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current value.
            }
        });
        optionsCard.addView(duplicateSpinner, margins(0, dp(4), 0, dp(14)));

        // Every copy is verified unconditionally (stage, read back, compare SHA-256), so there
        // is no option to offer here. A checkbox that changed nothing was misleading.
        TextView verifyNote = new TextView(this);
        verifyNote.setText("Every copy is verified with SHA-256 before it is published.");
        verifyNote.setTextSize(12);
        verifyNote.setTextColor(getColor(R.color.text_muted));
        optionsCard.addView(verifyNote, margins(0, 0, 0, dp(14)));
        scopeCheck = choice("Only match duplicates inside the destination folder",
                state.duplicatesInTargetFolderOnly(),
                (button, checked) -> state.setDuplicatesInTargetFolderOnly(checked));
        optionsCard.addView(scopeCheck);
        playlistCheck = choice("Write M3U playlists for each folder", state.writePlaylists(),
                (button, checked) -> state.setWritePlaylists(checked));
        optionsCard.addView(playlistCheck);
        convertCheck = choice("Convert non-MP3 audio to MP3 (originals untouched)",
                state.convertToMp3(), (button, checked) -> state.setConvertToMp3(checked));
        optionsCard.addView(convertCheck);
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

        root.addView(sectionHeader("5 · Playlists"));
        LinearLayout playlistCard = card();
        playlistNameInput = new EditText(this);
        playlistNameInput.setHint("Playlist name (e.g. Road Trip)");
        playlistNameInput.setSingleLine(true);
        playlistNameInput.setTextSize(13);
        playlistCard.addView(playlistNameInput, margins(0, 0, 0, dp(8)));
        LinearLayout playlistSaveRow = new LinearLayout(this);
        playlistSaveRow.setOrientation(LinearLayout.HORIZONTAL);
        Button savePlaylist = secondaryButton("Save selection", v -> savePlaylist());
        Button exportPlaylist = secondaryButton("Export selected", v -> exportPlaylist());
        playlistSaveRow.addView(savePlaylist, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        exportParams.leftMargin = dp(8);
        playlistSaveRow.addView(exportPlaylist, exportParams);
        playlistCard.addView(playlistSaveRow);
        playlistSpinner = new Spinner(this);
        playlistAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new ArrayList<String>());
        playlistAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playlistSpinner.setAdapter(playlistAdapter);
        playlistCard.addView(playlistSpinner, margins(0, dp(10), 0, dp(8)));
        LinearLayout playlistRow = new LinearLayout(this);
        playlistRow.setOrientation(LinearLayout.HORIZONTAL);
        Button loadPlaylist = secondaryButton("Load queue", v -> loadPlaylist());
        Button deletePlaylist = secondaryButton("Delete", v -> deletePlaylist());
        playlistRow.addView(loadPlaylist, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        deleteParams.leftMargin = dp(8);
        playlistRow.addView(deletePlaylist, deleteParams);
        playlistCard.addView(playlistRow);
        root.addView(playlistCard);

        root.addView(sectionHeader("6 · Subscriptions"));
        LinearLayout subscriptionCard = card();
        TextView subscriptionHelp = label(
                "Save the feed URL above, then download new episodes to the phone in advance.",
                13, false);
        subscriptionHelp.setTextColor(getColor(R.color.text_muted));
        subscriptionCard.addView(subscriptionHelp);
        subscriptionStatus = label("", 12, false);
        subscriptionStatus.setTextColor(getColor(R.color.text_muted));
        subscriptionCard.addView(subscriptionStatus, margins(0, dp(6), 0, dp(8)));
        LinearLayout subscriptionRow = new LinearLayout(this);
        subscriptionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button subscribe = secondaryButton("Subscribe", v -> subscribeToFeed());
        Button refreshSubscriptions = secondaryButton("Download new", v -> refreshSubscriptions());
        subscriptionRow.addView(subscribe, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        refreshParams.leftMargin = dp(8);
        subscriptionRow.addView(refreshSubscriptions, refreshParams);
        subscriptionCard.addView(subscriptionRow, margins(0, 0, 0, dp(8)));
        LinearLayout opmlRow = new LinearLayout(this);
        opmlRow.setOrientation(LinearLayout.HORIZONTAL);
        Button importOpml = secondaryButton("Import OPML", v -> importOpml());
        Button exportOpml = secondaryButton("Export OPML", v -> exportOpml());
        opmlRow.addView(importOpml, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams opmlParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        opmlParams.leftMargin = dp(8);
        opmlRow.addView(exportOpml, opmlParams);
        subscriptionCard.addView(opmlRow);
        root.addView(subscriptionCard);

        root.addView(sectionHeader("7 · Saved plan"));
        LinearLayout planCard = card();
        planStatus = label("", 12, false);
        planStatus.setTextColor(getColor(R.color.text_muted));
        planCard.addView(planStatus);
        LinearLayout planRow = new LinearLayout(this);
        planRow.setOrientation(LinearLayout.HORIZONTAL);
        Button savePlan = secondaryButton("Save setup", v -> savePlan());
        Button runPlan = secondaryButton("Run plan", v -> runPlan());
        planRow.addView(savePlan, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        runParams.leftMargin = dp(8);
        planRow.addView(runPlan, runParams);
        planCard.addView(planRow, margins(0, dp(8), 0, 0));
        planEnabledCheck = choice("Offer this plan when the player connects", false,
                (button, checked) -> {
                    SyncPlanStore.setEnabled(this, checked);
                    refreshPlanStatus();
                });
        planCard.addView(planEnabledCheck, margins(0, dp(6), 0, 0));
        root.addView(planCard);

        root.addView(sectionHeader("8 · What's on my RUIZU?"));
        LinearLayout auditCard = card();
        TextView auditHelp = label(
                "Scan every audio file on the selected player folder. Add music or podcasts from "
                + "your phone or Google Drive above to see what's missing. The scan is read-only.",
                13, false);
        auditHelp.setTextColor(getColor(R.color.text_muted));
        auditCard.addView(auditHelp);
        auditButton = primaryButton("Scan player & compare library", v -> auditPlayerLibrary());
        auditCard.addView(auditButton, margins(0, dp(8), 0, 0));
        safelyRemoveButton = secondaryButton("Safely remove RUIZU", v -> showSafeRemovalGuide());
        auditCard.addView(safelyRemoveButton, margins(0, dp(8), 0, 0));
        TextView auditNote = label(
                "For a complete inventory, choose USB storage 1 at its root, rather than just "
                + "Music or Podcasts. Possible matches are kept separate from confirmed copies.",
                12, false);
        auditNote.setTextColor(getColor(R.color.text_muted));
        auditCard.addView(auditNote, margins(0, dp(6), 0, 0));
        root.addView(auditCard);

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

        LinearLayout lastRunCard = card();
        lastRunText = label("No transfers yet", 12, false);
        lastRunText.setTextColor(getColor(R.color.text_muted));
        lastRunCard.addView(lastRunText);
        LinearLayout lastRunButtons = new LinearLayout(this);
        lastRunButtons.setOrientation(LinearLayout.HORIZONTAL);
        retryButton = secondaryButton("Retry failed", v -> retryFailed());
        retryButton.setEnabled(false);
        copyLogButton = secondaryButton("Copy log", v -> copyLog());
        lastRunButtons.addView(retryButton, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(8);
        lastRunButtons.addView(copyLogButton, copyParams);
        lastRunCard.addView(lastRunButtons, margins(0, dp(8), 0, 0));
        root.addView(lastRunCard);

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

        applyWindowInsets(scroll);
        setContentView(scroll);
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

    private boolean hasReadySources() {
        for (SourceItem item : state.sources()) {
            if (SourceBuckets.of(item.supportedFormat(), item.problem) == SourceBuckets.Bucket.SUPPORTED) return true;
        }
        return false;
    }

    private void renderSelections() {
        List<SourceItem> sources = state.sources();
        int supported = 0;
        int unsupported = 0;
        int unreadable = 0;
        int rebuilt = 0;
        long knownBytes = 0;
        for (SourceItem item : sources) {
            switch (SourceBuckets.of(item.supportedFormat(), item.problem)) {
                case SUPPORTED:
                    supported++;
                    break;
                case UNREADABLE:
                    // The file itself may be a perfectly good MP3; we simply could not read what
                    // it is. Reporting that as an "unsupported format" was the confusing part.
                    unreadable++;
                    break;
                default:
                    unsupported++;
                    break;
            }
            if (item.nameReconstructed) {
                rebuilt++;
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
            if (unreadable > 0) {
                sb.append("\n").append(unreadable).append(" could not be prepared and will be skipped");
            }
            if (rebuilt > 0) {
                sb.append("\n").append(rebuilt).append(rebuilt == 1 ? " file arrived" : " files arrived")
                        .append(" without a file name; showing names based on the audio type");
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
                if (item.problem != null) sb.append(" — ").append(item.problem);
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
            String deviceId = state.destinationDeviceId();
            if (deviceId == null) {
                sb.append("\nPlayer: unidentified");
            } else {
                String deviceName = state.destinationDeviceName();
                sb.append("\nPlayer: ").append(deviceName == null ? "Player" : deviceName)
                        .append(" (").append(shortDeviceId(deviceId)).append(")");
            }
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
        startButton.setEnabled(!running && !busy && hasReadySources() && dest != null);
        cancelButton.setEnabled(running);
        addFilesButton.setEnabled(!busy);
        addFolderButton.setEnabled(!busy);
        chooseDestButton.setEnabled(!busy);
        if (auditButton != null) auditButton.setEnabled(!busy && !running && dest != null);
        if (safelyRemoveButton != null) safelyRemoveButton.setEnabled(!busy);
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
            if (snapshot.retried > 0) {
                counters.append(" · ").append(snapshot.retried).append(" retried");
            }
            if (snapshot.playlists > 0) {
                counters.append(" · ").append(snapshot.playlists).append(" playlist")
                        .append(snapshot.playlists == 1 ? "" : "s");
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
        startButton.setEnabled(!running && !busy && hasReadySources() && state.destinationTree() != null);
        cancelButton.setEnabled(running);
        if (retryButton != null) {
            retryButton.setEnabled(!running && snapshot.isFinished() && !manager.failedSources().isEmpty());
        }
        if (snapshot.isFinished()) {
            updateLastRunText();
        }
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

    private void pickConvertedMovie() {
        if (manager.isRunning() || busy) {
            toast("Finish the current transfer first");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"video/x-amv", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_ADD_AMV);
        } catch (ActivityNotFoundException e) {
            toast("No file picker available");
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
        } else if (requestCode == REQ_ADD_AMV) {
            // The picker MIME type is a hint only; SourceItem + MediaTypes validates
            // the actual filename, rejecting unconverted MP4/MKV selections.
            addPickedFiles(collectUris(data));
        } else if (requestCode == REQ_ADD_FOLDER) {
            Uri tree = data.getData();
            if (tree != null) {
                takePersistable(tree, true);
                scanSourceFolder(tree);
            }
        } else if (requestCode == REQ_IMPORT_OPML) {
            if (data.getData() != null) {
                importOpmlFrom(data.getData());
            }
        } else if (requestCode == REQ_EXPORT_OPML) {
            if (data.getData() != null) {
                writeOpmlTo(data.getData());
            }
        } else if (requestCode == REQ_DEST_TREE) {
            Uri tree = data.getData();
            if (tree != null) {
                takePersistable(tree, false);
                state.setDestination(tree, "");
                destFreeBytes = -1;
                // Remembering happens in refreshDestination once the folder has been proven
                // usable and its player identity read; a folder that fails that check must not
                // become "the saved player folder".
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
            PlayerMarkerStore.Reading identity = PlayerMarkerStore.Reading.unidentified();
            try {
                SourceRepo repo = new SourceRepo(appContext);
                label = repo.treeDisplayName(treeUri);
                DestRepo destRepo = new DestRepo(appContext, treeUri);
                free = destRepo.availableBytes();
                if (!destRepo.rootName().isEmpty()) {
                    label = destRepo.rootName();
                }
                identity = PlayerMarkerStore.ensure(appContext, treeUri, label);
            } catch (Throwable t) {
                error = t;
            }
            final String finalLabel = label;
            final long finalFree = free;
            final Throwable finalError = error;
            final PlayerMarkerStore.Reading finalIdentity = identity;
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
                state.setDestination(treeUri, finalLabel, finalIdentity.deviceId(),
                        finalIdentity.deviceName());
                PlayerFolderStore.remember(this, treeUri, finalLabel, finalIdentity.deviceId());
                destFreeBytes = finalFree;
                renderSelections();
                if (!finalIdentity.identified()) {
                    // Only the failure needs saying out loud; a successful identification is
                    // already visible above the transfer controls.
                    toast(describeUnidentified(finalIdentity));
                }
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
                final List<FeedScanner.Episode> episodes =
                        RemoteAudio.fetchFeedEpisodes(link, RemoteAudio.MAX_FEED_CHARS);
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
            toast("Add music, podcasts or a converted AMV first");
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
                sources, dest, state.organiseMode(), state.duplicateMode(),
                state.duplicatesInTargetFolderOnly(),
                state.writePlaylists(), "", null, state.convertToMp3(), null);
        PlayerFolderStore.remember(this, dest, state.destinationLabel(), state.destinationDeviceId());
        try {
            manager.start(request);
        } catch (RuntimeException e) {
            toast(describe(e));
            return;
        }
        TransferService.start(this);
        sawRunning = true;
        notifiedPhase = null;
        render(manager.snapshot());
    }


    // ---------------------------------------------------------------------------------------
    // Read-only RUIZU library inventory and Drive/phone missing-track comparison
    // ---------------------------------------------------------------------------------------

    private void auditPlayerLibrary() {
        if (busy || manager.isRunning()) {
            toast("Finish the current operation before scanning the player");
            return;
        }
        final Uri dest = state.destinationTree();
        if (dest == null) {
            toast("Choose the RUIZU player folder first");
            return;
        }
        final List<SourceItem> selections = state.sources();
        setBusy(true, "Reading the player library…");
        renderSelections();
        ioExecutor.execute(() -> {
            try {
                DestRepo repo = new DestRepo(getApplicationContext(), dest);
                List<DestEntry> player = new ArrayList<>();
                for (DestRepo.Node node : repo.walkFiles(60000)) {
                    player.add(new DestEntry(node.relPath, node.name, node.size));
                }

                List<SourceItem> comparable = new ArrayList<>();
                List<LibraryComparison.Track> tracks = new ArrayList<>();
                for (SourceItem item : selections) {
                    if (item.problem != null || !item.supportedFormat()) continue;
                    String artist = "";
                    String album = "";
                    if (!RemoteAudio.isRemote(item.uri) && tracks.size() < 1000) {
                        SourceRepo.TrackTags tags = sourceRepo.readTags(item.uri);
                        if (tags.artist != null) artist = tags.artist;
                        if (tags.album != null) album = tags.album;
                    }
                    StringBuilder folder = new StringBuilder(item.sourceFolder);
                    for (String component : item.treeRelDirs) {
                        if (folder.length() > 0) folder.append('/');
                        folder.append(component);
                    }
                    tracks.add(new LibraryComparison.Track(item.displayName, item.size,
                            artist, album, folder.toString()));
                    comparable.add(item);
                }
                LibraryComparison.Result result = LibraryComparison.compare(tracks, player);
                final boolean truncated = player.size() >= 60000;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false, null);
                    showLibraryAuditResult(result, comparable, truncated);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false, null);
                    toast("Player scan failed: " + describe(t));
                });
            }
        });
    }

    private void showLibraryAuditResult(LibraryComparison.Result result,
                                        List<SourceItem> comparable, boolean truncated) {
        final String report = (truncated
                ? "Warning: scan limit reached; inventory may be incomplete.\\n\\n" : "")
                + result.report(100);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("RUIZU library audit")
                .setMessage(report)
                .setNegativeButton("Close", (d, which) -> {})
                .setNeutralButton("Share report", (d, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_SUBJECT, "RUIZU library inventory");
                    share.putExtra(Intent.EXTRA_TEXT,
                            (truncated ? "Scan was truncated.\\n" : "") + result.report(5000));
                    try {
                        startActivity(Intent.createChooser(share, "Share RUIZU inventory"));
                    } catch (ActivityNotFoundException e) {
                        toast("No share app available");
                    }
                });
        if (result.missing > 0) {
            dialog.setPositiveButton("Queue missing", (d, which) -> {
                List<SourceItem> missing = new ArrayList<>();
                for (int i = 0; i < result.matches.size(); i++) {
                    if (result.matches.get(i).status == LibraryComparison.Status.MISSING) {
                        missing.add(comparable.get(i));
                    }
                }
                state.clearSources();
                state.addSources(missing);
                renderSelections();
                toast(missing.size() + " missing files queued; review and start transfer when ready");
            });
        }
        dialog.show();
    }

    /**
     * Storage Access Framework does not provide a general-purpose USB unmount API.
     * Keep the wording truthful: a completed copy is NOT a confirmed system eject.
     */
    private void showSafeRemovalGuide() {
        if (manager.isRunning() || busy) {
            new AlertDialog.Builder(this)
                    .setTitle("RUIZU is still in use")
                    .setMessage("Wait until copying and verification finish, then use Samsung's "
                            + "system eject action. Do not unplug during a transfer.")
                    .setPositiveButton("OK", (d, which) -> {})
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Safely remove your RUIZU")
                .setMessage("RUIZU Sync is not copying files now, but it has NOT ejected the USB "
                        + "device. Open Samsung My Files, find USB storage 1 and use its "
                        + "three-dot menu to choose Unmount/Eject (if shown). Alternatively, "
                        + "open Settings > Device care > Storage and use the eject control "
                        + "beside USB storage. Wait for Samsung to confirm unmounting before "
                        + "disconnecting the player.")
                .setNegativeButton("Close", (d, which) -> {})
                .setPositiveButton("Open My Files", (d, which) -> {
                    try {
                        Intent launch = getPackageManager().getLaunchIntentForPackage(
                                "com.sec.android.app.myfiles");
                        if (launch != null) {
                            startActivity(launch);
                        } else {
                            startActivity(new Intent("android.settings.INTERNAL_STORAGE_SETTINGS"));
                        }
                    } catch (Throwable error) {
                        toast("Open Samsung My Files manually to eject USB storage 1");
                    }
                }).show();
    }

    // ---------------------------------------------------------------------------------------
    // USB detection
    // ---------------------------------------------------------------------------------------

    private void refreshUsb() {
        UsbVolumes.Detection environment = UsbVolumes.detectEnvironment(this);
        usbVolumes = environment.volumes;
        if (usbStatus == null) {
            return;
        }
        if (usbVolumes.isEmpty()) {
            if (environment.looksLikeMtpOnly()) {
                usbStatus.setText("A USB device is connected, but Android has not mounted it as "
                        + "storage (MTP/PTP only). No no-root app can write to an MTP player: switch "
                        + "it to USB disk / mass-storage mode, or copy from a computer.");
            } else {
                usbStatus.setText("No USB storage mounted yet. Connect the player with its cable, "
                        + "unlock the phone and wait for Android to mount it, then choose the player "
                        + "folder.");
            }
            useUsbButton.setEnabled(false);
            planOffered = false;
        } else {
            maybeRestorePlayerFolder();
            maybeOfferPlan();
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
        if (auditButton != null) auditButton.setEnabled(!value && !manager.isRunning()
                && state.destinationTree() != null);
        if (safelyRemoveButton != null) safelyRemoveButton.setEnabled(!value);
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

    // ---------------------------------------------------------------------------------------
    // Playlists, subscriptions, saved plan
    // ---------------------------------------------------------------------------------------

    private void savePlaylist() {
        String name = playlistNameInput == null ? "" : playlistNameInput.getText().toString().trim();
        if (name.isEmpty()) {
            toast("Enter a playlist name first");
            return;
        }
        List<SourceItem> items = state.sources();
        if (items.isEmpty()) {
            toast("Add some files first");
            return;
        }
        PlaylistStore.save(this, name, items);
        refreshPlaylistSpinner();
        toast("Saved playlist " + name + " (" + items.size() + " tracks)");
    }

    private void refreshPlaylistSpinner() {
        if (playlistAdapter == null) {
            return;
        }
        List<String> names = PlaylistStore.names(this);
        playlistAdapter.clear();
        playlistAdapter.addAll(names);
        playlistAdapter.notifyDataSetChanged();
        playlistSpinner.setEnabled(!names.isEmpty());
    }

    private String selectedPlaylistName() {
        if (playlistSpinner == null) {
            return null;
        }
        Object item = playlistSpinner.getSelectedItem();
        return item == null ? null : item.toString();
    }

    private void loadPlaylist() {
        String name = selectedPlaylistName();
        if (name == null) {
            toast("No saved playlists");
            return;
        }
        PlaylistStore.Playlist playlist = PlaylistStore.load(this, name);
        if (playlist == null || playlist.items.isEmpty()) {
            toast("That playlist is empty");
            return;
        }
        state.addSources(playlist.items);
        renderSelections();
        toast("Loaded " + playlist.items.size() + " tracks from " + name);
    }

    private void exportPlaylist() {
        String name = selectedPlaylistName();
        if (name == null) {
            toast("No saved playlists");
            return;
        }
        PlaylistStore.Playlist playlist = PlaylistStore.load(this, name);
        if (playlist == null || playlist.items.isEmpty()) {
            toast("That playlist is empty");
            return;
        }
        Uri dest = state.destinationTree();
        if (dest == null) {
            toast("Choose the player folder first");
            return;
        }
        TransferManager.Request request = new TransferManager.Request(playlist.items, dest,
                state.organiseMode(), state.duplicateMode(),
                state.duplicatesInTargetFolderOnly(), state.writePlaylists(), name, null,
                state.convertToMp3(), null);
        try {
            manager.start(request);
        } catch (RuntimeException e) {
            toast(describe(e));
            return;
        }
        TransferService.start(this);
        sawRunning = true;
        notifiedPhase = null;
        render(manager.snapshot());
        toast("Exporting playlist " + name);
    }

    private void deletePlaylist() {
        String name = selectedPlaylistName();
        if (name == null) {
            toast("No saved playlists");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete playlist?")
                .setMessage(name)
                .setPositiveButton("Delete", (dialog, which) -> {
                    PlaylistStore.delete(this, name);
                    refreshPlaylistSpinner();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshSubscriptionStatus() {
        if (subscriptionStatus == null) {
            return;
        }
        List<SubscriptionStore.Feed> feeds = SubscriptionStore.list(this);
        if (feeds.isEmpty()) {
            subscriptionStatus.setText("No subscriptions yet.");
            return;
        }
        StringBuilder sb = new StringBuilder(feeds.size() + (feeds.size() == 1 ? " feed: " : " feeds: "));
        int shown = 0;
        for (SubscriptionStore.Feed feed : feeds) {
            if (shown >= 3) {
                sb.append("…");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(feed.title);
            shown++;
        }
        subscriptionStatus.setText(sb.toString());
    }

    private void subscribeToFeed() {
        String link = feedInputUrl();
        if (link == null) {
            return;
        }
        SubscriptionStore.add(this, link, link);
        refreshSubscriptionStatus();
        toast("Subscribed to the feed in the URL box");
    }

    private void refreshSubscriptions() {
        List<SubscriptionStore.Feed> feeds = SubscriptionStore.list(this);
        if (feeds.isEmpty()) {
            toast("Subscribe to a feed first");
            return;
        }
        setBusy(true, "Checking subscriptions…");
        ioExecutor.execute(() -> {
            int downloaded = 0;
            int checked = 0;
            int failures = 0;
            File cacheDir = new File(getCacheDir(), "episodes");
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                mainHandler.post(() -> {
                    setBusy(false, null);
                    toast("Cannot create the episode cache folder");
                });
                return;
            }
            for (SubscriptionStore.Feed feed : feeds) {
                try {
                    List<FeedScanner.Episode> episodes =
                            RemoteAudio.fetchFeedEpisodes(feed.url, RemoteAudio.MAX_FEED_CHARS);
                    checked++;
                    List<SourceItem> added = new ArrayList<>();
                    List<String> seen = new ArrayList<>(feed.seen);
                    for (FeedScanner.Episode episode : episodes) {
                        if (downloaded >= MAX_AUTO_DOWNLOADS) {
                            break;
                        }
                        if (seen.contains(episode.audioUrl)) {
                            continue;
                        }
                        try {
                            String name = FileNameSanitizer.fileName(
                                    MediaTypes.normalizeAudioName(episode.title, episode.mimeType));
                            // A stable, URL-specific name prevents future feed refreshes
                            // from overwriting older cached episodes with the same title.
                            String episodeKey = DownloadResumePolicy.key(episode.audioUrl)
                                    .substring(0, 20);
                            File target = new File(cacheDir, episodeKey + "-" + name);
                            if (!target.isFile()) {
                                RemoteAudio.downloadToFile(episode.audioUrl, target, null);
                            }
                            added.add(new SourceItem(Uri.fromFile(target), target.getName(),
                                    target.length(), episode.mimeType, "Podcasts",
                                    Collections.<String>emptyList(), null));
                            seen.add(episode.audioUrl);
                            downloaded++;
                        } catch (Throwable t) {
                            failures++;
                        }
                    }
                    SubscriptionStore.markChecked(this, feed.url, seen, feed.title);
                    final List<SourceItem> toAdd = added;
                    mainHandler.post(() -> {
                        state.addSources(toAdd);
                        renderSelections();
                    });
                } catch (Throwable t) {
                    failures++;
                }
            }
            final int checkedCount = checked;
            final int downloadedCount = downloaded;
            final int failureCount = failures;
            mainHandler.post(() -> {
                setBusy(false, null);
                refreshSubscriptionStatus();
                toast(checkedCount + (checkedCount == 1 ? " feed checked" : " feeds checked") + ", "
                        + downloadedCount + (downloadedCount == 1 ? " new episode queued" : " new episodes queued")
                        + (failureCount > 0 ? " (" + failureCount + " failed)" : ""));
            });
        });
    }

    private void importOpml() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "text/xml", "application/xml", "application/x-opml", "text/plain",
                        "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_IMPORT_OPML);
        } catch (ActivityNotFoundException e) {
            toast("No file picker available");
        }
    }

    private void importOpmlFrom(Uri uri) {
        setBusy(true, "Importing subscriptions…");
        ioExecutor.execute(() -> {
            try {
                String xml = readText(uri, 8 * 1024 * 1024);
                List<Opml.Feed> feeds = Opml.parse(xml);
                mainHandler.post(() -> {
                    setBusy(false, null);
                    if (!isFinishing()) previewOpmlImport(feeds);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    setBusy(false, null);
                    toast("OPML import failed: " + describe(t));
                });
            }
        });
    }

    private void previewOpmlImport(List<Opml.Feed> feeds) {
        SubscriptionImportDialog.show(this, feeds, this::refreshSubscriptionStatus);
    }

    private void exportOpml() {
        List<SubscriptionStore.Feed> feeds = SubscriptionStore.list(this);
        if (feeds.isEmpty()) {
            toast("No subscriptions to export");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/xml")
                .putExtra(Intent.EXTRA_TITLE, "ruizu-subscriptions.opml");
        try {
            startActivityForResult(intent, REQ_EXPORT_OPML);
        } catch (ActivityNotFoundException e) {
            toast("No file picker available");
        }
    }

    private void writeOpmlTo(Uri uri) {
        List<SubscriptionStore.Feed> feeds = SubscriptionStore.list(this);
        List<Opml.Feed> entries = new ArrayList<>();
        for (SubscriptionStore.Feed feed : feeds) {
            entries.add(new Opml.Feed(feed.title, feed.url));
        }
        String text = Opml.generate(entries);
        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) {
                throw new IOException("Cannot open the destination");
            }
            out.write(text.getBytes("UTF-8"));
            toast("Exported " + entries.size() + (entries.size() == 1 ? " feed" : " feeds"));
        } catch (Throwable t) {
            toast("OPML export failed: " + describe(t));
        }
    }

    private void savePlan() {
        Uri dest = state.destinationTree();
        if (dest == null) {
            toast("Choose the player folder first");
            return;
        }
        List<Uri> trees = state.sourceTrees();
        if (trees.isEmpty()) {
            toast("Add a source folder first (Add folder)");
            return;
        }
        Uri sourceTree = trees.get(0);
        SyncPlanStore.Plan plan = new SyncPlanStore.Plan(sourceTree.toString(), "",
                dest.toString(), state.destinationLabel(), state.destinationDeviceId(),
                Collections.<String>emptyList(),
                state.organiseMode(), state.duplicateMode(),
                state.duplicatesInTargetFolderOnly(), state.writePlaylists(), false, "",
                planEnabledCheck != null && planEnabledCheck.isChecked());
        SyncPlanStore.save(this, plan);
        refreshPlanStatus();
        toast("Saved transfer plan");
    }

    private void runPlan() {
        SyncPlanStore.Plan plan = SyncPlanStore.load(this);
        if (plan == null) {
            toast("No saved plan yet");
            return;
        }
        if (plan.destTree.isEmpty()) {
            toast("The saved plan has no player folder");
            return;
        }
        setBusy(true, "Running the saved plan…");
        final Context appContext = getApplicationContext();
        ioExecutor.execute(() -> {
            try {
                List<SourceItem> items = new ArrayList<>();
                if (!plan.sourceTree.isEmpty()) {
                    SourceRepo.Listing listing = new SourceRepo(this).scanTree(Uri.parse(plan.sourceTree));
                    items.addAll(listing.items);
                }
                final PlayerMarkerStore.Reading identity =
                        PlayerMarkerStore.read(appContext, Uri.parse(plan.destTree));
                mainHandler.post(() -> {
                    setBusy(false, null);
                    if (isFinishing()) {
                        return;
                    }
                    // The plan remembers which player it was saved for; refuse to run it on
                    // another unit that happens to share the same volume id.
                    PlayerBinding.Restore restore =
                            PlayerBinding.decide(false, plan.destDeviceId, identity.deviceId());
                    if (!PlayerBinding.allowsAutomaticReuse(restore)) {
                        toast(describeRefusedPlan(plan, identity));
                        return;
                    }
                    if (items.isEmpty()) {
                        toast("The saved source folder has no supported audio");
                        return;
                    }
                    state.addSources(items);
                    Uri dest = Uri.parse(plan.destTree);
                    state.setDestination(dest, plan.destLabel, identity.deviceId(),
                            identity.deviceName());
                    state.setOrganiseMode(plan.organise);
                    state.setDuplicateMode(plan.duplicates);
                    state.setDuplicatesInTargetFolderOnly(plan.scopeFolder);
                    state.setWritePlaylists(plan.playlists);
                    syncOptionViews();
                    renderSelections();
                    beginTransfer(items, dest);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    setBusy(false, null);
                    toast("Saved plan failed: " + describe(t));
                });
            }
        });
    }

    private void refreshPlanStatus() {
        if (planStatus == null) {
            return;
        }
        SyncPlanStore.Plan plan = SyncPlanStore.load(this);
        if (plan == null) {
            planStatus.setText("No saved plan. Add a source folder, pick the player folder, then Save setup.");
            if (planEnabledCheck != null) {
                planEnabledCheck.setChecked(false);
            }
            return;
        }
        String destination = plan.destLabel.isEmpty() ? "player folder" : plan.destLabel;
        planStatus.setText("Plan: source folder → " + destination + " (" + plan.organise.label
                + ", " + plan.duplicates.label + ")");
        if (planEnabledCheck != null) {
            planEnabledCheck.setChecked(plan.enabled);
        }
    }

    private String describeRefusedPlan(SyncPlanStore.Plan plan, PlayerMarkerStore.Reading identity) {
        String remembered = plan.destLabel.isEmpty() ? "a different player" : plan.destLabel;
        if (!plan.destDeviceId.isEmpty()) {
            remembered = remembered + " (" + shortDeviceId(plan.destDeviceId) + ")";
        }
        String connected = identity.identified()
                ? describeIdentity(identity)
                : "an unidentified player";
        return "This plan belongs to " + remembered + ", but " + connected + " is connected."
                + " Nothing was copied. Reconnect the right player, or choose the player folder"
                + " and save the plan again.";
    }

    private void maybeOfferPlan() {
        if (planOffered) {
            return;
        }
        SyncPlanStore.Plan plan = SyncPlanStore.load(this);
        if (plan == null || !plan.enabled || manager.isRunning()) {
            return;
        }
        planOffered = true;
        new AlertDialog.Builder(this)
                .setTitle("Player connected")
                .setMessage("Run the saved transfer plan now?")
                .setPositiveButton("Run plan", (dialog, which) -> runPlan())
                .setNegativeButton("Not now", null)
                .show();
    }

    private String readText(Uri uri, int maxChars) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Cannot open the file");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxChars) {
                    throw new IOException("The file is too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private void syncOptionViews() {
        organiseSpinner.setSelection(state.organiseMode().ordinal());
        // Must map through the selectable list, not the enum ordinal: OVERWRITE is excluded
        // from the spinner, so ordinals no longer line up with adapter positions.
        duplicateSpinner.setSelection(
                Math.max(0, DuplicateMode.selectable().indexOf(state.duplicateMode())));
        if (scopeCheck != null) {
            scopeCheck.setChecked(state.duplicatesInTargetFolderOnly());
            playlistCheck.setChecked(state.writePlaylists());
            convertCheck.setChecked(state.convertToMp3());
        }
    }

    private CheckBox choice(String text, boolean checked,
                            android.widget.CompoundButton.OnCheckedChangeListener listener) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(13);
        box.setTextColor(getColor(R.color.text));
        box.setChecked(checked);
        box.setButtonTintList(ColorStateList.valueOf(getColor(R.color.brand)));
        box.setOnCheckedChangeListener(listener);
        return box;
    }

    private void updateLastRunText() {
        if (lastRunText == null) {
            return;
        }
        java.util.List<HistoryStore.Run> runs = HistoryStore.recentRuns(this, 3);
        if (runs.isEmpty()) {
            lastRunText.setText("No transfers yet");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (HistoryStore.Run run : runs) {
            String when = android.text.format.DateUtils.getRelativeTimeSpanString(run.at,
                    System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("• ").append(run.summary).append(" — ").append(when);
        }
        lastRunText.setText(sb.toString());
    }

    /** Diagnostics hook for CI: `adb shell am start ... --es selftest convert`. */
    private void maybeRunSelfTest(Intent intent) {
        if (intent == null) {
            return;
        }
        String mode = intent.getStringExtra("selftest");
        if (mode == null) {
            return;
        }
        if ("convert".equals(mode)) {
            ioExecutor.execute(() -> {
                String result = ConversionSelfTest.run(this);
                String line = "conversion selftest: " + result;
                android.util.Log.i("RUIZU-SELFTEST", line);
                mainHandler.post(() -> toast(line));
            });
        }
    }

    private void retryFailed() {
        List<SourceItem> failed = manager.failedSources();
        if (failed.isEmpty()) {
            toast("Nothing to retry");
            return;
        }
        Uri dest = state.destinationTree();
        if (dest == null) {
            toast("Choose the player folder first");
            return;
        }
        TransferManager.Request request = new TransferManager.Request(
                new ArrayList<>(failed), dest, state.organiseMode(), state.duplicateMode(),
                state.duplicatesInTargetFolderOnly(), state.writePlaylists(),
                "", null, state.convertToMp3(), null);
        try {
            manager.start(request);
        } catch (RuntimeException e) {
            toast(describe(e));
            return;
        }
        TransferService.start(this);
        sawRunning = true;
        notifiedPhase = null;
        render(manager.snapshot());
    }

    private void copyLog() {
        String stored = HistoryStore.lastRunLog(this);
        TransferManager.Snapshot snapshot = manager.snapshot();
        String live = snapshot == null ? "" : joinTail(snapshot.log, 200);
        String text = stored == null || stored.isEmpty() ? live : stored;
        if (text.isEmpty()) {
            toast("No log to copy yet");
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("RUIZU Sync log", text));
                toast("Log copied to the clipboard");
            }
        } catch (Throwable t) {
            toast("Could not copy the log: " + describe(t));
        }
    }

    private void maybeOfferResume() {
        if (!PendingTransferStore.hasPending(this)) {
            return;
        }
        PendingTransferStore.Pending pending = PendingTransferStore.load(this);
        if (pending == null || pending.remaining() == 0) {
            PendingTransferStore.clear(this);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Resume previous transfer?")
                .setMessage(pending.remaining() + " of " + pending.request.sources.size()
                        + " files were not copied when the app last stopped.")
                .setPositiveButton("Restore queue", (dialog, which) -> restorePending(pending))
                .setNegativeButton("Discard", (dialog, which) -> {
                    PendingTransferStore.clear(this);
                    renderSelections();
                })
                .show();
    }

    private void restorePending(PendingTransferStore.Pending pending) {
        List<SourceItem> sources = pending.request.sources;
        int from = Math.min(pending.processed, sources.size());
        state.clearSources();
        state.addSources(sources.subList(from, sources.size()));
        state.setDestination(pending.request.destTree, "");
        state.setOrganiseMode(pending.request.organiseMode);
        state.setDuplicateMode(pending.request.duplicateMode);
        state.setDuplicatesInTargetFolderOnly(pending.request.duplicatesInTargetFolderOnly);
        state.setWritePlaylists(pending.request.writePlaylists);
        syncOptionViews();
        refreshDestination(pending.request.destTree);
        renderSelections();
        toast("Queue restored — press Start transfer");
    }

    private void maybeRestorePlayerFolder() {
        if (state.destinationTree() != null || usbVolumes.isEmpty()) {
            return;
        }
        for (UsbVolumes.Info info : usbVolumes) {
            String uuid = info.volume.getUuid();
            String key = uuid == null || uuid.isEmpty() ? "primary" : uuid;
            PlayerFolderStore.Restore restore = PlayerFolderStore.evaluateRestore(this, key);
            if (restore == null || !restore.permissionHeld) {
                continue;
            }
            if (!PlayerBinding.allowsAutomaticReuse(restore.decision)) {
                // Both players report the same volume id, so the remembered folder is not
                // evidence that this is the same unit. Select nothing and say so plainly.
                state.clearDestination();
                renderSelections();
                showDifferentPlayerDialog(restore, info.label);
                return;
            }
            String label = restore.label.isEmpty() ? info.label : restore.label;
            state.setDestination(restore.treeUri, label, restore.connectedDeviceId,
                    restore.connectedDeviceName);
            refreshDestination(restore.treeUri);
            toast(restore.decision == PlayerBinding.Restore.MATCH
                    ? "Using your saved player folder on " + describeConnectedPlayer(restore)
                    : "Using your saved player folder on " + label);
            return;
        }
    }

    /**
     * Tells the user which unit is attached and which one the phone remembers. Nothing is
     * selected, so the wrong player cannot receive a transfer that was planned for the other one.
     */
    private void showDifferentPlayerDialog(PlayerFolderStore.Restore restore, String volumeLabel) {
        String message = describeRefusedRestore(restore, volumeLabel);
        new AlertDialog.Builder(this)
                .setTitle("A different player is connected")
                .setMessage(message)
                .setPositiveButton("Choose player folder", (dialog, which) -> chooseDestination(null))
                .setNegativeButton("Not now", null)
                .show();
    }

    private static String describeConnectedPlayer(PlayerFolderStore.Restore restore) {
        String name = restore.connectedDeviceName == null ? "Player" : restore.connectedDeviceName;
        return name + " (" + shortDeviceId(restore.connectedDeviceId) + ")";
    }

    private static String describeRefusedRestore(PlayerFolderStore.Restore restore, String volumeLabel) {
        String connected;
        if (restore.connectedDeviceId != null) {
            connected = describeConnectedPlayer(restore);
        } else if (restore.connectedState == PlayerMarkerStore.State.MALFORMED) {
            connected = "a player whose identity file cannot be understood";
        } else {
            connected = "a player with no readable identity";
        }
        String remembered = restore.storedLabel.isEmpty() ? "another player" : restore.storedLabel;
        if (restore.storedDeviceId != null) {
            remembered = remembered + " (" + shortDeviceId(restore.storedDeviceId) + ")";
        }
        String volume = volumeLabel == null || volumeLabel.isEmpty() ? "this volume" : volumeLabel;
        return "The phone remembers a player folder for " + remembered
                + ", but " + connected + " is attached on " + volume + "."
                + "\n\nThey share the same volume id, so they cannot be told apart by the"
                + " mount point alone. Nothing has been selected and no transfer will start."
                + "\n\nChoose the player folder again to use this player.";
    }

    private static String describeIdentity(PlayerMarkerStore.Reading reading) {
        String name = reading.deviceName() == null ? "Player" : reading.deviceName();
        return name + " (" + shortDeviceId(reading.deviceId()) + ")";
    }

    private String describeUnidentified(PlayerMarkerStore.Reading reading) {
        if (reading.state == PlayerMarkerStore.State.MALFORMED) {
            return "Player folder selected, but its identity file is not readable — player unidentified";
        }
        return "Player folder selected, but this player's identity could not be read or written"
                + " — player unidentified";
    }

    private static String shortDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return "no id";
        }
        return deviceId.length() <= 8 ? deviceId : deviceId.substring(0, 8);
    }

    private void applyWindowInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            final int left;
            final int top;
            final int right;
            final int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
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
