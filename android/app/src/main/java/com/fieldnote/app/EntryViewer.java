package com.fieldnote.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Views original linked media without leaving the notebook or modifying the originals. */
public final class EntryViewer extends Dialog {
    public interface AttachmentListener { void onAttach(String kind, String entryId); }
    public interface EditListener { void onEdit(String entryId); }

    // Stable native view IDs also allow acceptance tests to inspect the actual media widgets.
    public static final int VIEWER_ROOT_ID = 0x00f10001;
    public static final int PHOTO_ID = 0x00f10002;
    public static final int AUDIO_PLAY_ID = 0x00f10003;
    public static final int AUDIO_SEEK_ID = 0x00f10004;
    public static final int VIDEO_VIEW_ID = 0x00f10005;
    public static final int VIDEO_PLAY_ID = 0x00f10006;
    public static final int VIDEO_SEEK_ID = 0x00f10007;
    public static final int METADATA_BUTTON_ID = 0x00f10008;
    public static final int METADATA_CONTENT_ID = 0x00f10009;
    public static final int CLOSE_ID = 0x00f1000a;
    public static final int AUDIO_STATUS_ID = 0x00f1000b;
    public static final int VIDEO_STATUS_ID = 0x00f1000c;
    public static final int ENTRY_TITLE_ID = 0x00f1000d;
    public static final int ENTRY_TEXT_ID = 0x00f1000e;
    public static final int EDIT_ID = 0x00f1000f;
    public static final int TEXT_METADATA_BUTTON_ID = 0x00f10010;
    public static final int ENTRY_TAGS_ID = 0x00f10011;
    public static final int MARKERS_SECTION_ID = 0x00f10012;
    public static final int MARKER_PLAY_ID = 0x00f10013;
    public static final int MARKER_EDIT_ID = 0x00f10014;
    public static final int ADD_MARKER_ID = 0x00f10015;
    public static final int MARKER_LABEL_INPUT_ID = 0x00f10016;
    public static final int MARKER_SAVE_ID = 0x00f10017;
    public static final int MARKER_DELETE_ID = 0x00f10018;
    public static final int MARKER_STATUS_ID = 0x00f10019;
    static final int TRANSCRIPT_BODY_ID = 0x00f1001a;
    static final int TRANSCRIPT_READ_ID = 0x00f1001b;
    private static final int TRANSCRIPT_PREVIEW_CHARS = 1200;
    private static final int TRANSCRIPT_SECTION_CHARS = 2400;

    private final Activity activity;
    private final ProjectStore store;
    private final Uri projectUri;
    private final JSONObject entry;
    private final boolean dark;
    private final AttachmentListener attachments;
    private final EditListener editor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler editCompletion = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService edits = Executors.newSingleThreadExecutor();
    private final List<MediaCard> cards = new ArrayList<>();
    private final List<Bitmap> bitmaps = new ArrayList<>();
    private final AudioManager audioManager;
    private final AudioFocusRequest focus;
    private final int background, panel, inset, control, border, accent, ink, muted;
    private boolean closed;
    private boolean focusHeld;
    private AlertDialog metadataDialog;
    private AlertDialog markerDialog;
    private AlertDialog transcriptDialog;
    private Runnable annotationsChanged;

    /** Called on the main thread after a verified edit, including edits finishing after dismissal. */
    public void setOnAnnotationsChangedListener(Runnable listener) { annotationsChanged = listener; }

    /** Refreshes transcript fields from the host's verified snapshot without disturbing playback. */
    void refreshTranscripts(Uri project, JSONObject updatedProject) {
        if (!live() || !projectUri.equals(project) || updatedProject == null) return;
        JSONArray entries = updatedProject.optJSONArray("entries");
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject updatedEntry = entries.optJSONObject(i);
            if (updatedEntry == null || !entry.optString("id").equals(updatedEntry.optString("id"))) continue;
            JSONArray updatedMedia = updatedEntry.optJSONArray("media");
            if (updatedMedia == null) return;
            Map<String, JSONObject> byId = new HashMap<>();
            for (int m = 0; m < updatedMedia.length(); m++) {
                JSONObject media = updatedMedia.optJSONObject(m);
                if (media != null) byId.put(media.optString("id"), media);
            }
            for (MediaCard card : cards) {
                JSONObject media = byId.get(card.media.optString("id"));
                if (card.transcriptSection != null && media != null
                        && card.media.optString("sha256").equals(media.optString("sha256"))) {
                    card.renderTranscript(media.optJSONObject("transcript"));
                }
            }
            return;
        }
    }

    public EntryViewer(Activity activity, ProjectStore store, Uri projectUri, JSONObject entry,
                       boolean dark, AttachmentListener callback) {
        this(activity, store, projectUri, entry, dark, callback, null);
    }

    public EntryViewer(Activity activity, ProjectStore store, Uri projectUri, JSONObject entry,
                       boolean dark, AttachmentListener callback, EditListener editor) {
        super(activity, dark ? R.style.FieldnoteDark : R.style.FieldnoteLight);
        this.activity = activity;
        this.store = store;
        this.projectUri = projectUri;
        this.entry = entry;
        this.dark = dark;
        this.attachments = callback;
        this.editor = editor;
        background = Color.parseColor(dark ? "#0C1627" : "#F7FAFF");
        panel = Color.parseColor(dark ? "#14243B" : "#FFFFFF");
        inset = Color.parseColor(dark ? "#101E32" : "#F0F6FF");
        control = Color.parseColor(dark ? "#203B60" : "#E3EEFC");
        border = Color.parseColor(dark ? "#29415F" : "#D3E2F6");
        accent = Color.parseColor(dark ? "#91C5FF" : "#2C68B2");
        ink = Color.parseColor(dark ? "#E8F1FF" : "#17314D");
        muted = Color.parseColor(dark ? "#ABC0DB" : "#536C87");
        audioManager = activity.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) stopPlayback();
                }, main).build();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column();
        root.setId(VIEWER_ROOT_ID);
        root.setBackgroundColor(background);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(dp(20) + bars.left, dp(12) + bars.top,
                        dp(20) + bars.right, dp(12) + bars.bottom);
                return insets;
            });
        } else root.setFitsSystemWindows(true);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Entry", 21, ink);
        title.setTypeface(medium());
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        if (editor != null) {
            Button edit = button("Edit");
            edit.setId(EDIT_ID);
            edit.setContentDescription("Edit entry title and text");
            edit.setOnClickListener(view -> {
                dismiss();
                editor.onEdit(entry.optString("id"));
            });
            LinearLayout.LayoutParams editSpace = new LinearLayout.LayoutParams(-2, dp(48));
            editSpace.setMarginEnd(dp(8));
            header.addView(edit, editSpace);
        }
        Button close = quietButton("Close");
        close.setId(CLOSE_ID);
        close.setContentDescription("Close entry");
        close.setOnClickListener(view -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(header);
        TextView date = text(captureTime(entry.optString("createdAt")), 13, muted);
        date.setPadding(0, dp(4), 0, dp(18));
        root.addView(date);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(12));
        LinearLayout content = column();
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        String entryTitle = entry.optString("title").trim();
        String entryText = entry.optString("text");
        if (!entryTitle.isEmpty()) {
            TextView heading = text(entryTitle, 24, ink);
            heading.setId(ENTRY_TITLE_ID);
            heading.setTypeface(medium());
            heading.setTextIsSelectable(true);
            heading.setLineSpacing(dp(2), 1);
            heading.setPadding(0, 0, 0, dp(18));
            content.addView(heading);
        }
        JSONArray tags = entry.optJSONArray("tags");
        if (tags != null && tags.length() > 0) {
            ArrayList<String> labels = new ArrayList<>();
            for (int i = 0; i < tags.length(); i++) {
                String tag = tags.optString(i).trim();
                if (!tag.isEmpty()) labels.add("#" + tag);
            }
            if (!labels.isEmpty()) {
                TextView tagList = text(String.join("   ", labels), 13, accent);
                tagList.setTypeface(medium());
                tagList.setLineSpacing(dp(3), 1);
                tagList.setId(ENTRY_TAGS_ID);
                tagList.setContentDescription("Entry tags: " + String.join(", ", labels));
                tagList.setPadding(0, 0, 0, dp(14));
                content.addView(tagList);
            }
        }
        if (!entryText.trim().isEmpty()) {
            LinearLayout note = column();
            note.setPadding(dp(18), dp(18), dp(18), dp(8));
            note.setBackground(surface(panel, 20, border));
            TextView body = text(entryText, 16, ink);
            body.setId(ENTRY_TEXT_ID);
            body.setTextIsSelectable(true);
            body.setLineSpacing(dp(5), 1);
            body.setPadding(0, 0, 0, dp(10));
            note.addView(body);
            Button metadata = quietButton("Metadata");
            metadata.setId(TEXT_METADATA_BUTTON_ID);
            metadata.setContentDescription("Text metadata");
            metadata.setOnClickListener(view -> showTextMetadata());
            LinearLayout.LayoutParams metadataSpace = new LinearLayout.LayoutParams(-2, dp(48));
            metadataSpace.gravity = Gravity.END;
            note.addView(metadata, metadataSpace);
            content.addView(note, spaced());
        }
        JSONArray media = entry.optJSONArray("media");
        if (media != null) for (int i = 0; i < media.length(); i++) {
            JSONObject item = media.optJSONObject(i);
            if (item == null) continue;
            MediaCard card = new MediaCard(item);
            cards.add(card);
            content.addView(card.layout, spaced());
        }
        if (cards.isEmpty() && entryText.trim().isEmpty() && entryTitle.isEmpty())
            content.addView(text("Empty entry", 16, muted));
        TextView attachLabel = text("Add to this entry", 14, muted);
        attachLabel.setTypeface(medium());
        attachLabel.setPadding(0, dp(14), 0, dp(12));
        content.addView(attachLabel);
        LinearLayout actions = new LinearLayout(activity);
        for (String kind : new String[]{"audio", "photo", "video"}) {
            Button action = button("+ " + (kind.equals("audio") ? "Audio" : kind.equals("photo") ? "Photo" : "Video"));
            action.setContentDescription("Attach " + kind);
            action.setOnClickListener(view -> {
                if (RecordingService.isBusy()) {
                    android.widget.Toast.makeText(activity, "Finish the current recording first.", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                dismiss();
                if (attachments != null) attachments.onAttach(kind, entry.optString("id"));
            });
            LinearLayout.LayoutParams actionSpace = new LinearLayout.LayoutParams(0, dp(52), 1);
            if (!kind.equals("video")) actionSpace.setMarginEnd(dp(8));
            actions.addView(action, actionSpace);
        }
        content.addView(actions);
        setContentView(root);
        for (MediaCard card : cards) load(card);
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setStatusBarColor(background);
            window.setNavigationBarColor(background);
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                if (window.getInsetsController() != null) {
                    int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    window.getInsetsController().setSystemBarsAppearance(dark ? 0 : appearance, appearance);
                }
            } else window.getDecorView().setSystemUiVisibility(dark ? 0
                    : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    /** Called by the host before recording and when the Activity goes into the background. */
    public void stopPlayback() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::stopPlayback);
            return;
        }
        main.removeCallbacks(tick);
        for (MediaCard card : cards) card.releasePlayback();
        abandonFocus();
    }

    @Override public void dismiss() {
        if (!closed) {
            closed = true;
            stopPlayback();
            main.removeCallbacksAndMessages(null);
            io.shutdownNow();
            edits.shutdown(); // Accepted annotation writes retain their storage transaction.
            if (metadataDialog != null) {
                metadataDialog.dismiss();
                metadataDialog = null;
            }
            if (markerDialog != null) { markerDialog.dismiss(); markerDialog = null; }
            if (transcriptDialog != null) { transcriptDialog.dismiss(); transcriptDialog = null; }
            for (MediaCard card : cards) if (card.photo != null) card.photo.setImageDrawable(null);
            // Drop view references; Android may still draw a just-dismissed window for its exit animation.
            bitmaps.clear();
        }
        super.dismiss();
    }

    private boolean live() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    private boolean hasActivePlayback() {
        for (MediaCard card : cards) if (card.wantsPlay) return true;
        return false;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!live() || !hasActivePlayback()) return;
            for (MediaCard card : cards) card.updateProgress();
            if (hasActivePlayback()) main.postDelayed(this, 250);
        }
    };

    private void load(MediaCard card) {
        io.execute(() -> {
            Bitmap bitmap = null;
            try {
                Uri uri = store.mediaUri(projectUri, card.media.optString("path"));
                if (card.photo != null) bitmap = decodePhoto(uri);
                Bitmap ready = bitmap;
                main.post(() -> {
                    if (!live()) { if (ready != null) ready.recycle(); return; }
                    card.uri = uri;
                    card.status.setText("");
                    if (ready != null) {
                        bitmaps.add(ready);
                        card.photo.setImageBitmap(ready);
                        int available = activity.getResources().getDisplayMetrics().widthPixels - dp(72);
                        int height = Math.max(dp(150), Math.min(dp(480), Math.round((float) available * ready.getHeight() / ready.getWidth())));
                        card.photo.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
                    }
                    if (card.play != null) card.play.setEnabled(true);
                    if (card.markerRows != null) card.renderMarkers();
                });
            } catch (Exception | OutOfMemoryError error) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                main.post(() -> { if (live()) card.status.setText("Unable to open this original. " + errorText(error)); });
            }
        });
    }

    private Bitmap decodePhoto(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = input(uri)) { BitmapFactory.decodeStream(input, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("The photo could not be decoded.");
        int limit = Math.min(1400, Math.max(720, activity.getResources().getDisplayMetrics().widthPixels));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > limit) options.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = input(uri)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
        if (bitmap == null) throw new IOException("The photo could not be decoded.");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = input(uri)) {
            orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) { /* A decoded image without readable EXIF can still be displayed. */ }
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(-90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270); break;
            default: return bitmap;
        }
        Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
        if (oriented != bitmap) bitmap.recycle();
        return oriented;
    }

    private InputStream input(Uri uri) throws IOException {
        InputStream stream = activity.getContentResolver().openInputStream(uri);
        if (stream == null) throw new IOException("The file is unavailable.");
        return stream;
    }

    private boolean allowPlayback(MediaCard selected) {
        if (!live() || !isShowing()) return false;
        if (RecordingService.isBusy()) {
            selected.status.setText("Finish recording before playing this media.");
            return false;
        }
        for (MediaCard card : cards) if (card != selected) card.releasePlayback();
        if (!focusHeld) {
            focusHeld = audioManager != null && audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focusHeld) selected.status.setText("Audio is busy. Try Play again.");
        }
        return focusHeld;
    }

    private void abandonFocus() {
        if (focusHeld && audioManager != null) audioManager.abandonAudioFocusRequest(focus);
        focusHeld = false;
    }

    private final class MediaCard {
        final JSONObject media;
        final LinearLayout layout;
        final TextView status;
        final boolean isVideo;
        ImageView photo;
        VideoView video;
        TextView videoPlaceholder;
        Button play;
        SeekBar seek;
        TextView position;
        LinearLayout markerRows;
        Button addMarker;
        TextView markerStatus;
        LinearLayout transcriptSection;
        TextView transcriptBody;
        Button readTranscript;
        String transcriptState;
        String transcriptText = "";
        Uri uri;
        MediaPlayer audio;
        boolean prepared;
        boolean wantsPlay;
        boolean seeking;
        boolean pendingSeek;
        int savedPosition;
        int duration;
        int generation;

        MediaCard(JSONObject media) {
            this.media = media;
            String kind = media.optString("kind");
            isVideo = kind.equals("video");
            layout = column();
            layout.setTag(media.optString("id"));
            layout.setPadding(dp(16), dp(16), dp(16), dp(10));
            layout.setBackground(surface(panel, 20, border));
            LinearLayout caption = column();
            caption.setPadding(0, 0, 0, dp(14));
            TextView kindLabel = text(kind.equals("photo") ? "Photo" : isVideo ? "Video" : "Audio", 15, ink);
            kindLabel.setTypeface(medium());
            caption.addView(kindLabel, new LinearLayout.LayoutParams(-1, -2));
            TextView capturedAt = text(captureTime(media.optString("startedAt")), 12, muted);
            capturedAt.setPadding(0, dp(4), 0, 0);
            caption.addView(capturedAt);
            layout.addView(caption);
            status = text("Opening original…", 13, muted);
            if (kind.equals("photo")) {
                photo = new ImageView(activity);
                photo.setId(PHOTO_ID);
                photo.setContentDescription("Entry photo");
                photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                photo.setBackground(surface(inset, 14, Color.TRANSPARENT));
                photo.setClipToOutline(true);
                layout.addView(photo, new LinearLayout.LayoutParams(-1, dp(260)));
            } else if (kind.equals("audio") || isVideo) {
                if (isVideo) {
                    FrameLayout frame = new FrameLayout(activity);
                    frame.setBackgroundColor(Color.BLACK);
                    video = new VideoView(activity);
                    video.setId(VIDEO_VIEW_ID);
                    video.setContentDescription("Entry video");
                    video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
                    video.setAudioAttributes(attributes());
                    frame.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                    videoPlaceholder = text("Video", 22, Color.WHITE);
                    videoPlaceholder.setGravity(Gravity.CENTER);
                    frame.addView(videoPlaceholder, new FrameLayout.LayoutParams(-1, -1));
                    layout.addView(frame, new LinearLayout.LayoutParams(-1, dp(260)));
                }
                play = primaryButton(isVideo ? "Play video" : "Play audio");
                play.setId(isVideo ? VIDEO_PLAY_ID : AUDIO_PLAY_ID);
                play.setContentDescription(isVideo ? "Play video" : "Play audio");
                play.setEnabled(false);
                play.setOnClickListener(view -> toggle());
                LinearLayout.LayoutParams playSpace = new LinearLayout.LayoutParams(-1, dp(52));
                playSpace.topMargin = isVideo ? dp(12) : 0;
                playSpace.bottomMargin = dp(4);
                layout.addView(play, playSpace);
                seek = new SeekBar(activity);
                seek.setId(isVideo ? VIDEO_SEEK_ID : AUDIO_SEEK_ID);
                seek.setContentDescription(isVideo ? "Seek video" : "Seek audio");
                seek.setProgressTintList(ColorStateList.valueOf(accent));
                seek.setThumbTintList(ColorStateList.valueOf(accent));
                seek.setProgressBackgroundTintList(ColorStateList.valueOf(border));
                seek.setEnabled(false);
                layout.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
                position = text("0:00 / " + durationLabel(media.optLong("durationMs")), 13, muted);
                position.setId(isVideo ? VIDEO_STATUS_ID : AUDIO_STATUS_ID);
                position.setContentDescription(isVideo ? "Video playback position" : "Audio playback position");
                position.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                position.setFontFeatureSettings("tnum");
                layout.addView(position);
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (fromUser) {
                            position.setText(durationLabel(value) + " / " + durationLabel(duration));
                            // Keyboard and accessibility changes have no touch-stop callback.
                            if (!seeking) commitSeek(value);
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
                    @Override public void onStopTrackingTouch(SeekBar bar) {
                        seeking = false;
                        commitSeek(bar.getProgress());
                    }
                });
            }
            layout.addView(status);
            if (kind.equals("audio")) {
                LinearLayout markers = column();
                markers.setId(MARKERS_SECTION_ID);
                markers.setTag(media.optString("id"));
                markers.setContentDescription("Audio markers for " + media.optString("id"));
                TextView heading = text("Markers", 14, muted);
                heading.setTypeface(medium());
                heading.setPadding(0, dp(16), 0, dp(10));
                markers.addView(heading);
                markerRows = column();
                markers.addView(markerRows);
                addMarker = button("+ Marker at playback position");
                addMarker.setId(ADD_MARKER_ID);
                addMarker.setContentDescription("Add marker at playback position in audio " + media.optString("id"));
                addMarker.setOnClickListener(view -> editMarker(this, null));
                LinearLayout.LayoutParams addSpace = new LinearLayout.LayoutParams(-1, -2);
                addSpace.topMargin = dp(8);
                markers.addView(addMarker, addSpace);
                markerStatus = text("", 12, muted);
                markerStatus.setId(MARKER_STATUS_ID);
                markerStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                markers.addView(markerStatus);
                layout.addView(markers);
                renderMarkers();

                transcriptSection = column();
                TextView transcriptHeading = text("Transcript", 14, muted);
                transcriptHeading.setTypeface(medium());
                transcriptHeading.setPadding(0, dp(16), 0, dp(10));
                transcriptSection.addView(transcriptHeading);
                transcriptBody = text("", 15, ink);
                transcriptBody.setId(TRANSCRIPT_BODY_ID);
                transcriptBody.setLineSpacing(dp(4), 1);
                transcriptSection.addView(transcriptBody);
                readTranscript = quietButton("Read full transcript");
                readTranscript.setId(TRANSCRIPT_READ_ID);
                readTranscript.setOnClickListener(view -> showTranscript(transcriptText));
                transcriptSection.addView(readTranscript, new LinearLayout.LayoutParams(-2, dp(48)));
                layout.addView(transcriptSection);
                renderTranscript(media.optJSONObject("transcript"));
            }
            Button metadata = quietButton("Capture metadata");
            metadata.setId(METADATA_BUTTON_ID);
            metadata.setContentDescription("Capture metadata");
            metadata.setOnClickListener(view -> showMetadata(media));
            LinearLayout.LayoutParams metadataSpace = new LinearLayout.LayoutParams(-2, dp(48));
            metadataSpace.gravity = Gravity.END;
            metadataSpace.topMargin = dp(6);
            layout.addView(metadata, metadataSpace);
        }

        void renderTranscript(JSONObject transcript) {
            String state = transcript == null ? "" : transcript.optString("status");
            String body = "complete".equals(state) ? transcript.optString("text") : "";
            if (state.equals(transcriptState) && body.equals(transcriptText)) return;
            transcriptState = state;
            transcriptText = body;
            String display = switch (state) {
                case "pending" -> "Waiting for transcription…";
                case "complete" -> body;
                case "no_speech" -> "No speech recognized.";
                case "failed" -> "Transcription couldn’t finish. The audio is saved.";
                default -> "";
            };
            transcriptSection.setVisibility(display.isEmpty() ? View.GONE : View.VISIBLE);
            boolean hasText = !body.isEmpty();
            boolean hasPreview = body.length() > TRANSCRIPT_PREVIEW_CHARS;
            transcriptBody.setTextColor(hasText ? ink : muted);
            transcriptBody.setTextIsSelectable(hasText);
            transcriptBody.setAccessibilityLiveRegion(hasText ? View.ACCESSIBILITY_LIVE_REGION_NONE
                    : View.ACCESSIBILITY_LIVE_REGION_POLITE);
            transcriptBody.setText(hasPreview ? body.substring(0, transcriptSectionEnd(body, 0,
                    TRANSCRIPT_PREVIEW_CHARS)) + "…" : display);
            readTranscript.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
        }

        void renderMarkers() {
            markerRows.removeAllViews();
            ArrayList<JSONObject> markers = new ArrayList<>();
            JSONArray stored = media.optJSONArray("markers");
            if (stored != null) for (int i = 0; i < stored.length(); i++) {
                JSONObject marker = stored.optJSONObject(i);
                if (marker != null && media.optString("id").equals(marker.optString("mediaId"))) markers.add(marker);
            }
            markers.sort(Comparator.comparingLong(marker -> marker.optLong("offsetMs")));
            for (JSONObject marker : markers) {
                String label = markerLabel(marker);
                String time = markerTime(marker.optLong("offsetMs"));
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(4), dp(4), dp(4));
                row.setBackground(surface(inset, 14, border));
                Button jump = quietButton(time + "  ·  " + label);
                jump.setId(MARKER_PLAY_ID);
                jump.setTag(marker.optString("id"));
                jump.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                jump.setMaxLines(2);
                jump.setEllipsize(android.text.TextUtils.TruncateAt.END);
                jump.setContentDescription("Play marker " + label + " at " + time + " in audio " + media.optString("id"));
                jump.setEnabled(uri != null);
                jump.setOnClickListener(view -> playFrom(marker.optLong("offsetMs")));
                row.addView(jump, new LinearLayout.LayoutParams(0, -2, 1));
                Button edit = quietButton("Edit");
                edit.setId(MARKER_EDIT_ID);
                edit.setTag(marker.optString("id"));
                edit.setContentDescription("Edit marker " + label + " in audio " + media.optString("id"));
                edit.setOnClickListener(view -> editMarker(this, marker));
                row.addView(edit, new LinearLayout.LayoutParams(dp(64), dp(48)));
                LinearLayout.LayoutParams rowSpace = new LinearLayout.LayoutParams(-1, -2);
                rowSpace.bottomMargin = dp(8);
                markerRows.addView(row, rowSpace);
            }
            if (markers.isEmpty()) markerRows.addView(text("No markers yet", 13, muted));
            addMarker.setEnabled(uri != null);
        }

        int playbackPosition() {
            if (prepared && !pendingSeek) {
                try { return Math.max(0, audio.getCurrentPosition()); }
                catch (IllegalStateException ignored) { /* Keep the known paused/seek position. */ }
            }
            return Math.max(0, savedPosition);
        }

        void commitSeek(int value) {
            savedPosition = value;
            try {
                if (prepared) {
                    if (isVideo) video.seekTo(savedPosition);
                    else audio.seekTo(savedPosition);
                }
            } catch (IllegalStateException error) { fail("The media is no longer ready. Try Play again."); }
        }

        void playFrom(long offsetMs) {
            if (uri == null || isVideo || !allowPlayback(this)) return;
            releasePlayback();
            savedPosition = (int) Math.max(0, Math.min(Integer.MAX_VALUE, offsetMs));
            if (duration > 0) savedPosition = Math.min(savedPosition, duration);
            seek.setProgress(savedPosition);
            position.setText(durationLabel(savedPosition) + " / " + durationLabel(duration > 0 ? duration : media.optLong("durationMs")));
            toggle();
        }

        void toggle() {
            if (wantsPlay) {
                releasePlayback();
                abandonFocus();
                return;
            }
            if (uri == null || !allowPlayback(this)) return;
            wantsPlay = true;
            main.removeCallbacks(tick);
            main.post(tick);
            status.setText("Loading…");
            setPlayLabel();
            int token = ++generation;
            if (isVideo) prepareVideo(token);
            else prepareAudio(token);
        }

        private void prepareVideo(int token) {
            video.setOnPreparedListener(player -> {
                if (token != generation || !live() || !wantsPlay) return;
                if (!allowPlayback(this)) { releasePlayback(); abandonFocus(); return; }
                try {
                    prepared = true;
                    duration = Math.max(0, player.getDuration());
                    seek.setMax(duration);
                    seek.setEnabled(duration > 0);
                    if (savedPosition > 0) video.seekTo(savedPosition);
                    videoPlaceholder.setVisibility(View.GONE);
                    status.setText("");
                    video.start();
                } catch (RuntimeException error) { fail("Unable to play video. " + errorText(error)); }
            });
            video.setOnCompletionListener(player -> { if (token == generation && live()) complete(); });
            video.setOnErrorListener((player, what, extra) -> {
                if (token == generation && live()) fail("This video could not be played (" + what + "/" + extra + ").");
                return true;
            });
            try { video.setVideoURI(uri); }
            catch (RuntimeException error) { fail("Unable to play video. " + errorText(error)); }
        }

        private void prepareAudio(int token) {
            MediaPlayer player = new MediaPlayer();
            audio = player;
            player.setAudioAttributes(attributes());
            player.setOnPreparedListener(ready -> {
                if (token != generation || audio != ready || !live() || !wantsPlay) return;
                if (!allowPlayback(this)) { releasePlayback(); abandonFocus(); return; }
                try {
                    prepared = true;
                    duration = Math.max(0, ready.getDuration());
                    seek.setMax(duration);
                    seek.setEnabled(duration > 0);
                    savedPosition = Math.min(savedPosition, duration);
                    if (savedPosition > 0) {
                        pendingSeek = true;
                        ready.setOnSeekCompleteListener(seeked -> {
                            if (token != generation || audio != seeked || !live() || !wantsPlay) return;
                            if (!allowPlayback(this)) { releasePlayback(); abandonFocus(); return; }
                            try {
                                pendingSeek = false;
                                seeked.setOnSeekCompleteListener(null);
                                status.setText("");
                                seeked.start();
                            } catch (RuntimeException error) { fail("Unable to play audio. " + errorText(error)); }
                        });
                        ready.seekTo((long) savedPosition, MediaPlayer.SEEK_CLOSEST);
                    } else {
                        status.setText("");
                        ready.start();
                    }
                } catch (RuntimeException error) { fail("Unable to play audio. " + errorText(error)); }
            });
            player.setOnCompletionListener(ready -> { if (audio == ready) complete(); });
            player.setOnErrorListener((failed, what, extra) -> {
                if (audio == failed) fail("This audio could not be played (" + what + "/" + extra + ").");
                return true;
            });
            try { player.setDataSource(activity, uri); player.prepareAsync(); }
            catch (IOException | RuntimeException error) { fail("Unable to play audio. " + errorText(error)); }
        }

        void complete() {
            releasePlayback();
            savedPosition = 0;
            seek.setProgress(0);
            position.setText("0:00 / " + durationLabel(duration));
            abandonFocus();
        }

        void fail(String message) {
            releasePlayback();
            status.setText(message);
            abandonFocus();
        }

        void releasePlayback() {
            generation++;
            boolean wasPlaying = wantsPlay;
            wantsPlay = false;
            if (prepared && !pendingSeek) {
                try { savedPosition = isVideo ? video.getCurrentPosition() : audio.getCurrentPosition(); }
                catch (IllegalStateException ignored) { /* Preserve the last known position. */ }
            }
            prepared = false;
            pendingSeek = false;
            if (audio != null) { audio.release(); audio = null; }
            if (video != null) {
                video.setOnPreparedListener(null);
                video.setOnCompletionListener(null);
                video.setOnErrorListener(null);
                video.stopPlayback();
            }
            if (play != null) {
                setPlayLabel();
                seek.setEnabled(duration > 0);
                if (duration > 0 && !seeking) {
                    seek.setProgress(savedPosition);
                    position.setText(durationLabel(savedPosition) + " / " + durationLabel(duration));
                }
            }
            if (status.getText().toString().equals("Loading…")) status.setText("");
            if (wasPlaying && !hasActivePlayback()) main.removeCallbacks(tick);
        }

        void setPlayLabel() {
            String label = (wantsPlay ? "Pause " : "Play ") + (isVideo ? "video" : "audio");
            play.setText(label);
            play.setContentDescription(label);
        }

        void updateProgress() {
            if (!prepared || seeking || pendingSeek) return;
            try {
                int current = isVideo ? video.getCurrentPosition() : audio.getCurrentPosition();
                savedPosition = current;
                seek.setProgress(current);
                position.setText(durationLabel(current) + " / " + durationLabel(duration));
            } catch (IllegalStateException error) { fail("Playback stopped. Try Play again."); }
        }
    }

    private void editMarker(MediaCard card, JSONObject existing) {
        if (!live() || markerDialog != null) return;
        final String markerId = existing == null ? UUID.randomUUID().toString() : existing.optString("id");
        final long offsetMs = existing == null ? card.playbackPosition() : existing.optLong("offsetMs");
        final long markedAt = System.currentTimeMillis();
        final MarkerAddAttempt addAttempt = new MarkerAddAttempt();
        stopPlayback();
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), dp(12));
        TextView help = text("The marker belongs to this original audio clip.", 13, muted);
        help.setLineSpacing(dp(3), 1);
        help.setPadding(0, 0, 0, dp(14));
        form.addView(help);
        EditText label = new EditText(getContext());
        label.setId(MARKER_LABEL_INPUT_ID);
        label.setContentDescription("Marker label");
        label.setHint("Label (optional)");
        label.setTextColor(ink);
        label.setHintTextColor(muted);
        label.setTextSize(16);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        label.setPadding(dp(14), dp(14), dp(14), dp(14));
        label.setMinHeight(dp(52));
        StateListDrawable inputBackground = new StateListDrawable();
        inputBackground.addState(new int[]{android.R.attr.state_focused}, surface(inset, 12, accent));
        inputBackground.addState(new int[]{}, surface(inset, 12, border));
        label.setBackgroundTintList(null);
        label.setBackground(inputBackground);
        label.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        label.setSingleLine(true);
        label.setText(existing == null ? "" : existing.optString("label"));
        label.setSelectAllOnFocus(true);
        form.addView(label, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setCustomTitle(dialogTitle((existing == null ? "Marker at " : "Edit marker · ") + markerTime(offsetMs)))
                .setView(form).setPositiveButton(existing == null ? "Add" : "Save", null)
                .setNegativeButton("Cancel", null);
        if (existing != null) builder.setNeutralButton("Delete", null);
        AlertDialog dialog = builder.create();
        markerDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (markerDialog == dialog) markerDialog = null; });
        dialog.setOnShowListener(ignored -> {
            polishDialog(dialog);
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setId(MARKER_SAVE_ID);
            save.setContentDescription(existing == null ? "Add audio marker" : "Save audio marker");
            save.setOnClickListener(view -> {
                String value = label.getText().toString();
                if (value.length() > 200) { label.setError("Use 200 characters or fewer."); return; }
                writeMarker(card, markerId, dialog, label, false, () -> {
                    if (existing == null && !addAttempt.confirmed) {
                        // A failed publication may already retain this add for recovery. Replay its
                        // original intent before applying a label edited while the retry UI was open.
                        if (addAttempt.originalLabel == null) addAttempt.originalLabel = value;
                        JSONObject added = store.addAudioMarker(projectUri, entry.optString("id"), card.media.optString("id"),
                                markerId, offsetMs, addAttempt.originalLabel, markedAt);
                        addAttempt.confirmed = true;
                        if (value.trim().equals(added.optString("label"))) return added;
                    }
                    // Once the add succeeds, retry only the rename: a failed rename may also have
                    // been recovered, so replaying the initial add would now conflict with its label.
                    return store.updateAudioMarker(projectUri, entry.optString("id"), card.media.optString("id"),
                            markerId, value);
                });
            });
            if (existing != null) {
                Button delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                delete.setId(MARKER_DELETE_ID);
                delete.setContentDescription("Delete audio marker");
                delete.setOnClickListener(view -> writeMarker(card, markerId, dialog, label, true, () -> {
                    store.deleteAudioMarker(projectUri, entry.optString("id"), card.media.optString("id"), markerId);
                    return null;
                }));
            }
        });
        dialog.show();
    }

    private interface MarkerWrite { JSONObject run() throws IOException; }

    /** Accessed only by the annotation executor for one marker dialog. */
    private static final class MarkerAddAttempt {
        String originalLabel;
        boolean confirmed;
    }

    private void writeMarker(MediaCard card, String markerId, AlertDialog dialog, EditText label,
                             boolean deleting, MarkerWrite write) {
        if (!live() || markerDialog != dialog) return;
        setMarkerEditingEnabled(dialog, label, false);
        card.markerStatus.setText(deleting ? "Deleting marker…" : "Saving marker…");
        edits.execute(() -> {
            try {
                JSONObject saved = write.run();
                editCompletion.post(() -> {
                    // A replacement Activity must also learn about edits accepted by the old viewer.
                    activity.getApplicationContext().sendBroadcast(new Intent(ProjectPublisher.ACTION_MEDIA_SAVED)
                            .setPackage(activity.getPackageName()).putExtra("entryId", entry.optString("id"))
                            .putExtra("projectUri", projectUri.toString()));
                    if (live()) {
                        JSONArray markers = new JSONArray();
                        JSONArray previous = card.media.optJSONArray("markers");
                        if (previous != null) for (int i = 0; i < previous.length(); i++) {
                            JSONObject marker = previous.optJSONObject(i);
                            if (marker != null && !markerId.equals(marker.optString("id"))) markers.put(marker);
                        }
                        if (saved != null) markers.put(saved);
                        try {
                            card.media.put("markers", markers);
                            card.renderMarkers();
                            card.markerStatus.setText(deleting ? "Marker deleted" : "Marker saved");
                        } catch (org.json.JSONException error) {
                            card.markerStatus.setText("Saved. Reopen this entry to refresh markers.");
                        }
                        dialog.dismiss();
                    }
                    // The host may already have dismissed this viewer while publication was running.
                    if (annotationsChanged != null) annotationsChanged.run();
                });
            } catch (Exception error) {
                editCompletion.post(() -> {
                    if (!live() || markerDialog != dialog) return;
                    setMarkerEditingEnabled(dialog, label, true);
                    label.setError(errorText(error));
                    card.markerStatus.setText("Unable to save this change. Your label is still here for retry.");
                });
            }
        });
    }

    private void setMarkerEditingEnabled(AlertDialog dialog, EditText label, boolean enabled) {
        label.setEnabled(enabled);
        label.setError(null);
        dialog.setCancelable(enabled);
        dialog.setCanceledOnTouchOutside(enabled);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button action = dialog.getButton(which);
            if (action != null) action.setEnabled(enabled);
        }
    }

    private static String markerLabel(JSONObject marker) {
        String label = marker.optString("label").trim();
        return label.isEmpty() ? "Marker" : label;
    }

    private static String markerTime(long milliseconds) {
        long time = Math.max(0, milliseconds);
        return String.format(Locale.getDefault(), "%d:%02d.%03d", time / 60000, (time / 1000) % 60, time % 1000);
    }

    private void showMetadata(JSONObject media) {
        JSONObject details = new JSONObject();
        for (Iterator<String> keys = media.keys(); keys.hasNext();) {
            String key = keys.next();
            if ("transcript".equals(key)) continue;
            try { details.put(key, media.get(key)); }
            catch (org.json.JSONException ignored) { /* Omit only fields that cannot be read. */ }
        }
        showMetadata("Capture metadata", details);
    }

    private void showTranscript(String transcript) {
        if (transcriptDialog != null) transcriptDialog.dismiss();
        List<String> sections = new ArrayList<>();
        for (int start = 0; start < transcript.length();) {
            int end = transcriptSectionEnd(transcript, start, TRANSCRIPT_SECTION_CHARS);
            sections.add(transcript.substring(start, end));
            start = end;
        }
        ListView textSections = new ListView(activity);
        textSections.setDivider(null);
        textSections.setItemsCanFocus(true);
        textSections.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_list_item_1, sections) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView section = (TextView) convertView;
                if (section == null) {
                    section = text("", 16, ink);
                    section.setTextIsSelectable(true);
                    section.setLineSpacing(dp(4), 1);
                    section.setPadding(dp(20), dp(8), dp(20), dp(8));
                }
                section.setText(getItem(position));
                return section;
            }
        });
        transcriptDialog = new AlertDialog.Builder(getContext()).setCustomTitle(dialogTitle("Transcript"))
                .setView(textSections).setPositiveButton("Close", null).create();
        transcriptDialog.show();
        polishDialog(transcriptDialog);
    }

    private static int transcriptSectionEnd(String text, int start, int size) {
        int end = Math.min(start + size, text.length());
        if (end == text.length()) return end;
        for (int i = end - 1; i >= start + size / 2; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        }
        return Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
    }

    private void showTextMetadata() {
        JSONObject details = new JSONObject();
        for (String key : new String[]{"id", "createdAt", "textCreatedAt", "textUpdatedAt", "textMetadata"}) {
            if (!entry.has(key)) continue;
            try { details.put(key, entry.get(key)); }
            catch (org.json.JSONException ignored) { /* Only keys actually present in this entry are copied. */ }
        }
        showMetadata("Text metadata", details);
    }

    private void showMetadata(String title, JSONObject detailsJson) {
        if (metadataDialog != null) metadataDialog.dismiss();
        ScrollView scroll = new ScrollView(activity);
        TextView details = text("", 12, ink);
        details.setId(METADATA_CONTENT_ID);
        details.setContentDescription(title + " details");
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        details.setLineSpacing(dp(3), 1);
        details.setPadding(dp(20), dp(14), dp(20), dp(18));
        try { details.setText(detailsJson.toString(2)); }
        catch (org.json.JSONException ignored) { details.setText(detailsJson.toString()); }
        scroll.addView(details);
        metadataDialog = new AlertDialog.Builder(getContext()).setCustomTitle(dialogTitle(title))
                .setView(scroll).setPositiveButton("Close", null).create();
        metadataDialog.show();
        polishDialog(metadataDialog);
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        return params;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    private GradientDrawable surface(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private Button button(String title) {
        Button button = new Button(activity);
        button.setText(title);
        styleButton(button, control, accent, Color.TRANSPARENT);
        return button;
    }

    private Button quietButton(String title) {
        Button button = new Button(activity);
        button.setText(title);
        styleButton(button, Color.TRANSPARENT, accent, Color.TRANSPARENT);
        return button;
    }

    private Button primaryButton(String title) {
        Button button = new Button(activity);
        button.setText(title);
        styleButton(button, accent, dark ? background : Color.WHITE, Color.TRANSPARENT);
        return button;
    }

    private void styleButton(Button button, int fill, int foreground, int stroke) {
        button.setAllCaps(false);
        button.setTypeface(medium());
        button.setIncludeFontPadding(false);
        button.setTextSize(14);
        button.setLetterSpacing(.01f);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(64));
        button.setMinimumWidth(dp(64));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}
        }, new int[]{withAlpha(muted, 150), foreground}));
        StateListDrawable fills = new StateListDrawable();
        fills.addState(new int[]{-android.R.attr.state_enabled},
                surface(fill == Color.TRANSPARENT ? Color.TRANSPARENT : inset, 12, Color.TRANSPARENT));
        fills.addState(new int[]{}, surface(fill, 12, stroke));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(fill == accent ? foreground : accent, dark ? 44 : 30)),
                fills, surface(Color.WHITE, 12, Color.TRANSPARENT)));
        button.setStateListAnimator(null);
        button.setElevation(0);
    }

    private TextView dialogTitle(String title) {
        TextView heading = text(title, 20, ink);
        heading.setTypeface(medium());
        heading.setPadding(dp(20), dp(22), dp(20), dp(14));
        return heading;
    }

    private void polishDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(surface(panel, 24, border));
            window.setDimAmount(.48f);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button action = dialog.getButton(which);
            if (action == null) continue;
            boolean primary = which == AlertDialog.BUTTON_POSITIVE;
            styleButton(action, primary ? accent : Color.TRANSPARENT,
                    primary ? (dark ? background : Color.WHITE) : accent, Color.TRANSPARENT);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private String captureTime(String value) {
        try { return DateTimeFormatter.ofPattern("MMM d · HH:mm:ss", Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(Instant.parse(value)); }
        catch (RuntimeException ignored) { return value.isEmpty() ? "Capture time unavailable" : value; }
    }

    private static String durationLabel(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String errorText(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private int dp(int value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }
}
