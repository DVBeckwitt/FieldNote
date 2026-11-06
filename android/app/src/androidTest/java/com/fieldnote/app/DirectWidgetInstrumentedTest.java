package com.fieldnote.app;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
    private String priorOverlayMode;
    private File fixtureRoot;
    private Uri fixtureTree, project;
    private final List<Uri> ownedProjects = new ArrayList<>();
    private final AtomicReference<Bundle> hostState = new AtomicReference<>();
    private final AtomicInteger mainActivityEvents = new AtomicInteger();
    private ActivityLifecycleCallback lifecycle;
    private Touch touch;
    private boolean admitted, keepSmoke;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertFalse("Do not take over an existing recorder", RecordingService.isBusy());
        assertFalse("Do not take over an existing overlay", FloatingRecorderService.isShowing());
        admitted = true;
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        bookmarks = context.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        assertTrue(RecordingShortcuts.isWidgetIntent(context, RecordingShortcuts.widgetIntent(context)));
        priorPreferences = preferences.getAll();
        priorBookmarks = bookmarks.getAll();
        assertTrue(preferences.edit().remove("floating_text_draft").commit());
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        Matcher mode = Pattern.compile("(?m)^\\s*SYSTEM_ALERT_WINDOW:\\s*(allow|ignore|deny|default|foreground)\\b")
                .matcher(shell("appops get " + context.getPackageName() + " SYSTEM_ALERT_WINDOW"));
        priorOverlayMode = mode.find() ? mode.group(1) : "default";
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        await("overlay permission", 5_000, () -> Settings.canDrawOverlays(context));
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
        context.getSystemService(NotificationManager.class).cancel(1003);
    }

    @After public void tearDown() throws Exception {
        if (!admitted) return;
        try {
            TestDocumentsProvider.clearFailures();
            if (touch != null) touch.cancel();
            onMain(() -> {
                FloatingRecorderService.hide(context);
                if (ownedProjects.contains(RecordingService.snapshot().projectUri) && RecordingService.isBusy())
                    RecordingService.stop(context);
            });
            await("owned overlay and audio finish", 25_000, () -> activeService() == null && !RecordingService.isBusy());
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
            context.startActivity(new Intent().setComponent(hostComponent()).setAction("com.fieldnote.widgettest.CLOSE")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            deleteOwnedPending();
            deleteTree(fixtureRoot, fixtureRoot);
        } finally {
            if (lifecycle != null) onMain(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle));
            if (!keepSmoke) {
                if (priorBookmarks != null) restore(bookmarks, priorBookmarks);
                if (priorPreferences != null) restore(preferences, priorPreferences);
                if (priorOverlayMode != null)
                    shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW " + priorOverlayMode);
                context.getSystemService(NotificationManager.class).cancel(1003);
            }
            TestDocumentsProvider.clearFailures();
        }
    }

    @Test public void foreignHostWidgetRecordsAndPhotographsInLatestProjectWithoutOpeningMain() throws Exception {
        Uri previous = project;
        project = createProject("Latest widget project");
        store.setCurrentProject(previous);
        launchHost();
        tapWidget();
        awaitOverlay();
        FloatingRecorderService shown = onMainValue(this::requireService);
        assertEquals(project, onMainValue(() -> field(shown, "project")));
        tapWidget();
        assertSame("Repeated taps reuse the existing overlay", shown, onMainValue(this::requireService));
        assertFalse("Showing the widget does not itself start audio", RecordingService.isBusy());
        // Exceed common background-start grace periods before requesting microphone/camera.
        long readyAt = SystemClock.elapsedRealtime();
        await("delayed gesture starts outside the launch grace period", 15_000,
                () -> SystemClock.elapsedRealtime() - readyAt >= 12_500);
        touch = new Touch();
        touch.send(MotionEvent.ACTION_DOWN, 0, 0);
        await("held overlay starts real AAC without Main", 15_000,
                () -> RecordingService.snapshot().state == RecordingService.State.RECORDING
                        && RecordingService.snapshot().elapsedMs() >= 700);
        RecordingService.Snapshot started = RecordingService.snapshot();
        assertEquals(project, started.projectUri);
        touch.send(MotionEvent.ACTION_MOVE, -128, 0);
        await("photo swipe publishes a linked original while audio continues", 20_000,
                () -> media(store.readProject(project), "photo") != null);
        assertEquals(started.entryId, RecordingService.snapshot().entryId);
        assertEquals(RecordingService.State.RECORDING, RecordingService.snapshot().state);
        touch.send(MotionEvent.ACTION_MOVE, 0, 0);
        touch.send(MotionEvent.ACTION_UP, 0, 0);
        await("audio and photo publish together", 30_000, () -> !RecordingService.isBusy()
                && media(store.readProject(project), "audio") != null);
        JSONObject index = store.readProject(project);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(started.entryId, entry.getString("id"));
        assertEquals(2, entry.getJSONArray("media").length());
        assertEquals(0, store.readProject(previous).getJSONArray("entries").length());
        assertEncodedAudio(store.mediaUri(project, media(index, "audio").getString("path")));
        byte[] jpeg = bytes(context.getContentResolver().openInputStream(store.mediaUri(project, media(index, "photo").getString("path"))));
        BitmapFactory.Options photo = new BitmapFactory.Options();
        photo.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, photo);
        assertEquals("image/jpeg", photo.outMimeType);
        assertTrue(photo.outWidth > 0 && photo.outHeight > 0);
        assertEquals("No Fieldnote Activity lifecycle is entered by the widget", 0, mainActivityEvents.get());
        assertTrue(hostState.get().getBoolean("focused"));
        keepSmoke = "true".equals(InstrumentationRegistry.getArguments().getString("keepWidgetSmoke"));
    }

    @Test public void missingOverlayGrantShowsSetupNotificationWithoutOpeningMain() throws Exception {
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        await("overlay grant is revoked", 5_000, () -> !Settings.canDrawOverlays(context));
        launchHost();
        tapWidget();
        assertSetupOnly();
    }

    @Test public void missingProjectShowsSetupNotificationWithoutOpeningMain() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        launchHost();
        tapWidget();
        assertSetupOnly();
    }

    private void assertSetupOnly() throws Exception {
        await("setup notification replaces an automatic app launch", 10_000, () -> setupNotification() != null);
        await("unsatisfied widget service stops", 10_000, () -> activeService() == null);
        assertFalse(FloatingRecorderService.isShowing());
        assertFalse(RecordingService.isBusy());
        assertEquals(0, mainActivityEvents.get());
        StatusBarNotification notification = setupNotification();
        assertNotNull(notification.getNotification().contentIntent);
        if (Build.VERSION.SDK_INT >= 31) assertTrue(notification.getNotification().contentIntent.isActivity());
    }

    private StatusBarNotification setupNotification() {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (notification.getId() == 1003) return notification;
        return null;
    }

    private void launchHost() throws Exception {
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int code, Bundle data) { if (code == 1) hostState.set(data); }
        };
        context.startActivity(new Intent().setComponent(hostComponent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("views", RecordingWidget.views(context)).putExtra("micId", R.id.widget_mic_button).putExtra("results", receiver));
        await("foreign widget host is focused and laid out", 10_000, () -> hostState.get() != null
                && hostState.get().getBoolean("focused") && hostState.get().getInt("width") > 0);
        assertNotEquals("The host must really have a different Android UID", Process.myUid(), hostState.get().getInt("uid"));
        assertNotEquals(Process.myPid(), hostState.get().getInt("pid"));
        assertEquals(0, mainActivityEvents.get());
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

    private void awaitOverlay() throws Exception {
        await("widget attaches a usable overlay", 15_000, () -> FloatingRecorderService.isShowing() && onMainValue(() -> {
            FloatingRecorderService service = activeService();
            View mic = service == null ? null : (View) field(service, "microphone");
            return mic != null && mic.isAttachedToWindow() && mic.getWidth() > 0;
        }));
    }

    private Uri createProject(String name) throws Exception {
        Uri created = store.createProject(fixtureTree, name); ownedProjects.add(created); return created;
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

    private final class Touch {
        final float x, y, density;
        final long downTime = SystemClock.uptimeMillis();
        boolean down;
        float lastX, lastY;
        Touch() throws Exception {
            float[] point = onMainValue(() -> {
                View mic = (View) field(requireService(), "microphone");
                int[] location = new int[2]; mic.getLocationOnScreen(location);
                return new float[]{location[0] + mic.getWidth() / 2f, location[1] + mic.getHeight() / 2f,
                        mic.getResources().getDisplayMetrics().density};
            });
            x = point[0]; y = point[1]; density = point[2];
        }
        void send(int action, float dx, float dy) {
            lastX = dx; lastY = dy;
            if (action == MotionEvent.ACTION_DOWN) down = true;
            inject(downTime, action, x + dx * density, y + dy * density);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) down = false;
        }
        void cancel() { if (down) send(MotionEvent.ACTION_CANCEL, lastX, lastY); }
    }

    private void inject(long began, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(began, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); }
        finally { event.recycle(); }
    }
    private FloatingRecorderService activeService() { return (FloatingRecorderService) staticField(FloatingRecorderService.class, "active"); }
    private FloatingRecorderService requireService() { FloatingRecorderService service = activeService(); assertNotNull(service); return service; }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
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
