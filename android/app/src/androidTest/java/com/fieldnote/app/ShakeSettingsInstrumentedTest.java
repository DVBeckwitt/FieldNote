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

/** Real preference migration; no claim of physical shake recognition. */
@RunWith(AndroidJUnit4.class)
public final class ShakeSettingsInstrumentedTest {
    private static final String MODELS = "motion_models_v1";
    private static final String LEGACY_ENABLED = "motion_controls";
    private static final String ENABLED = "shake_recording_enabled";
    private Context context;
    private SharedPreferences preferences;
    private final Map<String, Object> previous = new HashMap<>();
    private MotionCaptureControls controls;

    @Before public void rememberPreferences() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE);
        Map<String, ?> all = preferences.getAll();
        for (String key : new String[]{MODELS, LEGACY_ENABLED, ENABLED})
            if (all.containsKey(key)) previous.put(key, all.get(key));
    }

    @After public void restorePreferences() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (controls != null) controls.close(); });
        SharedPreferences.Editor editor = preferences.edit().remove(MODELS).remove(LEGACY_ENABLED).remove(ENABLED);
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

    @Test public void obsoleteProfilesAreIgnoredAndPreservedWhileShakeDefaultsOn() {
        String unreadable = "not-a-valid-old-model";
        assertTrue(preferences.edit().remove(ENABLED).remove(LEGACY_ENABLED).putString(MODELS, unreadable).commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            controls = createControls();
            assertTrue(controls.isEnabled());
            assertTrue(preferences.getBoolean(ENABLED, false));
            assertEquals(unreadable, preferences.getString(MODELS, null));
            controls.close();
            assertTrue(preferences.edit().putInt(MODELS, 7).commit());
            controls = createControls();
            assertTrue("Wrong-typed obsolete profiles are also irrelevant", controls.isEnabled());
            assertEquals(7, preferences.getInt(MODELS, -1));
        });
    }

    @Test public void legacyDisabledSwitchMigratesOnceAndExplicitNewChoicePersists() {
        assertTrue(preferences.edit().remove(ENABLED).putBoolean(LEGACY_ENABLED, false).commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            controls = createControls();
            assertFalse("An existing disabled preference is respected on upgrade", controls.isEnabled());
            controls.start();
            assertFalse("Disabled shake controls do not register sensors", controls.isRunning());
            controls.setEnabled(true);
            assertTrue(preferences.getBoolean(ENABLED, false));
            controls.close();
            assertTrue(preferences.edit().putBoolean(LEGACY_ENABLED, false).putInt(MODELS, 7).commit());
            controls = createControls();
            assertTrue("The new preference is authoritative after migration", controls.isEnabled());
            assertEquals(7, preferences.getInt(MODELS, -1));
            controls.setEnabled(false);
            controls.close();
            controls = createControls();
            assertFalse(controls.isEnabled());
        });
    }

    @Test public void invalidSwitchSafelyDisablesAndAnExplicitChoiceRecovers() {
        assertTrue(preferences.edit().putString(ENABLED, "on").putBoolean(LEGACY_ENABLED, true).commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            controls = createControls();
            assertFalse(controls.isEnabled());
            controls.start();
            assertFalse(controls.isRunning());
            assertFalse(preferences.getBoolean(ENABLED, true));
            controls.setEnabled(true);
            controls.close();
            controls = createControls();
            assertTrue(controls.isEnabled());
        });
    }

    private MotionCaptureControls createControls() {
        return new MotionCaptureControls(context, () -> fail("Settings tests must never trigger capture."));
    }
}
