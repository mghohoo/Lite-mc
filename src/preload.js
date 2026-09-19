const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('liteMC', {
  getSettings: () => ipcRenderer.invoke('settings:get'),
  getVersions: value => ipcRenderer.invoke('versions:get', value),
  getLoaders: version => ipcRenderer.invoke('loaders:get', { version }),
  installVersion: value => ipcRenderer.invoke('version:install', value),
  launch: value => ipcRenderer.invoke('launch', value),
  openFolder: () => ipcRenderer.invoke('folder:open'),
  pickJava: () => ipcRenderer.invoke('java:pick'),
  login: () => ipcRenderer.invoke('account:login'),
  getAccount: () => ipcRenderer.invoke('account:get'),
  logout: () => ipcRenderer.invoke('account:logout'),
  uploadSkin: model => ipcRenderer.invoke('skin:upload', model),
  setSkinModel: model => ipcRenderer.invoke('skin:model', model),
  getCurseForgeKeyStatus: () => ipcRenderer.invoke('curseforge-key:status'),
  setCurseForgeKey: key => ipcRenderer.invoke('curseforge-key:set', key),
  searchMods: value => ipcRenderer.invoke('mods:search', value),
  installMod: value => ipcRenderer.invoke('mods:install', value),
  listMods: value => ipcRenderer.invoke('mods:list', value),
  openModsFolder: value => ipcRenderer.invoke('mods:open-folder', value),
  onLog: fn => ipcRenderer.on('log', (_, value) => fn(value)),
  onStatus: fn => ipcRenderer.on('status', (_, value) => fn(value)),
  onInstallProgress: fn => ipcRenderer.on('install-progress', (_, value) => fn(value))
});
