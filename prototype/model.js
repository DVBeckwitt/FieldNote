/** State and portable index preview for Fieldnote's simulated design prototype. */
export const SCHEMA_VERSION = 1;
const KINDS = { audio: 'm4a', photo: 'jpg', video: 'mp4' };
let fallbackSequence = 0;

function identifier(prefix) {
  const token = globalThis.crypto?.randomUUID?.() ??
    `${Date.now().toString(36)}-${(++fallbackSequence).toString(36)}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}-${token}`;
}

function timestamp(value, name = 'timestamp') {
  const date = value instanceof Date ? value : new Date(value);
  if (value === null || value === undefined || !Number.isFinite(date.getTime())) {
    throw new TypeError(`A valid ${name} is required.`);
  }
  return date.toISOString();
}

function safeFolderName(name) {
  const result = name.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '-').replace(/[. ]+$/g, '').trim();
  if (!result || result === '.' || result === '..') throw new TypeError('Enter a project name.');
  return result;
}

function timezoneMetadata(date) {
  return {
    name: Intl.DateTimeFormat().resolvedOptions().timeZone || 'unknown',
    utcOffsetMinutes: -new Date(date).getTimezoneOffset(),
    source: 'preview-browser',
  };
}

export function formatFilename(kind, date, id) {
  if (!Object.hasOwn(KINDS, kind)) throw new TypeError('Unsupported media kind.');
  if (typeof id !== 'string' || !id) throw new TypeError('A unique media ID is required.');
  const stamp = timestamp(date).replace(/[-:]/g, '').replace('.', '-');
  return `${kind}_${stamp}_${id.replace(/[^a-zA-Z0-9-]/g, '-')}.${KINDS[kind]}`;
}

export function getProject(state, id = state.currentProjectId) {
  const project = state.projects.find((item) => item.id === id);
  if (!project) throw new RangeError('Project not found.');
  return project;
}

export function createProject(state, { name, folder = state.defaultFolder, now = new Date() }) {
  if (typeof name !== 'string' || !name.trim()) throw new TypeError('Enter a project name.');
  if (typeof folder !== 'string' || !folder.trim()) throw new TypeError('Choose a project folder.');
  const cleanName = name.trim();
  const storageRoot = folder.trim().replace(/[\\/]+$/g, '');
  const folderName = safeFolderName(cleanName);
  const projectFolder = `${storageRoot}/${folderName}`;
  if (state.projects.some((project) => project.folder.toLowerCase() === projectFolder.toLowerCase())) {
    throw new RangeError('A project already uses this folder. Choose another name or location.');
  }
  const project = {
    id: identifier('project'), name: cleanName, storageRoot, folder: projectFolder,
    createdAt: timestamp(now), entries: [],
  };
  state.projects.push(project);
  state.currentProjectId = project.id;
  return project;
}

export function addCapture(state, { kind, startedAt, endedAt, entryId, now = new Date() }) {
  if (!Object.hasOwn(KINDS, kind)) throw new TypeError('Unsupported media kind.');
  const start = timestamp(startedAt ?? now, 'recording start time');
  const end = timestamp(endedAt ?? start, 'recording end time');
  if (new Date(end) < new Date(start)) throw new RangeError('Capture cannot end before it starts.');
  const project = getProject(state);
  let entry = entryId ? project.entries.find((item) => item.id === entryId) : null;
  if (entryId && !entry) throw new RangeError('The entry is not in the current project.');
  const mediaId = identifier('media');
  const media = {
    id: mediaId, kind, filename: formatFilename(kind, start, mediaId),
    startedAt: start, endedAt: end,
    durationMs: new Date(end).getTime() - new Date(start).getTime(),
    simulated: true,
    metadata: {
      captureMode: 'simulated',
      location: { status: 'simulated', coordinates: null },
      device: { status: 'unavailable', model: null },
      sensors: { status: 'unavailable' },
      timezone: timezoneMetadata(start),
      binaryAvailable: false,
    },
  };
  if (!entry) {
    entry = { id: identifier('entry'), createdAt: start, media: [] };
    project.entries.push(entry);
  }
  entry.media.push(media);
  return { entry, media, project };
}

export function createInitialState(now = new Date()) {
  const current = timestamp(now);
  const state = {
    schemaVersion: SCHEMA_VERSION,
    currentProjectId: null,
    defaultFolder: 'Documents/Fieldnote',
    projects: [],
  };
  const project = createProject(state, { name: 'Thin-film deposition', now: current });
  const day = current.slice(0, 10);
  const sample = (time) => `${day}T${time}.000Z`;
  const first = addCapture(state, {
    kind: 'audio', startedAt: sample('14:12:08'), endedAt: sample('14:12:32'),
  });
  first.entry.title = 'Baseline pressure';
  const mixed = addCapture(state, { kind: 'photo', startedAt: sample('14:18:06') });
  mixed.entry.title = 'Apparatus setup';
  addCapture(state, {
    kind: 'audio', startedAt: sample('14:18:22'), endedAt: sample('14:18:39'),
    entryId: mixed.entry.id,
  });
  const video = addCapture(state, {
    kind: 'video', startedAt: sample('14:24:15'), endedAt: sample('14:24:27'),
  });
  video.entry.title = 'First deposition run';
  for (const entry of project.entries) {
    for (const media of entry.media) media.metadata.sample = true;
  }
  createProject(state, { name: 'Optical bench', now: current });
  state.currentProjectId = project.id;
  return state;
}

export function projectManifest(project) {
  return {
    schemaVersion: SCHEMA_VERSION,
    format: 'fieldnote-project-preview',
    prototype: true,
    notice: 'Simulated captures only. This index demonstrates relationships; referenced media binaries do not exist.',
    project: {
      id: project.id, name: project.name, createdAt: project.createdAt,
    },
    timestampConvention: 'ISO 8601 UTC; original capture timezone stored per media item',
    entries: project.entries.map((entry) => ({
      id: entry.id,
      createdAt: entry.createdAt,
      ...(entry.title ? { title: entry.title } : {}),
      media: entry.media.map((media) => ({
        ...structuredClone(media),
        path: `media/${media.filename}`,
      })),
    })),
  };
}

function isString(value) { return typeof value === 'string' && value.trim().length > 0; }
function isTimestamp(value) { return typeof value === 'string' && Number.isFinite(Date.parse(value)); }

function validState(state) {
  if (!state || state.schemaVersion !== SCHEMA_VERSION || !isString(state.defaultFolder) ||
      !Array.isArray(state.projects) || !state.projects.length) return false;
  const projectIds = new Set();
  const entryIds = new Set();
  const mediaIds = new Set();
  for (const project of state.projects) {
    if (!project || !isString(project.id) || projectIds.has(project.id) ||
        !isString(project.name) || !isString(project.folder) || !isTimestamp(project.createdAt) ||
        !Array.isArray(project.entries)) return false;
    projectIds.add(project.id);
    const filenames = new Set();
    for (const entry of project.entries) {
      if (!entry || !isString(entry.id) || entryIds.has(entry.id) || !isTimestamp(entry.createdAt) ||
          !Array.isArray(entry.media) || !entry.media.length) return false;
      entryIds.add(entry.id);
      for (const media of entry.media) {
        if (!media || !isString(media.id) || mediaIds.has(media.id) ||
            !Object.hasOwn(KINDS, media.kind) || !isString(media.filename) ||
            /[\\/]/.test(media.filename) || filenames.has(media.filename) ||
            !isTimestamp(media.startedAt) || !isTimestamp(media.endedAt) ||
            Date.parse(media.endedAt) < Date.parse(media.startedAt) ||
            !Number.isFinite(media.durationMs) || media.durationMs < 0 ||
            media.durationMs !== Date.parse(media.endedAt) - Date.parse(media.startedAt) ||
            media.simulated !== true || !media.metadata ||
            media.metadata.binaryAvailable !== false || !media.metadata.location ||
            media.metadata.location.coordinates !== null) return false;
        mediaIds.add(media.id);
        filenames.add(media.filename);
      }
    }
  }
  return projectIds.has(state.currentProjectId);
}

/** Invalid, absent, or older-schema local state resets to a safe sample notebook. */
export function restoreState(raw) {
  try {
    const state = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return validState(state) ? structuredClone(state) : createInitialState();
  } catch {
    return createInitialState();
  }
}
