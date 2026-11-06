package com.fieldnote.app;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Streaming, non-extracting reader and writer for portable Fieldnote project ZIPs. */
public final class ProjectArchive {
    private static final long INDEX_LIMIT = 32L * 1024L * 1024L;
    private static final long MEDIA_LIMIT = 4L * 1024L * 1024L * 1024L;
    private static final long ARCHIVE_LIMIT = 8L * 1024L * 1024L * 1024L;
    private static final int MEMBER_LIMIT = 10_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    private ProjectArchive() { }

    public interface MediaSource {
        InputStream open(String relativePath) throws IOException;
    }

    public static final class Verified {
        public final File file;
        public final JSONObject index;
        public final String sha256;
        public final long bytes;

        private Verified(File file, JSONObject index, String sha256, long bytes) {
            this.file = file;
            this.index = index;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }

    /**
     * Verifies all members, including their ZIP CRCs and indexed original hashes.
     * No member is extracted. Callers must keep the input file immutable during use.
     */
    public static Verified verify(File archive) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("The project archive is missing.");
        long originalLength = archive.length();
        long originalModified = archive.lastModified();
        if (originalLength <= 0 || originalLength > ARCHIVE_LIMIT) {
            throw new IOException("Project archives must be nonempty and no larger than 8 GiB.");
        }
        JSONObject index;
        try (ZipFile zip = new ZipFile(archive)) {
            Map<String, ZipEntry> members = inspectMembers(zip, originalLength);
            ZipEntry indexEntry = members.get("index.json");
            if (indexEntry == null || indexEntry.isDirectory()) throw new IOException("The archive is missing index.json.");
            Budget actual = new Budget();
            ByteArrayOutputStream indexBytes = new ByteArrayOutputStream((int) Math.min(indexEntry.getSize(), BUFFER_SIZE));
            Fingerprint indexFingerprint = consume(zip.getInputStream(indexEntry), INDEX_LIMIT,
                    indexEntry.getSize(), actual, indexBytes);
            verifyZipFingerprint(indexEntry, indexFingerprint);
            ZipEntry directory = members.get("media/");
            if (directory != null) {
                verifyZipFingerprint(directory, consume(zip.getInputStream(directory), 0, 0, actual, null));
            }
            try {
                index = ProjectJson.parseObject(new String(indexBytes.toByteArray(), StandardCharsets.UTF_8));
            } catch (JSONException invalid) {
                throw new IOException("The archive's index.json is not valid JSON.", invalid);
            }
            Map<String, Media> expected = inspectIndex(index);
            for (String name : members.keySet()) {
                if (!name.equals("index.json") && !name.equals("media/") && !expected.containsKey(name)) {
                    throw new IOException("The archive contains an unindexed member: " + name);
                }
            }
            for (Media media : expected.values()) {
                ZipEntry member = members.get(media.path);
                if (member == null || member.isDirectory()) throw new IOException("The archive is missing an original: " + media.path);
                if (member.getSize() != media.bytes) throw new IOException("An original's ZIP size differs from the index: " + media.path);
                Fingerprint fingerprint = consume(zip.getInputStream(member), MEDIA_LIMIT,
                        media.bytes, actual, null);
                verifyZipFingerprint(member, fingerprint);
                if (!fingerprint.sha256.equalsIgnoreCase(media.sha256)) {
                    throw new IOException("An original does not match its indexed SHA-256: " + media.path);
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The project ZIP contains an invalid filename or structure.", invalid);
        }
        Fingerprint archiveFingerprint = consume(new FileInputStream(archive), ARCHIVE_LIMIT,
                originalLength, null, null);
        if (archive.length() != originalLength || archive.lastModified() != originalModified) {
            throw new IOException("The archive changed while it was being verified. Try again after the write finishes.");
        }
        return new Verified(archive, index, archiveFingerprint.sha256, archiveFingerprint.bytes);
    }

    /**
     * Writes only index.json and indexed originals, then verifies the entire result.
     * The caller supplies an owned staging destination; partial output is retained if
     * writing fails so the caller can handle its cleanup or diagnostics explicitly.
     */
    public static Verified build(File destination, JSONObject index, MediaSource source) throws IOException {
        if (destination == null || source == null || index == null) throw new IOException("An archive destination, index, and media source are required.");
        JSONObject snapshot;
        byte[] indexBytes;
        try {
            snapshot = ProjectJson.parseObject(index.toString());
            indexBytes = snapshot.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException | RuntimeException invalid) {
            throw new IOException("The project index could not be encoded.", invalid);
        }
        if (indexBytes.length > INDEX_LIMIT) throw new IOException("The project index exceeds the 32 MiB archive limit.");
        Map<String, Media> media = inspectIndex(snapshot);
        if (media.size() + 1 > MEMBER_LIMIT) throw new IOException("The archive would exceed the 10,000-member limit.");
        long declaredTotal = indexBytes.length;
        for (Media item : media.values()) declaredTotal = addWithinLimit(declaredTotal, item.bytes, ARCHIVE_LIMIT, "The archive's uncompressed content exceeds 8 GiB.");
        Budget actual = new Budget();
        try (ZipOutputStream zip = new ZipOutputStream(new ArchiveOutput(new FileOutputStream(destination)))) {
            // Media is already encoded; level 0 avoids spending battery recompressing it.
            zip.setLevel(0);
            ZipEntry indexEntry = new ZipEntry("index.json");
            indexEntry.setLastModifiedTime(FileTime.from(parseInstant(requiredString(snapshot, "createdAt"), "project creation time")));
            zip.putNextEntry(indexEntry);
            actual.add(indexBytes.length);
            zip.write(indexBytes);
            zip.closeEntry();
            for (Media item : media.values()) {
                ZipEntry entry = new ZipEntry(item.path);
                entry.setLastModifiedTime(FileTime.from(item.startedAt));
                zip.putNextEntry(entry);
                Fingerprint copied = consume(source.open(item.path), MEDIA_LIMIT, item.bytes, actual, zip);
                if (!copied.sha256.equalsIgnoreCase(item.sha256)) {
                    throw new IOException("An original changed or no longer matches its indexed SHA-256: " + item.path);
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("A project timestamp or filename cannot be stored in the archive.", invalid);
        }
        return verify(destination);
    }

    private static Map<String, ZipEntry> inspectMembers(ZipFile zip, long archiveBytes) throws IOException {
        Map<String, ZipEntry> members = new LinkedHashMap<>();
        Set<String> foldedNames = new HashSet<>();
        long declaredTotal = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            if (members.size() >= MEMBER_LIMIT) throw new IOException("The archive exceeds the 10,000-member limit.");
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName();
            safeMemberName(name);
            if (!foldedNames.add(folded(name)) || members.put(name, entry) != null) {
                throw new IOException("The archive has duplicate or case-colliding members: " + name);
            }
            if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                throw new IOException("The archive uses an unsupported ZIP compression method.");
            }
            long size = entry.getSize();
            long compressedSize = entry.getCompressedSize();
            if (size < 0 || compressedSize < 0 || compressedSize > archiveBytes || entry.getCrc() < 0) {
                throw new IOException("A ZIP member has missing or invalid size/CRC information: " + name);
            }
            if (entry.isDirectory()) {
                if (!name.equals("media/") || size != 0 || entry.getCrc() != 0) {
                    throw new IOException("The only optional directory member is an empty media/ directory.");
                }
            } else if (name.equals("index.json")) {
                if (size == 0 || size > INDEX_LIMIT) throw new IOException("The archive index must be nonempty and no larger than 32 MiB.");
            } else if (size > MEDIA_LIMIT) {
                throw new IOException("An individual original exceeds the 4 GiB limit: " + name);
            }
            declaredTotal = addWithinLimit(declaredTotal, size, ARCHIVE_LIMIT, "The ZIP declares more than 8 GiB of uncompressed content.");
        }
        return members;
    }

    /** Validates the index fields needed to bind archive members to original files. */
    private static Map<String, Media> inspectIndex(JSONObject index) throws IOException {
        try {
            if (requiredLong(index, "schemaVersion") != 1) throw new IOException("Unsupported project archive schema.");
            requiredString(index, "id");
            requiredString(index, "name");
            requiredString(index, "createdAt");
            if (requiredLong(index, "revision") < 0) throw new IOException("The index revision cannot be negative.");
            Object rawEntries = index.get("entries");
            if (!(rawEntries instanceof JSONArray)) throw new IOException("The project index must contain an entries array.");
            JSONArray entries = (JSONArray) rawEntries;
            Map<String, Media> result = new LinkedHashMap<>();
            Set<String> entryIds = new HashSet<>();
            Set<String> mediaIds = new HashSet<>();
            Set<String> foldedPaths = new HashSet<>();
            long declaredTotal = 0;
            for (int i = 0; i < entries.length(); i++) {
                Object rawEntry = entries.get(i);
                if (!(rawEntry instanceof JSONObject)) throw new IOException("Every entry must be a JSON object.");
                JSONObject entry = (JSONObject) rawEntry;
                if (!entryIds.add(requiredString(entry, "id"))) throw new IOException("The index contains duplicate entry IDs.");
                requiredString(entry, "createdAt");
                validateEntryContent(entry);
                Object rawMedia = entry.get("media");
                if (!(rawMedia instanceof JSONArray)) throw new IOException("Every entry must contain a media array.");
                JSONArray items = (JSONArray) rawMedia;
                for (int j = 0; j < items.length(); j++) {
                    if (result.size() >= MEMBER_LIMIT - 1) throw new IOException("The index references too many media members.");
                    Object rawItem = items.get(j);
                    if (!(rawItem instanceof JSONObject)) throw new IOException("Every media item must be a JSON object.");
                    JSONObject item = (JSONObject) rawItem;
                    if (!mediaIds.add(requiredString(item, "id"))) throw new IOException("The index contains duplicate media IDs.");
                    String kind = requiredString(item, "kind");
                    if (!kind.equals("audio") && !kind.equals("photo") && !kind.equals("video")) throw new IOException("The index contains an unsupported media kind.");
                    String path = requiredString(item, "path");
                    String filename = requiredString(item, "filename");
                    safeMemberName(path);
                    if (!path.startsWith("media/") || path.equals("media/") || !path.equals("media/" + filename)) {
                        throw new IOException("An indexed original must have a matching flat relative media path.");
                    }
                    if (!foldedPaths.add(folded(path))) throw new IOException("The index has duplicate or case-colliding media paths.");
                    Instant startedAt = parseInstant(requiredString(item, "startedAt"), "media capture start time");
                    requiredString(item, "endedAt");
                    if (requiredLong(item, "durationMs") < 0) throw new IOException("Media duration cannot be negative.");
                    long bytes = requiredLong(item, "bytes");
                    if (bytes <= 0 || bytes > MEDIA_LIMIT) throw new IOException("Each original must contain 1 byte to 4 GiB.");
                    declaredTotal = addWithinLimit(declaredTotal, bytes, ARCHIVE_LIMIT, "The index references more than 8 GiB of originals.");
                    String hash = requiredString(item, "sha256");
                    if (!hash.matches("[0-9a-fA-F]{64}")) throw new IOException("An original has an invalid SHA-256 checksum.");
                    if (!(item.get("metadata") instanceof JSONObject)) throw new IOException("Media metadata must be a JSON object.");
                    requiredString(item, "status");
                    EntryAnnotations.validateMarkers(item);
                    result.put(path, new Media(path, bytes, hash, startedAt));
                }
            }
            return result;
        } catch (JSONException invalid) {
            throw new IOException("The project index is missing required archive fields.", invalid);
        }
    }

    /** Optional beta.3 content; media-only entries retain their earlier schema. */
    static void validateEntryContent(JSONObject entry) throws IOException {
        if (entry == null) throw new IOException("Every entry must be a JSON object.");
        EntryAnnotations.validateTags(entry);
        if (entry.has("title")) {
            Object title = entry.opt("title");
            if (!(title instanceof String) || ((String) title).length() > 200) {
                throw new IOException("An entry title must be a string of at most 200 characters.");
            }
        }
        if (entry.has("text")) {
            String text = requiredString(entry, "text");
            if (text.length() > 100_000 || text.codePoints().allMatch(codePoint ->
                    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) {
                throw new IOException("Entry text must be nonblank and at most 100,000 characters.");
            }
            parseInstant(requiredString(entry, "textCreatedAt"), "text creation time");
            parseInstant(requiredString(entry, "textUpdatedAt"), "text update time");
            if (!(entry.opt("textMetadata") instanceof JSONObject)) {
                throw new IOException("Entry text metadata must be a JSON object.");
            }
        } else if (entry.has("textCreatedAt") || entry.has("textUpdatedAt") || entry.has("textMetadata")) {
            throw new IOException("An entry with text timestamps or metadata must contain text.");
        }
        if (entry.has("updatedAt")) {
            parseInstant(requiredString(entry, "updatedAt"), "entry update time");
        }
    }

    private static String requiredString(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw new IOException("The index field ‘" + key + "’ must be a nonempty string.");
        return (String) value;
    }

    private static long requiredLong(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new IOException("The index field ‘" + key + "’ must be an integer.");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { throw new IOException("The index field ‘" + key + "’ must be a finite 64-bit integer.", invalid); }
    }

    private static Instant parseInstant(String text, String label) throws IOException {
        try {
            Instant instant = Instant.parse(text);
            instant.toEpochMilli();
            return instant;
        } catch (RuntimeException invalid) { throw new IOException("The archive contains an invalid " + label + ".", invalid); }
    }

    private static void safeMemberName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw new IOException("An unsafe ZIP member name was rejected.");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) throw new IOException("Control characters are not allowed in ZIP member names.");
        }
        if (name.equals("index.json") || name.equals("media/")) return;
        if (!name.startsWith("media/")) throw new IOException("The archive contains an unexpected member: " + name);
        String filename = name.substring("media/".length());
        if (filename.isEmpty() || filename.contains("/") || filename.equals(".") || filename.equals("..")
                || filename.endsWith(".") || filename.endsWith(" ")) {
            throw new IOException("Originals must use safe, flat filenames inside media/.");
        }
    }

    private static String folded(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** Owns and closes the input; never closes the caller's optional output. */
    private static Fingerprint consume(InputStream input, long limit, long expectedBytes,
                                       Budget budget, OutputStream output) throws IOException {
        if (input == null) throw new IOException("An original or ZIP member could not be opened.");
        try (InputStream owned = input) {
            MessageDigest sha = sha256();
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[BUFFER_SIZE];
            long bytes = 0;
            int count;
            while ((count = owned.read(buffer)) != -1) {
                checkInterrupted();
                bytes = addWithinLimit(bytes, count, Math.min(limit, expectedBytes), "A member contains more bytes than its declared or permitted size.");
                if (budget != null) budget.add(count);
                sha.update(buffer, 0, count);
                crc.update(buffer, 0, count);
                if (output != null) output.write(buffer, 0, count);
            }
            if (bytes != expectedBytes) throw new IOException("A member's actual size differs from its declared size.");
            return new Fingerprint(bytes, crc.getValue(), hex(sha.digest()));
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("ZIP publication was paused; pending originals remain available for retry.");
    }

    private static void verifyZipFingerprint(ZipEntry entry, Fingerprint fingerprint) throws IOException {
        if (entry.getSize() != fingerprint.bytes || entry.getCrc() != fingerprint.crc) {
            throw new IOException("A ZIP member failed its size or CRC check: " + entry.getName());
        }
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable.", impossible); }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] & 0xff) >>> 4];
            result[i * 2 + 1] = digits[bytes[i] & 0x0f];
        }
        return new String(result);
    }

    private static long addWithinLimit(long current, long amount, long limit, String message) throws IOException {
        if (amount < 0 || current < 0 || current > limit || amount > limit - current) throw new IOException(message);
        return current + amount;
    }

    private static final class Media {
        final String path;
        final long bytes;
        final String sha256;
        final Instant startedAt;
        Media(String path, long bytes, String sha256, Instant startedAt) {
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.startedAt = startedAt;
        }
    }

    private static final class Fingerprint {
        final long bytes;
        final long crc;
        final String sha256;
        Fingerprint(long bytes, long crc, String sha256) { this.bytes = bytes; this.crc = crc; this.sha256 = sha256; }
    }

    private static final class Budget {
        long bytes;
        void add(long amount) throws IOException {
            bytes = addWithinLimit(bytes, amount, ARCHIVE_LIMIT, "The archive's actual uncompressed content exceeds 8 GiB.");
        }
    }

    private static final class ArchiveOutput extends OutputStream {
        private final OutputStream delegate;
        private long bytes;
        ArchiveOutput(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int value) throws IOException {
            checkInterrupted();
            bytes = addWithinLimit(bytes, 1, ARCHIVE_LIMIT, "The written archive exceeds 8 GiB.");
            delegate.write(value);
        }
        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
            checkInterrupted();
            bytes = addWithinLimit(bytes, length, ARCHIVE_LIMIT, "The written archive exceeds 8 GiB.");
            delegate.write(buffer, offset, length);
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
