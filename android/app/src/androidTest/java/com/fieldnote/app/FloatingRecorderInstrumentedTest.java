package com.fieldnote.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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

/** Actual overlay input, MediaRecorder and Camera2; not evidence about a physical phone's sensors. */
@RunWith(AndroidJUnit4.class)
public final class FloatingRecorderInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private ProjectStore store;
    private SharedPreferences preferences, bookmarks;
    private Map<String, ?> priorPreferences, priorBookmarks;
    private String priorOverlayMode;
    private File fixtureRoot;
    private Uri fixtureTree, project;
    private final List<Uri> ownedProjects = new ArrayList<>();
    // Lifecycle callbacks and the owned list are used only on the main thread.
    private final List<MainActivity> ownedActivities = new ArrayList<>();
    private volatile MainActivity activity;
    private ActivityLifecycleCallback lifecycle;
    private TouchPath touch;
    private boolean setupAdmitted;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertFalse("Do not take over another recording", RecordingService.isBusy());
        assertFalse("Do not take over another floating recorder", FloatingRecorderService.isShowing());
        setupAdmitted = true;
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        bookmarks = context.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        // Keep an established launcher capability valid when the full preference map is restored.
        assertTrue(RecordingShortcuts.isOverlayIntent(context, RecordingShortcuts.overlayIntent(context)));
        priorPreferences = preferences.getAll();
        priorBookmarks = bookmarks.getAll();
        assertTrue(preferences.edit().remove("floating_text_draft").commit());
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.ACCESS_COARSE_LOCATION);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        String appOps = shell("appops get " + ownPackage() + " SYSTEM_ALERT_WINDOW");
        Matcher mode = Pattern.compile("(?m)^\\s*SYSTEM_ALERT_WINDOW:\\s*(allow|ignore|deny|default|foreground)\\b").matcher(appOps);
        priorOverlayMode = mode.find() ? mode.group(1) : "default";
        shell("appops set " + ownPackage() + " SYSTEM_ALERT_WINDOW allow");
        await("the owned application receives overlay permission", 5_000, () -> Settings.canDrawOverlays(context));
        TestDocumentsProvider.clearFailures();
        store = new ProjectStore(context);
        ProjectPublisher.retry(context);
        await("previous queue work becomes idle", 30_000, () -> !ProjectPublisher.snapshot().running);
        File base = TestDocumentsProvider.rootDirectory(context);
        assertTrue(base.isDirectory() || base.mkdirs());
        fixtureRoot = new File(base, "floating-native-" + UUID.randomUUID());
        assertTrue(fixtureRoot.mkdir());
        fixtureTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root:" + fixtureRoot.getName());
        project = createProject("Floating native capture");
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
        if (!setupAdmitted) return;
        try {
            TestDocumentsProvider.clearFailures();
            if (touch != null && touch.down) touch.cancel();
            if (context != null) onMain(() -> {
                FloatingRecorderService.hide(context);
                if (ownedProjects.contains(RecordingService.snapshot().projectUri) && RecordingService.isBusy())
                    RecordingService.stop(context);
            });
            await("owned overlay and recording finish before cleanup", 25_000,
                    () -> activeService() == null && !RecordingService.isBusy());
            if (context != null) {
                ProjectPublisher.retry(context);
                await("published originals leave the worker before cleanup", 30_000,
                        () -> !ProjectPublisher.snapshot().running);
                onMain(() -> {
                    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
                    if (scheduler != null) scheduler.cancel(ProjectPublisher.JOB_ID);
                });
                onMain(() -> { });
                await("a cancelled late job unwinds before cleanup", 10_000,
                        () -> !ProjectPublisher.snapshot().running);
            }
            finishOwnedActivities();
            deleteOwnedPending();
            for (Uri owned : ownedProjects) {
                File publications = new File(context.getFilesDir(), "archive-publications").getCanonicalFile();
                File directory = new File(publications, sha256(owned.toString().getBytes(StandardCharsets.UTF_8))).getCanonicalFile();
                assertEquals(publications, directory.getParentFile());
                deleteTree(directory, directory);
            }
            if (fixtureRoot != null) deleteTree(fixtureRoot, fixtureRoot);
        } finally {
            if (lifecycle != null)
                onMain(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle));
            if (bookmarks != null && priorBookmarks != null) restore(bookmarks, priorBookmarks);
            if (preferences != null && priorPreferences != null) restore(preferences, priorPreferences);
            if (priorOverlayMode != null) shell("appops set " + ownPackage() + " SYSTEM_ALERT_WINDOW " + priorOverlayMode);
            TestDocumentsProvider.clearFailures();
            // Runtime permission revocation can terminate the in-process runner. As in
            // NativeCaptureInstrumentedTest, these grants stay on the isolated debug install.
        }
    }

    @Test public void shortcutOverlayOutlivesMainAndHeldAudioUsesLastCreatedProject() throws Exception {
        Uri previouslySelected = project;
        project = createProject("Last created floating destination");
        store.setCurrentProject(previouslySelected);
        launchOverlay();
        saveScreenshot("beta16-overlay-idle.png");
        assertEquals(project, onMainValue(() -> (Uri) field(requireService(), "project")));
        RecordingService.Snapshot started = holdAudio();
        saveScreenshot("beta16-overlay-hold.png");
        assertEquals(project, started.projectUri);
        touch.up(0, 0);
        JSONObject index = awaitPublished(1, 0, 0);
        assertEquals(0, store.readProject(previouslySelected).getJSONArray("entries").length());
        assertSingleEntry(index, started.entryId, 1);
        assertRealOriginals(index);
        assertTrue(FloatingRecorderService.isShowing());
        assertMainBackground();
    }

    @Test public void originalFloatingPointerReleaseStopsHeldAudioWhileSecondFingerRemains() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.addSecond();
        touch.liftOwner();
        assertFalse("Lifting the original finger ends the floating hold immediately",
                onMainValue(() -> (boolean) field(requireService(), "touching")));
        await("owner release finalizes audio without waiting for the other finger", 15_000,
                () -> !RecordingService.isBusy());
        touch.move(-128, 0);
        assertFalse("The remaining finger cannot take over camera gestures", overlayCameraBusy());
        assertFalse("The remaining finger cannot start another recording", RecordingService.isBusy());
        touch.up(-128, 0);
        JSONObject index = awaitPublished(1, 0, 0);
        assertSingleEntry(index, started.entryId, 1);
        assertRealOriginals(index);
    }

    @Test public void secondaryFloatingPointerReleaseKeepsOwnerAndExplicitLockSurvivesOwnerRelease() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.addSecond();
        touch.liftSecond();
        assertTrue("Lifting only the additional finger keeps the original hold",
                onMainValue(() -> (boolean) field(requireService(), "touching")));
        assertHeldIdentity(started, false);
        touch.move(0, -128);
        await("the original pointer can still explicitly lock", 5_000,
                () -> RecordingService.snapshot().locked);
        touch.addSecond();
        touch.liftOwner();
        assertFalse("An explicit lock does not transfer gesture ownership to another finger",
                onMainValue(() -> (boolean) field(requireService(), "touching")));
        assertHeldIdentity(started, true);
        touch.move(0, 0);
        touch.up(0, 0);
        assertHeldIdentity(started, true);
        awaitMicHome();
        touch = new TouchPath();
        touch.press();
        touch.up(0, 0);
        JSONObject index = awaitPublished(1, 0, 0);
        assertSingleEntry(index, started.entryId, 1);
        assertRealOriginals(index);
    }

    @Test public void draggingFloatingHandleMovesIdleMicAndRestoresPositionAfterReopening() throws Exception {
        launchOverlay();
        float[] original = microphoneCenter();
        dragFloatingHandle();
        float[] moved = microphoneCenter();
        assertTrue("The handle repositions the physical mic within its safe bounds",
                Math.hypot(moved[0] - original[0], moved[1] - original[1]) > 20 * moved[2]);
        assertFalse("Moving the idle control does not start audio", RecordingService.isBusy());
        closeOverlayFromMic();
        launchOverlay();
        awaitMicHome();
        float[] restored = microphoneCenter();
        assertEquals("Reopening restores the horizontal position", moved[0], restored[0], 2f);
        assertEquals("Reopening restores the vertical position", moved[1], restored[1], 2f);
        assertEquals(0, store.readProject(project).getJSONArray("entries").length());
        saveScreenshot("beta23-floating-moved.png");
    }

    @Test public void draggingFloatingHandleKeepsTheSameLockedNarration() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(0, -128);
        touch.up(0, -128);
        awaitMicHome();
        assertHeldIdentity(started, true);
        float[] original = microphoneCenter();
        dragFloatingHandle();
        float[] moved = microphoneCenter();
        assertTrue("A locked recording's control remains movable",
                Math.hypot(moved[0] - original[0], moved[1] - original[1]) > 20 * moved[2]);
        assertHeldIdentity(started, true);
        closeOverlayFromMic();
        JSONObject index = awaitPublished(1, 0, 0);
        assertSingleEntry(index, started.entryId, 1);
        assertRealOriginals(index);
    }

    @Test public void secondFingerOnMoveHandleCannotStealAnActiveMicHold() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        float[] original = microphoneCenter();
        float[] handle = overlayViewCenter(R.id.floating_move_handle);
        touch.addSecondAt((handle[0] - touch.x) / touch.density, (handle[1] - touch.y) / touch.density);
        touch.moveSecond(-40, -120);
        touch.liftSecond();
        assertHeldIdentity(started, false);
        float[] after = microphoneCenter();
        assertEquals("The extra finger cannot reposition an owned hold", original[0], after[0], 2f);
        assertEquals("The extra finger cannot reposition an owned hold", original[1], after[1], 2f);
        assertTrue("The microphone retains its original pointer owner",
                onMainValue(() -> (boolean) field(requireService(), "touching")));
        touch.up(0, 0);
        JSONObject index = awaitPublished(1, 0, 0);
        assertSingleEntry(index, started.entryId, 1);
        assertRealOriginals(index);
    }

    @Test public void southeastCloseStopsExplicitlyLockedNarrationAndRemovesOverlay() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(0, -128);
        touch.up(0, -128);
        awaitMicHome();
        assertHeldIdentity(started, true);
        closeOverlayFromMic(true);
        JSONObject index = awaitPublished(1, 0, 0);
        assertSingleEntry(index, started.entryId, 1);
        assertRealOriginals(index);
        assertFalse("Close must not leave a hidden locked microphone", RecordingService.isBusy());
    }

    @Test public void serviceStopFinalizesVideoLinkedToTheSameHeldNarration() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(128, 0);
        awaitVideoFrames();
        saveScreenshot("beta23-floating-purple-video.png");
        onMain(() -> RecordingService.stop(context));
        // Do not release the finger first: widget/notification Stop must end the linked video itself.
        await("external audio Stop also finalizes its linked overlay video", 25_000,
                () -> !RecordingService.isBusy() && !overlayCameraBusy());
        touch.cancel();
        JSONObject index = awaitPublished(1, 0, 1);
        assertSingleEntry(index, started.entryId, 2);
        assertRealOriginals(index);
    }

    @Test public void southeastCloseAfterVideoCenterKeepsLinkedAudioAndVideo() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(128, 0);
        awaitVideoFrames();
        touch.move(0, 0);
        touch.move(90, 90);
        await("the close gesture ends the overlay and its held narration", 25_000,
                () -> activeService() == null && !RecordingService.isBusy());
        touch.cancel();
        JSONObject index = awaitPublished(1, 0, 1);
        assertSingleEntry(index, started.entryId, 2);
        assertRealOriginals(index);
        assertFalse(FloatingRecorderService.isShowing());
    }

    @Test public void foreignProjectNarrationAllowsOnlyClosingTheFloatingControl() throws Exception {
        Uri overlayProject = project;
        launchOverlay();
        Uri narrationProject = createProject("Narration outside the floating project");
        onMain(() -> context.startActivity(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        await("the owned Activity is visible to start the other project's narration", 10_000,
                () -> onMainValue(() -> activity != null && activity.hasWindowFocus()
                        && ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED));
        onMain(() -> {
            RecordingService.start(activity, narrationProject, UUID.randomUUID().toString());
            RecordingService.lock(activity);
        });
        await("the other project records real narration", 15_000,
                () -> RecordingService.snapshot().state == RecordingService.State.RECORDING
                        && RecordingService.snapshot().elapsedMs() >= 700);
        RecordingService.Snapshot started = RecordingService.snapshot();
        assertEquals(narrationProject, started.projectUri);
        onMain(() -> activity.moveTaskToBack(true));
        await("the launcher receives the floating control again", 10_000,
                () -> onMainValue(() -> ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.STOPPED));
        assertEquals(overlayProject, onMainValue(() -> (Uri) field(requireService(), "project")));
        awaitMicHome();
        touch = new TouchPath();
        touch.press();
        touch.up(0, 0);
        assertHeldIdentity(started, true);

        awaitMicHome();
        touch = new TouchPath();
        touch.press();
        for (float[] direction : new float[][]{{-128, 0}, {128, 0}, {0, 128}, {90, -90}, {0, -128}}) {
            touch.move(direction[0], direction[1]);
            touch.move(0, 0);
        }
        assertHeldIdentity(started, true);
        assertEquals("Denied gestures do not add markers to the other original", 0, RecordingService.snapshot().markerCount);
        assertFalse("Denied gestures do not open the camera", overlayCameraBusy());
        assertNull("Denied gestures do not open text entry", onMainValue(() -> field(requireService(), "textDialog")));
        touch.move(90, 90);
        await("down-right closes this overlay independently of the other project's audio", 15_000,
                () -> activeService() == null);
        touch.cancel();
        assertHeldIdentity(started, true);
        assertFalse(FloatingRecorderService.isShowing());
        assertEquals("The unrelated overlay project stays empty", 0, store.readProject(overlayProject).getJSONArray("entries").length());
        onMain(() -> RecordingService.stop(context));
        project = narrationProject;
        JSONObject index = awaitPublished(1, 0, 0);
        assertSingleEntry(index, started.entryId, 1);
        assertRealOriginals(index);
    }

    @Test public void southeastCloseFinalizesAnActiveStandaloneVideoWithEmbeddedAudio() throws Exception {
        launchOverlay();
        awaitMicHome();
        touch = new TouchPath();
        touch.quickRightSwipe();
        awaitVideoFrames();
        assertFalse(RecordingService.isBusy());
        FloatingRecorderService owner = onMainValue(this::requireService);
        InlineCamera camera = onMainValue(() -> (InlineCamera) field(owner, "camera"));
        HandlerThread cameraThread = (HandlerThread) field(camera, "thread");
        assertTrue(onMainValue(camera::isRecording));
        closeOverlayFromMic();
        await("Close releases the active Camera2 worker", 15_000,
                () -> !cameraThread.isAlive() && onMainValue(() -> !camera.isBusy()));
        JSONObject index = awaitPublished(0, 0, 1);
        assertEquals(1, index.getJSONArray("entries").length());
        assertRealOriginals(index);
        JSONObject video = mediaOfKind(index, "video").getJSONObject(0);
        assertEncodedMedia(store.mediaUri(project, video.getString("path")), true);
        assertFalse(FloatingRecorderService.isShowing());
    }

    @Test public void centerPhotoCenterPhotoKeepsOneAudioAndTwoOriginalPhotos() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudioAt(32);
        assertHeldIdentity(started, false);
        // Outside the 28dp home zone, an ordinary hold must still start narration.
        // Returning to the physical center then arms its media gestures.
        touch.move(0, 0);
        touch.move(-128, 0);
        awaitPublished(0, 1, 0);
        assertHeldIdentity(started, false);
        touch.move(0, 0);
        touch.move(-128, 0);
        awaitPublished(0, 2, 0);
        assertHeldIdentity(started, false);
        touch.move(0, 0);
        touch.up(0, 0);
        JSONObject index = awaitPublished(1, 2, 0);
        assertSingleEntry(index, started.entryId, 3);
        JSONArray photos = mediaOfKind(index, "photo");
        assertNotEquals(photos.getJSONObject(0).getString("id"), photos.getJSONObject(1).getString("id"));
        assertNotEquals(photos.getJSONObject(0).getString("path"), photos.getJSONObject(1).getString("path"));
        assertRealOriginals(index);
    }

    @Test public void videoCenterPhotoKeepsNarrationUntilFinalRelease() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(128, 0);
        awaitVideoFrames();
        assertHeldIdentity(started, false);
        touch.move(0, 0);
        awaitPublished(0, 0, 1);
        await("video returns the overlay camera to idle", 10_000, () -> !overlayCameraBusy());
        assertHeldIdentity(started, false);
        touch.move(-128, 0);
        awaitPublished(0, 1, 1);
        assertHeldIdentity(started, false);
        touch.up(-128, 0);
        JSONObject index = awaitPublished(1, 1, 1);
        assertSingleEntry(index, started.entryId, 3);
        assertRealOriginals(index);
    }

    @Test public void upwardLockAndDiagonalMarkerSurviveReleaseUntilCenterTapStops() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(0, -128);
        touch.up(0, -128);
        await("up locks the same original after release", 5_000,
                () -> RecordingService.isRecording() && RecordingService.snapshot().locked);
        assertHeldIdentity(started, true);
        awaitMicHome();
        touch = new TouchPath();
        touch.press();
        assertTrue("The settled microphone receives the next hold after locking",
                onMainValue(() -> (Boolean) field(requireService(), "touching")));
        touch.move(90, -90);
        await("the diagonal gesture durably journals one marker", 5_000,
                () -> RecordingService.snapshot().markerCount == 1);
        long markedAt = RecordingService.snapshot().lastMarkerOffsetMs;
        touch.up(90, -90);
        assertHeldIdentity(started, true);
        awaitMicHome();
        touch = new TouchPath();
        touch.press();
        touch.up(0, 0);
        JSONObject index = awaitPublished(1, 0, 0);
        assertSingleEntry(index, started.entryId, 1);
        JSONObject audio = mediaOfKind(index, "audio").getJSONObject(0);
        JSONArray markers = audio.getJSONArray("markers");
        assertEquals(1, markers.length());
        assertEquals(audio.getString("id"), markers.getJSONObject(0).getString("mediaId"));
        assertEquals(markedAt, markers.getJSONObject(0).getLong("offsetMs"));
        assertTrue(markedAt >= 0 && markedAt <= audio.getLong("durationMs"));
        assertRealOriginals(index);
    }

    @Test public void downwardTextSubmissionLinksTextAndStopsItsAudio() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(0, 128);
        await("down opens the overlay text editor", 10_000,
                () -> onMainValue(() -> {
                    AlertDialog dialog = (AlertDialog) field(requireService(), "textDialog");
                    return dialog != null && dialog.isShowing() && dialog.findViewById(R.id.note_text_input) != null;
                }));
        saveScreenshot("beta16-overlay-text.png");
        touch.up(0, 128);
        assertHeldIdentity(started, true);
        String note = "Floating apparatus observation\nValve A is open.";
        onMain(() -> {
            AlertDialog dialog = (AlertDialog) field(requireService(), "textDialog");
            ((EditText) dialog.findViewById(R.id.note_text_input)).setText(note);
            assertTrue(dialog.findViewById(R.id.note_save).performClick());
        });
        await("text submission closes the editor and stops only its capture", 20_000,
                () -> !RecordingService.isBusy() && onMainValue(() -> field(requireService(), "textDialog") == null));
        JSONObject index = awaitPublished(1, 0, 0);
        await("text and audio reach the same portable entry", 15_000,
                () -> note.equals(store.readProject(project).getJSONArray("entries").getJSONObject(0).optString("text")));
        index = store.readProject(project);
        assertSingleEntry(index, started.entryId, 1);
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(note, entry.getString("text"));
        assertNotNull(Instant.parse(entry.getString("textCreatedAt")));
        assertNotNull(Instant.parse(entry.getJSONObject("textMetadata").getString("observedAt")));
        assertFalse(preferences.contains("floating_text_draft"));
        assertRealOriginals(index);
    }

    @Test public void hidingDuringVideoFinishesOwnedOriginalsAndReleasesCameraWithoutRestart() throws Exception {
        launchOverlay();
        RecordingService.Snapshot started = holdAudio();
        touch.move(128, 0);
        awaitVideoFrames();
        FloatingRecorderService owner = onMainValue(this::requireService);
        InlineCamera camera = onMainValue(() -> (InlineCamera) field(owner, "camera"));
        HandlerThread cameraThread = (HandlerThread) field(camera, "thread");
        onMain(() -> FloatingRecorderService.hide(context));
        await("Hide finishes the overlay and its unlocked audio", 25_000,
                () -> activeService() == null && !RecordingService.isBusy());
        // Hide must finish capture before the finger is lifted. Window removal
        // cancels only that target; system gesture monitors still own this injected
        // device's down state, so terminate the original pointer sequence explicitly.
        touch.cancel();
        await("Hide releases the actual Camera2 worker", 15_000,
                () -> !cameraThread.isAlive() && onMainValue(() -> !camera.isBusy()));
        JSONObject index = awaitPublished(1, 0, 1);
        assertSingleEntry(index, started.entryId, 2);
        assertRealOriginals(index);
        long until = SystemClock.elapsedRealtime() + 1_000L;
        do {
            assertFalse(FloatingRecorderService.isShowing());
            assertFalse(onMainValue(FloatingRecorderService::isBusyForHandoff));
            assertFalse(RecordingService.isBusy());
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        assertEquals(index.toString(), store.readProject(project).toString());
    }

    @Test public void quickStandaloneVideoSwipeContinuesAfterReleaseAndSavesItsOwnAudioTrack() throws Exception {
        launchOverlay();
        awaitMicHome();
        touch = new TouchPath();
        touch.quickRightSwipe();
        awaitVideoFrames();
        assertFalse("A quick video gesture must not also start a separate narration file", RecordingService.isBusy());
        assertTrue("Video keeps recording after the standalone swipe was released",
                onMainValue(() -> ((InlineCamera) field(requireService(), "camera")).isRecording()));
        onMain(() -> {
            View preview = (View) field(requireService(), "preview");
            View stop = preview.findViewById(InlineCamera.STOP_VIDEO_ID);
            assertNotNull(stop);
            assertTrue(stop.isShown() && stop.isEnabled());
            assertTrue("The native preview's explicit stop ends standalone video", stop.performClick());
        });
        JSONObject index = awaitPublished(0, 0, 1);
        assertEquals(1, index.getJSONArray("entries").length());
        assertRealOriginals(index);
        JSONObject video = mediaOfKind(index, "video").getJSONObject(0);
        assertEncodedMedia(store.mediaUri(project, video.getString("path")), true);
        assertFalse(RecordingService.isBusy());
        await("standalone video releases the camera", 10_000, () -> !overlayCameraBusy());
    }

    private Uri createProject(String name) throws IOException {
        Uri created = store.createProject(fixtureTree, name);
        ownedProjects.add(created);
        return created;
    }

    private void launchOverlay() throws Exception {
        onMain(() -> context.startActivity(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        await("Main has loaded the selected project", 15_000,
                () -> onMainValue(() -> activity != null && activity.hasWindowFocus()
                        && ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED
                        && field(activity, "project") != null && !(Boolean) field(activity, "projectLoading")));
        onMain(() -> activity.startActivity(RecordingShortcuts.overlayIntent(activity)));
        await("the real overlay attaches over the launcher", 15_000,
                () -> FloatingRecorderService.isShowing() && onMainValue(() -> {
                    FloatingRecorderService service = activeService();
                    if (service == null) return false;
                    View microphone = (View) field(service, "microphone");
                    return microphone != null && microphone.isAttachedToWindow() && microphone.getWidth() > 0;
                }));
        await("Main backgrounds without destroying the floating recorder", 10_000,
                () -> onMainValue(() -> ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.STOPPED));
        assertMainBackground();
    }

    private void assertMainBackground() throws Exception {
        assertEquals(Stage.STOPPED, onMainValue(() -> ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)));
        assertTrue(FloatingRecorderService.isShowing());
    }

    private RecordingService.Snapshot holdAudio() throws Exception {
        return holdAudioAt(0);
    }

    private float[] microphoneCenter() throws Exception { return overlayViewCenter(R.id.record_button); }

    private float[] overlayViewCenter(int id) throws Exception {
        return onMainValue(() -> {
            View surface = (View) field(requireService(), "surface");
            View target = surface.findViewById(id);
            assertNotNull("The overlay exposes the requested native control", target);
            assertTrue(target.isShown() && target.getWidth() > 0 && target.getHeight() > 0);
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            return new float[]{location[0] + target.getWidth() * target.getScaleX() / 2f,
                    location[1] + target.getHeight() * target.getScaleY() / 2f,
                    target.getResources().getDisplayMetrics().density};
        });
    }

    private void dragFloatingHandle() throws Exception {
        awaitMicHome();
        float[] position = microphoneCenter();
        float dx = position[0] > context.getResources().getDisplayMetrics().widthPixels / 2f ? -50 : 50;
        float dy = position[1] > context.getResources().getDisplayMetrics().heightPixels / 2f ? -100 : 100;
        touch = new TouchPath(R.id.floating_move_handle);
        touch.press();
        assertTrue("The settled move handle receives its own pointer",
                onMainValue(() -> (Boolean) field(requireService(), "moving")));
        long pressed = SystemClock.elapsedRealtime();
        await("handle hold exceeds the microphone's recording delay", 2_000,
                () -> SystemClock.elapsedRealtime() - pressed >= 300);
        touch.move(dx, dy);
        touch.up(dx, dy);
        await("dragged overlay layout reaches its new physical position", 5_000, () -> {
            float[] moved = microphoneCenter();
            return Math.hypot(moved[0] - position[0], moved[1] - position[1]) > 20 * moved[2];
        });
        awaitMicHome();
    }

    private void closeOverlayFromMic() throws Exception {
        closeOverlayFromMic(false);
    }

    private void closeOverlayFromMic(boolean inspectGuide) throws Exception {
        awaitMicHome();
        touch = new TouchPath();
        if (inspectGuide) {
            touch.press();
            saveScreenshot("beta23-floating-guide-center.png");
            touch.move(65, 65);
            assertTrue("Approaching the close target must keep the overlay open", FloatingRecorderService.isShowing());
            saveScreenshot("beta23-floating-close-guide.png");
            touch.move(90, 90);
            touch.cancel();
        } else touch.quickSoutheastSwipe();
        await("southeast close finishes the overlay and its recording", 25_000,
                () -> activeService() == null && !RecordingService.isBusy());
        touch.cancel();
        assertFalse(FloatingRecorderService.isShowing());
    }

    private RecordingService.Snapshot holdAudioAt(float xOffsetDp) throws Exception {
        awaitMicHome();
        touch = new TouchPath();
        touch.pressAt(xOffsetDp, 0);
        await("holding the physical overlay starts real narration", 15_000,
                () -> RecordingService.snapshot().state == RecordingService.State.RECORDING && RecordingService.snapshot().elapsedMs() >= 700);
        RecordingService.Snapshot started = RecordingService.snapshot();
        assertEquals(project, started.projectUri);
        assertFalse(started.locked);
        assertNull(started.error);
        return started;
    }

    private void assertHeldIdentity(RecordingService.Snapshot started, boolean locked) {
        RecordingService.Snapshot current = RecordingService.snapshot();
        assertEquals(RecordingService.State.RECORDING, current.state);
        assertEquals(started.projectUri, current.projectUri);
        assertEquals(started.entryId, current.entryId);
        assertEquals(started.startedAt, current.startedAt);
        assertEquals(started.startedElapsedRealtime, current.startedElapsedRealtime);
        assertEquals(locked, current.locked);
        assertNull(current.error);
    }

    private void awaitVideoFrames() throws Exception {
        await("the real overlay camera starts video", 20_000,
                () -> onMainValue(() -> ((InlineCamera) field(requireService(), "camera")).isRecording()));
        long began = SystemClock.elapsedRealtime();
        await("video records enough time to contain real encoded frames", 3_000,
                () -> SystemClock.elapsedRealtime() - began >= 1_200);
    }

    private boolean overlayCameraBusy() throws Exception {
        return onMainValue(() -> {
            FloatingRecorderService service = activeService();
            return service != null && ((InlineCamera) field(service, "camera")).isBusy();
        });
    }

    private void awaitMicHome() throws Exception {
        await("the overlay microphone returns home and its compact window finishes layout", 5_000,
                () -> onMainValue(() -> {
                    FloatingRecorderService service = activeService();
                    if (service == null) return false;
                    View mic = (View) field(service, "microphone");
                    View surface = (View) field(service, "surface");
                    WindowManager.LayoutParams window = (WindowManager.LayoutParams) field(service, "micWindow");
                    float density = context.getResources().getDisplayMetrics().density;
                    // The 230ms home animation ends before the 240ms contraction.
                    // Sampling coordinates then can race Android's window/input relayout.
                    boolean compactLayout = window != null && surface != null
                            && window.width == Math.round(96 * density) && window.height == Math.round(160 * density)
                            && surface.getWidth() == window.width && surface.getHeight() == window.height
                            && !surface.isLayoutRequested();
                    return compactLayout && mic != null && mic.isAttachedToWindow() && !mic.isPressed()
                            && Math.abs(mic.getTranslationX()) < 1 && Math.abs(mic.getTranslationY()) < 1
                            && Math.abs(mic.getScaleX() - 1f) < .01f && Math.abs(mic.getScaleY() - 1f) < .01f;
                }));
    }

    private JSONObject awaitPublished(int audio, int photos, int videos) throws Exception {
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        await("the project ZIP contains " + audio + " audio, " + photos + " photo and " + videos + " video originals", 30_000, () -> {
            JSONObject index = store.readProject(project);
            if (mediaOfKind(index, "audio").length() != audio || mediaOfKind(index, "photo").length() != photos
                    || mediaOfKind(index, "video").length() != videos) return false;
            saved.set(index);
            return true;
        });
        return saved.get();
    }

    private void assertSingleEntry(JSONObject index, String entryId, int originals) throws Exception {
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(entryId, entry.getString("id"));
        assertEquals(originals, entry.getJSONArray("media").length());
    }

    private JSONArray mediaOfKind(JSONObject index, String kind) throws Exception {
        JSONArray result = new JSONArray();
        JSONArray entries = index.getJSONArray("entries");
        for (int e = 0; e < entries.length(); e++) {
            JSONArray media = entries.getJSONObject(e).getJSONArray("media");
            for (int m = 0; m < media.length(); m++)
                if (kind.equals(media.getJSONObject(m).getString("kind"))) result.put(media.getJSONObject(m));
        }
        return result;
    }

    private void assertRealOriginals(JSONObject index) throws Exception {
        JSONArray entries = index.getJSONArray("entries");
        for (int e = 0; e < entries.length(); e++) {
            JSONArray media = entries.getJSONObject(e).getJSONArray("media");
            for (int m = 0; m < media.length(); m++) {
                JSONObject original = media.getJSONObject(m);
                String kind = original.getString("kind");
                Uri uri = store.mediaUri(project, original.getString("path"));
                byte[] bytes = readAll(context.getContentResolver().openInputStream(uri));
                assertEquals(original.getLong("bytes"), bytes.length);
                assertEquals(original.getString("sha256"), sha256(bytes));
                long start = Instant.parse(original.getString("startedAt")).toEpochMilli();
                long end = Instant.parse(original.getString("endedAt")).toEpochMilli();
                assertEquals(end - start, original.getLong("durationMs"));
                if ("photo".equals(kind)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    assertEquals("image/jpeg", options.outMimeType);
                    assertTrue(options.outWidth > 0 && options.outHeight > 0);
                } else assertEncodedMedia(uri, "audio".equals(kind));
            }
        }
    }

    private void assertEncodedMedia(Uri uri, boolean audio) throws Exception {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(context, uri);
            assertEquals("yes", metadata.extractMetadata(audio ? MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO : MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            assertTrue(Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) > 0);
            if (!audio) {
                Bitmap frame = metadata.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                assertNotNull("The MP4 must decode a camera frame", frame);
                frame.recycle();
            }
        } finally { metadata.release(); }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            boolean found = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith(audio ? "audio/" : "video/")) {
                    extractor.selectTrack(i);
                    assertTrue("The original track contains actual encoded samples",
                            extractor.readSampleData(ByteBuffer.allocate(4 * 1024 * 1024), 0) > 0);
                    found = true;
                    break;
                }
            }
            assertTrue("The original has the requested encoded media track", found);
        } finally { extractor.release(); }
    }

    private final class TouchPath {
        final float x, y, density;
        final long began;
        boolean down;
        boolean secondDown;
        int soloId;
        float lastX, lastY;
        float secondX, secondY;
        TouchPath() throws Exception { this(R.id.record_button); }
        TouchPath(int viewId) throws Exception {
            float[] position = onMainValue(() -> {
                View surface = (View) field(requireService(), "surface");
                View mic = surface.findViewById(viewId);
                assertNotNull("The requested overlay touch target exists", mic);
                int[] location = new int[2];
                mic.getLocationOnScreen(location);
                return new float[]{location[0] + mic.getWidth() / 2f, location[1] + mic.getHeight() / 2f,
                        mic.getResources().getDisplayMetrics().density};
            });
            x = position[0]; y = position[1]; density = position[2];
            began = SystemClock.uptimeMillis();
        }
        void press() { pressAt(0, 0); }
        void pressAt(float dx, float dy) { down = true; send(MotionEvent.ACTION_DOWN, dx, dy); }
        void move(float dx, float dy) { send(MotionEvent.ACTION_MOVE, dx, dy); }
        void up(float dx, float dy) { send(MotionEvent.ACTION_UP, dx, dy); down = false; }
        void cancel() {
            if (!down) return;
            if (secondDown) sendPair(MotionEvent.ACTION_CANCEL);
            else send(MotionEvent.ACTION_CANCEL, lastX, lastY);
            down = secondDown = false;
        }
        void addSecond() {
            addSecondAt(lastX + 12, lastY);
        }
        void addSecondAt(float dx, float dy) {
            assertTrue(down && !secondDown && soloId == 0);
            secondX = dx;
            secondY = dy;
            sendPair(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            secondDown = true;
        }
        void moveSecond(float dx, float dy) {
            assertTrue(secondDown);
            secondX = dx;
            secondY = dy;
            sendPair(MotionEvent.ACTION_MOVE);
        }
        void liftOwner() {
            assertTrue(secondDown);
            sendPair(MotionEvent.ACTION_POINTER_UP);
            secondDown = false;
            soloId = 1;
            lastX = secondX;
            lastY = secondY;
        }
        void liftSecond() {
            assertTrue(secondDown);
            sendPair(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            secondDown = false;
        }
        private void sendPair(int action) {
            MotionEvent.PointerProperties[] pointers = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] points = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                pointers[i] = new MotionEvent.PointerProperties();
                pointers[i].id = i;
                pointers[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                points[i] = new MotionEvent.PointerCoords();
                points[i].x = x + (i == 0 ? lastX : secondX) * density;
                points[i].y = y + (i == 0 ? lastY : secondY) * density;
                points[i].pressure = 1;
                points[i].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(began, SystemClock.uptimeMillis(), action, 2,
                    pointers, points, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { assertTrue("Android accepts both fingers on the floating microphone",
                    instrumentation.getUiAutomation().injectInputEvent(event, true)); }
            finally { event.recycle(); }
        }
        void quickRightSwipe() { quickSwipe(128, 0); }
        void quickSoutheastSwipe() { quickSwipe(90, 90); }
        private void quickSwipe(float dx, float dy) {
            down = true;
            // Queue the complete input path before the host's 180ms hold timer;
            // no UI-idle wait is inserted between the physical pointer events.
            send(MotionEvent.ACTION_DOWN, 0, 0, false);
            send(MotionEvent.ACTION_MOVE, dx, dy, false);
            send(MotionEvent.ACTION_UP, dx, dy, false);
            down = false;
        }
        private void send(int action, float dx, float dy) {
            send(action, dx, dy, true);
        }
        private void send(int action, float dx, float dy, boolean sync) {
            lastX = dx; lastY = dy;
            MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
            pointer.id = soloId; pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
            point.x = x + dx * density; point.y = y + dy * density; point.pressure = 1; point.size = 1;
            MotionEvent event = MotionEvent.obtain(began, SystemClock.uptimeMillis(), action, 1,
                    new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{point},
                    0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { assertTrue("Android input dispatch accepts the actual overlay pointer event", instrumentation.getUiAutomation().injectInputEvent(event, sync)); }
            finally { event.recycle(); }
        }
    }

    private FloatingRecorderService activeService() { return (FloatingRecorderService) staticField(FloatingRecorderService.class, "active"); }
    private FloatingRecorderService requireService() {
        FloatingRecorderService service = activeService();
        assertNotNull("The floating service is attached", service);
        return service;
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Native ownership contract changed: " + name, failure); }
    }
    private static Object staticField(Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Native ownership contract changed: " + name, failure); }
    }
    private void onMain(Action action) throws Exception { onMainValue(() -> { action.run(); return null; }); }
    private void finishOwnedActivities() throws Exception {
        onMain(() -> {
            for (MainActivity owned : ownedActivities)
                if (!owned.isDestroyed() && !owned.isFinishing()) owned.finish();
        });
        await("owned activities are destroyed", 10_000, () -> onMainValue(() -> {
            for (MainActivity owned : ownedActivities)
                if (ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(owned) != Stage.DESTROYED)
                    return false;
            return true;
        }));
        List<MainActivity> closed = onMainValue(() -> new ArrayList<>(ownedActivities));
        await("owned activity archive readers stop before fixture cleanup", 15_000, () -> {
            for (MainActivity owned : closed)
                for (String name : new String[]{"io", "localIo", "quickIo"})
                    if (!((ExecutorService) field(owned, name)).isTerminated()) return false;
            return true;
        });
    }
    private void saveScreenshot(String filename) throws IOException {
        // Dialog.show() precedes its compositor frame and keyboard animation.
        // This bounded settling delay is for review images, not behavioral readiness.
        SystemClock.sleep(600);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Android returns an actual compositor screenshot", bitmap);
        File destination = context.getExternalFilesDir(null);
        assertNotNull(destination);
        try (FileOutputStream output = new FileOutputStream(new File(destination, filename))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
    private <T> T onMainValue(Value<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.get();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
            try { if (!abandoned.get()) result.set(action.get()); }
            catch (Throwable error) { failure.set(error); }
            finally { delivered.countDown(); }
        }));
        if (!delivered.await(5, TimeUnit.SECONDS)) {
            abandoned.set(true);
            fail("The overlay main-loop action completes within 5 seconds");
        }
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof Exception) throw (Exception) error;
        if (error != null) throw new AssertionError(error);
        return result.get();
    }
    private void await(String description, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (condition.met()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Timed out: " + description + "; audio=" + RecordingService.snapshot().state
                + ", audioError=" + RecordingService.snapshot().error + ", overlay=" + FloatingRecorderService.isShowing()
                + "; " + floatingInputState());
    }
    private String floatingInputState() throws Exception {
        return onMainValue(() -> {
            RecordingService.Snapshot capture = RecordingService.snapshot();
            String state = "markers=" + capture.markerCount + ", markerError=" + capture.markerError;
            FloatingRecorderService service = activeService();
            if (service == null) return state + ", service=absent";
            RecordingGesture gesture = (RecordingGesture) field(service, "gesture");
            View mic = (View) field(service, "microphone");
            View surface = (View) field(service, "surface");
            android.view.WindowManager.LayoutParams window =
                    (android.view.WindowManager.LayoutParams) field(service, "micWindow");
            state += ", touching=" + field(service, "touching") + ", pointer=" + field(service, "touchPointerId")
                    + ", direction=" + (gesture == null ? "absent" : gesture.getDirection())
                    + ", armed=" + (gesture != null && gesture.isArmed())
                    + ", centered=" + (gesture != null && gesture.isCentered())
                    + ", anchor=" + field(service, "touchX") + "/" + field(service, "touchY");
            if (touch != null) state += ", injected=" + (touch.x + touch.lastX * touch.density)
                    + "/" + (touch.y + touch.lastY * touch.density) + ", soloId=" + touch.soloId;
            if (surface != null) {
                int[] location = new int[2];
                surface.getLocationOnScreen(location);
                state += ", surface=" + location[0] + "/" + location[1] + "/"
                        + surface.getWidth() + "/" + surface.getHeight();
            }
            if (mic != null) state += ", mic=" + mic.getLeft() + "/" + mic.getTop() + "/"
                    + mic.getWidth() + "/" + mic.getHeight() + ", translation="
                    + mic.getTranslationX() + "/" + mic.getTranslationY() + ", scale="
                    + mic.getScaleX() + "/" + mic.getScaleY();
            if (window != null) state += ", window=" + window.x + "/" + window.y + "/" + window.width + "/" + window.height;
            return state;
        });
    }
    private String ownPackage() {
        String name = context.getPackageName();
        assertTrue(name.matches("[A-Za-z0-9_.]+"));
        return name;
    }
    private String shell(String command) throws IOException {
        ParcelFileDescriptor output = instrumentation.getUiAutomation().executeShellCommand(command);
        return new String(readAll(new ParcelFileDescriptor.AutoCloseInputStream(output)), StandardCharsets.UTF_8);
    }
    private byte[] readAll(InputStream input) throws IOException {
        if (input == null) throw new IOException("The original could not be read");
        try (InputStream owned = input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = owned.read(buffer)) != -1) result.write(buffer, 0, count);
            return result.toByteArray();
        }
    }
    private String sha256(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format(Locale.US, "%02x", item & 255));
        return value.toString();
    }
    private void restore(SharedPreferences target, Map<String, ?> prior) {
        SharedPreferences.Editor edit = target.edit().clear();
        for (Map.Entry<String, ?> entry : prior.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                edit.putStringSet(key, strings);
            }
        }
        assertTrue(edit.commit());
    }
    private void deleteOwnedPending() throws Exception {
        if (context == null) return;
        File pending = new File(context.getFilesDir(), "pending-captures").getCanonicalFile();
        File[] journals = pending.listFiles((dir, name) -> name.endsWith(".json"));
        if (journals == null) return;
        for (File journal : journals) {
            JSONObject record = new JSONObject(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
            if (!ownedProjects.contains(Uri.parse(record.optString("projectUri")))) continue;
            File media = new File(pending, record.getString("file")).getCanonicalFile();
            assertEquals(pending, media.getParentFile());
            for (String suffix : new String[]{"", ".json", ".json.bak", ".json.new"}) {
                File owned = new File(media.getPath() + suffix);
                if (owned.exists() && !owned.delete()) throw new IOException("Could not remove owned overlay capture");
            }
        }
    }
    private void deleteTree(File target, File ownedRoot) throws IOException {
        String root = ownedRoot.getCanonicalPath(), path = target.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) throw new IOException("Overlay fixture escaped its owned root");
        if (target.isDirectory()) {
            File[] files = target.listFiles();
            if (files == null) throw new IOException("Cannot list owned overlay fixture");
            for (File child : files) deleteTree(child, ownedRoot);
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot remove owned overlay fixture");
    }
    private interface Action { void run() throws Exception; }
    private interface Value<T> { T get() throws Exception; }
    private interface Condition { boolean met() throws Exception; }
}
