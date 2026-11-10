const path = require('node:path');

const LOGIN = {
  baidu: { name: '百度网盘', url: 'https://pan.baidu.com/', domains: ['baidu.com'] },
  quark: { name: '夸克网盘', url: 'https://pan.quark.cn/', domains: ['quark.cn', 'uc.cn'] },
};

function browserUserAgent(value, appName, version) {
  return value.replace(` ${appName}/${version}`, '').replace(/ Electron\/\S+/g, '').replace(/[^\x20-\x7e]/g, '').replace(/\s+/g, ' ').trim();
}

function allowLoginPage(value, provider) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443') && LOGIN[provider]?.domains.some(d => url.hostname === d || url.hostname.endsWith(`.${d}`));
  } catch { return false; }
}

function createDriveLogin(provider, loginSession) {
  const { BrowserWindow, WebContentsView } = require('electron');
  const config = LOGIN[provider];
  let window = null;
  let view = null;
  let timer;
  let probe;
  let revision = 0;
  let status = 'loading';
  function clearTimers() { clearTimeout(timer); clearInterval(probe); }
  function send(next, detail = '') {
    status = next;
    if (window && !window.isDestroyed()) window.webContents.send('drive-login:state', { provider, name: config.name, status, detail });
  }
  function fail(code = 'ERR_FAILED') {
    if (!window || window.isDestroyed()) return;
    revision++;
    clearTimers();
    view.setVisible(false);
    const reason = ({ ERR_INTERNET_DISCONNECTED: '网络未连接', ERR_NAME_NOT_RESOLVED: '域名解析失败', ERR_PROXY_CONNECTION_FAILED: '系统代理连接失败', ERR_CERT_AUTHORITY_INVALID: '网站证书校验失败', ERR_TIMED_OUT: '页面加载超时', ERR_ABORTED: '页面请求被中断', ERR_BLOCKED_BY_CLIENT: '页面请求被拦截' })[code] || '官网页面未能正常加载';
    send('error', `${reason}（${/^ERR_[A-Z_]+$/.test(code) ? code : 'ERR_FAILED'}）。请检查网络后点击重新加载。`);
  }
  function reload() {
    if (!window || window.isDestroyed()) return;
    const attempt = ++revision;
    clearTimers();
    view.setVisible(false);
    view.webContents.stop();
    send('loading');
    let checking = false;
    probe = setInterval(async () => {
      if (checking || attempt !== revision) return;
      checking = true;
      try {
        const rendered = await view.webContents.executeJavaScript('Boolean(document.body && document.body.innerText.trim().length > 40)');
        if (rendered && attempt === revision) {
          clearTimers();
          view.setVisible(true);
          send('ready');
        }
      } catch { /* 页面导航期间执行上下文可能暂不可用。 */ }
      finally { checking = false; }
    }, 500);
    timer = setTimeout(() => { if (attempt === revision) fail('ERR_TIMED_OUT'); }, 20000);
    view.webContents.loadURL(config.url).catch(error => {
      if (attempt === revision && status !== 'ready') fail(error.code);
    });
  }
  function open() {
    if (window && !window.isDestroyed()) { window.show(); window.focus(); if (status !== 'ready') reload(); return; }
    window = new BrowserWindow({
      width: 1060, height: 820, minWidth: 760, minHeight: 600, title: `登录${config.name} · 澄映`, backgroundColor: '#181c23', autoHideMenuBar: true,
      webPreferences: { preload: path.join(__dirname, 'drive-login-preload.cjs'), nodeIntegration: false, contextIsolation: true, sandbox: true },
    });
    view = new WebContentsView({ webPreferences: { session: loginSession, nodeIntegration: false, contextIsolation: true, sandbox: true } });
    view.setVisible(false);
    window.contentView.addChildView(view);
    const resize = () => {
      const [width, height] = window.getContentSize();
      view.setBounds({ x: 0, y: 72, width, height: Math.max(0, height - 72) });
    };
    resize();
    window.on('resize', resize);
    view.webContents.on('will-navigate', (event, url) => { if (!allowLoginPage(url, provider)) event.preventDefault(); });
    view.webContents.on('will-redirect', (event, url) => { if (!allowLoginPage(url, provider)) { event.preventDefault(); setImmediate(() => fail('ERR_BLOCKED_BY_CLIENT')); } });
    view.webContents.setWindowOpenHandler(({ url }) => {
      if (allowLoginPage(url, provider)) view.webContents.loadURL(url).catch(error => fail(error.code));
      return { action: 'deny' };
    });
    view.webContents.on('did-fail-load', (_event, code, description, _url, mainFrame) => { if (mainFrame && code !== -3) setImmediate(() => fail(description)); });
    view.webContents.on('render-process-gone', () => fail('ERR_FAILED'));
    window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    window.webContents.on('will-navigate', event => event.preventDefault());
    window.once('closed', () => {
      revision++;
      clearTimers();
      if (!view.webContents.isDestroyed()) view.webContents.close();
      window = null;
      view = null;
    });
    window.loadURL(`clearplay://app/drive-login.html?provider=${provider}`).then(reload).catch(() => window?.close());
  }
  return {
    open, reload,
    close: () => window?.close(),
    owns: event => window && event.sender === window.webContents && event.senderFrame === window.webContents.mainFrame && new URL(event.senderFrame.url).pathname === '/drive-login.html',
  };
}

module.exports = { createDriveLogin, browserUserAgent, allowLoginPage };
