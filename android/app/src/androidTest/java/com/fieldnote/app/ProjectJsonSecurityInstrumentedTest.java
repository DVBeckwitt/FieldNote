package com.fieldnote.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Untrusted JSON is supplied as raw text, without parsing it in the fixture builder. */
@RunWith(AndroidJUnit4.class)
public final class ProjectJsonSecurityInstrumentedTest {
    private Context context;
    private ProjectStore store;
    private File fixtureRoot;
    private final List<Uri> archiveUris = new ArrayList<>();
    private final List<File> archiveFiles = new ArrayList<>();
    private final Set<String> initialWorkNames = new HashSet<>();

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new ProjectStore(context);
        TestDocumentsProvider.clearFailures();
        File base = TestDocumentsProvider.rootDirectory(context);
        assertTrue(base.isDirectory() || base.mkdirs());
        fixtureRoot = new File(base, "json-security-" + UUID.randomUUID());
        assertTrue(fixtureRoot.mkdir());
        File[] workFiles = new File(context.getCacheDir(), "archive-work").listFiles();
        if (workFiles != null) for (File file : workFiles) initialWorkNames.add(file.getName());
    }

    @After public void tearDown() throws Exception {
        TestDocumentsProvider.clearFailures();
        removeOwnInterruptedWorkCopies();
        for (Uri archive : archiveUris) {
            StringBuilder key = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(archive.toString().getBytes(StandardCharsets.UTF_8)))
                key.append(String.format(Locale.US, "%02x", value & 0xff));
            File cacheRoot = new File(context.getCacheDir(), "project-index").getCanonicalFile();
            File cached = new File(cacheRoot, key + ".json").getCanonicalFile();
            assertEquals(cacheRoot, cached.getParentFile());
            new android.util.AtomicFile(cached).delete();
            File recoveryRoot = new File(context.getFilesDir(), "archive-publications").getCanonicalFile();
            File ownedRecovery = new File(recoveryRoot, key.toString()).getCanonicalFile();
            assertEquals(recoveryRoot, ownedRecovery.getParentFile());
            deleteTree(ownedRecovery, ownedRecovery);
        }
        if (fixtureRoot != null) deleteOwned(fixtureRoot);
    }

    @Test public void deeplyNestedZipIndexIsRejectedWithoutChangingSource() throws Exception {
        byte[] index = indexWithMetadata(deepMetadata(20_000));
        assertTrue("The attack is far below the existing byte limit", index.length < 100_000);
        File archive = writeZip("deep.zip", index);
        assertRejected(documentUri(archive), archive);
    }

    @Test public void deeplyNestedLegacyIndexIsRejectedWithoutChangingSource() throws Exception {
        byte[] index = indexWithMetadata(deepMetadata(20_000));
        assertTrue(index.length < 100_000);
        File legacy = writeLegacy("deep-legacy", index);
        assertRejected(treeUri(legacy), new File(legacy, "index.json"));
    }

    @Test public void overComplexLegacyCurrentIndexCannotFallBackToAnOlderBackup() throws Exception {
        byte[] current = new String(indexWithMetadata(deepMetadata(61)), StandardCharsets.UTF_8)
                .replace("\"revision\":0", "\"revision\":7").getBytes(StandardCharsets.UTF_8);
        byte[] backup = indexWithMetadata("{}");
        File legacy = writeLegacy("legacy-with-backup", current);
        File backupFile = new File(legacy, "index.backup.json");
        try (FileOutputStream output = new FileOutputStream(backupFile)) { output.write(backup); }
        Uri project = treeUri(legacy);
        try {
            store.readProject(project);
            fail("An unsupported current index must not silently select its older backup");
        } catch (ProjectJson.LimitExceededException expected) {
            assertNotNull(expected.getMessage());
        }
        Uri destination = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName());
        try {
            store.importLegacyProject(project, destination);
            fail("Migration must not create a ZIP containing only the older backup's data");
        } catch (ProjectJson.LimitExceededException expected) {
            assertNotNull(expected.getMessage());
        }
        assertArrayEquals(current, Files.readAllBytes(new File(legacy, "index.json").toPath()));
        assertArrayEquals(backup, Files.readAllBytes(backupFile.toPath()));
        File[] destinationChildren = fixtureRoot.listFiles();
        assertNotNull(destinationChildren);
        assertEquals("Rejected migration creates no destination archive", 1, destinationChildren.length);
        assertEquals(legacy, destinationChildren[0]);
    }

    @Test public void nestedEscapedMetadataRemainsReadableAndSourcesAreUnchanged() throws Exception {
        String metadata = "{\"outer\":{\"samples\":[1,true,null,{\"label\":\"quoted \\\"value\\\", slash \\\\, brackets [ ] { }\"}]},\"unicode\":\"\\u03b1\"}";
        byte[] index = indexWithMetadata(metadata);
        File archive = writeZip("normal.zip", index);
        File legacy = writeLegacy("normal-legacy", index);
        byte[] archiveBefore = Files.readAllBytes(archive.toPath());

        for (Uri project : new Uri[]{documentUri(archive), treeUri(legacy)}) {
            JSONObject loaded = store.readProject(project);
            JSONObject actual = loaded.getJSONArray("entries").getJSONObject(0).getJSONObject("textMetadata");
            assertEquals(new JSONObject(metadata).toString(), actual.toString());
            assertEquals("quoted \"value\", slash \\, brackets [ ] { }",
                    actual.getJSONObject("outer").getJSONArray("samples").getJSONObject(3).getString("label"));
            assertEquals("α", actual.getString("unicode"));
        }
        assertArrayEquals(archiveBefore, Files.readAllBytes(archive.toPath()));
        assertArrayEquals(index, Files.readAllBytes(new File(legacy, "index.json").toPath()));
    }

    @Test public void depthCeilingAccepts64LevelsAndRejects65() throws Exception {
        // Root object, entries array, entry object, and metadata object use four levels.
        File accepted = writeZip("depth-64.zip", indexWithMetadata(deepMetadata(60)));
        JSONObject loaded = store.readProject(documentUri(accepted));
        JSONArray payload = loaded.getJSONArray("entries").getJSONObject(0)
                .getJSONObject("textMetadata").getJSONArray("payload");
        for (int i = 1; i < 60; i++) payload = payload.getJSONArray(0);
        assertEquals(0, payload.getInt(0));
        File rejected = writeZip("depth-65.zip", indexWithMetadata(deepMetadata(61)));
        assertRejected(documentUri(rejected), rejected);
    }

    @Test public void broadZipAndLegacyIndexesAreRejectedWithoutChangingSources() throws Exception {
        byte[] index = indexWithMetadata(broadMetadata());
        assertTrue("Small empty objects must be bounded independently of the byte limit", index.length < 1_000_000);
        File archive = writeZip("broad.zip", index);
        assertRejected(documentUri(archive), archive);
        File legacy = writeLegacy("broad-legacy", index);
        assertRejected(treeUri(legacy), new File(legacy, "index.json"));
    }

    @Test public void malformedOrTrailingJsonIsRejectedWithoutChangingSources() throws Exception {
        String[] invalidMetadata = {
                "{\"payload\":/* comment */0}",
                "{'payload':0}",
                "{\"payload\":[1,,2]}",
                "{\"payload\":0x10}",
                "{\"payload\":\"\\uZZZZ\"}",
                "{\"payload\":[0}"
        };
        for (int i = 0; i < invalidMetadata.length; i++) {
            byte[] index = indexWithMetadata(invalidMetadata[i]);
            File archive = writeZip("malformed-" + i + ".zip", index);
            assertRejected(documentUri(archive), archive);
            File legacy = writeLegacy("malformed-legacy-" + i, index);
            assertRejected(treeUri(legacy), new File(legacy, "index.json"));
        }
        byte[] trailing = (new String(indexWithMetadata("{}"), StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8);
        File archive = writeZip("trailing.zip", trailing);
        assertRejected(documentUri(archive), archive);
        File legacy = writeLegacy("trailing-legacy", trailing);
        assertRejected(treeUri(legacy), new File(legacy, "index.json"));
    }

    @Test public void archiveBuildEnforcesTheSameReopenPolicyBeforeWriting() throws Exception {
        JSONObject overLimit = new JSONObject(new String(indexWithMetadata(deepMetadata(61)), StandardCharsets.UTF_8));
        File destination = new File(fixtureRoot, "build.zip");
        byte[] untouched = "An existing destination must not be opened before validation".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(destination)) { output.write(untouched); }
        try {
            ProjectArchive.build(destination, overLimit, path -> { throw new IOException("This fixture has no original files"); });
            fail("The writer must refuse an index that its reader cannot reopen");
        } catch (ProjectJson.LimitExceededException expected) {
            assertArrayEquals(untouched, Files.readAllBytes(destination.toPath()));
        }
        JSONObject allowed = new JSONObject(new String(indexWithMetadata(deepMetadata(60)), StandardCharsets.UTF_8));
        ProjectArchive.build(destination, allowed, path -> { throw new IOException("This fixture has no original files"); });
        assertEquals("json-security-project", store.readProject(documentUri(destination)).getString("id"));
    }

    @Test public void pendingRecoveryNeverOverwritesAnExternallyChangedOverComplexZip() throws Exception {
        File archive = writeZip("recovery-boundary.zip", indexWithMetadata("{}"));
        Uri project = documentUri(archive);
        TestDocumentsProvider.failNextZipWriteAfterTruncate();
        try {
            store.updateEntry(project, "json-security-entry", null, "A pending text edit");
            fail("The provider must leave a retained pending publication");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        // This is a different, well-formed external document, not our interrupted write.
        writeZip(archive.getName(), indexWithMetadata(deepMetadata(61)));
        byte[] external = Files.readAllBytes(archive.toPath());
        assertRejected(project, archive);
        try {
            store.retryPending();
            fail("Unsupported external complexity must not authorize recovery over it");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        assertArrayEquals(external, Files.readAllBytes(archive.toPath()));
    }

    private byte[] indexWithMetadata(String metadata) {
        return ("{\"schemaVersion\":1,\"id\":\"json-security-project\",\"name\":\"JSON security fixture\","
                + "\"createdAt\":\"2026-11-05T15:00:00Z\",\"revision\":0,\"entries\":[{"
                + "\"id\":\"json-security-entry\",\"createdAt\":\"2026-11-05T15:00:00Z\","
                + "\"text\":\"A preserved note\",\"textCreatedAt\":\"2026-11-05T15:00:00Z\","
                + "\"textUpdatedAt\":\"2026-11-05T15:00:00Z\",\"textMetadata\":" + metadata
                + ",\"media\":[]}]}").getBytes(StandardCharsets.UTF_8);
    }

    private String deepMetadata(int depth) {
        StringBuilder result = new StringBuilder("{\"payload\":");
        for (int i = 0; i < depth; i++) result.append('[');
        result.append('0');
        for (int i = 0; i < depth; i++) result.append(']');
        return result.append('}').toString();
    }

    private String broadMetadata() {
        StringBuilder result = new StringBuilder("{\"payload\":[");
        // Each empty object requires two structural tokens, before the index's own fields.
        for (int i = 0; i < 130_000; i++) {
            if (i > 0) result.append(',');
            result.append("{}");
        }
        return result.append("]}").toString();
    }

    private File writeZip(String name, byte[] index) throws IOException {
        File archive = new File(fixtureRoot, name);
        assertOwned(archive);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("index.json"));
            output.write(index);
            output.closeEntry();
        }
        if (!archiveFiles.contains(archive)) archiveFiles.add(archive);
        return archive;
    }

    private File writeLegacy(String name, byte[] index) throws IOException {
        File directory = new File(fixtureRoot, name);
        assertOwned(directory);
        assertTrue(directory.mkdir());
        try (FileOutputStream output = new FileOutputStream(new File(directory, "index.json"))) { output.write(index); }
        return directory;
    }

    private Uri documentUri(File archive) {
        Uri uri = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName() + "/" + archive.getName());
        if (!archiveUris.contains(uri)) archiveUris.add(uri);
        return uri;
    }

    private Uri treeUri(File directory) {
        return DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                "root:" + fixtureRoot.getName() + "/" + directory.getName());
    }

    private void assertRejected(Uri project, File source) throws Exception {
        byte[] before = Files.readAllBytes(source.toPath());
        try {
            store.readProject(project);
            fail("Unsafe or overcomplex JSON must fail with IOException before reaching the recursive JSON parser");
        } catch (IOException expected) {
            assertNotNull("Import failure must explain the rejection", expected.getMessage());
        }
        assertArrayEquals("Rejected input must never be rewritten", before, Files.readAllBytes(source.toPath()));
    }

    private void assertOwned(File target) throws IOException {
        String base = fixtureRoot.getCanonicalPath();
        String actual = target.getCanonicalPath();
        if (!actual.equals(base) && !actual.startsWith(base + File.separator)) throw new IOException("JSON fixture escaped its test root");
    }

    private void removeOwnInterruptedWorkCopies() throws Exception {
        // The pre-fix StackOverflowError bypasses production cleanup. Never remove a
        // pre-existing work file or a new file whose bytes differ from our fixture ZIPs.
        File workRoot = new File(context.getCacheDir(), "archive-work").getCanonicalFile();
        File[] candidates = workRoot.listFiles();
        if (candidates == null) return;
        for (File candidate : candidates) {
            if (initialWorkNames.contains(candidate.getName()) || !candidate.isFile()
                    || !candidate.getName().startsWith("project-") || !candidate.getName().endsWith(".zip")
                    || !workRoot.equals(candidate.getCanonicalFile().getParentFile())) continue;
            for (File original : archiveFiles) {
                if (original.isFile() && candidate.length() == original.length()
                        && Arrays.equals(digest(candidate), digest(original))) {
                    if (!candidate.delete()) throw new IOException("Cannot remove owned interrupted ZIP copy");
                    break;
                }
            }
        }
    }

    private byte[] digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private void deleteOwned(File target) throws IOException {
        deleteTree(target, fixtureRoot);
    }

    private void deleteTree(File target, File allowedRoot) throws IOException {
        String base = allowedRoot.getCanonicalPath();
        String actual = target.getCanonicalPath();
        if (!actual.equals(base) && !actual.startsWith(base + File.separator)) throw new IOException("JSON cleanup escaped its owned root");
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Cannot list JSON fixture for cleanup");
            for (File child : children) deleteTree(child, allowedRoot);
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot remove JSON fixture");
    }
}
