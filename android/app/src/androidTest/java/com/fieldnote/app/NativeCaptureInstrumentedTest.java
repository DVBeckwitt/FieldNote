package com.fieldnote.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.util.Log;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.VideoView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** Real emulator encoders/camera/foreground-service evidence; not an acoustic or physical-camera test. */
@RunWith(AndroidJUnit4.class)
public final class NativeCaptureInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private ProjectStore store;
    private File fixtureRoot;
    private Uri projectUri;
    private SharedPreferences bookmarks;
    private SharedPreferences preferences;
    private Map<String, ?> priorBookmarks;
    private Map<String, ?> priorPreferences;
    private ActivityScenario<MainActivity> notebook;
    private String ownedEntryId;
    private String cameraStatus = "Inline camera not started";
    private TouchPath recordTouch;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertFalse("Test must not take over an existing recording", RecordingService.isBusy());
        bookmarks = context.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        RecordingShortcuts.recordIntent(context); // Keep launcher authentication stable across fixture restores.
        priorBookmarks = bookmarks.getAll();
        priorPreferences = preferences.getAll();
        assertTrue(preferences.edit().remove("dark_mode").remove("metadata_enabled").commit());
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.ACCESS_COARSE_LOCATION);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        TestDocumentsProvider.clearFailures();
        File base = TestDocumentsProvider.rootDirectory(context);
        assertTrue(base.isDirectory() || base.mkdirs());
        fixtureRoot = new File(base, "native-capture-" + UUID.randomUUID());
        assertTrue(fixtureRoot.mkdir());
        Uri fixtureTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName());
        store = new ProjectStore(context);
        projectUri = store.createProject(fixtureTree, "Native capture integration");
        store.setCurrentProject(projectUri);
    }

    @After public void tearDown() throws Exception {
        // A failed overlap assertion must release its provider latch before waiting for workers.
        TestDocumentsProvider.clearFailures();
        // Stop only this test's recording, while its visible test activity still exists.
        RecordingService.Snapshot owned = RecordingService.snapshot();
        if (projectUri != null && projectUri.equals(owned.projectUri) && RecordingService.isBusy()) {
            if (RecordingService.isRecording() || RecordingService.isPaused())
                instrumentation.runOnMainSync(() -> RecordingService.stop(context));
            await("owned recording finishes during cleanup", 15_000, () -> !RecordingService.isBusy());
        }
        stopInlineCamera();
        await("background publication finishes before owned fixture cleanup", 30_000,
                () -> !ProjectPublisher.snapshot().running);
        if (notebook != null) notebook.close();
        instrumentation.waitForIdleSync();
        if (projectUri != null) deleteOwnPendingCaptures();
        if (bookmarks != null) restore(bookmarks, priorBookmarks);
        if (preferences != null) restore(preferences, priorPreferences);
        if (fixtureRoot != null) deleteFixture(fixtureRoot);
        TestDocumentsProvider.clearFailures();
        // Runtime grants are deliberately left on this isolated debug installation:
        // revocation can kill its in-process runner before subsequent tests complete.
    }

    @Test public void lockedAudioContinuesThroughPhotoAndProducesLinkedOriginalFiles() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        assertCapturePageOnly();
        AtomicInteger idleFill = new AtomicInteger();
        onNotebook(activity -> {
            idleFill.set(recordFillPixel(activity.findViewById(R.id.record_button)));
            beginRecordTouch(activity);
            assertTrue("The record control responds visually as soon as it is pressed",
                    activity.findViewById(R.id.record_button).isPressed());
            GestureGuideView guide = (GestureGuideView) readField(activity, "gestureGuide");
            assertEquals("Pressing the microphone reveals the activation zones", View.VISIBLE, guide.getVisibility());
            assertTrue(guide.isShown());
            assertTrue(guide.isActive());
            assertEquals(0f, guide.getApproachProgress(), .01f);
            assertFalse("The initial press reserves time for a standalone media gesture", RecordingService.isBusy());
        });
        await("microphone starts", 15_000, () -> recordingState(RecordingService.State.RECORDING));
        await("recording changes the microphone fill color", 5_000, () -> {
            AtomicBoolean changed = new AtomicBoolean();
            onNotebook(activity -> changed.set(
                    recordFillPixel(activity.findViewById(R.id.record_button)) != idleFill.get()));
            return changed.get();
        });
        await("pressed recording control contracts visibly", 2_000, () -> {
            AtomicBoolean contracted = new AtomicBoolean();
            onNotebook(activity -> contracted.set(activity.findViewById(R.id.record_button).getScaleX() < .99f));
            return contracted.get();
        });
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        assertEquals(projectUri, started.projectUri);
        assertNotNull(ownedEntryId);
        assertTrue(started.startedAt > 0);
        saveScreenshot("recording-held-center.png");
        onNotebook(activity -> {
            View control = activity.findViewById(R.id.record_button);
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, -56);
            assertEquals("The microphone follows the vertical approach without horizontal drift", 0f, control.getTranslationX(), 1f);
            assertEquals("The microphone follows the finger from its fixed origin", -56 * recordTouch.density, control.getTranslationY(), 1f);
            assertEquals("The held microphone shrinks for directional gestures", .84f, control.getScaleX(), .02f);
            assertGuideCenter(activity, recordTouch.originX, recordTouch.originY);
            GestureGuideView guide = (GestureGuideView) readField(activity, "gestureGuide");
            assertTrue("Activation zones remain visible while approaching a target", guide.isShown());
            assertEquals(RecordingGesture.Direction.UP, guide.getApproachDirection());
            assertEquals("Halfway to the 112 dp activation boundary reports half progress",
                    .5f, guide.getApproachProgress(), .02f);
            assertFalse("Approaching the lock zone must not activate it early", RecordingService.snapshot().locked);
        });
        saveScreenshot("gesture-approach.png");
        onNotebook(activity -> {
            View control = activity.findViewById(R.id.record_button);
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, 0);
            assertEquals("Retreating keeps the microphone at its fixed center", 0f, control.getTranslationY(), 1f);
            assertEquals(0f, control.getTranslationX(), 1f);
            assertGuideCenter(activity, recordTouch.originX, recordTouch.originY);
            assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
            assertFalse("Retreating before activation keeps the recording unlocked", RecordingService.snapshot().locked);
            GestureGuideView guide = (GestureGuideView) readField(activity, "gestureGuide");
            assertTrue(guide.isShown());
            assertEquals("Retreating to the origin clears approach progress", 0f, guide.getApproachProgress(), .01f);
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, -96);
            assertFalse("Moving past the old target still waits for the farther activation boundary", RecordingService.snapshot().locked);
            assertEquals(RecordingGesture.Direction.NONE, ((RecordingGesture) readField(activity, "gesture")).getDirection());
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, -140);
            assertEquals("Crossing the lock target follows the finger vertically", -140 * recordTouch.density, control.getTranslationY(), 1f);
            assertEquals("Crossing the lock target has no horizontal drift", 0f, control.getTranslationX(), 1f);
            assertGuideCenter(activity, recordTouch.originX, recordTouch.originY);
            assertEquals(RecordingGesture.Direction.UP, guide.getApproachDirection());
            assertEquals("The guide reaches the lock target while its origin stays put", 1f, guide.getApproachProgress(), .01f);
            assertEquals(RecordingGesture.Direction.UP, ((RecordingGesture) readField(activity, "gesture")).getDirection());
            recordTouch.send(MotionEvent.ACTION_UP, 0, -140);
        });
        await("upward gesture locks audio after finger release", 5_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().locked);
        await("released locked control returns to its resting size", 2_000, () -> {
            AtomicBoolean released = new AtomicBoolean();
            onNotebook(activity -> {
                View record = activity.findViewById(R.id.record_button);
                GestureGuideView guide = (GestureGuideView) readField(activity, "gestureGuide");
                released.set(!record.isPressed() && Math.abs(record.getScaleX() - 1f) < .01f
                        && Math.abs(record.getTranslationX()) < 1f && Math.abs(record.getTranslationY()) < 1f
                        && guide.getVisibility() == View.GONE && !guide.isActive());
            });
            return released.get();
        });
        assertEquals("Lock must preserve recording start", started.startedAt, RecordingService.snapshot().startedAt);

        openCameraFromCapture(-120);
        assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
        assertEquals(started.startedAt, RecordingService.snapshot().startedAt);
        await("original JPEG commits to the selected project folder", 20_000,
                () -> findMedia(store.readProject(projectUri), "photo") != null);
        RecordingService.Snapshot afterPhoto = RecordingService.snapshot();
        assertEquals("Taking a picture must not stop narration", RecordingService.State.RECORDING, afterPhoto.state);
        assertTrue("A photo gesture preserves an explicit upward lock after release", afterPhoto.locked);
        assertEquals(ownedEntryId, afterPhoto.entryId);
        assertEquals("Taking a picture must not restart narration", started.startedAt, afterPhoto.startedAt);
        saveScreenshot("camera-recording.png");
        JSONObject photoBeforeAudioSave = findMedia(store.readProject(projectUri), "photo");
        String originalPhotoTime = photoBeforeAudioSave.getString("startedAt");
        String originalPhotoPath = photoBeforeAudioSave.getString("path");
        assertTrue("Photo must occur within the ongoing recording",
                Instant.parse(originalPhotoTime).toEpochMilli() >= started.startedAt);

        awaitNotebookReady();
        quickRecordGesture(0, 0);
        await("audio finalizes and commits", 20_000, () -> recordingState(RecordingService.State.IDLE));
        awaitPublishedMedia(1, 1, 0);
        await("saved audio returns the microphone to its idle fill", 5_000, () -> {
            AtomicBoolean restored = new AtomicBoolean();
            onNotebook(activity -> restored.set(
                    recordFillPixel(activity.findViewById(R.id.record_button)) == idleFill.get()));
            return restored.get();
        });
        JSONObject index = store.readProject(projectUri);
        JSONArray entries = index.getJSONArray("entries");
        assertEquals("The photograph and narration belong to one entry", 1, entries.length());
        JSONObject entry = entries.getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(2, entry.getJSONArray("media").length());
        JSONObject photo = findMedia(index, "photo");
        JSONObject audio = findMedia(index, "audio");
        assertNotNull(photo);
        assertNotNull(audio);
        assertEquals("Audio finalization cannot alter photo capture time", originalPhotoTime, photo.getString("startedAt"));
        assertEquals(originalPhotoPath, photo.getString("path"));
        assertNotEquals(photo.getString("path"), audio.getString("path"));
        assertNotEquals("Each original retains its actual capture start", photo.getString("startedAt"), audio.getString("startedAt"));
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") > 0);
        assertTrue(Instant.parse(audio.getString("endedAt")).isAfter(Instant.parse(audio.getString("startedAt"))));
        assertRealJpeg(store.mediaUri(projectUri, photo.getString("path")));
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        awaitNotebookReady();
        assertCapturePageOnly();
        awaitNotebookResumed();
        saveScreenshot("native-dark.png");
        openAllNotesFromRecent();
        await("all notes displays the saved linked entry", 10_000, () -> {
            AtomicBoolean rendered = new AtomicBoolean();
            onNotebook(activity -> {
                View entriesView = activity.findViewById(R.id.entry_list);
                rendered.set(entriesView != null && entriesView.isShown()
                        && containsText(entriesView, "Audio ") && containsText(entriesView, " + Photo"));
            });
            return rendered.get();
        });
        saveScreenshot("native-all-notes.png");
        openEntryFromAllNotes(" + Photo");
        assertLinkedPhotoAndAudioViewer(photo);
    }

    @Test public void standaloneVideoProducesDecodableVideoAndEmbeddedAudio() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        assertCapturePageOnly();
        openCameraFromCapture(120);
        awaitInlineVideoRecording();
        assertFalse("Standalone video must not start a competing narration recorder", RecordingService.isRecording());
        SystemClock.sleep(3200); // A bounded real clip, long enough to verify inline playback and pause.
        saveScreenshot("video-recording.png");
        onNotebook(activity -> assertTrue(activity.findViewById(InlineCamera.STOP_VIDEO_ID).performClick()));
        await("original MP4 commits to the project", 20_000,
                () -> findMedia(store.readProject(projectUri), "video") != null);
        JSONObject index = store.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        assertEquals(1, index.getJSONArray("entries").getJSONObject(0).getJSONArray("media").length());
        JSONObject video = findMedia(index, "video");
        assertNotNull(video);
        assertTrue(video.getString("path").endsWith(".mp4"));
        assertTrue(video.getLong("durationMs") > 0);
        assertTrue(Instant.parse(video.getString("endedAt")).isAfter(Instant.parse(video.getString("startedAt"))));
        Uri original = store.mediaUri(projectUri, video.getString("path"));
        assertEncodedVideo(original);
        assertEncodedAudio(original);
        awaitNotebookReady();
        assertCapturePageOnly();
        openAllNotesFromRecent();
        openEntryFromAllNotes("Video");
        assertInlineVideoViewer();
    }

    @Test public void queuedLeftPhotosRemainLinkedWhenFingerIsReleasedImmediately() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("held microphone starts before taking a photo", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1600);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        assertFalse(started.locked);
        final long[] elapsedAtRelease = new long[1];
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, -120, 0);
            assertTrue("First shutter request starts a real inline camera job",
                    ((InlineCamera) readField(activity, "inlineCamera")).isBusy());
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, 0);
            GestureGuideView guide = (GestureGuideView) readField(activity, "gestureGuide");
            assertEquals("Returning to center clears capture approach progress", 0f, guide.getApproachProgress(), .01f);
            recordTouch.send(MotionEvent.ACTION_MOVE, -120, 0);
            assertHeldNarration(started);
            elapsedAtRelease[0] = RecordingService.snapshot().elapsedMs();
            // Release immediately after the second accepted shutter request, while the first job is still busy.
            recordTouch.send(MotionEvent.ACTION_UP, -120, 0);
        });
        await("release ends narration but both accepted photos still commit", 30_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "photo").length() == 2 && inlineCameraIdle());
        JSONObject index = store.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(3, entry.getJSONArray("media").length());
        JSONArray photos = mediaOfKind(index, "photo");
        assertEquals(2, photos.length());
        assertEquals(1, mediaOfKind(index, "audio").length());
        JSONObject firstPhoto = photos.getJSONObject(0);
        JSONObject secondPhoto = photos.getJSONObject(1);
        assertNotEquals("Each accepted shutter creates a distinct JPEG original",
                firstPhoto.getString("path"), secondPhoto.getString("path"));
        assertTrue(Instant.parse(secondPhoto.getString("startedAt")).isAfter(Instant.parse(firstPhoto.getString("startedAt"))));
        assertRealJpeg(store.mediaUri(projectUri, firstPhoto.getString("path")));
        assertRealJpeg(store.mediaUri(projectUri, secondPhoto.getString("path")));
        JSONObject audio = findMedia(index, "audio");
        assertNotNull(audio);
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        long captureDuration = audio.getLong("durationMs");
        assertTrue("All audio up to finger release is retained", captureDuration >= elapsedAtRelease[0]);
        assertTrue("Audio ends at release without waiting for queued camera work",
                captureDuration <= elapsedAtRelease[0] + 500);
        assertTrue("The accepted second photo completes after the released audio has ended",
                Instant.parse(secondPhoto.getString("startedAt")).isAfter(Instant.parse(audio.getString("endedAt"))));
        Uri narration = store.mediaUri(projectUri, audio.getString("path"));
        assertEncodedAudio(narration);
        long encoded = encodedDuration(narration);
        assertTrue("Encoded audio retains the held span, allowing encoder startup tolerance", encoded >= elapsedAtRelease[0] - 500);
        assertTrue("Encoded audio excludes the later photo queue wait, allowing frame padding", encoded <= elapsedAtRelease[0] + 500);
        ProjectStore reopened = new ProjectStore(context);
        assertEquals(projectUri, reopened.currentProject());
        JSONObject preserved = reopened.readProject(projectUri).getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, preserved.getString("id"));
        assertEquals(3, preserved.getJSONArray("media").length());
        JSONArray reopenedPhotos = mediaOfKind(reopened.readProject(projectUri), "photo");
        assertEquals(firstPhoto.getString("path"), reopenedPhotos.getJSONObject(0).getString("path"));
        assertEquals(secondPhoto.getString("path"), reopenedPhotos.getJSONObject(1).getString("path"));
        assertFalse(RecordingService.isBusy());
    }

    @Test public void headerFilesButtonOffersExternalFolderAndZipWithoutReplacingRecord() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        assertCapturePageOnly();
        AtomicReference<Intent> launched = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                launched.set(new Intent(intent));
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            openFilesDialog();
            onFilesDialog(dialog -> {
                assertTrue(dialog.findViewById(R.id.open_project_folder).isEnabled());
                assertTrue(dialog.findViewById(R.id.open_project_zip).isEnabled());
            });
            saveScreenshot("project-file-choices.png");
            onFilesDialog(dialog -> assertTrue(dialog.findViewById(R.id.open_project_folder).performClick()));
            Intent folder = externalViewIntent(launched.get());
            Uri tree = DocumentsContract.buildTreeDocumentUri(projectUri.getAuthority(), DocumentsContract.getTreeDocumentId(projectUri));
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(projectUri, DocumentsContract.getTreeDocumentId(projectUri));
            assertEquals(Intent.ACTION_VIEW, folder.getAction());
            assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, folder.getType());
            assertEquals("The chosen storage folder opens, not a new folder picker", directory, folder.getData());
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    folder.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            assertNotNull(folder.getClipData());
            assertEquals(tree, folder.getClipData().getItemAt(0).getUri());
            awaitRecordWindowFocus(true);
            assertCapturePageOnly();
            launched.set(null);
            openFilesDialog();
            onFilesDialog(dialog -> assertTrue(dialog.findViewById(R.id.open_project_zip).performClick()));
            Intent zip = externalViewIntent(launched.get());
            assertEquals(Intent.ACTION_VIEW, zip.getAction());
            assertEquals("application/zip", zip.getType());
            assertEquals(projectUri, zip.getData());
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    zip.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            assertNotNull(zip.getClipData());
            assertEquals(projectUri, zip.getClipData().getItemAt(0).getUri());
            awaitRecordWindowFocus(true);
            assertCapturePageOnly();
            assertEquals("File choices do not create another in-app page", Stage.RESUMED, notebookStage());
            assertFalse(RecordingService.isBusy());
            assertEquals(0, store.readProject(projectUri).getJSONArray("entries").length());
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private static Intent externalViewIntent(Intent launched) {
        assertNotNull("The file choice dispatches an external application intent", launched);
        if (!Intent.ACTION_CHOOSER.equals(launched.getAction())) return launched;
        Intent target = launched.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(target);
        return target;
    }

    @Test public void horizontalSwipesOutsideMicrophoneStayOnRecordWithoutStartingCapture() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        assertCapturePageOnly();
        openAllNotesFromRecent();
        assertEquals("An empty recent card still opens the complete empty list", 0,
                store.readProject(projectUri).getJSONArray("entries").length());
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.close_notes).performClick()));
        awaitNotebookReady();
        assertCapturePageOnly();
        swipePage(-1);
        assertCapturePageOnly();
        swipePage(1);
        awaitNotebookReady();
        assertCapturePageOnly();
        assertFalse("Horizontal swipes cannot navigate to a hidden Files page or start capture", RecordingService.isBusy());
        assertEquals(0, store.readProject(projectUri).getJSONArray("entries").length());
    }

    @Test public void recentCardSwipeFindsOlderNotesAndRestoresNavigationAfterRecreation() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        String olderText = "Older apparatus note\nCalibration completed before the experiment.";
        quickRecordGesture(0, 120);
        awaitNoteDialog();
        saveNote(null, olderText);
        String olderId = awaitTextEntry(olderText).getString("id");
        awaitRecentTitle("Older apparatus note");
        String newerText = "Newer apparatus note\nThe second observation is ready for review.";
        quickRecordGesture(0, 120);
        awaitNoteDialog();
        saveNote(null, newerText);
        awaitRecentTitle("Newer apparatus note");
        assertEquals(2, store.readProject(projectUri).getJSONArray("entries").length());
        openRecentEntry();
        onEntryViewer(viewer -> {
            assertEquals("A normal tap continues to open only the latest note", newerText,
                    ((TextView) viewer.findViewById(EntryViewer.ENTRY_TEXT_ID)).getText().toString());
            assertTrue(viewer.findViewById(EntryViewer.CLOSE_ID).performClick());
        });
        swipeRecentCard(24, MotionEvent.ACTION_UP);
        instrumentation.waitForIdleSync();
        assertRecentGestureStayedOnRecord();
        swipeRecentCard(-80, MotionEvent.ACTION_CANCEL);
        instrumentation.waitForIdleSync();
        assertRecentGestureStayedOnRecord();
        openAllNotesFromRecent();
        onNotebook(activity -> {
            View entries = activity.findViewById(R.id.entry_list);
            assertTrue("The full list includes notes older than the recent card", containsText(entries, "Older apparatus note"));
            assertTrue(containsText(entries, "Newer apparatus note"));
        });
        openEntryFromAllNotes("Older apparatus note");
        onEntryViewer(viewer -> {
            assertEquals(olderText, ((TextView) viewer.findViewById(EntryViewer.ENTRY_TEXT_ID)).getText().toString());
            assertEquals(olderId, ((JSONObject) readField(viewer, "entry")).optString("id"));
            assertTrue(viewer.findViewById(EntryViewer.CLOSE_ID).performClick());
        });
        notebook.recreate();
        awaitAllNotesVisible();
        onNotebook(activity -> {
            assertTrue(containsText(activity.findViewById(R.id.entry_list), "Older apparatus note"));
            assertTrue(containsText(activity.findViewById(R.id.entry_list), "Newer apparatus note"));
        });
        saveScreenshot("all-notes.png");
        onNotebook(activity -> {
            TextView back = activity.findViewById(R.id.close_notes);
            assertTrue(back.getText().toString().contains("Back to Record"));
            assertTrue(back.performClick());
        });
        awaitNotebookReady();
        assertCapturePageOnly();
        awaitRecentTitle("Newer apparatus note");
        openAllNotesFromRecent();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        awaitNotebookReady();
        assertCapturePageOnly();
        assertEquals("System Back closes the list without leaving the app", Stage.RESUMED, notebookStage());
        assertFalse(RecordingService.isBusy());
        assertEquals(2, store.readProject(projectUri).getJSONArray("entries").length());
    }

    @Test public void openingNotesDuringHeldNarrationCannotStealItsRelease() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("held narration records before another control attempts to open notes", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> {
            View recent = activity.findViewById(R.id.recent_note_card);
            View record = activity.findViewById(R.id.record_button);
            View sheet = activity.findViewById(R.id.notes_sheet);
            assertTrue("The accessibility action is handled while its opening is guarded",
                    recent.performAccessibilityAction(R.id.show_all_notes, null));
            assertEquals("An accessibility action cannot cover the held microphone", View.GONE, sheet.getVisibility());
            assertTrue(record.isEnabled());
            assertHeldNarration(started);
            // Dispatch the independent card stream directly, as split touch does, without
            // replacing the original page/record touch stream or its pending finger release.
            TouchPath independentCardTouch = new TouchPath(recent);
            independentCardTouch.send(MotionEvent.ACTION_DOWN, 0, 0);
            independentCardTouch.send(MotionEvent.ACTION_MOVE, 0, -80);
            assertEquals("A second finger cannot start a notes-panel drag during a microphone hold",
                    View.GONE, sheet.getVisibility());
            assertTrue("The original microphone must remain able to receive its release", record.isEnabled());
            independentCardTouch.send(MotionEvent.ACTION_UP, 0, -80);
            assertHeldNarration(started);
        });
        long afterAttempts = RecordingService.snapshot().elapsedMs();
        await("narration advances after both rejected notes-opening attempts", 3_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= afterAttempts + 250);
        onNotebook(activity -> {
            assertEquals(View.GONE, activity.findViewById(R.id.notes_sheet).getVisibility());
            assertTrue(activity.findViewById(R.id.record_button).isEnabled());
            assertHeldNarration(started);
            recordTouch.send(MotionEvent.ACTION_UP, 0, 0);
        });
        await("the original finger release saves narration normally", 20_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 1);
        JSONObject index = store.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals("Both rejected attempts preserve one original recording", 1, entry.getJSONArray("media").length());
        JSONObject audio = findMedia(index, "audio");
        assertNotNull(audio);
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= afterAttempts + 250);
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        awaitNotebookReady();
        assertCapturePageOnly();
    }

    @Test public void filesButtonTapDuringHeldNarrationCannotStealItsRelease() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(activity -> {
            View root = (View) readField(activity, "root");
            View mic = activity.findViewById(R.id.record_button);
            int[] origin = new int[2], button = new int[2];
            root.getLocationOnScreen(origin);
            mic.getLocationOnScreen(button);
            recordTouch = new TouchPath(root, button[0] - origin[0] + mic.getWidth() / 2f,
                    button[1] - origin[1] + mic.getHeight() / 2f);
            recordTouch.send(MotionEvent.ACTION_DOWN, 0, 0);
        });
        await("held narration starts before the second-finger Files tap", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 900);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> recordTouch.tapSecondPointer(activity.findViewById(R.id.files_button)));
        // A separate main-thread turn lets the native Button dispatch its posted click.
        onNotebook(activity -> {
            assertTrue("A second finger cannot hide the microphone holding the active touch",
                    activity.findViewById(R.id.record_button).isShown());
            assertTrue(activity.findViewById(R.id.record_button).isEnabled());
            assertNull("A second finger cannot open a dialog over the held microphone", readField(activity, "filesDialog"));
            assertHeldNarration(started);
            recordTouch.send(MotionEvent.ACTION_UP, 0, 0);
        });
        await("the original release still finalizes exactly one audio file", 20_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 1);
        JSONObject audio = findMedia(store.readProject(projectUri), "audio");
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
    }

    @Test public void originalMicFingerReleaseEndsRecordingWhileSecondFingerRemains() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(activity -> {
            beginRootRecordTouch(activity);
            recordTouch.sendPointers(MotionEvent.ACTION_DOWN, new int[]{7}, new float[]{0}, new float[]{0});
        });
        await("original microphone finger starts narration", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 900);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> {
            // Both pointers belong to the same mic View, unlike a split header-button tap.
            recordTouch.sendPointers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{7, 9}, new float[]{0, 24}, new float[]{0, 0});
            assertHeldNarration(started);
            // Pointer indices are not identity: keep the owner at home while the
            // other finger becomes index zero and crosses both camera regions.
            for (float secondaryX : new float[]{-160, 160}) {
                recordTouch.sendPointers(MotionEvent.ACTION_MOVE,
                        new int[]{9, 7}, new float[]{secondaryX, 0}, new float[]{0, 0});
                assertEquals("Only the original finger can select a media gesture", RecordingGesture.Direction.NONE,
                        ((RecordingGesture) readField(activity, "gesture")).getDirection());
                assertHeldNarration(started);
            }
            recordTouch.sendPointers(MotionEvent.ACTION_POINTER_UP,
                    new int[]{9, 7}, new float[]{160, 0}, new float[]{0, 0});
            assertTrue("Lifting the non-owner finger leaves the original hold active",
                    (Boolean) readField(activity, "touching"));
            assertHeldNarration(started);
            recordTouch.sendPointers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{7, 9}, new float[]{0, 24}, new float[]{0, 0});
            recordTouch.sendPointers(MotionEvent.ACTION_POINTER_UP,
                    new int[]{7, 9}, new float[]{0, 24}, new float[]{0, 0});
            assertFalse("Lifting the original microphone finger ends its hold even while another finger stays down",
                    (Boolean) readField(activity, "touching"));
            recordTouch.sendPointers(MotionEvent.ACTION_MOVE, new int[]{9}, new float[]{-160}, new float[]{0});
            recordTouch.sendPointers(MotionEvent.ACTION_UP, new int[]{9}, new float[]{-160}, new float[]{0});
        });
        await("original release publishes narration without a second-finger camera action", 20_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 1);
        JSONObject index = store.readProject(projectUri);
        assertEquals(0, mediaOfKind(index, "photo").length());
        assertEquals(0, mediaOfKind(index, "video").length());
        JSONObject audio = findMedia(index, "audio");
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
    }

    @Test public void settingsButtonTapDuringHeldNarrationCannotStealItsRelease() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(activity -> {
            beginRootRecordTouch(activity);
            recordTouch.send(MotionEvent.ACTION_DOWN, 0, 0);
        });
        await("held narration starts before a second-finger Settings tap", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 900);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> recordTouch.tapSecondPointer(activity.findViewById(R.id.settings_button)));
        onNotebook(activity -> {
            assertNull("Settings cannot take window focus from a microphone hold", readField(activity, "settingsDialog"));
            assertHeldNarration(started);
            recordTouch.send(MotionEvent.ACTION_UP, 0, 0);
        });
        await("the held finger still ends its original audio file", 20_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 1);
        JSONObject audio = findMedia(store.readProject(projectUri), "audio");
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
    }

    private void beginRootRecordTouch(MainActivity activity) {
        View root = (View) readField(activity, "root");
        View mic = activity.findViewById(R.id.record_button);
        int[] origin = new int[2], button = new int[2];
        root.getLocationOnScreen(origin);
        mic.getLocationOnScreen(button);
        recordTouch = new TouchPath(root, button[0] - origin[0] + mic.getWidth() / 2f,
                button[1] - origin[1] + mic.getHeight() / 2f);
    }

    @Test public void recordingControlsFitWithMultiLineRecentNote() throws Exception {
        store.saveText(projectUri, UUID.randomUUID().toString(),
                "Apparatus observation\nA longer saved observation that occupies both preview lines in the latest-note card.",
                MetadataCollector.snapshot(context));
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        saveScreenshot("record-with-multiline-preview.png");
        onNotebook(activity -> {
            TextView preview = activity.findViewById(R.id.recent_note_preview);
            assertEquals("Exercise the full two-line recent-note preview", 2, preview.getLineCount());
            ViewGroup panel = (ViewGroup) readField(activity, "capturePanel");
            View status = activity.findViewById(R.id.record_status);
            View marker = activity.findViewById(R.id.add_marker);
            assertTrue("Recording/save status must stay inside the capture viewport", status.getTop() >= 0);
            assertTrue("Marker control must stay above the latest-note card", marker.getBottom() <= panel.getHeight());
            Rect visible = new Rect();
            assertTrue(status.getGlobalVisibleRect(visible));
            assertEquals("No part of recording status may be clipped", status.getHeight(), visible.height());
        });
    }

    @Test public void notesPanelFollowsFingerAndReversesBeforeSettling() throws Exception {
        JSONObject metadata = MetadataCollector.snapshot(context);
        for (int i = 0; i < 3; i++)
            store.saveText(projectUri, UUID.randomUUID().toString(),
                    "Panel motion note " + i + "\nA saved observation for native drag coverage.", metadata);
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        final TouchPath[] opening = new TouchPath[1];
        final float[] firstOpeningPosition = new float[1];
        onNotebook(activity -> {
            View sheet = activity.findViewById(R.id.notes_sheet);
            View pages = activity.findViewById(R.id.capture_pages);
            float density = activity.getResources().getDisplayMetrics().density;
            opening[0] = recentCardTouch(activity);
            opening[0].send(MotionEvent.ACTION_DOWN, 0, 0);
            opening[0].send(MotionEvent.ACTION_MOVE, 0, -20);
            assertEquals("The sheet appears during the drag, before release", View.VISIBLE, sheet.getVisibility());
            firstOpeningPosition[0] = sheet.getTranslationY();
            assertEquals("Opening follows the full distance from the original finger down",
                    pages.getHeight() - 20 * density, firstOpeningPosition[0], 1f);
            opening[0].send(MotionEvent.ACTION_MOVE, 0, -48);
            assertEquals("Another 28 dp of finger movement reveals another 28 dp of notes",
                    28 * density, firstOpeningPosition[0] - sheet.getTranslationY(), 1f);
            assertTrue("The recent card retains the active touch while opening",
                    activity.findViewById(R.id.recent_note_card).isShown());
            assertTrue(activity.findViewById(R.id.recent_note_card).isEnabled());
            assertTrue("Record remains underneath the moving sheet", activity.findViewById(R.id.record_button).isShown());
            assertFalse("The microphone cannot start recording during a panel drag",
                    activity.findViewById(R.id.record_button).isEnabled());
            opening[0].send(MotionEvent.ACTION_MOVE, 0, -160);
            assertEquals(pages.getHeight() - 160 * density, sheet.getTranslationY(), 1f);
            assertTrue(sheet.getTranslationY() > 0);
        });
        saveScreenshot("notes-opening-partial.png");
        onNotebook(activity -> {
            View sheet = activity.findViewById(R.id.notes_sheet);
            opening[0].send(MotionEvent.ACTION_MOVE, 0, -20);
            assertEquals("Reversing the finger retracts the sheet before release",
                    firstOpeningPosition[0], sheet.getTranslationY(), 1f);
            opening[0].send(MotionEvent.ACTION_UP, 0, -20);
        });
        assertRecentGestureStayedOnRecord();
        openAllNotesFromRecent();
        final TouchPath[] closing = new TouchPath[1];
        final float[] firstClosingPosition = new float[1];
        onNotebook(activity -> {
            View sheet = activity.findViewById(R.id.notes_sheet);
            ScrollView scroll = activity.findViewById(R.id.notes_scroll);
            float density = activity.getResources().getDisplayMetrics().density;
            scroll.scrollTo(0, 0);
            // Keep one path through the stationary pages while its child sheet moves.
            closing[0] = notesRowTouch(activity);
            closing[0].send(MotionEvent.ACTION_DOWN, 0, 0);
            closing[0].send(MotionEvent.ACTION_MOVE, 0, 20);
            firstClosingPosition[0] = sheet.getTranslationY();
            assertEquals("Closing follows the full distance from the original finger down",
                    20 * density, firstClosingPosition[0], 1f);
            assertTrue("Record is revealed underneath while the finger is still held",
                    activity.findViewById(R.id.record_button).isShown());
            closing[0].send(MotionEvent.ACTION_MOVE, 0, 48);
            assertEquals("Moving the sheet does not change the gesture's coordinate origin",
                    28 * density, sheet.getTranslationY() - firstClosingPosition[0], 1f);
            closing[0].send(MotionEvent.ACTION_MOVE, 0, 96);
            assertEquals(96 * density, sheet.getTranslationY(), 1f);
            assertTrue(sheet.getTranslationY() < activity.findViewById(R.id.capture_pages).getHeight());
        });
        saveScreenshot("notes-closing-partial.png");
        onNotebook(activity -> {
            View sheet = activity.findViewById(R.id.notes_sheet);
            closing[0].send(MotionEvent.ACTION_MOVE, 0, 20);
            assertEquals("A reversed downward drag moves the notes back toward their open position",
                    firstClosingPosition[0], sheet.getTranslationY(), 1f);
            closing[0].send(MotionEvent.ACTION_UP, 0, 20);
        });
        awaitAllNotesVisible();
        onNotebook(activity -> {
            assertNull("Dragging a real note row must cancel its click", readField(activity, "entryViewer"));
            assertNull(readField(activity, "noteDialog"));
            TouchPath completed = notesRowTouch(activity);
            completed.send(MotionEvent.ACTION_DOWN, 0, 0);
            completed.send(MotionEvent.ACTION_MOVE, 0, 96);
            completed.send(MotionEvent.ACTION_UP, 0, 96);
        });
        awaitNotebookReady();
        assertCapturePageOnly();
        assertFalse(RecordingService.isBusy());
        assertEquals("Panel movement preserves every saved note", 3,
                store.readProject(projectUri).getJSONArray("entries").length());
    }

    @Test public void notesPullDownDismissesOnlyWhenStartedAtTopAndReleased() throws Exception {
        JSONObject metadata = MetadataCollector.snapshot(context);
        for (int i = 0; i < 16; i++)
            store.saveText(projectUri, UUID.randomUUID().toString(),
                    "Scroll fixture note " + i + "\nA saved observation for native scrolling coverage.", metadata);
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        openAllNotesFromRecent();
        await("the seeded list has a measured scrollable native viewport", 5_000, () -> {
            AtomicBoolean measured = new AtomicBoolean();
            onNotebook(activity -> {
                ScrollView scroll = activity.findViewById(R.id.notes_scroll);
                measured.set(scroll != null && scroll.getHeight() > 0 && scroll.getChildCount() > 0
                        && scroll.getChildAt(0).getHeight() > scroll.getHeight());
            });
            return measured.get();
        });
        onNotebook(activity -> {
            ScrollView scroll = activity.findViewById(R.id.notes_scroll);
            assertNotNull(scroll);
            assertTrue("The fixture contains enough real rows to scroll", scroll.getChildAt(0).getHeight() > scroll.getHeight());
            scroll.scrollTo(0, 0);
            TouchPath gesture = notesRowTouch(activity);
            gesture.send(MotionEvent.ACTION_DOWN, 0, 0);
            gesture.send(MotionEvent.ACTION_MOVE, 0, 20);
            gesture.send(MotionEvent.ACTION_MOVE, 0, 96);
            assertTrue("Pulling down does not dismiss before release", activity.findViewById(R.id.close_notes).isShown());
            gesture.send(MotionEvent.ACTION_CANCEL, 0, 96);
        });
        instrumentation.waitForIdleSync();
        awaitAllNotesVisible();
        onNotebook(activity -> {
            assertNull("A cancelled pull must cancel the row's click", readField(activity, "entryViewer"));
            assertNull(readField(activity, "noteDialog"));
            ScrollView scroll = activity.findViewById(R.id.notes_scroll);
            scroll.scrollTo(0, Math.round(48 * activity.getResources().getDisplayMetrics().density));
            int initialScroll = scroll.getScrollY();
            assertTrue("The normal-scroll gesture begins below the top", initialScroll > 0);
            TouchPath gesture = notesRowTouch(activity);
            gesture.send(MotionEvent.ACTION_DOWN, 0, 0);
            gesture.send(MotionEvent.ACTION_MOVE, 0, 20);
            gesture.send(MotionEvent.ACTION_MOVE, 0, 96);
            gesture.send(MotionEvent.ACTION_UP, 0, 96);
            assertTrue("A downward drag begun below the top scrolls the actual list", scroll.getScrollY() < initialScroll);
            assertTrue("Reaching the top during a normal drag must not dismiss the list",
                    activity.findViewById(R.id.close_notes).isShown());
        });
        instrumentation.waitForIdleSync();
        awaitAllNotesVisible();
        onNotebook(activity -> {
            assertNull("Normal scrolling must not open an entry", readField(activity, "entryViewer"));
            ScrollView scroll = activity.findViewById(R.id.notes_scroll);
            scroll.scrollTo(0, 0);
            assertEquals(0, scroll.getScrollY());
            TouchPath gesture = notesRowTouch(activity);
            gesture.send(MotionEvent.ACTION_DOWN, 0, 0);
            gesture.send(MotionEvent.ACTION_MOVE, 0, 20);
            gesture.send(MotionEvent.ACTION_MOVE, 0, 96);
            assertTrue(activity.findViewById(R.id.close_notes).isShown());
            gesture.send(MotionEvent.ACTION_UP, 0, 96);
        });
        awaitNotebookReady();
        assertCapturePageOnly();
        assertEquals(Stage.RESUMED, notebookStage());
        assertFalse(RecordingService.isBusy());
        assertEquals("Scrolling and dismissal do not alter saved notes", 16,
                store.readProject(projectUri).getJSONArray("entries").length());
    }

    @Test public void heldRightVideoAndNarrationBothFinishOnFingerRelease() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("narration starts before the video gesture", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0));
        awaitInlineVideoRecording();
        assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
        assertEquals(started.startedAt, RecordingService.snapshot().startedAt);
        assertEquals(ownedEntryId, RecordingService.snapshot().entryId);
        assertFalse("Starting linked video must not implicitly lock held narration", RecordingService.snapshot().locked);
        SystemClock.sleep(1600); // Real native encoder samples, independent of the narration file.
        assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
        assertEquals(started.startedAt, RecordingService.snapshot().startedAt);
        onNotebook(activity -> assertTrue("Video continues until the held finger is released",
                ((InlineCamera) readField(activity, "inlineCamera")).isRecording()));
        long elapsedBeforeRelease = RecordingService.snapshot().elapsedMs();
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_UP, 120, 0));
        await("finger release stops both native recording owners", 20_000,
                () -> recordingState(RecordingService.State.IDLE) && inlineCameraIdle());
        awaitPublishedMedia(1, 0, 1);
        JSONObject index = store.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(2, entry.getJSONArray("media").length());
        JSONObject audio = findMedia(index, "audio");
        JSONObject video = findMedia(index, "video");
        assertNotNull(audio);
        assertNotNull(video);
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= elapsedBeforeRelease);
        assertTrue("Video must include the span captured while held", video.getLong("durationMs") >= 1500);
        assertTrue(Instant.parse(video.getString("startedAt")).toEpochMilli() > started.startedAt);
        Uri videoUri = store.mediaUri(projectUri, video.getString("path"));
        assertEncodedVideo(videoUri);
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, videoUri);
            assertNotEquals("Separate narration owns the microphone; linked video must not contend for it", "yes",
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
        } finally { reader.release(); }
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        assertEquals(projectUri, new ProjectStore(context).currentProject());
    }

    @Test public void releasingLockedVideoGestureSavesOnlyVideoUntilNarrationIsTapped() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("narration starts before the explicit upward lock", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, -140);
            recordTouch.send(MotionEvent.ACTION_UP, 0, -140);
        });
        await("explicit upward gesture keeps narration locked after release", 5_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().locked);
        awaitMicrophoneAtHome();
        // Exit an initial right gesture during preparation; this must not stop locked narration or open an error dialog.
        onNotebook(activity -> {
            beginRecordTouch(activity);
            recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0);
            assertTrue(((InlineCamera) readField(activity, "inlineCamera")).isBusy());
            recordTouch.send(MotionEvent.ACTION_MOVE, 40, 0);
            recordTouch.send(MotionEvent.ACTION_UP, 40, 0);
        });
        await("leaving a preparing video releases its camera job", 20_000, this::inlineCameraIdle);
        assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
        assertTrue(RecordingService.snapshot().locked);
        assertEquals(started.startedAt, RecordingService.snapshot().startedAt);
        assertEquals(ownedEntryId, RecordingService.snapshot().entryId);
        assertEquals("Cancelled preparation creates no fabricated video entry", 0,
                mediaOfKind(store.readProject(projectUri), "video").length());
        await("silent preparation cancellation leaves the notebook window focused", 5_000, () -> {
            AtomicBoolean focused = new AtomicBoolean();
            onNotebook(activity -> focused.set(activity.hasWindowFocus()));
            return focused.get();
        });
        awaitNotebookReady();
        awaitMicrophoneAtHome();
        onNotebook(activity -> {
            beginRecordTouch(activity);
            recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0);
        });
        awaitInlineVideoRecording();
        SystemClock.sleep(1600);
        assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
        assertTrue(RecordingService.snapshot().locked);
        onNotebook(activity -> assertTrue(((InlineCamera) readField(activity, "inlineCamera")).isRecording()));
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_UP, 120, 0));
        await("rightward finger release saves video without ending locked narration", 20_000,
                () -> inlineCameraIdle() && mediaOfKind(store.readProject(projectUri), "video").length() == 1);
        RecordingService.Snapshot afterRelease = RecordingService.snapshot();
        assertEquals(RecordingService.State.RECORDING, afterRelease.state);
        assertTrue(afterRelease.locked);
        assertEquals(ownedEntryId, afterRelease.entryId);
        assertEquals(started.startedAt, afterRelease.startedAt);
        long elapsedAfterRelease = afterRelease.elapsedMs();
        await("locked narration continues after the released video finishes", 5_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= elapsedAfterRelease + 400);
        JSONObject beforeStop = store.readProject(projectUri);
        assertEquals(0, mediaOfKind(beforeStop, "audio").length());
        JSONObject originalVideo = findMedia(beforeStop, "video");
        assertEncodedVideo(store.mediaUri(projectUri, originalVideo.getString("path")));
        awaitNotebookReady();
        quickRecordGesture(0, 0);
        await("a separate tap finishes the locked narration", 20_000,
                () -> recordingState(RecordingService.State.IDLE));
        awaitPublishedMedia(1, 0, 1);
        JSONObject index = store.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(2, entry.getJSONArray("media").length());
        JSONObject video = findMedia(index, "video");
        JSONObject audio = findMedia(index, "audio");
        assertNotNull(audio);
        assertEquals(originalVideo.getString("path"), video.getString("path"));
        assertEquals(originalVideo.getString("startedAt"), video.getString("startedAt"));
        assertEquals(originalVideo.getString("endedAt"), video.getString("endedAt"));
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= elapsedAfterRelease + 400);
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        assertEquals(projectUri, new ProjectStore(context).currentProject());
    }

    @Test public void leavingAndReenteringVideoZoneCreatesTwoClipsWithinOneNarration() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("held narration starts before entering the video zone", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0));
        awaitInlineVideoRecording();
        SystemClock.sleep(1600);
        assertHeldNarration(started);
        long firstExitElapsed = RecordingService.snapshot().elapsedMs();
        // 40 dp has left the 112 dp video zone but has not reached the fixed 28 dp rearm center.
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_MOVE, 40, 0));
        await("leaving the right zone saves clip one while narration stays active", 20_000,
                () -> mediaOfKind(store.readProject(projectUri), "video").length() == 1 && inlineCameraIdle());
        assertHeldNarration(started);
        await("narration advances after video-zone exit", 5_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= firstExitElapsed + 400);
        JSONObject firstClip = mediaOfKind(store.readProject(projectUri), "video").getJSONObject(0);
        assertEncodedVideo(store.mediaUri(projectUri, firstClip.getString("path")));
        awaitNotebookReady();
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0);
            assertFalse("Leaving the zone alone does not rearm another video activation",
                    ((InlineCamera) readField(activity, "inlineCamera")).isBusy());
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, 0);
            assertEquals(0f, ((GestureGuideView) readField(activity, "gestureGuide")).getApproachProgress(), .01f);
            recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0);
        });
        awaitInlineVideoRecording();
        assertHeldNarration(started);
        SystemClock.sleep(1600);
        onNotebook(activity -> assertTrue("Re-entering after center starts a second actual video recorder",
                ((InlineCamera) readField(activity, "inlineCamera")).isRecording()));
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_MOVE, 40, 0));
        await("second zone exit commits a second independent clip", 20_000,
                () -> mediaOfKind(store.readProject(projectUri), "video").length() == 2 && inlineCameraIdle());
        assertHeldNarration(started);
        JSONObject beforeRelease = store.readProject(projectUri);
        assertEquals("Both clips remain linked to the still-active narration entry", 1,
                beforeRelease.getJSONArray("entries").length());
        assertEquals(ownedEntryId, beforeRelease.getJSONArray("entries").getJSONObject(0).getString("id"));
        assertEquals("Narration is still open rather than split into an intermediate audio file", 0,
                mediaOfKind(beforeRelease, "audio").length());
        JSONArray captured = mediaOfKind(beforeRelease, "video");
        JSONObject secondClip = captured.getJSONObject(1);
        assertEquals(firstClip.getString("path"), captured.getJSONObject(0).getString("path"));
        assertEquals(firstClip.getString("startedAt"), captured.getJSONObject(0).getString("startedAt"));
        assertEquals(firstClip.getString("endedAt"), captured.getJSONObject(0).getString("endedAt"));
        assertNotEquals(firstClip.getString("path"), secondClip.getString("path"));
        assertTrue(Instant.parse(secondClip.getString("startedAt")).isAfter(Instant.parse(firstClip.getString("endedAt"))));
        assertEncodedVideo(store.mediaUri(projectUri, secondClip.getString("path")));
        long elapsedBeforeRelease = RecordingService.snapshot().elapsedMs();
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_UP, 40, 0));
        await("final finger release saves the one continuous narration original", 20_000,
                () -> recordingState(RecordingService.State.IDLE) && inlineCameraIdle());
        awaitPublishedMedia(1, 0, 2);
        ProjectStore reopened = new ProjectStore(context);
        assertEquals(projectUri, reopened.currentProject());
        JSONObject index = reopened.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(3, entry.getJSONArray("media").length());
        JSONArray clips = mediaOfKind(index, "video");
        assertEquals(2, clips.length());
        assertEquals(firstClip.getString("path"), clips.getJSONObject(0).getString("path"));
        assertEquals(firstClip.getString("endedAt"), clips.getJSONObject(0).getString("endedAt"));
        assertEquals(secondClip.getString("path"), clips.getJSONObject(1).getString("path"));
        assertEquals(secondClip.getString("endedAt"), clips.getJSONObject(1).getString("endedAt"));
        assertEquals(1, mediaOfKind(index, "audio").length());
        JSONObject audio = findMedia(index, "audio");
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= elapsedBeforeRelease);
        Uri narration = reopened.mediaUri(projectUri, audio.getString("path"));
        assertEncodedAudio(narration);
        assertTrue("One encoded audio original spans both video clips and the gap between them",
                lastAudioSampleTimeMs(narration) >= elapsedBeforeRelease - 500);
    }

    @Test public void batchedVideoToPhotoDuringFinalizationKeepsOneContinuousNarration() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("narration starts before the video-to-photo sequence", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0));
        awaitInlineVideoRecording();
        SystemClock.sleep(1600);
        assertHeldNarration(started);
        onNotebook(activity -> {
            recordTouch.sendBatchedMove(0, 0, -120, 0);
            assertTrue("The regression requests a photo while video finalization is still pending",
                    ((InlineCamera) readField(activity, "inlineCamera")).isBusy());
        });
        await("the immediate left activation saves a JPEG after the MP4", 30_000, () -> {
            assertHeldNarration(started);
            JSONObject index = store.readProject(projectUri);
            return mediaOfKind(index, "video").length() == 1
                    && mediaOfKind(index, "photo").length() == 1 && inlineCameraIdle();
        });
        long elapsedAfterCaptures = RecordingService.snapshot().elapsedMs();
        await("narration continues after both linked captures finish", 5_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= elapsedAfterCaptures + 400);
        assertHeldNarration(started);
        JSONObject beforeRelease = store.readProject(projectUri);
        assertEquals(1, beforeRelease.getJSONArray("entries").length());
        assertEquals(ownedEntryId, beforeRelease.getJSONArray("entries").getJSONObject(0).getString("id"));
        assertEquals("The narration stays open through the media transition", 0, mediaOfKind(beforeRelease, "audio").length());
        JSONObject originalVideo = findMedia(beforeRelease, "video");
        JSONObject originalPhoto = findMedia(beforeRelease, "photo");
        assertEncodedVideo(store.mediaUri(projectUri, originalVideo.getString("path")));
        assertRealJpeg(store.mediaUri(projectUri, originalPhoto.getString("path")));
        long elapsedBeforeRelease = RecordingService.snapshot().elapsedMs();
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_UP, -120, 0));
        await("release saves the original narration after video and photo", 20_000,
                () -> recordingState(RecordingService.State.IDLE));
        awaitPublishedMedia(1, 1, 1);
        ProjectStore reopened = new ProjectStore(context);
        assertEquals(projectUri, reopened.currentProject());
        JSONObject index = reopened.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(3, entry.getJSONArray("media").length());
        assertEquals(1, mediaOfKind(index, "audio").length());
        assertEquals(1, mediaOfKind(index, "video").length());
        assertEquals(1, mediaOfKind(index, "photo").length());
        JSONObject video = findMedia(index, "video");
        JSONObject photo = findMedia(index, "photo");
        assertEquals(originalVideo.getString("path"), video.getString("path"));
        assertEquals(originalVideo.getString("startedAt"), video.getString("startedAt"));
        assertEquals(originalVideo.getString("endedAt"), video.getString("endedAt"));
        assertEquals(originalPhoto.getString("path"), photo.getString("path"));
        assertEquals(originalPhoto.getString("startedAt"), photo.getString("startedAt"));
        JSONObject audio = findMedia(index, "audio");
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= elapsedBeforeRelease);
        Uri narration = reopened.mediaUri(projectUri, audio.getString("path"));
        assertEncodedAudio(narration);
        assertTrue("The encoded original spans the video-to-photo transition",
                lastAudioSampleTimeMs(narration) >= elapsedBeforeRelease - 500);
    }

    @Test public void batchedVideoToTextDuringFinalizationKeepsTheLinkedCapture() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("narration starts before the video-to-text sequence", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0));
        awaitInlineVideoRecording();
        SystemClock.sleep(1600);
        assertHeldNarration(started);
        onNotebook(activity -> {
            recordTouch.sendBatchedMove(0, 0, 0, 120);
            assertTrue("Text is requested before the video job finishes saving",
                    ((InlineCamera) readField(activity, "inlineCamera")).isBusy());
            recordTouch.send(MotionEvent.ACTION_UP, 0, 120);
        });
        awaitNoteDialog();
        RecordingService.Snapshot writing = RecordingService.snapshot();
        assertEquals(RecordingService.State.RECORDING, writing.state);
        assertEquals(started.startedAt, writing.startedAt);
        assertEquals(ownedEntryId, writing.entryId);
        assertFalse(writing.paused);
        String text = "Video transition note\nThe next observation is linked while narration continues.";
        onNoteDialog(dialog -> ((EditText) dialog.findViewById(R.id.note_text_input)).setText(text));
        long elapsedAtEditor = writing.elapsedMs();
        await("narration continues while writing after video-zone exit", 5_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= elapsedAtEditor + 500);
        long elapsedAtSubmit = RecordingService.snapshot().elapsedMs();
        saveNote(null, text);
        await("text submission completes linked audio and video originals", 30_000,
                () -> recordingState(RecordingService.State.IDLE) && inlineCameraIdle());
        awaitPublishedMedia(1, 0, 1);
        ProjectStore reopened = new ProjectStore(context);
        assertEquals(projectUri, reopened.currentProject());
        JSONObject index = reopened.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(text, entry.getString("text"));
        assertEquals(2, entry.getJSONArray("media").length());
        assertEquals(1, mediaOfKind(index, "video").length());
        assertEquals(1, mediaOfKind(index, "audio").length());
        JSONObject video = findMedia(index, "video");
        JSONObject audio = findMedia(index, "audio");
        assertEncodedVideo(reopened.mediaUri(projectUri, video.getString("path")));
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= elapsedAtSubmit);
        Uri narration = reopened.mediaUri(projectUri, audio.getString("path"));
        assertEncodedAudio(narration);
        assertTrue(lastAudioSampleTimeMs(narration) >= elapsedAtSubmit - 500);
        assertFalse(audio.getJSONObject("metadata").has("pauseReason"));
    }

    @Test public void standaloneTextUsesDefaultMetadataAndRecentLongPressEditsPersist() throws Exception {
        assertFalse("The default must be exercised without an explicit opt-in", preferences.contains("metadata_enabled"));
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        String originalText = "Apparatus alignment\nReference mark is level with the sensor.";
        quickRecordGesture(0, 120);
        awaitNoteDialog();
        onNoteDialog(dialog -> assertNull("New text can be saved without a naming step",
                dialog.findViewById(R.id.note_title_input)));
        assertNoRecordingFor(300);
        saveNote(null, originalText);
        JSONObject original = awaitTextEntry(originalText);
        assertEquals("A standalone text entry has no fabricated media", 0, original.getJSONArray("media").length());
        assertDefaultMetadata(original.getJSONObject("textMetadata"));
        String entryId = original.getString("id");
        String createdAt = original.getString("createdAt");
        String textCreatedAt = original.getString("textCreatedAt");
        String metadata = original.getJSONObject("textMetadata").toString();
        awaitRecentTitle("Apparatus alignment");
        openRecentEntry();
        onEntryViewer(viewer -> {
            assertEquals(originalText, ((TextView) viewer.findViewById(EntryViewer.ENTRY_TEXT_ID)).getText().toString());
            assertNull("Valid text has no empty-media placeholder", viewer.findViewById(EntryViewer.PHOTO_ID));
            assertFalse(containsText(viewer.findViewById(EntryViewer.VIEWER_ROOT_ID), "No saved media"));
            assertNull(readField(viewer, "metadataDialog"));
            assertTrue(viewer.findViewById(EntryViewer.TEXT_METADATA_BUTTON_ID).performClick());
        });
        awaitMetadataDialog();
        onEntryViewer(viewer -> {
            AlertDialog details = (AlertDialog) readField(viewer, "metadataDialog");
            TextView text = details.findViewById(EntryViewer.METADATA_CONTENT_ID);
            assertEquals("Text metadata details", text.getContentDescription().toString());
            try {
                JSONObject displayed = new JSONObject(text.getText().toString());
                assertEquals(textCreatedAt, displayed.getString("textCreatedAt"));
                assertEquals(metadata, displayed.getJSONObject("textMetadata").toString());
            } catch (org.json.JSONException error) { throw new AssertionError(error); }
            details.dismiss();
            assertTrue(viewer.findViewById(EntryViewer.CLOSE_ID).performClick());
        });
        longPressRecent();
        onNoteDialog(dialog -> {
            assertNotNull("Editing a saved note exposes its optional title", dialog.findViewById(R.id.note_title_input));
            assertEquals(originalText, ((EditText) dialog.findViewById(R.id.note_text_input)).getText().toString());
        });
        String editedTitle = "Alignment check — repeat 2";
        String editedText = "Apparatus alignment\nReference mark shifted 2 mm; recalibrate before the next run.";
        saveNote(editedTitle, editedText);
        JSONObject edited = awaitTextEntry(editedText);
        assertEquals(entryId, edited.getString("id"));
        assertEquals(editedTitle, edited.getString("title"));
        assertEquals(createdAt, edited.getString("createdAt"));
        assertEquals(textCreatedAt, edited.getString("textCreatedAt"));
        assertEquals("Editing preserves original observation metadata", metadata, edited.getJSONObject("textMetadata").toString());
        assertTrue(Instant.parse(edited.getString("textUpdatedAt")).isAfter(Instant.parse(textCreatedAt)));
        assertEquals(0, edited.getJSONArray("media").length());
        awaitRecentTitle(editedTitle);
        notebook.close();
        notebook = null;
        store = new ProjectStore(context);
        JSONObject reopened = store.readProject(projectUri).getJSONArray("entries").getJSONObject(0);
        assertEquals(editedTitle, reopened.getString("title"));
        assertEquals(editedText, reopened.getString("text"));
        assertEquals(metadata, reopened.getJSONObject("textMetadata").toString());
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        awaitRecentTitle(editedTitle);
        openRecentEntry();
        onEntryViewer(viewer -> {
            assertEquals(editedTitle, ((TextView) viewer.findViewById(EntryViewer.ENTRY_TITLE_ID)).getText().toString());
            assertEquals(editedText, ((TextView) viewer.findViewById(EntryViewer.ENTRY_TEXT_ID)).getText().toString());
        });
        saveScreenshot("entry-text-edited.png");
        onEntryViewer(viewer -> assertTrue(viewer.findViewById(EntryViewer.CLOSE_ID).performClick()));
        assertFalse(RecordingService.isBusy());
    }

    @Test public void downwardGestureRecordsThroughTypingAndTextSaveFinalizesTheSameOriginal() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("held microphone starts before attaching text", 15_000,
                () -> recordingState(RecordingService.State.RECORDING));
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        await("audio contains a playable span before opening text", 5_000,
                () -> RecordingService.snapshot().elapsedMs() >= 1600);
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, 120);
            View mic = activity.findViewById(R.id.record_button);
            assertEquals("The text swipe follows the finger without horizontal drift", 0f, mic.getTranslationX(), 1f);
            assertEquals("The text swipe moves the microphone toward its target", 120 * recordTouch.density, mic.getTranslationY(), 1f);
            assertGuideCenter(activity, recordTouch.originX, recordTouch.originY);
            GestureGuideView guide = (GestureGuideView) readField(activity, "gestureGuide");
            assertEquals(RecordingGesture.Direction.DOWN, guide.getApproachDirection());
            assertEquals("The guide activates text at the downward target", 1f, guide.getApproachProgress(), .01f);
            assertEquals(RecordingGesture.Direction.DOWN, ((RecordingGesture) readField(activity, "gesture")).getDirection());
            recordTouch.send(MotionEvent.ACTION_UP, 0, 120);
        });
        awaitNoteDialog();
        RecordingService.Snapshot editing = RecordingService.snapshot();
        assertEquals("Downward release keeps narration recording while the editor is open", RecordingService.State.RECORDING, editing.state);
        assertFalse("Text entry must not pause the original", editing.paused);
        assertTrue(RecordingService.isRecording());
        assertEquals(started.startedAt, editing.startedAt);
        assertEquals(ownedEntryId, editing.entryId);
        assertEquals(projectUri, editing.projectUri);
        long elapsedAtEditor = editing.elapsedMs();
        String text = "Narration note\nPressure increased during this recording; narration continues while typing.";
        onNoteDialog(dialog -> ((EditText) dialog.findViewById(R.id.note_text_input)).setText(text));
        long typingUntil = SystemClock.elapsedRealtime() + 1600;
        long lastElapsed = elapsedAtEditor;
        do {
            RecordingService.Snapshot typing = RecordingService.snapshot();
            assertEquals(RecordingService.State.RECORDING, typing.state);
            assertFalse(typing.paused);
            long elapsed = typing.elapsedMs();
            assertTrue("The original audio timer keeps advancing during text entry", elapsed >= lastElapsed);
            lastElapsed = elapsed;
            assertEquals("Typing cannot create a second recording session", ownedEntryId, typing.entryId);
            assertEquals(started.startedAt, typing.startedAt);
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < typingUntil);
        long elapsedAtSubmit = RecordingService.snapshot().elapsedMs();
        assertTrue("The entire typing interval remains part of the active recording",
                elapsedAtSubmit >= elapsedAtEditor + 1600);
        saveNote(null, text);
        await("saving the text finalizes the continuous audio automatically", 20_000,
                () -> recordingState(RecordingService.State.IDLE));
        awaitPublishedMedia(1, 0, 0);
        JSONObject index = store.readProject(projectUri);
        assertEquals("Text and narration must remain one entry", 1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertEquals(text, entry.getString("text"));
        assertDefaultMetadata(entry.getJSONObject("textMetadata"));
        assertEquals(1, entry.getJSONArray("media").length());
        JSONObject audio = findMedia(index, "audio");
        assertNotNull(audio);
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        long captureDuration = audio.getLong("durationMs");
        assertTrue("Audio retains everything recorded before text submission", captureDuration >= elapsedAtSubmit);
        assertEquals(started.startedAt + captureDuration, Instant.parse(audio.getString("endedAt")).toEpochMilli());
        JSONObject metadata = audio.getJSONObject("metadata");
        assertEquals(captureDuration, metadata.getLong("monotonicDurationMs"));
        assertFalse("Continuous text entry must not create a paused span", metadata.has("pauseReason"));
        assertFalse(metadata.has("capturePausedAt"));
        assertFalse(metadata.has("pausedBeforeFinalizationMs"));
        long endObservation = Instant.parse(metadata.getJSONObject("endSnapshot").getString("observedAt")).toEpochMilli();
        assertTrue("Audio-end metadata is collected after the typing interval",
                endObservation >= started.startedAt + elapsedAtSubmit - 100);
        assertTrue(metadata.has("finalizationSnapshot"));
        Uri original = store.mediaUri(projectUri, audio.getString("path"));
        assertEncodedAudio(original);
        long encodedDuration = encodedDuration(original);
        assertEquals(encodedDuration, metadata.getLong("encodedDurationMs"));
        assertTrue("The actual encoded audio includes typing, allowing startup and encoder frame tolerance",
                encodedDuration >= elapsedAtSubmit - 500);
        assertTrue("Real encoded sample timestamps extend through the typing interval",
                lastAudioSampleTimeMs(original) >= elapsedAtSubmit - 500);
        ProjectStore reopenedStore = new ProjectStore(context);
        assertEquals("Text and audio remain in the original project ZIP", projectUri, reopenedStore.currentProject());
        JSONObject reopened = reopenedStore.readProject(projectUri).getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, reopened.getString("id"));
        assertEquals(text, reopened.getString("text"));
        assertEquals(audio.getString("path"), reopened.getJSONArray("media").getJSONObject(0).getString("path"));
        awaitRecentTitle("Narration note");
        openRecentEntry();
        onEntryViewer(viewer -> {
            assertEquals(text, ((TextView) viewer.findViewById(EntryViewer.ENTRY_TEXT_ID)).getText().toString());
            assertNotNull("Linked text and original audio share the inline viewer", viewer.findViewById(EntryViewer.AUDIO_PLAY_ID));
            assertTrue(viewer.findViewById(EntryViewer.CLOSE_ID).performClick());
        });
    }

    @Test public void skippingTextFinalizesContinuousAudioWithoutAddingText() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("held microphone produces audio before skip", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1600);
        ownedEntryId = RecordingService.snapshot().entryId;
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, 120);
            recordTouch.send(MotionEvent.ACTION_UP, 0, 120);
        });
        awaitNoteDialog();
        RecordingService.Snapshot editing = RecordingService.snapshot();
        assertEquals(RecordingService.State.RECORDING, editing.state);
        assertFalse(editing.paused);
        long elapsedAtEditor = editing.elapsedMs();
        await("narration continues while the user considers the text note", 5_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= elapsedAtEditor + 500);
        long elapsedAtSkip = RecordingService.snapshot().elapsedMs();
        onNoteDialog(dialog -> assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()));
        await("skipping text saves and releases the continuous recording", 20_000,
                () -> recordingState(RecordingService.State.IDLE));
        awaitPublishedMedia(1, 0, 0);
        JSONObject index = store.readProject(projectUri);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(ownedEntryId, entry.getString("id"));
        assertTrue(entry.optString("text").isEmpty());
        assertEquals(1, entry.getJSONArray("media").length());
        JSONObject audio = findMedia(index, "audio");
        assertNotNull(audio);
        assertEquals(editing.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= elapsedAtSkip);
        assertFalse(audio.getJSONObject("metadata").has("pauseReason"));
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        assertFalse(RecordingService.isBusy());
    }

    /** Recognized shake callbacks plus actual recording/touch/camera; not physical sensor validation. */
    @Test public void recognizedShakeTogglesLockedNarrationAndKeepsTouchPhotosAndMarkersLinked() throws Exception {
        assertTrue(preferences.edit().putBoolean("shake_recording_enabled", true).commit());
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        openFilesDialog();
        onNotebook(activity -> invokeShakeCallback(activity));
        assertNoRecordingFor(250);
        onFilesDialog(dialog -> assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()));
        awaitRecordWindowFocus(true);
        openAllNotesFromRecent();
        onNotebook(activity -> {
            invokeShakeCallback(activity);
        });
        assertNoRecordingFor(250);
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.close_notes).performClick()));
        awaitNotebookReady();
        await("Record has focus for recognized shake callbacks", 5_000, () -> {
            AtomicBoolean focused = new AtomicBoolean();
            onNotebook(activity -> focused.set(activity.hasWindowFocus()));
            return focused.get();
        });
        onNotebook(activity -> invokeShakeCallback(activity));
        await("recognized shake starts hands-free narration", 15_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().locked && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> {
            beginRecordTouch(activity);
            recordTouch.send(MotionEvent.ACTION_MOVE, 90, -90);
            recordTouch.send(MotionEvent.ACTION_UP, 90, -90);
        });
        await("a real northeast touch gesture still marks shake-started narration", 10_000,
                () -> RecordingService.snapshot().markerCount == 1);
        RecordingService.Snapshot afterTouch = RecordingService.snapshot();
        long elapsedAfterTouch = afterTouch.elapsedMs();
        assertEquals("Releasing a marker swipe preserves the hands-free recording", RecordingService.State.RECORDING, afterTouch.state);
        assertTrue(afterTouch.locked);
        assertEquals(started.entryId, afterTouch.entryId);
        assertEquals(started.startedElapsedRealtime, afterTouch.startedElapsedRealtime);
        awaitMicrophoneAtHome();
        onNotebook(activity -> {
            beginRecordTouch(activity);
            recordTouch.send(MotionEvent.ACTION_MOVE, -128, 0);
            recordTouch.send(MotionEvent.ACTION_UP, -128, 0);
        });
        await("a left touch swipe captures one linked native JPEG", 20_000,
                () -> findMedia(store.readProject(projectUri), "photo") != null && inlineCameraIdle());
        RecordingService.Snapshot afterPhoto = RecordingService.snapshot();
        assertEquals(RecordingService.State.RECORDING, afterPhoto.state);
        assertTrue(afterPhoto.locked);
        assertEquals(started.entryId, afterPhoto.entryId);
        assertEquals(started.startedElapsedRealtime, afterPhoto.startedElapsedRealtime);
        assertTrue(afterPhoto.elapsedMs() > elapsedAfterTouch);
        saveScreenshot("shake-started-linked-photo.png");
        onNotebook(activity -> invokeShakeCallback(activity));
        await("a second recognized shake ends narration", 15_000,
                () -> recordingState(RecordingService.State.IDLE));
        awaitPublishedMedia(1, 1, 0);
        JSONObject index = store.readProject(projectUri);
        assertEquals("Shake and touch actions belong to one uninterrupted capture entry", 1, index.getJSONArray("entries").length());
        JSONObject entry = readEntry(started.entryId);
        assertEquals(2, entry.getJSONArray("media").length());
        JSONObject audio = findMedia(index, "audio");
        JSONObject photo = findMedia(index, "photo");
        assertEquals(1, audio.getJSONArray("markers").length());
        assertEquals(audio.getString("id"), audio.getJSONArray("markers").getJSONObject(0).getString("mediaId"));
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(Instant.parse(photo.getString("startedAt")).toEpochMilli() >= started.startedAt);
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        assertRealJpeg(store.mediaUri(projectUri, photo.getString("path")));
    }

    @Test public void recognizedShakeStopsHeldVideoAndLateFingerReleaseCannotRestartCapture() throws Exception {
        assertTrue(preferences.edit().putBoolean("shake_recording_enabled", true).commit());
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        awaitRecordWindowFocus(true);
        onNotebook(this::beginRecordTouch);
        await("held narration starts before its linked video", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_MOVE, 120, 0));
        awaitInlineVideoRecording();
        long videoReadyAt = RecordingService.snapshot().elapsedMs();
        await("the linked video has time to encode real frames", 5_000,
                () -> RecordingService.snapshot().elapsedMs() >= videoReadyAt + 1600);
        assertHeldNarration(started);
        long elapsedBeforeStop = RecordingService.snapshot().elapsedMs();
        onNotebook(activity -> {
            assertTrue(((InlineCamera) readField(activity, "inlineCamera")).isRecording());
            invokeShakeCallback(activity);
            assertFalse("Shake-stop owns and cancels the existing hold", (Boolean) readField(activity, "touching"));
            assertFalse(activity.findViewById(R.id.record_button).isPressed());
            assertFalse(((GestureGuideView) readField(activity, "gestureGuide")).isActive());
        });
        await("shake-stop finalizes both recording owners before finger release", 20_000,
                () -> recordingState(RecordingService.State.IDLE) && inlineCameraIdle());
        awaitMicrophoneAtHome();
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, 0, 0);
            recordTouch.send(MotionEvent.ACTION_MOVE, -128, 0);
            recordTouch.send(MotionEvent.ACTION_UP, -128, 0);
        });
        assertNoRecordingFor(500);
        awaitPublishedMedia(1, 0, 1);
        JSONObject index = store.readProject(projectUri);
        assertEquals("A late pointer stream cannot create a second entry", 1, index.getJSONArray("entries").length());
        JSONObject entry = readEntry(ownedEntryId);
        assertEquals(2, entry.getJSONArray("media").length());
        JSONObject audio = findMedia(index, "audio"), video = findMedia(index, "video");
        assertNotNull(audio);
        assertNotNull(video);
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertTrue(audio.getLong("durationMs") >= elapsedBeforeStop);
        assertTrue(video.getLong("durationMs") >= 1500);
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        assertEncodedVideo(store.mediaUri(projectUri, video.getString("path")));
        assertTrue(inlineCameraIdle());
    }

    @Test public void noteEditorsLockDuringZipSaveAndRecoverTheirDraftAfterFailure() throws Exception {
        String entryId = UUID.randomUUID().toString();
        store.saveText(projectUri, entryId, "Original note", new JSONObject(), new JSONArray().put("original"));
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        awaitRecentTitle("Original note");
        longPressRecent();
        String savedTitle = "Saved calibration", savedText = "The calibration passed.";
        onNoteDialog(dialog -> {
            ((EditText) dialog.findViewById(R.id.note_title_input)).setText(savedTitle);
            ((EditText) dialog.findViewById(R.id.note_text_input)).setText(savedText);
            ((EditText) dialog.findViewById(R.id.note_tags_input)).setText("calibration, bench");
        });
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(projectUri)) {
            onNoteDialog(dialog -> assertTrue(dialog.findViewById(R.id.note_save).performClick()));
            assertTrue("The save is held at the real project ZIP provider", barrier.awaitBlocked(10, TimeUnit.SECONDS));
            onNoteDialog(dialog -> {
                assertNoteEditorEnabled(dialog, false);
                assertEquals(savedTitle, ((EditText) dialog.findViewById(R.id.note_title_input)).getText().toString());
                assertEquals(savedText, ((EditText) dialog.findViewById(R.id.note_text_input)).getText().toString());
                assertEquals("calibration, bench", ((EditText) dialog.findViewById(R.id.note_tags_input)).getText().toString());
            });
            assertFalse("The deterministic save barrier must not expire", barrier.timedOut());
        }
        JSONObject saved = awaitTextEntry(savedText);
        awaitNotebookReady();
        assertEquals(entryId, saved.getString("id"));
        assertEquals(savedTitle, saved.getString("title"));
        assertEquals(new JSONArray().put("calibration").put("bench").toString(), saved.getJSONArray("tags").toString());
        awaitRecentTitle(savedTitle);
        longPressRecent();
        String retryTitle = "Retry calibration", retryText = "The follow-up calibration passed.";
        onNoteDialog(dialog -> {
            ((EditText) dialog.findViewById(R.id.note_title_input)).setText(retryTitle);
            ((EditText) dialog.findViewById(R.id.note_text_input)).setText(retryText);
            ((EditText) dialog.findViewById(R.id.note_tags_input)).setText("calibration, retry");
        });
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(projectUri)) {
            TestDocumentsProvider.failNextZipWriteAfterTruncate();
            onNoteDialog(dialog -> assertTrue(dialog.findViewById(R.id.note_save).performClick()));
            assertTrue("The failing save also reaches the ZIP provider", barrier.awaitBlocked(10, TimeUnit.SECONDS));
            onNoteDialog(dialog -> assertNoteEditorEnabled(dialog, false));
            assertFalse(barrier.timedOut());
        }
        await("a failed ZIP write keeps the editor open for correction and retry", 15_000, () -> {
            AtomicBoolean retryReady = new AtomicBoolean();
            onNotebook(activity -> {
                AlertDialog dialog = (AlertDialog) readField(activity, "noteDialog");
                EditText body = dialog == null ? null : dialog.findViewById(R.id.note_text_input);
                retryReady.set(dialog != null && dialog.isShowing() && body != null && body.getError() != null
                        && dialog.findViewById(R.id.note_save).isEnabled());
            });
            return retryReady.get();
        });
        onNoteDialog(dialog -> {
            assertNoteEditorEnabled(dialog, true);
            assertEquals(retryTitle, ((EditText) dialog.findViewById(R.id.note_title_input)).getText().toString());
            assertEquals(retryText, ((EditText) dialog.findViewById(R.id.note_text_input)).getText().toString());
            assertEquals("calibration, retry", ((EditText) dialog.findViewById(R.id.note_tags_input)).getText().toString());
        });
        JSONObject afterFailure = readEntry(entryId);
        assertEquals("Failed writes preserve the previously committed note", savedText, afterFailure.getString("text"));
        assertEquals(savedTitle, afterFailure.getString("title"));
        assertEquals(saved.getJSONArray("tags").toString(), afterFailure.getJSONArray("tags").toString());
        onNoteDialog(dialog -> assertTrue(dialog.findViewById(R.id.note_save).performClick()));
        JSONObject retried = awaitTextEntry(retryText);
        awaitNotebookReady();
        assertEquals(entryId, retried.getString("id"));
        assertEquals(retryTitle, retried.getString("title"));
        assertEquals(new JSONArray().put("calibration").put("retry").toString(), retried.getJSONArray("tags").toString());
        assertEquals(saved.getString("createdAt"), retried.getString("createdAt"));
        assertEquals(0, retried.getJSONArray("media").length());
    }

    private static void assertNoteEditorEnabled(AlertDialog dialog, boolean expected) {
        for (int id : new int[]{R.id.note_title_input, R.id.note_text_input, R.id.note_tags_input}) {
            EditText input = dialog.findViewById(id);
            assertNotNull(input);
            assertEquals("Note input must follow the save transaction state: " + input.getHint(), expected, input.isEnabled());
        }
        assertEquals(expected, dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertEquals(expected, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
    }

    @Test public void northeastMarkersRearmAtCenterWithinOneHeldOriginal() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(this::beginRecordTouch);
        await("held narration is ready for northeast markers", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 900);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> {
            assertTrue("Held feedback stays active while using the one-finger marker gesture",
                    activity.findViewById(R.id.record_button).isPressed());
            recordTouch.send(MotionEvent.ACTION_MOVE, 30, -30);
            RecordingGesture gesture = (RecordingGesture) readField(activity, "gesture");
            assertEquals("The diagonal approach below the activation radius does not mark", RecordingGesture.Direction.NONE,
                    gesture.getDirection());
            assertEquals(0, RecordingService.snapshot().markerCount);
            recordTouch.send(MotionEvent.ACTION_MOVE, 70, -70);
            assertEquals("The old diagonal radius no longer activates the farther marker target", RecordingGesture.Direction.NONE,
                    gesture.getDirection());
            assertEquals(0, RecordingService.snapshot().markerCount);
            recordTouch.send(MotionEvent.ACTION_MOVE, 90, -90);
            assertEquals("The northeast target is a marker, not lock or video", RecordingGesture.Direction.MARKER,
                    gesture.getDirection());
            assertHeldNarration(started);
        });
        await("first northeast gesture is journaled", 10_000, () -> RecordingService.snapshot().markerCount == 1);
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, 85, -90);
            recordTouch.send(MotionEvent.ACTION_MOVE, 100, -70);
            assertEquals("Remaining in the marker region cannot repeat the action", 1, RecordingService.snapshot().markerCount);
            assertHeldNarration(started);
        });
        saveScreenshot("northeast-marker-active.png");
        onNotebook(activity -> {
            // A real batched move must still notice the historical return through home.
            recordTouch.sendBatchedMove(0, 0, 90, -90);
            assertEquals(RecordingGesture.Direction.MARKER,
                    ((RecordingGesture) readField(activity, "gesture")).getDirection());
            recordTouch.send(MotionEvent.ACTION_UP, 90, -90);
            assertFalse("Release ends held microphone feedback",
                    activity.findViewById(R.id.record_button).isPressed());
        });
        await("release stages and publishes the twice-marked original", 25_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 1);
        JSONObject entry = readEntry(started.entryId);
        assertEquals("The marker diagonal creates neither photo nor video", 1, entry.getJSONArray("media").length());
        JSONObject audio = entry.getJSONArray("media").getJSONObject(0);
        JSONArray markers = audio.getJSONArray("markers");
        assertEquals(2, markers.length());
        for (int i = 0; i < markers.length(); i++) {
            assertEquals(audio.getString("id"), markers.getJSONObject(i).getString("mediaId"));
            assertTrue(markers.getJSONObject(i).getLong("offsetMs") >= 800);
            assertTrue(markers.getJSONObject(i).getLong("offsetMs") <= audio.getLong("durationMs"));
        }
        assertTrue(markers.getJSONObject(1).getLong("offsetMs") > markers.getJSONObject(0).getLong("offsetMs"));
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        assertNull(RecordingService.snapshot().markerError);
    }

    @Test public void offCenterPressUsesTheActualMicCenterBeforeArmingActions() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        final float[] center = new float[2];
        onNotebook(activity -> {
            View mic = activity.findViewById(R.id.record_button);
            int[] position = new int[2];
            mic.getLocationOnScreen(position);
            center[0] = position[0] + mic.getWidth() * mic.getScaleX() / 2f;
            center[1] = position[1] + mic.getHeight() * mic.getScaleY() / 2f;
            beginRecordTouch(activity, -80, 35);
            assertGuideCenter(activity, center[0], center[1]);
        });
        await("an off-center hold still starts narration normally", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1000);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        onNotebook(activity -> {
            recordTouch.send(MotionEvent.ACTION_MOVE, 2, -2);
            RecordingGesture gesture = (RecordingGesture) readField(activity, "gesture");
            assertEquals("Off-center press jitter does not take a photo", RecordingGesture.Direction.NONE, gesture.getDirection());
            assertFalse("An off-center initial press waits to pass through home", gesture.isArmed());
            assertGuideCenter(activity, center[0], center[1]);
            recordTouch.send(MotionEvent.ACTION_MOVE, 80, -35); // Actual center, relative to this DOWN.
            assertTrue(gesture.isCentered());
            assertTrue(gesture.isArmed());
            recordTouch.send(MotionEvent.ACTION_MOVE, 90, -45); // Still inside the actual home radius.
            assertEquals(RecordingGesture.Direction.NONE, gesture.getDirection());
            assertEquals(0, RecordingService.snapshot().markerCount);
            assertGuideCenter(activity, center[0], center[1]);
            assertHeldNarration(started);
            recordTouch.send(MotionEvent.ACTION_MOVE, 170, -125); // Actual center + (90, -90).
            assertEquals(RecordingGesture.Direction.MARKER, gesture.getDirection());
            assertGuideCenter(activity, center[0], center[1]);
        });
        await("the rearmed off-center hold accepts one intended marker", 10_000,
                () -> RecordingService.snapshot().markerCount == 1);
        saveScreenshot("off-center-home-anchor.png");
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_UP, 170, -125));
        await("the off-center hold preserves one marked audio file", 25_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 1);
        JSONObject entry = readEntry(started.entryId);
        assertEquals("Jitter must not create stray photo or video files", 1, entry.getJSONArray("media").length());
        JSONObject audio = entry.getJSONArray("media").getJSONObject(0);
        assertEquals(1, audio.getJSONArray("markers").length());
        assertEquals(audio.getString("id"), audio.getJSONArray("markers").getJSONObject(0).getString("mediaId"));
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
    }

    @Test public void aSecondMarkedRecordingCanStageWhileTheFirstZipPublicationIsBlocked() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        RecordingService.Snapshot first;
        RecordingService.Snapshot second;
        TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(projectUri);
        try {
            onNotebook(this::beginRecordTouch);
            await("first original records before background publication", 15_000,
                    () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1100);
            first = RecordingService.snapshot();
            ownedEntryId = first.entryId;
            onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_UP, 0, 0));
            assertTrue("The first publication reaches the actual SAF ZIP write", barrier.awaitBlocked(10, TimeUnit.SECONDS));
            await("the microphone becomes idle while the ZIP provider remains blocked", 10_000,
                    () -> recordingState(RecordingService.State.IDLE));
            assertTrue("Provider publication is still blocked before the next capture", barrier.isBlocked());
            assertEquals(1, store.retryablePendingCount());

            // Do not wait for projectLoading: the cached project must permit capture while a
            // previous original waits on the selected storage provider.
            onNotebook(activity -> {
                assertTrue(activity.findViewById(R.id.record_button).isEnabled());
                beginRecordTouch(activity);
            });
            await("the next held microphone starts before the first ZIP write is released", 10_000,
                    () -> recordingState(RecordingService.State.RECORDING)
                            && !first.entryId.equals(RecordingService.snapshot().entryId)
                            && RecordingService.snapshot().elapsedMs() >= 1100);
            second = RecordingService.snapshot();
            assertNotEquals(first.entryId, second.entryId);
            assertTrue(barrier.isBlocked());
            onNotebook(activity -> {
                recordTouch.send(MotionEvent.ACTION_MOVE, 90, -90);
                assertHeldNarration(second);
                recordTouch.send(MotionEvent.ACTION_UP, 90, -90);
            });
            await("both originals finalize locally without waiting for the ZIP provider", 10_000,
                    () -> recordingState(RecordingService.State.IDLE) && store.retryablePendingCount() == 2);
            assertTrue("Both originals are staged before provider access is restored", barrier.isBlocked());
            assertFalse(barrier.timedOut());
            assertEquals(1, RecordingService.snapshot().markerCount);
            assertNull(RecordingService.snapshot().markerError);
            notebook.recreate();
            await("the recreated capture screen opens from cache while publication is blocked", 5_000, () -> {
                AtomicBoolean ready = new AtomicBoolean();
                onNotebook(activity -> {
                    View mic = activity.findViewById(R.id.record_button);
                    TextView title = activity.findViewById(R.id.project_title);
                    ready.set(mic != null && mic.isShown() && mic.isEnabled()
                            && title.getText().toString().contains("Native capture integration"));
                });
                return ready.get();
            });
            assertTrue("Recreating the screen does not release or cancel the publisher", barrier.isBlocked());
            assertEquals("Both staged originals remain owned by the publication queue", 2, store.retryablePendingCount());
            saveScreenshot("two-captures-awaiting-zip.png");
        } finally {
            barrier.release();
        }
        assertFalse("The test released the barrier before its fail-safe timeout", barrier.timedOut());
        await("both staged originals are added to the one ongoing project ZIP", 30_000,
                () -> !ProjectPublisher.snapshot().running
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 2);
        JSONObject firstEntry = readEntry(first.entryId);
        JSONObject secondEntry = readEntry(second.entryId);
        assertEquals(1, firstEntry.getJSONArray("media").length());
        assertEquals(1, secondEntry.getJSONArray("media").length());
        JSONObject firstAudio = firstEntry.getJSONArray("media").getJSONObject(0);
        JSONObject secondAudio = secondEntry.getJSONArray("media").getJSONObject(0);
        assertEquals(first.startedAt, Instant.parse(firstAudio.getString("startedAt")).toEpochMilli());
        assertEquals(second.startedAt, Instant.parse(secondAudio.getString("startedAt")).toEpochMilli());
        assertFalse("The second capture's marker cannot leak into the preceding audio", firstAudio.has("markers"));
        assertEquals(1, secondAudio.getJSONArray("markers").length());
        assertEquals(secondAudio.getString("id"), secondAudio.getJSONArray("markers").getJSONObject(0).getString("mediaId"));
        assertEncodedAudio(store.mediaUri(projectUri, firstAudio.getString("path")));
        assertEncodedAudio(store.mediaUri(projectUri, secondAudio.getString("path")));
        File[] archives = fixtureRoot.listFiles((directory, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip"));
        assertNotNull(archives);
        assertEquals("Successive captures update one project archive", 1, archives.length);
        assertEquals(0, store.retryablePendingCount());
    }

    @Test public void splitTouchMarkersImmediatelyBeforeReleaseStayOnTheOriginalAudio() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        onNotebook(activity -> {
            View marker = activity.findViewById(R.id.add_marker);
            assertTrue("Marker control remains discoverable while idle", marker.isShown());
            assertFalse("Idle audio cannot receive markers", marker.isEnabled());
            beginRecordTouch(activity);
        });
        await("held narration is ready for two final moments", 15_000,
                () -> recordingState(RecordingService.State.RECORDING) && RecordingService.snapshot().elapsedMs() >= 1800);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        long earliestOffset = started.elapsedMs();
        onNotebook(activity -> {
            View marker = activity.findViewById(R.id.add_marker);
            assertTrue(marker.isEnabled());
            assertEquals("Both controls share the genuine split-touch ancestor", recordTouch.target,
                    activity.findViewById(R.id.capture_pages));
            recordTouch.tapSecondPointer(marker);
            recordTouch.tapSecondPointer(marker);
            assertHeldNarration(started);
        });
        // Native Button clicks are posted after POINTER_UP. The next main turn releases
        // pointer 0 immediately after those clicks, without awaiting any journal write.
        onNotebook(activity -> {
            assertHeldNarration(started);
            recordTouch.send(MotionEvent.ACTION_UP, 0, 0);
        });
        await("both accepted markers survive immediate audio finalization", 20_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == 1);
        JSONObject entry = readEntry(ownedEntryId);
        assertEquals(1, entry.getJSONArray("media").length());
        JSONObject audio = entry.getJSONArray("media").getJSONObject(0);
        JSONArray markers = audio.getJSONArray("markers");
        assertEquals("Stopping must drain both accepted marker requests", 2, markers.length());
        assertNotEquals(markers.getJSONObject(0).getString("id"), markers.getJSONObject(1).getString("id"));
        long previous = -1;
        for (int i = 0; i < markers.length(); i++) {
            JSONObject marker = markers.getJSONObject(i);
            assertEquals(audio.getString("id"), marker.getString("mediaId"));
            long offset = marker.getLong("offsetMs");
            assertTrue("Marker offsets are captured at the input moment", offset >= earliestOffset);
            assertTrue(offset >= previous);
            assertTrue(offset <= Math.max(audio.getLong("durationMs"), audio.getJSONObject("metadata").getLong("encodedDurationMs")));
            assertNotNull(Instant.parse(marker.getString("createdAt")));
            previous = offset;
        }
        assertEquals(started.startedAt, Instant.parse(audio.getString("startedAt")).toEpochMilli());
        assertEquals(2, RecordingService.snapshot().markerCount);
        assertEquals(previous, RecordingService.snapshot().lastMarkerOffsetMs);
        assertNull(RecordingService.snapshot().markerError);
        assertEncodedAudio(store.mediaUri(projectUri, audio.getString("path")));
        awaitNotebookReady();
        onNotebook(activity -> assertFalse(activity.findViewById(R.id.add_marker).isEnabled()));
    }

    @Test public void markerSeekAndPlaybackEditsStayBoundToTheirOwnAudioClip() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        JSONObject first = captureAudioWithNativeMarker(null, 700, 2400);
        String entryId = ownedEntryId;
        String firstId = first.getString("id");
        String firstMarkerId = first.getJSONArray("markers").getJSONObject(0).getString("id");
        store.updateAudioMarker(projectUri, entryId, firstId, firstMarkerId, "First clip");
        JSONObject second = captureAudioWithNativeMarker(entryId, 1300, 4300);
        String secondId = second.getString("id");
        String markerId = second.getJSONArray("markers").getJSONObject(0).getString("id");
        long markerOffset = second.getJSONArray("markers").getJSONObject(0).getLong("offsetMs");
        store.updateAudioMarker(projectUri, entryId, secondId, markerId, "Second clip");
        String firstBytes = mediaDigest(first);
        String secondBytes = mediaDigest(second);
        notebook.recreate();
        awaitNotebookReady();
        openRecentEntry();
        await("both original audio controls resolve in the same entry", 10_000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onEntryViewer(viewer -> ready.set(mediaCard(viewer, firstId).findViewById(EntryViewer.AUDIO_PLAY_ID).isEnabled()
                    && mediaCard(viewer, secondId).findViewById(EntryViewer.AUDIO_PLAY_ID).isEnabled()));
            return ready.get();
        });
        onEntryViewer(viewer -> {
            View jump = taggedId(mediaCard(viewer, secondId), EntryViewer.MARKER_PLAY_ID, markerId);
            assertNotNull("Each marker exposes its original clip-specific action", jump);
            assertTrue(jump.performClick());
        });
        await("marker jumps within the second original, rather than playing the first clip", 5_000, () -> {
            AtomicBoolean reached = new AtomicBoolean();
            onEntryViewer(viewer -> {
                View firstCard = mediaCard(viewer, firstId), secondCard = mediaCard(viewer, secondId);
                SeekBar firstSeek = firstCard.findViewById(EntryViewer.AUDIO_SEEK_ID);
                SeekBar secondSeek = secondCard.findViewById(EntryViewer.AUDIO_SEEK_ID);
                reached.set(secondSeek.getProgress() >= markerOffset - 100
                        && secondSeek.getProgress() <= markerOffset + 1200
                        && "Pause audio".contentEquals(secondCard.findViewById(EntryViewer.AUDIO_PLAY_ID).getContentDescription())
                        && firstSeek.getProgress() == 0
                        && "Play audio".contentEquals(firstCard.findViewById(EntryViewer.AUDIO_PLAY_ID).getContentDescription()));
            });
            return reached.get();
        });
        AtomicInteger pausedOffset = new AtomicInteger();
        onEntryViewer(viewer -> {
            View secondCard = mediaCard(viewer, secondId);
            assertTrue(secondCard.findViewById(EntryViewer.AUDIO_PLAY_ID).performClick());
            SeekBar seek = secondCard.findViewById(EntryViewer.AUDIO_SEEK_ID);
            int target = Math.min(seek.getMax() - 300, seek.getProgress() + 700);
            Bundle progress = new Bundle();
            progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target);
            assertTrue("TalkBack seeks the actual paused audio without touch callbacks",
                    seek.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), progress));
            pausedOffset.set(target);
            assertTrue(secondCard.findViewById(EntryViewer.ADD_MARKER_ID).performClick());
        });
        awaitMarkerDialog();
        saveMarkerLabel("Playback check");
        await("playback marker is persisted on the second original", 15_000,
                () -> mediaById(readEntry(entryId), secondId).getJSONArray("markers").length() == 2);
        JSONObject playback = markerByLabel(mediaById(readEntry(entryId), secondId), "Playback check");
        assertNotNull(playback);
        assertEquals(secondId, playback.getString("mediaId"));
        assertEquals("Playback marker uses the paused native player position", pausedOffset.get(), playback.getLong("offsetMs"), 100);
        onEntryViewer(viewer -> assertTrue(taggedId(mediaCard(viewer, secondId), EntryViewer.MARKER_EDIT_ID, markerId).performClick()));
        awaitMarkerDialog();
        saveMarkerLabel("Renamed checkpoint");
        await("renamed label persists without changing marker identity", 15_000,
                () -> "Renamed checkpoint".equals(markerById(mediaById(readEntry(entryId), secondId), markerId).optString("label")));
        assertEquals(markerOffset, markerById(mediaById(readEntry(entryId), secondId), markerId).getLong("offsetMs"));
        saveScreenshot("audio-markers.png");
        String playbackId = playback.getString("id");
        onEntryViewer(viewer -> assertTrue(taggedId(mediaCard(viewer, secondId), EntryViewer.MARKER_EDIT_ID, playbackId).performClick()));
        awaitMarkerDialog();
        onMarkerDialog(dialog -> assertTrue(dialog.findViewById(EntryViewer.MARKER_DELETE_ID).performClick()));
        await("deleting one marker preserves the other clip and marker", 15_000,
                () -> mediaById(readEntry(entryId), secondId).getJSONArray("markers").length() == 1);
        JSONObject finalEntry = readEntry(entryId);
        assertEquals(2, finalEntry.getJSONArray("media").length());
        assertEquals("First clip", markerById(mediaById(finalEntry, firstId), firstMarkerId).getString("label"));
        assertEquals("Renamed checkpoint", markerById(mediaById(finalEntry, secondId), markerId).getString("label"));
        assertOriginalUnchanged(first, mediaById(finalEntry, firstId));
        assertOriginalUnchanged(second, mediaById(finalEntry, secondId));
        assertEquals(firstBytes, mediaDigest(mediaById(finalEntry, firstId)));
        assertEquals(secondBytes, mediaDigest(mediaById(finalEntry, secondId)));
        onEntryViewer(viewer -> viewer.dismiss());
        notebook.recreate();
        awaitNotebookReady();
        openRecentEntry();
        onEntryViewer(viewer -> {
            assertNotNull(taggedId(mediaCard(viewer, secondId), EntryViewer.MARKER_PLAY_ID, markerId));
            assertNull(taggedId(mediaCard(viewer, secondId), EntryViewer.MARKER_PLAY_ID, playbackId));
        });
    }

    @Test public void failedNewMarkerCanRetryWithChangedLabelThroughAnotherFailure() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        JSONObject audio = captureAudioWithNativeMarker(null, 500, 1700);
        String entryId = ownedEntryId, mediaId = audio.getString("id");
        String bytes = mediaDigest(audio);
        openRecentEntry();
        await("audio is ready for a new playback marker", 10_000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onEntryViewer(viewer -> ready.set(mediaCard(viewer, mediaId).findViewById(EntryViewer.ADD_MARKER_ID).isEnabled()));
            return ready.get();
        });
        onEntryViewer(viewer -> assertTrue(mediaCard(viewer, mediaId).findViewById(EntryViewer.ADD_MARKER_ID).performClick()));
        awaitMarkerDialog();
        failMarkerSubmission("First attempt");
        store.retryPending();
        JSONObject recovered = markerByLabel(mediaById(readEntry(entryId), mediaId), "First attempt");
        assertNotNull("The original add is recoverable after failed publication", recovered);
        failMarkerSubmission("Edited after failure");
        saveMarkerLabel("Final corrected label");
        JSONObject finalAudio = mediaById(readEntry(entryId), mediaId);
        assertEquals("Retries create one new marker beside the original recording marker", 2,
                finalAudio.getJSONArray("markers").length());
        JSONObject corrected = markerById(finalAudio, recovered.getString("id"));
        assertNotNull(corrected);
        assertEquals("Final corrected label", corrected.getString("label"));
        assertEquals(recovered.getLong("offsetMs"), corrected.getLong("offsetMs"));
        assertEquals(recovered.getString("createdAt"), corrected.getString("createdAt"));
        assertOriginalUnchanged(audio, finalAudio);
        assertEquals(bytes, mediaDigest(finalAudio));
    }

    private void failMarkerSubmission(String label) throws Exception {
        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        onMarkerDialog(dialog -> {
            ((EditText) dialog.findViewById(EntryViewer.MARKER_LABEL_INPUT_ID)).setText(label);
            assertTrue(dialog.findViewById(EntryViewer.MARKER_SAVE_ID).performClick());
        });
        await("failed marker publication leaves the same editor available for retry", 15_000, () -> {
            AtomicBoolean failed = new AtomicBoolean();
            onEntryViewer(viewer -> {
                AlertDialog dialog = (AlertDialog) readField(viewer, "markerDialog");
                if (dialog != null && dialog.isShowing()) {
                    EditText input = dialog.findViewById(EntryViewer.MARKER_LABEL_INPUT_ID);
                    failed.set(input.isEnabled() && input.getError() != null
                            && dialog.findViewById(EntryViewer.MARKER_SAVE_ID).isEnabled());
                }
            });
            return failed.get();
        });
    }

    @Test public void tagsAndAllWordSearchSurviveFilesDialogAndRecreation() throws Exception {
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        quickRecordGesture(0, 120);
        awaitNoteDialog();
        saveTaggedNote(null, "Lens alignment\nBlue optical bench.", "Bench, OPTICS, bench");
        JSONObject optics = awaitTextEntry("Lens alignment\nBlue optical bench.");
        assertTagSet(optics, "bench", "optics");
        longPressRecent();
        saveTaggedNote("Alignment reference", "Blue optics alignment reference.", "Bench, optics");
        optics = readEntry(optics.getString("id"));
        assertEquals("Alignment reference", optics.getString("title"));
        assertEquals("Blue optics alignment reference.", optics.getString("text"));
        assertTagSet(optics, "bench", "optics");
        String vacuumId = UUID.randomUUID().toString();
        store.saveText(projectUri, vacuumId, "Vacuum pressure baseline", new JSONObject(), new JSONArray().put("vacuum"));
        store.updateEntry(projectUri, vacuumId, "Vacuum reference", null);
        notebook.recreate();
        awaitNotebookReady();
        JSONObject audio = captureAudioWithNativeMarker(null, 600, 1700);
        String audioEntry = ownedEntryId;
        String audioId = audio.getString("id");
        String markerId = audio.getJSONArray("markers").getJSONObject(0).getString("id");
        store.updateAudioMarker(projectUri, audioEntry, audioId, markerId, "Blue checkpoint");
        store.updateEntry(projectUri, audioEntry, "Pump sounds", null, new JSONObject(), new JSONArray().put("bench"));
        notebook.recreate();
        awaitNotebookReady();
        awaitRecentTitle("Pump sounds");
        openAllNotesFromRecent();
        assertNotesMatch(new String[]{"Alignment reference", "Vacuum reference", "Pump sounds"}, new String[]{});
        setNotesQuery("bEnCh CHECKPOINT");
        assertNotesMatch(new String[]{"Pump sounds"}, new String[]{"Alignment reference", "Vacuum reference"});
        setNotesQuery("ALIGNMENT oPtIcS");
        assertNotesMatch(new String[]{"Alignment reference"}, new String[]{"Pump sounds", "Vacuum reference"});
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.clear_note_filters).performClick()));
        onNotebook(activity -> {
            View chip = describedView(activity.getWindow().getDecorView(), "Filter tag bench");
            assertNotNull(chip);
            assertTrue(chip.performClick());
        });
        assertNotesMatch(new String[]{"Alignment reference", "Pump sounds"}, new String[]{"Vacuum reference"});
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.markers_filter).performClick()));
        setNotesQuery("BLUE bench");
        assertNotesMatch(new String[]{"Pump sounds"}, new String[]{"Alignment reference", "Vacuum reference"});
        notebook.recreate();
        await("filtered notes restore after recreation", 10_000, () -> {
            AtomicBoolean restored = new AtomicBoolean();
            onNotebook(activity -> restored.set(activity.findViewById(R.id.notes_search).isShown()
                    && "BLUE bench".equals(((EditText) activity.findViewById(R.id.notes_search)).getText().toString())
                    && activity.findViewById(R.id.markers_filter).isSelected()));
            return restored.get();
        });
        assertNotesMatch(new String[]{"Pump sounds"}, new String[]{"Alignment reference", "Vacuum reference"});
        onNotebook(activity -> assertTrue(describedView(activity.getWindow().getDecorView(), "Filter tag bench").isSelected()));
        saveScreenshot("notes-search-tags-markers.png");
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.close_notes).performClick()));
        awaitNotebookReady();
        awaitRecentTitle("Pump sounds");
        openFilesDialog();
        onFilesDialog(dialog -> {
            assertTrue(dialog.findViewById(R.id.open_project_folder).isEnabled());
            assertTrue(dialog.findViewById(R.id.open_project_zip).isEnabled());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        });
        onNotebook(activity -> {
            assertEquals("Opening files must not clear the saved search query", "BLUE bench",
                    ((EditText) activity.findViewById(R.id.notes_search)).getText().toString());
            assertTrue(activity.findViewById(R.id.markers_filter).isSelected());
            assertTrue(describedView(activity.getWindow().getDecorView(), "Filter tag bench").isSelected());
        });
        assertEquals("Opening files cannot remove notes hidden by the current filters", 3,
                store.readProject(projectUri).getJSONArray("entries").length());
        awaitNotebookReady();
        swipeRecentCard(-80, MotionEvent.ACTION_UP);
        await("filtered notes reopen at their settled position", 10_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onNotebook(activity -> {
                View sheet = activity.findViewById(R.id.notes_sheet);
                shown.set(sheet.isShown() && Math.abs(sheet.getTranslationY()) < 1
                        && activity.findViewById(R.id.notes_search).isShown()
                        && !activity.findViewById(R.id.record_button).isShown());
            });
            return shown.get();
        });
        assertNotesMatch(new String[]{"Pump sounds"}, new String[]{"Alignment reference", "Vacuum reference"});
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.clear_note_filters).performClick()));
        assertNotesMatch(new String[]{"Alignment reference", "Vacuum reference", "Pump sounds"}, new String[]{});
        openEntryFromAllNotes("Alignment reference");
        onEntryViewer(viewer -> {
            TextView tags = viewer.findViewById(EntryViewer.ENTRY_TAGS_ID);
            assertNotNull(tags);
            assertTrue(tags.getText().toString().contains("bench"));
            assertTrue(tags.getText().toString().contains("optics"));
        });
        assertTagSet(readEntry(optics.getString("id")), "bench", "optics");
    }

    private JSONObject captureAudioWithNativeMarker(String attachEntryId, long markerAtMs, long stopAtMs) throws Exception {
        awaitNotebookReady();
        int previousAudioCount = mediaOfKind(store.readProject(projectUri), "audio").length();
        if (attachEntryId != null) {
            openRecentEntry();
            onEntryViewer(viewer -> {
                View attach = describedView(viewer.findViewById(EntryViewer.VIEWER_ROOT_ID), "Attach audio");
                assertNotNull(attach);
                assertTrue(attach.performClick());
            });
            awaitNotebookReady();
        }
        onNotebook(this::beginRecordTouch);
        await("held original reaches the marker input moment", 15_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= markerAtMs);
        RecordingService.Snapshot started = RecordingService.snapshot();
        ownedEntryId = started.entryId;
        if (attachEntryId != null) assertEquals(attachEntryId, started.entryId);
        onNotebook(activity -> {
            View marker = activity.findViewById(R.id.add_marker);
            assertTrue(marker.isShown());
            assertTrue(marker.isEnabled());
            recordTouch.tapSecondPointer(marker);
            assertHeldNarration(started);
        });
        await("native second-pointer tap writes one marker while the original finger stays held", 10_000,
                () -> RecordingService.snapshot().markerCount == 1);
        assertNull(RecordingService.snapshot().markerError);
        assertHeldNarration(started);
        if (attachEntryId != null) saveScreenshot("recording-marker-count.png");
        await("narration continues beyond the marker", 10_000,
                () -> recordingState(RecordingService.State.RECORDING)
                        && RecordingService.snapshot().elapsedMs() >= stopAtMs);
        onNotebook(activity -> recordTouch.send(MotionEvent.ACTION_UP, 0, 0));
        await("release saves the marked original", 20_000,
                () -> recordingState(RecordingService.State.IDLE)
                        && mediaOfKind(store.readProject(projectUri), "audio").length() == previousAudioCount + 1);
        awaitNotebookReady();
        JSONArray media = readEntry(started.entryId).getJSONArray("media");
        for (int i = 0; i < media.length(); i++) {
            JSONObject item = media.getJSONObject(i);
            if ("audio".equals(item.optString("kind"))
                    && Instant.parse(item.getString("startedAt")).toEpochMilli() == started.startedAt) {
                assertEquals(1, item.getJSONArray("markers").length());
                assertEquals(item.getString("id"), item.getJSONArray("markers").getJSONObject(0).getString("mediaId"));
                assertEncodedAudio(store.mediaUri(projectUri, item.getString("path")));
                return item;
            }
        }
        throw new AssertionError("The marked original is missing from its captured entry");
    }

    private JSONObject readEntry(String entryId) throws Exception {
        JSONArray entries = store.readProject(projectUri).getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++)
            if (entryId.equals(entries.getJSONObject(i).optString("id"))) return entries.getJSONObject(i);
        throw new AssertionError("Missing entry " + entryId);
    }

    private static JSONObject mediaById(JSONObject entry, String id) throws Exception {
        JSONArray media = entry.getJSONArray("media");
        for (int i = 0; i < media.length(); i++)
            if (id.equals(media.getJSONObject(i).optString("id"))) return media.getJSONObject(i);
        throw new AssertionError("Missing original " + id);
    }

    private static JSONObject markerById(JSONObject media, String id) throws Exception {
        JSONArray markers = media.getJSONArray("markers");
        for (int i = 0; i < markers.length(); i++)
            if (id.equals(markers.getJSONObject(i).optString("id"))) return markers.getJSONObject(i);
        return null;
    }

    private static JSONObject markerByLabel(JSONObject media, String label) throws Exception {
        JSONArray markers = media.getJSONArray("markers");
        for (int i = 0; i < markers.length(); i++)
            if (label.equals(markers.getJSONObject(i).optString("label"))) return markers.getJSONObject(i);
        return null;
    }

    private static View mediaCard(EntryViewer viewer, String mediaId) {
        View card = viewer.findViewById(EntryViewer.VIEWER_ROOT_ID).findViewWithTag(mediaId);
        assertNotNull("The original has its own inline card: " + mediaId, card);
        return card;
    }

    private static View taggedId(View view, int id, String tag) {
        if (view == null) return null;
        if (view.getId() == id && tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = taggedId(group.getChildAt(i), id, tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View describedView(View view, String description) {
        if (view == null) return null;
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = describedView(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void awaitMarkerDialog() throws Exception {
        await("marker label editor opens separately from the media", 5_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onEntryViewer(viewer -> {
                AlertDialog dialog = (AlertDialog) readField(viewer, "markerDialog");
                shown.set(dialog != null && dialog.isShowing() && dialog.findViewById(EntryViewer.MARKER_LABEL_INPUT_ID) != null);
            });
            return shown.get();
        });
    }

    private void onMarkerDialog(Consumer<AlertDialog> action) {
        onEntryViewer(viewer -> {
            AlertDialog dialog = (AlertDialog) readField(viewer, "markerDialog");
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            action.accept(dialog);
        });
    }

    private void saveMarkerLabel(String label) throws Exception {
        onMarkerDialog(dialog -> {
            ((EditText) dialog.findViewById(EntryViewer.MARKER_LABEL_INPUT_ID)).setText(label);
            assertTrue(dialog.findViewById(EntryViewer.MARKER_SAVE_ID).performClick());
        });
        await("marker label transaction finishes", 15_000, () -> {
            AtomicBoolean saved = new AtomicBoolean();
            onEntryViewer(viewer -> {
                AlertDialog dialog = (AlertDialog) readField(viewer, "markerDialog");
                saved.set(dialog == null || !dialog.isShowing());
            });
            return saved.get();
        });
    }

    private void saveTaggedNote(String title, String body, String tags) throws Exception {
        onNoteDialog(dialog -> ((EditText) dialog.findViewById(R.id.note_tags_input)).setText(tags));
        saveNote(title, body);
    }

    private static void assertTagSet(JSONObject entry, String... expected) throws Exception {
        JSONArray tags = entry.getJSONArray("tags");
        Set<String> actual = new java.util.HashSet<>();
        for (int i = 0; i < tags.length(); i++) actual.add(tags.getString(i));
        assertEquals("Duplicate normalized tags are not retained", actual.size(), tags.length());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(expected)), actual);
    }

    private void setNotesQuery(String query) {
        onNotebook(activity -> ((EditText) activity.findViewById(R.id.notes_search)).setText(query));
    }

    private void assertNotesMatch(String[] present, String[] absent) throws Exception {
        await("All notes applies the requested title/text/tag/marker filters", 10_000, () -> {
            AtomicBoolean matches = new AtomicBoolean(true);
            onNotebook(activity -> {
                View list = activity.findViewById(R.id.entry_list);
                if (!list.isShown()) matches.set(false);
                for (String title : present) if (!containsText(list, title)) matches.set(false);
                for (String title : absent) if (containsText(list, title)) matches.set(false);
            });
            return matches.get();
        });
    }

    private String mediaDigest(JSONObject media) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getContentResolver().openInputStream(store.mediaUri(projectUri, media.getString("path")))) {
            assertNotNull(input);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        return hex.toString();
    }

    private static void assertOriginalUnchanged(JSONObject before, JSONObject after) throws Exception {
        for (String key : new String[]{"id", "path", "sha256", "bytes", "startedAt", "endedAt", "durationMs", "metadata"})
            assertEquals("Marker edits preserve original field " + key, before.optString(key), after.optString(key));
    }

    private void openCameraFromCapture(int horizontalDp) throws Exception {
        await("capture page camera action is ready", 5_000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onNotebook(activity -> {
                View button = activity.findViewById(R.id.record_button);
                ready.set(button != null && button.isEnabled() && button.isShown());
            });
            return ready.get();
        });
        quickRecordGesture(horizontalDp, 0);
        assertEquals("Mic camera gestures stay inside MainActivity", Stage.RESUMED, notebookStage());
    }

    private void stopInlineCamera() throws Exception {
        // This scenario is owned until tearDown closes it (or explicitly clears it).
        if (notebook == null) return;
        onNotebook(activity -> {
            InlineCamera inline = (InlineCamera) readField(activity, "inlineCamera");
            if (inline != null) inline.stopVideo();
        });
        await("owned inline camera finishes before fixture cleanup", 20_000, this::inlineCameraIdle);
    }

    private boolean inlineCameraIdle() {
        AtomicBoolean stopped = new AtomicBoolean();
        onNotebook(activity -> {
            InlineCamera inline = (InlineCamera) readField(activity, "inlineCamera");
            stopped.set(inline == null || !inline.isBusy());
        });
        return stopped.get();
    }

    private void awaitInlineVideoRecording() throws Exception {
        await("swipe starts native inline video without another shutter step", 20_000, () -> {
            AtomicBoolean recording = new AtomicBoolean();
            onNotebook(activity -> {
                InlineCamera inline = (InlineCamera) readField(activity, "inlineCamera");
                View stop = activity.findViewById(InlineCamera.STOP_VIDEO_ID);
                TextView status = activity.findViewById(InlineCamera.STATUS_ID);
                cameraStatus = status == null ? "Missing inline camera status" : status.getText().toString();
                recording.set(inline != null && inline.isRecording() && stop != null && stop.isEnabled() && stop.isShown());
            });
            return recording.get();
        });
        assertEquals(Stage.RESUMED, notebookStage());
    }

    @Test public void shakeCallbacksAreIgnoredInSettingsBackgroundWithoutFocusAndWhenDisabled() throws Exception {
        assertTrue(preferences.edit().putBoolean("shake_recording_enabled", true).commit());
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        openShakeSettings();
        onNotebook(activity -> invokeShakeCallback(activity));
        assertNoRecordingFor(250);
        onSettingsDialog(dialog -> assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()));
        notebook.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        assertEquals(Stage.STOPPED, notebookStage());
        onNotebook(activity -> invokeShakeCallback(activity));
        assertNoRecordingFor(250);
        notebook.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        awaitNotebookReady();
        awaitRecordWindowFocus(true);
        try {
            onNotebook(activity -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE));
            awaitRecordWindowFocus(false);
            onNotebook(activity -> invokeShakeCallback(activity));
            assertNoRecordingFor(250);
        } finally {
            onNotebook(activity -> activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE));
        }
        awaitRecordWindowFocus(true);
        onNotebook(activity -> {
            ((MotionCaptureControls) readField(activity, "motionControls")).setEnabled(false);
            invokeShakeCallback(activity);
        });
        assertNoRecordingFor(400);
        assertEquals("Ineligible shakes create no captures or other media", 0,
                store.readProject(projectUri).getJSONArray("entries").length());
    }

    @Test public void shakeSettingIsSimpleAndPersistsWithoutReadingOldProfiles() throws Exception {
        String oldProfiles = "unreadable old directional profiles";
        assertTrue(preferences.edit().remove("shake_recording_enabled")
                .putBoolean("motion_controls", true).putString("motion_models_v1", oldProfiles).commit());
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        openShakeSettings();
        onSettingsDialog(dialog -> {
            android.widget.Switch shake = dialog.findViewById(R.id.motion_controls_switch);
            assertEquals("Shake to record", shake.getText().toString());
            assertTrue("Obsolete custom profiles do not disable the simple shake toggle", shake.isChecked());
            View settings = dialog.getWindow().getDecorView();
            assertFalse(containsText(settings, "Teach motion"));
            assertFalse(containsText(settings, "Sensitivity"));
            assertFalse(containsText(settings, "Reset motion"));
            assertFalse("Settings no longer contains motion sensitivity sliders", containsSeekBar(settings));
            shake.setChecked(false);
            assertFalse(preferences.getBoolean("shake_recording_enabled", true));
            assertEquals(oldProfiles, preferences.getString("motion_models_v1", null));
        });
        saveScreenshot("shake-settings.png");
        onSettingsDialog(dialog -> assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()));
        notebook.recreate();
        awaitNotebookReady();
        openShakeSettings();
        onSettingsDialog(dialog -> {
            android.widget.Switch shake = dialog.findViewById(R.id.motion_controls_switch);
            assertFalse("The simple switch survives recreation", shake.isChecked());
            shake.setChecked(true);
            assertTrue(preferences.getBoolean("shake_recording_enabled", false));
            assertEquals(oldProfiles, preferences.getString("motion_models_v1", null));
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        });
        assertFalse("Changing the switch does not itself start a recording", RecordingService.isBusy());
        assertEquals(0, store.readProject(projectUri).getJSONArray("entries").length());
    }

    private void awaitRecordWindowFocus(boolean expected) throws Exception {
        await("Record window focus becomes " + expected, 5_000, () -> {
            AtomicBoolean focused = new AtomicBoolean();
            onNotebook(activity -> focused.set(activity.hasWindowFocus()));
            return focused.get() == expected;
        });
    }

    private boolean containsSeekBar(View view) {
        if (view instanceof SeekBar) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsSeekBar(group.getChildAt(i))) return true;
        }
        return false;
    }

    private void openShakeSettings() throws Exception {
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.settings_button).performClick()));
        await("the native shake Settings dialog opens", 5_000, () -> {
            AtomicBoolean showing = new AtomicBoolean();
            onNotebook(activity -> {
                AlertDialog dialog = (AlertDialog) readField(activity, "settingsDialog");
                showing.set(dialog != null && dialog.isShowing());
            });
            return showing.get();
        });
    }

    private void onSettingsDialog(Consumer<AlertDialog> action) {
        onNotebook(activity -> {
            AlertDialog dialog = (AlertDialog) readField(activity, "settingsDialog");
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            action.accept(dialog);
        });
    }

    @Test public void darkDefaultAndLightSelectionSurviveActivityRecreation() throws Exception {
        assertFalse(preferences.contains("dark_mode"));
        notebook = ActivityScenario.launch(MainActivity.class);
        awaitNotebookReady();
        assertCapturePageOnly();
        onNotebook(activity -> assertEquals(Color.parseColor("#91C5FF"), accent(activity)));
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.settings_button).performClick()));
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int previousFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        try {
            await("dark-mode switch appears in Settings", 5_000,
                    () -> accessibilitySwitch(false));
            assertTrue("Dark mode defaults on in the Settings control", accessibilitySwitch(true));
            await("light-mode preference is committed", 5_000,
                    () -> preferences.contains("dark_mode") && !preferences.getBoolean("dark_mode", true));
            await("recreated activity uses the light blue theme", 10_000,
                    () -> currentAccent() == Color.parseColor("#2C68B2"));
            notebook.recreate();
            awaitNotebookReady();
            assertCapturePageOnly();
            assertFalse(preferences.getBoolean("dark_mode", true));
            onNotebook(activity -> assertEquals(Color.parseColor("#2C68B2"), accent(activity)));
            awaitNotebookResumed();
            saveScreenshot("native-light.png");
        } finally {
            info.flags = previousFlags;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
    }

    private boolean accessibilitySwitch(boolean click) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> controls = root.findAccessibilityNodeInfosByViewId(context.getPackageName() + ":id/dark_mode_switch");
            try {
                for (AccessibilityNodeInfo node : controls) if (node.isVisibleToUser() && node.isEnabled()) {
                    if (!click) return true;
                    assertTrue("Default theme switch must be checked", node.isChecked());
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                return false;
            } finally { for (AccessibilityNodeInfo node : controls) node.recycle(); }
        } finally { root.recycle(); }
    }

    private void awaitNotebookReady() throws Exception {
        await("selected project loads into the notebook", 10_000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onNotebook(activity -> {
                TextView project = activity.findViewById(R.id.project_title);
                View record = activity.findViewById(R.id.record_button);
                View sheet = activity.findViewById(R.id.notes_sheet);
                ready.set(project != null && project.getText().toString().contains("Native capture integration")
                        && record != null && record.getWidth() > 0 && record.isShown()
                        && sheet != null && sheet.getVisibility() == View.GONE
                        && !((Boolean) readField(activity, "projectOperation"))
                        && !((Boolean) readField(activity, "projectLoading")));
            });
            return ready.get();
        });
    }

    private void awaitNotebookResumed() throws Exception {
        await("notebook is resumed before its review screenshot", 5_000,
                () -> notebookStage() == Stage.RESUMED);
    }

    private void assertCapturePageOnly() {
        onNotebook(activity -> {
            View record = activity.findViewById(R.id.record_button);
            View entries = activity.findViewById(R.id.entry_list);
            assertNotNull(record);
            assertTrue("Capture opens with its primary recording control visible", record.isShown());
            assertTrue("The portrait capture page has a large tactile recording control",
                    record.getWidth() >= 220 * activity.getResources().getDisplayMetrics().density);
            assertTrue("Capture must not show the historical entry list", entries == null || !entries.isShown());
            assertEquals("The notes panel has finished closing", View.GONE,
                    activity.findViewById(R.id.notes_sheet).getVisibility());
            assertTrue(activity.findViewById(R.id.record_title).isShown());
            for (String name : new String[]{"tab_capture", "tab_notebook", "tab_files"}) {
                int legacyId = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
                assertTrue("Retired navigation control is absent: " + name,
                        legacyId == 0 || activity.findViewById(legacyId) == null);
            }
            View files = activity.findViewById(R.id.files_button);
            View settings = activity.findViewById(R.id.settings_button);
            assertTrue(files.isShown());
            assertTrue(settings.isShown());
            assertSame("Files belongs to the Settings header", settings.getParent(), files.getParent());
            assertTrue(activity.findViewById(R.id.recent_note_card).isShown());
            int[] filesPosition = new int[2], settingsPosition = new int[2], micPosition = new int[2];
            files.getLocationOnScreen(filesPosition);
            settings.getLocationOnScreen(settingsPosition);
            record.getLocationOnScreen(micPosition);
            assertEquals("Header actions share a row", settingsPosition[1], filesPosition[1]);
            assertTrue("The Files action sits beside Settings", filesPosition[0] + files.getWidth() <= settingsPosition[0]);
            assertTrue("Header actions stay above the microphone", filesPosition[1] < micPosition[1]);
        });
    }

    private void swipePage(int direction) {
        final float[] points = new float[4];
        onNotebook(activity -> {
            View pages = activity.findViewById(R.id.capture_pages);
            float density = activity.getResources().getDisplayMetrics().density;
            int[] position = new int[2];
            pages.getLocationOnScreen(position);
            points[0] = position[0] + pages.getWidth() * (direction < 0 ? .72f : .28f);
            points[1] = position[1] + 12 * density;
            points[2] = points[0] + direction * 120 * density;
            points[3] = points[0] + direction * 140 * density;
            Rect micBounds = new Rect();
            View mic = activity.findViewById(R.id.record_button);
            if (mic.isShown() && mic.getGlobalVisibleRect(micBounds))
                assertFalse("Horizontal swipe begins outside the microphone", micBounds.contains(Math.round(points[0]), Math.round(points[1])));
        });
        long down = SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        float[] xs = {points[0], points[2], points[3]};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), actions[i], xs[i], points[1], 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue("The device accepts the horizontal input", instrumentation.getUiAutomation().injectInputEvent(event, true)); }
            finally { event.recycle(); }
            SystemClock.sleep(25);
        }
    }

    private void openFilesDialog() throws Exception {
        onNotebook(activity -> assertTrue(activity.findViewById(R.id.files_button).performClick()));
        await("header Files action opens the project-file choices", 5_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onNotebook(activity -> {
                AlertDialog dialog = (AlertDialog) readField(activity, "filesDialog");
                shown.set(dialog != null && dialog.isShowing() && dialog.findViewById(R.id.files_dialog) != null);
            });
            return shown.get();
        });
    }

    private void onFilesDialog(Consumer<AlertDialog> action) {
        onNotebook(activity -> {
            AlertDialog dialog = (AlertDialog) readField(activity, "filesDialog");
            assertNotNull("MainActivity owns the file choices", dialog);
            assertTrue(dialog.isShowing());
            action.accept(dialog);
        });
    }

    private void awaitNoteDialog() throws Exception {
        await("downward gesture opens the native text editor", 5_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onNotebook(activity -> {
                AlertDialog dialog = (AlertDialog) readField(activity, "noteDialog");
                View save = dialog == null ? null : dialog.findViewById(R.id.note_save);
                shown.set(dialog != null && dialog.isShowing() && save != null && save.isEnabled());
            });
            return shown.get();
        });
    }

    private void onNoteDialog(Consumer<AlertDialog> action) {
        onNotebook(activity -> {
            AlertDialog dialog = (AlertDialog) readField(activity, "noteDialog");
            assertNotNull("MainActivity owns the native note editor", dialog);
            assertTrue(dialog.isShowing());
            action.accept(dialog);
        });
    }

    private void saveNote(String title, String body) throws Exception {
        onNoteDialog(dialog -> {
            if (title != null) ((EditText) dialog.findViewById(R.id.note_title_input)).setText(title);
            ((EditText) dialog.findViewById(R.id.note_text_input)).setText(body);
            View save = dialog.findViewById(R.id.note_save);
            assertTrue(save.isEnabled());
            assertTrue(save.performClick());
        });
        await("text save commits and refreshes the current project", 20_000, () -> {
            AtomicBoolean saved = new AtomicBoolean();
            onNotebook(activity -> {
                AlertDialog dialog = (AlertDialog) readField(activity, "noteDialog");
                saved.set((dialog == null || !dialog.isShowing())
                        && !((Boolean) readField(activity, "projectOperation"))
                        && !((Boolean) readField(activity, "projectLoading")));
            });
            return saved.get();
        });
    }

    private JSONObject awaitTextEntry(String expected) throws Exception {
        await("project index contains saved text", 15_000, () -> {
            JSONArray entries = store.readProject(projectUri).getJSONArray("entries");
            return entries.length() == 1 && expected.equals(entries.getJSONObject(0).optString("text"));
        });
        return store.readProject(projectUri).getJSONArray("entries").getJSONObject(0);
    }

    private void assertDefaultMetadata(JSONObject metadata) throws Exception {
        assertEquals("enabled", metadata.getJSONObject("optionalMetadata").getString("status"));
        assertEquals(Build.MODEL, metadata.getJSONObject("device").getString("model"));
        assertEquals(Build.VERSION.SDK_INT, metadata.getJSONObject("device").getInt("sdkInt"));
        assertTrue(metadata.getLong("elapsedRealtimeNanos") > 0);
        assertFalse(metadata.getJSONObject("timezone").getString("name").isEmpty());
        assertTrue(Instant.parse(metadata.getString("observedAt")).toEpochMilli() > 0);
        assertNotEquals("disabled", metadata.getJSONObject("location").getString("status"));
        assertNotEquals("permission_denied", metadata.getJSONObject("location").getString("status"));
        assertNotEquals("disabled", metadata.getJSONObject("sensors").getString("status"));
    }

    private void assertNoRecordingFor(long durationMs) {
        long until = SystemClock.elapsedRealtime() + durationMs;
        do {
            assertFalse("No recording may start while capture is expected to remain idle", RecordingService.isBusy());
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < until);
    }

    private void awaitRecentTitle(String title) throws Exception {
        await("the bottom recent card displays " + title, 10_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onNotebook(activity -> {
                TextView recent = activity.findViewById(R.id.recent_note_title);
                shown.set(recent != null && recent.isShown() && title.equals(recent.getText().toString()));
            });
            return shown.get();
        });
    }

    private void openRecentEntry() throws Exception {
        onNotebook(activity -> {
            TouchPath tap = recentCardTouch(activity);
            tap.send(MotionEvent.ACTION_DOWN, 0, 0);
            tap.send(MotionEvent.ACTION_UP, 0, 0);
        });
        awaitEntryViewer();
    }

    private TouchPath recentCardTouch(MainActivity activity) {
        View card = activity.findViewById(R.id.recent_note_card);
        View pages = activity.findViewById(R.id.capture_pages);
        assertTrue("The recent card is visible before its gesture", card.isShown());
        int[] cardPosition = new int[2];
        int[] pagesPosition = new int[2];
        card.getLocationOnScreen(cardPosition);
        pages.getLocationOnScreen(pagesPosition);
        return new TouchPath(pages,
                cardPosition[0] - pagesPosition[0] + card.getWidth() / 2f,
                cardPosition[1] - pagesPosition[1] + card.getHeight() / 2f);
    }

    private TouchPath notesRowTouch(MainActivity activity) {
        ScrollView scroll = activity.findViewById(R.id.notes_scroll);
        ViewGroup entries = activity.findViewById(R.id.entry_list);
        View pages = activity.findViewById(R.id.capture_pages);
        Rect viewport = new Rect();
        assertTrue("The notes scroll viewport is visible", scroll.getGlobalVisibleRect(viewport));
        float density = activity.getResources().getDisplayMetrics().density;
        int[] pagesPosition = new int[2];
        pages.getLocationOnScreen(pagesPosition);
        for (int i = 0; i < entries.getChildCount(); i++) {
            View row = entries.getChildAt(i);
            Rect visible = new Rect();
            if (!row.isClickable() || !row.getGlobalVisibleRect(visible)
                    || !visible.intersect(viewport) || visible.height() < 16 * density) continue;
            int x = visible.centerX();
            int y = visible.top + Math.min(visible.height() / 2, Math.round(24 * density));
            if (y + 100 * density >= viewport.bottom) continue;
            // Enter through the page and a real clickable row, exercising ScrollView's touch interception.
            return new TouchPath(pages, x - pagesPosition[0], y - pagesPosition[1]);
        }
        throw new AssertionError("No visible note row has room for the native downward gesture");
    }

    private void swipeRecentCard(int yDp, int terminalAction) {
        onNotebook(activity -> {
            TouchPath swipe = recentCardTouch(activity);
            swipe.send(MotionEvent.ACTION_DOWN, 0, 0);
            swipe.send(MotionEvent.ACTION_MOVE, 0, yDp);
            assertTrue("The recent-card gesture commits on release, not on movement",
                    activity.findViewById(R.id.record_button).isShown());
            swipe.send(terminalAction, 0, yDp);
        });
    }

    private void assertRecentGestureStayedOnRecord() throws Exception {
        awaitNotebookReady();
        assertCapturePageOnly();
        onNotebook(activity -> {
            EntryViewer viewer = (EntryViewer) readField(activity, "entryViewer");
            AlertDialog editor = (AlertDialog) readField(activity, "noteDialog");
            assertTrue("A rejected recent-card swipe must not open the latest entry", viewer == null || !viewer.isShowing());
            assertTrue("A rejected recent-card swipe must not trigger long-press editing", editor == null || !editor.isShowing());
        });
    }

    private void longPressRecent() throws Exception {
        final TouchPath[] press = new TouchPath[1];
        onNotebook(activity -> {
            View card = activity.findViewById(R.id.recent_note_card);
            assertTrue(card.isShown());
            assertTrue(card.isLongClickable());
            press[0] = new TouchPath(card);
            press[0].send(MotionEvent.ACTION_DOWN, 0, 0);
        });
        try {
            // Exercise Android's real long-press dispatch rather than invoking its listener.
            awaitNoteDialog();
            assertTrue("Long press waits for the platform's gesture timeout",
                    SystemClock.uptimeMillis() - press[0].downTime >= ViewConfiguration.getLongPressTimeout());
        } finally {
            onNotebook(activity -> press[0].send(MotionEvent.ACTION_UP, 0, 0));
        }
    }

    private void openAllNotesFromRecent() throws Exception {
        await("camera capture completes before opening All notes", 20_000, this::inlineCameraIdle);
        awaitNotebookReady();
        swipeRecentCard(-80, MotionEvent.ACTION_UP);
        awaitAllNotesVisible();
    }

    private void awaitAllNotesVisible() throws Exception {
        await("recent-card swipe opens the full notes list within Record", 10_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onNotebook(activity -> {
                View entries = activity.findViewById(R.id.entry_list);
                View back = activity.findViewById(R.id.close_notes);
                View sheet = activity.findViewById(R.id.notes_sheet);
                shown.set(entries != null && entries.isShown()
                        && back != null && back.isShown()
                        && sheet != null && sheet.getVisibility() == View.VISIBLE && sheet.getHeight() > 0
                        && Math.abs(sheet.getTranslationY()) < 1
                        && !activity.findViewById(R.id.record_button).isShown()
                        && activity.findViewById(R.id.files_button).isShown()
                        && containsText(activity.getWindow().getDecorView(), "All notes")
                        && !((Boolean) readField(activity, "projectOperation"))
                        && !((Boolean) readField(activity, "projectLoading")));
            });
            return shown.get();
        });
        assertEquals("All notes stays in the existing Activity", Stage.RESUMED, notebookStage());
    }

    private void openEntryFromAllNotes(String titlePart) throws Exception {
        await("saved entry card appears in All notes", 10_000, () -> {
            AtomicBoolean found = new AtomicBoolean();
            onNotebook(activity -> found.set(
                    clickableEntry(activity.findViewById(R.id.entry_list), titlePart) != null));
            return found.get();
        });
        onNotebook(activity -> {
            View card = clickableEntry(activity.findViewById(R.id.entry_list), titlePart);
            assertNotNull(card);
            assertTrue("Opening a saved entry must be handled by its card", card.performClick());
        });
        awaitEntryViewer();
    }

    private void awaitEntryViewer() throws Exception {
        await("entry opens in the native inline viewer", 10_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onNotebook(activity -> {
                EntryViewer viewer = (EntryViewer) readField(activity, "entryViewer");
                shown.set(viewer != null && viewer.isShowing()
                        && viewer.findViewById(EntryViewer.VIEWER_ROOT_ID) != null);
            });
            return shown.get();
        });
        assertEquals("Opening an entry must keep the notebook Activity in the foreground",
                Stage.RESUMED, notebookStage());
    }

    private void awaitMetadataDialog() throws Exception {
        await("metadata opens separately from the entry", 5_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onEntryViewer(viewer -> {
                AlertDialog dialog = (AlertDialog) readField(viewer, "metadataDialog");
                View content = dialog == null ? null : dialog.findViewById(EntryViewer.METADATA_CONTENT_ID);
                shown.set(dialog != null && dialog.isShowing() && content != null && content.isShown());
            });
            return shown.get();
        });
    }

    private View clickableEntry(View view, String titlePart) {
        if (view == null || !view.isShown()) return null;
        if (view.isClickable() && containsText(view, titlePart)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = clickableEntry(group.getChildAt(i), titlePart);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void onEntryViewer(Consumer<EntryViewer> action) {
        onNotebook(activity -> {
            EntryViewer viewer = (EntryViewer) readField(activity, "entryViewer");
            assertNotNull("MainActivity owns the inline entry dialog", viewer);
            assertTrue("Entry dialog must still be open", viewer.isShowing());
            action.accept(viewer);
        });
    }

    private void assertLinkedPhotoAndAudioViewer(JSONObject originalPhoto) throws Exception {
        await("inline photo renders a decoded bitmap from the real JPEG", 15_000, () -> {
            AtomicBoolean decoded = new AtomicBoolean();
            onEntryViewer(viewer -> {
                ImageView image = viewer.findViewById(EntryViewer.PHOTO_ID);
                if (image != null && image.isShown() && image.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) image.getDrawable()).getBitmap();
                    decoded.set(bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
                }
            });
            return decoded.get();
        });
        await("inline audio original becomes ready to play", 15_000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onEntryViewer(viewer -> {
                View play = viewer.findViewById(EntryViewer.AUDIO_PLAY_ID);
                ready.set(play != null && play.isShown() && play.isEnabled());
            });
            return ready.get();
        });
        onEntryViewer(viewer -> {
            assertNull("Detailed metadata is not mixed into the initial media view",
                    viewer.findViewById(EntryViewer.METADATA_CONTENT_ID));
            assertNull("Metadata opens only after its separate action", readField(viewer, "metadataDialog"));
            assertTrue(viewer.findViewById(EntryViewer.AUDIO_PLAY_ID).performClick());
        });
        await("inline audio actually plays and advances through encoded samples", 15_000, () -> {
            AtomicBoolean playing = new AtomicBoolean();
            onEntryViewer(viewer -> {
                View play = viewer.findViewById(EntryViewer.AUDIO_PLAY_ID);
                SeekBar seek = viewer.findViewById(EntryViewer.AUDIO_SEEK_ID);
                playing.set("Pause audio".contentEquals(play.getContentDescription())
                        && seek.isEnabled() && seek.getProgress() > 0);
            });
            return playing.get();
        });
        AtomicInteger pausedPosition = new AtomicInteger();
        onEntryViewer(viewer -> {
            assertTrue(viewer.findViewById(EntryViewer.AUDIO_PLAY_ID).performClick());
            assertEquals("Play audio", viewer.findViewById(EntryViewer.AUDIO_PLAY_ID).getContentDescription().toString());
            SeekBar seek = viewer.findViewById(EntryViewer.AUDIO_SEEK_ID);
            pausedPosition.set(seek.getProgress());
            assertTrue("Pause keeps the already-played position", pausedPosition.get() > 0);
        });
        long pausedUntil = SystemClock.elapsedRealtime() + 350;
        do {
            onEntryViewer(viewer -> assertEquals("Paused audio must stop advancing",
                    pausedPosition.get(), ((SeekBar) viewer.findViewById(EntryViewer.AUDIO_SEEK_ID)).getProgress()));
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < pausedUntil);
        saveScreenshot("entry-photo-audio.png");
        onEntryViewer(viewer -> assertTrue(viewer.findViewById(EntryViewer.METADATA_BUTTON_ID).performClick()));
        await("capture metadata opens in a separate dialog", 5_000, () -> {
            AtomicBoolean shown = new AtomicBoolean();
            onEntryViewer(viewer -> {
                AlertDialog metadata = (AlertDialog) readField(viewer, "metadataDialog");
                TextView content = metadata == null ? null : metadata.findViewById(EntryViewer.METADATA_CONTENT_ID);
                shown.set(metadata != null && metadata.isShowing() && content != null && content.isShown());
            });
            return shown.get();
        });
        onEntryViewer(viewer -> {
            AlertDialog metadata = (AlertDialog) readField(viewer, "metadataDialog");
            TextView content = metadata.findViewById(EntryViewer.METADATA_CONTENT_ID);
            try {
                JSONObject displayed = new JSONObject(content.getText().toString());
                assertEquals("photo", displayed.getString("kind"));
                assertEquals(originalPhoto.getString("path"), displayed.getString("path"));
                assertEquals(originalPhoto.getString("startedAt"), displayed.getString("startedAt"));
            } catch (org.json.JSONException error) { throw new AssertionError("Metadata must preserve the original capture record", error); }
            metadata.dismiss();
            assertTrue(viewer.findViewById(EntryViewer.CLOSE_ID).performClick());
        });
        awaitNotebookResumed();
    }

    private void assertInlineVideoViewer() throws Exception {
        await("native embedded video original becomes ready", 15_000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            onEntryViewer(viewer -> {
                View play = viewer.findViewById(EntryViewer.VIDEO_PLAY_ID);
                VideoView video = viewer.findViewById(EntryViewer.VIDEO_VIEW_ID);
                ready.set(play != null && play.isShown() && play.isEnabled() && video != null && video.isShown());
            });
            return ready.get();
        });
        onEntryViewer(viewer -> assertTrue(viewer.findViewById(EntryViewer.VIDEO_PLAY_ID).performClick()));
        await("embedded VideoView plays real frames with an advancing position", 15_000, () -> {
            AtomicBoolean playing = new AtomicBoolean();
            onEntryViewer(viewer -> {
                VideoView video = viewer.findViewById(EntryViewer.VIDEO_VIEW_ID);
                playing.set(video.isPlaying() && video.getCurrentPosition() > 100);
            });
            return playing.get();
        });
        assertEquals("Video playback stays inside the notebook Activity", Stage.RESUMED, notebookStage());
        saveScreenshot("entry-video-playing.png");
        onEntryViewer(viewer -> {
            View play = viewer.findViewById(EntryViewer.VIDEO_PLAY_ID);
            assertEquals("Pause video", play.getContentDescription().toString());
            assertTrue(play.performClick());
            assertFalse("Pause stops native video playback", ((VideoView) viewer.findViewById(EntryViewer.VIDEO_VIEW_ID)).isPlaying());
            assertEquals("Play video", play.getContentDescription().toString());
            SeekBar seek = viewer.findViewById(EntryViewer.VIDEO_SEEK_ID);
            assertTrue("The review screenshot let playback advance beyond the seek target", seek.getProgress() > 600);
            Bundle progress = new Bundle();
            progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0);
            assertTrue("TalkBack can seek paused video back to the beginning",
                    seek.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), progress));
            assertTrue(play.performClick());
        });
        await("native video resumes after the accessibility seek", 10_000, () -> {
            AtomicBoolean playing = new AtomicBoolean();
            onEntryViewer(viewer -> playing.set(((VideoView) viewer.findViewById(EntryViewer.VIDEO_VIEW_ID)).isPlaying()));
            return playing.get();
        });
        onEntryViewer(viewer -> {
            assertTrue("The actual VideoView restarted at the requested position, not its old pause position",
                    ((VideoView) viewer.findViewById(EntryViewer.VIDEO_VIEW_ID)).getCurrentPosition() < 600);
            assertTrue(viewer.findViewById(EntryViewer.CLOSE_ID).performClick());
        });
        awaitNotebookResumed();
    }

    /** Observe lifecycle directly; ActivityScenario.getState waits for global UI idle. */
    private Stage notebookStage() {
        AtomicReference<Stage> stage = new AtomicReference<>();
        onNotebook(activity -> stage.set(ActivityLifecycleMonitorRegistry.getInstance()
                .getLifecycleStageOf(activity)));
        return stage.get();
    }

    /** Keep native actions bounded even while held feedback continuously requests frames. */
    private void onNotebook(Consumer<MainActivity> action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notebook.onActivity(action::accept);
            return;
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        boolean posted = new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (!abandoned.get()) notebook.onActivity(action::accept);
            } catch (Throwable error) {
                failure.set(error);
            } finally { completed.countDown(); }
        });
        assertTrue("The main loop accepts the native test action", posted);
        try {
            if (!completed.await(5, TimeUnit.SECONDS)) {
                abandoned.set(true);
                fail("The native test action did not complete on the main thread within 5 seconds.");
            }
        } catch (InterruptedException interrupted) {
            abandoned.set(true);
            Thread.currentThread().interrupt();
            throw new AssertionError("The native test action was interrupted.", interrupted);
        }
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error != null) throw new AssertionError("The native test action failed.", error);
    }

    /** Inspect the Activity-owned dialog without adding test-only APIs to production code. */
    private static Object readField(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Native Activity/dialog ownership contract changed: " + name, error);
        }
    }

    private static void invokeShakeCallback(MainActivity activity) {
        try {
            java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("toggleRecordingFromShake");
            method.setAccessible(true);
            method.invoke(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Recognized shake callback failed", error);
        }
    }

    /** A deliberate new center press waits for the previous drag's bounded return animation. */
    private void awaitMicrophoneAtHome() throws Exception {
        await("the released microphone returns to its fixed home before the next center press", 3_000, () -> {
            AtomicBoolean home = new AtomicBoolean();
            onNotebook(activity -> {
                View mic = activity.findViewById(R.id.record_button);
                home.set(mic.isShown() && mic.isEnabled() && !mic.isPressed()
                        && Math.abs(mic.getTranslationX()) < 1f && Math.abs(mic.getTranslationY()) < 1f
                        && Math.abs(mic.getScaleX() - 1f) < .01f && Math.abs(mic.getScaleY() - 1f) < .01f);
            });
            return home.get();
        });
    }

    private void beginRecordTouch(MainActivity activity) {
        beginRecordTouch(activity, 0, 0);
    }

    private void beginRecordTouch(MainActivity activity, int offsetXDp, int offsetYDp) {
        View control = activity.findViewById(R.id.record_button);
        assertNotNull(control);
        assertTrue(control.isShown() && control.isEnabled());
        View pages = activity.findViewById(R.id.capture_pages);
        int[] controlPosition = new int[2];
        int[] pagePosition = new int[2];
        control.getLocationOnScreen(controlPosition);
        pages.getLocationOnScreen(pagePosition);
        // Enter through the parent so a microphone gesture must be excluded from page navigation.
        recordTouch = new TouchPath(pages,
                controlPosition[0] - pagePosition[0] + control.getWidth() * control.getScaleX() / 2f
                        + offsetXDp * control.getResources().getDisplayMetrics().density,
                controlPosition[1] - pagePosition[1] + control.getHeight() * control.getScaleY() / 2f
                        + offsetYDp * control.getResources().getDisplayMetrics().density);
        recordTouch.send(MotionEvent.ACTION_DOWN, 0, 0);
    }

    private static void assertGuideCenter(MainActivity activity, float screenX, float screenY) {
        GestureGuideView guide = (GestureGuideView) readField(activity, "gestureGuide");
        int[] position = new int[2];
        guide.getLocationOnScreen(position);
        assertEquals("The guide stays anchored at the actual microphone center", screenX,
                position[0] + (Float) readField(guide, "originX"), 1.5f);
        assertEquals("The guide stays anchored at the actual microphone center", screenY,
                position[1] + (Float) readField(guide, "originY"), 1.5f);
    }

    private void quickRecordGesture(int xDp, int yDp) {
        // One UI turn completes the swipe before the 180 ms hold callback can run.
        onNotebook(activity -> {
            beginRecordTouch(activity);
            if (xDp != 0 || yDp != 0) recordTouch.send(MotionEvent.ACTION_MOVE, xDp, yDp);
            recordTouch.send(MotionEvent.ACTION_UP, xDp, yDp);
        });
    }

    /** Keep raw pointer coordinates fixed to DOWN while the target translates/scales beneath them. */
    private static final class TouchPath {
        final View target;
        final long downTime = SystemClock.uptimeMillis();
        final float originX, originY, density;

        TouchPath(View target) { this(target, target.getWidth() / 2f, target.getHeight() / 2f); }

        TouchPath(View target, float localX, float localY) {
            this.target = target;
            int[] position = new int[2];
            target.getLocationOnScreen(position);
            originX = position[0] + localX;
            originY = position[1] + localY;
            density = target.getResources().getDisplayMetrics().density;
        }

        void send(int action, int xDp, int yDp) {
            int[] position = new int[2];
            target.getLocationOnScreen(position);
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    originX + xDp * density, originY + yDp * density, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            event.offsetLocation(-position[0], -position[1]);
            try { assertTrue("Native touch target must handle its gesture", target.dispatchTouchEvent(event)); }
            finally { event.recycle(); }
        }

        void sendPointers(int action, int[] ids, float[] xDp, float[] yDp) {
            int[] position = new int[2];
            target.getLocationOnScreen(position);
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
            for (int i = 0; i < ids.length; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = ids[i];
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = originX + xDp[i] * density;
                coordinates[i].y = originY + yDp[i] * density;
                coordinates[i].pressure = 1f;
                coordinates[i].size = 1f;
            }
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    ids.length, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
            event.offsetLocation(-position[0], -position[1]);
            try { assertTrue("The native touch hierarchy routes the pointer stream", target.dispatchTouchEvent(event)); }
            finally { event.recycle(); }
        }

        void tapSecondPointer(View control) {
            int[] parent = new int[2], button = new int[2];
            target.getLocationOnScreen(parent);
            control.getLocationOnScreen(button);
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].pressure = 1f;
                coordinates[i].size = 1f;
            }
            coordinates[0].x = originX;
            coordinates[0].y = originY;
            coordinates[1].x = button[0] + control.getWidth() / 2f;
            coordinates[1].y = button[1] + control.getHeight() / 2f;
            for (int action : new int[]{MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP}) {
                MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates,
                        0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
                event.offsetLocation(-parent[0], -parent[1]);
                try { assertTrue("The common ancestor routes the genuine second pointer", target.dispatchTouchEvent(event)); }
                finally { event.recycle(); }
            }
        }

        void sendBatchedMove(int historicalXDp, int historicalYDp, int currentXDp, int currentYDp) {
            int[] position = new int[2];
            target.getLocationOnScreen(position);
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(downTime, now - 1, MotionEvent.ACTION_MOVE,
                    originX + historicalXDp * density, originY + historicalYDp * density, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            event.addBatch(now, originX + currentXDp * density, originY + currentYDp * density, 1f, 1f, 0);
            // Translate the entire batch once; current and historical samples retain the fixed raw DOWN origin.
            event.offsetLocation(-position[0], -position[1]);
            assertEquals("The native event contains the historical center sample", 1, event.getHistorySize());
            try { assertTrue("Native touch target handles the batched motion", target.dispatchTouchEvent(event)); }
            finally { event.recycle(); }
        }
    }

    private boolean recordingState(RecordingService.State wanted) {
        RecordingService.Snapshot state = RecordingService.snapshot();
        if (state.state == RecordingService.State.ERROR) fail("Native recorder failed: " + state.error);
        return state.state == wanted;
    }

    private void assertHeldNarration(RecordingService.Snapshot started) {
        RecordingService.Snapshot current = RecordingService.snapshot();
        assertEquals("Camera actions keep the held narration active", RecordingService.State.RECORDING, current.state);
        assertEquals(started.entryId, current.entryId);
        assertEquals(started.projectUri, current.projectUri);
        assertEquals("Camera actions cannot restart the narration file", started.startedAt, current.startedAt);
        assertEquals(started.startedElapsedRealtime, current.startedElapsedRealtime);
        assertFalse("Camera actions do not implicitly lock held narration", current.locked);
        assertFalse("Camera-zone transitions do not pause narration", current.paused);
    }

    private JSONObject findMedia(JSONObject index, String kind) throws Exception {
        JSONArray matches = mediaOfKind(index, kind);
        return matches.length() == 0 ? null : matches.getJSONObject(0);
    }

    /** Recorder IDLE acknowledges local staging; only the archive proves publication. */
    private void awaitPublishedMedia(int audio, int photos, int videos) throws Exception {
        await("staged originals appear in the ongoing project ZIP", 30_000, () -> {
            JSONObject index = store.readProject(projectUri);
            return mediaOfKind(index, "audio").length() == audio
                    && mediaOfKind(index, "photo").length() == photos
                    && mediaOfKind(index, "video").length() == videos;
        });
    }

    private JSONArray mediaOfKind(JSONObject index, String kind) throws Exception {
        JSONArray matches = new JSONArray();
        JSONArray entries = index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONArray media = entries.getJSONObject(i).getJSONArray("media");
            for (int j = 0; j < media.length(); j++) {
                JSONObject item = media.getJSONObject(j);
                if (kind.equals(item.getString("kind"))) matches.put(item);
            }
        }
        return matches;
    }

    /** Sample the actual fill, away from the center icon/rim and independent of view transforms. */
    private static int recordFillPixel(View view) {
        assertTrue("The microphone has a drawable native size", view.getWidth() > 0 && view.getHeight() > 0);
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(image));
            int fill = image.getPixel(view.getWidth() / 4, view.getHeight() / 2);
            assertTrue("The sampled recording-control interior is painted", Color.alpha(fill) > 0);
            return fill;
        } finally { image.recycle(); }
    }

    private void assertRealJpeg(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input);
            BitmapFactory.decodeStream(input, null, bounds);
        }
        assertEquals("image/jpeg", bounds.outMimeType);
        assertTrue("Camera must produce a decodable JPEG width", bounds.outWidth > 0);
        assertTrue("Camera must produce a decodable JPEG height", bounds.outHeight > 0);
    }

    private void assertEncodedAudio(Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull(duration);
            assertTrue("Encoded audio must have a positive media duration", Long.parseLong(duration) > 0);
        } finally { retriever.release(); }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            boolean audioFound = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioFound = true;
                    extractor.selectTrack(i);
                    assertTrue("Audio track must contain encoded samples",
                            extractor.readSampleData(ByteBuffer.allocate(128 * 1024), 0) > 0);
                    break;
                }
            }
            assertTrue("Saved original must contain an audio track", audioFound);
        } finally { extractor.release(); }
    }

    private long encodedDuration(Uri uri) throws Exception {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, uri);
            String duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull(duration);
            return Long.parseLong(duration);
        } finally { reader.release(); }
    }

    private long lastAudioSampleTimeMs(Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                String mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                extractor.selectTrack(track);
                ByteBuffer sample = ByteBuffer.allocate(128 * 1024);
                long lastSampleUs = -1;
                do {
                    long sampleTimeUs = extractor.getSampleTime();
                    if (sampleTimeUs < 0) break;
                    sample.clear();
                    assertTrue("Every timed audio sample contains encoded data", extractor.readSampleData(sample, 0) > 0);
                    lastSampleUs = Math.max(lastSampleUs, sampleTimeUs);
                } while (extractor.advance());
                assertTrue("The audio original contains timestamped samples", lastSampleUs >= 0);
                return lastSampleUs / 1000;
            }
            throw new AssertionError("No encoded audio track was present");
        } finally { extractor.release(); }
    }

    private void assertEncodedVideo(Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            assertTrue(Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)) > 0);
            assertTrue(Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)) > 0);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            assertNotNull(duration);
            assertTrue("Encoded video must have positive media duration", Long.parseLong(duration) > 0);
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            assertNotNull("The original MP4 must decode a real video frame", frame);
            try { assertTrue(frame.getWidth() > 0 && frame.getHeight() > 0); }
            finally { frame.recycle(); }
        } finally { retriever.release(); }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            boolean found = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    found = true;
                    extractor.selectTrack(i);
                    assertTrue("Video track must contain encoded samples",
                            extractor.readSampleData(ByteBuffer.allocate(4 * 1024 * 1024), 0) > 0);
                    break;
                }
            }
            assertTrue("Saved original must contain a video track", found);
        } finally { extractor.release(); }
    }

    private static int accent(Activity activity) {
        TypedValue value = new TypedValue();
        assertTrue(activity.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true));
        return value.data;
    }

    private boolean containsText(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), value)) return true;
        }
        return false;
    }

    private void saveScreenshot(String name) throws IOException {
        name = "beta21-final-" + name;
        awaitScreenshotMainTurn();
        // Held feedback intentionally keeps drawing. A fully idle UI is neither required
        // nor a bounded condition here. Behavior is established by each test's assertions;
        // this short delay lets window transitions settle only for the review image.
        SystemClock.sleep(600);
        awaitScreenshotMainTurn();
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation must return the actual emulator screenshot", screenshot);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FieldnoteBetaReview");
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri image = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                assertNotNull("Review screenshot must receive a MediaStore destination", image);
                try (OutputStream output = context.getContentResolver().openOutputStream(image, "w")) {
                    assertNotNull(output);
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                assertEquals(1, context.getContentResolver().update(image, ready, null, null));
                Log.i("FieldnoteAcceptance", "Review screenshot " + name + " saved to " + image);
            } else {
                File directory = context.getExternalFilesDir("review");
                assertNotNull(directory);
                assertTrue(directory.isDirectory() || directory.mkdirs());
                try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
            }
        } finally { screenshot.recycle(); }
    }

    private void awaitScreenshotMainTurn() throws IOException {
        CountDownLatch delivered = new CountDownLatch(1);
        if (!new Handler(Looper.getMainLooper()).post(delivered::countDown))
            throw new IOException("The main loop could not accept the screenshot synchronization callback.");
        try {
            if (!delivered.await(3, TimeUnit.SECONDS))
                throw new IOException("The main loop did not respond within 3 seconds before a review screenshot.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Screenshot synchronization was interrupted.", interrupted);
        }
    }

    private int currentAccent() {
        AtomicInteger result = new AtomicInteger();
        onNotebook(activity -> result.set(accent(activity)));
        return result.get();
    }

    private interface Condition { boolean matches() throws Exception; }
    private void await(String description, long timeoutMs, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (condition.matches()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        RecordingService.Snapshot audio = RecordingService.snapshot();
        fail("Timed out: " + description + ". Audio=" + audio.state + ", error=" + audio.error
                + ", camera=" + cameraStatus);
    }

    private void restore(SharedPreferences target, Map<String, ?> previous) {
        SharedPreferences.Editor edit = target.edit().clear();
        for (Map.Entry<String, ?> entry : previous.entrySet()) {
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

    private void deleteOwnPendingCaptures() throws Exception {
        File pending = new File(context.getFilesDir(), "pending-captures").getCanonicalFile();
        File[] journals = pending.listFiles((directory, name) -> name.endsWith(".json"));
        if (journals == null) return;
        for (File journal : journals) {
            JSONObject record = new JSONObject(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
            if (!projectUri.toString().equals(record.optString("projectUri"))) continue;
            File file = new File(pending, record.getString("file")).getCanonicalFile();
            if (!pending.equals(file.getParentFile())) throw new IOException("Test pending file escaped its staging directory");
            for (String suffix : new String[]{"", ".json", ".json.bak", ".json.new"}) {
                File own = new File(file.getPath() + suffix);
                if (own.exists() && !own.delete()) throw new IOException("Could not remove owned pending test capture");
            }
        }
    }

    private void deleteFixture(File file) throws IOException {
        String root = fixtureRoot.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) throw new IOException("Test cleanup escaped its fixture");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Could not list owned fixture");
            for (File child : children) deleteFixture(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Could not remove owned fixture");
    }
}
