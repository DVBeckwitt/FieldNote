package com.fieldnote.app;

import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Uncompressed project storage. ProjectStore serializes mutations and supplies SAF access. */
final class WorkingProjectFiles {
    private static final String INDEX = "index.json";
    private static final String PENDING_INDEX = "index.pending.json";
    private static final String DIRECTORY = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String BINARY = "application/octet-stream";
    private static final long MEDIA_LIMIT = 4L * 1024L * 1024L * 1024L;
    private static final int INDEX_LIMIT = 32 * 1024 * 1024;

    private final ProjectStore store;

    WorkingProjectFiles(ProjectStore store) { this.store = store; }

    Uri create(Uri parentTree, String name, JSONObject index, ProjectArchive.MediaSource source)
            throws IOException, JSONException {
        byte[] encoded = encode(index);
        Uri parent = store.directoryUri(parentTree);
        Set<String> names = new HashSet<>();
        for (String child : store.children(parent).keySet()) names.add(folded(child));
        String candidate = name;
        for (int suffix = 2; names.contains(folded(candidate)); suffix++) {
            candidate = name + " (" + suffix + ")";
        }
        Uri project = createNamed(parent, DIRECTORY, candidate);
        Uri originals = createNamed(project, DIRECTORY, "media");
        JSONArray entries = index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONArray media = entries.getJSONObject(i).getJSONArray("media");
            for (int j = 0; j < media.length(); j++) {
                JSONObject item = media.getJSONObject(j);
                String path = item.getString("path");
                ProjectStore.Digest expected = fingerprint(item);
                try (InputStream input = source.open(path)) {
                    Uri destination = createNamed(originals, BINARY, ProjectStore.mediaFilename(path));
                    writeOriginal(destination, input, expected);
                }
            }
        }
        // The workspace becomes readable only after every original was verified.
        writeVerifiedIndex(createNamed(project, "application/json", INDEX), encoded);
        return project;
    }

    JSONObject read(Uri directory) throws IOException, JSONException {
        return readSlots(store.children(store.directoryUri(directory))).latest().index;
    }

    void verifyForClose(Uri directory, JSONObject expected) throws IOException, JSONException {
        Uri project = store.directoryUri(directory);
        Map<String, ProjectStore.Document> children = store.children(project);
        IndexSlots slots = readSlots(children);
        IndexCopy latest = slots.latest();
        if (!latest.index.toString().equals(expected.toString())) {
            throw new IOException("The project changed while finishing. Review the project and try again.");
        }
        Map<String, ProjectStore.Document> originals = store.children(mediaDirectory(directory));
        JSONArray entries = latest.index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONArray media = entries.getJSONObject(i).getJSONArray("media");
            for (int j = 0; j < media.length(); j++) {
                JSONObject item = media.getJSONObject(j);
                String filename = ProjectStore.mediaFilename(item.getString("path"));
                ProjectStore.Document original = originals.get(filename);
                ProjectStore.Digest fingerprint = fingerprint(item);
                if (original == null || original.isDirectory()
                        || !fingerprint.equals(ProjectStore.digest(store.openInput(original.uri), fingerprint.bytes))) {
                    throw new IOException("The original media file is missing or changed: " + filename);
                }
            }
        }
        // The folder's main index must be current when opened outside Fieldnote.
        if (slots.canonical != latest) {
            Uri canonical = fileOrCreate(project, children, INDEX, "application/json");
            writeVerifiedIndex(canonical, latest.bytes);
        }
    }

    void writeIndex(Uri directory, JSONObject next) throws IOException, JSONException {
        byte[] encoded = encode(next);
        Uri project = store.directoryUri(directory);
        Map<String, ProjectStore.Document> children = store.children(project);
        IndexSlots slots = readSlots(children);
        IndexCopy latest = slots.latest();
        if (!latest.index.getString("id").equals(next.getString("id"))
                || latest.index.optLong("revision") + 1 != next.optLong("revision")) {
            throw new IOException("The working project changed while preparing the save. Open it again and retry.");
        }

        Uri canonical = fileOrCreate(project, children, INDEX, "application/json");
        if (slots.canonical != latest) {
            // Never truncate the sole verified copy of the latest committed revision.
            writeVerifiedIndex(canonical, latest.bytes);
        }
        Uri pending = fileOrCreate(project, children, PENDING_INDEX, "application/json");
        writeVerifiedIndex(pending, encoded);
        try {
            writeVerifiedIndex(canonical, encoded);
        } catch (InterruptedIOException interrupted) {
            throw interrupted;
        } catch (IOException mirrorFailure) {
            // The pending copy is already durable. The next update will repair the
            // canonical index before replacing the pending revision.
        }
    }

    void addOriginal(Uri directory, JSONObject media, InputStream source) throws IOException, JSONException {
        String filename = ProjectStore.mediaFilename(media.getString("path"));
        ProjectStore.Digest expected = fingerprint(media);
        Uri originals = mediaDirectory(directory);
        Map<String, ProjectStore.Document> children = store.children(originals);
        ProjectStore.Document existing = children.get(filename);
        if (existing != null) {
            if (!existing.isDirectory() && expected.equals(ProjectStore.digest(store.openInput(existing.uri)))) return;
            throw new IOException("A different original already uses this filename. Both originals were preserved.");
        }

        String stagedName = filename + ".pending";
        if (children.containsKey(stagedName) && hasIndexedPath(read(directory), "media/" + stagedName)) {
            throw new IOException("An indexed original already uses the pending filename. Both originals were preserved.");
        }
        Uri staged = fileOrCreate(originals, children, stagedName, BINARY);
        writeOriginal(staged, source, expected);
        Uri published = store.renameDocument(staged, filename);
        if (!filename.equals(store.document(published).name)
                || !expected.equals(ProjectStore.digest(store.openInput(published)))) {
            throw new IOException("The storage provider did not preserve the finalized original.");
        }
    }

    Uri mediaUri(Uri directory, String path) throws IOException {
        String filename = ProjectStore.mediaFilename(path);
        ProjectStore.Document file = store.children(mediaDirectory(directory)).get(filename);
        if (file == null || file.isDirectory()) throw new IOException("The original media file is missing: " + filename);
        return file.uri;
    }

    private Uri mediaDirectory(Uri project) throws IOException {
        ProjectStore.Document originals = store.children(store.directoryUri(project)).get("media");
        if (originals == null || !originals.isDirectory()) throw new IOException("The project's media folder is missing.");
        return originals.uri;
    }

    private Uri createNamed(Uri parent, String type, String name) throws IOException {
        Uri created = store.createDocument(parent, type, name);
        if (!name.equals(store.document(created).name)) {
            throw new IOException("The storage provider changed a project filename. Choose a location that preserves filenames.");
        }
        return created;
    }

    private Uri fileOrCreate(Uri parent, Map<String, ProjectStore.Document> children, String name, String mime)
            throws IOException {
        ProjectStore.Document existing = children.get(name);
        if (existing == null) return createNamed(parent, mime, name);
        if (existing.isDirectory()) throw new IOException("A folder is using a reserved project filename: " + name);
        return existing.uri;
    }

    private IndexSlots readSlots(Map<String, ProjectStore.Document> children) throws IOException {
        return new IndexSlots(readIndex(children.get(INDEX)), readIndex(children.get(PENDING_INDEX)));
    }

    private IndexCopy readIndex(ProjectStore.Document file) throws IOException {
        if (file == null) return null;
        if (file.isDirectory()) throw new IOException("A folder is using a reserved project index filename.");
        // A provider read failure is not proof of a truncated copy. In particular, it
        // must never authorize replacing an inaccessible, potentially newer revision.
        byte[] bytes = ProjectStore.readBytes(store.openInput(file.uri));
        try {
            JSONObject index = ProjectJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
            store.validateIndex(index);
            return new IndexCopy(index, bytes);
        } catch (ProjectJson.LimitExceededException | InterruptedIOException unavailable) {
            throw unavailable;
        } catch (IOException | JSONException invalid) {
            return null;
        }
    }

    byte[] encode(JSONObject index) throws IOException, JSONException {
        store.validateIndex(index);
        ProjectArchive.validatePortableIndex(index);
        String json = index.toString();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > INDEX_LIMIT) throw new ProjectJson.LimitExceededException("The project index exceeds the 32 MiB limit.");
        // Enforce the reader's structural limits before touching either copy.
        ProjectJson.parseObject(json);
        return bytes;
    }

    private void writeVerifiedIndex(Uri target, byte[] expected) throws IOException {
        try (OutputStream output = store.openOutput(target)) { output.write(expected); }
        if (!Arrays.equals(expected, ProjectStore.readBytes(store.openInput(target)))) {
            throw new IOException("The storage provider did not preserve the complete project index.");
        }
    }

    private void writeOriginal(Uri target, InputStream source, ProjectStore.Digest expected) throws IOException {
        try (OutputStream output = store.openOutput(target)) {
            ProjectStore.transferBounded(source, output, expected.bytes);
        }
        if (!expected.equals(ProjectStore.digest(store.openInput(target)))) {
            throw new IOException("The copied original did not match its expected length and checksum.");
        }
    }

    private static ProjectStore.Digest fingerprint(JSONObject media) throws IOException, JSONException {
        long bytes = media.getLong("bytes");
        String sha = media.getString("sha256").toLowerCase(Locale.ROOT);
        if (bytes < 1 || bytes > MEDIA_LIMIT || !sha.matches("[0-9a-f]{64}")) {
            throw new IOException("An original has an invalid length or checksum.");
        }
        return new ProjectStore.Digest(bytes, sha);
    }

    private static boolean hasIndexedPath(JSONObject index, String path) throws JSONException {
        JSONArray entries = index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONArray media = entries.getJSONObject(i).getJSONArray("media");
            for (int j = 0; j < media.length(); j++) {
                if (folded(path).equals(folded(media.getJSONObject(j).getString("path")))) return true;
            }
        }
        return false;
    }

    private static String folded(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static final class IndexCopy {
        final JSONObject index;
        final byte[] bytes;

        IndexCopy(JSONObject index, byte[] bytes) {
            this.index = index;
            this.bytes = bytes;
        }
    }

    private static final class IndexSlots {
        final IndexCopy canonical;
        final IndexCopy pending;

        IndexSlots(IndexCopy canonical, IndexCopy pending) {
            this.canonical = canonical;
            this.pending = pending;
        }

        IndexCopy latest() throws IOException, JSONException {
            if (canonical == null && pending == null) throw new IOException("The folder has no readable Fieldnote project index.");
            if (canonical == null) return pending;
            if (pending == null) return canonical;
            long currentRevision = canonical.index.optLong("revision");
            long pendingRevision = pending.index.optLong("revision");
            if (!canonical.index.getString("id").equals(pending.index.getString("id"))
                    || (currentRevision == pendingRevision && !Arrays.equals(canonical.bytes, pending.bytes))) {
                throw new IOException("The folder contains conflicting project index copies. Both copies were preserved.");
            }
            return currentRevision >= pendingRevision ? canonical : pending;
        }
    }
}
