const audio = document.getElementById('audio');

const nowCover = document.getElementById('now-cover');
const nowTitle = document.getElementById('now-title');
const nowArtist = document.getElementById('now-artist');
const nowStatus = document.getElementById('now-status');
const progress = document.getElementById('progress');
const currentTimeEl = document.getElementById('current-time');
const durationEl = document.getElementById('duration');
const volume = document.getElementById('volume');
const volLabel = document.getElementById('vol-label');

const barCover = document.getElementById('track-cover');
const barInfo = document.getElementById('track-info');
const barProgress = document.getElementById('bar-progress');
const barCurrent = document.getElementById('bar-current');
const barDuration = document.getElementById('bar-duration');
const barVolume = document.getElementById('bar-volume');
const barVolLabel = document.getElementById('bar-vol');

const playBtn = document.getElementById('play-btn');
const prevBtn = document.getElementById('prev-btn');
const nextBtn = document.getElementById('next-btn');
const shuffleBtn = document.getElementById('shuffle-btn');
const loopBtn = document.getElementById('loop-btn');

const barPlay = document.getElementById('bar-play');
const barPrev = document.getElementById('bar-prev');
const barNext = document.getElementById('bar-next');
const barDownload = document.getElementById('bar-download');
const barQueue = document.getElementById('bar-queue');

const searchInput = document.getElementById('search-input');
const searchBtn = document.getElementById('search-btn');
const searchResults = document.getElementById('search-results');
const searchContext = document.getElementById('search-context');
const searchBackBtn = document.getElementById('search-back-btn');
const searchContextLabel = document.getElementById('search-context-label');
const filterTrack = document.getElementById('filter-track');
const filterArtist = document.getElementById('filter-artist');
const filterPlaylist = document.getElementById('filter-playlist');
const queueList = document.getElementById('queue-list');
const playlistList = document.getElementById('playlist-list');
const playlistName = document.getElementById('playlist-name');
const playlistCreate = document.getElementById('playlist-create');
const downloadedList = document.getElementById('downloaded-list');
const historyList = document.getElementById('history-list');

const lyricsPanel = document.getElementById('lyrics-panel');
const lyricsBody = document.getElementById('lyrics-body');
const lyricsBodyDesktop = document.getElementById('lyrics-body-desktop');
const lyricsClose = document.getElementById('lyrics-close');
const downloadBtn = document.getElementById('download-btn');
const lyricsToggle = document.getElementById('lyrics-toggle');

const nowOverlay = document.getElementById('now-overlay');
const nowClose = document.getElementById('now-close');
const playerBar = document.getElementById('player-bar');
const queueDrawer = document.getElementById('queue-drawer');
const queueClose = document.getElementById('queue-close');
const nowCard = document.querySelector('.now');

const devicesBtn = document.getElementById('devices-btn');
const devicesOverlay = document.getElementById('devices-overlay');
const devicesClose = document.getElementById('devices-close');
const deviceName = document.getElementById('device-name');
const deviceHost = document.getElementById('device-host');
const devicePort = document.getElementById('device-port');
const deviceAdd = document.getElementById('device-add');
const devicesList = document.getElementById('devices-list');

const pickerOverlay = document.getElementById('playlist-picker');
const pickerClose = document.getElementById('picker-close');
const pickerList = document.getElementById('picker-list');
const toastStack = document.getElementById('toast-stack');

const tabs = Array.from(document.querySelectorAll('.tab'));
const tabPanels = {
  playlists: document.getElementById('tab-playlists'),
  downloads: document.getElementById('tab-downloads'),
  history: document.getElementById('tab-history'),
  lyrics: document.getElementById('tab-lyrics'),
};

let queue = [];
let currentIndex = -1;
let currentItem = null;
let isShuffle = false;
let isLoop = false;
let searchCache = [];
let searchStack = [];
let lyricsLines = [];
let lyricsIndex = -1;
let pendingPlaylistItem = null;

const DEFAULT_COVER = '/static/default-cover.png';
const ICONS = {
  play: '/static/icons/play.png',
  pause: '/static/icons/pause.png',
  prev: '/static/icons/skip_previous.png',
  next: '/static/icons/skip_next.png',
  shuffle: '/static/icons/shuffle.png',
  shuffleOn: '/static/icons/shuffle_on.png',
  repeat: '/static/icons/repeat.png',
  repeatOn: '/static/icons/repeat_on.png',
  queue: '/static/icons/queued_music.png',
  devices: '/static/icons/devices.png',
};

function iconImg(src, alt) {
  return `<img class="btn-icon" src="${src}" alt="${alt}" />`;
}

function syncControlIcons() {
  prevBtn.innerHTML = iconImg(ICONS.prev, 'Précédent');
  nextBtn.innerHTML = iconImg(ICONS.next, 'Suivant');
  barPrev.innerHTML = iconImg(ICONS.prev, 'Précédent');
  barNext.innerHTML = iconImg(ICONS.next, 'Suivant');
  barQueue.innerHTML = iconImg(ICONS.queue, 'Queue');
  devicesBtn.innerHTML = iconImg(ICONS.devices, 'Devices');
  playBtn.innerHTML = iconImg(audio.paused ? ICONS.play : ICONS.pause, audio.paused ? 'Play' : 'Pause');
  barPlay.innerHTML = iconImg(audio.paused ? ICONS.play : ICONS.pause, audio.paused ? 'Play' : 'Pause');
  shuffleBtn.innerHTML = iconImg(isShuffle ? ICONS.shuffleOn : ICONS.shuffle, 'Shuffle');
  loopBtn.innerHTML = iconImg(isLoop ? ICONS.repeatOn : ICONS.repeat, 'Repeat');
}

function showToast(message, type = 'info', timeoutMs = 4200) {
  if (!toastStack || !message) return;
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = message;
  toastStack.appendChild(toast);
  requestAnimationFrame(() => {
    toast.classList.add('show');
  });
  const hide = () => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 220);
  };
  setTimeout(hide, timeoutMs);
}

function fmtTime(seconds) {
  if (!seconds || Number.isNaN(seconds)) return '0:00';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${s.toString().padStart(2, '0')}`;
}

async function apiFetch(path, options = {}) {
  const { timeoutMs, ...fetchOptions } = options;
  const controller = timeoutMs ? new AbortController() : null;
  let timeoutId = null;
  if (controller) {
    timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  }
  try {
    const res = await fetch(path, {
      headers: { 'Content-Type': 'application/json' },
      signal: controller ? controller.signal : undefined,
      ...fetchOptions,
    });
    return res.json();
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
}

function setNowPlaying(item) {
  currentItem = item;
  nowTitle.textContent = item?.title || 'Aucune lecture';
  nowArtist.textContent = item?.artist || '';
  barInfo.textContent = item ? `${item.title} — ${item.artist || ''}` : 'Aucune lecture';
  const coverSrc = item?.cover || (item?.id ? `/api/cover?key=${item.id}` : '') || DEFAULT_COVER;
  nowCover.src = coverSrc;
  barCover.src = coverSrc;
}

function isMobile() {
  return window.matchMedia('(max-width: 900px)').matches;
}

function renderQueue() {
  queueList.innerHTML = '';
  queue.forEach((item, index) => {
    const card = document.createElement('div');
    card.className = `track ${index === currentIndex ? 'active' : ''}`;
    card.innerHTML = `
      <img src="${item.cover || DEFAULT_COVER}" alt="cover" />
      <div>
        <h4>${item.title}</h4>
        <span>${item.artist || ''}</span>
        <div class="actions">
          <button data-action="playlist">+ Playlist</button>
          <button data-action="remove">Retirer</button>
        </div>
      </div>
    `;
    card.addEventListener('click', () => playAtIndex(index));
    card.querySelector('[data-action="remove"]').addEventListener('click', (e) => {
      e.stopPropagation();
      queue.splice(index, 1);
      if (currentIndex >= index) currentIndex = Math.max(0, currentIndex - 1);
      renderQueue();
    });
    card.querySelector('[data-action="playlist"]').addEventListener('click', (e) => {
      e.stopPropagation();
      promptAddToPlaylist(item);
    });
    queueList.appendChild(card);
  });
}

function getNextCandidateIndex(fromIndex, attempted) {
  if (!queue.length || attempted.size >= queue.length) return -1;

  if (isShuffle) {
    const candidates = queue
      .map((_, idx) => idx)
      .filter((idx) => !attempted.has(idx));
    if (!candidates.length) return -1;
    return candidates[Math.floor(Math.random() * candidates.length)];
  }

  if (fromIndex + 1 < queue.length && !attempted.has(fromIndex + 1)) {
    return fromIndex + 1;
  }

  if (isLoop) {
    for (let i = 0; i < queue.length; i += 1) {
      if (!attempted.has(i)) return i;
    }
  }

  return -1;
}

function renderSearch(items) {
  searchResults.innerHTML = '';
  items.forEach((item) => {
    const itemType = item.type || 'track';
    const isTrack = itemType === 'track';
    const isPlaylist = itemType === 'playlist';
    const canOpenTracks = itemType === 'artist' || isPlaylist;
    const metaParts = [];
    if (item.artist) metaParts.push(item.artist);
    metaParts.push(itemType);
    const metaLine = metaParts.join(' · ');
    const card = document.createElement('div');
    card.className = 'track';
    const actionsHtml = isTrack
      ? `
          <button data-action="queue">+ Queue</button>
          <button data-action="playlist">+ Playlist</button>
          <button data-action="download">Download</button>
        `
      : canOpenTracks
        ? `${isPlaylist ? '<button data-action="import">+ Mes playlists</button>' : ''}<button data-action="open">Voir titres</button>`
        : '';
    card.innerHTML = `
      <img src="${item.cover || DEFAULT_COVER}" alt="cover" />
      <div>
        <h4>${item.title}</h4>
        <span>${metaLine}</span>
        <div class="actions">
          ${actionsHtml}
        </div>
      </div>
    `;
    if (isTrack) {
      card.querySelector('[data-action="queue"]').addEventListener('click', (e) => {
        e.stopPropagation();
        addToQueue(item, true);
        if (queue.length === 1) playAtIndex(0);
      });
      card.querySelector('[data-action="playlist"]').addEventListener('click', (e) => {
        e.stopPropagation();
        promptAddToPlaylist(item);
      });
      card.querySelector('[data-action="download"]').addEventListener('click', (e) => {
        e.stopPropagation();
        downloadItem(item);
      });
    } else if (canOpenTracks) {
      if (isPlaylist) {
        card.querySelector('[data-action="import"]').addEventListener('click', async (e) => {
          e.stopPropagation();
          await importPlaylistResult(item);
        });
      }
      card.querySelector('[data-action="open"]').addEventListener('click', async (e) => {
        e.stopPropagation();
        await loadExpandedSearch(item);
      });
      card.addEventListener('click', async () => {
        await loadExpandedSearch(item);
      });
    }
    searchResults.appendChild(card);
  });
}

function updateSearchContext() {
  if (!searchContext || !searchContextLabel) return;
  if (!searchStack.length) {
    searchContext.classList.add('hidden');
    searchContextLabel.textContent = '';
    return;
  }
  const current = searchStack[searchStack.length - 1];
  const typeLabel = current.type === 'artist' ? 'artiste' : 'playlist';
  searchContextLabel.textContent = `Dans ${typeLabel}: ${current.title}`;
  searchContext.classList.remove('hidden');
}

function renderHistory(items) {
  historyList.innerHTML = '';
  items.forEach((item) => {
    const card = document.createElement('div');
    card.className = 'track';
    card.innerHTML = `
      <img src="${item.cover || DEFAULT_COVER}" alt="cover" />
      <div>
        <h4>${item.title}</h4>
        <span>${item.artist || ''}</span>
        <div class="actions">
          <button data-action="queue">+ Queue</button>
          <button data-action="playlist">+ Playlist</button>
        </div>
      </div>
    `;
    card.querySelector('[data-action="queue"]').addEventListener('click', (e) => {
      e.stopPropagation();
      addToQueue(item, true);
      playAtIndex(queue.length - 1);
    });
    card.querySelector('[data-action="playlist"]').addEventListener('click', (e) => {
      e.stopPropagation();
      promptAddToPlaylist(item);
    });
    historyList.appendChild(card);
  });
}

function renderDownloads(items) {
  downloadedList.innerHTML = '';
  items.forEach((item) => {
    const coverSrc = item.cover_path ? `/api/cover?key=${item.id}` : DEFAULT_COVER;
    const card = document.createElement('div');
    card.className = 'track';
    card.innerHTML = `
      <img src="${coverSrc}" alt="cover" />
      <div>
        <h4>${item.title}</h4>
        <span>${item.artist || ''}</span>
        <div class="actions">
          <button data-action="queue">+ Queue</button>
          <button data-action="playlist">+ Playlist</button>
        </div>
      </div>
    `;
    card.querySelector('[data-action="queue"]').addEventListener('click', (e) => {
      e.stopPropagation();
      addToQueue({ ...item, file_url: item.file_url }, true, true);
      playAtIndex(queue.length - 1);
    });
    card.querySelector('[data-action="playlist"]').addEventListener('click', (e) => {
      e.stopPropagation();
      promptAddToPlaylist(item);
    });
    downloadedList.appendChild(card);
  });
}

function renderPlaylists(playlists) {
  playlistList.innerHTML = '';
  playlists.forEach((pl) => {
    const card = document.createElement('div');
    card.className = 'playlist-card';
    card.innerHTML = `
      <h4>${pl.name}</h4>
      <div>${pl.items.length} titres</div>
      <button data-action="play">Lire</button>
      <div class="playlist-items"></div>
    `;
    card.querySelector('[data-action="play"]').addEventListener('click', () => {
      queue = [...pl.items];
      currentIndex = -1;
      renderQueue();
      if (queue.length) playAtIndex(0);
    });
    const itemsWrap = card.querySelector('.playlist-items');
    pl.items.forEach((item) => {
      const row = document.createElement('div');
      row.className = 'playlist-item';
      row.innerHTML = `
        <div>
          <strong>${item.title}</strong>
          <span>${item.artist || ''}</span>
        </div>
        <button data-action="remove">Retirer</button>
      `;
      row.querySelector('[data-action="remove"]').addEventListener('click', () => {
        apiFetch('/api/playlists/remove', {
          method: 'POST',
          body: JSON.stringify({ name: pl.name, id: item.id }),
        }).then(loadPlaylists);
      });
      itemsWrap.appendChild(row);
    });
    playlistList.appendChild(card);
  });
}

function addToQueue(item, autofill = false, isDownloaded = false) {
  if (item?.type && item.type !== 'track') {
    showToast('Seuls les tracks peuvent être ajoutés à la queue.', 'info');
    return;
  }
  const entry = {
    id: item.id || item.url || item.title,
    title: item.title,
    artist: item.artist || '',
    url: item.url,
    cover: item.cover,
    file_url: item.file_url,
    downloaded: isDownloaded || item.downloaded,
  };
  queue.push(entry);
  if (autofill && searchCache.length) {
    const nextItems = searchCache.filter((it) => it.id !== entry.id).slice(0, 3);
    if (!isLoop) {
      nextItems.forEach((it) => {
        if (!queue.find((q) => q.id === it.id)) queue.push(it);
      });
    }
    apiFetch('/api/cache/prefetch', {
      method: 'POST',
      body: JSON.stringify({ items: nextItems }),
    });
  }
  renderQueue();
}

async function playAtIndex(index, attempted = new Set()) {
  if (index < 0 || index >= queue.length) return;
  attempted.add(index);
  currentIndex = index;
  const item = queue[index];
  setNowPlaying(item);
  nowStatus.textContent = 'Chargement...';

  let fileUrl = item.file_url;
  if (!fileUrl) {
    const res = await apiFetch('/api/cache/play', {
      method: 'POST',
      body: JSON.stringify({
        url: item.url,
        title: item.title,
        artist: item.artist,
        cover: item.cover,
      }),
    });
    if (!res.ok) {
      nowStatus.textContent = 'Erreur';
      showToast(res.error || 'Lecture impossible.', 'error');
      const nextIndex = getNextCandidateIndex(index, attempted);
      if (nextIndex !== -1) {
        showToast('Titre indisponible, passage au suivant.', 'info', 2400);
        await playAtIndex(nextIndex, attempted);
      } else {
        showToast('Aucun titre lisible dans la file.', 'error');
      }
      return;
    }
    fileUrl = res.file_url;
  }

  audio.src = fileUrl;
  try {
    await audio.play();
  } catch (e) {
    nowStatus.textContent = 'Erreur';
    showToast('Impossible de lire ce titre, passage au suivant.', 'error');
    const nextIndex = getNextCandidateIndex(index, attempted);
    if (nextIndex !== -1) {
      await playAtIndex(nextIndex, attempted);
    } else {
      showToast('Aucun titre lisible dans la file.', 'error');
    }
    return;
  }
  nowStatus.textContent = 'Lecture';
  syncControlIcons();
  renderQueue();

  apiFetch('/api/history/add', {
    method: 'POST',
    body: JSON.stringify({ item }),
  });

  fetchLyrics(item);
}

function nextTrack() {
  if (queue.length === 0) return;
  if (isShuffle) {
    const next = Math.floor(Math.random() * queue.length);
    playAtIndex(next);
    return;
  }
  if (currentIndex + 1 < queue.length) {
    playAtIndex(currentIndex + 1);
  } else if (isLoop) {
    playAtIndex(0);
  }
}

function prevTrack() {
  if (queue.length === 0) return;
  if (audio.currentTime > 5) {
    audio.currentTime = 0;
  } else if (currentIndex > 0) {
    playAtIndex(currentIndex - 1);
  }
}

function togglePlay() {
  if (!audio.src) return;
  if (audio.paused) {
    audio.play();
    nowStatus.textContent = 'Lecture';
  } else {
    audio.pause();
    nowStatus.textContent = 'Pause';
  }
  syncControlIcons();
}

function syncProgress() {
  progress.value = audio.duration ? (audio.currentTime / audio.duration) * 100 : 0;
  barProgress.value = progress.value;
  currentTimeEl.textContent = fmtTime(audio.currentTime);
  durationEl.textContent = fmtTime(audio.duration);
  barCurrent.textContent = fmtTime(audio.currentTime);
  barDuration.textContent = fmtTime(audio.duration);
}

function getSearchTypes() {
  const selected = [];
  if (filterTrack?.checked) selected.push('track');
  if (filterArtist?.checked) selected.push('artist');
  if (filterPlaylist?.checked) selected.push('playlist');
  return selected;
}

function ensureAtLeastOneFilter(changedInput) {
  const selected = getSearchTypes();
  if (selected.length) return true;
  if (changedInput) changedInput.checked = true;
  return false;
}

async function loadSearch() {
  const q = searchInput.value.trim();
  if (!q) return;
  try {
    const selectedTypes = getSearchTypes();
    const typesParam = selectedTypes.join(',');
    const path = `/api/search?q=${encodeURIComponent(q)}${typesParam ? `&types=${encodeURIComponent(typesParam)}` : ''}`;
    const res = await apiFetch(path, { timeoutMs: 20000 });
    if (!res.ok) {
      searchResults.innerHTML = `<div class="track"><div><h4>Recherche impossible</h4><span>${res.error || 'Vérifie la connexion ou yt-dlp.'}</span></div></div>`;
      return;
    }
    searchStack = [];
    searchCache = res.items;
    renderSearch(res.items);
    updateSearchContext();
  } catch (e) {
    searchResults.innerHTML = '<div class="track"><div><h4>Erreur réseau</h4><span>Le serveur ne répond pas.</span></div></div>';
  }
}

async function loadExpandedSearch(item) {
  if (!item || !item.id || !item.type) return;
  if (!['artist', 'playlist'].includes(item.type)) return;
  try {
    const path = `/api/search/expand?type=${encodeURIComponent(item.type)}&id=${encodeURIComponent(item.id)}&title=${encodeURIComponent(item.title || '')}`;
    const res = await apiFetch(path, { timeoutMs: 20000 });
    if (!res.ok) {
      showToast(res.error || 'Impossible de charger les titres.', 'error');
      return;
    }
    searchStack.push({
      type: item.type,
      title: item.title || '',
      previousItems: searchCache.slice(),
    });
    searchCache = res.items || [];
    renderSearch(searchCache);
    updateSearchContext();
    if (!searchCache.length) showToast('Aucun titre trouvé pour cette entrée.', 'info');
  } catch (e) {
    showToast('Erreur réseau pendant le chargement des titres.', 'error');
  }
}

function goBackSearchContext() {
  if (!searchStack.length) return;
  const prev = searchStack.pop();
  searchCache = prev.previousItems || [];
  renderSearch(searchCache);
  updateSearchContext();
}

async function importPlaylistResult(item) {
  if (!item || item.type !== 'playlist' || !item.id) return;
  try {
    const res = await apiFetch('/api/playlists/import', {
      method: 'POST',
      body: JSON.stringify({ id: item.id, title: item.title }),
      timeoutMs: 30000,
    });
    if (!res.ok) {
      showToast(res.error || 'Import playlist impossible.', 'error');
      return;
    }
    await loadPlaylists();
    showToast(`Playlist ajoutée: ${res.name} (${res.count} titres)`, 'info');
  } catch (e) {
    showToast('Erreur réseau pendant l’import playlist.', 'error');
  }
}

async function loadPlaylists() {
  const res = await apiFetch('/api/playlists');
  if (res.ok) renderPlaylists(res.items);
}

async function loadDownloads() {
  const res = await apiFetch('/api/download/list');
  if (res.ok) renderDownloads(res.items);
}

async function loadHistory() {
  const res = await apiFetch('/api/history');
  if (res.ok) renderHistory(res.items);
}

async function createPlaylist() {
  const name = playlistName.value.trim();
  if (!name) return;
  await apiFetch('/api/playlists/create', {
    method: 'POST',
    body: JSON.stringify({ name }),
  });
  playlistName.value = '';
  loadPlaylists();
}

async function downloadCurrent() {
  if (!currentItem || !currentItem.url) return;
  const ok = await downloadItem(currentItem);
  if (ok) loadDownloads();
}

async function fetchLyrics(item) {
  if (!item) return;
  const res = await apiFetch(`/api/lyrics?title=${encodeURIComponent(item.title)}&artist=${encodeURIComponent(item.artist || '')}`);
  if (!res.ok) {
    lyricsBody.innerHTML = '<p class="muted">Lyrics non disponibles.</p>';
    if (lyricsBodyDesktop) lyricsBodyDesktop.innerHTML = '<p class="muted">Lyrics non disponibles.</p>';
    return;
  }
  const synced = res.data.synced || '';
  lyricsLines = parseSyncedLyrics(synced);
  if (!lyricsLines.length) {
    lyricsBody.innerHTML = '<p class="muted">Lyrics non synchronisés.</p>';
    if (lyricsBodyDesktop) lyricsBodyDesktop.innerHTML = '<p class="muted">Lyrics non synchronisés.</p>';
    return;
  }
  const html = lyricsLines.map((line) => `<p data-time="${line.time}">${line.text}</p>`).join('');
  lyricsBody.innerHTML = html;
  if (lyricsBodyDesktop) lyricsBodyDesktop.innerHTML = html;
  syncLyrics();
}

function parseSyncedLyrics(raw) {
  const lines = raw.split('\n');
  const result = [];
  const regex = /\[(\d+):(\d+(?:\.\d+)?)\]/g;
  lines.forEach((line) => {
    const text = line.replace(regex, '').trim();
    let match;
    while ((match = regex.exec(line)) !== null) {
      const min = parseInt(match[1], 10);
      const sec = parseFloat(match[2]);
      const time = min * 60 + sec;
      if (text) result.push({ time, text });
    }
  });
  return result.sort((a, b) => a.time - b.time);
}

function syncLyrics() {
  if (!lyricsLines.length) return;
  const current = audio.currentTime || 0;
  let activeIndex = 0;
  for (let i = 0; i < lyricsLines.length; i += 1) {
    if (current >= lyricsLines[i].time) activeIndex = i;
  }
  if (activeIndex === lyricsIndex) return;
  lyricsIndex = activeIndex;
  const updateNodes = (container) => {
    if (!container) return;
    const nodes = container.querySelectorAll('p');
    nodes.forEach((node, idx) => {
      node.classList.toggle('active', idx === activeIndex);
      if (idx === activeIndex) node.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
  };
  updateNodes(lyricsBody);
  updateNodes(lyricsBodyDesktop);
}

async function downloadItem(item) {
  if (!item || !item.url) return;
  if (item?.type && item.type !== 'track') {
    showToast('Téléchargement disponible uniquement pour les tracks.', 'info');
    return false;
  }
  const res = await apiFetch('/api/download', {
    method: 'POST',
    body: JSON.stringify({
      url: item.url,
      title: item.title,
      artist: item.artist,
      cover: item.cover,
    }),
  });
  if (!res.ok) {
    nowStatus.textContent = 'Erreur';
    const message = res.error || 'Téléchargement impossible.';
    showToast(message, 'error');
    return false;
  }
  return true;
}

async function promptAddToPlaylist(item) {
  if (item?.type && item.type !== 'track') {
    showToast('Seuls les tracks peuvent être ajoutés à une playlist.', 'info');
    return;
  }
  pendingPlaylistItem = item;
  const res = await apiFetch('/api/playlists');
  if (!res.ok) return;
  pickerList.innerHTML = '';
  if (!res.items.length) {
    pickerList.innerHTML = '<div class="track"><div><h4>Aucune playlist</h4><span>Crée une playlist d’abord.</span></div></div>';
  } else {
    res.items.forEach((pl) => {
      const row = document.createElement('div');
      row.className = 'track';
      row.innerHTML = `
        <div>
          <h4>${pl.name}</h4>
          <span>${pl.items.length} titres</span>
        </div>
      `;
      row.addEventListener('click', async () => {
        await apiFetch('/api/playlists/add', {
          method: 'POST',
          body: JSON.stringify({ name: pl.name, item: pendingPlaylistItem }),
        });
        pickerOverlay.classList.add('hidden');
        pendingPlaylistItem = null;
        loadPlaylists();
      });
      pickerList.appendChild(row);
    });
  }
  pickerOverlay.classList.remove('hidden');
}

function openNow() {
  if (!isMobile()) return;
  nowOverlay.classList.remove('hidden');
  playerBar.classList.add('hidden');
  document.body.classList.add('no-scroll');
}

function closeNow() {
  if (!isMobile()) return;
  nowOverlay.classList.add('hidden');
  playerBar.classList.remove('hidden');
  document.body.classList.remove('no-scroll');
  closeLyrics();
}

function toggleQueueDrawer() {
  queueDrawer.classList.toggle('hidden');
}

async function loadDevices() {
  const res = await apiFetch('/api/devices');
  if (!res.ok) return;
  devicesList.innerHTML = '';
  res.items.forEach((device) => {
    const row = document.createElement('div');
    row.className = 'track';
    row.innerHTML = `
      <div>
        <h4>${device.name}</h4>
        <span>${device.host}:${device.port}</span>
        <div class="actions">
          <button data-action="remove">Supprimer</button>
        </div>
      </div>
    `;
    row.querySelector('[data-action="remove"]').addEventListener('click', () => {
      apiFetch('/api/devices/remove', {
        method: 'POST',
        body: JSON.stringify({ id: device.id }),
      }).then(loadDevices);
    });
    devicesList.appendChild(row);
  });
}

async function addDevice() {
  const name = deviceName.value.trim() || 'Device';
  const host = deviceHost.value.trim();
  const port = devicePort.value.trim();
  if (!host || !port) return;
  await apiFetch('/api/devices/add', {
    method: 'POST',
    body: JSON.stringify({ name, host, port }),
  });
  deviceName.value = '';
  deviceHost.value = '';
  devicePort.value = '';
  loadDevices();
}

function setActiveTab(name) {
  tabs.forEach((tab) => tab.classList.toggle('active', tab.dataset.tab === name));
  Object.entries(tabPanels).forEach(([key, panel]) => {
    panel.classList.toggle('hidden', key !== name);
  });
}

playBtn.addEventListener('click', togglePlay);
barPlay.addEventListener('click', togglePlay);
nextBtn.addEventListener('click', nextTrack);
barNext.addEventListener('click', nextTrack);
prevBtn.addEventListener('click', prevTrack);
barPrev.addEventListener('click', prevTrack);
barQueue.addEventListener('click', toggleQueueDrawer);
shuffleBtn.addEventListener('click', () => {
  isShuffle = !isShuffle;
  shuffleBtn.classList.toggle('secondary', !isShuffle);
  syncControlIcons();
});
loopBtn.addEventListener('click', () => {
  isLoop = !isLoop;
  loopBtn.classList.toggle('secondary', !isLoop);
  syncControlIcons();
});

searchBtn.addEventListener('click', loadSearch);
searchInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') loadSearch();
});
if (searchBackBtn) {
  searchBackBtn.addEventListener('click', goBackSearchContext);
}
[
  filterTrack,
  filterArtist,
  filterPlaylist,
].forEach((input) => {
  if (!input) return;
  input.addEventListener('change', () => {
    ensureAtLeastOneFilter(input);
    if (searchInput.value.trim()) loadSearch();
  });
});

playlistCreate.addEventListener('click', createPlaylist);
barDownload.addEventListener('click', downloadCurrent);
downloadBtn.addEventListener('click', downloadCurrent);

volume.addEventListener('input', () => {
  audio.volume = volume.value / 100;
  barVolume.value = volume.value;
  volLabel.textContent = volume.value;
  barVolLabel.textContent = volume.value;
});

barVolume.addEventListener('input', () => {
  audio.volume = barVolume.value / 100;
  volume.value = barVolume.value;
  volLabel.textContent = barVolume.value;
  barVolLabel.textContent = barVolume.value;
});

progress.addEventListener('input', () => {
  if (audio.duration) audio.currentTime = (progress.value / 100) * audio.duration;
});

barProgress.addEventListener('input', () => {
  if (audio.duration) audio.currentTime = (barProgress.value / 100) * audio.duration;
});


audio.addEventListener('timeupdate', syncProgress);
audio.addEventListener('timeupdate', syncLyrics);
audio.addEventListener('ended', nextTrack);
audio.addEventListener('play', syncControlIcons);
audio.addEventListener('pause', syncControlIcons);

nowClose.addEventListener('click', closeNow);
playerBar.addEventListener('click', (e) => {
  if (e.target.closest('.controls') || e.target.closest('.volume') || e.target.closest('.progress-container')) return;
  openNow();
});

queueClose.addEventListener('click', () => queueDrawer.classList.add('hidden'));
devicesBtn.addEventListener('click', () => {
  devicesOverlay.classList.remove('hidden');
  loadDevices();
});
devicesClose.addEventListener('click', () => devicesOverlay.classList.add('hidden'));
deviceAdd.addEventListener('click', addDevice);

tabs.forEach((tab) => {
  tab.addEventListener('click', () => setActiveTab(tab.dataset.tab));
});

function init() {
  loadPlaylists();
  loadDownloads();
  loadHistory();
  setInterval(loadHistory, 15000);
  queueDrawer.classList.add('hidden');
  setActiveTab('playlists');
  if (!isMobile()) {
    nowOverlay.classList.remove('hidden');
    document.body.classList.remove('no-scroll');
  }
  syncControlIcons();
}

pickerClose.addEventListener('click', () => {
  pickerOverlay.classList.add('hidden');
  pendingPlaylistItem = null;
});

function openLyrics() {
  if (!isMobile()) return;
  lyricsPanel.classList.remove('hidden');
  nowCard.classList.add('lyrics-open');
}

function closeLyrics() {
  if (!isMobile()) return;
  lyricsPanel.classList.add('hidden');
  nowCard.classList.remove('lyrics-open');
}

lyricsToggle.addEventListener('click', openLyrics);
lyricsClose.addEventListener('click', closeLyrics);

init();
