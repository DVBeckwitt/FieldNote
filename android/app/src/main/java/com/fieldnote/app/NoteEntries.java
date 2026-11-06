package com.fieldnote.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Entry snapshots and display values, independent of the current project and UI filters. */
final class NoteEntries {
    private NoteEntries() {}

    /**
     * The caller selects eligible pending entries; null excludes them (for example, in Files).
     * Unchanged committed entries retain their references. Merged and pending entries are copied
     * so adding unpublished media never changes the saved index or staging snapshot.
     */
    static List<JSONObject> snapshot(JSONObject project, JSONArray pendingEntries, boolean newestFirst) {
        ArrayList<JSONObject> result = new ArrayList<>();
        JSONArray array = project == null ? null : project.optJSONArray("entries");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONObject(i) != null) {
                    result.add(array.optJSONObject(i));
                }
            }
        }
        if (pendingEntries != null) {
            for (int i = 0; i < pendingEntries.length(); i++) {
                JSONObject pending = pendingEntries.optJSONObject(i);
                if (pending == null) {
                    continue;
                }
                try {
                    int found = -1;
                    for (int n = 0; n < result.size(); n++) {
                        if (result.get(n).optString("id").equals(pending.optString("id"))) {
                            found = n;
                            break;
                        }
                    }
                    if (found < 0) {
                        result.add(new JSONObject(pending.toString()));
                        continue;
                    }
                    JSONObject entry = new JSONObject(result.get(found).toString());
                    result.set(found, entry);
                    JSONArray media = entry.optJSONArray("media");
                    if (media == null) {
                        media = new JSONArray();
                        entry.put("media", media);
                    }
                    JSONArray incoming = pending.optJSONArray("media");
                    if (incoming == null) {
                        continue;
                    }
                    for (int m = 0; m < incoming.length(); m++) {
                        JSONObject item = incoming.getJSONObject(m);
                        boolean exists = false;
                        for (int n = 0; n < media.length(); n++) {
                            if (media.getJSONObject(n).optString("id").equals(item.optString("id"))) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            media.put(new JSONObject(item.toString()));
                            entry.put("_pending", true);
                        }
                    }
                } catch (JSONException ignored) {
                    // A concurrently completed staging snapshot can be refreshed.
                }
            }
        }
        // Reverse the stable ascending sort to preserve the existing newest-first equal-time order.
        result.sort(Comparator.comparingLong(NoteEntries::entryTime));
        if (newestFirst) {
            java.util.Collections.reverse(result);
        }
        return result;
    }

    static long entryTime(JSONObject entry) {
        long earliest = parseTime(entry.optString("createdAt"));
        JSONArray media = entry.optJSONArray("media");
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject item = media.optJSONObject(i);
                if (item != null) {
                    earliest = Math.min(earliest, parseTime(item.optString("startedAt")));
                }
            }
        }
        return earliest;
    }

    static String tagText(JSONObject entry) {
        JSONArray tags = entry.optJSONArray("tags");
        ArrayList<String> values = new ArrayList<>();
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                values.add("#" + tags.optString(i));
            }
        }
        return String.join("  ", values);
    }

    static int markerCount(JSONObject entry) {
        int count = 0;
        JSONArray media = entry.optJSONArray("media");
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject clip = media.optJSONObject(i);
                if (clip == null || !"audio".equals(clip.optString("kind"))) {
                    continue;
                }
                JSONArray markers = clip.optJSONArray("markers");
                if (markers != null) {
                    count += markers.length();
                }
            }
        }
        return count;
    }

    static boolean matchesNote(JSONObject entry, String searchQuery, String selectedTag, boolean markersOnly) {
        if (markersOnly && markerCount(entry) == 0) {
            return false;
        }
        JSONArray tags = entry.optJSONArray("tags");
        boolean tagMatch = selectedTag.isEmpty();
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                if (selectedTag.equalsIgnoreCase(tags.optString(i))) {
                    tagMatch = true;
                }
            }
        }
        if (!tagMatch) {
            return false;
        }
        StringBuilder haystack = new StringBuilder(entryLabel(entry)).append(' ').append(entry.optString("text"))
                .append(' ').append(tagText(entry));
        JSONArray media = entry.optJSONArray("media");
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject clip = media.optJSONObject(i);
                if (clip == null || !"audio".equals(clip.optString("kind"))) {
                    continue;
                }
                JSONArray markers = clip.optJSONArray("markers");
                if (markers != null) {
                    for (int m = 0; m < markers.length(); m++) {
                        JSONObject marker = markers.optJSONObject(m);
                        if (marker != null) {
                            haystack.append(' ').append(marker.optString("label"));
                        }
                    }
                }
            }
        }
        String searchable = haystack.toString().toLowerCase(Locale.ROOT);
        for (String word : searchQuery.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!searchable.contains(word)) {
                return false;
            }
        }
        return true;
    }

    static String entryLabel(JSONObject entry) {
        String title = entry.optString("title").trim();
        if (!title.isEmpty()) {
            return title;
        }
        String note = entry.optString("text").trim();
        if (!note.isEmpty()) {
            String firstLine = note.split("\\R", 2)[0];
            return firstLine.length() > 120 ? firstLine.substring(0, 120) + "…" : firstLine;
        }
        int audio = 0, photos = 0, video = 0;
        long duration = 0;
        JSONArray media = entry.optJSONArray("media");
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject item = media.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                switch (item.optString("kind")) {
                    case "audio":
                        audio++;
                        duration += item.optLong("durationMs");
                        break;
                    case "photo":
                        photos++;
                        break;
                    case "video":
                        video++;
                        break;
                    default:
                        break;
                }
            }
        }
        ArrayList<String> parts = new ArrayList<>();
        if (audio > 0) {
            parts.add("Audio " + duration(duration));
        }
        if (photos > 0) {
            parts.add(photos == 1 ? "Photo" : photos + " photos");
        }
        if (video > 0) {
            parts.add(video == 1 ? "Video" : video + " videos");
        }
        return parts.isEmpty() ? "Note" : String.join(" + ", parts);
    }

    static String duration(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static long parseTime(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
