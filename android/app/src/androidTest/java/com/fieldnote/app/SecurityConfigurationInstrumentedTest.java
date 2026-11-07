package com.fieldnote.app;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Installed configuration checks; these do not simulate another UID or an OEM backup transport. */
@RunWith(AndroidJUnit4.class)
public final class SecurityConfigurationInstrumentedTest {
    @Test public void sensitiveComponentsRemainPrivateOrSystemProtected() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager packages = context.getPackageManager();
        assertTrue(packages.getActivityInfo(new ComponentName(context, MainActivity.class), 0).exported);
        assertThrows("The retired standalone camera must not remain registered",
                PackageManager.NameNotFoundException.class, () -> packages.getActivityInfo(
                        new ComponentName(context.getPackageName(), "com.fieldnote.app.CameraActivity"), 0));
        assertFalse(packages.getServiceInfo(new ComponentName(context, RecordingService.class), 0).exported);
        assertFalse(packages.getServiceInfo(new ComponentName(context, FloatingRecorderService.class), 0).exported);
        assertFalse(packages.getReceiverInfo(new ComponentName(context, RecordingWidget.class), 0).exported);
        assertFalse(packages.getReceiverInfo(new ComponentName(context, RecordingWidgetControlReceiver.class), 0).exported);
        ServiceInfo publisher = packages.getServiceInfo(new ComponentName(context, ProjectPublisher.class), 0);
        assertTrue(publisher.exported);
        assertEquals("android.permission.BIND_JOB_SERVICE", publisher.permission);
        ProviderInfo media = packages.getProviderInfo(new ComponentName(context, ArchiveMediaProvider.class), 0);
        assertFalse(media.exported);
        assertTrue(media.grantUriPermissions);
        PermissionInfo internal = packages.getPermissionInfo(context.getPackageName() + ".INTERNAL", 0);
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, internal.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
        assertEquals(PackageManager.PERMISSION_DENIED,
                packages.checkPermission("android.permission.INTERNET", context.getPackageName()));
    }

    @Test public void backupConfigurationExcludesPrivateDataInEverySupportedMode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        assertExcludedDomains(context, R.xml.backup_rules, "full-backup-content");
        assertExcludedDomains(context, R.xml.data_extraction_rules, "cloud-backup", "device-transfer");
    }

    private static void assertExcludedDomains(Context context, int resource, String... modes) throws Exception {
        Set<String> expectedModes = new HashSet<>(Arrays.asList(modes));
        Set<String> domains = new HashSet<>(Arrays.asList("root", "file", "database", "sharedpref", "external",
                "device_root", "device_file", "device_database", "device_sharedpref"));
        Map<String, Set<String>> excluded = new HashMap<>();
        String mode = null;
        try (XmlResourceParser xml = context.getResources().getXml(resource)) {
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                String name = xml.getName();
                if (event == XmlPullParser.START_TAG) {
                    assertNotEquals("Private data must not be added to backup", "include", name);
                    if (expectedModes.contains(name)) {
                        mode = name;
                        assertNull("Duplicate backup mode", excluded.put(mode, new HashSet<>()));
                    } else if ("exclude".equals(name)) {
                        assertNotNull("An exclusion belongs to a backup mode", mode);
                        assertEquals(".", xml.getAttributeValue(null, "path"));
                        assertTrue(excluded.get(mode).add(xml.getAttributeValue(null, "domain")));
                    }
                } else if (event == XmlPullParser.END_TAG && expectedModes.contains(name)) {
                    mode = null;
                }
            }
        }
        assertEquals(expectedModes, excluded.keySet());
        for (Set<String> actual : excluded.values()) assertEquals(domains, actual);
    }
}
