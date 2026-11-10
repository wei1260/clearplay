const { contextBridge, ipcRenderer } = require('electron');

async function invoke(channel, ...args) {
  const result = await ipcRenderer.invoke(channel, ...args);
  if (!result.ok) throw new Error(result.error);
  return result.data;
}

contextBridge.exposeInMainWorld('clearplay', {
  state: () => invoke('app:state'),
  beginBaidu: (key, secret) => invoke('baidu:begin', key, secret),
  pollBaidu: () => invoke('baidu:poll'),
  cancelBaidu: () => invoke('baidu:cancel'),
  openBaidu: () => invoke('baidu:open'),
  openDocs: () => invoke('app:docs'),
  openPlatform: () => invoke('app:platform'),
  loginBaidu: () => invoke('baidu:login'),
  connectBaidu: () => invoke('baidu:connect'),
  loginQuark: () => invoke('quark:login'),
  connectQuark: () => invoke('quark:connect'),
  logout: provider => invoke('drive:logout', provider),
  list: (provider, dir, page) => invoke('drive:list', provider, dir, page),
  library: type => invoke('library:list', type),
  clearHistory: () => invoke('library:clear-history'),
  openLocal: () => invoke('local:open'),
  prepare: (provider, id) => invoke('player:prepare', provider, id),
  mediaError: () => invoke('player:error'),
  onPreparing: callback => ipcRenderer.on('player:preparing', (_event, info) => callback(info)),
  progress: (provider, id, position, duration) => invoke('player:progress', provider, id, position, duration),
});
