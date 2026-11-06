# Capture metadata in the beta

`MetadataCollector.snapshot(Context)` is best effort. It never waits for a new
GPS fix or sensor event. Recorder/camera start and end times are separate from
the metadata snapshot's `observedAt`. No metadata failure should prevent saving
an original media file.

Call `start(this)` and `stop(this)` with each Activity or recording Service
instance. Ownership is tracked separately: stopping the notebook Activity does
not stop the camera or recorder's sensor collection. Live location requests run
only while a started Activity is registered. A microphone service alone does not
start background location requests. Recordings in the background may therefore
contain an explicitly aged last-known fix instead of a new fix.

The preference `fieldnote` / `metadata_enabled` disables optional collection.
Timestamp, timezone name, offset, and monotonic timestamp remain present. Calling
`start` again re-evaluates that preference and current location permissions.

The beta stores available device/OS/app information, display rotation, battery
state, internal staging filesystem free bytes, optional location, the sensor
inventory, and latest observations from ordinary motion/environment sensors.
It does not collect device identifiers, contacts, Wi-Fi identifiers, installed
apps, body/health data, or permission-protected unrelated information.

Location records include the fix timestamp, monotonic age, provider, available
accuracy/altitude/speed/bearing values and the platform mock flag. Fine permission
is recorded as a permission fact, not a guarantee of precise coordinates.
Locations older than two minutes are marked `stale`; the coordinates remain
available for inspection with their age. No fix is invented when permission,
providers, or observations are missing.

Sensor values keep Android's units, event monotonic timestamps, accuracy status,
and device-natural coordinate axes. Observations older than ten seconds are
marked `stale`, including on-change sensors whose last event was older. Derived
wall-clock times are labeled estimates. This is a snapshot, not a continuous
scientific sensor log. Availability, calibration, device quality, clock changes,
and permissions limit interpretation.

Camera-specific exposure and capture settings are added by the camera code;
audio codec/settings are added by the recorder. Project storage uses its own
capture timestamps and per-file links independently of this metadata object.

Sources:

- [Android SensorEvent timestamps, units and axes](https://developer.android.com/reference/android/hardware/SensorEvent)
- [Android Location fix timestamps, accuracy and mock status](https://developer.android.com/reference/android/location/Location)
- [Android runtime location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)
