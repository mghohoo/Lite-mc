const { app, BrowserWindow, ipcMain, dialog, safeStorage } = require('electron');
const { Client } = require('minecraft-launcher-core');
const { Auth } = require('msmc');
const Handler = require('minecraft-launcher-core/components/handler');
const Zip = require('adm-zip');
const fs = require('fs');
const path = require('path');
const https = require('https');
const crypto = require('crypto');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');
const { execFile } = require('child_process');
const { promisify } = require('util');
const execFileAsync = promisify(execFile);

let windowRef;
let running = false;
let installing = false;
let onlineAccount = null; // Kept only in memory: never write a bearer token to disk.
let restoreAccountPromise = null;
const launcher = new Client();
const settingsPath = () => path.join(app.getPath('userData'), 'lite-mc.json');
const authSessionPath = () => path.join(app.getPath('userData'), 'lite-mc-auth.bin');
const curseForgeKeyPath = () => path.join(app.getPath('userData'), 'lite-mc-curseforge.bin');
const gameRoot = () => path.join(app.getPath('userData'), 'minecraft');
const versionManifestCachePath = () => path.join(gameRoot(), 'cache', 'lite-mc-version-manifest-v2.json');
const LOADER_NAMES = Object.freeze({ vanilla: '原版', fabric: 'Fabric', forge: 'Forge', liteloader: 'LiteLoader', optifine: 'OptiFine' });
function normalizeLoader(value = 'vanilla') {
  if (!Object.hasOwn(LOADER_NAMES, value)) throw new Error('未知加载器，请重新选择。');
  return value;
}
const instanceRoot = (version, loader) => normalizeLoader(loader) === 'vanilla' ? gameRoot() : path.join(gameRoot(), 'instances', `${loader}-${version}`);
const JAVA_RUNTIME_INDEX = 'https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';
const installMarker = (version, loader = 'vanilla') => path.join(gameRoot(), 'versions', version, normalizeLoader(loader) === 'vanilla' ? '.lite-mc-installed' : `.lite-mc-installed-${loader}`);
const installedLoaders = version => Object.fromEntries(Object.keys(LOADER_NAMES).map(loader => [loader, fs.existsSync(installMarker(version, loader))]));
const originalGetAssets = Handler.prototype.getAssets;
Handler.prototype.getAssets = async function fastInstalledAssets() {
  if (this.options.overrides?.liteMcSkipAssetCheck) {
    this.client.emit('debug', '[Lite-MC]: 已安装版本，跳过重复的全量资源校验。');
    return;
  }
  return originalGetAssets.call(this);
};
function readInstallMetadata(version, loader = 'vanilla') {
  try {
    const value = JSON.parse(fs.readFileSync(installMarker(version, loader), 'utf8'));
    return value && typeof value === 'object' ? value : {};
  } catch { return {}; }
}
function readVersionManifestCache() {
  try {
    const manifest = JSON.parse(fs.readFileSync(versionManifestCachePath(), 'utf8'));
    return manifest && Array.isArray(manifest.versions) && manifest.latest ? manifest : null;
  } catch { return null; }
}
function saveVersionManifestCache(manifest) {
  fs.mkdirSync(path.dirname(versionManifestCachePath()), { recursive: true });
  fs.writeFileSync(versionManifestCachePath(), JSON.stringify(manifest));
}
function localInstalledReleases() {
  const versionsDir = path.join(gameRoot(), 'versions');
  try {
    return fs.readdirSync(versionsDir, { withFileTypes: true })
      .filter(entry => entry.isDirectory() && /^[0-9][A-Za-z0-9._-]{0,39}$/.test(entry.name))
      .filter(entry => Object.values(installedLoaders(entry.name)).some(Boolean))
      .map(entry => ({ id: entry.name, type: 'release', releaseTime: '' }));
  } catch { return []; }
}
function offlineAuthorization(username) {
  // Vanilla's offline UUID is deterministic.  No Mojang/Microsoft endpoint is
  // involved: this is deliberately a fully local path for offline play.
  const bytes = crypto.createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const uuid = bytes.toString('hex');
  return { access_token: '0', client_token: '0', uuid, name: username, user_properties: '{}', meta: { type: 'legacy' } };
}

function readSettings() {
  try { return JSON.parse(fs.readFileSync(settingsPath(), 'utf8')); }
  catch { return { username: 'Player', version: 'latest-release', loader: 'vanilla', memory: 4096, javaPath: 'java', accountMode: 'offline', language: 'zh_cn' }; }
}
function saveSettings(value) { fs.writeFileSync(settingsPath(), JSON.stringify(value, null, 2)); }
function saveRefreshToken(token) {
  if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows 安全存储当前不可用，无法安全保持登录状态。');
  fs.writeFileSync(authSessionPath(), safeStorage.encryptString(token));
}
function loadRefreshToken() {
  if (!safeStorage.isEncryptionAvailable() || !fs.existsSync(authSessionPath())) return null;
  try { return safeStorage.decryptString(fs.readFileSync(authSessionPath())); } catch { return null; }
}
function clearRefreshToken() { if (fs.existsSync(authSessionPath())) fs.unlinkSync(authSessionPath()); }
function saveCurseForgeKey(key) {
  if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows 安全存储当前不可用，无法保存 CurseForge API Key。');
  fs.writeFileSync(curseForgeKeyPath(), safeStorage.encryptString(key));
}
function loadCurseForgeKey() {
  if (!safeStorage.isEncryptionAvailable() || !fs.existsSync(curseForgeKeyPath())) return '';
  try { return safeStorage.decryptString(fs.readFileSync(curseForgeKeyPath())); } catch { return ''; }
}
function applyGameLanguage(language, directory = gameRoot()) {
  const optionsPath = path.join(directory, 'options.txt');
  let content = '';
  try { content = fs.readFileSync(optionsPath, 'utf8'); } catch {}
  const lines = content ? content.replace(/\r/g, '').split('\n').filter(Boolean) : [];
  const index = lines.findIndex(line => line.startsWith('lang:'));
  if (index >= 0) lines[index] = `lang:${language}`;
  else lines.push(`lang:${language}`);
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(optionsPath, `${lines.join('\n')}\n`, 'utf8');
}
function send(channel, value) { windowRef?.webContents.send(channel, value); }
function redactLog(value) {
  return String(value).replace(/(--accessToken\s+)\S+/gi, '$1<已隐藏>').replace(/(--clientToken\s+)\S+/gi, '$1<已隐藏>');
}
function getJson(url, headers = {}) {
  return new Promise((resolve, reject) => https.get(url, { headers: { 'User-Agent': 'Lite-MC/1.0 (launcher)', ...headers } }, res => {
    if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) return resolve(getJson(new URL(res.headers.location, url).href, headers));
    let body = ''; res.on('data', c => body += c); res.on('end', () => {
      if (res.statusCode < 200 || res.statusCode >= 300) return reject(new Error(`接口请求失败（${res.statusCode}）`));
      try { resolve(JSON.parse(body)); } catch (e) { reject(new Error(`接口返回了无效数据：${e.message}`)); }
    });
  }).on('error', reject));
}
async function javaMajor(javaPath) {
  try {
    const { stdout, stderr } = await execFileAsync(javaPath, ['-version'], { windowsHide: true, timeout: 10000 });
    const match = `${stdout}\n${stderr}`.match(/version\s+"(\d+)(?:\.(\d+))?/i);
    if (!match) return 0;
    return Number(match[1]) === 1 ? Number(match[2]) : Number(match[1]);
  } catch { return 0; }
}
async function sha1(filePath) {
  const hash = crypto.createHash('sha1');
  await pipeline(fs.createReadStream(filePath), hash);
  return hash.digest('hex');
}
async function fetchTrusted(value, hosts, redirects = 0) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || (hosts && !hosts.includes(url.hostname))) throw new Error('下载地址未通过 HTTPS / 来源安全检查。');
  const response = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(120000), headers: { 'User-Agent': 'Lite-MC/1.1 (desktop launcher)' } });
  if (response.status >= 300 && response.status < 400 && response.headers.get('location')) {
    await response.body?.cancel();
    if (redirects >= 5) throw new Error('下载地址重定向次数过多。');
    return fetchTrusted(new URL(response.headers.get('location'), url).href, hosts, redirects + 1);
  }
  return response;
}
async function downloadRuntimeFile(url, destination, expectedSha1, allowedHosts) {
  if (expectedSha1 && !/^[a-f0-9]{40}$/i.test(expectedSha1)) throw new Error('下载文件的 SHA-1 元数据无效。');
  expectedSha1 = expectedSha1?.toLowerCase();
  if (fs.existsSync(destination) && fs.statSync(destination).isFile() && fs.statSync(destination).size > 0 && (!expectedSha1 || await sha1(destination) === expectedSha1)) return;
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const response = await fetchTrusted(url, allowedHosts);
  if (!response.ok || !response.body) throw new Error(`Java 运行时文件下载失败（${response.status}）`);
  const temporary = `${destination}.download`;
  try {
    await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(temporary));
    if (!fs.statSync(temporary).size || (expectedSha1 && await sha1(temporary) !== expectedSha1)) throw new Error('下载文件校验失败，请重试。');
    fs.renameSync(temporary, destination);
  } finally {
    if (fs.existsSync(temporary)) fs.unlinkSync(temporary);
  }
}
async function ensureOfficialJava(versionId, configuredJava) {
  const versionJsonPath = path.join(gameRoot(), 'versions', versionId, `${versionId}.json`);
  const versionJson = JSON.parse(fs.readFileSync(versionJsonPath, 'utf8'));
  const required = Number(versionJson.javaVersion?.majorVersion || 8);
  const configuredMajor = await javaMajor(configuredJava);
  if (configuredMajor >= required) return configuredJava;
  const component = versionJson.javaVersion?.component;
  if (!component) throw new Error(`该版本需要 Java ${required}，当前 Java 为 ${configuredMajor || '未知版本'}。请在设置中选择兼容的 java.exe。`);
  const runtimeRoot = path.join(gameRoot(), 'runtime', component, 'windows-x64', component);
  const javaExe = path.join(runtimeRoot, 'bin', 'java.exe');
  if (await javaMajor(javaExe) >= required) return javaExe;
  send('status', { running: true, text: `正在下载 Mojang 官方 Java ${required} 运行时…` });
  const runtimes = await getJson(JAVA_RUNTIME_INDEX);
  const runtime = runtimes['windows-x64']?.[component]?.[0];
  if (!runtime?.manifest?.url) throw new Error(`Mojang 未提供 ${component} 的 Windows x64 运行时。`);
  const manifest = await getJson(runtime.manifest.url);
  const files = Object.entries(manifest.files).filter(([, entry]) => entry.type === 'file' && entry.downloads?.raw);
  let completed = 0;
  let cursor = 0;
  const workers = Array.from({ length: 12 }, async () => {
    while (cursor < files.length) {
      const index = cursor++;
      const [relative, entry] = files[index];
      const destination = path.resolve(runtimeRoot, relative);
      if (!destination.startsWith(path.resolve(runtimeRoot) + path.sep)) throw new Error('Java 运行时清单包含无效路径。');
      await downloadRuntimeFile(entry.downloads.raw.url, destination, entry.downloads.raw.sha1);
      completed++;
      send('install-progress', { task: completed, total: files.length });
    }
  });
  await Promise.all(workers);
  const installedMajor = await javaMajor(javaExe);
  if (installedMajor < required) throw new Error(`Java 运行时安装不完整：需要 Java ${required}，检测到 ${installedMajor || '未知'}。`);
  send('log', { type: 'info', message: `已安装 Mojang 官方 Java ${installedMajor}：${javaExe}` });
  return javaExe;
}

function createWindow() {
  windowRef = new BrowserWindow({ width: 1040, height: 700, minWidth: 880, minHeight: 600, backgroundColor: '#111827', title: 'Lite-MC', webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false } });
  windowRef.setMenuBarVisibility(false);
  windowRef.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

app.whenReady().then(() => { createWindow(); app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) createWindow(); }); });
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });

ipcMain.handle('settings:get', () => ({ ...readSettings(), gameRoot: gameRoot() }));
ipcMain.handle('versions:get', async (_, input = {}) => {
  // Offline starts must remain offline.  Once a manifest was fetched, it is
  // enough to populate the selector without putting startup on the network.
  let manifest = input.offline ? readVersionManifestCache() : null;
  if (input.offline && !manifest) {
    const versions = localInstalledReleases();
    const requested = String(input.version || '');
    const latest = versions.find(version => version.id === requested)?.id || versions[0]?.id || requested || 'latest-release';
    return { latest: { release: latest }, versions: versions.map(v => ({ ...v, installed: installedLoaders(v.id) })) };
  }
  if (!manifest) {
    manifest = await getJson('https://launchermeta.mojang.com/mc/game/version_manifest_v2.json');
    saveVersionManifestCache(manifest);
  }
  return { latest: manifest.latest, versions: manifest.versions.filter(v => v.type === 'release').slice(0, 80).map(v => ({ id: v.id, type: v.type, releaseTime: v.releaseTime, installed: installedLoaders(v.id) })) };
});
async function validatedRelease(versionId, alwaysCheckOfficial = false) {
  if (!alwaysCheckOfficial && /^[A-Za-z0-9._-]{1,40}$/.test(String(versionId || '')) && fs.existsSync(installMarker(versionId))) return { id: versionId };
  const manifest = await getJson('https://launchermeta.mojang.com/mc/game/version_manifest_v2.json');
  const id = versionId || manifest.latest.release;
  const release = manifest.versions.find(version => version.type === 'release' && version.id === id);
  if (!release) throw new Error('只能安装 Mojang 官方版本列表中的正式版。');
  return release;
}
async function installVanilla(release) {
  if (fs.existsSync(installMarker(release.id))) return readInstallMetadata(release.id);
  const root = gameRoot();
  const directory = path.join(root, 'versions', release.id);
  fs.mkdirSync(directory, { recursive: true });
  const worker = new Client();
  worker.options = {
    root, directory, cache: path.join(root, 'cache'), timeout: 60000,
    version: { number: release.id, type: 'release' },
    overrides: {
      maxSockets: 16,
      url: { meta: 'https://launchermeta.mojang.com', resource: 'https://resources.download.minecraft.net', defaultRepoForge: 'https://libraries.minecraft.net/', fallbackMaven: 'https://search.maven.org/remotecontent?filepath=' }
    }
  };
  for (const event of ['debug', 'download', 'download-status', 'progress']) worker.on(event, value => send(event === 'download-status' || event === 'progress' ? 'install-progress' : 'log', event === 'debug' || event === 'download' ? { type: event, message: String(value) } : value));
  const handler = new Handler(worker);
  await handler.getVersion();
  await handler.getNatives();
  const jar = path.join(directory, `${release.id}.jar`);
  if (!fs.existsSync(jar)) await handler.getJar();
  const classes = await handler.getClasses();
  await handler.getAssets();
  const metadata = { completedAt: new Date().toISOString(), loader: 'vanilla', classes };
  fs.writeFileSync(installMarker(release.id), JSON.stringify(metadata));
  return metadata;
}
async function installFabric(release) {
  const loaders = await getJson(`https://meta.fabricmc.net/v2/versions/loader/${encodeURIComponent(release.id)}`);
  const selected = loaders.find(item => item.loader?.stable) || loaders[0];
  if (!selected?.loader?.version) throw new Error(`Fabric 暂不支持 Minecraft ${release.id}。`);
  const profile = await getJson(`https://meta.fabricmc.net/v2/versions/loader/${encodeURIComponent(release.id)}/${encodeURIComponent(selected.loader.version)}/profile/json`);
  validateCustomProfile(profile, release.id, 'Fabric');
  const customDirectory = path.join(gameRoot(), 'versions', profile.id);
  fs.mkdirSync(customDirectory, { recursive: true });
  fs.writeFileSync(path.join(customDirectory, `${profile.id}.json`), JSON.stringify(profile, null, 2));
  const vanillaJson = JSON.parse(fs.readFileSync(path.join(gameRoot(), 'versions', release.id, `${release.id}.json`), 'utf8'));
  const worker = new Client();
  worker.options = {
    root: gameRoot(), directory: path.join(gameRoot(), 'versions', release.id), timeout: 60000,
    version: { number: release.id, type: 'release', custom: profile.id },
    overrides: { maxSockets: 16 }
  };
  for (const event of ['debug', 'download', 'download-status', 'progress']) worker.on(event, value => send(event === 'download-status' || event === 'progress' ? 'install-progress' : 'log', event === 'debug' || event === 'download' ? { type: event, message: String(value) } : value));
  const handler = new Handler(worker);
  handler.version = vanillaJson;
  const classes = handler.cleanUp(await handler.getClasses(profile));
  verifyInstalledClasses(classes);
  const fabricApi = await installFabricApi(release.id);
  const metadata = { completedAt: new Date().toISOString(), loader: 'fabric', loaderVersion: selected.loader.version, customId: profile.id, classes, fabricApi };
  fs.writeFileSync(installMarker(release.id, 'fabric'), JSON.stringify(metadata));
  return metadata;
}

function validateCustomProfile(profile, gameVersion, label) {
  if (!profile || !/^[A-Za-z0-9][A-Za-z0-9._+-]{0,119}$/.test(profile.id || '') || profile.inheritsFrom !== gameVersion || !Array.isArray(profile.libraries) || !/^[A-Za-z_$][A-Za-z0-9_.$]+$/.test(profile.mainClass || '')) throw new Error(`${label} 官方版本配置不完整或继承版本不匹配。`);
  for (const library of profile.libraries) {
    if (!/^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+(?::[A-Za-z0-9_.-]+)?$/.test(library?.name || '')) throw new Error(`${label} 依赖坐标无效。`);
    const relative = library.downloads?.artifact?.path;
    if (relative && (relative.includes('\\') || relative.split('/').includes('..') || path.isAbsolute(relative) || !path.resolve(gameRoot(), 'libraries', relative).startsWith(path.resolve(gameRoot(), 'libraries') + path.sep))) throw new Error(`${label} 依赖包含不安全的路径。`);
    for (const value of [library.url, library.downloads?.artifact?.url].filter(Boolean)) {
      const url = new URL(value);
      if (url.protocol !== 'https:' || url.username || url.password) throw new Error(`${label} 依赖地址必须使用 HTTPS。`);
    }
  }
}
function verifyInstalledClasses(classes) {
  if (!Array.isArray(classes) || !classes.length) throw new Error('加载器没有生成可用的依赖列表。');
  for (const filename of classes) {
    if (typeof filename !== 'string' || !path.resolve(filename).startsWith(path.resolve(gameRoot(), 'libraries') + path.sep) || !fs.existsSync(filename) || !fs.statSync(filename).isFile() || !fs.statSync(filename).size) throw new Error(`加载器依赖未安装完整：${path.basename(String(filename))}`);
  }
}
function assertAutomaticLoader(loader, version) {
  if (loader === 'liteloader') throw new Error('LiteLoader 自动安装暂不可用：尚未取得可验证的官方版本元数据，不会改装成原版。请改选 Fabric / Forge；旧 LiteLoader 版本需要官方安装器手动处理。');
  if (loader === 'optifine') throw new Error('OptiFine 自动安装暂不可用：官方未提供此启动器可验证的安装元数据。请从 https://optifine.net/downloads 获取官方安装器；此操作不会被标记为已安装。');
  if (loader === 'forge' && (!/^1\.\d+(?:\.\d+)?$/.test(version) || Number(version.split('.')[1]) < 13)) throw new Error('当前 Forge 自动安装支持 Minecraft 1.13 及之后的 1.x 正式版；旧版 / 新版本号需要单独适配安装器，暂不标记为可安装。');
}
function forgeJvmArgs(profile) {
  const substitutions = { library_directory: path.join(gameRoot(), 'libraries'), classpath_separator: path.delimiter, version_name: profile.id };
  const result = [];
  for (const entry of profile.arguments?.jvm || []) {
    let values = entry;
    if (entry && typeof entry === 'object') {
      let allowed = !Array.isArray(entry.rules) || !entry.rules.length;
      for (const rule of entry.rules || []) {
        const os = rule.os?.name;
        if ((!os || os === (process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'osx' : 'linux')) && !rule.features) allowed = rule.action === 'allow';
      }
      if (!allowed) continue;
      values = entry.value;
    }
    for (const value of Array.isArray(values) ? values : [values]) {
      if (typeof value !== 'string' || /[\x00\r\n]/.test(value)) throw new Error('Forge JVM 参数格式不受支持。');
      const resolved = value.replace(/\$\{([^}]+)\}/g, (_, key) => {
        if (!Object.hasOwn(substitutions, key)) throw new Error(`Forge 使用了尚未支持的 JVM 参数：${key}`);
        return substitutions[key];
      });
      result.push(resolved);
    }
  }
  return result;
}
async function installForge(release, javaPath) {
  const promotions = await getJson('https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json');
  const loaderVersion = promotions.promos?.[`${release.id}-recommended`] || promotions.promos?.[`${release.id}-latest`];
  if (!/^[0-9][A-Za-z0-9._-]{0,79}$/.test(loaderVersion || '')) throw new Error(`Forge 官方尚未列出 Minecraft ${release.id} 的可用版本。`);
  const coordinate = `${release.id}-${loaderVersion}`;
  const filename = `forge-${coordinate}-installer.jar`;
  const url = `https://maven.minecraftforge.net/net/minecraftforge/forge/${coordinate}/${filename}`;
  const checksumResponse = await fetchTrusted(`${url}.sha1`, ['maven.minecraftforge.net']);
  if (!checksumResponse.ok) throw new Error(`无法取得 Forge 官方安装器校验值（${checksumResponse.status}）。`);
  const checksum = (await checksumResponse.text()).trim();
  if (!/^[a-f0-9]{40}$/i.test(checksum)) throw new Error('Forge 官方安装器 SHA-1 无效，已停止安装。');
  const installer = path.join(gameRoot(), 'cache', 'installers', filename);
  await downloadRuntimeFile(url, installer, checksum, ['maven.minecraftforge.net']);
  const archive = new Zip(installer);
  const profile = JSON.parse(archive.readAsText('version.json'));
  validateCustomProfile(profile, release.id, 'Forge');
  // Refuse incompatible JVM metadata before running the official installer.
  const jvmArgs = forgeJvmArgs(profile);
  const launcherProfiles = path.join(gameRoot(), 'launcher_profiles.json');
  if (!fs.existsSync(launcherProfiles)) fs.writeFileSync(launcherProfiles, JSON.stringify({ profiles: {} }), { flag: 'wx' });
  send('status', { running: true, text: `Forge ${loaderVersion}：正在运行官方安装器并处理游戏补丁…` });
  try {
    const { stdout, stderr } = await execFileAsync(javaPath, ['-Djava.awt.headless=true', '-jar', installer, '--installClient', gameRoot()], { cwd: gameRoot(), windowsHide: true, timeout: 20 * 60 * 1000, maxBuffer: 16 * 1024 * 1024 });
    send('log', { type: 'info', message: redactLog(`${stdout}\n${stderr}`).slice(-12000) });
  } catch (error) {
    send('log', { type: 'error', message: redactLog(`${error.stdout || ''}\n${error.stderr || ''}`).slice(-12000) });
    throw new Error('Forge 官方安装器未成功完成，请查看日志并重试；未标记为已安装。');
  }
  const profilePath = path.join(gameRoot(), 'versions', profile.id, `${profile.id}.json`);
  const installedProfile = JSON.parse(fs.readFileSync(profilePath, 'utf8'));
  validateCustomProfile(installedProfile, release.id, 'Forge');
  if (installedProfile.id !== profile.id || installedProfile.mainClass !== profile.mainClass) throw new Error('Forge 安装结果与官方安装器元数据不符。');
  const vanilla = JSON.parse(fs.readFileSync(path.join(gameRoot(), 'versions', release.id, `${release.id}.json`), 'utf8'));
  const customId = `lite-forge-${coordinate}`;
  const mergedProfile = { ...installedProfile, id: customId, arguments: { ...installedProfile.arguments, game: [...(vanilla.arguments?.game || []), ...(installedProfile.arguments?.game || [])] } };
  if (!mergedProfile.arguments.game.length) throw new Error('当前 Forge 版本的启动参数尚未适配。');
  const worker = new Client();
  worker.options = { root: gameRoot(), directory: path.join(gameRoot(), 'versions', release.id), timeout: 60000, version: { number: release.id, type: 'release' }, overrides: { maxSockets: 16 } };
  const handler = new Handler(worker); handler.version = vanilla;
  const classes = handler.cleanUp(await handler.getClasses(installedProfile));
  verifyInstalledClasses(classes);
  const customDirectory = path.join(gameRoot(), 'versions', customId);
  fs.mkdirSync(customDirectory, { recursive: true });
  fs.writeFileSync(path.join(customDirectory, `${customId}.json`), JSON.stringify(mergedProfile, null, 2));
  fs.mkdirSync(path.join(instanceRoot(release.id, 'forge'), 'mods'), { recursive: true });
  const metadata = { completedAt: new Date().toISOString(), loader: 'forge', loaderVersion, customId, classes, jvmArgs, installerSha1: checksum };
  fs.writeFileSync(installMarker(release.id, 'forge'), JSON.stringify(metadata));
  return metadata;
}

async function installFabricApi(version) {
  const files = await resolveModrinthFiles('fabric-api', version, 'fabric');
  const installed = [];
  for (const file of files) {
    const name = modFileName(file.name);
    if (!file.sha1) throw new Error('Fabric API 文件缺少官方校验值，已停止安装。');
    send('status', { running: true, text: `正在安装 Fabric API：${name}…` });
    await downloadRuntimeFile(file.url, path.join(instanceRoot(version, 'fabric'), 'mods', name), file.sha1, ['cdn.modrinth.com']);
    installed.push({ name, sha1: file.sha1, projectId: file.projectId, versionId: file.versionId });
  }
  if (!installed.length) throw new Error(`Fabric API 尚未提供 Minecraft ${version} 的兼容版本。`);
  return { source: 'modrinth', files: installed };
}
ipcMain.handle('version:install', async (_, input) => {
  if (installing || running) throw new Error('当前已有安装或游戏任务正在进行。');
  const versionId = typeof input === 'string' ? input : input?.version;
  const loader = normalizeLoader(typeof input === 'string' ? 'vanilla' : input?.loader);
  assertAutomaticLoader(loader, String(versionId || ''));
  installing = true;
  let release;
  try {
    release = await validatedRelease(versionId, true);
    assertAutomaticLoader(loader, release.id);
    send('status', { running: true, text: `正在下载 ${release.id} · ${LOADER_NAMES[loader]}…` });
    await installVanilla(release);
    const installedJava = await ensureOfficialJava(release.id, readSettings().javaPath || 'java');
    const previous = readInstallMetadata(release.id, loader);
    if (loader === 'fabric') {
      if (!previous.customId || !Array.isArray(previous.classes)) await installFabric(release);
      else {
        verifyInstalledClasses(previous.classes);
        let apiComplete = Array.isArray(previous.fabricApi?.files) && previous.fabricApi.files.length > 0;
        for (const file of previous.fabricApi?.files || []) {
          const candidate = path.join(instanceRoot(release.id, 'fabric'), 'mods', modFileName(file.name));
          if (!/^[a-f0-9]{40}$/i.test(file.sha1 || '') || !fs.existsSync(candidate) || await sha1(candidate) !== file.sha1.toLowerCase()) apiComplete = false;
        }
        if (!apiComplete) {
          const fabricApi = await installFabricApi(release.id);
          fs.writeFileSync(installMarker(release.id, loader), JSON.stringify({ ...previous, fabricApi }));
        }
      }
    } else if (loader === 'forge') {
      if (!previous.customId || !Array.isArray(previous.classes) || !Array.isArray(previous.jvmArgs)) await installForge(release, installedJava);
      else verifyInstalledClasses(previous.classes);
    }
    const saved = readSettings();
    saveSettings({ ...saved, version: release.id, loader, javaPath: installedJava });
    send('status', { running: false, text: `${release.id} · ${LOADER_NAMES[loader]}${loader === 'fabric' ? ' + Fabric API' : ''} 下载完成，可以启动` });
    return { version: release.id, loader, installed: true, javaPath: installedJava };
  } catch (error) {
    send('status', { running: false, text: `${release?.id || versionId || ''} 下载失败` });
    throw error;
  } finally { installing = false; }
});
function validateModContext(input) {
  const version = String(input?.version || '');
  if (!/^[A-Za-z0-9._-]{1,40}$/.test(version)) throw new Error('游戏版本无效。');
  const loader = normalizeLoader(input?.loader);
  if (!['fabric', 'forge'].includes(loader)) throw new Error('请先选择 Fabric 或 Forge；原版无法加载这些 Mod。');
  return { version, loader };
}
function modFileName(value) {
  const name = path.basename(String(value || '')).replace(/[^A-Za-z0-9._+()\- ]/g, '_');
  if (!name.toLowerCase().endsWith('.jar')) throw new Error('下载目标不是有效的 Mod JAR 文件。');
  return name;
}
function assertModDownloadUrl(value, source) {
  const url = new URL(value);
  const allowed = source === 'modrinth'
    ? url.hostname === 'cdn.modrinth.com'
    : /(^|\.)(forgecdn\.net|curseforge\.com)$/.test(url.hostname);
  if (url.protocol !== 'https:' || !allowed) throw new Error('Mod 下载地址未通过安全检查。');
  return url.href;
}
async function resolveModrinthFiles(projectId, version, loader, versionId = '', visited = new Set()) {
  const visitKey = versionId || projectId;
  if (!visitKey || !/^[A-Za-z0-9_-]{1,80}$/.test(String(visitKey))) throw new Error('Modrinth 依赖 ID 无效。');
  if (visited.has(visitKey)) return [];
  if (visited.size >= 128) throw new Error('Mod 依赖数量异常，已停止安装。');
  visited.add(visitKey);
  let candidate;
  if (versionId) candidate = await getJson(`https://api.modrinth.com/v2/version/${encodeURIComponent(versionId)}`);
  else {
    const url = new URL(`https://api.modrinth.com/v2/project/${encodeURIComponent(projectId)}/version`);
    url.searchParams.set('loaders', JSON.stringify([loader]));
    url.searchParams.set('game_versions', JSON.stringify([version]));
    url.searchParams.set('include_changelog', 'false');
    const result = await getJson(url.href);
    candidate = (Array.isArray(result) ? result : []).filter(item => item.loaders?.includes(loader) && item.game_versions?.includes(version)).sort((a, b) => Number(b.version_type === 'release') - Number(a.version_type === 'release') || new Date(b.date_published) - new Date(a.date_published))[0];
  }
  if (!candidate?.loaders?.includes(loader) || !candidate.game_versions?.includes(version)) throw new Error(`Mod ${projectId || versionId} 没有兼容 Minecraft ${version} / ${LOADER_NAMES[loader]} 的版本。`);
  const selectedFile = candidate.files?.find(item => item.primary) || candidate.files?.[0];
  if (!selectedFile || !/^[a-f0-9]{40}$/i.test(selectedFile.hashes?.sha1 || '')) throw new Error('Modrinth 文件缺少有效校验值，已停止安装。');
  const dependencies = [];
  for (const dependency of candidate.dependencies || []) {
    if (dependency.dependency_type === 'required') dependencies.push(...await resolveModrinthFiles(dependency.project_id, version, loader, dependency.version_id || '', visited));
  }
  return [...dependencies, { url: assertModDownloadUrl(selectedFile.url, 'modrinth'), name: modFileName(selectedFile.filename), sha1: selectedFile.hashes.sha1, projectId: candidate.project_id, versionId: candidate.id }];
}
ipcMain.handle('curseforge-key:status', () => ({ configured: Boolean(loadCurseForgeKey()) }));
ipcMain.handle('curseforge-key:set', (_, value) => {
  const key = String(value || '').trim();
  if (key.length < 16 || key.length > 256 || /\s/.test(key)) throw new Error('CurseForge API Key 格式不正确。');
  saveCurseForgeKey(key);
  return { configured: true };
});
ipcMain.handle('mods:search', async (_, input) => {
  const { version, loader } = validateModContext(input);
  const query = String(input?.query || '').trim().slice(0, 80);
  if (!query) throw new Error('请输入 Mod 名称。');
  if (input?.source === 'curseforge') {
    const apiKey = loadCurseForgeKey();
    if (!apiKey) throw new Error('请先填写并保存 CurseForge API Key。');
    const url = new URL('https://api.curseforge.com/v1/mods/search');
    url.search = new URLSearchParams({ gameId: '432', classId: '6', gameVersion: version, modLoaderType: loader === 'forge' ? '1' : '4', searchFilter: query, sortField: '6', sortOrder: 'desc', pageSize: '12' });
    const result = await getJson(url.href, { 'x-api-key': apiKey });
    return (result.data || []).map(item => ({ source: 'curseforge', id: item.id, title: item.name, description: item.summary, iconUrl: item.logo?.thumbnailUrl || '', downloads: item.downloadCount || 0, author: item.authors?.[0]?.name || '', pageUrl: item.links?.websiteUrl || '' }));
  }
  const facets = JSON.stringify([['project_type:mod'], [`versions:${version}`], [`categories:${loader}`]]);
  const url = new URL('https://api.modrinth.com/v2/search');
  url.search = new URLSearchParams({ query, limit: '12', index: 'downloads', facets });
  const result = await getJson(url.href);
  return (result.hits || []).map(item => ({ source: 'modrinth', id: item.project_id, title: item.title, description: item.description, iconUrl: item.icon_url || '', downloads: item.downloads || 0, author: item.author || '', pageUrl: `https://modrinth.com/mod/${item.slug}` }));
});
ipcMain.handle('mods:install', async (_, input) => {
  if (installing || running) throw new Error('当前已有安装或游戏任务正在进行。');
  const { version, loader } = validateModContext(input);
  if (!fs.existsSync(installMarker(version, loader))) throw new Error(`请先安装当前版本的 ${LOADER_NAMES[loader]}。`);
  const source = input?.source === 'curseforge' ? 'curseforge' : 'modrinth';
  const id = String(input?.id || '');
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(id)) throw new Error('Mod ID 无效。');
  installing = true;
  send('status', { running: true, text: '正在获取兼容的 Mod 文件…' });
  try {
    let files;
    if (source === 'curseforge') {
      const apiKey = loadCurseForgeKey();
      if (!apiKey) throw new Error('请先配置 CurseForge API Key。');
      const visited = new Set();
      const resolveCurseForge = async modId => {
        if (visited.has(String(modId))) return [];
        visited.add(String(modId));
        const url = new URL(`https://api.curseforge.com/v1/mods/${modId}/files`);
        url.search = new URLSearchParams({ gameVersion: version, modLoaderType: loader === 'forge' ? '1' : '4', pageSize: '50' });
        const result = await getJson(url.href, { 'x-api-key': apiKey });
        const candidate = (result.data || []).filter(item => item.isAvailable !== false).sort((a, b) => new Date(b.fileDate) - new Date(a.fileDate))[0];
        if (!candidate) throw new Error(`Mod ${modId} 没有兼容 Minecraft ${version} / ${LOADER_NAMES[loader]} 的文件。`);
        const dependencies = [];
        for (const dependency of candidate.dependencies || []) if (dependency.relationType === 3) dependencies.push(...await resolveCurseForge(dependency.modId));
        let downloadUrl = candidate.downloadUrl;
        if (!downloadUrl) {
          try { downloadUrl = (await getJson(`https://api.curseforge.com/v1/mods/${modId}/files/${candidate.id}/download-url`, { 'x-api-key': apiKey })).data; } catch {}
        }
        if (!downloadUrl) throw new Error(`Mod ${modId} 的作者不允许第三方下载，请从 CurseForge 项目页手动下载。`);
        return [...dependencies, { url: assertModDownloadUrl(downloadUrl, source), name: candidate.fileName, sha1: candidate.hashes?.find(hash => hash.algo === 1)?.value }];
      };
      files = await resolveCurseForge(id);
    } else {
      files = await resolveModrinthFiles(id, version, loader);
    }
    const installed = [];
    for (let index = 0; index < files.length; index++) {
      const file = files[index];
      const name = modFileName(file.name);
      const destination = path.join(instanceRoot(version, loader), 'mods', name);
      send('status', { running: true, text: `正在下载 ${name}（${index + 1}/${files.length}）…` });
      await downloadRuntimeFile(file.url, destination, file.sha1, source === 'modrinth' ? ['cdn.modrinth.com'] : ['edge.forgecdn.net', 'mediafilez.forgecdn.net', 'media.forgecdn.net']);
      installed.push(name);
    }
    const name = installed[installed.length - 1];
    send('status', { running: false, text: `${name} 与 ${Math.max(0, installed.length - 1)} 个必需依赖已安装` });
    return { name, files: installed, path: path.join(instanceRoot(version, loader), 'mods', name) };
  } catch (error) {
    send('status', { running: false, text: 'Mod 安装失败' });
    throw error;
  } finally { installing = false; }
});
ipcMain.handle('mods:list', (_, input) => {
  const { version, loader } = validateModContext(input);
  const directory = path.join(instanceRoot(version, loader), 'mods');
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory).filter(name => name.toLowerCase().endsWith('.jar')).sort();
});
ipcMain.handle('mods:open-folder', async (_, input) => {
  const version = String(input?.version || '');
  if (!/^[A-Za-z0-9._-]{1,40}$/.test(version)) throw new Error('请先选择一个有效的游戏版本。');
  const loader = normalizeLoader(input?.loader || 'fabric');
  if (!['fabric', 'forge'].includes(loader)) throw new Error('请先选择 Fabric 或 Forge 的 Mod 目录。');
  const directory = path.join(instanceRoot(version, loader), 'mods');
  fs.mkdirSync(directory, { recursive: true });
  const error = await require('electron').shell.openPath(directory);
  if (error) throw new Error(`Windows 无法打开 Mods 文件夹：${error}`);
  return { path: directory };
});
ipcMain.handle('folder:open', () => require('electron').shell.openPath(gameRoot()));
ipcMain.handle('java:pick', async () => {
  const result = await dialog.showOpenDialog(windowRef, { title: '选择 Java 可执行文件', properties: ['openFile'], filters: [{ name: 'Java', extensions: ['exe', 'bat', 'cmd'] }] });
  return result.canceled ? null : result.filePaths[0];
});
function activeSkin(profile) { return profile?.skins?.find(skin => skin.state === 'ACTIVE') || profile?.skins?.[0] || null; }
function publicAccount() {
  if (!onlineAccount) return null;
  const skin = activeSkin(onlineAccount.profile);
  return { name: onlineAccount.profile.name, id: onlineAccount.profile.id, skinUrl: skin?.url || null, variant: skin?.variant || 'CLASSIC' };
}
async function minecraftProfile(token) {
  const response = await fetch('https://api.minecraftservices.com/minecraft/profile', { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) throw new Error(`无法读取 Minecraft 档案（${response.status}）。请重新登录。`);
  return response.json();
}
async function restoreOnlineAccount() {
  const refreshToken = loadRefreshToken();
  if (!refreshToken) return null;
  try {
    const auth = new Auth('none');
    const xbox = await auth.refresh(refreshToken);
    const minecraft = await xbox.getMinecraft();
    onlineAccount = { authorization: minecraft.mclc(), token: minecraft.mcToken, profile: await minecraftProfile(minecraft.mcToken), refreshToken: xbox.save() };
    saveRefreshToken(onlineAccount.refreshToken);
    return publicAccount();
  } catch (error) {
    clearRefreshToken();
    send('log', { type: 'warn', message: '微软登录会话已过期，请重新登录。' });
    return null;
  }
}
ipcMain.handle('account:get', async () => {
  if (!onlineAccount && !restoreAccountPromise) restoreAccountPromise = restoreOnlineAccount();
  if (restoreAccountPromise) await restoreAccountPromise;
  return publicAccount();
});
ipcMain.handle('account:login', async () => {
  send('status', { running: false, text: '正在打开微软登录窗口…' });
  const auth = new Auth('select_account');
  const xbox = await auth.launch('electron', { width: 520, height: 700, title: '登录 Microsoft 帐号' });
  const minecraft = await xbox.getMinecraft();
  onlineAccount = { authorization: minecraft.mclc(), token: minecraft.mcToken, profile: minecraft.profile, refreshToken: xbox.save() };
  // The profile returned during authentication is normally complete; query the
  // official service once so the displayed active skin is always current.
  onlineAccount.profile = await minecraftProfile(onlineAccount.token);
  saveRefreshToken(onlineAccount.refreshToken);
  return publicAccount();
});
ipcMain.handle('account:logout', () => { onlineAccount = null; clearRefreshToken(); return true; });
ipcMain.handle('skin:upload', async (_, model) => {
  if (!onlineAccount) throw new Error('请先登录拥有 Minecraft: Java Edition 的微软帐号。');
  const variant = model === 'SLIM' ? 'slim' : 'classic';
  const result = await dialog.showOpenDialog(windowRef, { title: '选择 PNG 皮肤', properties: ['openFile'], filters: [{ name: 'PNG 皮肤', extensions: ['png'] }] });
  if (result.canceled) return publicAccount();
  const filePath = result.filePaths[0];
  const image = fs.readFileSync(filePath);
  const form = new FormData();
  form.set('variant', variant);
  form.set('file', new Blob([image], { type: 'image/png' }), path.basename(filePath));
  const response = await fetch('https://api.minecraftservices.com/minecraft/profile/skins', { method: 'POST', headers: { Authorization: `Bearer ${onlineAccount.token}` }, body: form });
  if (!response.ok) throw new Error(`皮肤上传失败（${response.status}）。请确认皮肤为有效 PNG 且帐号登录未过期。`);
  onlineAccount.profile = await minecraftProfile(onlineAccount.token);
  return publicAccount();
});
ipcMain.handle('skin:model', async (_, model) => {
  if (!onlineAccount) throw new Error('请先登录正版帐号。');
  const skin = activeSkin(onlineAccount.profile);
  if (!skin?.url) throw new Error('当前帐号没有可切换模型的皮肤。请先上传一个皮肤。');
  const response = await fetch('https://api.minecraftservices.com/minecraft/profile/skins', { method: 'POST', headers: { Authorization: `Bearer ${onlineAccount.token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ url: skin.url, variant: model === 'SLIM' ? 'slim' : 'classic' }) });
  if (!response.ok) throw new Error(`模型切换失败（${response.status}）。`);
  onlineAccount.profile = await minecraftProfile(onlineAccount.token);
  return publicAccount();
});
ipcMain.handle('launch', async (_, input) => {
  if (running || installing) throw new Error('游戏正在启动、运行或安装中');
  const accountMode = input.accountMode === 'online' ? 'online' : 'offline';
  const username = String(input.username || '').trim();
  if (accountMode === 'offline' && !/^[A-Za-z0-9_]{3,16}$/.test(username)) throw new Error('离线用户名须为 3–16 位英文字母、数字或下划线。');
  if (accountMode === 'online' && !onlineAccount) throw new Error('请先登录微软正版帐号。');
  const javaPath = String(input.javaPath || 'java').trim();
  const forbiddenJavaCharacters = /[\x00-\x1F\x7F"'&|;<>`$()%^!]/;
  const isJavaCommand = javaPath === 'java';
  const isJavaExecutable = path.isAbsolute(javaPath) && path.extname(javaPath).toLowerCase() === '.exe' && fs.existsSync(javaPath) && fs.statSync(javaPath).isFile();
  if (forbiddenJavaCharacters.test(javaPath) || (!isJavaCommand && !isJavaExecutable)) {
    throw new Error('Java 路径只能是 java，或一个存在的绝对 .exe 文件路径。');
  }
  const memory = Number(input.memory);
  if (!Number.isInteger(memory) || memory < 1024 || memory > 12288) {
    throw new Error('最大内存须为 1024–12288 MB 的整数。');
  }
  const loader = normalizeLoader(input.loader);
  assertAutomaticLoader(loader, String(input.version || ''));
  const release = await validatedRelease(input.version);
  if (!fs.existsSync(installMarker(release.id, loader))) throw new Error(`请先下载 ${release.id} · ${LOADER_NAMES[loader]}。`);
  const supportedLanguages = new Set(['zh_cn', 'en_us', 'zh_tw', 'ja_jp']);
  const language = supportedLanguages.has(input.language) ? input.language : 'zh_cn';
  const compatibleJava = await ensureOfficialJava(release.id, javaPath);
  const settings = { username, version: release.id, loader, memory, javaPath: compatibleJava, accountMode, language };
  saveSettings(settings);
  const playDirectory = instanceRoot(release.id, loader);
  applyGameLanguage(language, playDirectory);
  running = true;
  send('log', { type: 'info', message: `准备启动 ${settings.version} · ${LOADER_NAMES[loader]}，角色：${username}` });
  try {
    // getAuth is asynchronous even for an offline profile. Resolve it before
    // handing it to the launcher so launch arguments always contain a real
    // name/UUID/token object.
    const authorization = accountMode === 'online' ? onlineAccount.authorization : offlineAuthorization(username);
    const installMetadata = readInstallMetadata(settings.version, loader);
    const fastOverrides = { maxSockets: 16, liteMcSkipAssetCheck: true };
    if (Array.isArray(installMetadata.classes) && installMetadata.classes.length) fastOverrides.classes = installMetadata.classes;
    const launchVersion = { number: settings.version, type: 'release' };
    if (loader !== 'vanilla') {
      if (!/^[A-Za-z0-9][A-Za-z0-9._+-]{0,119}$/.test(installMetadata.customId || '')) throw new Error(`${LOADER_NAMES[loader]} 安装信息损坏，请重新下载该版本。`);
      verifyInstalledClasses(installMetadata.classes);
      launchVersion.custom = installMetadata.customId;
      fastOverrides.versionJson = path.join(gameRoot(), 'versions', settings.version, `${settings.version}.json`);
      fastOverrides.minecraftJar = path.join(gameRoot(), 'versions', settings.version, `${settings.version}.jar`);
      const vanilla = JSON.parse(fs.readFileSync(fastOverrides.versionJson, 'utf8'));
      fastOverrides.assetIndex = vanilla.assetIndex?.id || vanilla.assets || settings.version;
      fastOverrides.gameDirectory = playDirectory;
    }
    if (loader === 'forge' && (!Array.isArray(installMetadata.jvmArgs) || installMetadata.jvmArgs.some(value => typeof value !== 'string' || /[\x00\r\n]/.test(value)))) throw new Error('Forge JVM 参数缺失或损坏，请重新安装。');
    const child = await launcher.launch({
      authorization,
      root: gameRoot(), javaPath: settings.javaPath,
      version: launchVersion,
      memory: { min: '1G', max: `${settings.memory}M` },
      customArgs: loader === 'forge' ? installMetadata.jvmArgs : undefined,
      // Mojang official endpoints, with a larger connection pool for assets.
      overrides: fastOverrides,
      window: { width: 1280, height: 720, fullscreen: false }
    });
    // minecraft-launcher-core reports download, Java, and launch failures by
    // resolving with null (after emitting its debug event), rather than
    // rejecting. Treat that as a failed launch instead of leaving the UI in
    // the "running" state with no game process.
    if (!child) throw new Error('Minecraft 未能启动。请查看日志，并确认 Java 路径及其版本与所选游戏版本兼容。');
    send('status', { running: true, text: 'Minecraft 正在运行' });
    child?.on('close', code => { running = false; send('status', { running: false, text: `游戏已退出（代码 ${code}）` }); });
  } catch (error) {
    running = false; send('status', { running: false, text: '启动失败' });
    throw error;
  }
  return true;
});
for (const event of ['debug', 'data', 'download', 'progress']) launcher.on(event, e => send('log', { type: event, message: redactLog(typeof e === 'string' ? e : JSON.stringify(e)) }));
launcher.on('close', code => { running = false; send('status', { running: false, text: `游戏已退出（代码 ${code}）` }); });
