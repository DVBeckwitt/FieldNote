package com.fieldnote.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.SystemClock;
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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Storage-contract tests use byte fixtures, not claims of encoded camera/audio validation. */
@RunWith(AndroidJUnit4.class)
public final class ProjectStoreInstrumentedTest {
    private Context context;
    private ProjectStore store;
    private File fixtureRoot;
    private Uri fixtureTree;
    private SharedPreferences bookmarks;
    private Map<String, ?> previousBookmarks;
    private final List<File> pendingFiles = new ArrayList<>();
    private final List<Uri> ownedArchives = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new ProjectStore(context);
        bookmarks = context.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        previousBookmarks = bookmarks.getAll();
        TestDocumentsProvider.clearFailures();
        String name = "instrumentation-" + UUID.randomUUID();
        File base = TestDocumentsProvider.rootDirectory(context);
        assertTrue(base.isDirectory() || base.mkdirs());
        fixtureRoot = new File(base, name);
        assertTrue(fixtureRoot.mkdir());
        fixtureTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root:" + name);
    }

    @After public void tearDown() throws Exception {
        TestDocumentsProvider.clearFailures();
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
        for (File file : pendingFiles) {
            deleteOwnPending(file);
            deleteOwnPending(new File(file.getPath() + ".json"));
            deleteOwnPending(new File(file.getPath() + ".json.bak"));
            deleteOwnPending(new File(file.getPath() + ".json.new"));
        }
        for (Uri archive : ownedArchives) deleteOwnPublication(archive);
        if (fixtureRoot != null) deleteFixtureTree(fixtureRoot);
    }

    @Test public void finishKeepsTheSameFolderAndCanBeReopenedForEdits() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        Uri working = store.createWorkingProject(fixtureTree, "Working round trip");
        assertEquals(working, store.currentProject());
        assertFalse(store.isArchive(working));
        assertWorkingFolderHasIndexAndMedia(working);
        assertEquals(0, exportedZipCount());

        long capturedAt = Instant.parse("2026-11-09T18:00:00Z").toEpochMilli();
        String entryId = UUID.randomUUID().toString();
        byte[] firstPhoto = encodedPhoto(0xff253d66);
        JSONObject original = saveFixture(working, entryId, "photo", firstPhoto, capturedAt, capturedAt);
        store.updateEntry(working, entryId, "Original note", "Before finishing");
        String originalPath = original.getString("path");
        assertArrayEquals(firstPhoto, bytes(store.mediaUri(working, originalPath)));
        assertEquals(0, exportedZipCount());

        Uri finished = store.finishWorkingProject(working);
        assertEquals(working, finished);
        assertFalse(store.isArchive(finished));
        assertTrue(store.isWorkingProject(working));
        assertTrue(store.projects().contains(working));
        assertNull(store.currentProject());
        assertNull(store.lastCreatedProject());
        assertEquals(finished, store.finishWorkingProject(working));
        assertEquals(0, exportedZipCount());
        assertArrayEquals(firstPhoto, bytes(store.mediaUri(finished, originalPath)));
        JSONObject exported = entryById(store.readProject(finished), entryId);
        assertEquals("Before finishing", exported.getString("text"));
        assertEquals(sha256(firstPhoto), exported.getJSONArray("media").getJSONObject(0).getString("sha256"));
        assertThrows(IOException.class, () -> store.updateEntry(finished, entryId, "Closed", "Open it before editing"));

        store.setCurrentProject(finished);
        assertEquals(finished, store.currentProject());
        assertWorkingFolderHasIndexAndMedia(finished);
        store.updateEntry(finished, entryId, "Edited note", "After reopening");
        byte[] secondPhoto = encodedPhoto(0xff305e48);
        JSONObject additional = saveFixture(finished, entryId, "photo", secondPhoto, capturedAt + 4_000L,
                capturedAt + 4_000L);

        assertEquals(finished, store.finishWorkingProject(finished));
        assertNull(store.currentProject());
        assertEquals(0, exportedZipCount());
        JSONObject edited = entryById(store.readProject(finished), entryId);
        assertEquals("After reopening", edited.getString("text"));
        assertEquals(2, edited.getJSONArray("media").length());
        assertEquals(original.getString("startedAt"), edited.getJSONArray("media").getJSONObject(0)
                .getString("startedAt"));
        assertArrayEquals(firstPhoto, bytes(store.mediaUri(finished, originalPath)));
        assertArrayEquals(secondPhoto, bytes(store.mediaUri(finished, additional.getString("path"))));
    }

    @Test public void failedFinishPreservesWorkingProjectForRetry() throws Exception {
        Uri working = store.createWorkingProject(fixtureTree, "Retry finish");
        String entryId = UUID.randomUUID().toString();
        byte[] photo = encodedPhoto(0xff253d66);
        long now = System.currentTimeMillis();
        JSONObject media = saveFixture(working, entryId, "photo", photo, now, now);
        store.updateEntry(working, entryId, "Keep this note", "Keep this draft");
        JSONObject before = store.readProject(working);
        File original = new File(new File(fixtureRoot, "Retry finish"), media.getString("path"));
        writeFile(original, new byte[]{1});
        assertThrows(IOException.class, () -> store.finishWorkingProject(working));
        assertEquals(working, store.currentProject());
        assertEquals(before.toString(), store.readProject(working).toString());
        assertEquals(0, exportedZipCount());

        writeFile(original, photo);
        Uri finished = store.finishWorkingProject(working);
        assertEquals(working, finished);
        assertEquals("Keep this draft", entryById(store.readProject(finished), entryId).getString("text"));
        assertArrayEquals(photo, bytes(store.mediaUri(finished, media.getString("path"))));
        assertEquals(0, exportedZipCount());
    }

    @Test public void interruptedLegacyFinishLeavesTheFolderAndDoesNotRewriteTheZip() throws Exception {
        verifyLegacyFinishRecovery(false);
    }

    @Test public void committedLegacyFinishRemainsReadableAfterRetiringItsJournal() throws Exception {
        verifyLegacyFinishRecovery(true);
    }

    private void verifyLegacyFinishRecovery(boolean committed) throws Exception {
        Uri working = store.createWorkingProject(fixtureTree, "Older Finish");
        JSONObject index = store.readProject(working);
        byte[] snapshot = zip(Collections.singletonMap("index.json", index.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] external = committed ? snapshot : new byte[]{1, 2, 3};
        Uri archive = writeArchive("Older Finish.zip", external);
        String key = sha256(archive.toString().getBytes(StandardCharsets.UTF_8));
        File publication = new File(new File(context.getFilesDir(), "archive-publications"), key);
        File retired = new File(context.getNoBackupFilesDir(), "retired-zip-finishes");
        assertTrue(publication.mkdirs());
        Files.write(new File(publication, "next.zip").toPath(), snapshot);
        JSONObject record = new JSONObject().put("schemaVersion", 1).put("projectUri", archive.toString())
                .put("projectId", index.getString("id")).put("purpose", "finish")
                .put("nextSha256", sha256(snapshot)).put("nextBytes", snapshot.length);
        Files.write(new File(publication, "publication.json").toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
        try {
            if (!committed) assertArchiveRejected(archive);
            store.retryPending();
            assertArrayEquals(external, bytes(archive));
            assertFalse(new File(publication, "publication.json").exists());
            assertEquals(working, store.currentProject());
            assertEquals(index.toString(), store.readProject(working).toString());
            assertEquals(working, store.finishWorkingProject(working));
            assertEquals(1, exportedZipCount());
            if (committed) {
                assertEquals(index.getString("id"), store.readProject(archive).getString("id"));
                Uri imported = store.importWorkingProject(archive, fixtureTree);
                assertFalse(store.isArchive(imported));
                assertEquals(index.getString("id"), store.readProject(imported).getString("id"));
                assertEquals(1, exportedZipCount());
                assertArrayEquals(external, bytes(archive));
            } else {
                File[] retained = retired.listFiles(file -> file.getName().startsWith(key + "-"));
                assertNotNull(retained);
                assertEquals(1, retained.length);
                assertArrayEquals(snapshot, readFile(new File(retained[0], "next.zip")));
            }
        } finally {
            File[] retained = retired.listFiles(file -> file.getName().startsWith(key + "-"));
            if (retained != null) for (File owned : retained) deletePublicationTree(owned, owned);
        }
    }

    @Test public void importingAPendingLegacyCaptureRecoversItIntoTheFolderWithoutRewritingItsSource() throws Exception {
        Uri source = createProject("Pending archive capture");
        byte[] sourceBefore = bytes(source);
        byte[] recording = "Retained original from a prior ZIP save".getBytes(StandardCharsets.UTF_8);
        File pending = fixtureFile("audio", recording);
        String entryId = UUID.randomUUID().toString();
        long started = Instant.parse("2026-11-05T15:00:00Z").toEpochMilli();
        JSONObject metadata = fixtureMetadata();
        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        assertThrows(IOException.class, () -> store.saveCapture(source, entryId, "audio", pending,
                started, started + 4_000L, metadata, "complete"));
        assertArrayEquals(recording, readFile(pending));
        assertArrayEquals(sourceBefore, bytes(source));

        Uri pickerAlias = DocumentsContract.buildDocumentUri(source.getAuthority(), DocumentsContract.getDocumentId(source));
        assertNotEquals(source, pickerAlias);
        Uri recovered = store.importWorkingProject(pickerAlias, fixtureTree);
        assertFalse(store.isArchive(recovered));
        JSONObject media = entryById(store.readProject(recovered), entryId).getJSONArray("media").getJSONObject(0);
        assertArrayEquals(recording, bytes(store.mediaUri(recovered, media.getString("path"))));
        store.retryPending();
        assertFalse(pending.exists());
        assertEquals(recovered, store.importWorkingProject(source, fixtureTree));
        assertEquals(recovered, store.importWorkingProject(pickerAlias, fixtureTree));
        assertEquals(1, entryById(store.readProject(recovered), entryId).getJSONArray("media").length());
        assertArrayEquals(sourceBefore, bytes(source));
        assertEquals(1, exportedZipCount());
    }

    @Test public void finishWaitsForAPendingCaptureRoutedFromTheLegacySource() throws Exception {
        Uri source = createProject("Legacy capture awaiting retry");
        byte[] sourceBefore = bytes(source);
        byte[] original = encodedPhoto(0xff253d66);
        File pending = fixtureFile("photo", original);
        String entryId = UUID.randomUUID().toString();
        long capturedAt = Instant.parse("2026-11-05T15:00:00Z").toEpochMilli();
        TestDocumentsProvider.failZipReads(source, 1);
        assertThrows(IOException.class, () -> store.saveCapture(source, entryId, "photo", pending,
                capturedAt, capturedAt, fixtureMetadata(), "complete"));

        Uri working = store.importWorkingProject(source, fixtureTree);
        assertEquals(0, store.readProject(working).getJSONArray("entries").length());
        IOException failure = assertThrows(IOException.class, () -> store.finishWorkingProject(working));
        assertTrue(failure.getMessage().contains("pending saves"));
        assertEquals(working, store.currentProject());
        assertArrayEquals(original, readFile(pending));

        store.retryPending();
        assertFalse(pending.exists());
        assertEquals(working, store.finishWorkingProject(working));
        JSONObject media = entryById(store.readProject(working), entryId).getJSONArray("media").getJSONObject(0);
        assertArrayEquals(original, bytes(store.mediaUri(working, media.getString("path"))));
        assertArrayEquals(sourceBefore, bytes(source));
        assertEquals(1, exportedZipCount());
    }

    @Test public void finishWaitsForAnOlderWatchReceiptRoutedToTheFolder() throws Exception {
        Uri source = createProject("Earlier watch recording destination");
        Uri working = store.importWorkingProject(source, fixtureTree);
        assertEquals(source, store.beginWatchTransfer(source, false));
        try {
            IOException failure = assertThrows(IOException.class, () -> store.finishWorkingProject(working));
            assertTrue(failure.getMessage().contains("watch recording"));
            assertEquals(working, store.currentProject());
        } finally { store.endWatchTransfer(); }

        assertEquals(working, store.finishWorkingProject(working));
        assertThrows(IOException.class, () -> store.beginWatchTransfer(source, false));
        store.setCurrentProject(working);
        try { assertEquals(source, store.beginWatchTransfer(source, false)); }
        finally { store.endWatchTransfer(); }
    }

    @Test public void savedAudioAutomaticallyGetsItsOwnTranscriptInTheFinishedFolder() throws Exception {
        Uri working = store.createWorkingProject(fixtureTree, "Spoken project");
        String entryId = UUID.randomUUID().toString();
        byte[] spoken = readAll(InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("spoken-aac-stereo.m4a"));
        long start = Instant.parse("2026-11-09T18:00:00Z").toEpochMilli();
        JSONObject original = saveFixture(working, entryId, "audio", spoken, start, start + 6_000L);
        store.updateEntry(working, entryId, "Recorded observation", "Keep my typed note");
        assertEquals(0, exportedZipCount());

        // The save must enqueue the Android job itself. Force only its scheduling
        // window so a device's JobScheduler quotas cannot delay instrumentation.
        String command = "cmd jobscheduler run -f " + context.getPackageName() + " " + AudioTranscriptionJob.JOB_ID;
        ParcelFileDescriptor scheduled = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        String result = new String(readAll(new ParcelFileDescriptor.AutoCloseInputStream(scheduled)), StandardCharsets.UTF_8);
        JSONObject entry;
        JSONObject media;
        JSONObject transcript;
        long deadline = SystemClock.elapsedRealtime() + 45_000L;
        do {
            entry = entryById(store.readProject(working), entryId);
            media = entry.getJSONArray("media").getJSONObject(0);
            transcript = media.getJSONObject("transcript");
            if (!"pending".equals(transcript.getString("status"))) break;
            SystemClock.sleep(100L);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals("Unexpected transcription status after JobScheduler reported " + result,
                "complete", transcript.getString("status"));
        assertTrue(transcript.getString("text").contains("blue notebook is on the table"));
        assertEquals("Keep my typed note", entry.getString("text"));
        assertEquals(original.getString("id"), media.getString("id"));
        assertEquals(sha256(spoken), media.getString("sha256"));
        assertArrayEquals(spoken, bytes(store.mediaUri(working, original.getString("path"))));
        assertEquals(0, exportedZipCount());

        Uri finished = store.finishWorkingProject(working);
        assertEquals(working, finished);
        JSONObject exportedAudio = entryById(store.readProject(finished), entryId).getJSONArray("media").getJSONObject(0);
        assertEquals(transcript.getString("text"), exportedAudio.getJSONObject("transcript").getString("text"));
        assertArrayEquals(spoken, bytes(store.mediaUri(finished, original.getString("path"))));
        assertEquals(0, exportedZipCount());
    }

    @Test public void latestCreatedProjectPersistsIndependentlyOfSelectionOpeningAndImport() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        Uri first = createProject("Created first");
        Uri latest = createProject("Created latest");
        store.setCurrentProject(first);
        assertEquals(latest, store.lastCreatedProject());

        Uri opened = writeEmptyProjectArchive("Opened newer elsewhere.zip", "2099-01-01T00:00:00Z");
        store.readProject(opened);
        store.rememberProject(opened);
        store.setCurrentProject(opened);
        assertEquals(latest, store.lastCreatedProject());

        File legacy = new File(fixtureRoot, "Imported legacy");
        assertTrue(legacy.mkdir());
        byte[] source = emptyIndex("Imported legacy").put("createdAt", "2100-01-01T00:00:00Z")
                .toString().getBytes(StandardCharsets.UTF_8);
        writeFile(new File(legacy, "index.json"), source);
        Uri legacyTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName() + "/" + legacy.getName());
        Uri imported = store.importLegacyProject(legacyTree, fixtureTree);
        ownedArchives.add(imported);
        assertEquals(imported, store.currentProject());
        assertEquals(latest, store.lastCreatedProject());
        assertEquals(latest, new ProjectStore(context).lastCreatedProject());
        assertEquals(latest.toString(), bookmarks.getString("last-created-project", null));
        assertArrayEquals(source, readFile(new File(legacy, "index.json")));
    }

    @Test public void failedCreationDoesNotChangeLatestCreatedProject() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        Uri latest = createProject("Successful creation");
        Uri failed = DocumentsContract.buildDocumentUriUsingTree(fixtureTree,
                "root:" + fixtureRoot.getName() + "/Failed creation.zip");
        ownedArchives.add(failed);
        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        try {
            store.createProject(fixtureTree, "Failed creation");
            fail("The fixture must fail before project creation completes");
        } catch (IOException expected) {
            assertEquals(latest, store.lastCreatedProject());
            assertEquals(latest, new ProjectStore(context).lastCreatedProject());
            assertEquals(latest, store.currentProject());
        }
    }

    @Test public void unavailableLatestCreatedProjectNeverFallsBackToAnOlderProject() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        Uri older = createProject("Accessible older project");
        Uri latest = createProject("Unavailable latest project");
        store.setCurrentProject(older);
        TestDocumentsProvider.failZipReads(latest, 1);
        assertEquals(latest, new ProjectStore(context).lastCreatedProject());
        try {
            store.readProject(latest);
            fail("Latest-project lookup must not consume provider access failures");
        } catch (IOException expected) { assertNotNull(expected.getMessage()); }
        assertTrue(new File(fixtureRoot, "Unavailable latest project.zip").delete());
        assertEquals(latest, store.lastCreatedProject());
        assertEquals(older, store.currentProject());
    }

    @Test public void upgradeLatestProjectUsesCreationTimeAndDeterministicTiesWithoutChangingPreferences() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        Uri older = writeEmptyProjectArchive("A earlier.zip", "2026-11-05T15:00:00.000000001Z");
        Uri later = writeEmptyProjectArchive("Z later.zip", "2026-11-05T15:00:00.000000002Z");
        store.rememberProject(later);
        store.setCurrentProject(older);
        byte[] olderBytes = bytes(older), laterBytes = bytes(later);
        assertEquals("Submillisecond differences are creation order, not URI ties", later, store.lastCreatedProject());
        Uri tied = writeEmptyProjectArchive("B same time.zip", "2026-11-05T15:00:00.000000002Z");
        store.rememberProject(tied);
        assertEquals(tied, store.lastCreatedProject());
        assertEquals(tied, new ProjectStore(context).lastCreatedProject());
        assertEquals(older, store.currentProject());
        assertFalse("Only successful creation writes the explicit latest-project preference", bookmarks.contains("last-created-project"));
        assertArrayEquals(olderBytes, bytes(older));
        assertArrayEquals(laterBytes, bytes(later));
    }

    @Test public void upgradeLatestProjectRejectsIndeterminateBookmarksAndReturnsNullOnlyWhenEmpty() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        assertNull(store.lastCreatedProject());
        for (String invalid : new String[]{"not JSON", "[null]", "[42]", "[\"\"]", "[] garbage", "[] {}"}) {
            assertTrue(bookmarks.edit().putString("projects", invalid).commit());
            try { store.lastCreatedProject(); fail("Malformed bookmark inventory cannot mean there are no projects"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
        assertTrue(bookmarks.edit().clear().commit());
        Uri older = writeEmptyProjectArchive("Known older.zip", "2025-01-01T00:00:00Z");
        Uri unknown = writeEmptyProjectArchive("Unavailable bookmark.zip", "2026-01-01T00:00:00Z");
        store.setCurrentProject(older);
        store.rememberProject(unknown);
        TestDocumentsProvider.failZipReads(unknown, 1);
        byte[] before = bytes(older);
        try { store.lastCreatedProject(); fail("An unreadable bookmark could be newer; do not guess an older target"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("could not be determined")); }
        assertEquals(older, store.currentProject());
        assertFalse(bookmarks.contains("last-created-project"));
        assertArrayEquals(before, bytes(older));
    }

    @Test public void upgradeLatestProjectDiscoveryNeverRepairsAnUnreadableArchive() throws Exception {
        assertTrue(bookmarks.edit().clear().commit());
        Uri project = createProject("Discovery must not recover");
        File pending = prepareFailedAudioPublication(project, Instant.parse("2026-11-05T15:00:00Z").toEpochMilli(),
                "Retained pending audio fixture".getBytes(StandardCharsets.UTF_8));
        byte[] original = readFile(pending);
        byte[] broken = "Externally unreadable ZIP while a private backup exists".getBytes(StandardCharsets.UTF_8);
        File canonical = new File(fixtureRoot, "Discovery must not recover.zip");
        writeFile(canonical, broken);
        assertTrue(bookmarks.edit().remove("last-created-project").commit());
        try { store.lastCreatedProject(); fail("Discovery must not repair a bookmarked source to determine its creation time"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
        assertArrayEquals(broken, readFile(canonical));
        assertArrayEquals(original, readFile(pending));
        assertEquals(project, store.currentProject());
        assertFalse(bookmarks.contains("last-created-project"));
    }

    @Test public void attachmentsPreserveOriginalTimesAndBytes() throws Exception {
        Uri project = createProject("Linked originals");
        long photoAt = Instant.parse("2026-11-05T15:10:00Z").toEpochMilli();
        long audioAt = photoAt + 8_000L;
        String entryId = UUID.randomUUID().toString();
        byte[] photoBytes = "JPEG storage fixture; not encoded media".getBytes(StandardCharsets.UTF_8);
        byte[] audioBytes = new byte[4096];
        for (int i = 0; i < audioBytes.length; i++) audioBytes[i] = (byte) (i * 31);

        JSONObject photo = saveFixture(project, entryId, "photo", photoBytes, photoAt, photoAt);
        JSONObject audio = saveFixture(project, entryId, "audio", audioBytes, audioAt, audioAt + 3_000L);
        JSONObject index = store.readProject(project);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONObject entry = index.getJSONArray("entries").getJSONObject(0);
        assertEquals(Instant.ofEpochMilli(photoAt).toString(), entry.getString("createdAt"));
        JSONArray media = entry.getJSONArray("media");
        assertEquals(2, media.length());
        assertEquals(Instant.ofEpochMilli(photoAt).toString(), media.getJSONObject(0).getString("startedAt"));
        assertEquals(Instant.ofEpochMilli(audioAt).toString(), media.getJSONObject(1).getString("startedAt"));
        assertEquals(3_000L, media.getJSONObject(1).getLong("durationMs"));
        assertNotEquals(photo.getString("path"), audio.getString("path"));
        assertTrue(photo.getString("path").startsWith("media/"));
        assertArrayEquals(photoBytes, bytes(store.mediaUri(project, photo.getString("path"))));
        assertArrayEquals(audioBytes, bytes(store.mediaUri(project, audio.getString("path"))));
        assertFalse(pendingFiles.get(0).exists());
        assertFalse(pendingFiles.get(1).exists());
        assertTrue(store.isArchive(project));
        assertSingleExternalZip();
        Map<String, byte[]> members = archiveMembers(bytes(project));
        assertEquals(3, members.size());
        assertArrayEquals(photoBytes, members.get(photo.getString("path")));
        assertArrayEquals(audioBytes, members.get(audio.getString("path")));
        JSONObject archivedIndex = new JSONObject(new String(members.get("index.json"), StandardCharsets.UTF_8));
        assertEquals(index.getJSONArray("entries").toString(), archivedIndex.getJSONArray("entries").toString());
    }

    @Test public void copiedZipReopensWithoutBookmarksOrOriginalArchive() throws Exception {
        Uri project = createProject("Original project");
        long at = Instant.parse("2026-11-05T15:00:00Z").toEpochMilli();
        byte[] original = "Portable original bytes".getBytes(StandardCharsets.UTF_8);
        String linkedId = UUID.randomUUID().toString();
        JSONObject media = saveFixture(project, linkedId, "photo", original, at, at);
        store.saveText(project, linkedId, "Original apparatus description", fixtureMetadata());
        store.updateEntry(project, linkedId, "Apparatus at baseline", "Revised apparatus description\nSecond line retained.");
        JSONObject standalone = store.saveText(project, null, "A portable standalone note", defaultMetadataSnapshot());
        store.updateEntry(project, standalone.getString("id"), "Lab conditions", null);
        JSONObject originalIndex = store.readProject(project);
        assertSingleExternalZip();
        File source = new File(fixtureRoot, "Original project.zip");
        assertTrue(source.isFile());
        File copied = new File(fixtureRoot, "Copied elsewhere.zip");
        try (FileOutputStream output = new FileOutputStream(copied)) { output.write(bytes(project)); }
        assertTrue(source.delete());
        assertTrue(bookmarks.edit().clear().commit());

        // A plain document URI models ACTION_OPEN_DOCUMENT and carries no old tree.
        Uri copiedArchive = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName() + "/" + copied.getName());
        ownedArchives.add(copiedArchive);
        ProjectStore fresh = new ProjectStore(context);
        assertTrue(fresh.projects().isEmpty());
        assertNull(fresh.currentProject());
        JSONObject reopened = fresh.readProject(copiedArchive);
        assertEquals(originalIndex.getString("id"), reopened.getString("id"));
        assertEquals(originalIndex.getJSONArray("entries").toString(), reopened.getJSONArray("entries").toString());
        assertEquals("Apparatus at baseline", entryById(reopened, linkedId).getString("title"));
        assertEquals("Revised apparatus description\nSecond line retained.", entryById(reopened, linkedId).getString("text"));
        assertEquals("Lab conditions", entryById(reopened, standalone.getString("id")).getString("title"));
        assertEquals(0, entryById(reopened, standalone.getString("id")).getJSONArray("media").length());
        assertArrayEquals(original, bytes(fresh.mediaUri(copiedArchive, media.getString("path"))));
        assertTrue(fresh.isArchive(copiedArchive));
        fresh.rememberProject(copiedArchive);
        fresh.setCurrentProject(copiedArchive);
        assertEquals(copiedArchive, fresh.currentProject());
        assertSingleExternalZip();
    }

    @Test public void textBeforeAudioAndLaterEditsKeepOneZipAndOriginalCapture() throws Exception {
        Uri project = createProject("Text and narration");
        String entryId = UUID.randomUUID().toString();
        long started = Instant.parse("2026-11-05T15:40:00Z").toEpochMilli();
        long ended = started + 12_000L;
        byte[] audioBytes = "Original narration bytes remain unchanged by note editing".getBytes(StandardCharsets.UTF_8);
        File pending = fixtureFile("audio", audioBytes);
        JSONObject captureMetadata = fixtureMetadata();
        store.beginCapture(project, entryId, "audio", pending, started, captureMetadata);

        // Text can be saved into the recording's entry before its audio finalizes.
        JSONObject textMetadata = defaultMetadataSnapshot();
        JSONObject initialText = store.saveText(project, entryId, "Pressure increasing.\nCheck valve A.", textMetadata);
        assertEquals(entryId, initialText.getString("id"));
        assertEquals(0, initialText.getJSONArray("media").length());
        assertEquals(textMetadata.toString(), initialText.getJSONObject("textMetadata").toString());
        assertEquals("enabled", initialText.getJSONObject("textMetadata").getJSONObject("optionalMetadata").getString("status"));
        assertTrue(pending.isFile());
        assertSingleExternalZip();

        JSONObject audio = store.saveCapture(project, entryId, "audio", pending, started, ended, captureMetadata, "complete");
        JSONObject attached = entryById(store.readProject(project), entryId);
        assertEquals(initialText.getString("createdAt"), attached.getString("createdAt"));
        assertEquals(initialText.getString("textCreatedAt"), attached.getString("textCreatedAt"));
        assertEquals(initialText.getString("text"), attached.getString("text"));
        assertEquals(1, attached.getJSONArray("media").length());
        assertEquals(Instant.ofEpochMilli(started).toString(), audio.getString("startedAt"));
        assertEquals(Instant.ofEpochMilli(ended).toString(), audio.getString("endedAt"));
        assertEquals(12_000L, audio.getLong("durationMs"));
        assertFalse(pending.exists());

        JSONObject edited = store.updateEntry(project, entryId, "Valve A: pressure test", "Pressure stabilized.\nValve A closed at the end.");
        assertEquals("Valve A: pressure test", edited.getString("title"));
        assertEquals(initialText.getString("createdAt"), edited.getString("createdAt"));
        assertEquals(initialText.getString("textCreatedAt"), edited.getString("textCreatedAt"));
        assertEquals(textMetadata.toString(), edited.getJSONObject("textMetadata").toString());
        assertFalse(Instant.parse(edited.getString("textUpdatedAt")).isBefore(Instant.parse(initialText.getString("textUpdatedAt"))));
        assertEquals(audio.toString(), edited.getJSONArray("media").getJSONObject(0).toString());

        JSONObject standaloneMetadata = defaultMetadataSnapshot();
        long beforeText = System.currentTimeMillis();
        JSONObject standalone = store.saveText(project, null, "Ambient conditions entered without a recording.", standaloneMetadata);
        long afterText = System.currentTimeMillis();
        assertNotEquals(entryId, standalone.getString("id"));
        assertEquals(0, standalone.getJSONArray("media").length());
        long textCreated = Instant.parse(standalone.getString("textCreatedAt")).toEpochMilli();
        assertTrue(textCreated >= beforeText && textCreated <= afterText);
        assertEquals(standalone.getString("createdAt"), standalone.getString("textCreatedAt"));
        assertEquals(standaloneMetadata.toString(), standalone.getJSONObject("textMetadata").toString());
        assertNotNull(Instant.parse(standalone.getJSONObject("textMetadata").getString("observedAt")));
        store.updateEntry(project, standalone.getString("id"), "Ambient conditions", "Ambient conditions revised after observation.");

        JSONObject persisted = archivedIndex(bytes(project));
        assertEquals(2, persisted.getJSONArray("entries").length());
        assertEquals(edited.toString(), entryById(persisted, entryId).toString());
        JSONObject persistedStandalone = entryById(persisted, standalone.getString("id"));
        assertEquals("Ambient conditions", persistedStandalone.getString("title"));
        assertEquals("Ambient conditions revised after observation.", persistedStandalone.getString("text"));
        assertEquals(standalone.getString("createdAt"), persistedStandalone.getString("createdAt"));
        assertEquals(standalone.getString("textCreatedAt"), persistedStandalone.getString("textCreatedAt"));
        assertEquals(standaloneMetadata.toString(), persistedStandalone.getJSONObject("textMetadata").toString());
        assertEquals(0, persistedStandalone.getJSONArray("media").length());
        assertEquals(2, archiveMembers(bytes(project)).size());
        assertArrayEquals(audioBytes, archiveMembers(bytes(project)).get(audio.getString("path")));
        assertArrayEquals(audioBytes, bytes(store.mediaUri(project, audio.getString("path"))));
        assertEquals(project, store.currentProject());
        assertEquals(1, java.util.Collections.frequency(store.projects(), project));
        assertSingleExternalZip();
    }

    @Test public void failedTextEditRestoresPreviousZipAndRetriesExactlyOnce() throws Exception {
        Uri project = createProject("Retry edited note");
        String entryId = UUID.randomUUID().toString();
        long at = Instant.parse("2026-11-05T15:50:00Z").toEpochMilli();
        byte[] original = "Narration saved before a text edit failure".getBytes(StandardCharsets.UTF_8);
        JSONObject audio = saveFixture(project, entryId, "audio", original, at, at + 5_000L);
        store.saveText(project, entryId, "Original observation", fixtureMetadata());
        store.updateEntry(project, entryId, "Initial title", null);
        JSONObject beforeEntry = entryById(store.readProject(project), entryId);
        byte[] previousZip = bytes(project);
        int previousPending = store.pendingCount();

        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        try {
            store.updateEntry(project, entryId, "Corrected title", "Corrected observation retained for retry");
            fail("Injected failure during index-only publication must be reported");
        } catch (IOException expected) {
            assertArrayEquals("A failed edit must restore the exact previous project ZIP", previousZip, bytes(project));
        }
        assertEquals(previousPending + 1, store.pendingCount());
        assertEquals(beforeEntry.toString(), entryById(archivedIndex(bytes(project)), entryId).toString());
        assertSingleExternalZip();

        ProjectStore restarted = new ProjectStore(context);
        assertEquals(1, restarted.retryPending());
        assertEquals(previousPending, restarted.pendingCount());
        JSONObject recoveredIndex = restarted.readProject(project);
        assertEquals(1, recoveredIndex.getJSONArray("entries").length());
        JSONObject recovered = entryById(recoveredIndex, entryId);
        assertEquals("Corrected title", recovered.getString("title"));
        assertEquals("Corrected observation retained for retry", recovered.getString("text"));
        assertEquals(beforeEntry.getString("createdAt"), recovered.getString("createdAt"));
        assertEquals(beforeEntry.getString("textCreatedAt"), recovered.getString("textCreatedAt"));
        assertEquals(beforeEntry.getJSONObject("textMetadata").toString(), recovered.getJSONObject("textMetadata").toString());
        assertEquals(beforeEntry.getJSONArray("media").toString(), recovered.getJSONArray("media").toString());
        assertArrayEquals(original, bytes(restarted.mediaUri(project, audio.getString("path"))));
        assertEquals(2, archiveMembers(bytes(project)).size());
        byte[] committed = bytes(project);
        assertEquals(0, restarted.retryPending());
        assertArrayEquals("A second retry must not republish or duplicate the edit", committed, bytes(project));
        JSONObject repeated = restarted.saveText(project, entryId, recovered.getString("text"), fixtureMetadata());
        assertEquals(recovered.toString(), repeated.toString());
        assertArrayEquals("Repeating the same text with its stable entry ID must be a no-op", committed, bytes(project));
        assertEquals(project, restarted.currentProject());
        assertSingleExternalZip();
    }

    @Test public void failedZipPublicationRestoresPreviousArchiveAndRetriesWithoutDuplicates() throws Exception {
        Uri project = createProject("Retry project");
        byte[] previousZip = bytes(project);
        int previousPending = store.pendingCount();
        long at = Instant.parse("2026-11-05T15:20:00Z").toEpochMilli();
        String entryId = UUID.randomUUID().toString();
        byte[] original = "A capture that must survive index-write failure".getBytes(StandardCharsets.UTF_8);
        File pending = fixtureFile("audio", original);
        JSONObject metadata = fixtureMetadata();
        store.beginCapture(project, entryId, "audio", pending, at, metadata);
        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        try {
            store.saveCapture(project, entryId, "audio", pending, at, at + 4_000L, metadata, "complete");
            fail("Injected archive publication failure must be reported");
        } catch (IOException expected) {
            assertTrue("The local original must survive failed archive publication", pending.isFile());
        }
        assertEquals(previousPending + 1, store.pendingCount());
        assertArrayEquals(original, readFile(pending));
        assertArrayEquals("Previous ZIP must be restored after the failed/truncated provider write", previousZip, bytes(project));
        JSONObject canonicalBeforeRetry = archivedIndex(bytes(project));
        assertEquals(0, canonicalBeforeRetry.getJSONArray("entries").length());
        assertSingleExternalZip();

        ProjectStore restarted = new ProjectStore(context);
        assertEquals(1, restarted.retryPending());
        assertFalse(pending.exists());
        assertEquals(previousPending, restarted.pendingCount());
        JSONObject canonical = archivedIndex(bytes(project));
        assertEquals(1, canonical.getJSONArray("entries").length());
        JSONArray media = canonical.getJSONArray("entries").getJSONObject(0).getJSONArray("media");
        assertEquals(1, media.length());
        assertArrayEquals(original, bytes(restarted.mediaUri(project, media.getJSONObject(0).getString("path"))));
        assertEquals(2, archiveMembers(bytes(project)).size());
        assertSingleExternalZip();
        assertEquals(0, restarted.retryPending());
    }

    @Test public void legacyFolderConversionPreservesOriginalsAndRelationships() throws Exception {
        File legacy = new File(fixtureRoot, "Legacy beta1");
        File legacyMedia = new File(legacy, "media");
        assertTrue(legacyMedia.mkdirs());
        byte[] original = "Original legacy project media bytes".getBytes(StandardCharsets.UTF_8);
        JSONObject index = indexedFixture("Legacy beta1", "photo_fixture.jpg", original);
        byte[] indexBytes = index.toString(2).getBytes(StandardCharsets.UTF_8);
        writeFile(new File(legacy, "index.json"), indexBytes);
        writeFile(new File(legacyMedia, "photo_fixture.jpg"), original);
        Uri legacyTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName() + "/Legacy beta1");
        store.rememberProject(legacyTree);
        store.setCurrentProject(legacyTree);

        Uri archive = store.importLegacyProject(legacyTree, fixtureTree);
        ownedArchives.add(archive);
        assertTrue(store.isArchive(archive));
        assertFalse(store.projects().contains(legacyTree));
        assertEquals(1, java.util.Collections.frequency(store.projects(), archive));
        assertEquals(archive, store.currentProject());
        JSONObject converted = archivedIndex(bytes(archive));
        assertEquals(index.getString("id"), converted.getString("id"));
        assertEquals(index.getJSONArray("entries").toString(), converted.getJSONArray("entries").toString());
        assertArrayEquals(original, archiveMembers(bytes(archive)).get("media/photo_fixture.jpg"));
        assertArrayEquals("Legacy index must not be rewritten", indexBytes, readFile(new File(legacy, "index.json")));
        assertArrayEquals("Legacy media must remain unchanged", original, readFile(new File(legacyMedia, "photo_fixture.jpg")));
        assertEquals(2, legacy.listFiles().length);
        assertEquals(1, legacyMedia.listFiles().length);
    }

    @Test public void temporaryReadFailuresNeverOverwriteAnExternallyChangedProject() throws Exception {
        Uri project = createProject("Read failure conflict");
        JSONObject initialIndex = store.readProject(project);
        int baselinePending = store.pendingCount();
        long at = Instant.parse("2026-11-05T19:00:00Z").toEpochMilli();
        byte[] pendingBytes = "Local original awaiting safe publication".getBytes(StandardCharsets.UTF_8);
        File pending = prepareFailedAudioPublication(project, at, pendingBytes);

        // Another editor or sync client replaces the ZIP after our failed save.
        byte[] externalPhoto = "An externally added original must never be overwritten on a read error".getBytes(StandardCharsets.UTF_8);
        JSONObject externalIndex = indexedFixture(initialIndex.getString("name"), "external_photo.jpg", externalPhoto);
        externalIndex.put("id", initialIndex.getString("id"));
        externalIndex.put("createdAt", initialIndex.getString("createdAt"));
        externalIndex.put("revision", initialIndex.getLong("revision") + 1);
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("index.json", externalIndex.toString().getBytes(StandardCharsets.UTF_8));
        members.put("media/external_photo.jpg", externalPhoto);
        byte[] externalZip = zip(members);
        File canonical = new File(fixtureRoot, "Read failure conflict.zip");
        writeFile(canonical, externalZip);

        TestDocumentsProvider.failZipReads(project, 1);
        try {
            store.readProject(project);
            fail("A transient provider read failure must be reported without writing a recovery copy");
        } catch (IOException expected) {
            assertArrayEquals(externalZip, readFile(canonical));
        }

        // retryPending checks the publication journal and then its capture journal.
        TestDocumentsProvider.failZipReads(project, 2);
        try {
            store.retryPending();
            fail("Unavailable external bytes must not be treated as an interrupted write");
        } catch (IOException expected) {
            assertArrayEquals(externalZip, readFile(canonical));
        }
        assertArrayEquals(pendingBytes, readFile(pending));
        assertEquals(baselinePending + 1, store.pendingCount());
        assertEquals(externalIndex.toString(), store.readProject(project).toString());
        try {
            store.retryPending();
            fail("Once readable, the external change must still require conflict resolution");
        } catch (IOException expected) {
            assertArrayEquals(externalZip, readFile(canonical));
        }
        assertArrayEquals(pendingBytes, readFile(pending));
        assertArrayEquals(externalPhoto, archiveMembers(readFile(canonical)).get("media/external_photo.jpg"));
        assertSingleExternalZip();
    }

    @Test public void fullyReadTruncatedZipStillRecoversAndRetriesOriginalExactlyOnce() throws Exception {
        Uri project = createProject("Confirmed truncated ZIP");
        byte[] previousZip = bytes(project);
        long at = Instant.parse("2026-11-05T19:10:00Z").toEpochMilli();
        byte[] original = "Original retained while a confirmed incomplete ZIP is repaired".getBytes(StandardCharsets.UTF_8);
        File pending = prepareFailedAudioPublication(project, at, original);
        File canonical = new File(fixtureRoot, "Confirmed truncated ZIP.zip");
        writeFile(canonical, "truncated ZIP bytes".getBytes(StandardCharsets.UTF_8));

        assertEquals(0, store.readProject(project).getJSONArray("entries").length());
        assertArrayEquals(previousZip, readFile(canonical));
        assertArrayEquals(original, readFile(pending));
        assertEquals(1, store.retryPending());
        JSONObject index = store.readProject(project);
        assertEquals(1, index.getJSONArray("entries").length());
        JSONArray media = index.getJSONArray("entries").getJSONObject(0).getJSONArray("media");
        assertEquals(1, media.length());
        assertEquals(Instant.ofEpochMilli(at).toString(), media.getJSONObject(0).getString("startedAt"));
        assertEquals(Instant.ofEpochMilli(at + 4_000L).toString(), media.getJSONObject(0).getString("endedAt"));
        assertArrayEquals(original, bytes(store.mediaUri(project, media.getJSONObject(0).getString("path"))));
        assertFalse(pending.exists());
        byte[] committed = bytes(project);
        assertEquals(0, store.retryPending());
        assertArrayEquals(committed, bytes(project));
        assertSingleExternalZip();
    }

    @Test public void finalizedLegacyPendingCaptureRetriesIntoMigratedZipExactlyOnce() throws Exception {
        File legacy = new File(fixtureRoot, "Legacy pending beta1");
        File legacyMedia = new File(legacy, "media");
        assertTrue(legacyMedia.mkdirs());
        byte[] photoBytes = "An original already saved by beta1".getBytes(StandardCharsets.UTF_8);
        JSONObject legacyIndex = indexedFixture("Legacy pending beta1", "photo_fixture.jpg", photoBytes);
        byte[] legacyIndexBytes = legacyIndex.toString(2).getBytes(StandardCharsets.UTF_8);
        writeFile(new File(legacy, "index.json"), legacyIndexBytes);
        writeFile(new File(legacyMedia, "photo_fixture.jpg"), photoBytes);
        Uri legacyTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName() + "/Legacy pending beta1");
        store.rememberProject(legacyTree);
        store.setCurrentProject(legacyTree);

        int previousPending = store.pendingCount();
        String entryId = legacyIndex.getJSONArray("entries").getJSONObject(0).getString("id");
        long audioStart = Instant.parse("2026-11-05T15:11:00Z").toEpochMilli();
        long audioEnd = audioStart + 7_000L;
        byte[] audioBytes = "Finalized beta1 narration awaiting a successful save".getBytes(StandardCharsets.UTF_8);
        File pending = fixtureFile("audio", audioBytes);
        JSONObject metadata = fixtureMetadata();
        store.beginCapture(legacyTree, entryId, "audio", pending, audioStart, metadata);
        try {
            // Beta2 must preserve a finalized schema-v1 journal while an old
            // folder has not yet been converted; it cannot append loose files.
            store.saveCapture(legacyTree, entryId, "audio", pending, audioStart, audioEnd, metadata, "complete");
            fail("An unmigrated legacy destination must require conversion");
        } catch (IOException expected) {
            assertArrayEquals(audioBytes, readFile(pending));
        }
        JSONObject journal = new JSONObject(new String(readFile(new File(pending.getPath() + ".json")), StandardCharsets.UTF_8));
        assertEquals(1, journal.getInt("schemaVersion"));
        assertTrue(journal.getBoolean("finalized"));
        assertEquals(legacyTree.toString(), journal.getString("projectUri"));
        assertEquals(audioStart, journal.getLong("startedAtMs"));
        assertEquals(audioEnd, journal.getLong("endedAtMs"));
        assertEquals(previousPending + 1, store.pendingCount());
        assertArrayEquals(legacyIndexBytes, readFile(new File(legacy, "index.json")));
        assertEquals(1, legacyMedia.listFiles().length);

        ProjectStore upgraded = new ProjectStore(context);
        Uri archive = upgraded.importLegacyProject(legacyTree, fixtureTree);
        ownedArchives.add(archive);
        assertFalse(upgraded.projects().contains(legacyTree));
        assertEquals(1, java.util.Collections.frequency(upgraded.projects(), archive));
        assertEquals(archive, upgraded.currentProject());
        assertEquals(legacyIndex.getJSONArray("entries").toString(),
                archivedIndex(bytes(archive)).getJSONArray("entries").toString());

        // A separate store instance must resolve the persisted migration route
        // from the old journal's URI, not the currently selected project.
        ProjectStore restarted = new ProjectStore(context);
        restarted.setCurrentProject(null);
        assertEquals(1, restarted.retryPending());
        assertFalse(pending.exists());
        assertFalse(new File(pending.getPath() + ".json").exists());
        assertEquals(previousPending, restarted.pendingCount());
        JSONObject migrated = archivedIndex(bytes(archive));
        assertEquals(1, migrated.getJSONArray("entries").length());
        JSONObject entry = migrated.getJSONArray("entries").getJSONObject(0);
        assertEquals(entryId, entry.getString("id"));
        assertEquals("2026-11-05T15:10:00Z", entry.getString("createdAt"));
        JSONArray media = entry.getJSONArray("media");
        assertEquals(2, media.length());
        JSONObject photo = media.getJSONObject(0);
        JSONObject audio = media.getJSONObject(1);
        assertEquals(legacyIndex.getJSONArray("entries").getJSONObject(0).getJSONArray("media").getJSONObject(0).toString(), photo.toString());
        assertEquals("audio", audio.getString("kind"));
        assertEquals(Instant.ofEpochMilli(audioStart).toString(), audio.getString("startedAt"));
        assertEquals(Instant.ofEpochMilli(audioEnd).toString(), audio.getString("endedAt"));
        assertEquals(7_000L, audio.getLong("durationMs"));
        assertArrayEquals(audioBytes, bytes(restarted.mediaUri(archive, audio.getString("path"))));
        assertArrayEquals(photoBytes, bytes(restarted.mediaUri(archive, photo.getString("path"))));
        assertEquals(3, archiveMembers(bytes(archive)).size());

        byte[] committed = bytes(archive);
        assertEquals(0, restarted.retryPending());
        assertArrayEquals("A second retry must not republish or duplicate the capture", committed, bytes(archive));
        assertArrayEquals("Migrating/retrying must not rewrite the legacy index", legacyIndexBytes, readFile(new File(legacy, "index.json")));
        assertArrayEquals(photoBytes, readFile(new File(legacyMedia, "photo_fixture.jpg")));
        assertEquals(2, legacy.listFiles().length);
        assertEquals(1, legacyMedia.listFiles().length);
    }

    @Test public void unsafeOrUnindexedZipPathsAreRejectedWithoutChangingInput() throws Exception {
        String[] unsafeNames = {"../escape.jpg", "/absolute.jpg", "media\\backslash.jpg", "C:/drive.jpg", "notes.txt"};
        for (int i = 0; i < unsafeNames.length; i++) {
            Map<String, byte[]> members = new LinkedHashMap<>();
            members.put("index.json", emptyIndex("Unsafe fixture").toString().getBytes(StandardCharsets.UTF_8));
            members.put(unsafeNames[i], new byte[] {1, 2, 3});
            assertArchiveRejected(writeArchive("unsafe-" + i + ".zip", zip(members)));
        }
    }

    @Test public void pendingMarkersSurviveRepeatedBeginAndFailedCaptureRetry() throws Exception {
        Uri project = createProject("Pending audio markers");
        String entryId = UUID.randomUUID().toString();
        long at = Instant.parse("2026-11-05T16:00:00Z").toEpochMilli();
        byte[] original = "Original audio with markers retained across failed publication".getBytes(StandardCharsets.UTF_8);
        int previousPending = store.pendingCount();
        File pending = fixtureFile("audio", original);
        String captureId = ProjectStore.captureIdFor(pending);
        JSONObject metadata = fixtureMetadata();
        byte[] previousZip = bytes(project);
        store.beginCapture(project, entryId, "audio", pending, at, metadata);
        store.addPendingAudioMarker(pending, "valve-open", 500L, "Valve opened", at + 500L);
        store.addPendingAudioMarker(pending, "pressure-peak", 1_900L, "Pressure peak", at + 1_900L);
        store.addPendingAudioMarker(pending, "valve-open", 500L, "Valve opened", at + 500L);
        JSONArray markers = store.pendingAudioMarkers(pending);
        assertEquals("A retried marker tap with a stable ID must not duplicate it", 2, markers.length());
        assertEquals(captureId, markers.getJSONObject(0).getString("mediaId"));
        assertEquals(500L, markers.getJSONObject(0).getLong("offsetMs"));
        assertEquals("Pressure peak", markers.getJSONObject(1).getString("label"));
        try {
            store.addPendingAudioMarker(pending, "valve-open", 750L, "Conflicting retry", at + 750L);
            fail("A stable marker ID must not silently replace an earlier observation");
        } catch (IOException expected) {
            assertEquals(markers.toString(), store.pendingAudioMarkers(pending).toString());
        }
        assertEquals(0, store.retryPending());
        assertEquals(0, store.readProject(project).getJSONArray("entries").length());

        // The recorder journals once before preparation and again at actual start.
        store.beginCapture(project, entryId, "audio", pending, at, metadata);
        assertEquals(markers.toString(), store.pendingAudioMarkers(pending).toString());
        File journal = new File(pending.getPath() + ".json");
        byte[] beforeWrongIdentity = readFile(journal);
        try {
            store.beginCapture(project, "another-entry", "audio", pending, at, metadata);
            fail("An existing capture journal must not be reassigned to another entry");
        } catch (IOException expected) {
            assertArrayEquals(beforeWrongIdentity, readFile(journal));
        }

        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        try {
            store.saveCapture(project, entryId, "audio", pending, at, at + 4_000L, metadata, "complete");
            fail("Injected capture publication failure must be reported");
        } catch (IOException expected) {
            assertArrayEquals(previousZip, bytes(project));
            assertArrayEquals(original, readFile(pending));
        }
        assertEquals(previousPending + 1, store.pendingCount());
        assertEquals(markers.toString(), store.pendingAudioMarkers(pending).toString());
        byte[] finalizedJournal = readFile(journal);
        assertTrue(new JSONObject(new String(finalizedJournal, StandardCharsets.UTF_8)).getBoolean("finalized"));
        try {
            store.beginCapture(project, entryId, "audio", pending, at, metadata);
            fail("Repeated begin must not downgrade a finalized retryable capture");
        } catch (IOException expected) {
            assertArrayEquals(finalizedJournal, readFile(journal));
        }

        ProjectStore restarted = new ProjectStore(context);
        assertEquals(1, restarted.retryPending());
        JSONObject entry = entryById(restarted.readProject(project), entryId);
        assertEquals(1, entry.getJSONArray("media").length());
        JSONObject audio = entry.getJSONArray("media").getJSONObject(0);
        assertEquals(captureId, audio.getString("id"));
        assertEquals(markers.toString(), audio.getJSONArray("markers").toString());
        assertEquals(Instant.ofEpochMilli(at).toString(), audio.getString("startedAt"));
        assertEquals(Instant.ofEpochMilli(at + 4_000L).toString(), audio.getString("endedAt"));
        assertArrayEquals(original, bytes(restarted.mediaUri(project, audio.getString("path"))));
        assertFalse(pending.exists());
        assertFalse(journal.exists());
        assertEquals(previousPending, restarted.pendingCount());
        byte[] committed = bytes(project);
        assertEquals(0, restarted.retryPending());
        assertArrayEquals(committed, bytes(project));
        assertSingleExternalZip();
    }

    @Test public void markersStayWithTheirExactAudioThroughEditsAndCopiedZip() throws Exception {
        Uri project = createProject("Multiple marked recordings");
        String entryId = UUID.randomUUID().toString();
        long at = Instant.parse("2026-11-05T16:10:00Z").toEpochMilli();
        byte[] firstBytes = "First original audio".getBytes(StandardCharsets.UTF_8);
        byte[] secondBytes = "Second distinct original audio".getBytes(StandardCharsets.UTF_8);
        byte[] photoBytes = "An attached original photo".getBytes(StandardCharsets.UTF_8);
        JSONObject first = saveMarkedAudioFixture(project, entryId, firstBytes, at, "first-marker", 800L, "First valve");
        JSONObject second = saveMarkedAudioFixture(project, entryId, secondBytes, at + 10_000L, "second-marker", 800L, "Second valve");
        JSONObject photo = saveFixture(project, entryId, "photo", photoBytes, at + 15_000L, at + 15_000L);
        store.addAudioMarker(project, entryId, second.getString("id"), "later-marker", 2_500L, "Initial label", at + 12_500L);
        store.updateAudioMarker(project, entryId, second.getString("id"), "later-marker", "Second recording peak");
        assertTrue(store.deleteAudioMarker(project, entryId, second.getString("id"), "second-marker"));
        JSONObject beforeEdit = entryById(store.readProject(project), entryId);
        JSONArray originalMedia = beforeEdit.getJSONArray("media");
        assertEquals(first.getJSONArray("markers").toString(), mediaById(beforeEdit, first.getString("id")).getJSONArray("markers").toString());
        JSONArray secondMarkers = mediaById(beforeEdit, second.getString("id")).getJSONArray("markers");
        assertEquals(1, secondMarkers.length());
        assertEquals(second.getString("id"), secondMarkers.getJSONObject(0).getString("mediaId"));
        assertEquals("later-marker", secondMarkers.getJSONObject(0).getString("id"));
        assertEquals(2_500L, secondMarkers.getJSONObject(0).getLong("offsetMs"));
        assertEquals("Second recording peak", secondMarkers.getJSONObject(0).getString("label"));
        assertEquals(Instant.ofEpochMilli(at + 12_500L).toString(), secondMarkers.getJSONObject(0).getString("createdAt"));
        assertOriginalMediaIdentity(first, mediaById(beforeEdit, first.getString("id")));
        assertOriginalMediaIdentity(second, mediaById(beforeEdit, second.getString("id")));

        JSONArray tags = ProjectStore.normalizeTags(" #Pressure , calibration, PRESSURE,  room   temp, Cafe\u0301 ");
        assertEquals(new JSONArray().put("pressure").put("calibration").put("room temp").put("caf\u00e9").toString(), tags.toString());
        store.updateEntry(project, entryId, "Two valve observations", "Both recordings belong to the same experiment.", fixtureMetadata(), tags);
        JSONObject persisted = store.readProject(project);
        JSONObject edited = entryById(persisted, entryId);
        assertEquals("Two valve observations", edited.getString("title"));
        assertEquals("Both recordings belong to the same experiment.", edited.getString("text"));
        assertEquals(tags.toString(), edited.getJSONArray("tags").toString());
        assertEquals(originalMedia.toString(), edited.getJSONArray("media").toString());
        assertEquals(beforeEdit.getString("createdAt"), edited.getString("createdAt"));
        assertEquals(4, archiveMembers(bytes(project)).size());
        assertEquals(project, store.currentProject());
        assertSingleExternalZip();

        Uri copied = writeArchive("Portable marked project.zip", bytes(project));
        File source = new File(fixtureRoot, "Multiple marked recordings.zip");
        assertTrue(source.delete());
        assertTrue(bookmarks.edit().clear().commit());
        ProjectStore fresh = new ProjectStore(context);
        assertTrue(fresh.projects().isEmpty());
        assertNull(fresh.currentProject());
        JSONObject reopened = fresh.readProject(copied);
        assertEquals(persisted.toString(), reopened.toString());
        assertArrayEquals(firstBytes, bytes(fresh.mediaUri(copied, first.getString("path"))));
        assertArrayEquals(secondBytes, bytes(fresh.mediaUri(copied, second.getString("path"))));
        assertArrayEquals(photoBytes, bytes(fresh.mediaUri(copied, photo.getString("path"))));
        assertSingleExternalZip();
    }

    @Test public void atomicTitleTextTagsRetryPreservesAudioMarkersAndOriginals() throws Exception {
        Uri project = createProject("Atomic tagged edits");
        String entryId = UUID.randomUUID().toString();
        long at = Instant.parse("2026-11-05T16:20:00Z").toEpochMilli();
        byte[] original = "Audio original independent of title text and tags".getBytes(StandardCharsets.UTF_8);
        JSONObject audio = saveMarkedAudioFixture(project, entryId, original, at, "critical-point", 1_100L, "Valve transition");
        store.updateEntry(project, entryId, "Before", "Initial text", fixtureMetadata(), ProjectStore.normalizeTags("baseline"));
        JSONObject before = entryById(store.readProject(project), entryId);
        byte[] previousZip = bytes(project);
        int previousPending = store.pendingCount();
        JSONArray afterTags = ProjectStore.normalizeTags("analysis, reviewed");
        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        try {
            store.updateEntry(project, entryId, "After", "Revised text", fixtureMetadata(), afterTags);
            fail("An interrupted combined edit must not report success");
        } catch (IOException expected) {
            assertArrayEquals("No subset of the combined edit may be published", previousZip, bytes(project));
        }
        assertEquals(before.toString(), entryById(archivedIndex(bytes(project)), entryId).toString());
        assertEquals(previousPending + 1, store.pendingCount());
        ProjectStore restarted = new ProjectStore(context);
        assertEquals(1, restarted.retryPending());
        JSONObject after = entryById(restarted.readProject(project), entryId);
        assertEquals("After", after.getString("title"));
        assertEquals("Revised text", after.getString("text"));
        assertEquals(afterTags.toString(), after.getJSONArray("tags").toString());
        assertEquals(before.getString("createdAt"), after.getString("createdAt"));
        assertEquals(before.getString("textCreatedAt"), after.getString("textCreatedAt"));
        assertEquals(before.getJSONObject("textMetadata").toString(), after.getJSONObject("textMetadata").toString());
        assertEquals(before.getJSONArray("media").toString(), after.getJSONArray("media").toString());
        assertArrayEquals(original, bytes(restarted.mediaUri(project, audio.getString("path"))));
        assertEquals(previousPending, restarted.pendingCount());
        byte[] committed = bytes(project);
        assertEquals(0, restarted.retryPending());
        assertArrayEquals(committed, bytes(project));
        assertSingleExternalZip();
    }

    @Test public void invalidImportedAudioMarkersAreRejectedWithoutChangingArchive() throws Exception {
        byte[] original = "Audio fixture for imported marker validation".getBytes(StandardCharsets.UTF_8);
        JSONObject validIndex = indexedFixture("Imported markers", "audio_fixture.m4a", original);
        JSONObject validAudio = validIndex.getJSONArray("entries").getJSONObject(0).getJSONArray("media").getJSONObject(0);
        validAudio.put("kind", "audio");
        validAudio.put("endedAt", "2026-11-05T15:10:05Z");
        validAudio.put("durationMs", 5_000L);
        JSONObject validMarker = new JSONObject().put("id", "fixture-marker")
                .put("mediaId", validAudio.getString("id")).put("offsetMs", 1_250L)
                .put("label", "Valve opened").put("createdAt", "2026-11-05T15:10:01.250Z");
        validAudio.put("markers", new JSONArray().put(validMarker));
        Uri validArchive = writeIndexArchive("valid-markers.zip", validIndex, original);
        assertEquals(validIndex.getJSONArray("entries").toString(), store.readProject(validArchive).getJSONArray("entries").toString());

        // Encoded AAC duration may include padding beyond the wall-clock interval.
        JSONObject paddedIndex = new JSONObject(validIndex.toString());
        JSONObject paddedAudio = paddedIndex.getJSONArray("entries").getJSONObject(0).getJSONArray("media").getJSONObject(0);
        paddedAudio.getJSONObject("metadata").put("encodedDurationMs", 6_000L);
        paddedAudio.getJSONArray("markers").getJSONObject(0).put("offsetMs", 5_500L);
        Uri paddedArchive = writeIndexArchive("valid-padded-markers.zip", paddedIndex, original);
        assertEquals(paddedIndex.getJSONArray("entries").toString(), store.readProject(paddedArchive).getJSONArray("entries").toString());

        String[] invalidCases = {"negative", "fractional", "string", "past-end", "wrong-media", "missing-id", "bad-time", "duplicate", "non-audio"};
        for (String invalidCase : invalidCases) {
            JSONObject invalidIndex = new JSONObject(validIndex.toString());
            JSONObject audio = invalidIndex.getJSONArray("entries").getJSONObject(0).getJSONArray("media").getJSONObject(0);
            JSONObject marker = audio.getJSONArray("markers").getJSONObject(0);
            switch (invalidCase) {
                case "negative": marker.put("offsetMs", -1); break;
                case "fractional": marker.put("offsetMs", 1.5); break;
                case "string": marker.put("offsetMs", "1250"); break;
                case "past-end": marker.put("offsetMs", 5_001L); break;
                case "wrong-media": marker.put("mediaId", "another-audio-id"); break;
                case "missing-id": marker.remove("id"); break;
                case "bad-time": marker.put("createdAt", "not-a-timestamp"); break;
                case "duplicate": audio.getJSONArray("markers").put(new JSONObject(marker.toString())); break;
                case "non-audio": audio.put("kind", "video"); break;
                default: throw new AssertionError("Missing invalid marker fixture");
            }
            assertArchiveRejected(writeIndexArchive("invalid-marker-" + invalidCase + ".zip", invalidIndex, original));
        }
    }

    @Test public void invalidTagsLeaveZipUntouchedAndEmptyTagsClearOnlyTags() throws Exception {
        Uri project = createProject("Validated tags");
        JSONArray tags = ProjectStore.normalizeTags(" #Baseline, baseline,  room   temp ");
        JSONObject text = store.saveText(project, null, "Standalone note with tags", fixtureMetadata(), tags);
        String entryId = text.getString("id");
        assertEquals(new JSONArray().put("baseline").put("room temp").toString(), text.getJSONArray("tags").toString());
        store.updateEntry(project, entryId, "Preserve tags on title change", null);
        JSONObject before = entryById(store.readProject(project), entryId);
        assertEquals(tags.toString(), before.getJSONArray("tags").toString());
        byte[] previousZip = bytes(project);
        int previousPending = store.pendingCount();
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 25; i++) tooMany.put("tag-" + i);
        JSONArray[] invalidTags = {
                new JSONArray().put(true),
                new JSONArray().put(new JSONObject().put("name", "not-a-string")),
                new JSONArray().put(new String(new char[41]).replace('\0', 'x')),
                tooMany
        };
        for (JSONArray invalid : invalidTags) {
            try {
                store.updateEntry(project, entryId, "Must not commit", "Must not replace text", fixtureMetadata(), invalid);
                fail("Invalid tags must reject the entire edit");
            } catch (IOException expected) {
                assertArrayEquals(previousZip, bytes(project));
                assertEquals(previousPending, store.pendingCount());
            }
        }
        store.setEntryTags(project, entryId, new JSONArray());
        JSONObject cleared = entryById(store.readProject(project), entryId);
        assertFalse(cleared.has("tags"));
        assertEquals(before.getString("title"), cleared.getString("title"));
        assertEquals(before.getString("text"), cleared.getString("text"));
        assertEquals(before.getString("createdAt"), cleared.getString("createdAt"));
        assertEquals(before.getString("textCreatedAt"), cleared.getString("textCreatedAt"));
        assertEquals(before.getString("textUpdatedAt"), cleared.getString("textUpdatedAt"));
        assertEquals(before.getJSONObject("textMetadata").toString(), cleared.getJSONObject("textMetadata").toString());
        assertEquals(before.getJSONArray("media").toString(), cleared.getJSONArray("media").toString());
        assertSingleExternalZip();
    }

    @Test public void duplicateZipMembersAreRejected() throws Exception {
        byte[] original = new byte[] {1, 2, 3};
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("index.json", indexedFixture("Duplicate fixture", "one.jpg", original).toString().getBytes(StandardCharsets.UTF_8));
        members.put("media/one.jpg", original);
        members.put("media/two.jpg", original);
        byte[] duplicated = replaceAscii(zip(members), "media/two.jpg", "media/one.jpg");
        assertArchiveRejected(writeArchive("duplicates.zip", duplicated));
    }

    @Test public void missingOrCorruptReferencedOriginalIsRejected() throws Exception {
        byte[] original = "Expected original bytes".getBytes(StandardCharsets.UTF_8);
        JSONObject index = indexedFixture("Integrity fixture", "photo_fixture.jpg", original);
        Map<String, byte[]> missing = new LinkedHashMap<>();
        missing.put("index.json", index.toString().getBytes(StandardCharsets.UTF_8));
        assertArchiveRejected(writeArchive("missing.zip", zip(missing)));

        Map<String, byte[]> corrupt = new LinkedHashMap<>(missing);
        byte[] changed = original.clone();
        changed[0] ^= 1;
        corrupt.put("media/photo_fixture.jpg", changed);
        assertArchiveRejected(writeArchive("checksum-mismatch.zip", zip(corrupt)));
    }

    @Test public void unfinalizedCaptureIsRetainedButNeverAdvertisedAsSaved() throws Exception {
        Uri project = createProject("Interrupted project");
        int previousPending = store.pendingCount();
        File pending = fixtureFile("audio", "unfinished recorder bytes".getBytes(StandardCharsets.UTF_8));
        store.beginCapture(project, UUID.randomUUID().toString(), "audio", pending,
                Instant.parse("2026-11-05T15:30:00Z").toEpochMilli(), fixtureMetadata());
        assertEquals(0, store.retryPending());
        assertEquals(previousPending + 1, store.pendingCount());
        assertTrue(pending.exists());
        assertEquals(0, store.readProject(project).getJSONArray("entries").length());
    }

    @Test public void disabledMetadataDoesNotInventCoordinatesOrSensorReadings() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        boolean hadPreference = preferences.contains("metadata_enabled");
        boolean previous = preferences.getBoolean("metadata_enabled", true);
        try {
            assertTrue(preferences.edit().putBoolean("metadata_enabled", false).commit());
            JSONObject snapshot = MetadataCollector.snapshot(context);
            assertNotNull(Instant.parse(snapshot.getString("observedAt")));
            assertTrue(snapshot.getJSONObject("timezone").has("name"));
            JSONObject location = snapshot.getJSONObject("location");
            assertEquals("disabled", location.getString("status"));
            assertFalse(location.has("latitudeDegrees"));
            assertFalse(location.has("longitudeDegrees"));
            assertEquals("disabled", snapshot.getJSONObject("sensors").getString("status"));
            assertFalse(snapshot.getJSONObject("sensors").has("observations"));
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadPreference) editor.putBoolean("metadata_enabled", previous);
            else editor.remove("metadata_enabled");
            assertTrue(editor.commit());
        }
    }

    @Test public void availableMetadataIsEnabledByDefaultAndAlwaysTimestamped() throws Exception {
        long before = System.currentTimeMillis();
        JSONObject snapshot = defaultMetadataSnapshot();
        long after = System.currentTimeMillis();
        long observed = Instant.parse(snapshot.getString("observedAt")).toEpochMilli();
        assertTrue(observed >= before && observed <= after);
        assertFalse(snapshot.getJSONObject("timezone").getString("name").isEmpty());
        assertTrue(snapshot.getJSONObject("timezone").has("utcOffsetMinutes"));
        assertEquals("available", snapshot.getJSONObject("device").getString("status"));
        assertTrue(snapshot.getJSONObject("location").has("status"));
        assertTrue(snapshot.getJSONObject("sensors").has("status"));
    }

    @Test public void publicationScheduleIdentitySurvivesParcelCopyAndSeparatesReplacementJobs() {
        PersistableBundle startedExtras = new PersistableBundle();
        startedExtras.putString(ProjectPublisher.EXTRA_SCHEDULE_ID, UUID.randomUUID().toString());
        PersistableBundle stoppedExtras;
        Parcel parcel = Parcel.obtain();
        try {
            startedExtras.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            stoppedExtras = PersistableBundle.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
        assertNotSame("Binder callbacks reconstruct extras instead of preserving object identity", startedExtras, stoppedExtras);
        String startedIdentity = ProjectPublisher.scheduleIdentity(ProjectPublisher.JOB_ID, startedExtras);
        String stoppedIdentity = ProjectPublisher.scheduleIdentity(ProjectPublisher.JOB_ID, stoppedExtras);
        assertEquals(startedIdentity, stoppedIdentity);

        PersistableBundle replacementExtras = new PersistableBundle();
        replacementExtras.putString(ProjectPublisher.EXTRA_SCHEDULE_ID, UUID.randomUUID().toString());
        String replacementIdentity = ProjectPublisher.scheduleIdentity(ProjectPublisher.JOB_ID, replacementExtras);
        assertNotEquals("Reusing Android's job ID must not reuse the replaced schedule's identity", startedIdentity, replacementIdentity);
        assertNotEquals(startedIdentity, ProjectPublisher.scheduleIdentity(ProjectPublisher.JOB_ID + 1, stoppedExtras));

        Object startedOwner = new Object();
        Object replacementOwner = new Object();
        Map<String, Object> owners = new LinkedHashMap<>();
        owners.put(startedIdentity, startedOwner);
        owners.put(replacementIdentity, replacementOwner);
        assertSame(startedOwner, owners.get(stoppedIdentity));
        assertSame("A reconstructed stop callback must remove its original owner", startedOwner, owners.remove(stoppedIdentity));
        assertSame("A late old stop must leave the replacement owner intact", replacementOwner, owners.get(replacementIdentity));
        assertEquals(1, owners.size());

        String legacyIdentity = ProjectPublisher.scheduleIdentity(ProjectPublisher.JOB_ID, null);
        assertEquals(legacyIdentity, ProjectPublisher.scheduleIdentity(ProjectPublisher.JOB_ID, new PersistableBundle()));
        assertNotEquals(legacyIdentity, startedIdentity);
        assertNotEquals(legacyIdentity, replacementIdentity);
    }

    private JSONObject defaultMetadataSnapshot() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        boolean hadPreference = preferences.contains("metadata_enabled");
        boolean previous = preferences.getBoolean("metadata_enabled", true);
        try {
            assertTrue(preferences.edit().remove("metadata_enabled").commit());
            JSONObject snapshot = MetadataCollector.snapshot(context);
            assertEquals("enabled", snapshot.getJSONObject("optionalMetadata").getString("status"));
            return snapshot;
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadPreference) editor.putBoolean("metadata_enabled", previous);
            else editor.remove("metadata_enabled");
            assertTrue(editor.commit());
        }
    }

    private JSONObject entryById(JSONObject index, String entryId) throws Exception {
        JSONArray entries = index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entryId.equals(entry.getString("id"))) return entry;
        }
        throw new AssertionError("Missing entry " + entryId);
    }

    private JSONObject mediaById(JSONObject entry, String mediaId) throws Exception {
        JSONArray media = entry.getJSONArray("media");
        for (int i = 0; i < media.length(); i++) {
            JSONObject item = media.getJSONObject(i);
            if (mediaId.equals(item.getString("id"))) return item;
        }
        throw new AssertionError("Missing media " + mediaId);
    }

    private void assertOriginalMediaIdentity(JSONObject original, JSONObject current) throws Exception {
        for (String key : new String[] {"id", "kind", "filename", "path", "startedAt", "endedAt", "attachedAt", "sha256", "status"})
            assertEquals("Original media field changed: " + key, original.getString(key), current.getString(key));
        for (String key : new String[] {"durationMs", "bytes"})
            assertEquals("Original media field changed: " + key, original.getLong(key), current.getLong(key));
        assertEquals(original.getJSONObject("metadata").toString(), current.getJSONObject("metadata").toString());
    }

    private JSONObject saveMarkedAudioFixture(Uri project, String entryId, byte[] original,
                                             long at, String markerId, long offset, String label) throws Exception {
        File file = fixtureFile("audio", original);
        JSONObject metadata = fixtureMetadata();
        store.beginCapture(project, entryId, "audio", file, at, metadata);
        store.addPendingAudioMarker(file, markerId, offset, label, at + offset);
        return store.saveCapture(project, entryId, "audio", file, at, at + 5_000L, metadata, "complete");
    }

    private JSONObject saveFixture(Uri project, String entryId, String kind, byte[] value,
                                   long start, long end) throws Exception {
        File file = fixtureFile(kind, value);
        JSONObject metadata = fixtureMetadata();
        store.beginCapture(project, entryId, kind, file, start, metadata);
        return store.saveCapture(project, entryId, kind, file, start, end, metadata, "complete");
    }

    private File prepareFailedAudioPublication(Uri project, long at, byte[] original) throws Exception {
        File pending = fixtureFile("audio", original);
        String entryId = UUID.randomUUID().toString();
        JSONObject metadata = fixtureMetadata();
        store.beginCapture(project, entryId, "audio", pending, at, metadata);
        TestDocumentsProvider.failNextZipWrite();
        try {
            store.saveCapture(project, entryId, "audio", pending, at, at + 4_000L, metadata, "complete");
            fail("The fixture must leave a durable pending publication");
        } catch (IOException expected) {
            assertArrayEquals(original, readFile(pending));
        }
        return pending;
    }

    private Uri createProject(String name) throws IOException {
        Uri archive = store.createProject(fixtureTree, name);
        ownedArchives.add(archive);
        return archive;
    }

    private File fixtureFile(String kind, byte[] value) throws Exception {
        File file = store.newPendingFile(kind);
        pendingFiles.add(file);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(value); }
        return file;
    }

    private JSONObject fixtureMetadata() throws Exception {
        return new JSONObject().put("source", "instrumentation-byte-fixture-not-encoded-media")
                .put("location", new JSONObject().put("status", "unavailable"));
    }

    private byte[] bytes(Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Provider did not return media bytes");
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

    private void assertSingleExternalZip() {
        File[] external = fixtureRoot.listFiles();
        assertNotNull(external);
        assertEquals("A project must publish one archive, without external loose files or project directories", 1, external.length);
        assertTrue(external[0].isFile());
        assertTrue(external[0].getName().endsWith(".zip"));
    }

    private void assertWorkingFolderHasIndexAndMedia(Uri working) {
        String prefix = "root:" + fixtureRoot.getName() + "/";
        String documentId = DocumentsContract.getDocumentId(working);
        assertTrue(documentId.startsWith(prefix));
        File directory = new File(fixtureRoot, documentId.substring(prefix.length()));
        assertTrue(new File(directory, "index.json").isFile());
        assertTrue(new File(directory, "media").isDirectory());
    }

    private int exportedZipCount() {
        File[] archives = fixtureRoot.listFiles(file -> file.isFile() && file.getName().endsWith(".zip"));
        assertNotNull(archives);
        return archives.length;
    }

    private byte[] encodedPhoto(int color) {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded));
            return encoded.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private JSONObject archivedIndex(byte[] archive) throws Exception {
        byte[] value = archiveMembers(archive).get("index.json");
        assertNotNull("ZIP must contain its authoritative metadata index", value);
        return new JSONObject(new String(value, StandardCharsets.UTF_8));
    }

    private Map<String, byte[]> archiveMembers(byte[] archive) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                assertNull("Valid published archives must not contain duplicate members", members.put(entry.getName(), output.toByteArray()));
                zip.closeEntry();
            }
        }
        return members;
    }

    private JSONObject emptyIndex(String name) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("format", "fieldnote-project")
                .put("id", UUID.randomUUID().toString()).put("name", name)
                .put("createdAt", "2026-11-05T15:00:00Z").put("revision", 0)
                .put("entries", new JSONArray());
    }

    private Uri writeEmptyProjectArchive(String name, String createdAt) throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("index.json", emptyIndex(name).put("createdAt", createdAt).toString().getBytes(StandardCharsets.UTF_8));
        return writeArchive(name, zip(members));
    }

    private Uri writeIndexArchive(String name, JSONObject index, byte[] singleOriginal) throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("index.json", index.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject media = index.getJSONArray("entries").getJSONObject(0).getJSONArray("media").getJSONObject(0);
        members.put(media.getString("path"), singleOriginal);
        return writeArchive(name, zip(members));
    }

    private JSONObject indexedFixture(String name, String filename, byte[] original) throws Exception {
        JSONObject media = new JSONObject().put("id", "fixture-photo")
                .put("kind", "photo").put("filename", filename).put("path", "media/" + filename)
                .put("startedAt", "2026-11-05T15:10:00Z").put("endedAt", "2026-11-05T15:10:00Z")
                .put("durationMs", 0).put("attachedAt", "2026-11-05T15:10:01Z")
                .put("bytes", original.length).put("sha256", sha256(original))
                .put("status", "complete").put("metadata", fixtureMetadata());
        JSONObject entry = new JSONObject().put("id", "fixture-entry")
                .put("createdAt", "2026-11-05T15:10:00Z").put("media", new JSONArray().put(media));
        return emptyIndex(name).put("entries", new JSONArray().put(entry));
    }

    private String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private byte[] zip(Map<String, byte[]> members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                output.putNextEntry(new ZipEntry(member.getKey()));
                output.write(member.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Uri writeArchive(String name, byte[] archive) throws IOException {
        File file = new File(fixtureRoot, name);
        writeFile(file, archive);
        Uri archiveUri = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName() + "/" + name);
        ownedArchives.add(archiveUri);
        return archiveUri;
    }

    private void writeFile(File file, byte[] value) throws IOException {
        assertInsideFixture(file);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(value); }
    }

    private void assertArchiveRejected(Uri archive) throws Exception {
        byte[] before = bytes(archive);
        try {
            store.readProject(archive);
            fail("Unsafe or incomplete ZIP must not be accepted as a project");
        } catch (IOException expected) {
            assertArrayEquals("Rejected import must not rewrite its source", before, bytes(archive));
        }
    }

    private byte[] replaceAscii(byte[] archive, String oldName, String newName) {
        byte[] oldBytes = oldName.getBytes(StandardCharsets.US_ASCII);
        byte[] newBytes = newName.getBytes(StandardCharsets.US_ASCII);
        assertEquals(oldBytes.length, newBytes.length);
        byte[] result = archive.clone();
        int changed = 0;
        for (int i = 0; i <= result.length - oldBytes.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(result, i, i + oldBytes.length), oldBytes)) {
                System.arraycopy(newBytes, 0, result, i, newBytes.length);
                changed++;
                i += oldBytes.length - 1;
            }
        }
        assertEquals("Replace the local header and central-directory name only", 2, changed);
        return result;
    }

    private void deleteFixtureTree(File target) throws IOException {
        assertInsideFixture(target);
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Cannot list fixture for cleanup");
            for (File child : children) deleteFixtureTree(child);
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not remove owned fixture");
    }

    private void assertInsideFixture(File target) throws IOException {
        String base = fixtureRoot.getCanonicalPath();
        String path = target.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) throw new IOException("Fixture path escaped test root");
    }

    private void deleteOwnPending(File file) throws IOException {
        File directory = new File(context.getFilesDir(), "pending-captures").getCanonicalFile();
        if (!directory.equals(file.getCanonicalFile().getParentFile())) throw new IOException("Pending fixture path escaped staging");
        if (file.exists() && !file.delete()) throw new IOException("Could not remove owned pending fixture");
    }

    private void deleteOwnPublication(Uri archive) throws Exception {
        // Only remove this test URI's recovery directory, never another project's.
        File parent = new File(context.getFilesDir(), "archive-publications").getCanonicalFile();
        File owned = new File(parent, sha256(archive.toString().getBytes(StandardCharsets.UTF_8))).getCanonicalFile();
        if (!parent.equals(owned.getParentFile())) throw new IOException("Publication fixture escaped its root");
        deletePublicationTree(owned, owned);
    }

    private void deletePublicationTree(File target, File ownedRoot) throws IOException {
        String allowed = ownedRoot.getCanonicalPath();
        String actual = target.getCanonicalPath();
        if (!actual.equals(allowed) && !actual.startsWith(allowed + File.separator))
            throw new IOException("Publication fixture escaped its owned directory");
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Could not list publication fixture");
            for (File child : children) deletePublicationTree(child, ownedRoot);
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not remove publication fixture");
    }
}
