<img src="docs/logo.svg" alt="Fieldnote logo" width="80">

# Fieldnote

This app was inspired as I was taking notes for lab work and wanted a more streamlined note taking method. No open-source projects existed so I made this. 
It stays completely offline so there is no data concerns. 
For transparency, I used Codex Astra to *assist* (not write code or UI blindly) in the creation of this app as Python is my native language.  

Android lab notebook for audio, photos, video, and text, organized by project.

- **Capture with one hand.** Hold the microphone to record; swipe to take photos, record video, add text, or mark a moment. The camera preview stays with the recording control.
- **Capture from your home screen.** Tap the Recorder widget to start or stop audio in the currently selected Fieldnote project. Record now starts in the app; Floating mic keeps the full swipe control over your launcher. Those separate shortcuts use your latest-created project for new recordings.
- **Record from your watch.** Fieldnote Voice sends audio over Bluetooth into your current phone project. Assign the Galaxy Watch double-press or Knock knock shortcut to start and stop. Recording continues in the background and with the display asleep.
- **Keep related media together.** Attach photos, video, text, tags, and timestamped markers to an audio note. Search and filter your notes later.
- **Transcribe recordings offline.** The phone automatically transcribes newly saved audio, including watch recordings, with a bundled English speech model. Read or search the transcript in its project.
- **See when you're recording.** The blue microphone turns purple with a radiating glow during recording. Choose dark navy or light blue; dark mode is the default. On Record, shake once to start audio and again to stop.

Use **New project** in the top bar to start an editable folder in your chosen storage location. When you are done, tap **Finish** to save a verified portable ZIP and close the project. Import a finished ZIP to add more notes or edit an existing entry. The exported ZIP contains the original files and their capture times, plus a JSON index with metadata, transcripts, and relationships.

Works without an account, Google services, or root access. Android 8.0 or newer.

[Download Fieldnote 1.1.0 beta](https://github.com/DVBeckwitt/FieldNote/releases/tag/v1.1.0-beta.1) for working folders and offline transcription. The [stable 1.0.0 release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v1.0.0) provides Fieldnote and the Fieldnote Voice watch companion.

<img src="docs/screenshot.png" alt="Fieldnote in dark mode, showing the large recording control and latest audio/photo note" width="320">

[User guide](android/GUIDE.md) · [Build instructions](android/BUILDING.md) · [Validation](docs/VALIDATION.md) · [MIT license](LICENSE)
