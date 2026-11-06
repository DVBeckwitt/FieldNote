import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createInitialState, createProject, addCapture, getProject, projectManifest,
  formatFilename, restoreState,
} from './model.js';

const now = new Date('2026-11-05T16:00:00.000Z');
function emptyState() {
  const state = createInitialState(now);
  state.currentProjectId = state.projects[1].id;
  return state;
}

test('each hold/release creates a separate timestamped capture and entry', () => {
  const state = emptyState();
  const first = addCapture(state, {
    kind: 'audio', startedAt: '2026-11-05T16:00:00.100Z', endedAt: '2026-11-05T16:00:01.200Z',
  });
  const second = addCapture(state, {
    kind: 'audio', startedAt: '2026-11-05T16:00:01.300Z', endedAt: '2026-11-05T16:00:04.000Z',
  });
  assert.equal(getProject(state).entries.length, 2);
  assert.notEqual(first.entry.id, second.entry.id);
  assert.notEqual(first.media.filename, second.media.filename);
  assert.equal(first.media.durationMs, 1100);
  assert.equal(first.media.startedAt, '2026-11-05T16:00:00.100Z');
  assert.equal(second.media.endedAt, '2026-11-05T16:00:04.000Z');
});

test('attaching narration preserves the original photo time and original file', () => {
  const state = emptyState();
  const photo = addCapture(state, { kind: 'photo', startedAt: '2026-11-05T16:00:00Z' });
  const originalPhoto = structuredClone(photo.media);
  const recording = addCapture(state, {
    kind: 'audio', startedAt: '2026-11-05T17:10:00Z', endedAt: '2026-11-05T17:10:18Z',
    entryId: photo.entry.id,
  });
  assert.equal(getProject(state).entries.length, 1);
  assert.equal(photo.entry.media.length, 2);
  assert.deepEqual(photo.entry.media[0], originalPhoto);
  assert.equal(recording.entry.createdAt, '2026-11-05T16:00:00.000Z');
  assert.equal(recording.media.startedAt, '2026-11-05T17:10:00.000Z');
});

test('captures and attachments remain isolated to the selected project', () => {
  const state = createInitialState(now);
  const original = structuredClone(getProject(state));
  const next = createProject(state, { name: 'New study', folder: 'SD card/Research', now });
  assert.equal(next.folder, 'SD card/Research/New study');
  assert.equal(state.defaultFolder, 'Documents/Fieldnote');
  addCapture(state, { kind: 'video', startedAt: now });
  assert.equal(next.entries.length, 1);
  assert.deepEqual(state.projects[0], original);
  assert.throws(() => addCapture(state, {
    kind: 'audio', startedAt: now, entryId: original.entries[0].id,
  }), /current project/);
});

test('same-millisecond captures have unique names and encode UTC start time', () => {
  const state = emptyState();
  const filenames = Array.from({ length: 30 }, () => addCapture(state, {
    kind: 'audio', startedAt: now, endedAt: now,
  }).media.filename);
  assert.equal(new Set(filenames).size, 30);
  assert.match(filenames[0], /^audio_20261105T160000-000Z_/);
  assert.match(formatFilename('photo', now, 'media-one'), /\.jpg$/);
  assert.throws(() => formatFilename('unknown', now, 'media-one'), /Unsupported/);
});

test('restoration round-trips state and safely recovers from invalid saved data', () => {
  const state = createInitialState(now);
  assert.deepEqual(restoreState(JSON.stringify(state)), state);
  assert.notEqual(restoreState(state), state);
  for (const invalid of [null, '{bad json', '{}', '{"schemaVersion":99}', { projects: null }]) {
    assert.equal(restoreState(invalid).schemaVersion, 1);
    assert.equal(restoreState(invalid).projects.length, 2);
  }
  const broken = structuredClone(state);
  broken.projects[0].entries[0].media[0].durationMs = -1;
  assert.notEqual(restoreState(broken).projects[0].id, broken.projects[0].id);
});

test('manifest is portable, preserves links and declares missing simulated binaries', () => {
  const state = createInitialState(now);
  const project = getProject(state);
  const manifest = projectManifest(project);
  assert.equal(manifest.prototype, true);
  assert.equal(manifest.entries[1].media.length, 2);
  assert.ok(manifest.entries.every((entry) => entry.media.every((media) =>
    media.path.startsWith('media/') && media.simulated && !media.metadata.binaryAvailable)));
  assert.equal(JSON.stringify(manifest).includes('Documents/Fieldnote'), false);
  manifest.entries[0].media[0].metadata.location.status = 'changed';
  assert.equal(project.entries[0].media[0].metadata.location.status, 'simulated');
});

test('invalid times, duplicate folders, and empty names do not mutate state', () => {
  const state = emptyState();
  const before = structuredClone(state);
  assert.throws(() => addCapture(state, {
    kind: 'audio', startedAt: '2026-11-05T16:00:00Z', endedAt: '2026-11-05T15:59:59Z',
  }), /before/);
  assert.throws(() => createProject(state, { name: '', now }), /name/);
  assert.throws(() => createProject(state, { name: 'Optical bench', now }), /already/);
  assert.deepEqual(state, before);
});
