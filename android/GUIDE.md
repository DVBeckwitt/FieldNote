# Fieldnote user guide

For Android 8.0 and later. The app runs offline without Google services or
Android network permission.
Root access is neither needed nor used.

## Install

For editable project folders and selectable offline transcription models, download
`Fieldnote-1.1.0-beta.2.apk` from the
[1.1.0 beta 2 release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v1.1.0-beta.2).
The [stable 1.0.0 release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v1.0.0)
also provides the Fieldnote Voice watch companion.
Transfer the APK to the phone and open it from Files. If prompted, allow that
installer source to install apps, then complete installation. The published
release uses the same signing certificate as earlier betas. Keep the existing
app installed to preserve its permissions, settings, and any pending captures.

The **Recorder** widget starts and stops audio directly on the home screen.
It replaces the older widget action that opened the floating control.
The separate **Record now** and **Floating mic** shortcuts remain available.
The microphone is blue while idle and turns purple with a radiating glow during
recording, in the app, floating control, and audio widget. The widget glow is
static; the app and floating control animate it when system settings allow.
The [validation summary](../docs/VALIDATION.md) records the checks and their limits.
See [Building Fieldnote](BUILDING.md) to build or test from source.

## First project and recording

1. Tap **New project** in the top bar, enter a name, and choose a writable
   parent folder in Android's picker. The first location becomes the default.
   Choose **Different folder** to use another location. Fieldnote creates an
   editable folder there for the project's files.
2. On Record, hold the large microphone. Grant microphone access if requested,
   then hold again to record. Press feedback is haptic when system settings allow.
   Its purple recording glow shows when audio is active. Release to finalize a
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
7. Drag upward on the recent-note card to reveal all notes within the main screen.
   The panel follows your finger and settles open or closed on release. Drag down
   from the top of the all-notes list to close it; short, reversed, or cancelled
   drags return to the state where you started. When the list is scrolled down,
   downward swipes scroll the list normally. **Back to Record** and Android's
   system Back also return to Record.

The recent note appears at the bottom of Record. Once saved, tap it to open
the entry or hold it to edit its name and text. Wait for notes labeled
**Saving to project…** before opening or editing them.
Tap a saved entry in all notes to view photos and play audio/video inside the
app; Metadata opens separately. Attaching media later preserves each original
capture timestamp. Names and text are stored in the project's metadata index.

While held, the microphone shrinks to 84% and follows your thumb's displacement
one-for-one from its fixed layout center. The home, guide, and targets keep that
same fixed origin; they do not move with the circle. Release returns the circle
home with a 230 ms easing animation. The gradient artwork compacts as your
drag grows, without changing the view's tracking or activation geometry. Hold
anywhere in the microphone area to start audio; if you press
outside home, return there before a directional action can trigger.
Holding reveals **Lock**, **Photo**, **Video**, **Text**, and the upper-right
marker target. Targets are farther out: activation is 112 dp from the fixed
origin, with a 28 dp central home region for rearming repeatable actions.
The guide uses narrow blue trails, translucent target rings, and a fading halo.
Haptic steps change on approach and retreat; crossing a target gives an activation
pulse. The idle microphone is blue; active audio or video uses a purple glow,
including locked, hands-free recording. Its glow animates while recording, and
the held control keeps its subtle shake. Animation and haptic feedback respect
system settings. The notes panel still follows your finger and
uses a smooth 180–260 ms settling animation after release.

Standalone video includes embedded audio when microphone permission is available.
Inline video during narration has no embedded audio: the voice stays in the
separate linked `.m4a`. Both originals remain in the same entry.

## Record from a Galaxy Watch

This setup uses the Fieldnote phone build with watch support and the separate
**Fieldnote Voice** app for Wear OS. Pair the watch with your Android phone first.
Install `Fieldnote-Voice-1.0.0.apk` from the same release on the watch using
[Wear OS wireless debugging](https://developer.android.com/training/wearables/get-started/debugging).
The watch app requires Wear OS 3 or newer. See [build and install commands](BUILDING.md#wear-os-companion).

1. In Fieldnote on the phone, open your destination project. In **Settings →
   Watch recordings → Choose watch**, grant **Nearby devices** access when asked
   and select your paired watch. The watch receiver stays active while the phone
   is locked. Use **Stop receiving** to turn it off.
2. Open **Fieldnote Voice** on the watch. Choose **Connect phone**, grant
   **Nearby devices** access when asked, and select your paired phone.
3. In Galaxy Wearable on your phone, go to **Watch settings → Buttons and gestures
   → Home button → Double press** and choose **Fieldnote Voice**. Some Watch4
   software versions list these controls under **Advanced features**.
4. On watches with **Settings → Buttons and gestures → Knock knock**, turn the
   gesture on, choose **Apps**, and select **Fieldnote Voice**. Make a fist and
   bend your wrist down and up twice to launch it. Wake and unlock the watch
   before using the gesture.
5. Double-press the upper watch button or use Knock knock to record. Grant
   microphone access the first time. Repeat the shortcut while recording, or tap
   **Stop**, to send the recording. Fieldnote saves the original
   `.m4a` and its capture times as a new audio entry in the project open when the
   phone begins receiving it. Keep that project open until the watch shows it
   was sent. Saving and transcription continue on the phone after the transfer.

An active recording continues if you go Home, open another app, or let the watch
display sleep. Allow notifications to use **Stop** from the recording notification.
You can also return to Fieldnote Voice and tap **Stop**, or repeat the configured
shortcut while the watch is unlocked.
Samsung's Knock knock shortcut requires the watch to be unlocked before it can
launch an app. The phone can remain locked while receiving a recording.
Long recordings save automatically as they approach the Bluetooth transfer size
limit. The watch shows that it saved at the limit; start another recording to
continue.

Bluetooth must be on and the phone receiver ready to receive. If the transfer
fails, the recording stays on the watch. Open the intended project on the phone,
then choose **Phone transfer → Send saved** on the watch and select the recording.
If the phone already began receiving that recording, the retry stays with the
original project. After restarting the phone or turning Bluetooth off, reopen
**Watch recordings** and select the watch to resume receiving.
Audio travels directly between the paired devices.

## Home-screen widget, shortcuts, and floating microphone

Open a project and grant microphone access in Fieldnote first. New **Recorder
widget** recordings use the project currently selected in Fieldnote. The
destination is fixed when you tap to start; opening another project while it
records does not move that recording. The separate **Record now** and **Floating
mic** shortcuts use the most recently created project for a new recording.
The Recorder widget stops current audio when tapped while recording; the shortcuts
reuse its project and entry instead of starting a duplicate.

In **Settings → Recording controls**, choose:

- **Add Record now shortcut** to request placement on your home screen. Confirm
  it in your launcher. You can also long-press the Fieldnote app icon to find
  **Record now** when your launcher supports app shortcuts.
- **Add Floating mic shortcut** to request a separate launcher shortcut that
  shows the overlay. You can also find **Floating mic** by long-pressing the app icon.
- **Add recording widget** for the single microphone button. Confirm
  placement in your launcher; if pinning is unavailable, add **Recorder**
  from the launcher's widget picker.
- **Show floating mic** to open the floating control directly, without adding
  a home-screen item.

Tapping **Record now** opens Fieldnote and starts locked, hands-free audio as
soon as the project and microphone permission are ready. Tap the microphone
to stop and save. It does not require holding the shortcut. An already-running
recording keeps its project and original file. If the floating control is open,
Record now hides it and keeps that audio locked on the main screen instead of
starting a duplicate recording. Finish floating video or text entry before this handoff.

Tap the blue **Recorder** widget to start hands-free audio in place, then tap it
again to stop and save. It stays on Smart Launcher or your other home screen:
it does not open Fieldnote or a floating circle. The widget needs microphone
permission, but no camera or **Display over other apps** permission. During audio
recording it shows a purple circle with a static radiating gradient. Tapping it
stops the current audio and any linked floating video, even if that recording
began in the app or floating mic. Finish standalone floating capture or text
entry before starting a new widget recording. While audio is
starting, another tap cancels it; while audio is finalizing, the button is
temporarily unavailable.

If microphone permission or a project is missing, recording does not start and
the widget keeps you on the home screen. It explains the needed setup and offers
a **Set up the recording widget** notification when notifications are allowed.
Tap that notification or open Fieldnote yourself to complete setup. Updates
refresh existing widgets to the new Recorder action. If your launcher keeps the
old action or artwork cached, remove the widget and add **Recorder** again.

The widget supports tap recording; full media swipes remain in the app and
floating control. Android's current standard widgets use
[RemoteViews layouts without custom views](https://developer.android.com/develop/ui/views/appwidgets#CreateLayout)
and have [limited gesture support](https://developer.android.com/develop/ui/views/appwidgets/overview#gestures).
Smart Launcher's documented
[third-party widget API](https://docs.smartlauncher.net/work-with-smart-launcher/third-party-widget-devs-apis)
covers configuration backup and restore, not embedding the custom hold-and-drag
microphone. These published APIs do not provide that full interaction in place;
root access alone does not extend their input contract.

For the floating control, use **Settings → Recording controls → Show floating mic** to finish setup.
Allow **Display over other apps** (sometimes called **Draw over other apps**)
and grant microphone and camera access when requested. Camera access is required
on phones with a camera so the media gestures work. Root access is not needed.

The floating mic uses the same touch actions: hold to record, release to save,
up to lock, left for a linked photo, right for video, down for text, and diagonally
upper-right for an audio marker. Return home to rearm repeated photos or markers.
Leaving the Video zone stops video while narration continues; releasing a held
capture ends its audio and video unless audio was explicitly locked. Text keeps
audio running through typing; **Save** links the text and ends audio, while
**Skip** ends audio without text. Quick gestures before audio starts can create
standalone media or text. A small camera preview appears beside or above the mic.

Drag the small six-dot handle above the circle to move it without recording.
Its position is remembered across closing/reopening and adapted to screen changes.
Movement stays within bounds that leave space for every swipe target. You can move
it while audio is locked; moving is unavailable during a held mic gesture, camera
capture, or text editor.

Swipe the microphone **diagonally down-right toward X** to close the floating
control and finish its active audio/video, including explicitly locked audio.
The close target appears with the other gesture guides and uses the same approach,
retreat, and activation haptics. Return to center before changing from a video
swipe to another action, including close. This close gesture belongs only to the
floating control; the main microphone keeps its existing gestures.
If the floating control belongs to another project, down-right still closes it
without stopping that project's audio. Other capture gestures stay unavailable.

The **Fieldnote floating microphone** notification remains available too.
**Hide** preserves explicitly locked narration, which has its own recording
notification; it ends an unlocked hold, camera capture, or narration being used
by the text editor. **Stop all** ends
the floating session's audio and video and hides the control. Turning the screen
off also hides the control; explicitly locked narration can continue. Finish
any floating camera capture or text editor before switching capture surfaces.
Shake to record works only on Record inside the main app, not
in the floating overlay.

## Shake to record

**Settings → Recording controls → Shake to record** enables one simple action. While Record is open
and focused with a project selected, shake the phone to start locked, hands-free
audio. Pause briefly, then shake again to stop and save. There is no required
direction, teaching, or separate photo/marker shake. You can also tap the locked
microphone to stop.

One shake burst triggers once. The detector waits for a quiet interval and a
cooldown before accepting another burst, so continued shaking does not immediately
stop the recording it just started. It uses the accelerometer; no extra permission
or root access is required. Physical phone sensitivity remains unverified.

The setting is on by default. Upgrades preserve an explicitly disabled motion
switch. Old trained profiles are no longer read or used. Photos, video, text,
and markers remain available through the microphone's touch gestures, including
during a shake-started recording.

Shake detection stops in the background, outside Record, while notes/settings or
other dialogs are open, and while the floating microphone is shown. The floating
microphone retains its touch controls; shaking does not operate the overlay.

## Search, transcripts, tags, and audio bookmarks

Open all notes and type in Search to filter the current project immediately.
Search matches names, note text, transcripts, tags, and audio marker labels,
ignoring case. Every word you enter must appear somewhere in the entry. Use the
horizontal tag chips for an exact tag match, **Markers only** for entries with
audio bookmarks, or both alongside search. **Clear filters** resets them.
Filters survive closing the panel and Android activity recreation, but are not
retained across a full app relaunch. The recent-note card is never filtered.

After each new audio recording is saved, Fieldnote automatically transcribes it
on the phone with the selected Vosk English model. This includes recordings made
in the app, through the widget or floating microphone, and on a paired watch.
Open the audio entry to read or copy its transcript. Each audio clip shows its
own transcript or status, including when it is waiting or no speech was detected.
Transcription runs in the background. The original remains playable if
recognition fails. Automatic transcription starts with new recordings.

In **Settings → Transcription**, choose **Small 0.15**, **LGraph 0.22**,
**Full 0.22**, or **GigaSpeech 0.42**. Small is bundled. For the other models,
tap **Get**, download the official model in your browser, extract it outside
Fieldnote, and choose the extracted model folder. Fieldnote checks the files
against the sizes and checksums in Vosk's archive as it copies them into private
storage. After installation, select the model in
**Transcription**. You can delete the original download and extracted copy from
your Downloads folder once the model is installed.

Full and GigaSpeech need about 2.87 GB and 4.03 GB of space for the installed
copy, respectively. They require a 64-bit phone with enough free memory to load
the model. If the selected large model's load is interrupted by process exit,
Fieldnote uses Small for new transcriptions after recovery. Select the large
model again to retry. Changing the selection applies to newly queued transcriptions.
Existing transcripts and transcriptions already in the queue retain their
original model.

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
Markers join the audio's queued save to the project folder after finalization.
Marking during capture only updates the local journal.

Open an entry to see each audio clip's markers with their times and labels. Tap
a marker to seek and play that clip. You can add one at the current playback
position, rename its label, or delete it. A clip supports up to 1,000 markers;
labels can contain up to 200 characters. Saved-clip marker edits update the
project index while preserving the audio original.

## Storage and settings

An editable project lives in a folder with `index.json` and `media/` originals.
Captures and note edits update those files as you work. Choose a storage provider
that supports creating and renaming files. The folder button beside Settings
offers **Open storage folder** in a compatible file explorer.

Tap **Finish** in the top bar, then choose **Finish and close**. Wait for
captures, pending saves, and transcription to finish. Fieldnote verifies the
project and closes it in the same folder. To continue editing, select it from
**Projects**. Copy the entire folder to keep the originals, capture times,
notes, and relationships together.

Use **Import project ZIP** and Android's picker to open a ZIP saved by an older
Fieldnote version. Fieldnote uses the default location for the editable folder
or asks you to choose one. The source ZIP stays in place.

In the portable index, `entries[].tags` is an array of normalized strings, and
`entries[].media[].markers` belongs to each audio original. A marker contains
`id`, `mediaId`, `offsetMs`, `label`, `createdAt`, and optionally `updatedAt`.
`mediaId` matches the containing media object's `id`; `offsetMs` is the position
in that clip in milliseconds. The label is optional; an empty label receives a
display name in the app. Annotation timestamps do not replace media capture times.
When present, `entries[].media[].transcript` belongs to a single audio original.
It contains a `status` and, when complete, the recognized `text`.

Finalized originals are first flushed to local storage with an atomic pending
journal. A background publisher verifies the saved originals and the new project
index. One writer processes overlapping audio, photo, and video arrivals, so you
can keep capturing while earlier originals are added. Finishing larger projects
can take longer because Fieldnote verifies the original files.

The publisher uses a separate in-process worker and Android's JobScheduler.
Newly staged media permits one prompt publication attempt during another
project's retry cooldown, without resetting the backoff for repeated failures.
A persisted job keeps pending saves recoverable across restarts;
the normal `RECEIVE_BOOT_COMPLETED` permission keeps it scheduled after reboot.
This uses no network, Google services, or root access.

Fieldnote allows up to 8 GiB per archive, 4 GiB per original, 10,000 archive members,
and a 32 MiB metadata index. JSON also has ceilings of 64 nesting levels and
250,000 tokens, counting property names, values, and container delimiters,
including unknown metadata. Very complex previously accepted indexes can be
rejected. A complexity failure preserves the source instead of treating it as
corruption. Legacy import preserves the source when its index exceeds the limits.

Working folders and finished ZIPs hold original media and plaintext metadata,
potentially including location and transcripts. App backup rules explicitly exclude
private/app-specific data from legacy backup, cloud backup, and device transfer;
these rules do not control your storage provider's sync, copies, or shared ZIPs.
Private playback caches and pending recovery data remain intentionally available.
Behavior across every manufacturer's backup/transfer implementation is unverified.

**Settings** groups options under **Recording controls**, **Transcription**,
**Watch recordings**, **Capture privacy**, **Project storage**, and **Appearance**.
Changing the default location in **Project storage** applies to future projects.
Existing folders and legacy ZIPs stay in their original locations.

For beta 1 projects, use **Settings → Project storage → Import an old project folder**. Fieldnote
copies the old folder to an editable project and preserves the source. On upgrade,
Fieldnote opens an editable copy of the currently selected legacy project using
the default location, or asks where to put the copy.

Metadata collection is on by default. Grant location access through Settings if
wanted; the switch does not bypass Android permissions. Values depend on granted
access and available hardware; location can be approximate, stale, or unavailable.
Sensor metadata are snapshots of available observations, not a continuous sensor log.
Device metadata does not collect persistent device identifiers. Capture times
remain when optional metadata is disabled.

If saving fails, internal pending data remain for recovery and automatic retries
back off between attempts. Keep the app and its data installed and restore access
or space. **Settings → Project storage → Retry pending saves** also retries in the background.
If the storage provider fails to read an existing ZIP, Fieldnote preserves it.
A provider read error does not trigger replacement of that ZIP from recovery data.
“Saved” requires finalized media and a verified project update. Wait for queued
captures to be saved before finishing the project. Very short or interrupted
captures may fail to produce usable media.

## Validation limits

Floating microphone recording and camera capture over the launcher have been
exercised on an Android API 36 emulator. The Recorder widget and Bluetooth watch
transfer have also been exercised on a physical Pixel 8 Pro and Galaxy Watch4
Classic. See the [validation summary](../docs/VALIDATION.md) for the scope and
limits of those checks. Other capture paths have not all been repeated on the
physical phone. Motion trace tests and simulated callbacks do not
establish real-world gesture detection or sensitivity; physical haptic feel also
remains untested. Hardware, permission revocation, storage-provider availability,
launcher behavior, and process interruption can affect capture and saving.
Some apps hide overlays on sensitive screens. Native UI tests use the debug
variant; they do not establish signed-release behavior on every device.
Android 8.0+ is supported by the minimum SDK declaration,
not a claim of testing every Android version. There is currently no CI workflow;
the recorded results are local validation.
