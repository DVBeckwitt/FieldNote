# Prototype model

`model.js` is a dependency-free ES module. All captures are simulated; it never
records audio, writes media, queries sensors, or claims to have captured a real
location. The UI owns localStorage persistence.

## API

- `createInitialState(now = new Date())`: returns schema version 1, two projects,
  and three sample entries in Thin-film deposition. Optical bench starts empty.
- `getProject(state, id = state.currentProjectId)`: returns the selected project;
  throws if the ID is unknown.
- `createProject(state, {name, folder = state.defaultFolder, now})`: mutates state,
  adds and selects the project, and returns it. `folder` is the parent location;
  `project.storageRoot` stores that parent and `project.folder` appends the project
  name. Duplicate folder names are rejected. This does not change the default.
- `addCapture(state, {kind, startedAt, endedAt, entryId, now})`: mutates state and
  returns `{project, entry, media}`. `kind` is audio, photo, or video. Each capture
  gets its own unique ID and UTC timestamped filename. Without `entryId`, a new
  entry is added. With it, a file is attached to an existing entry in the current
  project. The entry's original creation time and previous media stay unchanged.
  `startedAt` defaults to `now`; `endedAt` defaults to `startedAt`. Dates can be
  Date objects or ISO date strings; end must not precede start.
- `formatFilename(kind, date, id)`: produces the planned filename from UTC capture
  time, media type, and an ID suffix. UUIDs prevent filename collisions.
- `projectManifest(project)`: returns a detached, JSON-compatible metadata index
  preview with relative `media/…` paths and per-entry media relationships. It
  explicitly states that the media binaries do not exist.
- `restoreState(raw)`: parses a JSON string (or accepts an object), validates
  structural invariants, and returns an independent copy. Missing, corrupt, or
  incompatible saved data returns a new sample state.

New entries append in creation order. A timeline may sort entries by `createdAt`.
Each media item has its own `startedAt`, `endedAt`, `durationMs`, `simulated: true`,
and `metadata`. The metadata contains browser timezone information and explicit
unavailable/simulated sensor states. Sample media additionally have
`metadata.sample: true`. Optional sample entry titles are UI demonstration text.
No project manifest depends on an absolute filesystem path or a hidden database.

Run behavioral checks from the project root with Node.js 20 or newer:

```sh
node --test prototype/model.test.mjs
```

The parent project must mark JavaScript as ES modules (`"type": "module"`) when
using Node versions that do not infer module syntax.
