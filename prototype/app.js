import { createInitialState, getProject, createProject, addCapture, projectManifest, restoreState } from './model.js';

const icons = {
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/>',
  camera: '<path d="M14.5 5 13 3H7L5.5 5H3a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h18a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="4"/>',
  video: '<rect x="2" y="5" width="14" height="14" rx="3"/><path d="m16 9 6-4v14l-6-4"/>',
  folder: '<path d="M3 5h6l2 2h10v13H3z"/><path d="M3 11h18"/>',
  notebook: '<rect x="5" y="3" width="15" height="18" rx="2"/><path d="M8 3v18M11 8h5m-5 4h5M3 7h3m-3 5h3m-3 5h3"/>',
  settings: '<path d="m9 3 6 0 .5 3 2.5 1.5 3-.8 3 5.2-2.5 2.2v2.8L24 19l-3 5-3-.9-2.5 1.5L15 28H9l-.5-3.4L6 23.1 3 24l-3-5 2.5-2.1v-2.8L0 12l3-5.3 3 .8L8.5 6z" transform="translate(3 0) scale(.6)"/><circle cx="10.2" cy="9.2" r="3"/><path d="M0 0"/>',
  chevron: '<path d="m6 9 6 6 6-6"/>',
  right: '<path d="m9 5 7 7-7 7"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  link: '<path d="m10 7 3-3a5 5 0 0 1 7 7l-3 3M14 17l-3 3a5 5 0 0 1-7-7l3-3m1 8 8-8"/>',
  sort: '<path d="M7 4v16m-4-4 4 4 4-4M17 20V4m-4 4 4-4 4 4"/>',
  play: '<path d="m8 5 11 7-11 7z"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  file: '<path d="M13 2H5v20h14V8zm0 0v6h6M8 13h8m-8 4h6"/>',
  code: '<path d="m8 7-5 5 5 5m8-10 5 5-5 5M14 3l-4 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  location: '<path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0z"/><circle cx="12" cy="10" r="3"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',
};
icons.settings = '<path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM9 3h6l.5 3 2 1 3-.5 2 5-2.5 2v2l2 2-3 4-2.5-1-2 1L14 24H9l-.5-3-2-1-3 1-2-4 2-2v-2l-2-2 2-5 3 .5 2-1z" transform="translate(1 -1) scale(.9)"/>';
function icon(name) { return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] || icons.file}</svg>`; }
function paintIcons(root = document) { root.querySelectorAll('[data-icon]').forEach(node => { node.innerHTML = icon(node.dataset.icon); }); }
function esc(value) { return String(value ?? '').replace(/[&<>"']/g, char => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[char])); }
const $ = selector => document.querySelector(selector);
const storageKey = 'fieldnote.prototype.v1';
let state;
try { state = restoreState(localStorage.getItem(storageKey)); } catch { state = createInitialState(); }
let metadataEnabled = true;
try { metadataEnabled = localStorage.getItem('fieldnote.metadata') !== 'false'; } catch { /* Preview still works in memory. */ }
let newestFirst = true;
let currentView = 'notebook';
let activeAudio = null;
let activeVideo = null;
let timer = null;
let toastTimer;
let selectedEntryId = null;
let folderContext = null;
let draftProject = { name: '', folder: state.defaultFolder };
let persistenceFailed = false;
const sheet = $('#sheet');

function persist() {
  try {
    localStorage.setItem(storageKey, JSON.stringify(state));
    if (persistenceFailed) $('.storage-failure')?.remove();
    persistenceFailed = false;
  } catch {
    persistenceFailed = true;
    if (!$('.storage-failure')) {
      const notice = document.createElement('div');
      notice.className = 'storage-failure';
      notice.setAttribute('role', 'alert');
      notice.textContent = 'Browser storage is unavailable. Preview changes last for this session only.';
      document.body.prepend(notice);
    }
  }
}
function toast(message) {
  const node = $('#toast');
  node.textContent = message;
  node.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('show'), 4200);
}
const shortTime = date => new Date(date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
const fullTime = (date, timeZone) => new Date(date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, ...(timeZone ? {timeZone} : {}) });
const dateLabel = (date, timeZone) => new Date(date).toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric', ...(timeZone ? {timeZone} : {}) });
function captureZone(media) {
  const zone = media.metadata.timezone?.name || 'UTC';
  try { new Intl.DateTimeFormat('en', {timeZone:zone}).format(); return zone; }
  catch { return 'UTC'; }
}
function metadataDetails(media) {
  const zone = captureZone(media);
  const startDay = dateLabel(media.startedAt, zone);
  const endDay = dateLabel(media.endedAt, zone);
  const interrupted = media.finalizationStatus === 'simulated-interruption';
  return `<dl class="meta-table"><dt>Capture date</dt><dd>${esc(startDay)}</dd><dt>${media.kind === 'photo' ? 'Captured' : 'Started'}</dt><dd>${esc(fullTime(media.startedAt, zone))}</dd>${media.kind !== 'photo' ? `<dt>Ended</dt><dd>${endDay !== startDay ? `${esc(endDay)} · ` : ''}${esc(fullTime(media.endedAt, zone))} · ${duration(media.durationMs)}</dd>` : ''}<dt>Capture zone</dt><dd>${esc(zone)}</dd><dt>Location</dt><dd>${media.metadata.location.status === 'disabled' ? 'Disabled for this capture' : 'Unavailable in preview'}</dd><dt>Device</dt><dd>Unavailable in preview</dd><dt>File status</dt><dd>${interrupted ? 'Interrupted · partial demo entry' : 'Simulated · no media binary'}</dd></dl>`;
}
function duration(ms) { const seconds = Math.floor(ms / 1000); return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`; }
function mediaLabel(kind) { return { audio: 'Audio note', photo: 'Photo', video: 'Video' }[kind]; }
function entryTitle(entry) {
  if (entry.title) return entry.title;
  if (entry.media.length > 1) return entry.media.some(m => m.kind === 'photo') ? 'Photo with context' : entry.media.some(m => m.kind === 'video') ? 'Video with context' : 'Linked audio notes';
  return mediaLabel(entry.media[0].kind);
}
function waveform() { return `<div class="waveform" aria-hidden="true">${Array.from({length: 53}, (_, i) => `<i style="height:${3 + ((i * 13 + Math.floor(i / 3) * 7) % 18)}px"></i>`).join('')}</div>`; }
function apparatus(video = false) {
  return `<svg viewBox="0 0 480 280" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Illustrated sample of a laboratory vacuum chamber"><defs><linearGradient id="wall${video}" x2="0" y2="1"><stop stop-color="#d4dbd5"/><stop offset="1" stop-color="#aab8b0"/></linearGradient><linearGradient id="metal${video}"><stop stop-color="#7e938f"/><stop offset=".35" stop-color="#d6e0da"/><stop offset=".7" stop-color="#a5b8b0"/><stop offset="1" stop-color="#6c8580"/></linearGradient></defs><path fill="url(#wall${video})" d="M0 0h480v280H0z"/><path d="M0 55h480M0 110h480M0 165h480" stroke="#869a9226"/><path d="M0 222h480v58H0z" fill="#779089"/><path d="M0 231h480" stroke="#546f67" stroke-width="5"/><path d="M44 35h8v185h-8zM427 24h8v198h-8z" fill="#7a9188"/><path d="M49 42h382" stroke="#99aba1" stroke-width="7"/><rect x="85" y="137" width="86" height="80" rx="4" fill="#425e56"/><rect x="95" y="147" width="65" height="26" rx="2" fill="#263e36"/><text x="102" y="165" fill="#aed2a6" font-family="monospace" font-size="13">2.4e-6</text><circle cx="107" cy="193" r="6" fill="#abbdb0"/><circle cx="130" cy="193" r="5" fill="#92ac90"/><circle cx="151" cy="193" r="3" fill="#ab7858"/><path d="M168 184h28v-55" stroke="#465e58" stroke-width="9"/><path d="M198 101h124v102H198z" fill="url(#metal${video})"/><ellipse cx="260" cy="102" rx="62" ry="16" fill="#d2dcd4"/><ellipse cx="260" cy="203" rx="62" ry="16" fill="#728c84"/><path d="M198 103v99m124-99v99" stroke="#6c827b" stroke-width="3"/><circle cx="260" cy="152" r="39" fill="#526d65"/><circle cx="260" cy="152" r="31" fill="#bbcdc3"/><circle cx="260" cy="152" r="24" fill="${video ? '#677d92' : '#3c5550'}"/><ellipse cx="252" cy="145" rx="9" ry="14" fill="#a7c7bc" opacity=".2"/><path d="M245 174v-22h30v22" stroke="${video ? '#bb99cc' : '#87a297'}" stroke-width="3"/>${[0,60,120,180,240,300].map(a => `<circle cx="260" cy="117" r="2.5" fill="#dce6dd" transform="rotate(${a} 260 152)"/>`).join('')}<path d="M248 85V61h24v24" fill="#a2b6aa"/><ellipse cx="260" cy="60" rx="16" ry="5" fill="#d5e0d4"/><path d="M322 137h36v-34" stroke="#6e8d81" stroke-width="13"/><circle cx="357" cy="90" r="21" fill="#aebfb3"/><circle cx="357" cy="90" r="16" fill="#e5eade"/><path d="m357 90 8-8" stroke="#637969" stroke-width="2"/><circle cx="357" cy="90" r="2" fill="#637969"/><path d="M229 213v13m62-13v13" stroke="#49665a" stroke-width="8"/><path d="M370 145h39v70h-39z" fill="#b5bca6"/><path d="M381 140v-14h17v14" fill="#7e9583"/><path d="M362 216h56" stroke="#5e7c6e" stroke-width="3"/><path d="M28 249h145m194 4h88" stroke="#92a69a" stroke-width="2"/></svg>`;
}
function captureMetadata(media) {
  media.metadata.preference = metadataEnabled ? 'capture-available' : 'timestamps-only';
  if (!metadataEnabled) media.metadata.location.status = 'disabled';
}
function capture(kind, startedAt, endedAt, entryId) {
  const result = addCapture(state, { kind, startedAt, endedAt, entryId });
  captureMetadata(result.media);
  result.media.attachedAt = new Date().toISOString();
  persist();
  render();
  if (newestFirst) $('#timeline').scrollTop = 0;
  return result;
}
function render() {
  const project = getProject(state);
  $('#project-title').textContent = project.name;
  $('#storage-short').textContent = (project.storageRoot || state.defaultFolder).replaceAll('/', ' / ');
  $('#entry-count').textContent = project.entries.length;
  $('#sort-button').innerHTML = `${newestFirst ? 'Newest' : 'Oldest'} first ${icon('sort')}`;
  const entries = [...project.entries].sort((a, b) => (Date.parse(b.createdAt) - Date.parse(a.createdAt)) * (newestFirst ? 1 : -1));
  let lastDay = '';
  $('#timeline').innerHTML = entries.length ? entries.map(entry => {
    const day = dateLabel(entry.createdAt);
    const divider = lastDay !== day ? `<div class="date-divider">${new Date(entry.createdAt).toDateString() === new Date().toDateString() ? 'TODAY' : esc(day.toUpperCase())}<span>${new Date(entry.createdAt).toDateString() === new Date().toDateString() ? esc(day.toUpperCase()) : ''}</span></div>` : '';
    lastDay = day;
    const media = entry.media;
    const visual = media.find(m => m.kind !== 'audio');
    const audio = media.find(m => m.kind === 'audio');
    const kinds = [...new Set(media.map(m => m.kind))];
    return `${divider}<button class="entry-card" data-action="entry" data-id="${esc(entry.id)}" aria-label="Open ${esc(entryTitle(entry))}, ${esc(shortTime(entry.createdAt))}"><div class="entry-top"><span class="entry-time">${esc(shortTime(entry.createdAt))}</span><span>·</span><span>${media.length} ${media.length === 1 ? 'file' : 'linked files'}</span><span class="entry-type">${kinds.map(k=>icon(k==='photo'?'camera':k==='audio'?'mic':'video')).join('')}</span></div><div class="entry-body">${visual ? `<div class="media-thumbnail">${apparatus(visual.kind === 'video')}${visual.kind === 'video' ? `<span class="thumb-play">${icon('play')}</span>` : ''}</div><div><h3>${esc(entryTitle(entry))}</h3><p>${media.length > 1 ? 'Originals, connected' : visual.kind === 'video' ? `${duration(visual.durationMs)} · sample video` : 'Sample photo'}</p></div>` : `<span class="audio-mark">${icon('mic')}</span>${waveform()}<span style="font-size:10px;color:#879a74">${duration(audio.durationMs)}</span>`}<span class="entry-arrow">${icon('right')}</span></div><div class="entry-footer">${icon(media.length > 1 ? 'link' : 'clock')}${media.length > 1 ? 'Linked media · individual capture times' : `${esc(entry.title || 'Timestamped automatically')} · demo`}</div></button>`;
  }).join('') : `<div class="empty-state">${icon('notebook')}<h3>A fresh page for your project.</h3><p>Hold the microphone to capture a thought.<br>Add a photo or video whenever you need it.</p></div>`;
  renderFiles();
  renderFolderTree();
  setView(currentView, false);
}
function renderFolderTree() {
  const project = getProject(state);
  const media = project.entries.flatMap(e => e.media);
  const recent = [...media].sort((a,b)=>Date.parse(b.startedAt)-Date.parse(a.startedAt)).slice(0,4);
  $('#folder-tree').innerHTML = `<div class="tree-root">${icon('folder')} ${esc(project.name)}/</div><div class="tree-branch"><div class="tree-file">${icon('folder')} media/</div><div class="tree-branch">${recent.map(m => `<div class="tree-file" title="${esc(m.filename)}">${icon(m.kind === 'audio' ? 'mic' : m.kind === 'photo' ? 'camera' : 'video')}<span class="file-tail">${esc(m.filename.slice(0, 28))}…${esc(m.filename.slice(-4))}</span></div>`).join('')}${media.length>4?`<div class="tree-file">+ ${media.length-4} more files</div>`:!media.length?'<div class="tree-file">Your next observation…</div>':''}</div><div class="tree-index">${icon('code')} index.json <small>timestamps + links</small></div></div>`;
}
function renderFiles() {
  const project = getProject(state);
  const media = project.entries.flatMap(e => e.media.map(m=>({...m,entryId:e.id})));
  $('#files-view').innerHTML = `<div class="files-banner"><h2>Your project is a folder.</h2><p>Each original keeps its capture time. The index connects related files.</p></div><span class="eyebrow">PROJECT LOCATION · PREVIEW</span><div class="path-box">${esc(project.folder)}/</div><div class="file-item"><span>${icon('code')}</span><div><strong>index.json</strong><p>${project.entries.length} entries · ${media.length} files · relationships & metadata</p></div><button data-action="manifest" aria-label="Inspect index.json">${icon('right')}</button></div>${media.map(m=>`<div class="file-item"><span>${icon(m.kind==='photo'?'camera':m.kind==='audio'?'mic':'video')}</span><div><strong>media/${esc(m.filename)}</strong><p>${esc(dateLabel(m.startedAt))} · ${esc(fullTime(m.startedAt))}${m.kind !== 'photo' ? ` — ${esc(fullTime(m.endedAt))}` : ''} · simulated</p></div><button data-action="entry" data-id="${esc(m.entryId)}" aria-label="View ${esc(mediaLabel(m.kind))} details">${icon('right')}</button></div>`).join('')}<button class="secondary-button" data-action="download">${icon('download')} Download sample index</button><p class="detail-note">This is a folder preview. No media files or real folders are created by the prototype.</p>`;
}
function setView(view, announce = true) {
  currentView = view;
  $('#notebook-view').hidden = view !== 'notebook';
  $('#files-view').hidden = view !== 'files';
  $('#nav-notebook').classList.toggle('active', view === 'notebook');
  $('#nav-files').classList.toggle('active', view === 'files');
  $('#nav-notebook').setAttribute('aria-current', view==='notebook'?'page':'false');
  $('#nav-files').setAttribute('aria-current', view==='files'?'page':'false');
  if (announce) sheet.close();
}
function openSheet(view, kicker, title, content) {
  $('#sheet-kicker').textContent = kicker;
  $('#sheet-title').textContent = title;
  $('#sheet-content').innerHTML = content;
  if (!sheet.open) sheet.showModal();
  sheet.scrollTop = 0;
  $('#close-sheet').focus({preventScroll:true});
}
function openProjects() {
  openSheet('projects', 'YOUR WORK, ORGANIZED', 'Projects', `<p class="sheet-lead">Pick up where you left off.</p>${state.projects.map(p=>`<button class="project-option ${p.id===state.currentProjectId?'selected':''}" data-action="select-project" data-id="${esc(p.id)}"><span>${icon('folder')}</span><div><strong>${esc(p.name)}</strong><small>${p.entries.length} ${p.entries.length===1?'entry':'entries'} · ${esc(dateLabel(p.createdAt))}</small></div><span>${icon(p.id===state.currentProjectId?'check':'right')}</span></button>`).join('')}<button class="primary-button" data-action="new-project">${icon('plus')} New project</button><p class="detail-note">Every project gets its own folder and metadata index.</p>`);
}
function newProjectForm() {
  openSheet('new-project','A NEW LINE OF INQUIRY','Create a project',`<form class="project-form" id="project-form"><label class="form-label" for="new-project-name">Project name</label><input id="new-project-name" type="text" required maxlength="80" placeholder="e.g. Magnetron sputtering" value="${esc(draftProject.name)}" autocomplete="off"><label class="form-label">Save inside</label><button type="button" class="folder-choice" data-action="project-folder">${icon('folder')}<div><strong>${esc(draftProject.folder)}</strong><small>Tap to choose a different folder · simulated picker</small></div></button><p class="field-help">A new folder named after your project will be created here in the Android app.</p><p class="error-text" id="project-error" role="alert"></p><button type="submit" class="primary-button">Create project ${icon('right')}</button></form>`);
  $('#new-project-name').focus();
  $('#project-form').addEventListener('submit', event=>{
    event.preventDefault();
    try {
      createProject(state,{name:$('#new-project-name').value,folder:draftProject.folder});
      persist(); currentView='notebook'; render(); sheet.close();
      toast('Project created in the preview. Ready for your first note.');
    } catch(error) { $('#project-error').textContent=error.message; }
  });
}
function openFolders(context) {
  folderContext = context;
  const current = context==='project' ? draftProject.folder : state.defaultFolder;
  const choices = [...new Set([state.defaultFolder,'Documents/Fieldnote','Documents/Research','SD card/Lab notebooks',current])];
  openSheet('folders','SIMULATED ANDROID FOLDER PICKER',context==='project'?'Project location':'Default location',`<p class="sheet-lead">Choose a parent folder. Your project will live in its own folder inside it.</p>${choices.map(path=>`<button class="folder-choice ${path===current?'selected':''}" data-action="choose-folder" data-path="${esc(path)}">${icon('folder')}<div><strong>${esc(path)}</strong><small>${path===state.defaultFolder?'Current default':path.startsWith('SD')?'Removable storage · example':'On this device · example'}</small></div>${path===current?icon('check'):''}</button>`).join('')}<form id="custom-folder-form"><label class="form-label" for="custom-folder">Or preview a different location</label><input id="custom-folder" type="text" maxlength="180" placeholder="Documents / My lab" required><button class="secondary-button" type="submit">Use this location</button></form><p class="small-alert">This simulates Android’s system picker. It does not access or create folders on your device.</p>`);
  $('#custom-folder-form').addEventListener('submit', event=>{event.preventDefault(); chooseFolder($('#custom-folder').value.trim());});
}
function chooseFolder(path) {
  if (!path) return;
  if (folderContext==='project') { draftProject.folder=path; newProjectForm(); }
  else { state.defaultFolder=path; persist(); openStorage(); toast('Default location updated for future projects.'); }
}
function openStorage() {
  const project=getProject(state);
  openSheet('storage','FILES THAT STAY WITH YOU','Project storage',`<span class="eyebrow">THIS PROJECT</span><div class="path-box">${esc(project.folder)}/</div><p class="sheet-lead">Media files and <strong>index.json</strong> belong together. Copying the complete folder will preserve entries and their links in the Android app.</p><div class="setting-row"><div><strong>Default for new projects</strong><small>${esc(state.defaultFolder)}</small></div><button class="icon-button" data-action="default-folder" aria-label="Change default storage location">${icon('right')}</button></div><button class="secondary-button" data-action="files">${icon('folder')} Preview project files</button><p class="small-alert">Prototype storage is browser-local demo data. Native folder access and media writing are not connected yet.</p>`);
}
function openSettings() {
  openSheet('settings','SMALL SETTINGS. USEFUL CONTEXT.','Capture & storage',`<p class="sheet-lead">Keep the details that help you retrace an observation.</p><label class="setting-row" for="metadata-toggle"><div><strong>Capture available metadata</strong><small>Location, device, capture settings and sensors when supported and permitted.</small></div><input id="metadata-toggle" type="checkbox" ${metadataEnabled?'checked':''}></label><div class="setting-row"><div><strong>Capture date & start / end times</strong><small>Stored separately for each original file.</small></div><span>Always on</span></div><div class="setting-row"><div><strong>Location</strong><small>No real location is collected in this preview.</small></div><span>Simulated</span></div><div class="setting-row"><div><strong>Device & sensor details</strong><small>Missing values stay explicitly unavailable.</small></div><span>Unavailable</span></div><button class="secondary-button" data-action="storage">${icon('folder')} Storage settings</button><p class="detail-note">Android permissions will control what can be captured. The app should never substitute a guess for missing metadata.</p><button class="secondary-button" data-action="reset">Reset demo notebook</button>`);
  $('#metadata-toggle').addEventListener('change',event=>{
    metadataEnabled=event.target.checked;
    try{localStorage.setItem('fieldnote.metadata',String(metadataEnabled));}catch{/* Preference remains available for this session. */}
  });
}
function entryDetails(id) {
  const entry=getProject(state).entries.find(e=>e.id===id);
  if(!entry)return;
  selectedEntryId=id;
  openSheet('entry',`${dateLabel(entry.createdAt)} · ${shortTime(entry.createdAt)}`,entryTitle(entry),`${entry.media.length>1?`<div class="linked-note">${icon('link')} ${entry.media.length} linked originals · each with its own capture time</div>`:''}${entry.media.map(media=>`<article class="detail-media">${media.kind!=='audio'?`<div class="detail-image">${apparatus(media.kind==='video')}<span class="sample-label">ILLUSTRATED SAMPLE · ${media.kind.toUpperCase()}</span></div>`:`<div class="preview-player"><span class="audio-mark">${icon('mic')}</span>${waveform()}<span>${duration(media.durationMs)}</span></div>`}<div class="detail-meta"><h3>${icon(media.kind==='photo'?'camera':media.kind==='audio'?'mic':'video')} ${mediaLabel(media.kind)} <span class="demo-tag">SIMULATED</span></h3><div class="filename">${esc(media.filename)}</div>${metadataDetails(media)}</div></article>`).join('')}<span class="eyebrow">ADD TO THIS ENTRY</span><div class="attachment-actions"><button data-action="attach-audio">${icon('mic')} Audio</button><button data-action="attach-photo">${icon('camera')} Photo</button><button data-action="attach-video">${icon('video')} Video</button></div><p class="detail-note">Attachments stay separate. Adding an explanation later never changes the original photo or video’s capture time.</p>`);
}
function showCamera(kind,entryId=null) {
  selectedEntryId=entryId;
  openSheet('camera', 'SIMULATED CAPTURE',kind==='photo'?'Capture a photo':'Capture a video',`${entryId?'<div class="attach-context">Adding to the existing entry · original times preserved</div>':''}<div class="capture-preview" id="capture-preview">${apparatus(kind==='video')}<div class="viewfinder"><span>ILLUSTRATED SAMPLE</span><span id="camera-time">${kind==='video'?'00:00':'4:3'}</span></div></div><button class="capture-trigger ${kind==='video'?'video':''}" id="camera-trigger" data-action="${kind==='photo'?'shutter':'video-start'}" aria-label="${kind==='photo'?'Capture sample photo':'Start sample video recording'}">${icon(kind==='photo'?'camera':'video')}</button><p class="capture-caption" id="camera-caption">${kind==='photo'?'Tap to keep this sample photo.':'Tap to start, then tap again to save.'}<br>You can attach an audio explanation next.</p><p class="detail-note">Camera preview is illustrated. No real camera or microphone is being used.</p>`);
}
function startVideo() {
  activeVideo={startedAt:new Date(),entryId:selectedEntryId};
  $('#camera-trigger').dataset.action='video-stop';
  $('#camera-trigger').innerHTML=icon('stop');
  $('#camera-trigger').setAttribute('aria-label','Stop and save sample video');
  $('#camera-time').classList.add('recording-dot');
  $('#camera-caption').innerHTML='Recording sample video…<br>Tap stop to save automatically.';
  timer=setInterval(()=>{if($('#camera-time'))$('#camera-time').textContent=duration(Date.now()-activeVideo.startedAt.getTime());},100);
}
function finishVideo(interrupted=false) {
  if(!activeVideo)return;
  const recording=activeVideo; activeVideo=null; clearInterval(timer);
  const result=capture('video',recording.startedAt,new Date(),recording.entryId);
  result.media.finalizationStatus=interrupted?'simulated-interruption':'simulated-complete';persist();
  entryDetails(result.entry.id);
  toast(interrupted?'Interrupted demo video kept as a partial entry.':'Demo video added. Attach an explanation below.');
}
function attachAudio() {
  const entryId=selectedEntryId;
  openSheet('audio','ADD CONTEXT','An explanation, linked.',`<div class="attach-context">Audio will be attached to this entry as a separate file.</div><div class="audio-sheet" id="audio-sheet"><h3 id="attach-record-label">Hold to explain.</h3><p>Release to keep the recording.</p><button class="record-button" id="attach-record" aria-label="Hold to record an attached audio explanation">${icon('mic')}</button><span id="attach-timer" class="capture-counter">00:00</span><p>Simulated audio · no microphone access</p></div>`);
  bindRecord($('#attach-record'),entryId);
}
function beginAudio(button,entryId=null) {
  if(activeAudio||activeVideo)return;
  activeAudio={startedAt:new Date(),entryId,button};
  button.innerHTML=icon('stop');
  button.setAttribute('aria-label','Release to save audio note');
  if(entryId){$('#audio-sheet')?.classList.add('is-recording');if($('#attach-record-label'))$('#attach-record-label').textContent='Recording your explanation…';}
  else{$('#capture-panel').classList.add('is-recording');$('#capture-status').innerHTML='<span class="ready-dot"></span> Recording a sample note';$('#record-heading').textContent='Go on. We’re listening.';$('#record-help').innerHTML='Release to save<br>this audio note.';$('#save-hint').textContent='Simulating capture · no microphone access';}
  timer=setInterval(()=>{
    const elapsed=duration(Date.now()-activeAudio.startedAt.getTime());
    $('#capture-counter').textContent=elapsed;
    if($('#attach-timer'))$('#attach-timer').textContent=elapsed;
  },100);
}
function finishAudio(interrupted=false) {
  if(!activeAudio)return;
  const recording=activeAudio;activeAudio=null;clearInterval(timer);
  recording.button.innerHTML=icon('mic');
  recording.button.setAttribute('aria-label',recording.entryId?'Hold to record an attached audio explanation':'Hold to record an audio note');
  $('#capture-panel').classList.remove('is-recording');
  $('#capture-status').innerHTML='<span class="ready-dot"></span> Ready when you are';
  $('#record-heading').textContent='Catch a thought.';
  $('#record-help').innerHTML='Hold to record.<br>Release to keep it.';
  $('#save-hint').textContent='A separate file, every time.';
  $('#capture-counter').textContent='00:00';
  const result=capture('audio',recording.startedAt,new Date(),recording.entryId);
  result.media.finalizationStatus=interrupted?'simulated-interruption':'simulated-complete';persist();
  if(recording.entryId)entryDetails(result.entry.id);
  toast(interrupted?'Interrupted demo note kept with its timestamps.':recording.entryId?'Audio linked. The original capture time is unchanged.':'Demo audio note added · timestamped automatically.');
}
function bindRecord(button,entryId=null) {
  button.addEventListener('pointerdown',event=>{
    if(event.button!==0)return;
    event.preventDefault();button.focus();button.setPointerCapture(event.pointerId);beginAudio(button,entryId);
  });
  button.addEventListener('pointerup',event=>{event.preventDefault();finishAudio();});
  button.addEventListener('pointercancel',()=>finishAudio(true));
  button.addEventListener('lostpointercapture',()=>{if(activeAudio?.button===button)finishAudio(true);});
  button.addEventListener('keydown',event=>{if([' ','Enter'].includes(event.key)){event.preventDefault();if(!event.repeat)beginAudio(button,entryId);}});
  button.addEventListener('keyup',event=>{if([' ','Enter'].includes(event.key)){event.preventDefault();finishAudio();}});
  button.addEventListener('blur',()=>{if(activeAudio?.button===button)finishAudio(true);});
  button.addEventListener('contextmenu',event=>event.preventDefault());
}
function showManifest() {
  const manifest=projectManifest(getProject(state));
  openSheet('manifest','PORTABLE PROJECT INDEX','index.json',`<p class="sheet-lead">Relative file paths preserve links when a complete project folder is copied. Every media item has its own timestamps.</p><pre class="manifest">${esc(JSON.stringify(manifest,null,2))}</pre><button class="primary-button" data-action="download">${icon('download')} Download sample index</button><p class="detail-note">This JSON demonstrates the structure. Referenced files are simulated and are not included.</p>`);
}
function downloadManifest() {
  const project=getProject(state);
  const blob=new Blob([JSON.stringify(projectManifest(project),null,2)],{type:'application/json'});
  const url=URL.createObjectURL(blob);
  const anchor=document.createElement('a');anchor.href=url;anchor.download=`${project.name.replace(/[^a-z0-9-]+/gi,'-').toLowerCase()}-preview-index.json`;
  document.body.appendChild(anchor);anchor.click();anchor.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
  toast('Sample index download requested. Media files are simulated.');
}
function closeSheet() {
  finishAudio(true);finishVideo(true);sheet.close();
  // A mobile sheet may replace its opener, so keep focus inside the app.
  $('#project-switch').focus({preventScroll:true});
}
document.addEventListener('click',event=>{
  const button=event.target.closest('[data-action]');
  if(!button||activeAudio)return;
  const action=button.dataset.action;
  if(activeVideo&&action!=='video-stop')return;
  switch(action){
    case 'projects':openProjects();break;
    case 'select-project':state.currentProjectId=button.dataset.id;persist();currentView='notebook';render();sheet.close();break;
    case 'new-project':draftProject={name:'',folder:state.defaultFolder};newProjectForm();break;
    case 'project-folder':draftProject.name=$('#new-project-name').value;openFolders('project');break;
    case 'choose-folder':chooseFolder(button.dataset.path);break;
    case 'default-folder':openFolders('default');break;
    case 'settings':openSettings();break;
    case 'storage':openStorage();break;
    case 'files':setView('files');break;
    case 'notebook':setView('notebook');break;
    case 'sort':newestFirst=!newestFirst;render();break;
    case 'entry':entryDetails(button.dataset.id);break;
    case 'photo':showCamera('photo');break;
    case 'video':showCamera('video');break;
    case 'shutter':{const now=new Date();const result=capture('photo',now,now,selectedEntryId);entryDetails(result.entry.id);toast('Demo photo added. Add an audio explanation below.');break;}
    case 'video-start':startVideo();break;
    case 'video-stop':finishVideo();break;
    case 'attach-audio':attachAudio();break;
    case 'attach-photo':showCamera('photo',selectedEntryId);break;
    case 'attach-video':showCamera('video',selectedEntryId);break;
    case 'manifest':showManifest();break;
    case 'download':downloadManifest();break;
    case 'try-record':setView('notebook');$('#record-button').focus();$('#record-button').classList.remove('highlight-record');requestAnimationFrame(()=>$('#record-button').classList.add('highlight-record'));toast('Press and hold the microphone. Release to save.');break;
    case 'try-link':{const entry=getProject(state).entries.find(e=>e.media.some(m=>m.kind==='photo'));if(entry)entryDetails(entry.id);else showCamera('photo');break;}
    case 'reset':openSheet('reset','ONLY THE PROTOTYPE','Start fresh?',`<p class="sheet-lead">This removes the demo projects and entries in this browser and restores the sample notebook.</p><button class="primary-button" data-action="confirm-reset">Reset demo data</button><button class="secondary-button" data-action="settings">Keep my changes</button>`);break;
    case 'confirm-reset':state=createInitialState();persist();currentView='notebook';render();sheet.close();toast('Sample notebook restored.');break;
  }
});
$('#close-sheet').addEventListener('click',closeSheet);
sheet.addEventListener('cancel',event=>{event.preventDefault();closeSheet();});
sheet.addEventListener('click',event=>{if(event.target===sheet){const rect=sheet.getBoundingClientRect();if(event.clientX<rect.left||event.clientX>rect.right||event.clientY<rect.top||event.clientY>rect.bottom)closeSheet();}});
window.addEventListener('blur',()=>{finishAudio(true);finishVideo(true);});
document.addEventListener('visibilitychange',()=>{if(document.hidden){finishAudio(true);finishVideo(true);}});
window.addEventListener('pagehide',()=>{finishAudio(true);finishVideo(true);});
paintIcons();
bindRecord($('#record-button'));
$('#clock').textContent=new Date().toLocaleTimeString([],{hour:'numeric',minute:'2-digit',hour12:false});
persist();
render();
