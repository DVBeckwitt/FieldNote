package com.fieldnote.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared validation for optional entry tags and audio-relative markers. */
final class EntryAnnotations {
    private static final int TAG_LIMIT = 24;
    private static final int TAG_LENGTH = 40;
    private static final int MARKER_LIMIT = 1_000;
    private static final int LABEL_LENGTH = 200;

    private EntryAnnotations() { }

    static JSONArray normalizeTags(String commaSeparated) throws IOException {
        if (commaSeparated == null) throw new IOException("Tags must be text.");
        JSONArray input = new JSONArray();
        for (String tag : commaSeparated.split(",", -1)) input.put(tag);
        return normalizeTags(input);
    }

    static JSONArray normalizeTags(JSONArray tags) throws IOException {
        if (tags == null) throw new IOException("Tags must be an array of strings.");
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tags.length(); i++) {
            Object value = tags.opt(i);
            if (!(value instanceof String)) throw new IOException("Every tag must be a string.");
            String tag = normalizedTag((String) value);
            if (tag.length() > TAG_LENGTH) throw new IOException("A tag can contain at most 40 characters.");
            if (tag.isEmpty() || !seen.add(tag)) continue;
            if (result.length() >= TAG_LIMIT) throw new IOException("An entry can have at most 24 tags.");
            result.put(tag);
        }
        return result;
    }

    /** Validates stored tags without rewriting their spelling or order. */
    static void validateTags(JSONObject entry) throws IOException {
        if (entry == null) throw new IOException("Every entry must be a JSON object.");
        if (!entry.has("tags")) return;
        Object raw = entry.opt("tags");
        if (!(raw instanceof JSONArray)) throw new IOException("Entry tags must be an array.");
        JSONArray tags = (JSONArray) raw;
        if (tags.length() > TAG_LIMIT) throw new IOException("An entry can have at most 24 tags.");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tags.length(); i++) {
            Object value = tags.opt(i);
            if (!(value instanceof String) || ((String) value).length() > TAG_LENGTH) {
                throw new IOException("Every stored tag must be a string of at most 40 characters.");
            }
            String key = normalizedTag((String) value);
            if (key.isEmpty()) throw new IOException("Stored tags cannot be blank.");
            if (!seen.add(key)) throw new IOException("An entry contains duplicate tags.");
        }
    }

    static void validateMarkers(JSONObject media) throws IOException {
        if (media == null) throw new IOException("Every media item must be a JSON object.");
        if (!media.has("markers")) return;
        if (!"audio".equals(media.opt("kind"))) throw new IOException("Only audio originals can contain markers.");
        Object raw = media.opt("markers");
        if (!(raw instanceof JSONArray)) throw new IOException("Audio markers must be an array.");
        validateMarkerArray((JSONArray) raw, requiredString(media, "id"), markerLimit(media));
    }

    static void validateTranscript(JSONObject media) throws IOException {
        if (!media.has("transcript")) return;
        JSONObject transcript = media.optJSONObject("transcript");
        if (!"audio".equals(media.opt("kind")) || transcript == null) {
            throw new IOException("Only audio originals can have a transcript object.");
        }
        String status = requiredString(transcript, "status");
        if ("complete".equals(status)) {
            if (requiredString(transcript, "text").length() > OfflineTranscriber.MAX_TRANSCRIPT_CHARS) {
                throw new IOException("The audio transcript exceeds the supported length.");
            }
        } else if (transcript.has("text") || !("pending".equals(status) || "no_speech".equals(status) || "failed".equals(status))) {
            throw new IOException("The audio transcript has an invalid status or text.");
        }
    }

    /** A maximum of -1 permits still-recording offsets whose final duration is unknown. */
    static void validateMarkerArray(JSONArray markers, String mediaId, long maxOffsetMs) throws IOException {
        requireNonblank(mediaId, "mediaId");
        if (maxOffsetMs < -1) throw new IOException("The marker duration limit is invalid.");
        if (markers == null) throw new IOException("Audio markers must be an array.");
        if (markers.length() > MARKER_LIMIT) throw new IOException("An audio original can have at most 1,000 markers.");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < markers.length(); i++) {
            Object raw = markers.opt(i);
            if (!(raw instanceof JSONObject)) throw new IOException("Every audio marker must be a JSON object.");
            JSONObject marker = (JSONObject) raw;
            String id = requiredString(marker, "id");
            if (!ids.add(id)) throw new IOException("An audio original contains duplicate marker IDs.");
            if (!mediaId.equals(requiredString(marker, "mediaId"))) {
                throw new IOException("An audio marker refers to a different original.");
            }
            long offset = requiredLong(marker, "offsetMs");
            if (offset < 0 || (maxOffsetMs >= 0 && offset > maxOffsetMs)) {
                throw new IOException("A marker offset is outside its audio original.");
            }
            Object label = marker.opt("label");
            if (!(label instanceof String) || ((String) label).length() > LABEL_LENGTH) {
                throw new IOException("A marker label must be a string of at most 200 characters.");
            }
            validateInstant(requiredString(marker, "createdAt"), "marker creation time");
            if (marker.has("updatedAt")) validateInstant(requiredString(marker, "updatedAt"), "marker update time");
        }
    }

    static JSONObject marker(String mediaId, String id, long offsetMs, String label, long markedAt) throws IOException {
        requireNonblank(mediaId, "mediaId");
        requireNonblank(id, "id");
        if (label == null) throw new IOException("A marker label must be a string.");
        try {
            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("mediaId", mediaId);
            result.put("offsetMs", offsetMs);
            result.put("label", trimWhitespace(label));
            result.put("createdAt", Instant.ofEpochMilli(markedAt).toString());
            validateMarkerArray(new JSONArray().put(result), mediaId, -1);
            return result;
        } catch (JSONException invalid) {
            throw new IOException("The audio marker could not be encoded.", invalid);
        }
    }

    /** Optional encoded duration is legacy metadata; malformed values do not invalidate it. */
    static long markerLimit(JSONObject media) throws IOException {
        if (media == null) throw new IOException("The audio original is missing.");
        long duration = requiredLong(media, "durationMs");
        if (duration < 0) throw new IOException("Audio duration cannot be negative.");
        Object rawMetadata = media.opt("metadata");
        if (rawMetadata instanceof JSONObject) {
            JSONObject metadata = (JSONObject) rawMetadata;
            if (metadata.has("encodedDurationMs")) {
                try {
                    long encoded = requiredLong(metadata, "encodedDurationMs");
                    if (encoded >= 0) duration = Math.max(duration, encoded);
                } catch (IOException ignored) { /* Preserve compatibility with opaque older metadata. */ }
            }
        }
        return duration;
    }

    private static String normalizedTag(String raw) throws IOException {
        StringBuilder result = new StringBuilder();
        boolean space = false;
        for (int position = 0; position < raw.length();) {
            int codePoint = raw.codePointAt(position);
            position += Character.charCount(codePoint);
            if (isWhitespace(codePoint)) {
                if (result.length() > 0) space = true;
                continue;
            }
            if (codePoint == ',' || Character.isISOControl(codePoint)) {
                throw new IOException("Tags cannot contain commas or non-whitespace control characters.");
            }
            if (space) result.append(' ');
            result.appendCodePoint(codePoint);
            space = false;
        }
        String tag = result.toString();
        int start = 0;
        while (start < tag.length() && tag.charAt(start) == '#') {
            start++;
            while (start < tag.length() && tag.charAt(start) == ' ') start++;
        }
        return Normalizer.normalize(tag.substring(start).toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    private static String trimWhitespace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isWhitespace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && isWhitespace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }

    /** Unicode White_Space, including NEL and nonbreaking spaces. */
    private static boolean isWhitespace(int codePoint) {
        return (codePoint >= 0x09 && codePoint <= 0x0d) || codePoint == 0x20 || codePoint == 0x85
                || codePoint == 0xa0 || codePoint == 0x1680 || (codePoint >= 0x2000 && codePoint <= 0x200a)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f
                || codePoint == 0x205f || codePoint == 0x3000;
    }

    private static String requiredString(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new IOException("The annotation field '" + key + "' must be a string.");
        requireNonblank((String) value, key);
        return (String) value;
    }

    private static void requireNonblank(String value, String key) throws IOException {
        if (value == null || trimWhitespace(value).isEmpty()) throw new IOException("The annotation field '" + key + "' cannot be blank.");
    }

    private static long requiredLong(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new IOException("The annotation field '" + key + "' must be an integer.");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new IOException("The annotation field '" + key + "' must be a finite 64-bit integer.", invalid);
        }
    }

    private static void validateInstant(String value, String label) throws IOException {
        try { Instant.parse(value).toEpochMilli(); }
        catch (RuntimeException invalid) { throw new IOException("The annotation contains an invalid " + label + ".", invalid); }
    }
}
