const api = window.clearplay;
let preparationTimer;
const $ = id => document.getElementById(id);
const video = $('video');
const providerName = { baidu: '百度网盘', quark: '夸克网盘', local: '本地视频', recent: '最近播放', sample: '播放示例' };
const state = {
  view: 'baidu', authProvider: 'baidu', accounts: { baidu: {}, quark: {} },
  files: [], queue: [], breadcrumbs: [{ name: '全部文件', dir: '/' }], page: 1,
  hasMore: false, loading: false, current: null, sources: [], hls: null,
  listRevision: 0, playRevision: 0, authRevision: 0, authTimer: null, progressAt: 0, seeking: false,
};
const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}
function iconElement(name, className = '') {
  const node = element('span', className);
  node.innerHTML = icon(name);
  return node;
}
let toastTimer;
function toast(message) {
  clearTimeout(toastTimer);
  $('toast').textContent = message;
  $('toast').classList.remove('hidden');
  toastTimer = setTimeout(() => $('toast').classList.add('hidden'), 5500);
}
function errorMessage(error) { return error?.message || '操作失败，请重试。'; }
const keyOf = file => `${file.provider}:${file.id}`;
function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00';
  const value = Math.floor(seconds);
  const parts = [Math.floor(value / 3600), Math.floor(value / 60) % 60, value % 60];
  return (parts[0] ? parts : parts.slice(1)).map(p => String(p).padStart(2, '0')).join(':');
}
function formatSize(bytes) {
  if (!bytes) return '—';
  const unit = Math.min(3, Math.floor(Math.log(bytes) / Math.log(1024)));
  return `${(bytes / 1024 ** unit).toFixed(unit ? 1 : 0)} ${['B', 'KB', 'MB', 'GB'][unit]}`;
}
function rangeFill(input) { input.style.setProperty('--fill', `${Number(input.value) / Number(input.max) * 100}%`); }

async function updateAccounts() {
  const result = await api.state();
  $('app-version').textContent = `v${result.version}`;
  state.accounts = { baidu: result.baidu, quark: result.quark };
  for (const provider of ['baidu', 'quark']) $(provider + '-dot').classList.toggle('connected', result[provider].connected);
  const count = Object.values(state.accounts).filter(a => a.connected).length;
  $('connection-title').textContent = count ? `已连接 ${count} 个网盘` : '连接你的网盘';
  $('connection-subtitle').textContent = count ? '管理网盘连接' : '开启私人放映室';
  return result;
}

function renderViewHeading() {
  const cloud = ['baidu', 'quark'].includes(state.view);
  const connected = !!state.accounts[state.view]?.connected;
  $('library-title').textContent = cloud ? '云端片库' : providerName[state.view];
  $('source-badge').textContent = providerName[state.view];
  $('source-badge').classList.toggle('hidden', !cloud);
  $('source-state').classList.toggle('hidden', !cloud);
  $('source-state').textContent = connected ? '已连接' : '未连接';
  $('source-state').classList.toggle('connected', connected);
  $('connect-drive').querySelector('span').textContent = cloud && connected ? '管理网盘连接' : `连接${cloud ? providerName[state.view] : '网盘'}`;
  $('search').placeholder = state.view === 'recent' ? '搜索播放记录' : '搜索当前目录视频';
  document.querySelectorAll('.nav-item').forEach(button => button.classList.toggle('active', button.dataset.view === state.view));
  $('breadcrumbs').replaceChildren();
  if (!cloud) return;
  state.breadcrumbs.forEach((crumb, index) => {
    if (index) $('breadcrumbs').append(iconElement('chevron'));
    const button = element('button', '', crumb.name);
    button.addEventListener('click', () => {
      state.breadcrumbs = state.breadcrumbs.slice(0, index + 1);
      loadFiles();
    });
    $('breadcrumbs').append(button);
  });
}

function emptyFiles(title, detail, action, callback, symbol = 'cloud') {
  const box = element('div', 'library-empty');
  box.append(iconElement(symbol, 'empty-icon'));
  const copy = element('div');
  copy.append(element('h3', '', title), element('p', '', detail));
  box.append(copy);
  if (action) {
    const button = element('button', 'button secondary', action);
    button.addEventListener('click', callback);
    box.append(button);
  }
  $('file-list').replaceChildren(box);
}

function renderFiles() {
  renderViewHeading();
  $('load-more').classList.toggle('hidden', !state.hasMore);
  $('load-more').disabled = state.loading;
  if (state.loading && !state.files.length) {
    emptyFiles('正在读取视频目录', '请稍候，正在连接网盘。', null, null);
    return;
  }
  if (['baidu', 'quark'].includes(state.view) && !state.accounts[state.view]?.connected) {
    emptyFiles(`你的${providerName[state.view]}，也是你的片库`, '连接后浏览文件夹、选择影片，直接在这里播放。', '连接网盘 ↗', () => showConnect(state.view), state.view === 'quark' ? 'quark' : 'cloud');
    return;
  }
  const query = $('search').value.trim().toLocaleLowerCase();
  const files = state.files.filter(f => f.name.toLocaleLowerCase().includes(query));
  if (!files.length) {
    if (query) emptyFiles('没有找到匹配的视频', state.hasMore ? '搜索仅覆盖已加载文件，可以加载更多后继续查找。' : '试试其他关键词。', '清空搜索', () => { $('search').value = ''; renderFiles(); }, 'search');
    else if (state.view === 'local') emptyFiles('把本地收藏，也带进来', '选择一个或多个视频文件，即可开始播放。', '打开本地视频', openLocal, 'folder');
    else if (state.view === 'recent') emptyFiles('每次回来，接着上次看', '播放视频后，这里会留下你的观看进度。', null, null, 'clock');
    else emptyFiles('这个目录里暂时没有视频', state.hasMore ? '本页没有视频，可以加载更多文件。' : '这里只显示文件夹和视频，试试其他文件夹。', null, null, 'folder');
    return;
  }
  const fragment = document.createDocumentFragment();
  for (const file of files) {
    const button = element('button', `file-row${state.current && keyOf(file) === keyOf(state.current) ? ' selected' : ''}`);
    button.setAttribute('aria-label', `${file.isDir ? '打开文件夹' : '播放'}：${file.name}`);
    button.append(iconElement(file.isDir ? 'folder' : 'film'));
    const info = element('div');
    info.append(element('strong', '', file.name));
    const caption = state.view === 'recent' ? `${providerName[file.provider]} · ${file.position ? `已看到 ${formatTime(file.position)}` : '从头播放'}` : file.isDir ? '文件夹' : `${providerName[file.provider]} · ${file.name.split('.').pop().toUpperCase()}`;
    info.append(element('small', '', caption));
    button.append(info, element('span', 'file-size', file.isDir ? '—' : formatSize(file.size)));
    button.append(element('span', 'file-date', file.modifiedAt ? new Date(file.modifiedAt).toLocaleDateString('zh-CN') : ''));
    button.append(iconElement(file.isDir ? 'chevron' : 'play', 'row-arrow'));
    button.addEventListener('click', () => {
      if (file.isDir) {
        state.breadcrumbs.push({ name: file.name, dir: file.dir });
        loadFiles();
      } else {
        state.queue = state.files.filter(f => !f.isDir);
        playFile(file);
      }
    });
    fragment.append(button);
  }
  $('file-list').replaceChildren(fragment);
}

async function selectView(view) {
  state.view = view;
  state.breadcrumbs = [{ name: '全部文件', dir: view === 'quark' ? '0' : '/' }];
  $('search').value = '';
  await loadFiles();
}

async function loadFiles(append = false) {
  const revision = ++state.listRevision;
  const view = state.view;
  if (!append) { state.files = []; state.page = 1; state.hasMore = false; }
  state.loading = true;
  renderFiles();
  try {
    let result;
    if (['local', 'recent'].includes(view)) result = { files: await api.library(view), hasMore: false };
    else if (!state.accounts[view]?.connected) result = { files: [], hasMore: false };
    else result = await api.list(view, state.breadcrumbs.at(-1).dir, append ? state.page + 1 : 1);
    if (revision !== state.listRevision) return;
    state.files = append ? [...state.files, ...result.files.filter(f => !state.files.some(old => keyOf(old) === keyOf(f)))] : result.files;
    if (append) state.page++;
    state.hasMore = result.hasMore;
    state.loading = false;
    renderFiles();
  } catch (error) {
    if (revision !== state.listRevision) return;
    state.loading = false;
    renderViewHeading();
    if (append) { renderFiles(); toast(errorMessage(error)); }
    else emptyFiles('目录暂时没有打开', errorMessage(error), '重试', () => loadFiles(), 'refresh');
  }
}

async function openLocal() {
  try {
    const files = await api.openLocal();
    if (!files.length) return;
    await selectView('local');
    state.queue = files;
    await playFile(files[0]);
  } catch (error) { toast(errorMessage(error)); }
}

function renderQueue() {
  $('queue-count').textContent = String(state.queue.length).padStart(2, '0');
  if (!state.queue.length) return;
  const fragment = document.createDocumentFragment();
  state.queue.forEach((file, index) => {
    const selected = state.current && keyOf(state.current) === keyOf(file);
    const button = element('button', `queue-item${selected ? ' selected' : ''}`);
    button.append(element('span', 'queue-number', selected ? '▶' : String(index + 1).padStart(2, '0')));
    const info = element('span', 'queue-info');
    info.append(element('strong', '', file.name), element('small', '', `${providerName[file.provider]}${file.size ? ` · ${formatSize(file.size)}` : ''}`));
    button.append(info);
    button.addEventListener('click', () => playFile(file));
    fragment.append(button);
  });
  $('queue').replaceChildren(fragment);
  updateNext();
}

function updateNext() {
  const index = state.current ? state.queue.findIndex(f => keyOf(f) === keyOf(state.current)) : -1;
  $('next-video').disabled = index < 0 || index >= state.queue.length - 1;
}

function nextVideo() {
  const index = state.queue.findIndex(f => state.current && keyOf(f) === keyOf(state.current));
  if (index >= 0 && state.queue[index + 1]) playFile(state.queue[index + 1]);
}

function showPlayerMessage(title, detail = '', retry = false) {
  $('player-message').classList.remove('hidden');
  $('player-message-title').textContent = title;
  $('player-message-detail').textContent = detail;
  $('retry-play').classList.toggle('hidden', !retry);
  $('player-spinner').classList.toggle('hidden', retry);
}

function showPlaybackError(detail) {
  showPlayerMessage('暂时无法播放这个视频', detail || '播放资源失效、网盘权益不足，或当前视频编码不受支持。请重新获取资源，或尝试其他可用画质。', true);
}

async function saveProgress() {
  if (!state.current || !video.src || !Number.isFinite(video.duration)) return;
  try { await api.progress(state.current.provider, state.current.id, video.currentTime, video.duration); }
  catch (error) { toast(`进度未保存：${errorMessage(error)}`); }
}

async function playFile(file, retry = false) {
  clearInterval(preparationTimer);
  const revision = ++state.playRevision;
  const retryPosition = retry ? video.currentTime : 0;
  await saveProgress();
  if (revision !== state.playRevision) return;
  state.current = file;
  video.pause();
  state.hls?.destroy();
  state.hls = null;
  video.removeAttribute('src');
  video.load();
  $('welcome').classList.add('hidden');
  $('video-tag').classList.add('hidden');
  $('now-title').textContent = file.name;
  $('now-detail').textContent = '正在读取播放资源';
  $('current-time').textContent = '00:00';
  $('duration').textContent = '00:00';
  $('seek').value = 0;
  rangeFill($('seek'));
  $('toggle-play').disabled = true;
  $('seek').disabled = true;
  $('quality').disabled = true;
  showPlayerMessage('正在打开视频', file.provider === 'local' || file.provider === 'sample' ? '准备本地播放资源…' : `正在获取${providerName[file.provider]}的可用画质…`);
  renderQueue();
  renderFiles();
  try {
    const result = await api.prepare(file.provider, file.id);
    if (revision !== state.playRevision) return;
    clearInterval(preparationTimer);
    state.sources = result.sources;
    $('quality').replaceChildren(...result.sources.map((source, index) => {
      const option = element('option', '', source.label);
      option.value = index;
      return option;
    }));
    $('quality').disabled = result.sources.length < 2;
    if (result.notice) toast(result.notice);
    const hd = result.sources.findIndex(source => source.hls && source.label === '720P');
    const streaming = result.sources.findIndex(source => source.hls);
    loadSource(hd >= 0 ? hd : streaming >= 0 ? streaming : 0, retryPosition || result.resumeAt || 0, true);
    if (state.view === 'recent') loadFiles();
  } catch (error) {
    if (revision === state.playRevision) { clearInterval(preparationTimer); showPlaybackError(errorMessage(error)); }
  }
}

api.onPreparing(info => {
  if (!state.current || keyOf(state.current) !== `${info.provider}:${info.id}`) return;
  clearInterval(preparationTimer);
  let remaining = Math.ceil(info.seconds);
  const render = () => showPlayerMessage('网盘视频准备中', remaining > 0 ? `网盘返回的等待时间还剩约 ${remaining} 秒，完成后自动播放。` : '等待已完成，正在获取视频流…');
  render();
  preparationTimer = setInterval(() => { remaining = Math.max(0, remaining - 1); render(); }, 1000);
});

let sourceRevision = 0;
function loadSource(index, position, autoplay) {
  const revision = ++sourceRevision;
  const source = state.sources[index];
  state.hls?.destroy();
  state.hls = null;
  video.pause();
  showPlayerMessage('正在读取视频信息', source.label === '原画' && ['baidu', 'quark'].includes(state.current.provider) ? '原画首次播放需要读取视频索引，较大的文件可能需要更长时间。' : '正在读取视频流…');
  $('quality').value = String(index);
  $('video-tag').textContent = source.label;
  video.onloadedmetadata = async () => {
    if (revision !== sourceRevision) return;
    video.playbackRate = Number($('speed').value);
    if (position > 0 && position < video.duration) video.currentTime = position;
    $('duration').textContent = formatTime(video.duration);
    $('toggle-play').disabled = false;
    $('skip-back').disabled = false;
    $('seek').disabled = !Number.isFinite(video.duration);
    $('now-detail').textContent = `${providerName[state.current.provider]} · ${video.videoWidth} × ${video.videoHeight}`;
    $('video-tag').classList.remove('hidden');
    $('player-message').classList.add('hidden');
    if (autoplay) {
      try { await video.play(); } catch { toast('视频已就绪，点击播放继续。'); }
    }
  };
  if (source.hls && Hls.isSupported()) {
    state.hls = new Hls({ enableWorker: false, maxBufferLength: 30 });
    state.hls.loadSource(source.url);
    state.hls.attachMedia(video);
    state.hls.on(Hls.Events.ERROR, (_event, data) => {
      if (data.fatal && revision === sourceRevision) showPlaybackError('网盘视频流暂不可用，请重新获取资源或切换其他画质。');
    });
  } else video.src = source.url;
}

async function togglePlay() {
  if (!video.src && !state.hls) return;
  try { if (video.paused) await video.play(); else video.pause(); }
  catch { showPlaybackError(); }
}

function renderPlayState() {
  $('toggle-play').innerHTML = icon(video.paused ? 'play' : 'pause');
  $('toggle-play').setAttribute('aria-label', video.paused ? '播放' : '暂停');
  $('playing-indicator').classList.toggle('on', !video.paused);
}

function setVolume(value) {
  video.volume = Math.max(0, Math.min(1, value));
  video.muted = false;
  $('volume').value = video.volume;
  rangeFill($('volume'));
  localStorage.setItem('clearplay-volume', video.volume);
  renderMute();
}

function renderMute() {
  const muted = video.muted || video.volume === 0;
  $('toggle-mute').innerHTML = icon(muted ? 'mute' : 'volume');
  $('toggle-mute').setAttribute('aria-label', muted ? '取消静音' : '静音');
}

async function fullscreen() {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await $('player-card').requestFullscreen(); }
  catch { toast('暂时无法进入全屏，请重试。'); }
}

function renderConnect() {
  const provider = state.authProvider;
  const connected = state.accounts[provider]?.connected && !(provider === 'baidu' && state.forceWebLogin);
  document.querySelectorAll('[data-provider]').forEach(button => button.setAttribute('aria-selected', String(button.dataset.provider === provider)));
  $('connected-panel').classList.toggle('hidden', !connected);
  $('connected-name').textContent = `${providerName[provider]}已连接`;
  $('baidu-panel').classList.toggle('hidden', provider !== 'baidu' || connected);
  $('quark-panel').classList.toggle('hidden', provider !== 'quark' || connected);
  $('connect-error').classList.add('hidden');
  $('app-key').value = state.accounts.baidu.appKey || '';
  $('baidu-advanced').open = false;
  $('switch-baidu-web').classList.toggle('hidden', provider !== 'baidu' || state.accounts.baidu.mode !== 'oauth');
}

async function cancelAuth() {
  state.authRevision++;
  clearTimeout(state.authTimer);
  state.authTimer = null;
  $('secret-key').value = '';
  $('auth-code').textContent = '';
  $('auth-code-panel').classList.add('hidden');
  $('baidu-form').classList.remove('hidden');
  $('begin-auth').disabled = false;
  await api.cancelBaidu();
}

async function showConnect(provider = state.view) {
  await cancelAuth();
  state.authProvider = ['baidu', 'quark'].includes(provider) ? provider : 'baidu';
  state.forceWebLogin = false;
  renderConnect();
  if (!$('connect-dialog').open) $('connect-dialog').showModal();
}

function connectError(error) {
  $('connect-error').textContent = errorMessage(error);
  $('connect-error').classList.remove('hidden');
}

async function connected(provider) {
  await updateAccounts();
  $('connect-dialog').close();
  await selectView(provider);
  toast(`${providerName[provider]}连接成功，选择视频开始播放。`);
}

async function pollAuth(revision, interval) {
  state.authTimer = setTimeout(async () => {
    if (state.authRevision !== revision || !$('connect-dialog').open) return;
    try {
      const result = await api.pollBaidu();
      if (state.authRevision !== revision) return;
      if (result.pending) pollAuth(revision, result.interval);
      else await connected('baidu');
    } catch (error) {
      if (state.authRevision !== revision) return;
      await cancelAuth();
      connectError(error);
    }
  }, interval * 1000);
}

document.querySelectorAll('.nav-item').forEach(button => button.addEventListener('click', () => selectView(button.dataset.view)));
$('open-local').addEventListener('click', openLocal);
$('connect-drive').addEventListener('click', () => showConnect());
$('welcome-connect').addEventListener('click', () => showConnect());
$('manage-connections').addEventListener('click', () => showConnect());
$('play-sample').addEventListener('click', () => {
  const sample = { provider: 'sample', id: 'clearplay-sample', name: '光的轨迹 · 1080P 播放示例' };
  state.queue = [sample];
  playFile(sample);
});
$('refresh-list').addEventListener('click', () => loadFiles());
$('load-more').addEventListener('click', () => loadFiles(true));
$('search').addEventListener('input', renderFiles);
$('toggle-play').addEventListener('click', togglePlay);
$('skip-back').addEventListener('click', () => { video.currentTime = Math.max(0, video.currentTime - 10); });
$('next-video').addEventListener('click', nextVideo);
$('retry-play').addEventListener('click', () => { if (state.current) playFile(state.current, true); });
$('fullscreen').addEventListener('click', fullscreen);
$('volume').addEventListener('input', () => setVolume(Number($('volume').value)));
$('toggle-mute').addEventListener('click', () => { video.muted = !video.muted; renderMute(); });
$('speed').addEventListener('change', () => { video.playbackRate = Number($('speed').value); localStorage.setItem('clearplay-speed', $('speed').value); toast(`播放速度 ${$('speed').value}×`); });
$('quality').addEventListener('change', () => loadSource(Number($('quality').value), video.currentTime, !video.paused));
$('autoplay').checked = localStorage.getItem('clearplay-autoplay') === 'true';
$('autoplay').addEventListener('change', () => localStorage.setItem('clearplay-autoplay', $('autoplay').checked));
$('seek').addEventListener('input', () => { state.seeking = true; rangeFill($('seek')); $('current-time').textContent = formatTime(Number($('seek').value) / 1000 * video.duration); });
$('seek').addEventListener('change', () => { if (Number.isFinite(video.duration)) video.currentTime = Number($('seek').value) / 1000 * video.duration; state.seeking = false; });
video.addEventListener('click', togglePlay);
video.addEventListener('dblclick', fullscreen);
video.addEventListener('play', renderPlayState);
video.addEventListener('pause', () => { renderPlayState(); saveProgress(); });
video.addEventListener('timeupdate', () => {
  if (!state.seeking) {
    $('current-time').textContent = formatTime(video.currentTime);
    $('seek').value = Number.isFinite(video.duration) && video.duration ? video.currentTime / video.duration * 1000 : 0;
    rangeFill($('seek'));
  }
  if (Date.now() - state.progressAt > 5000) { state.progressAt = Date.now(); saveProgress(); }
});
video.addEventListener('error', async () => {
  if (!video.getAttribute('src') || video.error?.code === 1) return;
  const revision = sourceRevision;
  const code = video.error?.code;
  let detail = code === 2 ? '视频数据传输中断，请重新获取资源或尝试其他画质。' : code === 3 ? '视频解码失败，请尝试其他可用画质。' : '无法解析当前视频资源，请重新获取资源或尝试其他可用画质。';
  try {
    const error = await api.mediaError();
    if (error?.httpStatus) detail = `网盘媒体请求返回 HTTP ${error.httpStatus}，请重新获取播放资源；仍失败时请检查网盘登录和账号权益。`;
    else if (error?.message) detail = error.message;
  } catch { /* 保留浏览器提供的媒体错误分类。 */ }
  if (revision === sourceRevision) showPlaybackError(detail);
});
video.addEventListener('playing', () => $('player-message').classList.add('hidden'));
video.addEventListener('ended', () => { saveProgress(); if ($('autoplay').checked) nextVideo(); });
window.addEventListener('beforeunload', saveProgress);

$('show-shortcuts').addEventListener('click', () => $('shortcuts-dialog').showModal());
$('close-shortcuts').addEventListener('click', () => $('shortcuts-dialog').close());
$('close-connect').addEventListener('click', () => $('connect-dialog').close());
$('connect-dialog').addEventListener('close', () => { cancelAuth().catch(error => toast(errorMessage(error))); });
document.querySelectorAll('[data-provider]').forEach(button => button.addEventListener('click', async () => {
  await cancelAuth();
  state.authProvider = button.dataset.provider;
  renderConnect();
}));
$('open-docs').addEventListener('click', () => api.openDocs().catch(connectError));
$('open-platform').addEventListener('click', () => api.openPlatform().catch(connectError));
$('switch-baidu-web').addEventListener('click', () => { state.forceWebLogin = true; renderConnect(); });
$('login-baidu').addEventListener('click', () => api.loginBaidu().catch(connectError));
$('finish-baidu').addEventListener('click', async () => {
  $('finish-baidu').disabled = true;
  $('connect-error').classList.add('hidden');
  try { await api.connectBaidu(); await connected('baidu'); }
  catch (error) { connectError(error); }
  finally { $('finish-baidu').disabled = false; }
});
$('baidu-form').addEventListener('submit', async event => {
  event.preventDefault();
  const revision = ++state.authRevision;
  $('begin-auth').disabled = true;
  $('connect-error').classList.add('hidden');
  try {
    const result = await api.beginBaidu($('app-key').value, $('secret-key').value);
    $('secret-key').value = '';
    if (revision !== state.authRevision) return;
    $('baidu-form').classList.add('hidden');
    $('auth-code-panel').classList.remove('hidden');
    $('auth-code').textContent = result.userCode;
    $('auth-status').textContent = `等待授权完成，授权码约 ${Math.ceil(result.expiresIn / 60)} 分钟内有效`;
    pollAuth(revision, result.interval);
  } catch (error) {
    if (revision === state.authRevision) { connectError(error); $('begin-auth').disabled = false; }
  }
});
$('open-auth').addEventListener('click', () => api.openBaidu().catch(connectError));
$('login-quark').addEventListener('click', () => api.loginQuark().catch(connectError));
$('finish-quark').addEventListener('click', async () => {
  $('finish-quark').disabled = true;
  $('connect-error').classList.add('hidden');
  try { await api.connectQuark(); await connected('quark'); }
  catch (error) { connectError(error); }
  finally { $('finish-quark').disabled = false; }
});
$('disconnect').addEventListener('click', async () => {
  const provider = state.authProvider;
  try {
    if (state.current?.provider === provider) {
      await saveProgress();
      video.pause();
      state.hls?.destroy();
      state.hls = null;
      video.removeAttribute('src');
      video.load();
      state.playRevision++;
      state.current = null;
      $('welcome').classList.remove('hidden');
      $('player-message').classList.add('hidden');
      $('video-tag').classList.add('hidden');
      $('toggle-play').disabled = true;
      $('seek').disabled = true;
      $('quality').disabled = true;
      $('now-title').textContent = '等待你的下一部影片';
      $('now-detail').textContent = '原画播放 · 最高 4× 倍速';
    }
    await api.logout(provider);
    await updateAccounts();
    renderConnect();
    await loadFiles();
    toast(`${providerName[provider]}已断开连接。`);
  } catch (error) { connectError(error); }
});

document.addEventListener('keydown', event => {
  if ($('connect-dialog').open || $('shortcuts-dialog').open || ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(document.activeElement.tagName) || event.ctrlKey || event.altKey || event.metaKey) return;
  const key = event.key.toLowerCase();
  if (![' ', 'arrowleft', 'arrowright', 'arrowup', 'arrowdown', 'm', 'f'].includes(key)) return;
  event.preventDefault();
  if (key === ' ') togglePlay();
  if (key === 'f') fullscreen();
  if (key === 'm') { video.muted = !video.muted; renderMute(); }
  if (key === 'arrowup') setVolume(video.volume + 0.05);
  if (key === 'arrowdown') setVolume(video.volume - 0.05);
  if (Number.isFinite(video.duration)) {
    if (key === 'arrowleft') video.currentTime = Math.max(0, video.currentTime - 10);
    if (key === 'arrowright') video.currentTime = Math.min(video.duration, video.currentTime + 10);
  }
});

async function initialize() {
  const speed = localStorage.getItem('clearplay-speed') || '1';
  if ([...$('speed').options].some(option => option.value === speed)) $('speed').value = speed;
  const volume = Number(localStorage.getItem('clearplay-volume') ?? 0.8);
  setVolume(Number.isFinite(volume) ? volume : 0.8);
  try {
    const result = await updateAccounts();
    if (result.notice) toast(result.notice);
    await loadFiles();
  } catch (error) { toast(errorMessage(error)); }
}
initialize();
