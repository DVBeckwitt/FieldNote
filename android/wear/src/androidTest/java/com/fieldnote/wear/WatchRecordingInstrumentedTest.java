package com.fieldnote.wear;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fieldnote.watchlink.WatchTransfer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Dedicated-emulator lifecycle/encoder checks. No acoustic or Samsung-gesture claim. */
@RunWith(AndroidJUnit4.class)
public final class WatchRecordingInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private ActivityScenario<WatchActivity> scenario;
    private SharedPreferences preferences;
    private String priorPhone;
    private File queueDirectory;
    private Set<String> priorFiles;
    private boolean admitted;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Run destructive instrumentation only on a dedicated emulator",
                Build.MODEL.startsWith("sdk_") || Build.FINGERPRINT.contains("generic"));
        assertEquals(WatchRecordingService.State.IDLE, snapshot().state());
        admitted = true;
        queueDirectory = new File(context.getFilesDir(), "pending-recordings");
        priorFiles = fileNames();
        preferences = context.getSharedPreferences("watch_link", Context.MODE_PRIVATE);
        priorPhone = preferences.getString("phone_address", null);
        assertTrue(preferences.edit().remove("phone_address").commit());
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        }
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        scenario = ActivityScenario.launch(new Intent(context, WatchActivity.class)
                .setAction(WatchRecordingService.ACTION_OPEN));
    }

    @After public void tearDown() throws Exception {
        if (!admitted) return;
        try {
            onMain(() -> WatchRecordingService.finish(context, false));
            await("recorder teardown", 10_000, () -> snapshot().state() == WatchRecordingService.State.IDLE);
            if (scenario != null) scenario.close();
            if (queueDirectory != null && priorFiles != null) {
                File[] files = queueDirectory.listFiles();
                if (files != null) for (File file : files) {
                    if (!priorFiles.contains(file.getName())) assertTrue("Remove only this test's original", file.delete());
                }
            }
        } finally {
            if (preferences != null) assertTrue(preferences.edit().putString("phone_address", priorPhone).commit());
        }
    }

    @Test public void homeReopenAndNotificationStopPreserveAudioAndRejectStaleStop() throws Exception {
        startRecording();
        long firstStarted = snapshot().startedElapsed();
        PendingIntent staleStop = recordingNotification().actions[0].actionIntent;
        File active = activeFile();
        long beforeHome = active.length();
        shell("input keyevent KEYCODE_HOME");
        await("audio continues on Home", 10_000, () -> active.length() > beforeHome + 8192);
        assertEquals(firstStarted, snapshot().startedElapsed());
        recordingNotification().contentIntent.send();
        await("notification reopens the existing Activity", 10_000,
                () -> scenario.getState() == androidx.lifecycle.Lifecycle.State.RESUMED);
        assertEquals(WatchRecordingService.State.RECORDING, snapshot().state());
        scenario.recreate();
        assertEquals("Recreation keeps the same capture", firstStarted, snapshot().startedElapsed());
        staleStop.send();
        awaitIdle();
        List<WatchRecordingQueue.Capture> saved = newCaptures();
        assertEquals(1, saved.size());
        assertPlayable(saved.get(0));

        startRecording();
        long secondStarted = snapshot().startedElapsed();
        File secondFile = activeFile();
        long beforeStale = secondFile.length();
        staleStop.send();
        await("a stale Stop leaves the new capture writing", 10_000,
                () -> secondFile.length() > beforeStale + 8192);
        assertEquals(secondStarted, snapshot().startedElapsed());
        recordingNotification().actions[0].actionIntent.send();
        awaitIdle();
        saved = newCaptures();
        assertEquals(2, saved.size());
        for (WatchRecordingQueue.Capture capture : saved) assertPlayable(capture);
    }

    @Test public void limitEventsFinalizeAndQueueAudioWhileUnrelatedAndStaleEventsDoNot() throws Exception {
        for (int event : new int[] {MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING,
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}) {
            int savedBefore = newCaptures().size();
            startRecording();
            String session = session();
            // Inject only the framework information callback. Audio encoding, service
            // finalization and queue publication are real. This does not fill 128 MiB.
            recorderInfo(UUID.randomUUID().toString(), event);
            recorderInfo(session, MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN);
            assertEquals(WatchRecordingService.State.RECORDING, snapshot().state());
            recorderInfo(session, event);
            awaitIdle();
            assertEquals(R.string.limit_saved_on_watch, snapshot().message());
            List<WatchRecordingQueue.Capture> saved = newCaptures();
            assertEquals(savedBefore + 1, saved.size());
            for (WatchRecordingQueue.Capture capture : saved) assertPlayable(capture);
        }
    }

    @Test public void discardRemovesOnlyActiveOriginal() throws Exception {
        startRecording();
        onMain(() -> WatchRecordingService.finish(context, true));
        awaitIdle();
        WatchRecordingQueue.Capture retained = newCaptures().get(0);
        byte[] original = Files.readAllBytes(retained.file().toPath());
        startRecording();
        onMain(() -> WatchRecordingService.finish(context, false));
        awaitIdle();
        assertEquals(1, newCaptures().size());
        assertArrayEquals(original, Files.readAllBytes(retained.file().toPath()));
    }

    @Test public void failedFinalRenameCannotBeOverwrittenByTheNextRecording() throws Exception {
        File first = WatchRecordingQueue.begin(context);
        byte[] original = new byte[] {1, 2, 3, 4, 5};
        try (FileOutputStream output = new FileOutputStream(first)) { output.write(original); }
        UUID id = UUID.randomUUID();
        File blockedDestination = new File(queueDirectory, id + "_100_200.m4a");
        assertTrue(blockedDestination.mkdir());
        assertThrows(java.io.IOException.class, () -> WatchRecordingQueue.commit(first, id, 100, 200));
        File next = WatchRecordingQueue.begin(context);
        assertNotEquals(first, next);
        assertArrayEquals(original, Files.readAllBytes(first.toPath()));
    }

    private void startRecording() throws Exception {
        scenario.onActivity(WatchRecordingService::start);
        await("foreground microphone starts", 10_000,
                () -> snapshot().state() == WatchRecordingService.State.RECORDING);
        await("real encoded audio reaches disk", 10_000, () -> activeFile().length() > 24_000
                && SystemClock.elapsedRealtime() - snapshot().startedElapsed() > 1_500);
        assertNotNull(recordingNotification());
    }

    private void awaitIdle() throws Exception {
        await("recording is finalized", 10_000, () -> snapshot().state() == WatchRecordingService.State.IDLE);
    }

    private File activeFile() {
        File[] files = queueDirectory.listFiles(file -> file.getName().endsWith(".part")
                && !priorFiles.contains(file.getName()));
        assertNotNull(files);
        assertEquals("One live temporary original", 1, files.length);
        return files[0];
    }

    private List<WatchRecordingQueue.Capture> newCaptures() {
        return WatchRecordingQueue.pending(context).stream()
                .filter(capture -> !priorFiles.contains(capture.file().getName())).collect(Collectors.toList());
    }

    private Notification recordingNotification() throws Exception {
        AtomicReference<Notification> found = new AtomicReference<>();
        await("recording notification has Stop", 5_000, () -> {
            for (StatusBarNotification posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                Notification notification = posted.getNotification();
                if (notification.actions != null && notification.actions.length == 1) found.set(notification);
            }
            return found.get() != null;
        });
        return found.get();
    }

    private void assertPlayable(WatchRecordingQueue.Capture capture) throws Exception {
        assertTrue(capture.file().length() <= WatchTransfer.MAX_BYTES);
        assertTrue(capture.endedAtMillis() > capture.startedAtMillis());
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(capture.file().getAbsolutePath());
            assertEquals(1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME));
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 500_000);
            extractor.selectTrack(0);
            assertTrue(extractor.readSampleData(ByteBuffer.allocate(8192), 0) > 0);
        } finally { extractor.release(); }
    }

    private WatchRecordingService.Snapshot snapshot() {
        AtomicReference<WatchRecordingService.Snapshot> result = new AtomicReference<>();
        onMain(() -> result.set(WatchRecordingService.snapshot()));
        return result.get();
    }

    private String session() throws Exception {
        Field field = WatchRecordingService.class.getDeclaredField("currentToken");
        field.setAccessible(true);
        AtomicReference<String> result = new AtomicReference<>();
        onMain(() -> { try { result.set((String) field.get(null)); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } });
        return result.get();
    }

    private void recorderInfo(String session, int what) throws Exception {
        Method method = WatchRecordingService.class.getDeclaredMethod("recordingInfo", Context.class, String.class, int.class);
        method.setAccessible(true);
        onMain(() -> { try { method.invoke(null, context, session, what); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } });
    }

    private Set<String> fileNames() {
        String[] files = queueDirectory.list();
        return files == null ? Set.of() : new HashSet<>(Arrays.asList(files));
    }

    private void onMain(Runnable work) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { work.run(); } catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() != null) throw new AssertionError("Main-thread test operation failed", error.get());
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            output.readAllBytes();
        }
    }

    private interface Condition { boolean get() throws Exception; }

    private void await(String message, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.get()) return;
            SystemClock.sleep(50);
        }
        fail(message);
    }
}
