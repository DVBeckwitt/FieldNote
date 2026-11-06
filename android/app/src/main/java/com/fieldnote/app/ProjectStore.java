package com.fieldnote.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipFile;

/**
 * A completed project's selected ZIP is the source of truth; bookmarks contain no media.
 * SAF providers do not promise atomic replacement. Durable private previous/next archives
 * and a journal protect publication, and failed captures retain their original bytes.
 * A successful save requires a fresh read and full verification of the selected ZIP.
 * Legacy project folders remain readable and are copied, never modified, during migration.
 * Unfinished or invalid recordings are retained without claiming that they were saved.
 */
public final class ProjectStore {
    private static final Object LOCK = new Object();
    private static final Object CAPTURE_LOCK = new Object();
    private static final Object CACHE_LOCK = new Object();
    private static final Object PREF_LOCK = new Object();
    private static volatile long publishedGeneration;
    private static final long MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024;
    private static final String INDEX = "index.json";
    private static final String BACKUP = "index.backup.json";
    private static final String PENDING_INDEX = "index.pending.json";
    private static final String LAST_CREATED_PROJECT = "last-created-project";
    private static final int JSON_LIMIT = 32 * 1024 * 1024;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC);
    private final ContentResolver resolver;
    private final Context appContext;
    private final SharedPreferences preferences;
    private final File pendingDirectory;
    private final File publicationsDirectory;
    private final File archiveWorkDirectory;
    private final File projectCacheDirectory;
    private volatile String lastError = "";

    public ProjectStore(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        resolver = app.getContentResolver();
        preferences = app.getSharedPreferences("fieldnote-folders", Context.MODE_PRIVATE);
        pendingDirectory = new File(app.getFilesDir(), "pending-captures");
        publicationsDirectory = new File(app.getFilesDir(), "archive-publications");
        archiveWorkDirectory = new File(app.getCacheDir(), "archive-work");
        projectCacheDirectory = new File(app.getCacheDir(), "project-index");
    }

    /** Process-local notification hint; project ZIP contents remain authoritative. */
    public static long publicationGeneration() { return publishedGeneration; }

    /** New projects are one portable ZIP in the user-selected parent folder. */
    public Uri createProject(Uri parentTree, String name) throws IOException {
        synchronized (LOCK) {
            try {
                String clean = folderName(name);
                JSONObject index = new JSONObject();
                index.put("schemaVersion", 1);
                index.put("format", "fieldnote-project");
                index.put("id", UUID.randomUUID().toString());
                index.put("name", clean);
                index.put("createdAt", iso(System.currentTimeMillis()));
                index.put("revision", 0);
                index.put("timestampConvention", "ISO 8601 UTC; capture timezone and UTC offset are stored per media item when available.");
                index.put("entries", new JSONArray());
                Uri archive = createArchive(parentTree, clean, index, path -> {
                    throw new IOException("An empty project has no media members.");
                }, "create");
                rememberProject(archive);
                setCurrentProject(archive);
                synchronized (PREF_LOCK) { setPreferenceUri(LAST_CREATED_PROJECT, archive); }
                return archive;
            } catch (JSONException failure) { throw problem("Could not create the project ZIP", failure); }
        }
    }

    /** Performs provider I/O; callers should keep it off the UI thread. */
    public boolean isArchive(Uri projectUri) throws IOException {
        synchronized (LOCK) { return !document(documentReference(projectUri)).isDirectory(); }
    }

    /** Copies a beta.1 folder into a ZIP without modifying any source file or timestamp. */
    public Uri importLegacyProject(Uri legacyProject, Uri destinationParentTree) throws IOException {
        synchronized (LOCK) {
            if (isArchive(legacyProject)) throw new IOException("This project is already a ZIP.");
            JSONObject index = readLegacyProject(legacyProject);
            try {
                String name = folderName(index.getString("name"));
                Uri parent = directoryUri(destinationParentTree);
                String archiveName = zipName(name);
                Document existing = children(parent).get(archiveName);
                Uri archive;
                if (existing != null) {
                    if (existing.isDirectory()) throw new IOException("The destination already contains a folder named " + archiveName + ".");
                    JSONObject converted = readProject(existing.uri);
                    if (!preservesLegacy(index, converted)) throw new IOException("A different project ZIP already uses this name. Choose a different destination folder.");
                    archive = existing.uri;
                } else {
                    archive = createArchive(parent, name, index,
                            path -> openInput(legacyMediaUri(legacyProject, path)), "import");
                }
                synchronized (PREF_LOCK) {
                    JSONObject routes = new JSONObject(preferences.getString("legacy-archive-routes", "{}"));
                    routes.put(legacyProject.toString(), archive.toString());
                    JSONArray bookmarks = new JSONArray();
                    Set<String> seen = new HashSet<>();
                    for (Uri saved : projects()) {
                        Uri replacement = saved.equals(legacyProject) ? archive : saved;
                        if (seen.add(replacement.toString())) bookmarks.put(replacement.toString());
                    }
                    if (seen.add(archive.toString())) bookmarks.put(archive.toString());
                    // Only bookmarks move. The legacy folder remains an untouched backup,
                    // and its route still lets finalized beta.1 journals reach this ZIP.
                    preferences.edit().putString("legacy-archive-routes", routes.toString())
                            .putString("projects", bookmarks.toString())
                            .putString("current-project", archive.toString()).apply();
                }
                return archive;
            } catch (JSONException failure) { throw problem("Could not convert the legacy project to ZIP", failure); }
        }
    }

    public JSONObject readProject(Uri projectUri) throws IOException {
        synchronized (LOCK) {
            if (!isArchive(projectUri)) return readLegacyProject(projectUri);
            ProjectArchive.Verified archive = readArchive(projectUri, true);
            try {
                validateIndex(archive.index);
                return copy(archive.index);
            } catch (JSONException failure) { throw problem("The ZIP contains an invalid project index", failure); }
            finally { deleteWorkFile(archive.file); }
        }
    }

    private JSONObject readLegacyProject(Uri projectUri) throws IOException {
        synchronized (LOCK) {
            try {
                Uri directory = directoryUri(projectUri);
                Map<String, Document> files = children(directory);
                JSONObject best = null;
                IOException invalid = null;
                // At equal revision prefer the normal index over recovery copies.
                for (String name : new String[] {INDEX, PENDING_INDEX, BACKUP}) {
                    Document document = files.get(name);
                    if (document == null || document.isDirectory()) continue;
                    try {
                        JSONObject candidate = readJson(document.uri);
                        validateIndex(candidate);
                        if (best == null || candidate.optLong("revision", 0) > best.optLong("revision", 0)) {
                            best = candidate;
                        }
                    } catch (ProjectJson.LimitExceededException unsupported) {
                        // Unsupported complexity is not corruption: selecting an older
                        // index could silently omit this candidate's data during migration.
                        throw unsupported;
                    } catch (IOException | JSONException | RuntimeException failure) {
                        invalid = problem("The project index is unreadable", failure);
                    }
                }
                if (best == null) {
                    if (invalid != null) throw invalid;
                    throw new IOException("This folder has no Fieldnote index.json. Select the complete project folder.");
                }
                return best;
            } catch (RuntimeException failure) {
                throw problem("Cannot access this project. Its folder may have moved or its permission may need to be renewed", failure);
            }
        }
    }

    public List<Uri> projects() {
        synchronized (PREF_LOCK) {
            List<Uri> result = new ArrayList<>();
            try {
                JSONArray saved = new JSONArray(preferences.getString("projects", "[]"));
                for (int i = 0; i < saved.length(); i++) {
                    String value = saved.optString(i, "");
                    if (!value.isEmpty()) result.add(Uri.parse(value));
                }
            } catch (JSONException ignored) {
                // A damaged bookmark list cannot damage project folders or their indexes.
            }
            return result;
        }
    }

    public void rememberProject(Uri projectUri) {
        synchronized (PREF_LOCK) {
            if (projectUri == null) return;
            List<Uri> saved = projects();
            if (!saved.contains(projectUri)) saved.add(projectUri);
            JSONArray array = new JSONArray();
            for (Uri uri : saved) array.put(uri.toString());
            preferences.edit().putString("projects", array.toString()).apply();
        }
    }

    public Uri currentProject() {
        synchronized (PREF_LOCK) { return preferenceUri("current-project"); }
    }

    /**
     * Quick capture targets the last successful creation, independently of selection.
     * A stored URI is returned even while inaccessible. Older installations without
     * this preference require read-only provider I/O, so call this off the UI thread.
     * An empty bookmark list returns null; an indeterminate latest project is an error.
     */
    public Uri lastCreatedProject() throws IOException {
        synchronized (PREF_LOCK) {
            Uri stored = persistedLastCreatedProject();
            if (stored != null) return stored;
        }
        synchronized (LOCK) {
            List<Uri> bookmarked = new ArrayList<>();
            try {
                synchronized (PREF_LOCK) {
                    // Creation may have completed while this fallback waited for I/O.
                    Uri stored = persistedLastCreatedProject();
                    if (stored != null) return stored;
                    try (JsonReader saved = new JsonReader(new StringReader(preferences.getString("projects", "[]")))) {
                        saved.setLenient(false);
                        saved.beginArray();
                        Set<String> seen = new HashSet<>();
                        while (saved.hasNext()) {
                            if (saved.peek() != JsonToken.STRING) throw new IOException("A saved project location is invalid.");
                            String value = saved.nextString();
                            if (value.trim().isEmpty()) throw new IOException("A saved project location is invalid.");
                            if (seen.add(value)) bookmarked.add(Uri.parse(value));
                        }
                        saved.endArray();
                        if (saved.peek() != JsonToken.END_DOCUMENT) throw new IOException("The saved project list has trailing content.");
                    }
                }
                Uri latest = null;
                Instant latestTime = null;
                for (Uri project : bookmarked) {
                    Instant createdAt = projectCreationTime(project);
                    if (latest == null || createdAt.isAfter(latestTime)
                            || (createdAt.equals(latestTime) && project.toString().compareTo(latest.toString()) < 0)) {
                        latest = project;
                        latestTime = createdAt;
                    }
                }
                return latest;
            } catch (IOException | JSONException | RuntimeException failure) {
                throw problem("The latest created project could not be determined. Restore access to saved projects or create a new project before quick capture", failure);
            }
        }
    }

    /** Caller holds PREF_LOCK. An unusable stored value must not enable fallback. */
    private Uri persistedLastCreatedProject() throws IOException {
        try {
            if (!preferences.contains(LAST_CREATED_PROJECT)) return null;
            Uri stored = preferenceUri(LAST_CREATED_PROJECT);
            if (stored == null) throw new IOException("The last created project location is missing.");
            return stored;
        } catch (RuntimeException failure) {
            throw problem("The last created project location could not be read", failure);
        }
    }

    /** Caller holds LOCK. Discovery must never repair or rewrite a bookmarked project. */
    private Instant projectCreationTime(Uri project) throws IOException, JSONException {
        if (!isArchive(project)) return Instant.parse(readLegacyProject(project).getString("createdAt"));
        ProjectArchive.Verified archive = readArchive(project, false);
        try { return Instant.parse(archive.index.getString("createdAt")); }
        finally { deleteWorkFile(archive.file); }
    }

    public void setCurrentProject(Uri projectUri) {
        synchronized (PREF_LOCK) {
            if (projectUri != null) rememberProject(projectUri);
            setPreferenceUri("current-project", projectUri);
        }
    }

    public Uri defaultFolder() {
        synchronized (PREF_LOCK) { return preferenceUri("default-folder"); }
    }

    public void setDefaultFolder(Uri folderUri) {
        synchronized (PREF_LOCK) { setPreferenceUri("default-folder", folderUri); }
    }

    public Uri mediaUri(Uri projectUri, String relativePath) throws IOException {
        synchronized (LOCK) {
            if (!isArchive(projectUri)) return legacyMediaUri(projectUri, relativePath);
            String filename = mediaFilename(relativePath);
            ProjectArchive.Verified archive = readArchive(projectUri, true);
            try (ZipFile zip = new ZipFile(archive.file)) {
                JSONObject media = findMediaByPath(archive.index, relativePath);
                if (media == null) throw new IOException("This original is not in the project index.");
                File directory = ArchiveMediaProvider.cacheDirectory(appContext);
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Playback cache is unavailable.");
                String cacheName = media.getString("sha256").toLowerCase(Locale.US) + extension(media.getString("kind"));
                File cached = new File(directory, cacheName);
                Digest expected = new Digest(media.getLong("bytes"), media.getString("sha256").toLowerCase(Locale.US));
                boolean matches = cached.isFile() && expected.equals(digest(new FileInputStream(cached)));
                if (!matches) {
                    File partial = new File(directory, cacheName + ".partial");
                    try (InputStream input = zip.getInputStream(zip.getEntry(relativePath)); OutputStream output = new FileOutputStream(partial)) {
                        transferBounded(input, output, MAX_ARCHIVE_BYTES);
                    }
                    if (!expected.equals(digest(new FileInputStream(partial)))) throw new IOException("The extracted original failed its checksum.");
                    if (cached.exists() && !cached.delete()) throw new IOException("Could not replace an invalid playback cache file.");
                    if (!partial.renameTo(cached)) throw new IOException("Could not finalize the playback cache file.");
                }
                return ArchiveMediaProvider.uriForFile(appContext, cached, filename);
            } catch (JSONException failure) { throw problem("The original's metadata is invalid", failure); }
            finally { deleteWorkFile(archive.file); }
        }
    }

    private Uri legacyMediaUri(Uri projectUri, String relativePath) throws IOException {
        synchronized (LOCK) {
            String filename = mediaFilename(relativePath);
            Document mediaDirectory = children(directoryUri(projectUri)).get("media");
            if (mediaDirectory == null || !mediaDirectory.isDirectory()) {
                throw new IOException("The project's media folder is missing.");
            }
            Document file = children(mediaDirectory.uri).get(filename);
            if (file == null || file.isDirectory()) throw new IOException("The original media file is missing: " + filename);
            return file.uri;
        }
    }

    public File newPendingFile(String kind) throws IOException {
        synchronized (CAPTURE_LOCK) {
            String extension = extension(kind);
            ensurePendingDirectory();
            File file = new File(pendingDirectory, "capture-" + UUID.randomUUID() + extension);
            if (!file.createNewFile()) throw new IOException("Could not reserve a recording file.");
            return file;
        }
    }

    public void beginCapture(Uri project, String entryId, String kind, File file,
                             long startedAt, JSONObject metadata) throws IOException {
        synchronized (CAPTURE_LOCK) {
            validateCapture(project, entryId, kind, file, startedAt, startedAt);
            try {
                JSONObject record = journal(project, entryId, kind, file, startedAt, startedAt, metadata, "recording", false);
                writeJournal(file, mergeCaptureJournal(file, record, false));
            } catch (JSONException failure) {
                throw problem("Could not journal the recording before capture", failure);
            }
        }
    }

    /** The stable ID used by a pending original and its eventual indexed media item. */
    public static String captureIdFor(File file) {
        if (file == null) throw new IllegalArgumentException("A pending capture file is required.");
        return captureId(file);
    }

    /**
     * Journals a marker without opening the project ZIP. RecordingService supplies the
     * immutable monotonic offset and wall-clock time from the user's marker action.
     */
    public JSONObject addPendingAudioMarker(File file, String markerId, long offsetMs,
                                            String label, long markedAt) throws IOException {
        synchronized (CAPTURE_LOCK) {
            validatePendingFile(file);
            try {
                JSONObject record = readLocalJson(journalFile(file));
                validateAudioJournal(file, record);
                JSONObject marker = EntryAnnotations.marker(captureId(file), markerId, offsetMs, label, markedAt);
                JSONArray markers = record.has("markers") ? record.getJSONArray("markers") : new JSONArray();
                EntryAnnotations.validateMarkerArray(markers, captureId(file), -1);
                JSONObject existing = findMarker(markers, markerId);
                if (existing != null) {
                    requireSameMarkerIntent(existing, marker);
                    return copy(existing);
                }
                if (record.optBoolean("finalized", false)) throw new IOException("This recording has already finished; add the marker from audio playback.");
                markers.put(marker);
                EntryAnnotations.validateMarkerArray(markers, captureId(file), -1);
                record.put("markers", sortedMarkers(markers));
                writeJournal(file, record);
                return copy(marker);
            } catch (JSONException | RuntimeException failure) {
                throw problem("The audio marker could not be preserved in the recording journal", failure);
            }
        }
    }

    public JSONArray pendingAudioMarkers(File file) throws IOException {
        synchronized (CAPTURE_LOCK) {
            validatePendingFile(file);
            try {
                JSONObject record = readLocalJson(journalFile(file));
                validateAudioJournal(file, record);
                JSONArray markers = record.has("markers") ? record.getJSONArray("markers") : new JSONArray();
                EntryAnnotations.validateMarkerArray(markers, captureId(file), -1);
                return new JSONArray(sortedMarkers(markers).toString());
            } catch (JSONException | RuntimeException failure) {
                throw problem("The recording's audio markers could not be read", failure);
            }
        }
    }

    private void validateAudioJournal(File file, JSONObject record) throws IOException, JSONException {
        if (!"audio".equals(record.getString("kind")) || !captureId(file).equals(record.getString("captureId"))
                || !file.getName().equals(record.getString("file"))) {
            throw new IOException("This pending journal does not belong to the audio recording.");
        }
    }

    /**
     * Durably finalizes a local original without touching the project provider or waiting
     * for ZIP publication. On success the publisher owns the retained original/journal.
     * The returned summary is explicitly pending; its checksum is computed when published.
     */
    public JSONObject stageCapture(Uri project, String entryId, String kind, File file,
                                   long startedAt, long endedAt, JSONObject metadata,
                                   String status) throws IOException {
        JSONObject summary;
        boolean newlyStaged;
        synchronized (CAPTURE_LOCK) {
            validateCapture(project, entryId, kind, file, startedAt, endedAt);
            if (!file.isFile() || file.length() == 0) throw new IOException("The finalized capture is empty or missing. It was not queued.");
            try {
                JSONObject record = mergeCaptureJournal(file, journal(project, entryId, kind, file, startedAt, endedAt,
                        metadata, status == null ? "complete" : status, true), true);
                summary = pendingMediaSummary(record, file);
                EntryAnnotations.validateMarkers(summary);
                newlyStaged = !record.has("stagedAt");
                // The encoder is already closed. Sync its bytes without copying, hashing,
                // truncating, or holding the archive publication monitor.
                try (FileOutputStream original = new FileOutputStream(file, true)) { original.getFD().sync(); }
                if (newlyStaged) record.put("stagedAt", iso(System.currentTimeMillis()));
                writeJournal(file, record);
                lastError = "";
            } catch (JSONException | RuntimeException failure) {
                throw problem("The finalized capture could not be preserved in local pending storage", failure);
            }
        }
        // Scheduling cannot undo local durability or turn a successful capture into a
        // recorder failure. Startup/manual retry also discovers these finalized journals.
        try {
            if (newlyStaged) ProjectPublisher.captureStaged(appContext);
            else ProjectPublisher.kick(appContext);
        }
        catch (RuntimeException failure) { lastError = "Capture saved locally; background ZIP publication could not start. Retry pending saves."; }
        return summary;
    }

    /** Local pending notes for a UI overlay. No provider access, hashing, or archive LOCK. */
    public JSONArray pendingEntries(Uri project) throws IOException {
        synchronized (CAPTURE_LOCK) {
            JSONArray result = new JSONArray();
            if (project == null) return result;
            Map<String, JSONObject> entries = new LinkedHashMap<>();
            try {
                JSONObject routes = new JSONObject(preferences.getString("legacy-archive-routes", "{}"));
                String requested = routes.optString(project.toString(), project.toString());
                for (File path : journalFiles()) {
                    try {
                        JSONObject record = readLocalJson(path);
                        if (!record.optBoolean("finalized", false)) continue;
                        String source = record.getString("projectUri");
                        if (!requested.equals(routes.optString(source, source))) continue;
                        File original = new File(pendingDirectory, record.getString("file"));
                        validatePendingFile(original);
                        JSONObject media = pendingMediaSummary(record, original);
                        EntryAnnotations.validateMarkers(media);
                        String id = record.getString("entryId");
                        JSONObject entry = entries.get(id);
                        if (entry == null) {
                            entry = new JSONObject().put("id", id).put("createdAt", media.getString("startedAt"))
                                    .put("media", new JSONArray()).put("_pending", true);
                            entries.put(id, entry);
                        } else if (parseInstant(media.getString("startedAt")) < parseInstant(entry.getString("createdAt"))) {
                            entry.put("createdAt", media.getString("startedAt"));
                        }
                        entry.getJSONArray("media").put(media);
                    } catch (IOException | JSONException | RuntimeException ignored) {
                        // A damaged/incomplete journal remains counted for attention, but
                        // must never be rendered as a valid queued original.
                    }
                }
                for (JSONObject entry : entries.values()) result.put(entry);
                return result;
            } catch (JSONException failure) { throw problem("Pending project notes could not be read", failure); }
        }
    }

    private JSONObject pendingMediaSummary(JSONObject record, File file) throws IOException, JSONException {
        String kind = record.getString("kind");
        String id = captureId(file);
        if (!id.equals(record.getString("captureId")) || !file.getName().equals(record.getString("file"))) {
            throw new IOException("A pending original does not match its capture journal.");
        }
        long start = record.getLong("startedAtMs"), end = record.getLong("endedAtMs");
        if (start < 0 || end < start) throw new IOException("Pending capture times are inconsistent.");
        String filename = kind + "_" + FILE_STAMP.format(Instant.ofEpochMilli(start)) + "_" + id + extension(kind);
        JSONObject media = new JSONObject().put("id", id).put("entryId", record.getString("entryId"))
                .put("kind", kind).put("filename", filename).put("path", "media/" + filename)
                .put("startedAt", iso(start)).put("endedAt", iso(end)).put("durationMs", end - start)
                .put("bytes", file.length()).put("status", record.getString("status"))
                .put("metadata", copy(record.getJSONObject("metadata"))).put("_pending", true);
        if (record.has("markers")) media.put("markers", new JSONArray(sortedMarkers(record.getJSONArray("markers")).toString()));
        return media;
    }

    /** Optional derived view only. All mutations and fresh reads still verify the external ZIP. */
    public JSONObject readCachedProject(Uri project) {
        if (project == null) return null;
        synchronized (CACHE_LOCK) {
            try {
                JSONObject cached = readLocalJson(projectCacheFile(project));
                validateIndex(cached);
                return cached;
            } catch (IOException | JSONException | RuntimeException ignored) { return null; }
        }
    }

    private File projectCacheFile(Uri project) throws IOException {
        Digest key = digest(new java.io.ByteArrayInputStream(project.toString().getBytes(StandardCharsets.UTF_8)));
        return new File(projectCacheDirectory, key.hex + ".json");
    }

    private void cacheVerifiedProject(Uri project, JSONObject index) {
        synchronized (CACHE_LOCK) {
            AtomicFile atomic = null;
            FileOutputStream output = null;
            try {
                if (!projectCacheDirectory.isDirectory() && !projectCacheDirectory.mkdirs()) return;
                atomic = new AtomicFile(projectCacheFile(project));
                output = atomic.startWrite();
                output.write(index.toString().getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(output);
            } catch (IOException | RuntimeException ignored) {
                if (atomic != null && output != null) atomic.failWrite(output);
                // Cache failure never changes the validity of the selected project ZIP.
            }
        }
    }

    /**
     * Commits one finalized original. On failure both its private file and journal stay
     * available for retry. The stable capture ID makes retries safe after partial success.
     */
    public JSONObject saveCapture(Uri project, String entryId, String kind, File file,
                                  long startedAt, long endedAt, JSONObject metadata,
                                  String status) throws IOException {
        synchronized (LOCK) {
            validateCapture(project, entryId, kind, file, startedAt, endedAt);
            ProjectArchive.Verified previousArchive = null;
            File nextFile = null;
            try {
                JSONObject record;
                synchronized (CAPTURE_LOCK) {
                    record = mergeCaptureJournal(file, journal(project, entryId, kind, file, startedAt, endedAt, metadata,
                            status == null ? "complete" : status, true), true);
                    // Markers accepted before this boundary are retained in the finalized
                    // journal. Active marker writes never wait for archive/provider I/O.
                    writeJournal(file, record);
                }
                Uri archiveUri = captureDestination(project);
                completePublicationIfPresent(archiveUri);
                previousArchive = readArchive(archiveUri, true);
                JSONObject index = previousArchive.index;
                JSONObject existing = findMedia(index, captureId(file));
                if (existing != null) {
                    Digest expected = new Digest(existing.getLong("bytes"), existing.getString("sha256").toLowerCase(Locale.US));
                    if (file.isFile() && !expected.equals(digest(new FileInputStream(file)))) {
                        throw new IOException("This pending capture differs from the indexed original. Both copies were retained.");
                    }
                    clearPending(file);
                    lastError = "";
                    return copy(existing);
                }
                if (!file.isFile() || file.length() == 0) {
                    throw new IOException("The finalized recording is empty or missing. It has not been added to the project.");
                }
                Digest source = digest(new FileInputStream(file));
                String filename = kind + "_" + FILE_STAMP.format(Instant.ofEpochMilli(startedAt))
                        + "_" + captureId(file) + extension(kind);
                JSONObject media = new JSONObject();
                media.put("id", captureId(file));
                media.put("kind", kind);
                media.put("filename", filename);
                media.put("path", "media/" + filename);
                media.put("startedAt", iso(startedAt));
                media.put("endedAt", iso(endedAt));
                media.put("durationMs", endedAt - startedAt);
                media.put("attachedAt", iso(System.currentTimeMillis()));
                media.put("bytes", source.bytes);
                media.put("sha256", source.hex);
                media.put("status", record.getString("status"));
                media.put("metadata", copy(metadata == null ? new JSONObject() : metadata));
                if (record.has("markers")) {
                    media.put("markers", sortedMarkers(record.getJSONArray("markers")));
                    EntryAnnotations.validateMarkers(media);
                }
                JSONObject next = copy(index);
                JSONArray entries = next.getJSONArray("entries");
                JSONObject entry = null;
                for (int i = 0; i < entries.length(); i++) {
                    if (entryId.equals(entries.getJSONObject(i).getString("id"))) { entry = entries.getJSONObject(i); break; }
                }
                if (entry == null) {
                    entry = new JSONObject();
                    entry.put("id", entryId);
                    entry.put("createdAt", iso(startedAt));
                    entry.put("media", new JSONArray());
                    entries.put(entry);
                }
                entry.getJSONArray("media").put(media);
                next.put("revision", index.optLong("revision", 0) + 1);
                next.put("updatedAt", iso(System.currentTimeMillis()));
                validateIndex(next);
                nextFile = newWorkFile();
                final String newPath = media.getString("path");
                try (ZipFile oldZip = new ZipFile(previousArchive.file)) {
                    ProjectArchive.Verified built = ProjectArchive.build(nextFile, next, path -> {
                        if (newPath.equals(path)) return new FileInputStream(file);
                        if (oldZip.getEntry(path) == null) throw new IOException("An indexed original is missing from the previous ZIP.");
                        return oldZip.getInputStream(oldZip.getEntry(path));
                    });
                    publishArchive(archiveUri, built, previousArchive, "capture", captureId(file));
                }
                clearPending(file);
                lastError = "";
                return media;
            } catch (JSONException | RuntimeException failure) {
                throw problem("The capture is retained on this phone, but saving to the project ZIP failed", failure);
            } catch (IOException failure) {
                lastError = failure.getMessage();
                throw failure;
            } finally {
                if (previousArchive != null) deleteWorkFile(previousArchive.file);
                deleteWorkFile(nextFile);
            }
        }
    }

    /**
     * Creates standalone text or sets text on the supplied entry. A supplied ID may be
     * absent while its audio is still recording; that later capture joins this entry.
     * Callers should retain one ID for a draft across retries after an uncertain save.
     */
    public JSONObject saveText(Uri project, String entryId, String text, JSONObject metadata) throws IOException {
        return saveText(project, entryId, text, metadata, null);
    }

    /** Optional tags are committed with the text, in the same ZIP update. */
    public JSONObject saveText(Uri project, String entryId, String text, JSONObject metadata, JSONArray tags) throws IOException {
        if (text == null || text.trim().isEmpty()) throw new IOException("Enter some text before saving.");
        return editEntry(project, entryId, null, text, metadata, tags, true);
    }

    /** Null fields are unchanged. Empty title clears it; text can be cleared if media remains. */
    public JSONObject updateEntry(Uri project, String entryId, String title, String text) throws IOException {
        return updateEntry(project, entryId, title, text, null, null);
    }

    /** Metadata is used only when this edit introduces text for the first time. */
    public JSONObject updateEntry(Uri project, String entryId, String title, String text, JSONObject metadata) throws IOException {
        return updateEntry(project, entryId, title, text, metadata, null);
    }

    public JSONObject updateEntry(Uri project, String entryId, String title, String text,
                                  JSONObject metadata, JSONArray tags) throws IOException {
        return editEntry(project, entryId, title, text, metadata, tags, false);
    }

    public JSONObject setEntryTags(Uri project, String entryId, JSONArray tags) throws IOException {
        return editEntry(project, entryId, null, null, null, tags, false);
    }

    public static JSONArray normalizeTags(String commaSeparated) throws IOException {
        return EntryAnnotations.normalizeTags(commaSeparated);
    }

    private JSONObject editEntry(Uri project, String entryId, String title, String text,
                                 JSONObject metadata, JSONArray tags, boolean createIfMissing) throws IOException {
        synchronized (LOCK) {
            if (project == null) throw new IOException("Choose a project before saving a note.");
            if (entryId == null) {
                if (!createIfMissing) throw new IOException("Choose the entry to edit.");
                entryId = UUID.randomUUID().toString();
            }
            if (entryId.trim().isEmpty()) throw new IOException("The entry ID is missing.");
            if (title != null) {
                title = title.trim();
                if (title.length() > 200) throw new IOException("Entry titles can contain at most 200 characters.");
            }
            if (text != null && text.length() > 100_000) throw new IOException("A text note can contain at most 100,000 characters.");
            JSONArray normalizedTags = tags == null ? null : EntryAnnotations.normalizeTags(tags);
            ProjectArchive.Verified previous = null;
            try {
                Uri target = captureDestination(project);
                completePublicationIfPresent(target);
                previous = readArchive(target, true);
                JSONObject next = copy(previous.index);
                JSONObject entry = findEntry(next, entryId);
                boolean changed = false;
                String now = iso(System.currentTimeMillis());
                if (entry == null) {
                    if (!createIfMissing) throw new IOException("This entry no longer exists in the project.");
                    entry = new JSONObject();
                    entry.put("id", entryId);
                    entry.put("createdAt", now);
                    entry.put("media", new JSONArray());
                    next.getJSONArray("entries").put(entry);
                    changed = true;
                }
                if (title != null) {
                    if (title.isEmpty()) {
                        if (entry.has("title")) { entry.remove("title"); changed = true; }
                    } else if (!title.equals(entry.optString("title", ""))) {
                        entry.put("title", title);
                        changed = true;
                    }
                }
                if (text != null) {
                    if (text.trim().isEmpty()) {
                        if (entry.getJSONArray("media").length() == 0) throw new IOException("A standalone text note must keep some text.");
                        if (entry.has("text")) {
                            entry.remove("text");
                            entry.remove("textCreatedAt");
                            entry.remove("textUpdatedAt");
                            entry.remove("textMetadata");
                            changed = true;
                        }
                    } else if (!text.equals(entry.optString("text", ""))) {
                        if (!entry.has("text")) {
                            entry.put("textCreatedAt", now);
                            entry.put("textMetadata", copy(metadata == null ? new JSONObject() : metadata));
                        }
                        entry.put("text", text);
                        entry.put("textUpdatedAt", now);
                        changed = true;
                    }
                }
                if (normalizedTags != null) {
                    if (normalizedTags.length() == 0) {
                        if (entry.has("tags")) { entry.remove("tags"); changed = true; }
                    } else if (!entry.has("tags") || !sameJson(entry.getJSONArray("tags"), normalizedTags)) {
                        entry.put("tags", normalizedTags);
                        changed = true;
                    }
                }
                if (!changed) {
                    lastError = "";
                    return copy(entry);
                }
                entry.put("updatedAt", now);
                next.put("revision", previous.index.optLong("revision", 0) + 1);
                next.put("updatedAt", now);
                publishIndexEdit(target, previous, next);
                lastError = "";
                return copy(entry);
            } catch (JSONException | RuntimeException failure) {
                throw problem("The note could not be saved to the project ZIP", failure);
            } catch (IOException failure) {
                lastError = failure.getMessage();
                throw failure;
            } finally { if (previous != null) deleteWorkFile(previous.file); }
        }
    }

    public JSONObject addAudioMarker(Uri project, String entryId, String mediaId, String markerId,
                                      long offsetMs, String label, long markedAt) throws IOException {
        JSONObject marker = EntryAnnotations.marker(mediaId, markerId, offsetMs, label, markedAt);
        return editAudioMarker(project, entryId, mediaId, markerId, marker, null, MarkerEdit.ADD);
    }

    public JSONObject updateAudioMarker(Uri project, String entryId, String mediaId,
                                         String markerId, String label) throws IOException {
        String normalized = label == null ? "" : label.trim();
        if (normalized.length() > 200) throw new IOException("Audio marker labels can contain at most 200 characters.");
        return editAudioMarker(project, entryId, mediaId, markerId, null, normalized, MarkerEdit.RENAME);
    }

    /** Deleting an already-absent marker is a no-op, so a repeated request is safe. */
    public boolean deleteAudioMarker(Uri project, String entryId, String mediaId, String markerId) throws IOException {
        return editAudioMarker(project, entryId, mediaId, markerId, null, null, MarkerEdit.DELETE) != null;
    }

    private enum MarkerEdit { ADD, RENAME, DELETE }

    private JSONObject editAudioMarker(Uri project, String entryId, String mediaId, String markerId,
                                        JSONObject added, String label, MarkerEdit edit) throws IOException {
        synchronized (LOCK) {
            if (project == null || entryId == null || entryId.trim().isEmpty() || mediaId == null || mediaId.trim().isEmpty()
                    || markerId == null || markerId.trim().isEmpty()) throw new IOException("Choose the audio recording and marker to edit.");
            ProjectArchive.Verified previous = null;
            try {
                Uri target = captureDestination(project);
                completePublicationIfPresent(target);
                previous = readArchive(target, true);
                JSONObject next = copy(previous.index);
                JSONObject entry = findEntry(next, entryId);
                if (entry == null) throw new IOException("This entry no longer exists in the project.");
                JSONObject audio = null;
                JSONArray media = entry.getJSONArray("media");
                for (int i = 0; i < media.length(); i++) {
                    JSONObject candidate = media.getJSONObject(i);
                    if (mediaId.equals(candidate.getString("id"))) { audio = candidate; break; }
                }
                if (audio == null || !"audio".equals(audio.getString("kind"))) throw new IOException("The marker's audio recording is not in this entry.");
                JSONArray markers = audio.has("markers") ? audio.getJSONArray("markers") : new JSONArray();
                JSONObject existing = findMarker(markers, markerId);
                JSONObject result;
                String now = iso(System.currentTimeMillis());
                if (edit == MarkerEdit.ADD) {
                    if (existing != null) {
                        requireSameMarkerIntent(existing, added);
                        lastError = "";
                        return copy(existing);
                    }
                    markers.put(added);
                    result = added;
                } else if (edit == MarkerEdit.RENAME) {
                    if (existing == null) throw new IOException("This audio marker no longer exists.");
                    if (label.equals(existing.getString("label"))) {
                        lastError = "";
                        return copy(existing);
                    }
                    existing.put("label", label);
                    existing.put("updatedAt", now);
                    result = existing;
                } else {
                    if (existing == null) { lastError = ""; return null; }
                    result = copy(existing);
                    for (int i = 0; i < markers.length(); i++) {
                        if (markerId.equals(markers.getJSONObject(i).getString("id"))) { markers.remove(i); break; }
                    }
                }
                if (markers.length() == 0) audio.remove("markers");
                else audio.put("markers", sortedMarkers(markers));
                EntryAnnotations.validateMarkers(audio);
                entry.put("updatedAt", now);
                next.put("revision", previous.index.optLong("revision", 0) + 1);
                next.put("updatedAt", now);
                publishIndexEdit(target, previous, next);
                lastError = "";
                return copy(result);
            } catch (JSONException | RuntimeException failure) {
                throw problem("The audio marker could not be saved to the project ZIP", failure);
            } catch (IOException failure) {
                lastError = failure.getMessage();
                throw failure;
            } finally { if (previous != null) deleteWorkFile(previous.file); }
        }
    }

    private static JSONObject findMarker(JSONArray markers, String markerId) throws JSONException {
        for (int i = 0; i < markers.length(); i++) {
            JSONObject marker = markers.getJSONObject(i);
            if (markerId.equals(marker.getString("id"))) return marker;
        }
        return null;
    }

    private static void requireSameMarkerIntent(JSONObject existing, JSONObject requested) throws IOException, JSONException {
        if (existing.getLong("offsetMs") != requested.getLong("offsetMs")) {
            throw new IOException("This audio marker ID already identifies a different marker. The saved marker was not changed.");
        }
        for (String key : new String[] {"id", "mediaId", "label", "createdAt"}) {
            if (!sameJson(existing.get(key), requested.get(key))) {
                throw new IOException("This audio marker ID already identifies a different marker. The saved marker was not changed.");
            }
        }
    }

    private static JSONArray sortedMarkers(JSONArray markers) throws JSONException {
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < markers.length(); i++) ordered.add(markers.getJSONObject(i));
        ordered.sort((a, b) -> {
            int offset = Long.compare(a.optLong("offsetMs"), b.optLong("offsetMs"));
            return offset == 0 ? a.optString("id").compareTo(b.optString("id")) : offset;
        });
        JSONArray result = new JSONArray();
        for (JSONObject marker : ordered) result.put(marker);
        return result;
    }

    /** Rebuilds only the index; every existing original is streamed without modification. */
    private void publishIndexEdit(Uri target, ProjectArchive.Verified previous, JSONObject next) throws IOException, JSONException {
        validateIndex(next);
        File work = newWorkFile();
        try (ZipFile originals = new ZipFile(previous.file)) {
            ProjectArchive.Verified built = ProjectArchive.build(work, next, path -> {
                if (originals.getEntry(path) == null) throw new IOException("An indexed original is missing from the project ZIP.");
                return originals.getInputStream(originals.getEntry(path));
            });
            publishArchive(target, built, previous, "entry", null);
        } finally { deleteWorkFile(work); }
    }

    private JSONObject findEntry(JSONObject index, String entryId) throws JSONException {
        JSONArray entries = index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entryId.equals(entry.getString("id"))) return entry;
        }
        return null;
    }

    /** Retries durable ZIP edits and finalized captures; unfinished recorder output is retained. */
    public int retryPending() throws IOException {
        synchronized (LOCK) {
            int saved = 0;
            IOException lastFailure = null;
            for (File directory : publicationDirectories()) {
                checkInterrupted();
                try {
                    JSONObject publication = readLocalJson(new File(directory, "publication.json"));
                    Uri target = Uri.parse(publication.getString("projectUri"));
                    if (!directory.equals(publicationDirectory(target))) throw new IOException("A ZIP recovery journal is stored under an unexpected location.");
                    boolean hasCaptureJournal = publicationHasCaptureJournal(publication);
                    completePublicationIfPresent(target);
                    rememberProject(target);
                    if (currentProject() == null) setCurrentProject(target);
                    if (!hasCaptureJournal) saved++;
                } catch (IOException failure) { lastFailure = failure; }
                catch (JSONException | RuntimeException failure) {
                    lastFailure = problem("A ZIP recovery journal is unreadable; recovery copies were retained", failure);
                }
            }
            for (File path : journalFiles()) {
                checkInterrupted();
                try {
                    JSONObject record = readCaptureJournalFile(path);
                    if (!record.optBoolean("finalized", false)) continue;
                    File file = new File(pendingDirectory, record.getString("file"));
                    validatePendingFile(file);
                    saveCapture(Uri.parse(record.getString("projectUri")), record.getString("entryId"),
                            record.getString("kind"), file, record.getLong("startedAtMs"),
                            record.getLong("endedAtMs"), record.getJSONObject("metadata"), record.getString("status"));
                    saved++;
                } catch (JSONException | RuntimeException failure) {
                    lastFailure = problem("A pending capture journal is unreadable; the original has been retained", failure);
                } catch (IOException failure) {
                    lastFailure = failure;
                }
            }
            checkInterrupted();
            if (lastFailure != null) {
                lastError = saved + " pending item(s) saved; at least one pending item still needs attention. " + lastFailure.getMessage();
                throw new IOException(lastError, lastFailure);
            }
            lastError = "";
            return saved;
        }
    }

    /** Journals plus orphan staging files, including output that cannot be safely auto-retried. */
    public int pendingCount() {
        synchronized (CAPTURE_LOCK) {
            File[] journals = journalFiles();
            Set<String> journaled = new HashSet<>();
            for (File journal : journals) {
                String name = journal.getName();
                journaled.add(name.substring(0, name.length() - ".json".length()));
            }
            int count = journals.length + extraPublicationCount(false);
            File[] originals = pendingDirectory.listFiles((directory, name) -> name.startsWith("capture-")
                    && (name.endsWith(".m4a") || name.endsWith(".jpg") || name.endsWith(".mp4")));
            if (originals != null) for (File original : originals) if (!journaled.contains(original.getName())) count++;
            return count;
        }
    }

    public int retryablePendingCount() {
        synchronized (CAPTURE_LOCK) {
            int count = extraPublicationCount(true);
            for (File file : journalFiles()) {
                try { if (readCaptureJournalFile(file).optBoolean("finalized", false)) count++; }
                catch (IOException | JSONException ignored) { /* Corrupt journals remain unfinished. */ }
            }
            return count;
        }
    }

    public int unfinishedPendingCount() { synchronized (CAPTURE_LOCK) { return pendingCount() - retryablePendingCount(); } }

    /** Removes only empty captures. Nonempty failed output is retained with an honest status. */
    public void abandonCapture(File file, String reason) throws IOException {
        synchronized (CAPTURE_LOCK) {
            validatePendingFile(file);
            if (!file.exists() || file.length() == 0) {
                clearPending(file);
                return;
            }
            try {
                JSONObject record = readLocalJson(journalFile(file));
                record.put("finalized", false);
                record.put("status", "failed");
                record.put("failureReason", reason == null ? "Capture did not finalize successfully." : reason);
                record.put("failureObservedAt", iso(System.currentTimeMillis()));
                writeJournal(file, record);
            } catch (JSONException failure) {
                throw problem("Could not update the failed capture journal; the original is retained", failure);
            }
        }
    }

    public String lastError() { return lastError; }

    private Uri createArchive(Uri parentTree, String name, JSONObject index,
                              ProjectArchive.MediaSource source, String purpose) throws IOException, JSONException {
        Uri parent = directoryUri(parentTree);
        String filename = zipName(name);
        for (String child : children(parent).keySet()) {
            if (filename.equalsIgnoreCase(child)) throw new IOException("A project ZIP with this name already exists in this folder.");
        }
        validateIndex(index);
        File work = newWorkFile();
        try {
            ProjectArchive.Verified built = ProjectArchive.build(work, index, source);
            Uri archive = createDocument(parent, "application/zip", filename);
            if (!filename.equals(document(archive).name)) {
                throw new IOException("The storage provider changed the ZIP filename. Select a location that supports normal filenames.");
            }
            publishArchive(archive, built, null, purpose, null);
            return archive;
        } finally { deleteWorkFile(work); }
    }

    private Uri captureDestination(Uri original) throws IOException, JSONException {
        // Consult a completed migration route first: the preserved source folder can move
        // after conversion without stranding a beta.1 pending recording.
        JSONObject routes = new JSONObject(preferences.getString("legacy-archive-routes", "{}"));
        String routed = routes.optString(original.toString(), "");
        Uri destination = routed.isEmpty() ? original : Uri.parse(routed);
        if (!isArchive(destination)) throw new IOException("Convert this older project folder to a ZIP before saving changes.");
        return destination;
    }

    private static String zipName(String name) {
        return name.toLowerCase(Locale.US).endsWith(".zip") ? name : name + ".zip";
    }

    private boolean preservesLegacy(JSONObject source, JSONObject destination) throws JSONException {
        if (!source.getString("id").equals(destination.getString("id"))
                || !source.getString("createdAt").equals(destination.getString("createdAt"))
                || !source.getString("name").equals(destination.getString("name"))) return false;
        JSONArray originalEntries = source.getJSONArray("entries");
        JSONArray convertedEntries = destination.getJSONArray("entries");
        for (int i = 0; i < originalEntries.length(); i++) {
            JSONObject originalEntry = originalEntries.getJSONObject(i);
            JSONObject convertedEntry = null;
            for (int j = 0; j < convertedEntries.length(); j++) {
                JSONObject candidate = convertedEntries.getJSONObject(j);
                if (originalEntry.getString("id").equals(candidate.getString("id"))) { convertedEntry = candidate; break; }
            }
            if (convertedEntry == null || !originalEntry.getString("createdAt").equals(convertedEntry.getString("createdAt"))) return false;
            JSONArray originals = originalEntry.getJSONArray("media");
            JSONArray converted = convertedEntry.getJSONArray("media");
            for (int j = 0; j < originals.length(); j++) {
                JSONObject original = originals.getJSONObject(j);
                boolean found = false;
                for (int k = 0; k < converted.length(); k++) {
                    JSONObject candidate = converted.getJSONObject(k);
                    if (original.getString("id").equals(candidate.getString("id")) && sameJson(original, candidate)) { found = true; break; }
                }
                if (!found) return false;
            }
        }
        return true;
    }

    private static boolean sameJson(Object a, Object b) throws JSONException {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject left = (JSONObject) a, right = (JSONObject) b;
            if (left.length() != right.length()) return false;
            java.util.Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !sameJson(left.get(key), right.get(key))) return false;
            }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray left = (JSONArray) a, right = (JSONArray) b;
            if (left.length() != right.length()) return false;
            for (int i = 0; i < left.length(); i++) if (!sameJson(left.get(i), right.get(i))) return false;
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }

    private JSONObject findMediaByPath(JSONObject index, String path) throws JSONException {
        JSONArray entries = index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONArray media = entries.getJSONObject(i).getJSONArray("media");
            for (int j = 0; j < media.length(); j++) {
                JSONObject item = media.getJSONObject(j);
                if (path.equals(item.getString("path"))) return item;
            }
        }
        return null;
    }

    private Uri documentReference(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("Select a project using Android's file picker.");
        try {
            if (!DocumentsContract.isTreeUri(uri)) return uri;
            String id;
            try { id = DocumentsContract.getDocumentId(uri); }
            catch (IllegalArgumentException treeOnly) { id = DocumentsContract.getTreeDocumentId(uri); }
            return DocumentsContract.buildDocumentUriUsingTree(uri, id);
        } catch (RuntimeException failure) { throw problem("The project location is invalid", failure); }
    }

    private File newWorkFile() throws IOException {
        if (!archiveWorkDirectory.isDirectory() && !archiveWorkDirectory.mkdirs()) throw new IOException("There is no space to stage the project ZIP on this phone.");
        return File.createTempFile("project-", ".zip", archiveWorkDirectory);
    }

    private void deleteWorkFile(File file) {
        if (file != null && archiveWorkDirectory.equals(file.getParentFile())) file.delete();
    }

    private ProjectArchive.Verified readExternalArchive(Uri uri) throws IOException {
        File work = newWorkFile();
        boolean readCompleted = false;
        try {
            try (InputStream input = openInput(uri); OutputStream output = new FileOutputStream(work)) {
                transferBounded(input, output, MAX_ARCHIVE_BYTES);
            }
            readCompleted = true;
            ProjectArchive.Verified result = ProjectArchive.verify(work);
            validateIndex(result.index);
            cacheVerifiedProject(uri, result.index);
            return result;
        } catch (JSONException | RuntimeException failure) {
            deleteWorkFile(work);
            IOException invalid = problem("The selected ZIP contains an invalid project", failure);
            if (readCompleted) throw new InvalidProjectArchiveException(invalid);
            throw invalid;
        } catch (IOException failure) {
            deleteWorkFile(work);
            if (readCompleted && !(failure instanceof InterruptedIOException)
                    && !(failure instanceof ProjectJson.LimitExceededException) && !Thread.currentThread().isInterrupted())
                throw new InvalidProjectArchiveException(failure);
            throw failure;
        }
    }

    /** Only a complete provider read can establish that recovery may replace invalid bytes. */
    private static final class InvalidProjectArchiveException extends IOException {
        InvalidProjectArchiveException(IOException cause) { super(cause.getMessage(), cause); }
    }

    private ProjectArchive.Verified readArchive(Uri uri, boolean recover) throws IOException {
        try { return readExternalArchive(uri); }
        catch (InvalidProjectArchiveException originalFailure) {
            if (!recover || Thread.currentThread().isInterrupted()) throw originalFailure;
            File directory = publicationDirectory(uri);
            if (!hasPublication(directory)) throw originalFailure;
            try {
                JSONObject record = publicationRecord(directory, uri);
                // Reading repairs an interrupted write to the last committed project. It
                // does not silently commit a later capture that the UI reported pending.
                String which = record.has("previousSha256") ? "previous" : "next";
                ProjectArchive.Verified backup = publicationArchive(directory, record, which);
                writeAndVerify(uri, backup);
                return readExternalArchive(uri);
            } catch (IOException | JSONException failure) {
                originalFailure.addSuppressed(failure);
                throw new IOException("The project ZIP needs recovery. Verified private recovery files and pending originals are retained; restore access and retry saving.", originalFailure);
            }
        }
    }

    private File publicationDirectory(Uri uri) throws IOException {
        Digest key = digest(new java.io.ByteArrayInputStream(uri.toString().getBytes(StandardCharsets.UTF_8)));
        return new File(publicationsDirectory, key.hex);
    }

    private boolean hasPublication(File directory) {
        File record = new File(directory, "publication.json");
        return record.isFile() || new File(record.getPath() + ".bak").isFile();
    }

    private JSONObject publicationRecord(File directory, Uri uri) throws IOException, JSONException {
        JSONObject record = readLocalJson(new File(directory, "publication.json"));
        if (!uri.toString().equals(record.getString("projectUri"))) throw new IOException("The ZIP recovery journal belongs to another location.");
        return record;
    }

    private ProjectArchive.Verified publicationArchive(File directory, JSONObject record, String which) throws IOException, JSONException {
        ProjectArchive.Verified archive = ProjectArchive.verify(new File(directory, which + ".zip"));
        validateIndex(archive.index);
        if (!archive.sha256.equals(record.getString(which + "Sha256"))
                || archive.bytes != record.getLong(which + "Bytes")
                || !archive.index.getString("id").equals(record.getString("projectId"))) {
            throw new IOException("A ZIP recovery file no longer matches its journal. All files were retained.");
        }
        return archive;
    }

    private void publishArchive(Uri uri, ProjectArchive.Verified next, ProjectArchive.Verified previous,
                                String purpose, String captureId) throws IOException, JSONException {
        File directory = publicationDirectory(uri);
        if (hasPublication(directory)) throw new IOException("An earlier ZIP update needs to be retried first.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("There is no space for a durable ZIP recovery copy.");
        // No provider write occurs until both copies and the recovery journal are durable.
        copyDurable(next.file, new File(directory, "next.zip"), next);
        if (previous != null) copyDurable(previous.file, new File(directory, "previous.zip"), previous);
        JSONObject record = new JSONObject();
        record.put("schemaVersion", 1);
        record.put("projectUri", uri.toString());
        record.put("projectId", next.index.getString("id"));
        record.put("purpose", purpose);
        if (captureId != null) record.put("captureId", captureId);
        record.put("nextSha256", next.sha256);
        record.put("nextBytes", next.bytes);
        if (previous != null) {
            record.put("previousSha256", previous.sha256);
            record.put("previousBytes", previous.bytes);
        }
        writeAtomicJson(new File(directory, "publication.json"), record);
        completePublicationIfPresent(uri);
    }

    private boolean completePublicationIfPresent(Uri uri) throws IOException, JSONException {
        checkInterrupted();
        File directory = publicationDirectory(uri);
        if (!hasPublication(directory)) return false;
        JSONObject record = publicationRecord(directory, uri);
        ProjectArchive.Verified next = publicationArchive(directory, record, "next");
        ProjectArchive.Verified previous = record.has("previousSha256") ? publicationArchive(directory, record, "previous") : null;
        ProjectArchive.Verified current = null;
        try {
            try { current = readExternalArchive(uri); }
            catch (InvalidProjectArchiveException ignored) {
                // A fully read, invalid ZIP may be our interrupted write. Access or
                // provider read failures must not bypass the external-change check.
            }
            if (current != null && current.sha256.equals(next.sha256) && current.bytes == next.bytes) {
                clearPublication(directory);
                publishedGeneration++;
                return true;
            }
            if (current != null && (previous == null || !current.sha256.equals(previous.sha256) || current.bytes != previous.bytes)) {
                throw new IOException("The project ZIP changed outside Fieldnote during a pending save. The external file and private recovery copies were preserved.");
            }
            try { writeAndVerify(uri, next); }
            catch (IOException failure) {
                if (previous != null && !Thread.currentThread().isInterrupted()) {
                    try { writeAndVerify(uri, previous); }
                    catch (IOException restoreFailure) { failure.addSuppressed(restoreFailure); }
                }
                throw new IOException("Saving the project ZIP failed. The pending update and verified recovery copies are retained on this phone for retry.", failure);
            }
            clearPublication(directory);
            publishedGeneration++;
            return true;
        } finally { if (current != null) deleteWorkFile(current.file); }
    }

    private void writeAndVerify(Uri uri, ProjectArchive.Verified source) throws IOException {
        checkInterrupted();
        try (InputStream input = new FileInputStream(source.file); OutputStream output = openOutput(uri)) {
            transferBounded(input, output, MAX_ARCHIVE_BYTES);
        } catch (RuntimeException failure) {
            throw problem("The storage provider interrupted the project ZIP write", failure);
        }
        ProjectArchive.Verified actual = readExternalArchive(uri);
        try {
            if (!source.sha256.equals(actual.sha256) || source.bytes != actual.bytes) {
                throw new IOException("The storage provider did not preserve the complete project ZIP.");
            }
        } finally { deleteWorkFile(actual.file); }
    }

    private void copyDurable(File source, File destination, ProjectArchive.Verified expected) throws IOException {
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
            transferBounded(input, output, MAX_ARCHIVE_BYTES);
            output.getFD().sync();
        }
        Digest actual = digest(new FileInputStream(destination));
        if (actual.bytes != expected.bytes || !actual.hex.equals(expected.sha256)) throw new IOException("The durable ZIP recovery copy could not be verified.");
    }

    private void clearPublication(File directory) {
        // Removing the journal first makes interruption during cleanup harmless: the
        // external archive has already passed a fresh full verification.
        synchronized (CAPTURE_LOCK) {
            new AtomicFile(new File(directory, "publication.json")).delete();
            if (hasPublication(directory)) return;
        }
        new File(directory, "next.zip").delete();
        new File(directory, "previous.zip").delete();
        directory.delete();
    }

    private boolean publicationHasCaptureJournal(JSONObject record) {
        String id = record.optString("captureId", "");
        if (id.isEmpty()) return false;
        for (File journal : journalFiles()) {
            String mediaName = journal.getName().substring(0, journal.getName().length() - 5);
            if (id.equals(captureId(new File(pendingDirectory, mediaName)))) return true;
        }
        return false;
    }

    private int extraPublicationCount(boolean onlyRetryable) {
        int count = 0;
        for (File directory : publicationDirectories()) {
            try {
                if (!publicationHasCaptureJournal(readLocalJson(new File(directory, "publication.json")))) count++;
            } catch (IOException | JSONException failure) {
                if (!onlyRetryable) count++;
            }
        }
        return count;
    }

    private File[] publicationDirectories() {
        File[] directories = publicationsDirectory.listFiles(file -> file.isDirectory() && hasPublication(file));
        return directories == null ? new File[0] : directories;
    }

    private void writeAtomicJson(File file, JSONObject value) throws IOException, JSONException {
        synchronized (CAPTURE_LOCK) {
            writeDurableJournal(file, value, "Could not preserve the ZIP recovery journal");
        }
    }

    /** The caller owns journal synchronization and validates the destination. */
    private void writeDurableJournal(File file, JSONObject value, String failureMessage) throws IOException, JSONException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            if (output != null) atomic.failWrite(output);
            throw problem(failureMessage, failure);
        }
    }

    private static void transferBounded(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkInterrupted();
            total += count;
            if (total > limit) throw new IOException("The project exceeds this beta's 8 GiB ZIP limit.");
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    /** Caller holds CAPTURE_LOCK across this merge and the following atomic write. */
    private JSONObject mergeCaptureJournal(File file, JSONObject next, boolean finalizing) throws IOException, JSONException {
        File path = journalFile(file);
        if (!path.exists() && !new File(path.getPath() + ".bak").exists()) return next;
        JSONObject previous = readLocalJson(path);
        verifyJournalIdentity(previous, next);
        if (!finalizing && previous.optBoolean("finalized", false)) {
            throw new IOException("This capture has already finalized. Its pending save was retained instead of restarting its journal.");
        }
        if (previous.has("markers")) {
            if (!"audio".equals(previous.getString("kind"))) throw new IOException("Only audio recordings can contain audio markers.");
            EntryAnnotations.validateMarkerArray(previous.getJSONArray("markers"), captureId(file), -1);
        }
        // Retain markers and unknown additive fields from later journal writers.
        java.util.Iterator<String> keys = next.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            previous.put(key, next.get(key));
        }
        return previous;
    }

    private JSONObject readCaptureJournalFile(File path) throws IOException, JSONException {
        synchronized (CAPTURE_LOCK) { return readLocalJson(path); }
    }

    private JSONObject journal(Uri project, String entryId, String kind, File file, long startedAt,
                               long endedAt, JSONObject metadata, String status, boolean finalized) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("schemaVersion", 1);
        result.put("captureId", captureId(file));
        result.put("projectUri", project.toString());
        result.put("entryId", entryId);
        result.put("kind", kind);
        result.put("file", file.getName());
        result.put("startedAtMs", startedAt);
        result.put("endedAtMs", finalized ? endedAt : JSONObject.NULL);
        result.put("status", status);
        result.put("finalized", finalized);
        result.put("metadata", copy(metadata == null ? new JSONObject() : metadata));
        return result;
    }

    private void verifyJournalIdentity(JSONObject old, JSONObject next) throws IOException {
        for (String key : new String[] {"captureId", "projectUri", "entryId", "kind", "file"}) {
            if (!old.optString(key).equals(next.optString(key))) {
                throw new IOException("The pending capture belongs to another project or entry. It was not moved or overwritten.");
            }
        }
    }

    private void validateIndex(JSONObject index) throws JSONException, IOException {
        if (index.optInt("schemaVersion", -1) != 1 || index.optString("id").isEmpty()
                || index.optString("name").isEmpty() || index.optLong("revision", 0) < 0) {
            throw new IOException("This project has an unsupported or invalid index.");
        }
        parseInstant(index.getString("createdAt"));
        JSONArray entries = index.getJSONArray("entries");
        Set<String> entryIds = new HashSet<>();
        Set<String> mediaIds = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String entryId = entry.getString("id");
            if (entryId.isEmpty() || !entryIds.add(entryId)) throw new IOException("The project contains duplicate or missing entry IDs.");
            parseInstant(entry.getString("createdAt"));
            ProjectArchive.validateEntryContent(entry);
            JSONArray media = entry.getJSONArray("media");
            for (int j = 0; j < media.length(); j++) {
                JSONObject item = media.getJSONObject(j);
                String id = item.getString("id");
                if (id.isEmpty() || !mediaIds.add(id)) throw new IOException("The project contains duplicate or missing media IDs.");
                extension(item.getString("kind"));
                String path = item.getString("path");
                if (!paths.add(path)) throw new IOException("Two media records refer to the same original file.");
                String filename = mediaFilename(path);
                if (!filename.equals(item.getString("filename"))) throw new IOException("A media filename and relative path disagree.");
                long start = parseInstant(item.getString("startedAt"));
                long end = parseInstant(item.getString("endedAt"));
                if (end < start || item.getLong("durationMs") != end - start) throw new IOException("A media item's capture times are inconsistent.");
                item.getJSONObject("metadata");
                EntryAnnotations.validateMarkers(item);
            }
        }
    }

    private JSONObject findMedia(JSONObject index, String captureId) throws JSONException {
        JSONArray entries = index.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONArray media = entries.getJSONObject(i).getJSONArray("media");
            for (int j = 0; j < media.length(); j++) {
                JSONObject item = media.getJSONObject(j);
                if (captureId.equals(item.getString("id"))) return item;
            }
        }
        return null;
    }

    private Uri directoryUri(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme()) || !DocumentsContract.isTreeUri(uri)) {
            throw new IOException("Choose a folder using Android's folder picker.");
        }
        try {
            String id;
            try { id = DocumentsContract.getDocumentId(uri); }
            catch (IllegalArgumentException treeOnly) { id = DocumentsContract.getTreeDocumentId(uri); }
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, id);
            if (!document(documentUri).isDirectory()) throw new IOException("The selected location is not a folder.");
            return documentUri;
        } catch (IllegalArgumentException | SecurityException failure) {
            throw problem("Folder access is unavailable. Select the folder again to renew permission", failure);
        }
    }

    private Map<String, Document> children(Uri directory) throws IOException {
        try {
            String id = DocumentsContract.getDocumentId(directory);
            Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(directory, id);
            Map<String, Document> result = new LinkedHashMap<>();
            try (Cursor cursor = resolver.query(query, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IOException("The storage provider did not return the folder contents.");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    Document child = new Document(DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0)), name, cursor.getString(2));
                    if (result.put(name, child) != null) throw new IOException("The storage provider returned duplicate filenames: " + name);
                }
            }
            return result;
        } catch (RuntimeException failure) {
            throw problem("Cannot read the selected folder. Check its permission and availability", failure);
        }
    }

    private Document document(Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri, new String[] {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("The selected document no longer exists.");
            return new Document(uri, cursor.getString(0), cursor.getString(1));
        } catch (RuntimeException failure) {
            throw problem("Cannot access the selected document", failure);
        }
    }

    private Uri createDocument(Uri parent, String mimeType, String name) throws IOException {
        try {
            Uri created = DocumentsContract.createDocument(resolver, parent, mimeType, name);
            if (created == null) throw new IOException("The storage provider could not create " + name + ".");
            return DocumentsContract.buildDocumentUriUsingTree(parent, DocumentsContract.getDocumentId(created));
        } catch (RuntimeException failure) {
            throw problem("Cannot write to this folder. Check free space and folder permission", failure);
        }
    }

    private JSONObject readJson(Uri uri) throws IOException, JSONException {
        return ProjectJson.parseObject(new String(readBytes(openInput(uri)), StandardCharsets.UTF_8));
    }

    private JSONObject readLocalJson(File file) throws IOException, JSONException {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return ProjectJson.parseObject(new String(readBytes(input), StandardCharsets.UTF_8));
        }
    }

    private void writeJournal(File media, JSONObject journal) throws IOException, JSONException {
        ensurePendingDirectory();
        validatePendingFile(media);
        writeDurableJournal(journalFile(media), journal, "Could not preserve the pending capture journal");
    }

    private InputStream openInput(Uri uri) throws IOException {
        try {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) throw new IOException("The storage provider could not open a file for reading.");
            return input;
        } catch (RuntimeException failure) { throw problem("Cannot read a project file", failure); }
    }

    private OutputStream openOutput(Uri uri) throws IOException {
        checkInterrupted();
        try {
            OutputStream output = resolver.openOutputStream(uri, "wt");
            if (output == null) throw new IOException("The storage provider could not open a file for writing.");
            return output;
        } catch (RuntimeException failure) { throw problem("Cannot write a project file", failure); }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP publication was paused; durable originals and recovery files are retained.");
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        try (InputStream owned = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = owned.read(buffer)) != -1) {
                checkInterrupted();
                if (output.size() + count > JSON_LIMIT) throw new IOException("The JSON file exceeds this beta's 32 MB limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        output.flush();
    }

    private static Digest digest(InputStream input) throws IOException {
        try (InputStream owned = input) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long bytes = 0;
            int count;
            while ((count = owned.read(buffer)) != -1) {
                checkInterrupted();
                sha.update(buffer, 0, count);
                bytes += count;
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : sha.digest()) hex.append(String.format(Locale.US, "%02x", value & 0xff));
            return new Digest(bytes, hex.toString());
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable.", impossible); }
    }

    private void validateCapture(Uri project, String entryId, String kind, File file,
                                 long startedAt, long endedAt) throws IOException {
        if (project == null || entryId == null || entryId.trim().isEmpty()) throw new IOException("Choose a project before recording.");
        extension(kind);
        validatePendingFile(file);
        if (startedAt < 0 || endedAt < startedAt) throw new IOException("Capture start and end times are inconsistent.");
    }

    private void validatePendingFile(File file) throws IOException {
        if (file == null || !pendingDirectory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())
                || !file.getName().startsWith("capture-")) {
            throw new IOException("The capture file is outside Fieldnote's pending storage.");
        }
    }

    private void ensurePendingDirectory() throws IOException {
        if (!pendingDirectory.isDirectory() && !pendingDirectory.mkdirs()) throw new IOException("There is no space for a pending capture on this phone.");
    }

    private File[] journalFiles() {
        File[] files = pendingDirectory.listFiles((directory, name) -> name.startsWith("capture-")
                && (name.endsWith(".json") || name.endsWith(".json.bak")));
        if (files == null) return new File[0];
        Map<String, File> bases = new LinkedHashMap<>();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
            bases.put(name, new File(pendingDirectory, name));
        }
        return bases.values().toArray(new File[0]);
    }

    private File journalFile(File media) { return new File(pendingDirectory, media.getName() + ".json"); }

    private void clearPending(File media) {
        synchronized (CAPTURE_LOCK) {
            // Destination and index have already been verified, or this capture is empty.
            if (media.exists() && !media.delete()) {
                lastError = "The project is saved, but an extra temporary copy remains on this phone.";
                return;
            }
            new AtomicFile(journalFile(media)).delete();
        }
    }

    private Uri preferenceUri(String key) {
        String value = preferences.getString(key, null);
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    private void setPreferenceUri(String key, Uri uri) {
        SharedPreferences.Editor editor = preferences.edit();
        if (uri == null) editor.remove(key); else editor.putString(key, uri.toString());
        editor.apply();
    }

    private IOException problem(String message, Throwable cause) {
        lastError = message + ".";
        return new IOException(lastError, cause);
    }

    private static String folderName(String name) throws IOException {
        if (name == null) throw new IOException("Enter a project name.");
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 100 || clean.equals(".") || clean.equals("..")
                || clean.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || clean.endsWith(".")) {
            throw new IOException("Use a project name of 1–100 characters without slashes or filename punctuation.");
        }
        return clean;
    }

    private static String mediaFilename(String path) throws IOException {
        if (path == null || !path.startsWith("media/")) throw new IOException("Media paths must be relative to the project's media folder.");
        String name = path.substring(6);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                || name.indexOf('\0') >= 0) throw new IOException("An unsafe relative media path was rejected.");
        return name;
    }

    private static String captureId(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String kind) throws IOException {
        if ("audio".equals(kind)) return ".m4a";
        if ("photo".equals(kind)) return ".jpg";
        if ("video".equals(kind)) return ".mp4";
        throw new IOException("Unsupported media type.");
    }

    private static String mime(String kind) throws IOException {
        if ("audio".equals(kind)) return "audio/mp4";
        if ("photo".equals(kind)) return "image/jpeg";
        if ("video".equals(kind)) return "video/mp4";
        throw new IOException("Unsupported media type.");
    }

    private static String iso(long milliseconds) { return Instant.ofEpochMilli(milliseconds).toString(); }

    private static long parseInstant(String value) throws IOException {
        try { return Instant.parse(value).toEpochMilli(); }
        catch (RuntimeException invalid) { throw new IOException("The project contains an invalid capture timestamp.", invalid); }
    }

    private static JSONObject copy(JSONObject object) throws JSONException { return new JSONObject(object.toString()); }

    private static final class Document {
        final Uri uri;
        final String name;
        final String mime;
        Document(Uri uri, String name, String mime) { this.uri = uri; this.name = name; this.mime = mime; }
        boolean isDirectory() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }
    }

    private static final class Digest {
        final long bytes;
        final String hex;
        Digest(long bytes, String hex) { this.bytes = bytes; this.hex = hex; }
        @Override public boolean equals(Object object) {
            return object instanceof Digest && bytes == ((Digest) object).bytes && hex.equals(((Digest) object).hex);
        }
        @Override public int hashCode() { return Long.hashCode(bytes) * 31 + hex.hashCode(); }
    }
}
