package com.fieldnote.app;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.app.job.JobInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Byte fixtures exercise durable queuing; native recorder acceptance lives separately. */
@RunWith(AndroidJUnit4.class)
public final class ProjectQueueInstrumentedTest {
    private Context context;
    private ProjectStore store;
    private SharedPreferences bookmarks;
    private Map<String, ?> previousBookmarks;
    private File fixtureRoot;
    private Uri fixtureTree;
    private Uri project;
    private ExecutorService captures;
    private final ConcurrentLinkedQueue<File> ownedPending = new ConcurrentLinkedQueue<>();
    private final List<Uri> ownedProjects = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new ProjectStore(context);
        bookmarks = context.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        previousBookmarks = bookmarks.getAll();
        TestDocumentsProvider.clearFailures();
        ProjectPublisher.retry(context);
        awaitCondition("A previous publisher must finish before queue fixtures start", 10_000L,
                () -> !ProjectPublisher.snapshot().running);
        String name = "queue-instrumentation-" + UUID.randomUUID();
        File base = TestDocumentsProvider.rootDirectory(context);
        assertTrue(base.isDirectory() || base.mkdirs());
        fixtureRoot = new File(base, name);
        assertTrue(fixtureRoot.mkdir());
        fixtureTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root:" + name);
        captures = Executors.newFixedThreadPool(4);
    }

    @After public void tearDown() throws Exception {
        TestDocumentsProvider.clearFailures();
        if (captures != null) {
            captures.shutdown();
            assertTrue("Capture fixture tasks must exit before cleanup", captures.awaitTermination(10, TimeUnit.SECONDS));
        }
        if (context != null) {
            // Release provider holds first, then let any accepted originals finish.
            // Never delete an original while its publisher could still be reading it.
            ProjectPublisher.retry(context);
            awaitCondition("Publisher must leave its critical section before cleanup", 15_000L,
                    () -> !ProjectPublisher.snapshot().running);
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> scheduler.cancel(ProjectPublisher.JOB_ID));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                awaitCondition("A stopped late job must unwind before fixture cleanup", 10_000L,
                        () -> !ProjectPublisher.snapshot().running);
            }
        }
        for (File file : ownedPending) {
            deleteOwnedPending(file);
            deleteOwnedPending(new File(file.getPath() + ".json"));
            deleteOwnedPending(new File(file.getPath() + ".json.bak"));
            deleteOwnedPending(new File(file.getPath() + ".json.new"));
        }
        for (Uri ownedProject : ownedProjects) {
            File parent = new File(context.getFilesDir(), "archive-publications").getCanonicalFile();
            File owned = new File(parent, sha256(ownedProject.toString().getBytes(StandardCharsets.UTF_8))).getCanonicalFile();
            assertEquals(parent, owned.getParentFile());
            deleteOwnedTree(owned, owned);
        }
        if (fixtureRoot != null) deleteOwnedTree(fixtureRoot, fixtureRoot);
        if (bookmarks != null) {
            SharedPreferences.Editor editor = bookmarks.edit().clear();
            for (Map.Entry<String, ?> entry : previousBookmarks.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            }
            assertTrue(editor.commit());
        }
    }

    @Test public void blockedZipAllowsConcurrentAudioPhotoAndVideoStagingWithoutLostLinks() throws Exception {
        project = createProject("Concurrent queued originals");
        int baselinePending = store.pendingCount();
        byte[] previousZip = readUri(project);
        String firstEntry = UUID.randomUUID().toString();
        String secondEntry = UUID.randomUUID().toString();
        long at = Instant.parse("2026-11-05T18:00:00Z").toEpochMilli();
        List<QueuedCapture> queued = new ArrayList<>();
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(project)) {
            queued.add(captures.submit(() -> stageFixture(firstEntry, "audio", at, at + 4_000L, "first audio", "first-marker")).get(3, TimeUnit.SECONDS));
            assertTrue("The first publication must enter the provider hold", barrier.awaitBlocked(10, TimeUnit.SECONDS));
            assertTrue(barrier.isBlocked());

            CountDownLatch start = new CountDownLatch(1);
            Future<QueuedCapture> audio = captures.submit(() -> {
                start.await();
                return stageFixture(secondEntry, "audio", at + 10_000L, at + 14_000L, "second audio", "second-marker");
            });
            Future<QueuedCapture> photo = captures.submit(() -> {
                start.await();
                return stageFixture(firstEntry, "photo", at + 1_500L, at + 1_500L, "apparatus photo", null);
            });
            Future<QueuedCapture> video = captures.submit(() -> {
                start.await();
                return stageFixture(secondEntry, "video", at + 10_500L, at + 13_500L, "apparatus video", null);
            });
            start.countDown();
            queued.add(audio.get(3, TimeUnit.SECONDS));
            queued.add(photo.get(3, TimeUnit.SECONDS));
            queued.add(video.get(3, TimeUnit.SECONDS));
            assertTrue("Every staging operation must finish while ZIP publication is still blocked", barrier.isBlocked());
            assertFalse(barrier.timedOut());

            Future<JSONArray> overlay = captures.submit(() -> {
                assertEquals(baselinePending + 4, store.pendingCount());
                assertEquals(4, store.retryablePendingCount());
                return store.pendingEntries(project);
            });
            JSONArray pendingEntries = overlay.get(3, TimeUnit.SECONDS);
            assertEquals(2, pendingEntries.length());
            assertEquals(2, entryById(pendingEntries, firstEntry).getJSONArray("media").length());
            assertEquals(2, entryById(pendingEntries, secondEntry).getJSONArray("media").length());
            for (QueuedCapture capture : queued) assertStaged(capture, pendingEntries);
            assertArrayEquals("A local queue overlay must not imply external publication", previousZip, readUri(project));

            // Duplicate scheduling requests and a repeated durable stage use the same IDs.
            QueuedCapture repeated = queued.get(1);
            captures.submit(() -> store.stageCapture(project, repeated.entryId, repeated.kind, repeated.file,
                    repeated.start, repeated.end, repeated.metadata, "complete")).get(3, TimeUnit.SECONDS);
            for (int i = 0; i < 4; i++) ProjectPublisher.kick(context);
            assertTrue(barrier.isBlocked());
            barrier.release();
        }
        awaitOwnQueueDrained(queued);
        JSONObject index = store.readProject(project);
        assertEquals(2, index.getJSONArray("entries").length());
        for (QueuedCapture capture : queued) assertPublished(capture, index);
        assertEquals(2, entryById(index.getJSONArray("entries"), firstEntry).getJSONArray("media").length());
        assertEquals(2, entryById(index.getJSONArray("entries"), secondEntry).getJSONArray("media").length());
        assertEquals(baselinePending, store.pendingCount());
        assertSingleZip();
        byte[] committed = readUri(project);
        ProjectPublisher.retry(context);
        awaitCondition("An empty repeated drain must finish", 10_000L, () -> !ProjectPublisher.snapshot().running);
        assertArrayEquals("Repeated drain must not append duplicate media or republish", committed, readUri(project));
    }

    @Test public void failedBackgroundPublicationRetainsOriginalAndMarkersUntilExplicitRetry() throws Exception {
        project = createProject("Queued failure recovery");
        int baselinePending = store.pendingCount();
        byte[] previousZip = readUri(project);
        String entryId = UUID.randomUUID().toString();
        long at = Instant.parse("2026-11-05T18:10:00Z").toEpochMilli();
        QueuedCapture queued;
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(project)) {
            queued = captures.submit(() -> stageFixture(entryId, "audio", at, at + 5_000L, "retryable queued original", "retry-marker")).get(3, TimeUnit.SECONDS);
            assertTrue(barrier.awaitBlocked(10, TimeUnit.SECONDS));
            assertTrue(barrier.isBlocked());
            TestDocumentsProvider.failNextZipWriteAfterTruncate();
            barrier.release();
        }
        awaitCondition("A failed publisher must expose retained work and leave its worker", 10_000L,
                () -> !ProjectPublisher.snapshot().running && !ProjectPublisher.snapshot().error.isEmpty());
        assertEquals(baselinePending + 1, store.pendingCount());
        assertTrue(queued.file.isFile());
        assertArrayEquals(queued.bytes, readFile(queued.file));
        JSONObject journal = new JSONObject(new String(readFile(new File(queued.file.getPath() + ".json")), StandardCharsets.UTF_8));
        assertTrue(journal.getBoolean("finalized"));
        assertEquals(queued.local.getJSONArray("markers").toString(), journal.getJSONArray("markers").toString());
        assertArrayEquals("Truncated provider output must be restored to the last committed ZIP", previousZip, readUri(project));
        assertEquals(0, store.readProject(project).getJSONArray("entries").length());
        assertEquals(1, store.pendingEntries(project).length());

        ProjectPublisher.retry(context);
        List<QueuedCapture> capturesToDrain = new ArrayList<>();
        capturesToDrain.add(queued);
        awaitOwnQueueDrained(capturesToDrain);
        JSONObject index = new ProjectStore(context).readProject(project);
        assertEquals(1, index.getJSONArray("entries").length());
        assertEquals(1, entryById(index.getJSONArray("entries"), entryId).getJSONArray("media").length());
        assertPublished(queued, index);
        assertEquals(baselinePending, store.pendingCount());
        assertSingleZip();
    }

    @Test public void backgroundQueuePublishesFinalizedCaptureAndRetainsUnfinishedOriginal() throws Exception {
        project = createProject("Unfinished capture beside queue");
        int baselinePending = store.pendingCount();
        int baselineUnfinished = store.unfinishedPendingCount();
        long at = Instant.parse("2026-11-05T18:20:00Z").toEpochMilli();
        String completeEntry = UUID.randomUUID().toString();
        String unfinishedEntry = UUID.randomUUID().toString();
        byte[] unfinishedBytes = "Unfinished camera output must not be presented as a saved original".getBytes(StandardCharsets.UTF_8);
        QueuedCapture complete;
        File unfinished;
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(project)) {
            complete = captures.submit(() -> stageFixture(completeEntry, "audio", at, at + 3_000L,
                    "complete audio beside unfinished photo", "complete-marker")).get(3, TimeUnit.SECONDS);
            assertTrue(barrier.awaitBlocked(10, TimeUnit.SECONDS));
            unfinished = captures.submit(() -> {
                File file = store.newPendingFile("photo");
                ownedPending.add(file);
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(unfinishedBytes); }
                store.beginCapture(project, unfinishedEntry, "photo", file, at + 1_000L, new JSONObject());
                return file;
            }).get(3, TimeUnit.SECONDS);
            assertTrue(barrier.isBlocked());
            assertFalse(barrier.timedOut());
            barrier.release();
        }
        List<QueuedCapture> accepted = new ArrayList<>();
        accepted.add(complete);
        awaitOwnQueueDrained(accepted);
        JSONObject index = store.readProject(project);
        assertEquals(1, index.getJSONArray("entries").length());
        assertPublished(complete, index);
        assertArrayEquals(unfinishedBytes, readFile(unfinished));
        JSONObject journal = new JSONObject(new String(readFile(new File(unfinished.getPath() + ".json")), StandardCharsets.UTF_8));
        assertFalse(journal.getBoolean("finalized"));
        assertEquals(unfinishedEntry, journal.getString("entryId"));
        assertEquals(baselinePending + 1, store.pendingCount());
        assertEquals(baselineUnfinished + 1, store.unfinishedPendingCount());
        assertEquals(0, store.pendingEntries(project).length());
        byte[] committed = readUri(project);
        ProjectPublisher.retry(context);
        awaitCondition("Unfinished output must not keep a publishing loop alive", 10_000L,
                () -> !ProjectPublisher.snapshot().running);
        assertArrayEquals(committed, readUri(project));
        assertArrayEquals(unfinishedBytes, readFile(unfinished));
        assertSingleZip();
    }

    @Test public void newlyStagedHealthyProjectBypassesUnrelatedFailureCooldownWithoutResettingBackoff() throws Exception {
        project = createProject("Unavailable project");
        Uri healthyProject = createProject("Healthy project");
        long at = Instant.parse("2026-11-05T18:30:00Z").toEpochMilli();
        QueuedCapture failing;
        try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(project)) {
            failing = captures.submit(() -> stageFixture(UUID.randomUUID().toString(), "audio", at, at + 3_000L,
                    "unavailable project original", "unavailable-marker")).get(3, TimeUnit.SECONDS);
            assertTrue(barrier.awaitBlocked(10, TimeUnit.SECONDS));
            TestDocumentsProvider.failNextZipWriteAfterTruncate();
            barrier.release();
        }
        awaitCondition("The unavailable project must enter automatic retry cooldown", 10_000L,
                () -> !ProjectPublisher.snapshot().running && !ProjectPublisher.snapshot().error.isEmpty());
        assertTrue(failing.file.isFile());

        // App refreshes and ordinary retry kicks must still respect that cooldown.
        ProjectPublisher.kick(context);
        assertFalse("An ordinary kick must not bypass failure backoff", ProjectPublisher.snapshot().running);
        captures.submit(() -> store.stageCapture(project, failing.entryId, failing.kind, failing.file,
                failing.start, failing.end, failing.metadata, "complete")).get(3, TimeUnit.SECONDS);
        assertFalse("Repeating an already-staged capture is not newly durable work", ProjectPublisher.snapshot().running);
        TestDocumentsProvider.failZipReads(project, 100);
        try {
            QueuedCapture healthy;
            try (TestDocumentsProvider.WriteBarrier barrier = TestDocumentsProvider.blockNextZipWrite(healthyProject)) {
                healthy = captures.submit(() -> stageFixture(healthyProject, UUID.randomUUID().toString(), "audio",
                        at + 10_000L, at + 14_000L, "healthy original during another project's cooldown", "healthy-marker"))
                        .get(3, TimeUnit.SECONDS);
                assertTrue("New durable work must reach its healthy ZIP before the 30-second cooldown",
                        barrier.awaitBlocked(10, TimeUnit.SECONDS));
                assertFalse(barrier.timedOut());
                barrier.release();
            }
            awaitOwnQueueDrained(java.util.Collections.singletonList(healthy));
            JSONObject healthyIndex = store.readProject(healthyProject);
            assertEquals(1, healthyIndex.getJSONArray("entries").length());
            assertPublished(healthyProject, healthy, healthyIndex);
            assertTrue("The unrelated failed original must remain recoverable", failing.file.isFile());
            assertArrayEquals(failing.bytes, readFile(failing.file));

            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            assertNotNull(scheduler);
            awaitCondition("New work must not reset the growing retry delay of the still-failing project", 10_000L, () -> {
                JobInfo job = scheduler.getPendingJob(ProjectPublisher.JOB_ID);
                return job != null && job.getMinLatencyMillis() > 45_000L;
            });
            ProjectPublisher.kick(context);
            assertFalse("Ordinary kicks still wait after the healthy original has published", ProjectPublisher.snapshot().running);
        } finally { TestDocumentsProvider.clearFailures(); }
    }

    @Test public void captureStagedDuringFailingPassGetsImmediateFollowUpPass() throws Exception {
        project = createProject("Failing active project");
        Uri healthyProject = createProject("Project staged during failure");
        long at = Instant.parse("2026-11-05T18:40:00Z").toEpochMilli();
        QueuedCapture failing;
        QueuedCapture healthy;
        try {
            try (TestDocumentsProvider.WriteBarrier firstWrite = TestDocumentsProvider.blockNextZipWrite(project)) {
                failing = captures.submit(() -> stageFixture(UUID.randomUUID().toString(), "audio", at, at + 3_000L,
                        "original in active failing pass", "active-failure-marker")).get(3, TimeUnit.SECONDS);
                assertTrue(firstWrite.awaitBlocked(10, TimeUnit.SECONDS));
                // The current pass has already taken its journal-file snapshot. This
                // second capture therefore requires a separate pass after it fails.
                try (TestDocumentsProvider.WriteBarrier secondWrite = TestDocumentsProvider.blockNextZipWrite(healthyProject)) {
                    healthy = captures.submit(() -> stageFixture(healthyProject, UUID.randomUUID().toString(), "audio",
                            at + 10_000L, at + 15_000L, "capture accepted during a failing pass", "follow-up-marker"))
                            .get(3, TimeUnit.SECONDS);
                    assertTrue(firstWrite.isBlocked());
                    TestDocumentsProvider.failZipReads(project, 100);
                    TestDocumentsProvider.failNextZipWriteAfterTruncate();
                    firstWrite.release();
                    assertTrue("A failed active pass must not strand work staged after its snapshot",
                            secondWrite.awaitBlocked(10, TimeUnit.SECONDS));
                    assertFalse(secondWrite.timedOut());
                    secondWrite.release();
                }
            }
            awaitOwnQueueDrained(java.util.Collections.singletonList(healthy));
            JSONObject index = store.readProject(healthyProject);
            assertEquals(1, index.getJSONArray("entries").length());
            assertPublished(healthyProject, healthy, index);
            assertTrue(failing.file.isFile());
            assertArrayEquals(failing.bytes, readFile(failing.file));
            assertFalse(ProjectPublisher.snapshot().error.isEmpty());
        } finally { TestDocumentsProvider.clearFailures(); }
    }

    private Uri createProject(String name) throws IOException {
        Uri created = store.createProject(fixtureTree, name);
        ownedProjects.add(created);
        return created;
    }

    private QueuedCapture stageFixture(String entryId, String kind, long start, long end,
                                       String payload, String markerId) throws Exception {
        return stageFixture(project, entryId, kind, start, end, payload, markerId);
    }

    private QueuedCapture stageFixture(Uri destination, String entryId, String kind, long start, long end,
                                       String payload, String markerId) throws Exception {
        byte[] bytes = (payload + "; storage fixture, not encoded media").getBytes(StandardCharsets.UTF_8);
        File file = store.newPendingFile(kind);
        ownedPending.add(file);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
        JSONObject metadata = new JSONObject().put("fixture", payload);
        store.beginCapture(destination, entryId, kind, file, start, metadata);
        if (markerId != null) store.addPendingAudioMarker(file, markerId, 750L, payload + " marker", start + 750L);
        JSONObject local = store.stageCapture(destination, entryId, kind, file, start, end, metadata, "complete");
        return new QueuedCapture(entryId, kind, file, bytes, start, end, metadata, local);
    }

    private void assertStaged(QueuedCapture capture, JSONArray pendingEntries) throws Exception {
        assertTrue(capture.local.getBoolean("_pending"));
        assertFalse("A staged summary must not fabricate an uncomputed checksum", capture.local.has("sha256"));
        assertTrue(capture.file.isFile());
        JSONObject entry = entryById(pendingEntries, capture.entryId);
        assertTrue(entry.getBoolean("_pending"));
        JSONObject pendingMedia = mediaById(entry, capture.local.getString("id"));
        assertEquals(capture.local.toString(), pendingMedia.toString());
        JSONObject journal = new JSONObject(new String(readFile(new File(capture.file.getPath() + ".json")), StandardCharsets.UTF_8));
        assertTrue(journal.getBoolean("finalized"));
        assertEquals(project.toString(), journal.getString("projectUri"));
        assertEquals(capture.entryId, journal.getString("entryId"));
        assertEquals(capture.start, journal.getLong("startedAtMs"));
        assertEquals(capture.end, journal.getLong("endedAtMs"));
    }

    private void assertPublished(QueuedCapture capture, JSONObject index) throws Exception {
        assertPublished(project, capture, index);
    }

    private void assertPublished(Uri destination, QueuedCapture capture, JSONObject index) throws Exception {
        JSONObject entry = entryById(index.getJSONArray("entries"), capture.entryId);
        JSONObject media = mediaById(entry, capture.local.getString("id"));
        assertFalse(media.optBoolean("_pending", false));
        assertEquals(capture.kind, media.getString("kind"));
        assertEquals(Instant.ofEpochMilli(capture.start).toString(), media.getString("startedAt"));
        assertEquals(Instant.ofEpochMilli(capture.end).toString(), media.getString("endedAt"));
        assertEquals(capture.end - capture.start, media.getLong("durationMs"));
        assertEquals(sha256(capture.bytes), media.getString("sha256"));
        assertEquals(capture.metadata.toString(), media.getJSONObject("metadata").toString());
        if (capture.local.has("markers")) {
            assertEquals(capture.local.getJSONArray("markers").toString(), media.getJSONArray("markers").toString());
            assertEquals(media.getString("id"), media.getJSONArray("markers").getJSONObject(0).getString("mediaId"));
        }
        assertArrayEquals(capture.bytes, readUri(store.mediaUri(destination, media.getString("path"))));
        assertFalse(capture.file.exists());
        assertFalse(new File(capture.file.getPath() + ".json").exists());
    }

    private void awaitOwnQueueDrained(List<QueuedCapture> queued) throws Exception {
        awaitCondition("Every staged original must publish after the provider is released", 15_000L, () -> {
            if (ProjectPublisher.snapshot().running) return false;
            for (QueuedCapture capture : queued)
                if (capture.file.exists() || new File(capture.file.getPath() + ".json").exists()) return false;
            return true;
        });
    }

    private void awaitCondition(String message, long timeoutMs, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        do {
            if (condition.met()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail(message);
    }

    private JSONObject entryById(JSONArray entries, String id) throws Exception {
        for (int i = 0; i < entries.length(); i++)
            if (id.equals(entries.getJSONObject(i).getString("id"))) return entries.getJSONObject(i);
        throw new AssertionError("Missing entry " + id);
    }

    private JSONObject mediaById(JSONObject entry, String id) throws Exception {
        JSONArray media = entry.getJSONArray("media");
        for (int i = 0; i < media.length(); i++)
            if (id.equals(media.getJSONObject(i).getString("id"))) return media.getJSONObject(i);
        throw new AssertionError("Missing media " + id);
    }

    private void assertSingleZip() {
        File[] files = fixtureRoot.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertTrue(files[0].isFile());
        assertTrue(files[0].getName().endsWith(".zip"));
    }

    private byte[] readUri(Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Test provider did not open the original");
        return readAll(input);
    }

    private byte[] readFile(File file) throws IOException { return readAll(new FileInputStream(file)); }

    private byte[] readAll(InputStream input) throws IOException {
        try (InputStream owned = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = owned.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private String sha256(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(value))
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        return result.toString();
    }

    private void deleteOwnedPending(File file) throws IOException {
        File allowed = new File(context.getFilesDir(), "pending-captures").getCanonicalFile();
        if (!allowed.equals(file.getCanonicalFile().getParentFile())) throw new IOException("Queue fixture escaped staging");
        if (file.exists() && !file.delete()) throw new IOException("Could not remove owned queue fixture");
    }

    private void deleteOwnedTree(File target, File ownedRoot) throws IOException {
        String allowed = ownedRoot.getCanonicalPath();
        String actual = target.getCanonicalPath();
        if (!actual.equals(allowed) && !actual.startsWith(allowed + File.separator))
            throw new IOException("Queue fixture escaped its owned directory");
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Could not list owned queue fixture");
            for (File child : children) deleteOwnedTree(child, ownedRoot);
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not remove owned queue fixture");
    }

    private interface Condition { boolean met() throws Exception; }

    private static final class QueuedCapture {
        final String entryId;
        final String kind;
        final File file;
        final byte[] bytes;
        final long start;
        final long end;
        final JSONObject metadata;
        final JSONObject local;
        QueuedCapture(String entryId, String kind, File file, byte[] bytes, long start, long end,
                      JSONObject metadata, JSONObject local) {
            this.entryId = entryId;
            this.kind = kind;
            this.file = file;
            this.bytes = bytes;
            this.start = start;
            this.end = end;
            this.metadata = metadata;
            this.local = local;
        }
    }
}
