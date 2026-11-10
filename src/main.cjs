const { app, BrowserWindow, ipcMain, protocol, net, session, safeStorage, dialog, shell, Menu, screen } = require('electron');
const { readFileSync, writeFileSync, renameSync, existsSync, mkdirSync } = require('node:fs');
const { stat } = require('node:fs/promises');
const { pathToFileURL } = require('node:url');
const { createHash } = require('node:crypto');
const path = require('node:path');
const { BaiduDrive, BaiduWebDrive, QuarkDrive, VIDEO_EXT } = require('./providers.cjs');
const { MediaRegistry } = require('./media.cjs');
const { createDriveLogin, browserUserAgent } = require('./drive-login.cjs');

protocol.registerSchemesAsPrivileged([{ scheme: 'clearplay', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } }]);
if (process.env.CLEARPLAY_USER_DATA) app.setPath('userData', process.env.CLEARPLAY_USER_DATA);
if (!app.requestSingleInstanceLock()) app.quit();

let mainWindow;
let baidu;
let baiduWeb;
let baiduSession;
let baiduMode = 'web';
let quark;
let quarkSession;
const logins = {};
let media;
let data = { history: [], localFiles: [] };
let active = null;
let playRevision = 0;
const knownFiles = new Map();
const keyOf = file => `${file.provider}:${file.id}`;
const publicFile = ({ file, ...rest }) => rest;
const dataFile = () => path.join(app.getPath('userData'), 'library.json');
const credentialsFile = () => path.join(app.getPath('userData'), 'baidu-auth.bin');

function atomicWrite(file, content) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(`${file}.tmp`, content);
  renameSync(`${file}.tmp`, file);
}

function saveLibrary() { atomicWrite(dataFile(), JSON.stringify(data)); }

function saveCredentials(credentials) {
  if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows 凭据加密当前不可用，无法保存网盘授权。');
  atomicWrite(credentialsFile(), safeStorage.encryptString(JSON.stringify(credentials)));
}

function remember(file, progress = {}) {
  const previous = data.history.find(f => keyOf(f) === keyOf(file));
  const entry = { ...previous, ...file, ...progress, playedAt: Date.now() };
  data.history = [entry, ...data.history.filter(f => keyOf(f) !== keyOf(file))].slice(0, 100);
  saveLibrary();
}

function register(channel, handler) {
  ipcMain.handle(channel, async (event, ...args) => {
    if (event.sender !== mainWindow?.webContents || event.senderFrame !== mainWindow.webContents.mainFrame || new URL(event.senderFrame.url).host !== 'app') return { ok: false, error: '无效的应用请求。' };
    try { return { ok: true, data: await handler(...args) }; }
    catch (error) { return { ok: false, error: error.message || '操作失败，请重试。' }; }
  });
}

function drive(provider) {
  if (provider === 'baidu') return baiduMode === 'oauth' ? baidu : baiduWeb;
  if (provider === 'quark') return quark;
  throw new Error('不支持的网盘来源。');
}

async function setup() {
  Menu.setApplicationMenu(null);
  let storageNotice = '';
  try {
    if (existsSync(dataFile())) {
      const stored = JSON.parse(readFileSync(dataFile(), 'utf8'));
      data = { history: Array.isArray(stored.history) ? stored.history.slice(0, 100) : [], localFiles: Array.isArray(stored.localFiles) ? stored.localFiles.slice(0, 100) : [], baiduMode: stored.baiduMode };
    }
  } catch { storageNotice = '本地播放记录无法读取。'; }
  let credentials = {};
  try {
    if (existsSync(credentialsFile())) credentials = JSON.parse(safeStorage.decryptString(readFileSync(credentialsFile())));
  } catch { storageNotice = '保存的百度授权无法解密，请重新连接。'; }
  baidu = new BaiduDrive({ credentials, save: saveCredentials });
  baiduMode = ['oauth', 'web'].includes(data.baiduMode) ? data.baiduMode : credentials.accessToken ? 'oauth' : 'web';
  baiduSession = session.fromPartition('persist:clearplay-baidu');
  quarkSession = session.fromPartition('persist:clearplay-quark');
  baiduSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  quarkSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  const userAgent = browserUserAgent(quarkSession.getUserAgent(), app.getName(), app.getVersion());
  baiduSession.setUserAgent(userAgent);
  quarkSession.setUserAgent(userAgent);
  baiduWeb = new BaiduWebDrive({ userAgent, getCookie: async () => (await baiduSession.cookies.get({ url: 'https://pan.baidu.com/' })).map(c => `${c.name}=${c.value}`).join('; ') });
  baiduWeb.connected = (await baiduSession.cookies.get({ url: 'https://pan.baidu.com/' })).some(c => ['BDUSS', 'BDUSS_BFESS'].includes(c.name));
  logins.baidu = createDriveLogin('baidu', baiduSession);
  logins.quark = createDriveLogin('quark', quarkSession);
  for (const action of ['retry', 'close']) ipcMain.handle(`drive-login:${action}`, event => {
    const login = Object.values(logins).find(candidate => candidate.owns(event));
    if (login) action === 'retry' ? login.reload() : login.close();
  });
  const getCookie = async () => (await quarkSession.cookies.get({ url: 'https://drive.quark.cn/' })).map(c => `${c.name}=${c.value}`).join('; ');
  quark = new QuarkDrive({
    getCookie, userAgent,
    setCookies: async cookies => {
      for (const raw of cookies) {
        const match = /^(__puus|__pus)=([^;]*)/i.exec(raw);
        if (!match) continue;
        const age = /max-age=(\d+)/i.exec(raw);
        const expires = /expires=([^;]+)/i.exec(raw);
        const expirationDate = age ? Date.now() / 1000 + Number(age[1]) : expires ? Date.parse(expires[1]) / 1000 : undefined;
        await quarkSession.cookies.set({ url: 'https://drive.quark.cn/', domain: '.quark.cn', path: '/', name: match[1], value: match[2], secure: true, httpOnly: true, ...(Number.isFinite(expirationDate) ? { expirationDate } : {}) });
      }
    },
  });
  quark.connected = (await quarkSession.cookies.get({ url: 'https://drive.quark.cn/' })).some(c => ['__pus', '__puus'].includes(c.name));
  media = new MediaRegistry();
  const assets = {
    '/index.html': path.join(__dirname, 'renderer/index.html'),
    '/app.js': path.join(__dirname, 'renderer/app.js'),
    '/style.css': path.join(__dirname, 'renderer/style.css'),
    '/drive-login.html': path.join(__dirname, 'renderer/drive-login.html'),
    '/drive-login.js': path.join(__dirname, 'renderer/drive-login.js'),
    '/drive-login.css': path.join(__dirname, 'renderer/drive-login.css'),
    '/hls.min.js': path.join(app.getAppPath(), 'node_modules/hls.js/dist/hls.min.js'),
  };
  protocol.handle('clearplay', request => {
    const url = new URL(request.url);
    if (url.host === 'media') return media.handle(request);
    const file = url.host === 'app' ? assets[url.pathname] : null;
    if (!file) return new Response(null, { status: 404 });
    return net.fetch(pathToFileURL(file).href);
  });

  register('app:state', () => ({ baidu: { ...drive('baidu').status(), appKey: baidu.status().appKey, mode: baiduMode }, quark: quark.status(), notice: storageNotice, version: app.getVersion() }));
  register('baidu:begin', (key, secret) => baidu.begin(key, secret));
  register('baidu:poll', async () => {
    const result = await baidu.poll();
    if (!result.pending) { baiduMode = 'oauth'; data.baiduMode = baiduMode; saveLibrary(); }
    return result;
  });
  register('baidu:cancel', () => baidu.cancel());
  register('baidu:open', async () => {
    if (!baidu.pending?.verificationUrl) throw new Error('请先生成授权码。');
    await shell.openExternal(baidu.pending.verificationUrl);
  });
  register('app:docs', () => shell.openExternal('https://pan.baidu.com/union/doc/al0rwqzzl'));
  register('app:platform', () => shell.openExternal('https://pan.baidu.com/union'));
  register('baidu:login', () => logins.baidu.open());
  register('baidu:connect', async () => {
    const result = await baiduWeb.connect();
    baiduMode = 'web'; data.baiduMode = baiduMode; saveLibrary();
    await baiduSession.cookies.flushStore();
    logins.baidu.close();
    return result;
  });
  register('quark:login', () => logins.quark.open());
  register('quark:connect', async () => {
    const result = await quark.connect();
    await quarkSession.cookies.flushStore();
    logins.quark.close();
    return result;
  });
  register('drive:logout', async provider => {
    drive(provider);
    if (active?.provider === provider) { media.clear(); active = null; playRevision++; }
    knownFiles.forEach((file, key) => { if (file.provider === provider) knownFiles.delete(key); });
    if (provider === 'baidu') {
      logins.baidu.close();
      await baidu.logout();
      await baiduSession.clearStorageData();
      baiduWeb.connected = false;
      baiduMode = 'web'; data.baiduMode = baiduMode; saveLibrary();
    }
    else {
      logins.quark.close();
      await quarkSession.clearStorageData();
      quark.connected = false;
    }
  });
  register('drive:list', async (provider, dir, page = 1) => {
    if (!Number.isInteger(page) || page < 1) throw new Error('无效的目录页码。');
    const result = await drive(provider).list(dir, page);
    for (const file of result.files) knownFiles.set(keyOf(file), file);
    return result;
  });
  register('library:list', type => (type === 'local' ? data.localFiles : data.history).map(publicFile));
  register('library:clear-history', () => { data.history = []; saveLibrary(); });
  register('local:open', async () => {
    const result = await dialog.showOpenDialog(mainWindow, { title: '打开本地视频', properties: ['openFile', 'multiSelections'], filters: [{ name: '视频', extensions: ['mp4', 'm4v', 'mov', 'webm', 'mkv', 'avi', 'ts', 'm2ts', 'flv', 'wmv'] }] });
    if (result.canceled) return [];
    const files = [];
    for (const file of result.filePaths) {
      if (!VIDEO_EXT.test(file)) continue;
      const info = await stat(file);
      if (!info.isFile()) continue;
      const item = { provider: 'local', id: createHash('sha256').update(file).digest('hex'), name: path.basename(file), size: info.size, modifiedAt: info.mtimeMs, file, isDir: false };
      knownFiles.set(keyOf(item), item);
      data.localFiles = [item, ...data.localFiles.filter(f => f.id !== item.id)].slice(0, 100);
      files.push(publicFile(item));
    }
    saveLibrary();
    return files;
  });
  register('player:prepare', async (provider, id) => {
    const revision = ++playRevision;
    let item;
    let result;
    if (provider === 'sample') {
      item = { provider, id: 'clearplay-sample', name: '光的轨迹 · 1080P 播放示例', isDir: false };
      result = { sources: [{ label: '原画', file: path.join(__dirname, 'assets/sample.webm') }] };
    } else {
      item = knownFiles.get(`${provider}:${id}`) || data.localFiles.find(f => keyOf(f) === `${provider}:${id}`) || data.history.find(f => keyOf(f) === `${provider}:${id}`);
      if (!item || item.isDir) throw new Error('请从视频列表中选择文件。');
      result = provider === 'local' ? { sources: [{ label: '原画', file: item.file }] } : await drive(provider).sources(String(id), item.dir, {
        onPreparing: seconds => {
          if (revision === playRevision) mainWindow.webContents.send('player:preparing', { provider, id, seconds });
        },
      });
    }
    if (revision !== playRevision) throw new Error('已切换到其他视频。');
    media.clear();
    const sources = result.sources.map(source => ({ label: source.label, hls: !!source.hls, url: media.add({ ...source, provider }) }));
    active = item;
    const previous = data.history.find(f => keyOf(f) === keyOf(item));
    if (provider !== 'sample') remember(item);
    return { item: publicFile(item), sources, notice: result.notice, resumeAt: previous?.position || 0 };
  });
  register('player:progress', (provider, id, position, duration) => {
    if (!active || active.provider === 'sample' || keyOf(active) !== `${provider}:${id}`) return;
    if (!Number.isFinite(position) || !Number.isFinite(duration) || position < 0 || duration <= 0 || position > duration + 1) return;
    remember(active, { position: duration - position < 3 ? 0 : position, duration });
  });
  register('player:error', () => media.error);

  const area = screen.getPrimaryDisplay().workAreaSize;
  mainWindow = new BrowserWindow({
    width: Math.min(1480, area.width), height: Math.min(980, area.height), minWidth: 1060, minHeight: 720,
    title: '澄映 · Clearplay', backgroundColor: '#101217', show: false,
    titleBarStyle: 'hidden', titleBarOverlay: { color: '#101217', symbolColor: '#a0a5b0', height: 36 },
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), nodeIntegration: false, contextIsolation: true, sandbox: true },
  });
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', event => event.preventDefault());
  mainWindow.webContents.session.setPermissionRequestHandler((contents, permission, callback) => callback(contents === mainWindow.webContents && permission === 'fullscreen'));
  mainWindow.once('ready-to-show', () => mainWindow.show());
  await mainWindow.loadURL('clearplay://app/index.html');
}

app.on('second-instance', () => { if (mainWindow) { if (mainWindow.isMinimized()) mainWindow.restore(); mainWindow.focus(); } });
app.whenReady().then(setup).catch(() => {
  dialog.showErrorBox('澄映启动失败', '无法初始化播放器。请检查应用目录及 Windows 用户目录的访问权限。');
  app.quit();
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => { Object.values(logins).forEach(login => login.close()); });
