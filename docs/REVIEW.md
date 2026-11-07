# 0.2.0 review

Reviewed on 2026-11-06 against beta 23 (`8906abfed7f421fd6cbff84753694b15ae9151f7`).
This pass covered recording and widget lifecycle, project publication, archive
and component security boundaries, unused code, and notes-list work. Final
release checks and device limits are recorded in [VALIDATION.md](VALIDATION.md).

## Corrections and simplification

- Moved publisher queue-count scans from the main thread to its existing serial
  worker. A regression test first reproduced the UI waiting on the capture
  journal lock, then passed with the scan off the main thread. Count snapshots
  are read together under the journal lock and delivered in worker order.
- Removed the unreachable separate camera Activity and its manifest entry and
  controls. Active photo/video gestures continue through the existing inline
  camera. The media-saved broadcast keeps its original action string.
- Removed the unused pause-for-text action and state. Text entry already keeps
  narration running until submit/skip; that behavior and its duration checks
  remain. Historical metadata and recovery readers are retained.
- Removed unused storage helpers, intent extras, imports, and constructor
  parameters. Retained old widget and folder compatibility paths that still
  have consumers, along with the separate original browser prototype.
- Entry playback now schedules progress updates only while a card wants to
  play. Native playback checks cover idle, active, pause, and dismissal states.

## Measured notes-list work

Each sort now parses an entry's capture time once. Empty searches skip building
search text after applying tag and marker filters. Tests preserve chronological
order, ties, repeated object references, source data, and exact search results.

One before/after comparison used a fixed shuffled synthetic notebook on the
dedicated API 36 emulator, with two warmups and seven samples per operation.
The predeclared target was a greater than 20% reduction in median time at 1,000
entries. Both targeted helpers exceeded it:

| 1,000-entry operation | Before median (range), ms | After median (range), ms |
| --- | --- | --- |
| Snapshot and sort | 599.936 (573.731–615.769) | 11.737 (11.450–23.084) |
| Empty-query filter | 22.546 (19.492–29.551) | 0.224 (0.222–0.264) |

The unchanged nonblank-search control also improved, so runtime warming,
garbage collection, and emulator scheduling remain confounders. These are
helper timings, not full-screen rendering or physical-phone performance claims.
Archive I/O, media decoding, and Smart Launcher were not benchmarked. Both
timing runs passed the same five correctness tests; raw samples and source
hashes were retained with local release evidence.

## Security and release configuration

The review checked ZIP expansion/member limits, canonical and flat archive
paths, duplicate/case-collision rejection, CRC and SHA checks, bounded JSON
parsing, private components, session-bound widget Stop actions, read-only media
grants, backup exclusions, and metadata opt-out/coarse-location behavior.
No new exploitable boundary bypass was confirmed in that scope; this is not an
exhaustive security guarantee. Project ZIPs intentionally contain plaintext
originals and metadata.

Release builds no longer silently use a debug signing identity. They are
unsigned by default, accept a complete external signing configuration, or allow
an explicit compatibility build using the existing beta keystore. Partial
configuration is rejected. The published update retains the existing certificate;
no signing key is committed. See [BUILDING.md](../android/BUILDING.md).

The app bundles no third-party runtime dependencies. The Gradle distribution
is HTTPS/checksum pinned at 8.14.6, whose
[release notes](https://docs.gradle.org/8.14.6/release-notes.html) include the
applicable Gradle security fixes. This was a focused dependency/toolchain review,
not an exhaustive advisory scan. No new network permission, root requirement,
or project storage format was introduced.
