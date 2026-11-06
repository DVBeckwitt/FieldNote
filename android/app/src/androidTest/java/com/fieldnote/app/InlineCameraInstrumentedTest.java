package com.fieldnote.app;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real queued stop path with a controlled startup interleaving.
 * The recorder is a spy; this does not simulate camera hardware or prove its timing.
 * NativeCaptureInstrumentedTest separately covers actual camera start/stop and linked audio.
 */
@RunWith(AndroidJUnit4.class)
public final class InlineCameraInstrumentedTest {
    @Test public void preStartCancellationStaysSilentWhenStartCompletesBeforeQueuedStop() throws Exception {
        checkStartupCancellation(false);
    }

    @Test public void preStartCancellationStaysSilentWhenTheStartedRecorderHasNoFrames() throws Exception {
        checkStartupCancellation(true);
    }

    private void checkStartupCancellation(boolean stopThrows) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<InlineCamera> controller = new AtomicReference<>();
        AtomicReference<RecorderSpy> recorder = new AtomicReference<>();
        AtomicInteger saved = new AtomicInteger(), errors = new AtomicInteger();
        CountDownLatch idle = new CountDownLatch(1), startupEntered = new CountDownLatch(1);
        CountDownLatch allowStartReturn = new CountDownLatch(1);
        AtomicReference<Throwable> cameraFailure = new AtomicReference<>();
        File original = new ProjectStore(context).newPendingFile("video");
        try {
            onMain(() -> {
                Activity activity = new CaptureContextActivity(context);
                controller.set(new InlineCamera(activity, new FrameLayout(activity), new InlineCamera.Listener() {
                    @Override public void onStateChanged(boolean busy, boolean recording) {
                        if (!busy) idle.countDown();
                    }
                    @Override public void onSaved() { saved.incrementAndGet(); }
                    @Override public void onError(String message) { errors.incrementAndGet(); }
                }));
                recorder.set(new RecorderSpy(stopThrows));
            });
            InlineCamera inline = controller.get();
            Class<?> jobClass = Class.forName("com.fieldnote.app.InlineCamera$Job");
            Constructor<?> constructor = jobClass.getDeclaredConstructor(InlineCamera.class, Uri.class, String.class, boolean.class);
            constructor.setAccessible(true);
            Object job = constructor.newInstance(inline, Uri.parse("content://fieldnote-test/cancelled-video"),
                    UUID.randomUUID().toString(), true);
            set(job, "file", original);
            set(inline, "current", job);
            set(inline, "busy", true);
            set(inline, "recorder", recorder.get());
            Handler camera = (Handler) get(inline, "cameraHandler");
            assertTrue(camera.post(() -> {
                startupEntered.countDown();
                try {
                    if (!allowStartReturn.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("The controlled startup was not released within 5 seconds.");
                    // Model recorder.start() returning after cancellation was requested.
                    // These are the same existing fields assigned by onConfigured.
                    set(job, "recording", true);
                    set(inline, "recording", true);
                } catch (Throwable failure) { cameraFailure.set(failure); }
            }));
            assertTrue("The actual camera handler reaches startup", startupEntered.await(5, TimeUnit.SECONDS));
            onMain(inline::stopVideo);
            assertTrue("The public stop method records preparation cancellation", (Boolean) get(job, "cancelled"));
            allowStartReturn.countDown();
            assertTrue("The cancelled job leaves the camera idle", idle.await(5, TimeUnit.SECONDS));
            onMain(() -> {
                assertFalse(inline.isBusy());
                assertFalse(inline.isRecording());
                assertEquals("Cancellation must never report a saved clip", 0, saved.get());
                assertEquals("An intentional startup cancellation stays silent", 0, errors.get());
                assertEquals("A recorder that started during cancellation is stopped", 1, recorder.get().stops.get());
                assertEquals("The cancelled recorder is released", 1, recorder.get().releases.get());
            });
            if (cameraFailure.get() != null) throw new AssertionError("Controlled startup failed", cameraFailure.get());
            assertFalse("An empty cancelled original is not left pending", original.exists());
        } finally {
            allowStartReturn.countDown();
            InlineCamera inline = controller.get();
            if (inline != null) {
                onMain(inline::close);
                HandlerThread thread = (HandlerThread) get(inline, "thread");
                thread.join(5_000);
                assertFalse("The isolated camera worker closes", thread.isAlive());
                assertTrue("The isolated storage worker closes",
                        ((ExecutorService) get(inline, "io")).awaitTermination(5, TimeUnit.SECONDS));
            }
            RecorderSpy spy = recorder.get();
            if (spy != null && spy.releases.get() == 0) onMain(spy::release);
            if (original.exists()) assertTrue("Remove only this test's empty original", original.delete());
        }
    }

    /** Widgets need a themed Activity context, but this fixture never opens a window or camera. */
    private static final class CaptureContextActivity extends Activity {
        CaptureContextActivity(Context context) {
            attachBaseContext(context);
            // Activity.setTheme also updates its Window on API 36. This fixture
            // intentionally has no Window; only its widget theme is needed.
            getTheme().applyStyle(R.style.FieldnoteDark, true);
        }
        @Override public Object getSystemService(String name) { return getBaseContext().getSystemService(name); }
    }

    @SuppressWarnings("deprecation")
    private static final class RecorderSpy extends MediaRecorder {
        final AtomicInteger stops = new AtomicInteger(), releases = new AtomicInteger();
        final boolean stopThrows;
        RecorderSpy(boolean stopThrows) { this.stopThrows = stopThrows; }
        @Override public void stop() {
            stops.incrementAndGet();
            if (stopThrows) throw new IllegalStateException("No frame was produced before cancellation.");
        }
        @Override public void reset() { /* No encoder was configured by this interleaving fixture. */ }
        @Override public void release() { releases.incrementAndGet(); super.release(); }
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void onMain(Runnable action) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(new Handler(Looper.getMainLooper()).post(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        }));
        assertTrue("The native main-thread action finishes", finished.await(5, TimeUnit.SECONDS));
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof Exception) throw (Exception) error;
        if (error != null) throw new AssertionError(error);
    }
}
