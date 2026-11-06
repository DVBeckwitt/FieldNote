package com.fieldnote.app;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;

/** Bounds JSON structure before Android's recursive JSONObject parser allocates a tree. */
final class ProjectJson {
    // Compatibility ceilings for every project/index/journal document. The byte limit
    // alone cannot bound recursive depth or the overhead of many small JSON objects.
    // Count names, scalar values, and both container delimiters, including unknown fields.
    static final int MAX_DEPTH = 64;
    static final int MAX_TOKENS = 250_000;

    private ProjectJson() { }

    /** Unsupported complexity does not establish corruption or authorize ZIP recovery. */
    static final class LimitExceededException extends IOException {
        LimitExceededException(String message) { super(message); }
    }

    static JSONObject parseObject(String text) throws IOException, JSONException {
        validateStructure(text);
        return new JSONObject(text);
    }

    private static void validateStructure(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            // Keep Android's non-lenient token grammar. Some platform syntax quirks
            // remain; this is bounded parsing rather than a separate RFC validator.
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("A project JSON document must be an object.");
            int depth = 0;
            int tokens = 0;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Project JSON reading was paused.");
                JsonToken token = reader.peek();
                if (token == JsonToken.END_DOCUMENT) break;
                if (++tokens > MAX_TOKENS) throw new LimitExceededException("The project JSON exceeds the 250,000-token complexity limit.");
                switch (token) {
                    case BEGIN_OBJECT:
                    case BEGIN_ARRAY:
                        if (++depth > MAX_DEPTH) throw new LimitExceededException("The project JSON exceeds the 64-level nesting limit.");
                        if (token == JsonToken.BEGIN_OBJECT) reader.beginObject();
                        else reader.beginArray();
                        break;
                    case END_OBJECT:
                        reader.endObject();
                        depth--;
                        break;
                    case END_ARRAY:
                        reader.endArray();
                        depth--;
                        break;
                    case NAME:
                        reader.nextName();
                        break;
                    case STRING:
                    case NUMBER:
                        reader.nextString(); // Preserve numeric text; do not coerce large integers.
                        break;
                    case BOOLEAN:
                        reader.nextBoolean();
                        break;
                    case NULL:
                        reader.nextNull();
                        break;
                    default:
                        throw new IOException("The project JSON contains an unexpected token.");
                }
            }
            if (depth != 0) throw new IOException("The project JSON contains an unfinished object or array.");
        } catch (IllegalStateException | NumberFormatException invalid) {
            throw new IOException("The project JSON contains invalid syntax.", invalid);
        }
    }
}
