# Fieldnote 0.2.0-beta.15

Android beta (version code 16) for Android 8.0 and later.
No Google services are needed; no network permission is requested.
Root access is neither needed nor used.

## Install

Download the APK from the [beta 15 release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.15).
Transfer it to the phone and open it from Files. If prompted, allow that
installer source to install apps, then complete installation. This personal beta
uses the local signing certificate; updates over earlier betas must use the same
certificate. Keep the existing app installed to preserve its permissions,
settings, and any pending captures.

Beta 15 bounds project JSON complexity, prevents complexity failures from
silently selecting an older legacy backup, updates the build wrapper, and adds
explicit app-backup exclusions. Capture controls and the ZIP format remain.
The [validation summary](../docs/VALIDATION.md) records the checks and their limits.
See [Building Fieldnote](BUILDING.md) to build or test from source.

## First project and recording

1. Create a project and choose a writable parent folder in Android's picker.
   The first location becomes the default; **Different folder** overrides it for
   a new project. Fieldnote creates `<project-name>.zip` there.
2. On Record, hold the large microphone. Grant microphone access if requested,
   then hold again to record. Press feedback is haptic when system settings allow.
   Its distinct recording blue shows when audio is active. Release to finalize a
   separate AAC `.m4a` file and queue its project save. Audio starts after the
   short 180 ms hold threshold; quicker directional gestures select an action.
3. Drag upward to lock narration. On release, the microphone returns home with
   a bold locked appearance. Tap it to stop and queue the recording for saving.
4. Keep holding and drag left to take a photo. Return to the center home position
   to rearm Photo, then drag left again for another. Repeat during the same hold;
   the photos join one continuous audio recording without extra shutter taps.
   Rapid photo requests finish in order. Release ends audio while photos already
   requested finish saving into that same entry.
5. Drag right into the Video zone to start inline video. Leave that zone to stop
   video only. Audio never pauses as you move among capture zones; you stay on
   Record and all media join the same entry. Release to stop and save the held
   audio/video capture; these gestures do not lock recording. If you explicitly
   swiped up to lock first, releasing stops the gesture's video and keeps narration
   active. In that locked session, **Stop video** also leaves voice recording; tapping
   the locked microphone stops both audio and video.
   Return to center and swipe to Photo or Text while the video finishes saving;
   these actions remain linked to the same narration.
6. Drag down while audio is recording to open the text editor. Audio continues
   throughout typing. **Save** stops audio at submission and links the text in the
   same entry; **Skip** stops and saves audio without adding text. The typing
   interval is included in captured audio duration. Fast left/right/down gestures
   before the hold threshold create standalone photo/video/text notes instead.
7. Swipe horizontally away from the microphone to switch between Record and Files.
   Tab changes are ignored during a microphone hold, preserving its release behavior.
   Drag upward on the recent-note card to reveal all notes within the main screen.
   The panel follows your finger and settles open or closed on release. Drag down
   from the top of the all-notes list to close it; short, reversed, or cancelled
   drags return to the state where you started. When the list is scrolled down,
   downward swipes scroll the list normally. **Back to Record** and Android's
   system Back also return to Record.

The recent note appears at the bottom of Record. Once published, tap it to open
the entry or hold it to edit its name and text. Notes labeled **Adding to ZIP**
are still pending and cannot be opened or edited until publication finishes.
Tap a published entry in all notes to view photos and play audio/video inside the
app; Metadata opens separately. Attaching media later preserves each original
capture timestamp. Names and text are stored in the project's metadata index.

While held, the microphone shrinks to 84% and follows your thumb's displacement
one-for-one from its fixed layout center. The home, guide, and targets keep that
same fixed origin; they do not move with the circle. Release returns the circle
home with a 230 ms easing animation. The blue gradient artwork compacts as your
drag grows, without changing the view's tracking or activation geometry. Hold
anywhere in the microphone area to start audio; if you press
outside home, return there before a directional action can trigger.
Holding reveals **Lock**, **Photo**, **Video**, **Text**, and the upper-right
marker target. Targets are farther out: activation is 112 dp from the fixed
origin, with a 28 dp central home region for rearming repeatable actions.
The guide uses narrow blue trails, translucent target rings, and a fading halo.
Haptic steps change on approach and retreat; crossing a target gives an activation
pulse. Subtle held-state shake and soft blue glow remain. Animation and haptic
feedback respect system settings. The notes panel still follows your finger and
uses a smooth 180–260 ms settling animation after release.

Standalone video includes embedded audio when microphone permission is available.
Inline video during narration has no embedded audio: the voice stays in the
separate linked `.m4a`. Both originals remain in the same entry.

## Motion controls

**Settings → Motion controls** is on by default. Motion detection runs only when
Record is visible and focused, with a project open and no dialog open. By default,
with the screen facing you, a quick push away followed by a stop toggles locked,
hands-free audio. During recording, a short clockwise or counterclockwise twist
around the screen's center, then a stop, takes a linked photo. A motion for Marker
must first be taught; Photo and Marker motions act only while audio is recording.

Settings has separate **Audio**, **Photo**, and **Marker** sensitivity sliders
from 0 to 100; higher means easier to trigger. Raise only the relevant slider if
an intended motion is missed, or lower it if ordinary movement triggers an action.
These sliders tune phone-motion detection, not the microphone's touch distances.

For each action, choose **Teach motion**, then **Start learning**. Hold still,
demonstrate your chosen short motion, pause, and repeat until three examples are
accepted. Hold the phone as you will while recording, and choose motions distinct
from those assigned to other actions. Three accepted examples save automatically;
Cancel or an error preserves the previous learned motion. Motions can use any
direction, with natural variation in angle, timing, and strength. Learned motions
take priority over built-in gestures, and ambiguous matches are ignored.

**Reset motion** restores Audio's forward push or Photo's clockwise/counterclockwise
twist. For Marker it clears the assignment; use Teach motion again to enable it.

If saved motion profiles cannot be read, Settings shows a warning and motion
controls stay off. The unreadable data are retained until you explicitly reset or
teach a motion; changing sensitivity alone does not replace them. After repair,
enable Motion controls again. An invalid saved switch value is also turned off
with a warning to review the settings.

The microphone's touch swipes still work during motion-started narration. Lift
your thumb after a photo, video, or marker gesture and narration keeps running;
use your Audio motion or tap the locked microphone to stop. Pause between motions
to let the detector rearm.

Normal capture recognition stops outside Record, in the background, and while
dialogs are open. The teaching dialog temporarily samples sensors for learning;
it does not trigger capture actions. Normal recognition resumes after closing
Settings and returning to Record.
Detection requests 50 Hz linear acceleration where available, otherwise filtered
raw acceleration, plus the gyroscope for twists. It needs no extra permissions, root
access, or always-running sensor service. Physical Pixel/GrapheneOS gesture
sensitivity is unverified; software traces and simulated callbacks cannot replace
testing the actual phone in hand.

## Search, tags, and audio bookmarks

Open all notes and type in Search to filter the current project immediately.
Search matches names, note text, tags, and audio marker labels, ignoring case;
every word you enter must appear somewhere in the entry. Use the horizontal tag
chips for an exact tag match, **Markers only** for entries with audio bookmarks,
or both alongside search. **Clear filters** resets them. Filters survive closing
the panel and Android activity recreation, but are not retained across a full
app relaunch. The recent-note card and Files are never filtered.

The text editor includes **Tags, separated by commas** for new and existing
notes. Tags become lowercase, leading `#` characters are removed, and duplicates
are combined. An entry can have up to 24 tags, each at most 40 characters.

While holding to record, drag diagonally upper-right to add a marker with the same
thumb. Return home, then move upper-right again to mark another moment. This
keeps the same audio recording running until release, unless you explicitly lock
it. A quick upper-right gesture before the hold threshold starts audio and requests
a marker once recording becomes active; its offset need not be exactly zero.
Left still takes photos, right records video, up locks,
and down opens text. Alternatively, tap **Mark moment** with a second finger
while held, or simply tap it when narration is locked.

A marker's time is an elapsed offset in that specific audio clip, measured with
a monotonic clock so wall-clock changes do not shift it. The marker count, last-marker time, and
haptic confirmation update after its durable local journal write succeeds.
Markers join the audio's queued save to the same project ZIP after finalization;
marking during capture does not rewrite the archive or modify the audio file.

Open an entry to see each audio clip's markers with their times and labels. Tap
a marker to seek and play that clip. You can add one at the current playback
position, rename its label, or delete it. A clip supports up to 1,000 markers;
labels can contain up to 200 characters. Saved-clip marker edits update the
project ZIP while preserving the audio original.

## Storage and settings

The project ZIP is the canonical saved record: `index.json` plus `media/` originals.
All notes, attachments, name/text edits, tags, and finalized audio markers update
this existing ZIP. Text and entry names live in `index.json`; media originals
remain separate archive members.
When the ZIP is up to date, copying it preserves all files and relationships; use
**Open project ZIP** and the document picker to reopen it. Files lists only the
canonical archive's contents. **Share project ZIP** is blocked during active
capture or pending saves; tap it again after they finish to share the completed
project. A hidden app database is not needed for a
completed project; the app's cached verified index is derived from the archive.

In the portable index, `entries[].tags` is an array of normalized strings, and
`entries[].media[].markers` belongs to each audio original. A marker contains
`id`, `mediaId`, `offsetMs`, `label`, `createdAt`, and optionally `updatedAt`.
`mediaId` matches the containing media object's `id`; `offsetMs` is the position
in that clip in milliseconds. The label is optional; an empty label receives a
display name in the app. Annotation timestamps do not replace media capture times.

Finalized originals are first flushed to local storage with an atomic pending
journal. A separate background publisher then rewrites and verifies the complete
ZIP. One writer processes overlapping audio, photo, and video arrivals, so you
can keep capturing while earlier originals are added. Pending recent/all-notes
cards show **Adding to ZIP**; wait until publication completes before copying the
ZIP outside the app. Recording markers first use the local journal described
above. Larger projects take longer and need extra temporary phone storage, plus
enough destination space for the enlarged archive.

The publisher uses a separate in-process worker and Android's JobScheduler.
Newly staged media permits one prompt publication attempt during another
project's retry cooldown, without resetting the backoff for repeated failures.
A persisted job keeps pending saves recoverable across restarts;
the normal `RECEIVE_BOOT_COMPLETED` permission keeps it scheduled after reboot.
This uses no network, Google services, or root access.

This beta allows up to 8 GiB per archive, 4 GiB per original, 10,000 archive members,
and a 32 MiB metadata index. JSON also has ceilings of 64 nesting levels and
250,000 tokens, counting property names, values, and container delimiters,
including unknown metadata. Very complex previously accepted indexes can be
rejected. A complexity failure preserves the source instead of treating it as
corruption; legacy import will not substitute an older backup or create a ZIP
from that stale index.

Project ZIPs hold original media and plaintext metadata, potentially including
location. This beta does not encrypt them. App backup rules explicitly exclude
private/app-specific data from legacy backup, cloud backup, and device transfer;
these rules do not control your storage provider's sync, copies, or shared ZIPs.
Private playback caches and pending recovery data remain intentionally available.
Behavior across every manufacturer's backup/transfer implementation is unverified.

**Settings** changes the default save location, optional metadata, motion controls,
and dark navy/light-blue appearance. The launcher uses the prototype's fₙ mark.
Changing the default location does not move existing project ZIPs.

For beta 1 projects, use **Settings → Import an old project folder**. Conversion
creates a ZIP and preserves the old folder. An already selected old project
converts using the remembered default location; otherwise Fieldnote asks where to
save its ZIP. The original folder remains available after conversion.

Metadata collection is on by default. Grant location access through Settings if
wanted; the switch does not bypass Android permissions. Values depend on granted
access and available hardware; location can be approximate, stale, or unavailable.
Sensor metadata are snapshots of available observations, not a continuous sensor log.
Device metadata does not collect persistent device identifiers. Capture times
remain when optional metadata is disabled.

If saving fails, internal pending data remain for recovery and automatic retries
back off between attempts. Keep the app and its data installed and restore access
or space. **Settings → Retry pending saves** also retries in the background.
If the storage provider fails to read an existing ZIP, Fieldnote preserves it.
A provider read error does not trigger replacement of that ZIP from recovery data.
“Saved” requires finalized media and a verified ZIP write; a queued capture is not
yet in the portable ZIP. Very short or interrupted captures may fail instead of
producing usable media.

## Validation limits

Beta 15 passed release assembly, 86 JVM tests, and 76 native API 36 emulator tests
(30 capture/UI, 21 storage, 9 JSON-security, 5 queue, 3 motion-settings, 3 entry-data,
2 inline-camera, 2 security-configuration, and 1 guide test). All 162 tests had zero
failures, errors, or skips. Lint reported 0 errors and 68 warnings; only the prior
backup-rules warning was removed. The delivered APK matches the final build; its
v2 signature, manifest, and backup resources were verified. The signed release
denied an ungranted provider query and recording-service start from shell UID 2000.
Dark/light Record and notes-filter screenshots were reviewed. See the
[validation summary](../docs/VALIDATION.md).
Emulator tests do not establish physical Pixel/GrapheneOS behavior; that
device has not been tested. Motion trace tests and simulated callbacks do not
establish real-world gesture detection or sensitivity; physical haptic feel also
remains untested. Hardware, permission revocation, storage-provider availability,
and process interruption can affect
capture and saving. Native UI tests use the debug variant; signed-release checks
were limited to artifact inspection and the two access-denial probes. This pass
did not include a signed-release UI/upgrade exercise or full URI grant/revoke
testing. Android 8.0+ is supported by the minimum SDK declaration,
not a claim of testing every Android version. There is currently no CI workflow;
the recorded results are local validation.
