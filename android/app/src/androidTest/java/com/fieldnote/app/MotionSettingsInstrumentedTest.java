package com.fieldnote.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Real SharedPreferences loading/recovery; no physical motion or microphone is simulated. */
@RunWith(AndroidJUnit4.class)
public final class MotionSettingsInstrumentedTest {
    private Context context;
    private SharedPreferences preferences;
    private final Map<String, Object> previous = new HashMap<>();
    private MotionCaptureControls controls;
    private static final String MODELS = "motion_models_v1", ENABLED = "motion_controls";

    @Before public void rememberPreferences() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        Map<String, ?> all = preferences.getAll();
        for (String key : new String[]{MODELS, ENABLED}) if (all.containsKey(key)) previous.put(key, all.get(key));
    }

    @After public void restorePreferences() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (controls != null) controls.stop(); });
        SharedPreferences.Editor editor = preferences.edit().remove(MODELS).remove(ENABLED);
        for (Map.Entry<String, Object> item : previous.entrySet()) {
            String key = item.getKey(); Object value = item.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                editor.putStringSet(key, strings);
            }
        }
        assertTrue(editor.commit());
    }

    @Test public void unreadableCustomProfilesCannotSilentlyEnableDefaultActionsAndResetRecovers() {
        String unreadable = "not-a-valid-saved-model";
        assertTrue(preferences.edit().putString(MODELS, unreadable).putBoolean(ENABLED, true).commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            controls = createControls();
            assertNotNull(controls.settingsLoadError());
            assertTrue(controls.hasUnreadableProfiles());
            controls.acknowledgeSettingsWarning();
            assertNotNull("Acknowledging cannot recover an unreadable mapping", controls.settingsLoadError());
            assertFalse(preferences.getBoolean(ENABLED, true));
            controls.start();
            assertFalse("Unreadable mappings cannot register action sensors", controls.isRunning());
            controls.setSensitivity(MotionAction.PHOTO, 70);
            assertEquals("A slider cannot silently discard unreadable profiles", unreadable, preferences.getString(MODELS, null));
            controls.resetTraining(MotionAction.PHOTO);
            assertNull(controls.settingsLoadError());
            assertFalse(controls.hasUnreadableProfiles());
            assertEquals(70, MotionSettings.decode(preferences.getString(MODELS, null)).getSensitivity(MotionAction.PHOTO));
            assertFalse("Recovery requires explicit re-enabling", preferences.getBoolean(ENABLED, true));
        });
    }

    @Test public void wrongTypedModelDoesNotCrashAndIsPreservedForExplicitRecovery() {
        assertTrue(preferences.edit().putInt(MODELS, 7).putBoolean(ENABLED, true).commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            controls = createControls();
            assertNotNull(controls.settingsLoadError());
            controls.start();
            assertFalse(controls.isRunning());
            assertEquals(7, preferences.getInt(MODELS, -1));
            assertFalse(preferences.getBoolean(ENABLED, true));
        });
    }

    @Test public void wrongTypedEnableSwitchIsTurnedOffWithoutDiscardingValidProfiles() {
        String original = new MotionSettings().withSensitivity(MotionAction.MARKER, 73).encode();
        assertTrue(preferences.edit().putString(MODELS, original).putString(ENABLED, "on").commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            controls = createControls();
            assertNotNull(controls.settingsLoadError());
            assertFalse(preferences.getBoolean(ENABLED, true));
            assertEquals(original, preferences.getString(MODELS, null));
            assertEquals(73, controls.getSensitivity(MotionAction.MARKER));
            assertFalse(controls.hasUnreadableProfiles());
            controls.acknowledgeSettingsWarning();
            assertNull(controls.settingsLoadError());
            assertEquals(original, preferences.getString(MODELS, null));
        });
    }

    private MotionCaptureControls createControls() {
        return new MotionCaptureControls(context, new MotionCaptureControls.Listener() {
            @Override public void onForwardShake() { fail("Settings tests must never start an audio action."); }
            @Override public void onTwist() { fail("Settings tests must never start a photo action."); }
            @Override public void onAudioMarker() { fail("Settings tests must never create a marker."); }
        });
    }
}
