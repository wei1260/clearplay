const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('driveLogin', {
  retry: () => ipcRenderer.invoke('drive-login:retry'),
  close: () => ipcRenderer.invoke('drive-login:close'),
  onState: callback => ipcRenderer.on('drive-login:state', (_event, state) => callback(state)),
});
