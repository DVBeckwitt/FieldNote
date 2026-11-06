package com.fieldnote.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Launcher metadata and capability validation only: never pins a shortcut or starts recording. */
@RunWith(AndroidJUnit4.class)
public final class RecordingShortcutsInstrumentedTest {
    private Context context;
    private SharedPreferences preferences;
    private Object previousNonce;
    private boolean hadNonce;

    @Before public void rememberNonce() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        hadNonce = preferences.contains(RecordingShortcuts.NONCE_KEY);
        previousNonce = preferences.getAll().get(RecordingShortcuts.NONCE_KEY);
    }

    @After public void restoreNonce() throws Exception {
        awaitPublication();
        SharedPreferences.Editor editor = preferences.edit().remove(RecordingShortcuts.NONCE_KEY);
        if (hadNonce) {
            String key = RecordingShortcuts.NONCE_KEY;
            if (previousNonce instanceof String) editor.putString(key, (String) previousNonce);
            else if (previousNonce instanceof Boolean) editor.putBoolean(key, (Boolean) previousNonce);
            else if (previousNonce instanceof Integer) editor.putInt(key, (Integer) previousNonce);
            else if (previousNonce instanceof Long) editor.putLong(key, (Long) previousNonce);
            else if (previousNonce instanceof Float) editor.putFloat(key, (Float) previousNonce);
            else if (previousNonce instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> values = (Set<String>) previousNonce;
                editor.putStringSet(key, values);
            }
        }
        assertTrue(editor.commit());
    }

    @Test public void generatedIntentIsExplicitAuthenticatedAndContainsNoProjectOrEntry() {
        Intent record = RecordingShortcuts.recordIntent(context), floating = RecordingShortcuts.overlayIntent(context);
        assertTrue(RecordingShortcuts.isRecordIntent(context, record));
        assertFalse(RecordingShortcuts.isOverlayIntent(context, record));
        assertTrue(RecordingShortcuts.isOverlayIntent(context, floating));
        assertFalse(RecordingShortcuts.isRecordIntent(context, floating));
        assertEquals(RecordingShortcuts.ACTION_RECORD, record.getAction());
        assertEquals(RecordingShortcuts.ACTION_FLOATING, floating.getAction());
        for (Intent intent : new Intent[]{record, floating}) {
            assertEquals(new ComponentName(context, MainActivity.class), intent.getComponent());
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.getFlags());
            assertNull(intent.getData());
            assertNull(intent.getClipData());
            assertNotNull(intent.getExtras());
            assertEquals(Collections.singleton(RecordingShortcuts.EXTRA_NONCE), intent.getExtras().keySet());
        }
    }

    @Test public void forgedMissingWrongActionWrongTypeAndHistoryRequestsAreRejected() {
        assertRejected(null);
        for (Intent valid : new Intent[]{RecordingShortcuts.recordIntent(context), RecordingShortcuts.overlayIntent(context)}) {
            Intent missing = new Intent(valid);
            missing.removeExtra(RecordingShortcuts.EXTRA_NONCE);
            assertRejected(missing);
            assertRejected(new Intent(valid).putExtra(RecordingShortcuts.EXTRA_NONCE, UUID.randomUUID().toString()));
            assertRejected(new Intent(valid).putExtra(RecordingShortcuts.EXTRA_NONCE, 7));
            assertRejected(new Intent(valid).setAction(Intent.ACTION_MAIN));
            assertRejected(new Intent(valid).setAction(null));
            assertRejected(new Intent(valid).setComponent(null));
            assertRejected(new Intent(valid).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY));
            assertTrue("Rejected requests cannot invalidate the legitimate shortcut",
                    RecordingShortcuts.isRecordIntent(context, valid) || RecordingShortcuts.isOverlayIntent(context, valid));
        }
    }

    @Test public void validationNeverCreatesOrRepairsAnAuthenticationToken() {
        assertTrue(preferences.edit().remove(RecordingShortcuts.NONCE_KEY).commit());
        Intent forged = new Intent(context, MainActivity.class).setAction(RecordingShortcuts.ACTION_RECORD)
                .putExtra(RecordingShortcuts.EXTRA_NONCE, UUID.randomUUID().toString());
        assertRejected(forged);
        assertRejected(new Intent(forged).setAction(RecordingShortcuts.ACTION_FLOATING));
        assertFalse(preferences.contains(RecordingShortcuts.NONCE_KEY));
        assertTrue(preferences.edit().putInt(RecordingShortcuts.NONCE_KEY, 7).commit());
        assertRejected(forged);
        assertRejected(new Intent(forged).setAction(RecordingShortcuts.ACTION_FLOATING));
        assertTrue(preferences.getAll().get(RecordingShortcuts.NONCE_KEY) instanceof Integer);
        assertTrue("Only publishing an app-owned intent establishes a replacement token",
                RecordingShortcuts.isRecordIntent(context, RecordingShortcuts.recordIntent(context)));
    }

    @Test public void authenticationTokenPersistsAcrossIndependentContextInstances() throws Exception {
        Intent first = RecordingShortcuts.recordIntent(context);
        Context independent = context.createPackageContext(context.getPackageName(), 0);
        Intent second = RecordingShortcuts.overlayIntent(independent);
        String token = first.getStringExtra(RecordingShortcuts.EXTRA_NONCE);
        assertNotNull(token);
        // Boolean assertions deliberately keep authentication values out of failure output.
        assertTrue("Repeated intents retain the installed shortcut's token",
                token.equals(second.getStringExtra(RecordingShortcuts.EXTRA_NONCE)));
        assertTrue("The token is persisted in private preferences", token.equals(independent
                .getSharedPreferences("fieldnote", Context.MODE_PRIVATE).getAll().get(RecordingShortcuts.NONCE_KEY)));
        assertTrue(RecordingShortcuts.isRecordIntent(independent, first));
        assertTrue(RecordingShortcuts.isOverlayIntent(independent, second));
    }

    @Test public void unsupportedLauncherRejectsBothPinActionsWithoutCreatingCaptureCapabilities() {
        assertTrue(preferences.edit().remove(RecordingShortcuts.NONCE_KEY).commit());
        AtomicInteger serviceRequests = new AtomicInteger();
        Context unsupported = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                if (Context.SHORTCUT_SERVICE.equals(name)) {
                    serviceRequests.incrementAndGet();
                    return null;
                }
                return super.getSystemService(name);
            }
        };
        RecordingService.Snapshot before = RecordingService.snapshot();
        assertFalse(RecordingShortcuts.requestPin(unsupported));
        assertFalse(RecordingShortcuts.requestPinFloating(unsupported));
        assertEquals(2, serviceRequests.get());
        assertFalse("Unsupported pinning does not publish a capability", preferences.contains(RecordingShortcuts.NONCE_KEY));
        assertSame("Pin requests cannot start a recording", before, RecordingService.snapshot());
    }

    @Test public void publishingPreservesOtherShortcutsAndDoesNotRewriteCorrectMetadata() throws Exception {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        assertNotNull(manager);
        List<ShortcutInfo> before = manager.getDynamicShortcuts();
        ShortcutInfo previous = find(before, RecordingShortcuts.SHORTCUT_ID);
        ShortcutInfo previousPinned = find(manager.getPinnedShortcuts(), RecordingShortcuts.SHORTCUT_ID);
        ShortcutInfo previousFloating = find(before, RecordingShortcuts.FLOATING_SHORTCUT_ID);
        ShortcutInfo previousFloatingPinned = find(manager.getPinnedShortcuts(), RecordingShortcuts.FLOATING_SHORTCUT_ID);
        String otherId = "shortcut-test-" + UUID.randomUUID();
        ShortcutInfo other = new ShortcutInfo.Builder(context, otherId)
                .setShortLabel("Unrelated test action")
                .setActivity(new ComponentName(context, MainActivity.class))
                .setIntent(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_record)).build();
        boolean addedOther = false;
        try {
            assertTrue("The isolated launcher must have room for this test's extra action",
                    before.size() + (previous == null ? 1 : 0) + (previousFloating == null ? 1 : 0) + 1
                            <= manager.getMaxShortcutCountPerActivity());
            assertTrue(manager.addDynamicShortcuts(Collections.singletonList(other)));
            addedOther = true;
            RecordingShortcuts.publish(context);
            awaitPublication();
            List<ShortcutInfo> published = manager.getDynamicShortcuts();
            ShortcutInfo record = find(published, RecordingShortcuts.SHORTCUT_ID);
            assertNotNull("The supported launcher exposes the dynamic recording shortcut", record);
            assertEquals("Record now", record.getShortLabel().toString());
            assertEquals("Record now in app", record.getLongLabel().toString());
            assertTrue(record.isEnabled());
            assertTrue(RecordingShortcuts.isRecordIntent(context, record.getIntent()));
            assertEquals(new ComponentName(context, MainActivity.class), record.getActivity());
            ShortcutInfo floating = find(published, RecordingShortcuts.FLOATING_SHORTCUT_ID);
            assertNotNull("The second shortcut is published when the launcher has capacity", floating);
            assertEquals("Floating mic", floating.getShortLabel().toString());
            assertEquals("Show floating mic", floating.getLongLabel().toString());
            assertTrue(RecordingShortcuts.isOverlayIntent(context, floating.getIntent()));
            assertNotNull(find(published, otherId));
            Set<String> ids = new HashSet<>();
            for (ShortcutInfo item : published) ids.add(item.getId());
            for (ShortcutInfo item : before) assertTrue(ids.contains(item.getId()));
            long changedAt = record.getLastChangedTimestamp();
            long floatingChangedAt = floating.getLastChangedTimestamp();
            SystemClock.sleep(20);
            RecordingShortcuts.publish(context);
            awaitPublication();
            assertEquals("An unchanged shortcut must not consume another launcher update",
                    changedAt, find(manager.getDynamicShortcuts(), RecordingShortcuts.SHORTCUT_ID).getLastChangedTimestamp());
            assertEquals(floatingChangedAt,
                    find(manager.getDynamicShortcuts(), RecordingShortcuts.FLOATING_SHORTCUT_ID).getLastChangedTimestamp());
        } finally {
            awaitPublication();
            if (addedOther) manager.removeDynamicShortcuts(Collections.singletonList(otherId));
            if (previous == null) {
                manager.removeDynamicShortcuts(Collections.singletonList(RecordingShortcuts.SHORTCUT_ID));
                if (previousPinned != null) assertTrue(manager.updateShortcuts(Collections.singletonList(previousPinned)));
            } else assertTrue(manager.updateShortcuts(Collections.singletonList(previous)));
            if (previousFloating == null) {
                manager.removeDynamicShortcuts(Collections.singletonList(RecordingShortcuts.FLOATING_SHORTCUT_ID));
                if (previousFloatingPinned != null) assertTrue(manager.updateShortcuts(Collections.singletonList(previousFloatingPinned)));
            } else assertTrue(manager.updateShortcuts(Collections.singletonList(previousFloating)));
        }
    }

    @Test public void widgetRegistrationIsPrivateStaticAndAvailableToTheNormalPicker() throws Exception {
        ComponentName component = new ComponentName(context, RecordingWidget.class);
        ActivityInfo receiver = context.getPackageManager().getReceiverInfo(component, PackageManager.GET_META_DATA);
        assertFalse("Only Android should deliver widget updates", receiver.exported);
        assertEquals(R.xml.recording_widget_info, receiver.metaData.getInt("android.appwidget.provider"));
        AppWidgetProviderInfo found = null;
        for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(context).getInstalledProviders())
            if (component.equals(info.provider)) found = info;
        assertNotNull("The widget picker can expose the mic even when pinning is unsupported", found);
        assertEquals(R.layout.widget_mic, found.initialLayout);
        assertEquals(0, found.updatePeriodMillis);
        assertEquals(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, found.widgetCategory);
        assertTrue(found.minWidth > 0 && found.minHeight > 0);
        assertNull("A mic-only widget has no configuration Activity", found.configure);
    }

    @Test public void widgetContainsOnlyTheMicAndStartUsesAnImmutableForegroundServiceIntent() {
        AtomicInteger clicks = new AtomicInteger();
        AtomicReference<IntentSender> sent = new AtomicReference<>();
        Context intercepted = new ContextWrapper(context) {
            @Override public void startIntentSender(IntentSender sender, Intent fill, int mask, int values, int extra) {
                clicks.incrementAndGet(); sent.set(sender);
            }
            @Override public void startIntentSender(IntentSender sender, Intent fill, int mask, int values, int extra, Bundle options) {
                clicks.incrementAndGet(); sent.set(sender);
            }
        };
        RecordingService.Snapshot before = RecordingService.snapshot();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = RecordingWidget.views(context).apply(intercepted, new FrameLayout(intercepted));
            assertTrue(root instanceof FrameLayout);
            assertEquals("No text, project list or secondary controls surround the mic", 1, ((ViewGroup) root).getChildCount());
            View microphone = root.findViewById(R.id.widget_mic_button);
            assertTrue(microphone instanceof ImageButton);
            assertNotNull(((ImageButton) microphone).getDrawable());
            assertEquals(context.getString(R.string.widget_mic_description), microphone.getContentDescription().toString());
            assertTrue(microphone.performClick());
        });
        assertEquals("The native RemoteViews click is intercepted without launching Main", 1, clicks.get());
        PendingIntent expected = PendingIntent.getForegroundService(context, 62, RecordingShortcuts.recordingWidgetIntent(context),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        assertNotNull(expected);
        assertEquals(context.getPackageName(), expected.getCreatorPackage());
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(expected.isImmutable());
            assertTrue(expected.isForegroundService());
            assertFalse(expected.isActivity());
        }
        assertEquals(expected.getIntentSender(), sent.get());
        assertSame("Inspecting and clicking the widget fixture cannot start a capture", before, RecordingService.snapshot());
    }

    @Test public void widgetCapabilityIsPersistentExplicitAndDoesNotChooseAProject() {
        Intent widget = RecordingShortcuts.widgetIntent(context);
        assertTrue(RecordingShortcuts.isWidgetIntent(context, widget));
        assertFalse(RecordingShortcuts.isRecordIntent(context, widget));
        assertFalse(RecordingShortcuts.isOverlayIntent(context, widget));
        assertEquals(new ComponentName(context, FloatingRecorderService.class), widget.getComponent());
        assertEquals(RecordingShortcuts.ACTION_WIDGET_FLOATING, widget.getAction());
        assertEquals(0, widget.getFlags());
        assertNull(widget.getData());
        assertNull(widget.getClipData());
        assertEquals(Collections.singleton(RecordingShortcuts.EXTRA_NONCE), widget.getExtras().keySet());
        String persisted = preferences.getString(RecordingShortcuts.NONCE_KEY, null);
        assertEquals(persisted, widget.getStringExtra(RecordingShortcuts.EXTRA_NONCE));
        Intent reconstructed = new Intent(context, FloatingRecorderService.class)
                .setAction(RecordingShortcuts.ACTION_WIDGET_FLOATING)
                .putExtra(RecordingShortcuts.EXTRA_NONCE, persisted);
        assertTrue("Validation depends on durable preferences, not a process admission token",
                RecordingShortcuts.isWidgetIntent(context, reconstructed));
    }

    @Test public void widgetRejectsWrongCapabilityComponentActionAndMalformedNonce() {
        Intent widget = RecordingShortcuts.widgetIntent(context);
        assertFalse(RecordingShortcuts.isWidgetIntent(context, null));
        Intent missing = new Intent(widget);
        missing.removeExtra(RecordingShortcuts.EXTRA_NONCE);
        assertFalse(RecordingShortcuts.isWidgetIntent(context, missing));
        assertFalse(RecordingShortcuts.isWidgetIntent(context,
                new Intent(widget).putExtra(RecordingShortcuts.EXTRA_NONCE, UUID.randomUUID().toString())));
        assertFalse(RecordingShortcuts.isWidgetIntent(context,
                new Intent(widget).putExtra(RecordingShortcuts.EXTRA_NONCE, 7)));
        assertFalse(RecordingShortcuts.isWidgetIntent(context,
                new Intent(widget).setComponent(new ComponentName(context, MainActivity.class))));
        assertFalse(RecordingShortcuts.isWidgetIntent(context, new Intent(widget).setAction(Intent.ACTION_MAIN)));
        assertFalse(RecordingShortcuts.isWidgetIntent(context,
                new Intent(widget).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)));
        assertTrue(preferences.edit().remove(RecordingShortcuts.NONCE_KEY).commit());
        assertFalse(RecordingShortcuts.isWidgetIntent(context, widget));
        assertFalse("Validation never creates a missing capability", preferences.contains(RecordingShortcuts.NONCE_KEY));
    }

    @Test public void recordingWidgetCapabilityIsExplicitPersistentAndContainsNoCallerProject() {
        Intent widget = RecordingShortcuts.recordingWidgetIntent(context);
        assertTrue(RecordingShortcuts.isRecordingWidgetIntent(context, widget));
        assertFalse(RecordingShortcuts.isWidgetIntent(context, widget));
        assertFalse(RecordingShortcuts.isRecordIntent(context, widget));
        assertFalse(RecordingShortcuts.isOverlayIntent(context, widget));
        assertEquals(new ComponentName(context, RecordingService.class), widget.getComponent());
        assertEquals(0, widget.getFlags());
        assertNull(widget.getData());
        assertNull(widget.getClipData());
        assertEquals(Collections.singleton(RecordingShortcuts.EXTRA_NONCE), widget.getExtras().keySet());
        String persisted = preferences.getString(RecordingShortcuts.NONCE_KEY, null);
        assertTrue("The widget uses its durable private capability",
                persisted != null && persisted.equals(widget.getStringExtra(RecordingShortcuts.EXTRA_NONCE)));
        Intent reconstructed = new Intent(context, RecordingService.class).setAction(widget.getAction())
                .putExtra(RecordingShortcuts.EXTRA_NONCE, persisted);
        assertTrue(RecordingShortcuts.isRecordingWidgetIntent(context, reconstructed));
    }

    @Test public void recordingWidgetRejectsForgedCapabilitiesAndCannotCreateOneByValidation() {
        Intent widget = RecordingShortcuts.recordingWidgetIntent(context);
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context, null));
        Intent missing = new Intent(widget);
        missing.removeExtra(RecordingShortcuts.EXTRA_NONCE);
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context, missing));
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context,
                new Intent(widget).putExtra(RecordingShortcuts.EXTRA_NONCE, UUID.randomUUID().toString())));
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context,
                new Intent(widget).putExtra(RecordingShortcuts.EXTRA_NONCE, 7)));
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context,
                new Intent(widget).setComponent(new ComponentName(context, FloatingRecorderService.class))));
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context, new Intent(widget).setAction(Intent.ACTION_MAIN)));
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context,
                new Intent(widget).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)));
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context, RecordingShortcuts.widgetIntent(context)));
        assertTrue(preferences.edit().remove(RecordingShortcuts.NONCE_KEY).commit());
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context, widget));
        assertFalse(preferences.contains(RecordingShortcuts.NONCE_KEY));
        assertTrue(preferences.edit().putInt(RecordingShortcuts.NONCE_KEY, 7).commit());
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context, widget));
        assertTrue("Rejecting a malformed stored token does not repair it", preferences.getAll().get(RecordingShortcuts.NONCE_KEY) instanceof Integer);
    }

    @Test public void recordingWidgetStopCapabilityIsDistinctAndRejectsForgedAuthentication() {
        RecordingService.Snapshot before = RecordingService.snapshot();
        Intent stop = RecordingShortcuts.recordingWidgetStopIntent(context, before);
        assertTrue(RecordingShortcuts.isRecordingWidgetStopIntent(context, stop));
        assertFalse(RecordingShortcuts.isRecordingWidgetIntent(context, stop));
        assertFalse(RecordingShortcuts.isRecordingWidgetStopIntent(context, RecordingShortcuts.recordingWidgetIntent(context)));
        assertEquals(new ComponentName(context, RecordingWidgetControlReceiver.class), stop.getComponent());
        assertEquals(RecordingShortcuts.ACTION_WIDGET_STOP, stop.getAction());
        assertEquals(before.entryId, stop.getStringExtra(RecordingShortcuts.EXTRA_WIDGET_ENTRY));
        assertNotNull("STOP has a distinct PendingIntent data identity", stop.getData());
        assertEquals("fieldnote", stop.getData().getScheme());
        assertEquals("recording-widget", stop.getData().getAuthority());
        assertEquals(before.entryId == null ? "unavailable" : before.entryId, stop.getData().getPathSegments().get(1));
        assertNull(stop.getClipData());
        Intent missing = new Intent(stop);
        missing.removeExtra(RecordingShortcuts.EXTRA_NONCE);
        assertFalse(RecordingShortcuts.isRecordingWidgetStopIntent(context, missing));
        assertFalse(RecordingShortcuts.isRecordingWidgetStopIntent(context,
                new Intent(stop).putExtra(RecordingShortcuts.EXTRA_NONCE, UUID.randomUUID().toString())));
        assertFalse(RecordingShortcuts.isRecordingWidgetStopIntent(context,
                new Intent(stop).putExtra(RecordingShortcuts.EXTRA_NONCE, 7)));
        assertFalse(RecordingShortcuts.isRecordingWidgetStopIntent(context,
                new Intent(stop).setComponent(new ComponentName(context, MainActivity.class))));
        assertFalse(RecordingShortcuts.isRecordingWidgetStopIntent(context,
                new Intent(stop).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)));
        assertTrue(preferences.edit().remove(RecordingShortcuts.NONCE_KEY).commit());
        assertFalse(RecordingShortcuts.isRecordingWidgetStopIntent(context, stop));
        assertFalse(preferences.contains(RecordingShortcuts.NONCE_KEY));
        assertSame("Creating or validating STOP capabilities never changes recording state", before, RecordingService.snapshot());
    }

    private static ShortcutInfo find(List<ShortcutInfo> shortcuts, String id) {
        for (ShortcutInfo shortcut : shortcuts) if (id.equals(shortcut.getId())) return shortcut;
        return null;
    }

    private void assertRejected(Intent intent) {
        assertFalse(RecordingShortcuts.isRecordIntent(context, intent));
        assertFalse(RecordingShortcuts.isOverlayIntent(context, intent));
    }

    private static void awaitPublication() throws Exception {
        Field field = RecordingShortcuts.class.getDeclaredField("PUBLISH_QUEUED");
        field.setAccessible(true);
        AtomicBoolean pending = (AtomicBoolean) field.get(null);
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (pending.get() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertFalse("Shortcut publication completes within the bounded wait", pending.get());
    }
}
