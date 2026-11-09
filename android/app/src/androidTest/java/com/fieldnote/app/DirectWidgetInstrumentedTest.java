package com.fieldnote.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actual RemoteViews clicks from a foreign visible UID, not a Fieldnote Activity. */
@RunWith(AndroidJUnit4.class)
public final class DirectWidgetInstrumentedTest {
    private static final String HOST = "com.fieldnote.widgettest.WidgetHostActivity";
    private Instrumentation instrumentation;
    private Context context;
    private ProjectStore store;
    private SharedPreferences preferences, bookmarks;
    private Map<String, ?> priorPreferences, priorBookmarks;
    private String priorOverlayMode, priorCameraMode;
    private String widgetUserId;
    private File fixtureRoot;
    private Uri fixtureTree, project;
    private final List<Uri> ownedProjects = new ArrayList<>();
    private final AtomicReference<Bundle> hostState = new AtomicReference<>();
    private final AtomicBoolean hostClosed = new AtomicBoolean();
    private final AtomicInteger mainActivityEvents = new AtomicInteger();
    private ActivityLifecycleCallback lifecycle;
    private boolean admitted, keepSmoke;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertFalse("Do not take over an existing recorder", RecordingService.isBusy());
        assertFalse("Do not take over an existing overlay", FloatingRecorderService.isShowing());
        admitted = true;
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        bookmarks = context.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        assertTrue(RecordingShortcuts.isRecordingWidgetIntent(context, RecordingShortcuts.recordingWidgetIntent(context)));
        priorPreferences = preferences.getAll();
        priorBookmarks = bookmarks.getAll();
        assertTrue(preferences.edit().remove("floating_text_draft").commit());
        // Missing RECORD_AUDIO is verified outside instrumentation by the cold-widget
        // acceptance check in docs/VALIDATION.md; instrumentation can override AppOps.
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        priorOverlayMode = appOpMode("SYSTEM_ALERT_WINDOW");
        priorCameraMode = appOpMode("CAMERA");
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        shell("appops set " + context.getPackageName() + " CAMERA deny");
        await("overlay access is unavailable", 5_000, () -> !Settings.canDrawOverlays(context));
        // Android's own launcher/CTS tests use this grant for a dedicated widget host.
        // Resolve the user explicitly: Android 16's appwidget command passes "current"
        // through as USER_CURRENT (-2), which the binding service rejects as locked.
        widgetUserId = shell("am get-current-user").trim();
        assertTrue("The foreground Android user must be a numeric ID", widgetUserId.matches("[0-9]+"));
        setWidgetBindingPermission(true);
        TestDocumentsProvider.clearFailures();
        ProjectPublisher.retry(context);
        await("publication is idle before fixture creation", 30_000, () -> !ProjectPublisher.snapshot().running);
        store = new ProjectStore(context);
        File base = TestDocumentsProvider.rootDirectory(context);
        assertTrue(base.isDirectory() || base.mkdirs());
        fixtureRoot = new File(base, "widget-native-" + UUID.randomUUID());
        assertTrue(fixtureRoot.mkdir());
        fixtureTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root:" + fixtureRoot.getName());
        project = createProject("Direct widget capture");
        lifecycle = (activity, stage) -> { if (activity instanceof MainActivity) mainActivityEvents.incrementAndGet(); };
        onMain(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle));
        context.getSystemService(NotificationManager.class).cancel(1004);
    }

    @After public void tearDown() throws Exception {
        if (!admitted) return;
        try {
            TestDocumentsProvider.clearFailures();
            onMain(() -> {
                FloatingRecorderService.hide(context);
                if ((RecordingService.snapshot().projectUri == null || ownedProjects.contains(RecordingService.snapshot().projectUri)) && RecordingService.isBusy())
                    RecordingService.stop(context);
            });
            await("owned overlay and audio finish", 25_000, () -> activeRecorder() == null && !RecordingService.isBusy());
            ProjectPublisher.retry(context);
            await("owned publications finish", 30_000, () -> !ProjectPublisher.snapshot().running);
            onMain(() -> {
                JobScheduler scheduler = context.getSystemService(JobScheduler.class);
                if (scheduler != null) scheduler.cancel(ProjectPublisher.JOB_ID);
            });
            await("late publication unwinds", 10_000, () -> !ProjectPublisher.snapshot().running);
            if (keepSmoke) {
                Bundle retained = new Bundle();
                retained.putString("widgetSmoke", new JSONObject().put("project", project.toString())
                        .put("fixtureRoot", fixtureRoot.getAbsolutePath())
                        .put("hostComponent", hostComponent().flattenToString())
                        .put("widgetX", hostState.get().getFloat("x"))
                        .put("widgetY", hostState.get().getFloat("y")).toString());
                instrumentation.sendStatus(0, retained);
                return; // Opt-in, dedicated-emulator manual smoke. Root removes the test install afterward.
            }
            // ActivityScenario.close() may leave its helper above the host in this task.
            context.startActivity(new Intent().setComponent(hostComponent()).setAction("com.fieldnote.widgettest.CLOSE")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            if (hostState.get() != null) await("the dedicated widget host is removed", 10_000, hostClosed::get);
            deleteOwnedPending();
            deleteTree(fixtureRoot, fixtureRoot);
        } finally {
            if (lifecycle != null) onMain(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle));
            if (!keepSmoke) {
                if (priorBookmarks != null) restore(bookmarks, priorBookmarks);
                if (priorPreferences != null) restore(preferences, priorPreferences);
                if (priorOverlayMode != null) shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW " + priorOverlayMode);
                if (priorCameraMode != null) shell("appops set " + context.getPackageName() + " CAMERA " + priorCameraMode);
                if (widgetUserId != null && widgetUserId.matches("[0-9]+")) setWidgetBindingPermission(false);
                context.getSystemService(NotificationManager.class).cancel(1004);
            }
            TestDocumentsProvider.clearFailures();
        }
    }

    @Test public void foreignWidgetTogglesCurrentProjectInPlaceAndReceivesLivePurpleState() throws Exception {
        Uri latest = createProject("Latest widget project");
        store.setCurrentProject(project);
        assertEquals(latest, store.lastCreatedProject());
        launchHost();
        float x = hostState.get().getFloat("x"), y = hostState.get().getFloat("y");
        awaitIdleWidget();
        assertFalse(Settings.canDrawOverlays(context));
        tapWidget();
        RecordingService.Snapshot started = awaitAudio();
        assertEquals(project, started.projectUri);
        assertTrue(started.locked);
        awaitRecordingWidget();
        assertActiveWidgetUsesImmutableStopBroadcast(started);
        int purple = hostState.get().getInt("backgroundCenter");
        assertTrue("The actual hosted recording background is violet", Color.red(purple) > Color.green(purple) + 20
                && Color.blue(purple) > Color.green(purple) + 20);
        assertEquals(x, hostState.get().getFloat("x"), 1f);
        assertEquals(y, hostState.get().getFloat("y"), 1f);
        assertHomeScreenOnly();
        saveScreenshot("beta23-widget-recording.png");
        tapWidget();
        JSONObject index = awaitSaved(project);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(started.entryId, entry.getString("id"));
        assertEquals(1, entry.getJSONArray("media").length());
        assertEquals(0, store.readProject(latest).getJSONArray("entries").length());
        assertEncodedAudio(store.mediaUri(project, media(index, "audio").getString("path")));
        awaitIdleWidget();
        assertHomeScreenOnly();
        saveScreenshot("beta23-widget-idle.png");
        keepSmoke = "true".equals(InstrumentationRegistry.getArguments().getString("keepWidgetSmoke"));
    }

    @Test public void widgetShowsAndStopsAnExistingAppRecordingWithoutChangingItsProject() throws Exception {
        Uri original = project;
        project = createProject("Newer project not used by the current recording");
        AtomicReference<MainActivity> launched = new AtomicReference<>();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(screen -> {
                launched.set(screen);
                RecordingService.start(screen, original, UUID.randomUUID().toString());
                RecordingService.lock(screen);
            });
            RecordingService.Snapshot started = awaitAudio();
            launchHost(false);
            await("the original app is fully backgrounded", 10_000, () -> onMainValue(() ->
                    ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(launched.get()) == Stage.STOPPED));
            int activityEventsBeforeTap = mainActivityEvents.get();
            awaitRecordingWidget();
            tapWidget();
            JSONObject index = awaitSaved(original);
            assertEquals(started.entryId, index.getJSONArray("entries").getJSONObject(0).getString("id"));
            assertEquals(1, index.getJSONArray("entries").length());
            assertEquals(0, store.readProject(project).getJSONArray("entries").length());
            assertEncodedAudio(store.mediaUri(original, media(index, "audio").getString("path")));
            awaitIdleWidget();
            assertEquals("The stop tap must not foreground the app", activityEventsBeforeTap, mainActivityEvents.get());
            assertFalse(FloatingRecorderService.isShowing());
            assertTrue(hostState.get().getBoolean("focused"));
        }
    }

    @Test public void missingCurrentProjectDoesNotFallBackToLatestOrOpenMain() throws Exception {
        store.setCurrentProject(null);
        assertEquals(project, store.lastCreatedProject());
        launchHost();
        tapWidget();
        assertSetupOnly();
        assertEquals(0, store.readProject(project).getJSONArray("entries").length());
    }

    @Test public void malformedCurrentProjectDoesNotFallBackToLatest() throws Exception {
        assertTrue(bookmarks.edit().putInt("current-project", 7).commit());
        assertEquals(project, store.lastCreatedProject());
        launchHost();
        tapWidget();
        assertSetupOnly();
        assertEquals(0, store.readProject(project).getJSONArray("entries").length());
    }

    @Test public void widgetBindsProjectBeforeResolutionAndNextRecordingFollowsSelection() throws Exception {
        Uri next = createProject("Next widget selection");
        store.setCurrentProject(project);
        launchHost();
        synchronized (staticField(ProjectStore.class, "CACHE_LOCK")) {
            tapWidget();
            await("the tap binds its project before local index loading", 5_000,
                    () -> RecordingService.snapshot().state == RecordingService.State.STARTING
                            && project.equals(RecordingService.snapshot().projectUri));
            store.setCurrentProject(next);
        }
        RecordingService.Snapshot first = awaitAudio();
        assertEquals(project, first.projectUri);
        awaitRecordingWidget();
        tapWidget();
        JSONObject firstIndex = awaitSaved(project);
        assertEquals(1, firstIndex.getJSONArray("entries").length());
        assertEquals(0, store.readProject(next).getJSONArray("entries").length());
        assertEncodedAudio(store.mediaUri(project, media(firstIndex, "audio").getString("path")));
        await("the first recorder finishes teardown", 10_000, () -> activeRecorder() == null);
        awaitIdleWidget();
        tapWidget();
        RecordingService.Snapshot second = awaitAudio();
        assertEquals(next, second.projectUri);
        assertNotEquals(first.entryId, second.entryId);
        // Selection changes during recording also leave the already bound original alone.
        store.setCurrentProject(project);
        awaitRecordingWidget();
        tapWidget();
        JSONObject nextIndex = awaitSaved(next);
        assertEquals(1, nextIndex.getJSONArray("entries").length());
        assertEquals(1, store.readProject(project).getJSONArray("entries").length());
        assertEncodedAudio(store.mediaUri(next, media(nextIndex, "audio").getString("path")));
        awaitIdleWidget();
        assertHomeScreenOnly();
    }

    @Test public void secondTapDuringProjectResolutionCancelsWithoutCreatingAnOriginal() throws Exception {
        launchHost();
        // Keep provider resolution pending without sleeping or delaying Android's main thread.
        synchronized (staticField(ProjectStore.class, "CACHE_LOCK")) {
            tapWidget();
            await("first tap reserves one locked start before project I/O", 5_000,
                    () -> RecordingService.snapshot().state == RecordingService.State.STARTING && RecordingService.snapshot().locked);
            tapWidget();
        }
        await("the cancelled start and worker terminate", 15_000,
                () -> !RecordingService.isBusy() && activeRecorder() == null);
        assertEquals(0, store.readProject(project).getJSONArray("entries").length());
        awaitIdleWidget();
        assertHomeScreenOnly();
    }

    @Test public void staleRecordingWidgetCannotStartAnotherOriginalAfterAudioEnds() throws Exception {
        launchHost();
        tapWidget();
        RecordingService.Snapshot first = awaitAudio();
        awaitRecordingWidget();
        cacheRenderedWidget();
        onMain(() -> RecordingService.stop(context));
        awaitSaved(project);
        awaitIdleWidget();
        int beforeClick = replayCachedWidget();
        tapWidget();
        await("a stale STOP refreshes the real host without starting audio", 10_000,
                () -> hostState.get().getInt("updates") > beforeClick && activeRecorder() == null && !RecordingService.isBusy());
        awaitIdleWidget();
        JSONObject index = store.readProject(project);
        assertEquals(1, index.getJSONArray("entries").length());
        assertEquals(first.entryId, index.getJSONArray("entries").getJSONObject(0).getString("id"));
        assertEquals(1, index.getJSONArray("entries").getJSONObject(0).getJSONArray("media").length());
        assertHomeScreenOnly();
    }

    @Test public void cachedStopCannotStopANewerOriginalAttachedToTheSameEntry() throws Exception {
        launchHost();
        tapWidget();
        RecordingService.Snapshot first = awaitAudio();
        awaitRecordingWidget();
        cacheRenderedWidget();
        onMain(() -> RecordingService.stop(context));
        awaitSaved(project);
        awaitIdleWidget();
        AtomicReference<MainActivity> launched = new AtomicReference<>();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(screen -> {
                launched.set(screen);
                RecordingService.start(screen, project, first.entryId);
                RecordingService.lock(screen);
            });
            RecordingService.Snapshot second = awaitAudio();
            assertEquals(first.entryId, second.entryId);
            assertNotEquals(first.startedElapsedRealtime, second.startedElapsedRealtime);
            int beforeClick = replayCachedWidget();
            await("the app is backgrounded before replaying the old STOP", 10_000, () -> onMainValue(() ->
                    ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(launched.get()) == Stage.STOPPED));
            int activityEventsBeforeTap = mainActivityEvents.get();
            tapWidget();
            await("the stale STOP refreshes the widget to the current session", 10_000,
                    () -> hostState.get().getInt("updates") > beforeClick);
            RecordingService.Snapshot after = RecordingService.snapshot();
            assertEquals(RecordingService.State.RECORDING, after.state);
            assertEquals(second.entryId, after.entryId);
            assertEquals(second.startedElapsedRealtime, after.startedElapsedRealtime);
            assertNull(after.error);
            assertEquals(activityEventsBeforeTap, mainActivityEvents.get());
            assertFalse(FloatingRecorderService.isShowing());
            awaitRecordingWidget();
            tapWidget(); // The refreshed button now owns the newer recording's STOP.
            await("both distinct originals are preserved in the shared entry", 30_000,
                    () -> !RecordingService.isBusy() && store.readProject(project).getJSONArray("entries")
                            .getJSONObject(0).getJSONArray("media").length() == 2);
            JSONObject index = store.readProject(project);
            assertEquals(1, index.getJSONArray("entries").length());
            JSONArray originals = index.getJSONArray("entries").getJSONObject(0).getJSONArray("media");
            assertNotEquals(originals.getJSONObject(0).getString("path"), originals.getJSONObject(1).getString("path"));
            for (int i = 0; i < originals.length(); i++)
                assertEncodedAudio(store.mediaUri(project, originals.getJSONObject(i).getString("path")));
        }
    }

    private void cacheRenderedWidget() throws Exception {
        hostCommand("com.fieldnote.widgettest.CACHE");
        await("the launcher caches the displayed active RemoteViews", 5_000,
                () -> hostState.get().getBoolean("cached"));
    }

    private int replayCachedWidget() throws Exception {
        hostCommand("com.fieldnote.widgettest.REPLAY");
        await("the launcher displays its cached recording button", 10_000,
                () -> hostState.get().getBoolean("replaying") && hostState.get().getBoolean("focused")
                        && "Recording. Tap to stop and save.".equals(hostState.get().getString("description")));
        return hostState.get().getInt("updates");
    }

    private void hostCommand(String action) {
        context.startActivity(new Intent().setComponent(hostComponent()).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private RecordingService.Snapshot awaitAudio() throws Exception {
        await("the widget starts encoded narration", 15_000,
                () -> RecordingService.snapshot().state == RecordingService.State.RECORDING
                        && RecordingService.snapshot().elapsedMs() >= 800);
        return RecordingService.snapshot();
    }

    private JSONObject awaitSaved(Uri destination) throws Exception {
        await("the stopped original is published", 30_000,
                () -> !RecordingService.isBusy() && media(store.readProject(destination), "audio") != null);
        return store.readProject(destination);
    }

    private void awaitRecordingWidget() throws Exception {
        await("AppWidgetManager delivers the recording state to the foreign host", 10_000,
                () -> "Recording. Tap to stop and save.".equals(hostState.get().getString("description")));
    }

    private void awaitIdleWidget() throws Exception {
        await("AppWidgetManager delivers the idle state to the foreign host", 10_000,
                () -> context.getString(R.string.widget_mic_description).equals(hostState.get().getString("description")));
    }

    private void assertHomeScreenOnly() {
        assertEquals("The widget never enters a Fieldnote Activity lifecycle", 0, mainActivityEvents.get());
        assertFalse("The widget does not open an independent floating control", FloatingRecorderService.isShowing());
        assertTrue(hostState.get().getBoolean("focused"));
    }

    private void assertActiveWidgetUsesImmutableStopBroadcast(RecordingService.Snapshot capture) throws Exception {
        AtomicReference<IntentSender> sent = new AtomicReference<>();
        Context intercepted = new ContextWrapper(context) {
            @Override public void startIntentSender(IntentSender sender, Intent fill, int mask, int values, int extra) {
                sent.set(sender);
            }
            @Override public void startIntentSender(IntentSender sender, Intent fill, int mask, int values, int extra, Bundle options) {
                sent.set(sender);
            }
        };
        onMain(() -> {
            View rendered = RecordingWidget.views(context).apply(intercepted, new FrameLayout(intercepted));
            assertTrue(rendered.findViewById(R.id.widget_mic_button).performClick());
        });
        PendingIntent expected = PendingIntent.getBroadcast(context, 63,
                RecordingShortcuts.recordingWidgetStopIntent(context, capture),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        assertNotNull("The active RemoteViews created a STOP broadcast capability", expected);
        assertEquals(expected.getIntentSender(), sent.get());
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(expected.isImmutable());
            assertTrue(expected.isBroadcast());
            assertFalse(expected.isForegroundService());
        }
        assertEquals("Inspecting its click does not stop the original", capture.startedElapsedRealtime,
                RecordingService.snapshot().startedElapsedRealtime);
        assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
    }

    private void assertSetupOnly() throws Exception {
        await("setup notification replaces an automatic app launch", 10_000, () -> setupNotification() != null);
        await("unsatisfied widget service stops", 10_000, () -> activeRecorder() == null);
        assertFalse(FloatingRecorderService.isShowing());
        assertFalse(RecordingService.isBusy());
        assertEquals(0, mainActivityEvents.get());
        StatusBarNotification notification = setupNotification();
        assertNotNull(notification.getNotification().contentIntent);
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.getNotification().contentIntent.isActivity());
        PendingIntent openFieldnote = PendingIntent.getActivity(context, 2005,
                new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        assertNotNull("Setup opens Fieldnote without the latest-project Record now action", openFieldnote);
        assertEquals(openFieldnote, notification.getNotification().contentIntent);
    }

    private StatusBarNotification setupNotification() {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (notification.getId() == 1004) return notification;
        return null;
    }

    private void launchHost() throws Exception { launchHost(true); }

    private void launchHost(boolean expectNoMain) throws Exception {
        hostClosed.set(false);
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (code == 3) hostClosed.set(true);
                else hostState.set(data);
            }
        };
        context.startActivity(new Intent().setComponent(hostComponent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("provider", new ComponentName(context, RecordingWidget.class))
                .putExtra("micId", R.id.widget_mic_button).putExtra("results", receiver));
        await("foreign widget host is focused and laid out", 10_000, () -> {
            Bundle state = hostState.get();
            if (state == null) return false;
            assertNull("The native widget host must bind its provider", state.getString("error"));
            return state.getBoolean("focused") && state.getInt("width") > 0;
        });
        assertNotEquals("The host must really have a different Android UID", Process.myUid(), hostState.get().getInt("uid"));
        assertNotEquals(Process.myPid(), hostState.get().getInt("pid"));
        if (expectNoMain) assertEquals(0, mainActivityEvents.get());
    }

    private ComponentName hostComponent() {
        return new ComponentName(instrumentation.getContext().getPackageName(), HOST);
    }

    private void tapWidget() {
        Bundle position = hostState.get();
        long time = SystemClock.uptimeMillis();
        inject(time, MotionEvent.ACTION_DOWN, position.getFloat("x"), position.getFloat("y"));
        inject(time, MotionEvent.ACTION_UP, position.getFloat("x"), position.getFloat("y"));
    }

    private Uri createProject(String name) throws Exception {
        Uri created = store.createWorkingProject(fixtureTree, name); ownedProjects.add(created); return created;
    }

    private JSONObject media(JSONObject index, String kind) throws Exception {
        JSONArray entries = index.getJSONArray("entries");
        for (int e = 0; e < entries.length(); e++) {
            JSONArray originals = entries.getJSONObject(e).getJSONArray("media");
            for (int m = 0; m < originals.length(); m++)
                if (kind.equals(originals.getJSONObject(m).optString("kind"))) return originals.getJSONObject(m);
        }
        return null;
    }

    private void assertEncodedAudio(Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    assertTrue(extractor.readSampleData(ByteBuffer.allocate(1024 * 1024), 0) > 0);
                    return;
                }
            }
            fail("The saved original must contain encoded audio samples");
        } finally { extractor.release(); }
    }

    private void saveScreenshot(String name) throws Exception {
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File directory = context.getExternalFilesDir(null);
        assertNotNull(directory);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }

    private void inject(long began, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(began, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); }
        finally { event.recycle(); }
    }
    private RecordingService activeRecorder() { return (RecordingService) staticField(RecordingService.class, "activeInstance"); }
    private static Object staticField(Class<?> type, String name) {
        try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private void onMain(Action action) throws Exception { onMainValue(() -> { action.run(); return null; }); }
    private <T> T onMainValue(Value<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(action.get()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() instanceof Exception) throw (Exception) error.get();
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
    private void await(String label, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do { if (condition.met()) return; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < deadline);
        fail("Timed out: " + label + "; audio=" + RecordingService.snapshot().state + "; error=" + RecordingService.snapshot().error);
    }
    private String appOpMode(String op) throws Exception {
        Matcher mode = Pattern.compile("(?m)^\\s*" + op + ":\\s*(allow|ignore|deny|default|foreground)\\b")
                .matcher(shell("appops get " + context.getPackageName() + " " + op));
        return mode.find() ? mode.group(1) : "default";
    }
    private void setWidgetBindingPermission(boolean allowed) throws Exception {
        String result = shell("appwidget " + (allowed ? "grantbind" : "revokebind") + " --package "
                + instrumentation.getContext().getPackageName() + " --user " + widgetUserId);
        assertFalse("The dedicated widget-host binding command must succeed",
                result.contains("Exception") || result.contains("Error") || result.contains("usage:"));
    }
    private String shell(String command) throws Exception {
        ParcelFileDescriptor output = instrumentation.getUiAutomation().executeShellCommand(command);
        return new String(bytes(new ParcelFileDescriptor.AutoCloseInputStream(output)), StandardCharsets.UTF_8);
    }
    private byte[] bytes(InputStream input) throws Exception {
        assertNotNull(input);
        try (InputStream owned = input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = owned.read(buffer)) != -1) result.write(buffer, 0, n);
            return result.toByteArray();
        }
    }
    private void restore(SharedPreferences target, Map<String, ?> prior) {
        SharedPreferences.Editor edit = target.edit().clear();
        for (Map.Entry<String, ?> item : prior.entrySet()) {
            Object value = item.getValue(); String key = item.getKey();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) { @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value; edit.putStringSet(key, values); }
        }
        assertTrue(edit.commit());
    }
    private void deleteOwnedPending() throws Exception {
        File pending = new File(context.getFilesDir(), "pending-captures").getCanonicalFile();
        File[] journals = pending.listFiles((dir, name) -> name.endsWith(".json"));
        if (journals == null) return;
        for (File journal : journals) {
            JSONObject record = new JSONObject(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8));
            if (!ownedProjects.contains(Uri.parse(record.optString("projectUri")))) continue;
            File original = new File(pending, record.getString("file")).getCanonicalFile();
            assertEquals(pending, original.getParentFile());
            for (String suffix : new String[]{"", ".json", ".json.bak", ".json.new"}) {
                File owned = new File(original.getPath() + suffix);
                if (owned.exists() && !owned.delete()) throw new IOException("Cannot delete owned widget capture");
            }
        }
    }
    private void deleteTree(File target, File root) throws Exception {
        if (target == null) return;
        String owned = root.getCanonicalPath(), path = target.getCanonicalPath();
        assertTrue(path.equals(owned) || path.startsWith(owned + File.separator));
        if (target.isDirectory()) {
            File[] children = target.listFiles(); assertNotNull(children);
            for (File child : children) deleteTree(child, root);
        }
        if (target.exists()) assertTrue(target.delete());
    }
    private interface Action { void run() throws Exception; }
    private interface Value<T> { T get() throws Exception; }
    private interface Condition { boolean met() throws Exception; }
}
