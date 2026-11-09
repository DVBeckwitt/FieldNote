package com.fieldnote.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real launch/service/AAC evidence. Activity-cold launches do not simulate process death. */
@RunWith(AndroidJUnit4.class)
public final class QuickCaptureInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private ProjectStore store;
    private SharedPreferences preferences, bookmarks;
    private Map<String, ?> priorPreferences, priorBookmarks;
    private File fixtureRoot;
    private Uri fixtureTree;
    private final List<Uri> ownedProjects = new ArrayList<>();
    // Lifecycle callbacks and this list are accessed only on the main thread.
    private final List<MainActivity> ownedActivities = new ArrayList<>();
    private volatile MainActivity activity;
    private ActivityLifecycleCallback lifecycle;
    private Object acceptedRecordingToken;
    private boolean overlayRequested;
    private String priorOverlayMode;
    private FloatingRecorderService ownedOverlay;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertFalse("Do not take over an existing recording", RecordingService.isBusy());
        assertFalse("Do not take over an existing floating recorder", FloatingRecorderService.isShowing());
        acceptedRecordingToken = readField(RecordingService.snapshot(), "token");
        // Initialize the authenticated launcher key before snapshotting preferences.
        RecordingShortcuts.recordIntent(context);
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        bookmarks = context.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        priorPreferences = preferences.getAll();
        priorBookmarks = bookmarks.getAll();
        assertTrue(preferences.edit().putBoolean("metadata_enabled", false)
                .putBoolean("location_permission_asked", true)
                .putBoolean("motion_controls", false).commit());
        assertTrue(bookmarks.edit().clear().commit());
        // As in NativeCaptureInstrumentedTest, grants are additive on the isolated
        // debug installation. Revoking them can terminate the in-process runner.
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        TestDocumentsProvider.clearFailures();
        await("previous publication is idle", 30_000, () -> !ProjectPublisher.snapshot().running);
        File base = TestDocumentsProvider.rootDirectory(context);
        assertTrue(base.isDirectory() || base.mkdirs());
        fixtureRoot = new File(base, "quick-capture-" + UUID.randomUUID());
        assertTrue(fixtureRoot.mkdir());
        fixtureTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName());
        store = new ProjectStore(context);
        lifecycle = (changed, stage) -> {
            if (!(changed instanceof MainActivity)) return;
            MainActivity main = (MainActivity) changed;
            if (!ownedActivities.contains(main)) ownedActivities.add(main);
            if (stage == Stage.RESUMED) activity = main;
            if (stage == Stage.DESTROYED && activity == main) activity = null;
        };
        onMain(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle));
    }

    @After public void tearDown() throws Exception {
        TestDocumentsProvider.clearFailures(); // Release provider latches before any waits.
        if (context == null) return;
        try {
            finishOwnedActivities(); // Cancels unresolved requests before restoring preferences.
            if (overlayRequested) {
                onMain(() -> FloatingRecorderService.hide(context));
                await("the owned floating host finishes before fixture cleanup", 15_000,
                        () -> activeOverlay() == null);
                if (ownedOverlay != null) await("the owned overlay reader finishes", 15_000,
                        () -> ((ExecutorService) readField(ownedOverlay, "io")).isTerminated());
            }
            RecordingService.Snapshot capture = RecordingService.snapshot();
            if (ownedProjects.contains(capture.projectUri) && RecordingService.isBusy()) {
                onMain(() -> RecordingService.stop(context));
                await("owned microphone stops during cleanup", 20_000, () -> !RecordingService.isBusy());
            }
            await("publication finishes before deleting owned fixtures", 30_000,
                    () -> !ProjectPublisher.snapshot().running);
            awaitOwnedReaders();
            deleteOwnedPendingCaptures();
            for (Uri project : ownedProjects) {
                clearOwnIndexCache(project);
                File recovery = new File(context.getFilesDir(), "archive-publications/" + uriKey(project));
                deleteWithin(recovery, recovery);
            }
            if (fixtureRoot != null) deleteWithin(fixtureRoot, fixtureRoot);
        } finally {
            if (lifecycle != null)
                onMain(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle));
            if (bookmarks != null && priorBookmarks != null) restore(bookmarks, priorBookmarks);
            if (preferences != null && priorPreferences != null) restore(preferences, priorPreferences);
            if (priorOverlayMode != null)
                shell("appops set " + ownPackage() + " SYSTEM_ALERT_WINDOW " + priorOverlayMode);
            TestDocumentsProvider.clearFailures();
        }
    }

    @Test public void activityColdRecordNowUsesLatestCreatedProjectAndIgnoresSuppliedAttachment() throws Exception {
        Uri older = createProject("Older project");
        String oldEntry = addText(older, "Keep this note separate");
        Uri latest = createProject("Latest project");
        store.setCurrentProject(older);
        Intent record = RecordingShortcuts.recordIntent(context)
                .putExtra("projectUri", older.toString()).putExtra("entryId", oldEntry);
        launch(record);
        RecordingService.Snapshot capture = awaitLockedRecording();
        assertFalse("Record now starts on the static main screen", FloatingRecorderService.isShowing());
        assertEquals(latest, capture.projectUri);
        assertNotEquals(oldEntry, capture.entryId);
        assertEquals(latest, store.lastCreatedProject());
        stopAndVerify(capture);
        assertEquals(0, audioCount(store.readProject(older)));
        assertEquals(0, entry(store.readProject(older), oldEntry).getJSONArray("media").length());
        assertEquals(1, audioCount(store.readProject(latest)));
    }

    @Test public void recordNowClosesIdleOverlayBeforeStartingStaticAudioInLatestProject() throws Exception {
        Uri overlayProject = createProject("Idle floating project");
        Uri latest = createProject("New static recording project");
        store.setCurrentProject(overlayProject);
        launch(ordinaryIntent());
        awaitSettled();
        showOwnedOverlay(overlayProject);
        assertFalse(RecordingService.isBusy());

        launchRecordAndAwaitDelivery();
        RecordingService.Snapshot capture = awaitLockedRecording();
        awaitSettled();
        await("static recording dismisses the floating host", 10_000, () -> activeOverlay() == null);
        assertFalse(FloatingRecorderService.isShowing());
        assertEquals(latest, capture.projectUri);
        assertTrue(isFocused());
        stopAndVerify(capture);
        assertEquals(0, audioCount(store.readProject(overlayProject)));
        assertEquals(1, audioCount(store.readProject(latest)));
    }

    @Test public void recordNowMovesLockedOverlayAudioToStaticModeWithoutRestartingIt() throws Exception {
        Uri original = createProject("Existing floating original");
        launch(ordinaryIntent());
        awaitSettled();
        showOwnedOverlay(original);
        onMain(() -> {
            RecordingService.start(activity, original, UUID.randomUUID().toString());
            RecordingService.lock(activity);
        });
        RecordingService.Snapshot before = awaitLockedRecording();
        awaitRecordableDuration();
        Uri newer = createProject("Created after the active original");

        launchRecordAndAwaitDelivery();
        awaitSettled();
        await("locked audio hands off to the static main screen", 10_000, () -> activeOverlay() == null);
        assertFalse(FloatingRecorderService.isShowing());
        assertTrue(isFocused());
        assertSameCapture(before, RecordingService.snapshot());
        launchRecordAndAwaitDelivery();
        awaitSettled();
        assertSameCapture(before, RecordingService.snapshot());
        stopAndVerify(before);
        assertEquals(1, audioCount(store.readProject(original)));
        assertEquals(0, audioCount(store.readProject(newer)));
    }

    @Test public void warmRecordNowClearsAttachmentAndDuplicateLaunchesKeepRecordingIdentity() throws Exception {
        Uri older = createProject("Previously selected");
        String attachment = addText(older, "A pending attachment target");
        Uri latest = createProject("Newest notebook");
        store.setCurrentProject(older);
        launch(ordinaryIntent());
        awaitSettled();
        onMain(() -> setField(activity, "nextEntryId", attachment));
        launchRecordAndAwaitDelivery();
        RecordingService.Snapshot first = awaitLockedRecording();
        assertEquals(latest, first.projectUri);
        assertNotEquals(attachment, first.entryId);
        onMain(() -> assertNull(readField(activity, "nextEntryId")));
        for (int i = 0; i < 2; i++) {
            launchRecordAndAwaitDelivery();
            awaitSettled();
            assertSameCapture(first, RecordingService.snapshot());
        }
        stopAndVerify(first);
        assertEquals(0, audioCount(store.readProject(older)));
        assertEquals(1, audioCount(store.readProject(latest)));
    }

    @Test public void duplicateIntentWhileStartingKeepsTheOriginalProjectAndSession() throws Exception {
        Uri original = createProject("Original capture project");
        Uri newer = createProject("Created later");
        store.setCurrentProject(original);
        launch(ordinaryIntent());
        awaitSettled();
        String entryId = UUID.randomUUID().toString();
        AtomicReference<RecordingService.Snapshot> starting = new AtomicReference<>();
        onMain(() -> {
            // start() reserves STARTING synchronously; onStartCommand cannot run
            // until this main-loop turn ends, making this race deterministic.
            RecordingService.start(activity, original, entryId);
            RecordingService.lock(activity);
            starting.set(RecordingService.snapshot());
            assertEquals(RecordingService.State.STARTING, starting.get().state);
            activity.onNewIntent(RecordingShortcuts.recordIntent(context));
            activity.onNewIntent(RecordingShortcuts.recordIntent(context));
        });
        RecordingService.Snapshot recording = awaitLockedRecording();
        awaitSettled();
        assertEquals(original, recording.projectUri);
        assertEquals(entryId, recording.entryId);
        assertEquals(readField(starting.get(), "token"), readField(recording, "token"));
        assertEquals(newer, store.lastCreatedProject());
        stopAndVerify(recording);
        assertEquals(1, audioCount(store.readProject(original)));
        assertEquals(0, audioCount(store.readProject(newer)));
    }

    @Test public void completedQuickCaptureDoesNotReplayAfterRecreationOrOrdinaryLaunch() throws Exception {
        Uri project = createProject("One deliberate recording");
        launch(RecordingShortcuts.recordIntent(context));
        RecordingService.Snapshot capture = awaitLockedRecording();
        stopAndVerify(capture);
        MainActivity before = activity;
        onMain(before::recreate);
        await("recreated Main is resumed", 15_000, () -> activity != null && activity != before && isFocused());
        awaitSettled();
        assertNoCaptureFor(800);
        launch(ordinaryIntent());
        awaitSettled();
        assertNoCaptureFor(800);
        finishOwnedActivities();
        launch(ordinaryIntent());
        awaitSettled();
        assertNoCaptureFor(800);
        assertEquals(1, audioCount(store.readProject(project)));
    }

    @Test public void forgedRecordIntentCannotStartTheMicrophone() throws Exception {
        Uri project = createProject("Authenticated launch only");
        Intent forged = ordinaryIntent().setAction(RecordingShortcuts.ACTION_RECORD)
                .putExtra(RecordingShortcuts.EXTRA_NONCE, "not-the-installation-key")
                .putExtra("projectUri", project.toString());
        launch(forged);
        awaitSettled();
        assertNoCaptureFor(1_000);
        assertEquals(0, audioCount(store.readProject(project)));
    }

    @Test public void noProjectRequestIsConsumedAndCannotStartWhenAProjectAppearsLater() throws Exception {
        assertNull(store.lastCreatedProject());
        launch(RecordingShortcuts.recordIntent(context));
        awaitSettled();
        assertNoCaptureFor(500);
        Uri createdLater = createProject("Created after the failed request");
        finishOwnedActivities();
        launch(ordinaryIntent());
        awaitSettled();
        assertNoCaptureFor(1_000);
        assertEquals(0, audioCount(store.readProject(createdLater)));
    }

    @Test public void inaccessibleUncachedLatestProjectNeverFallsBackToAnOlderProject() throws Exception {
        Uri older = createProject("Still accessible");
        Uri latest = createProject("Temporarily unavailable");
        store.setCurrentProject(older);
        File index = ownIndexFile(latest);
        byte[] original = Files.readAllBytes(index.toPath());
        // Cached offline projects may intentionally stage new captures. Remove only
        // this fixture's verified cache to exercise actual destination resolution.
        clearOwnIndexCache(latest);
        assertNull(store.readCachedProject(latest));
        assertTrue(index.delete());
        try {
            launch(RecordingShortcuts.recordIntent(context));
            awaitSettled();
            assertNoCaptureFor(1_000);
            assertEquals(latest, store.lastCreatedProject());
            assertEquals(older, store.currentProject());
            assertEquals(0, audioCount(store.readProject(older)));
        } finally { Files.write(index.toPath(), original); }
        MainActivity before = activity;
        onMain(before::recreate);
        await("Main returns after access is restored", 15_000,
                () -> activity != null && activity != before && isFocused());
        awaitSettled();
        assertNoCaptureFor(800);
        assertEquals(0, audioCount(store.readProject(latest)));
    }

    @Test public void blockedFolderPublicationAllowsAnotherQuickLockedRecording() throws Exception {
        Uri project = createProject("Record while previous save is busy");
        launch(RecordingShortcuts.recordIntent(context));
        RecordingService.Snapshot first = awaitLockedRecording();
        awaitRecordableDuration();
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextWorkingIndexWrite(project)) {
            onMain(() -> RecordingService.stop(context));
            assertTrue("First publication reaches the intentionally blocked provider",
                    barrier.awaitBlocked(10, TimeUnit.SECONDS));
            await("first microphone is released before the index write completes", 10_000,
                    () -> RecordingService.snapshot().state == RecordingService.State.IDLE);
            launchRecordAndAwaitDelivery();
            RecordingService.Snapshot second = awaitLockedRecording();
            assertTrue("Second recording starts while the first index write is still blocked", barrier.isBlocked());
            assertFalse(barrier.timedOut());
            assertEquals(project, second.projectUri);
            assertNotEquals(first.entryId, second.entryId);
            barrier.release();
            stopAndVerify(second);
        }
        verifySavedAudio(first);
        assertEquals(2, audioCount(store.readProject(project)));
    }

    @Test public void coldCachedQuickRequestSurvivesIdleTickerBeforeWindowFocus() throws Exception {
        Uri project = createProject("Cold quick capture while save is busy");
        launch(RecordingShortcuts.recordIntent(context));
        RecordingService.Snapshot first = awaitLockedRecording();
        MainActivity previous = activity;
        awaitRecordableDuration();
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextWorkingIndexWrite(project)) {
            onMain(() -> RecordingService.stop(context));
            assertTrue(barrier.awaitBlocked(10, TimeUnit.SECONDS));
            await("first microphone stages before the cold launch", 10_000,
                    () -> RecordingService.snapshot().state == RecordingService.State.IDLE);
            finishOwnedActivities();
            assertNotNull("Cold launch has a verified local index", store.readCachedProject(project));
            AtomicReference<MainActivity> cold = new AtomicReference<>();
            ActivityLifecycleCallback holdFocus = (changed, stage) -> {
                if (!(changed instanceof MainActivity)) return;
                MainActivity main = (MainActivity) changed;
                if (stage == Stage.CREATED) {
                    cold.set(main);
                    // Control real window focus, rather than mocking hasWindowFocus().
                    main.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                }
                if (stage == Stage.STARTED || stage == Stage.RESUMED)
                    ((Handler) readField(main, "main")).removeCallbacks((Runnable) readField(main, "ticker"));
            };
            onMain(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(holdFocus));
            try {
                onMain(() -> context.startActivity(RecordingShortcuts.recordIntent(context)));
                await("cold cached resolver finishes before its window receives focus", 10_000, () -> {
                    AtomicBoolean ready = new AtomicBoolean();
                    onMain(() -> {
                        MainActivity main = cold.get();
                        ready.set(main != null && main != previous
                                && ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(main) == Stage.RESUMED
                                && (Boolean) readField(main, "quickReady")
                                && !(Boolean) readField(main, "quickResolving"));
                    });
                    return ready.get();
                });
                onMain(() -> {
                    MainActivity main = cold.get();
                    assertFalse("The pending shortcut has not received real window focus", main.hasWindowFocus());
                    assertEquals(1, ((Number) readField(main, "quickMode")).intValue());
                    assertFalse((Boolean) readField(main, "projectLoading"));
                    assertEquals(RecordingService.State.IDLE, RecordingService.snapshot().state);
                    // A publisher broadcast may already have observed IDLE. Inject
                    // the cold first-observation ordering after the resolver returns,
                    // then execute the real ticker and its real refresh path.
                    setField(main, "observedState", null);
                    ((Runnable) readField(main, "ticker")).run();
                    assertEquals(RecordingService.State.IDLE, readField(main, "observedState"));
                    assertFalse("The first idle tick must not replace the ready cached quick request with a blocked project read",
                            (Boolean) readField(main, "projectLoading"));
                    assertFalse(RecordingService.isBusy());
                    main.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                });
                await("cold Main receives focus after the controlled ordering", 5_000, this::isFocused);
                RecordingService.Snapshot second = awaitLockedRecording();
                assertTrue("Cold quick capture starts before the prior index write is released", barrier.isBlocked());
                assertFalse(barrier.timedOut());
                assertEquals(project, second.projectUri);
                assertNotEquals(first.entryId, second.entryId);
                barrier.release();
                stopAndVerify(second);
            } finally {
                onMain(() -> {
                    ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(holdFocus);
                    MainActivity main = cold.get();
                    if (main != null && !main.isDestroyed())
                        main.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                });
            }
        }
        verifySavedAudio(first);
        assertEquals(2, audioCount(store.readProject(project)));
    }

    @Test public void backgroundedPendingRequestAndStalePermissionCallbackCannotStartLater() throws Exception {
        Uri project = createProject("Cancel an unresolved launch");
        launch(RecordingShortcuts.recordIntent(context));
        RecordingService.Snapshot first = awaitLockedRecording();
        awaitRecordableDuration();
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextWorkingIndexWrite(project)) {
            onMain(() -> RecordingService.stop(context));
            assertTrue(barrier.awaitBlocked(10, TimeUnit.SECONDS));
            await("first recording stages", 10_000,
                    () -> RecordingService.snapshot().state == RecordingService.State.IDLE);
            clearOwnIndexCache(project);
            assertNull(store.readCachedProject(project));
            launchRecordAndAwaitDelivery();
            await("quick resolver waits for uncached project access", 5_000,
                    () -> mainBoolean("quickResolving"));
            MainActivity waiting = activity;
            onMain(() -> assertTrue(waiting.moveTaskToBack(true)));
            await("real background transition cancels the pending request", 5_000,
                    () -> stageOf(waiting) == Stage.STOPPED);
            onMain(() -> assertEquals(0, ((Number) readField(waiting, "quickMode")).intValue()));
            barrier.release();
            await("prior folder publication completes", 20_000, () -> !ProjectPublisher.snapshot().running);
            launch(ordinaryIntent());
            awaitSettled();
            // Cancellation clears UI loading flags before the old read returns.
            // Drain that exact resolver, then its posted main callback, so teardown
            // cannot hide a late surprise start after this assertion window.
            CountDownLatch resolved = new CountDownLatch(1);
            ((ExecutorService) readField(waiting, "quickIo")).execute(resolved::countDown);
            assertTrue("The formerly blocked quick resolver finishes", resolved.await(10, TimeUnit.SECONDS));
            onMain(() -> { });
            // This is deliberately a stale callback test, not an OS-denial test:
            // RECORD_AUDIO remains granted throughout this in-process suite.
            onMain(() -> activity.onRequestPermissionsResult(50,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED}));
            assertNoCaptureFor(1_000);
        }
        verifySavedAudio(first);
        assertEquals(1, audioCount(store.readProject(project)));
    }

    private Uri createProject(String name) throws Exception {
        Uri project = store.createWorkingProject(fixtureTree, name);
        ownedProjects.add(project);
        return project;
    }

    private String addText(Uri project, String text) throws Exception {
        return store.saveText(project, null, text, null).getString("id");
    }

    private void showOwnedOverlay(Uri project) throws Exception {
        assertTrue(ownedProjects.contains(project));
        assertNull(activeOverlay());
        String appOps = shell("appops get " + ownPackage() + " SYSTEM_ALERT_WINDOW");
        Matcher mode = Pattern.compile("(?m)^\\s*SYSTEM_ALERT_WINDOW:\\s*(allow|ignore|deny|default|foreground)\\b").matcher(appOps);
        priorOverlayMode = mode.find() ? mode.group(1) : "default";
        shell("appops set " + ownPackage() + " SYSTEM_ALERT_WINDOW allow");
        await("the test app receives overlay permission", 5_000, () -> Settings.canDrawOverlays(context));
        onMain(() -> {
            assertTrue(FloatingRecorderService.show(activity, project));
            overlayRequested = true;
        });
        await("the owned floating microphone is visible", 10_000,
                () -> FloatingRecorderService.isShowing() && activeOverlay() != null);
        ownedOverlay = activeOverlay();
        assertEquals(project, readField(ownedOverlay, "project"));
    }

    private FloatingRecorderService activeOverlay() throws Exception {
        Field active = FloatingRecorderService.class.getDeclaredField("active");
        active.setAccessible(true);
        return (FloatingRecorderService) active.get(null);
    }

    private String ownPackage() {
        String name = context.getPackageName();
        assertTrue(name.matches("[A-Za-z0-9_.]+"));
        return name;
    }

    private String shell(String command) throws IOException {
        ParcelFileDescriptor output = instrumentation.getUiAutomation().executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(output);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Intent ordinaryIntent() {
        return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void launch(Intent intent) throws Exception {
        onMain(() -> context.startActivity(intent));
        await("Main is resumed with window focus", 15_000, this::isFocused);
    }

    private void launchRecordAndAwaitDelivery() throws Exception {
        AtomicReference<Long> before = new AtomicReference<>();
        onMain(() -> before.set(((Number) readField(activity, "quickGeneration")).longValue()));
        launch(RecordingShortcuts.recordIntent(context));
        await("authenticated quick intent is delivered", 5_000, () -> {
            AtomicBoolean delivered = new AtomicBoolean();
            onMain(() -> delivered.set(activity != null
                    && ((Number) readField(activity, "quickGeneration")).longValue() > before.get()));
            return delivered.get();
        });
    }

    private boolean isFocused() throws Exception {
        AtomicBoolean focused = new AtomicBoolean();
        onMain(() -> focused.set(activity != null && activity.hasWindowFocus()
                && ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED));
        return focused.get();
    }

    private void awaitSettled() throws Exception {
        await("quick resolution has completed in the foreground", 15_000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onMain(() -> ready.set(activity != null && activity.hasWindowFocus()
                    && ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED
                    && ((Number) readField(activity, "quickMode")).intValue() == 0
                    && !(Boolean) readField(activity, "quickResolving")
                    && !(Boolean) readField(activity, "projectLoading")));
            return ready.get();
        });
    }

    private boolean mainBoolean(String name) throws Exception {
        AtomicBoolean value = new AtomicBoolean();
        onMain(() -> value.set(activity != null && (Boolean) readField(activity, name)));
        return value.get();
    }

    private Stage stageOf(Activity target) throws Exception {
        AtomicReference<Stage> value = new AtomicReference<>();
        onMain(() -> value.set(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(target)));
        return value.get();
    }

    private RecordingService.Snapshot awaitLockedRecording() throws Exception {
        await("real microphone enters locked RECORDING", 15_000,
                () -> RecordingService.snapshot().state == RecordingService.State.RECORDING
                        && RecordingService.snapshot().locked);
        RecordingService.Snapshot capture = RecordingService.snapshot();
        assertTrue("A real start timestamp is assigned", capture.startedAt > 0);
        assertNotNull(capture.entryId);
        acceptedRecordingToken = readField(capture, "token");
        return capture;
    }

    private void awaitRecordableDuration() throws Exception {
        await("recording contains enough genuine AAC samples", 5_000,
                () -> RecordingService.snapshot().state == RecordingService.State.RECORDING
                        && RecordingService.snapshot().elapsedMs() >= 1_100);
    }

    private void stopAndVerify(RecordingService.Snapshot capture) throws Exception {
        assertSameCapture(capture, RecordingService.snapshot());
        awaitRecordableDuration();
        onMain(() -> RecordingService.stop(context));
        await("recording finalizes without an error", 20_000,
                () -> RecordingService.snapshot().state == RecordingService.State.IDLE);
        await("original is committed to its project", 30_000, () -> !ProjectPublisher.snapshot().running);
        verifySavedAudio(capture);
    }

    private void verifySavedAudio(RecordingService.Snapshot capture) throws Exception {
        JSONObject savedEntry = entry(store.readProject(capture.projectUri), capture.entryId);
        JSONArray originals = savedEntry.getJSONArray("media");
        assertEquals("Exactly one original belongs to this quick capture", 1, originals.length());
        JSONObject audio = originals.getJSONObject(0);
        assertEquals("audio", audio.getString("kind"));
        assertEquals(Instant.ofEpochMilli(capture.startedAt).toString(), audio.getString("startedAt"));
        assertTrue(audio.getLong("durationMs") > 0);
        Uri original = store.mediaUri(capture.projectUri, audio.getString("path"));
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, original, null);
            boolean found = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                extractor.selectTrack(i);
                assertTrue("The stored original contains actual encoded audio samples",
                        extractor.readSampleData(ByteBuffer.allocate(128 * 1024), 0) > 0);
                assertTrue(extractor.getSampleTime() >= 0);
                found = true;
                break;
            }
            assertTrue("A playable audio track is present", found);
        } finally { extractor.release(); }
    }

    private void assertSameCapture(RecordingService.Snapshot expected, RecordingService.Snapshot actual) {
        assertEquals(expected.projectUri, actual.projectUri);
        assertEquals(expected.entryId, actual.entryId);
        assertEquals(expected.startedAt, actual.startedAt);
        assertEquals(expected.startedElapsedRealtime, actual.startedElapsedRealtime);
        assertEquals(readField(expected, "token"), readField(actual, "token"));
        assertEquals(RecordingService.State.RECORDING, actual.state);
        assertTrue(actual.locked);
    }

    private void assertNoCaptureFor(long durationMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + durationMs;
        do {
            assertFalse("A consumed or rejected launch must not start another microphone session", RecordingService.isBusy());
            assertEquals("Even an already-finished or failed surprise start must be detected",
                    acceptedRecordingToken, readField(RecordingService.snapshot(), "token"));
            onMain(() -> { }); // Bounded main-loop turn; never wait for global UI idle.
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
    }

    private static JSONObject entry(JSONObject project, String id) throws Exception {
        JSONArray entries = project.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (id.equals(entry.getString("id"))) return entry;
        }
        throw new AssertionError("Saved entry was not found: " + id);
    }

    private static int audioCount(JSONObject project) throws Exception {
        int count = 0;
        JSONArray entries = project.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONArray media = entries.getJSONObject(i).getJSONArray("media");
            for (int j = 0; j < media.length(); j++)
                if ("audio".equals(media.getJSONObject(j).getString("kind"))) count++;
        }
        return count;
    }

    private void finishOwnedActivities() throws Exception {
        onMain(() -> {
            for (MainActivity owned : ownedActivities)
                if (!owned.isDestroyed() && !owned.isFinishing()) owned.finish();
        });
        await("owned activities are destroyed", 10_000, () -> {
            AtomicBoolean done = new AtomicBoolean(true);
            onMain(() -> {
                for (MainActivity owned : ownedActivities)
                    if (ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(owned) != Stage.DESTROYED)
                        done.set(false);
            });
            return done.get();
        });
    }

    private void awaitOwnedReaders() throws Exception {
        List<MainActivity> closed = new ArrayList<>();
        onMain(() -> closed.addAll(ownedActivities));
        await("destroyed activities finish their owned archive readers", 15_000, () -> {
            for (MainActivity owned : closed)
                for (String name : new String[]{"io", "localIo", "quickIo"})
                    if (!((ExecutorService) readField(owned, name)).isTerminated()) return false;
            return true;
        });
    }

    private interface MainAction { void run() throws Exception; }
    private void onMain(MainAction action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) { action.run(); return; }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
            try { if (!abandoned.get()) action.run(); }
            catch (Throwable error) { failure.set(error); }
            finally { completed.countDown(); }
        }));
        if (!completed.await(5, TimeUnit.SECONDS)) {
            abandoned.set(true);
            fail("Bounded main-thread action did not complete within five seconds");
        }
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof Exception) throw (Exception) error;
        if (error != null) throw new AssertionError(error);
    }

    private interface Condition { boolean matches() throws Exception; }
    private void await(String description, long timeoutMs, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (condition.matches()) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        RecordingService.Snapshot capture = RecordingService.snapshot();
        fail("Timed out: " + description + "; recording=" + capture.state + ", error=" + capture.error
                + "; publisher=" + ProjectPublisher.snapshot().error);
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private File ownIndexFile(Uri project) throws Exception {
        assertTrue(ownedProjects.contains(project));
        String prefix = "root:" + fixtureRoot.getName() + "/";
        String id = DocumentsContract.getDocumentId(project);
        assertTrue(id.startsWith(prefix));
        File folder = new File(fixtureRoot, id.substring(prefix.length())).getCanonicalFile();
        assertEquals(fixtureRoot.getCanonicalFile(), folder.getParentFile());
        return new File(folder, "index.json");
    }

    private void clearOwnIndexCache(Uri project) throws Exception {
        assertTrue(ownedProjects.contains(project));
        File cache = new File(context.getCacheDir(), "project-index/" + uriKey(project) + ".json");
        new AtomicFile(cache).delete();
    }

    private static String uriKey(Uri project) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(project.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private void deleteOwnedPendingCaptures() throws Exception {
        File pending = new File(context.getFilesDir(), "pending-captures").getCanonicalFile();
        File[] journals = pending.listFiles((directory, name) -> name.endsWith(".json"));
        if (journals == null) return;
        Set<String> owned = new HashSet<>();
        for (Uri project : ownedProjects) owned.add(project.toString());
        for (File journal : journals) {
            JSONObject record = new JSONObject(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
            if (!owned.contains(record.optString("projectUri"))) continue;
            File original = new File(pending, record.getString("file")).getCanonicalFile();
            if (!pending.equals(original.getParentFile())) throw new IOException("Owned pending original escaped its directory");
            for (String suffix : new String[]{"", ".json", ".json.bak", ".json.new"}) {
                File file = new File(original.getPath() + suffix);
                if (file.exists() && !file.delete()) throw new IOException("Could not remove owned pending original");
            }
        }
    }

    private static void deleteWithin(File file, File root) throws IOException {
        String path = file.getCanonicalPath(), allowed = root.getCanonicalPath();
        if (!path.equals(allowed) && !path.startsWith(allowed + File.separator))
            throw new IOException("Test cleanup escaped its owned fixture");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Could not list owned fixture");
            for (File child : children) deleteWithin(child, root);
        }
        if (file.exists() && !file.delete()) throw new IOException("Could not remove owned fixture");
    }

    private static void restore(SharedPreferences target, Map<String, ?> snapshot) {
        SharedPreferences.Editor edit = target.edit().clear();
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) edit.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) edit.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) edit.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) edit.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) edit.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                edit.putStringSet(entry.getKey(), strings);
            }
        }
        assertTrue(edit.commit());
    }
}
