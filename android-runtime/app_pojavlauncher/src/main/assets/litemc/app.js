'use strict';
const pageStyles = document.createElement('link'); pageStyles.rel = 'stylesheet'; pageStyles.href = 'pages.css'; document.querySelector('head').append(pageStyles);
const $ = id => document.getElementById(id);
const pending = new Map(); let sequence = 0, state = {}, loader = 'vanilla', selectedSkin = '', loginFlow = '', loginTimer, toastTimer, busy = false, language = 'zh';
let catalogItems = [];
const text = (zh, en) => language === 'en' ? en : zh;
function call(action, args = {}) {
  if (!window.LiteNative) return Promise.reject(new Error(text('这是界面预览，请在 Android APK 中使用此功能。', 'UI preview only. Use the Android APK for this action.')));
  return new Promise((resolve, reject) => { const id = String(++sequence); pending.set(id, {resolve, reject}); window.LiteNative.request(id, action, JSON.stringify(args)); });
}
window.LiteEvent = (name, data) => {
  if (name === 'reply') { const item = pending.get(data.id); if (!item) return; pending.delete(data.id); data.error ? item.reject(new Error(data.error)) : item.resolve(data.data); }
  if (name === 'ready') { state.ready = true; refresh().catch(showError); }
  if (name === 'error') toast(data.message);
  if (name === 'progress' && busy) { $('task-message').textContent = data.message; if (data.percent < 0) $('task-progress').removeAttribute('value'); else $('task-progress').value = Math.max(0, Math.min(100, data.percent)); }
};
function toast(message) { clearTimeout(toastTimer); $('toast').textContent = message; $('toast').hidden = false; toastTimer = setTimeout(() => $('toast').hidden = true, 6500); }
function showError(error) { toast(error.message || String(error)); }
async function run(button, job, progress = false) {
  if (busy) return toast(text('请等待当前操作完成。', 'Please wait for the current operation.'));
  busy = true; if (button) button.disabled = true;
  if (progress) { $('task-panel').hidden = false; $('task-message').textContent = text('正在准备… 首次安装可能需要较长时间，请保持应用在前台。', 'Preparing… Keep the app in the foreground during the first installation.'); $('task-progress').removeAttribute('value'); }
  try { await job(); } catch (error) { showError(error); }
  finally { busy = false; if (button) button.disabled = false; $('task-panel').hidden = true; updateLaunch(); }
}
function node(tag, className, content) { const item = document.createElement(tag); if (className) item.className = className; if (content !== undefined) item.textContent = content; return item; }
function empty(target, message) { target.replaceChildren(node('div', 'empty', message)); }
function selected() { return (state.instances || []).find(item => item.id === $('instance-select').value); }
function versionIssue(version, entry) {
  const item = entry || catalogItems.find(value => value.id === version);
  if (state.android26Supported === true && (!item || item.supported !== false)) return '';
  if (item && item.supported === false && item.reason) return item.reason;
  return /^26(?:[.-]|$)/.test(version || '') ? text('当前 Android 图形运行层暂不支持 Minecraft 26.x；Mali-G615 上已确认渲染初始化失败。请选择 1.21.x，已有实例和存档会保留。', 'Minecraft 26.x is blocked by the current Android graphics runtime. Rendering initialization fails on Mali-G615. Choose 1.21.x; existing instances and saves are preserved.') : '';
}
function requireVersion(version, entry) { const reason = versionIssue(version, entry); if (reason) throw new Error(reason); }
function updateInstallState() {
  const version = $('version-select').value;
  $('install').disabled = busy || !$('version-select').dataset.loaded || !version || Boolean(versionIssue(version));
  const download = document.getElementById('download-version-control'), button = document.getElementById('download-install');
  if (download && button) button.disabled = busy || !$('version-select').dataset.loaded || !download.value || Boolean(versionIssue(download.value));
}
function loaderCapability(id) { return (state.loaders || []).find(item => item.id === id) || {automaticInstall: ['vanilla', 'fabric', 'forge'].includes(id), reason: text('此加载器尚未接入 Android 自动安装。', 'Automatic installation is unavailable on Android.')}; }
function allowLoader(id) { const capability = loaderCapability(id); if (!capability.automaticInstall) { toast(capability.reason); return false; } return true; }
function renderLoaderCapabilities() {
  document.querySelectorAll('[data-loader],[data-download-loader]').forEach(button => {
    const id = button.dataset.loader || button.dataset.downloadLoader, capability = loaderCapability(id);
    button.dataset.unavailable = String(!capability.automaticInstall); button.title = capability.reason || '';
    const label = button.querySelector('small');
    if (label) { label.dataset.zh = capability.automaticInstall ? (id === 'fabric' ? '自动安装 Fabric API' : '可安装') : '安卓暂不可自动安装'; label.dataset.en = capability.automaticInstall ? (id === 'fabric' ? 'Includes Fabric API' : 'Available') : 'Unavailable on Android'; label.textContent = text(label.dataset.zh, label.dataset.en); }
  });
}
function settings() { return {language: $('language').value, memory: Number($('memory').value), motion: $('motion').checked, model: window.LiteSkin.model, controlScale: Number($('control-scale').value), performanceMode: $('performance-mode') ? $('performance-mode').value : 'balanced'}; }
function translate() {
  document.documentElement.lang = language === 'en' ? 'en' : 'zh-CN';
  document.querySelectorAll('[data-zh]').forEach(item => item.textContent = (language === 'en' ? item.dataset.en : item.dataset.zh).replace(/\\n/g, '\n'));
  if (language === 'zh') {
    const brandSmall = document.querySelector('.brand small');
    const railNote = document.querySelector('.rail-note');
    const modTab = document.querySelector('[data-page="mods"] label');
    const homeEyebrow = document.querySelector('#home .eyebrow');
    if (brandSmall) brandSmall.textContent = '轻量 · 自由 · 中文版';
    if (railNote) railNote.innerHTML = '<i></i> 轻一点，玩自己的';
    if (modTab) modTab.textContent = '资源';
    if (homeEyebrow) homeEyebrow.textContent = '你的下一场冒险';
    document.querySelectorAll('.eyebrow').forEach(item => {
      if (item.textContent === 'YOUR NEXT ADVENTURE') item.textContent = '你的下一场冒险';
      if (item.textContent === 'LITE-MC / ANDROID') item.textContent = 'LITE-MC / 安卓版';
      if (item.textContent === 'BUILD YOUR COLLECTION') item.textContent = '我的游戏版本';
      if (item.textContent === 'LESS LIMITS. MORE POSSIBILITIES.') item.textContent = '更多玩法，更多可能';
      if (item.textContent === 'BE YOURSELF') item.textContent = '成为你自己';
      if (item.textContent === 'FINELY TUNED') item.textContent = '调整到刚刚好';
    });
  }
  window.LiteSkin.motion = $('motion').checked;
  const titles = {home: text('准备好，出发。', 'Ready for your next world.'), versions: text('版本仓库', 'Your collection'), downloads: text('下载新版本', 'Download a version'), mods: text('模组实验室', 'Mod workshop'), 'mod-detail': text('Mod 详情', 'Mod details'), 'local-files': text('本地资源', 'Local resources'), 'global-controls': text('全局按键', 'Global controls'), profile: text('我的角色', 'Your identity'), settings: text('调整到刚刚好', 'Make yourself at home')};
  titles['pack-detail'] = text('整合包版本', 'Modpack versions');
  $('page-title').textContent = titles[document.querySelector('.page.active').id];
}
function page(name) {
  document.querySelectorAll('.page').forEach(item => item.classList.toggle('active', item.id === name));
  const navPage = {'local-files':'mods', 'global-controls':'settings', 'mod-detail':'mods', 'pack-detail':'mods'}[name] || name;
  document.querySelectorAll('[data-page]').forEach(item => item.classList.toggle('active', item.dataset.page === navPage));
  translate(); window.scrollTo(0, 0);
  if (window.LiteToolsPage) window.LiteToolsPage(name);
  if (['versions', 'downloads'].includes(name) && !$('version-select').dataset.loaded) catalog(false).catch(showError);
}
window.LiteBack = () => page('home');
document.querySelectorAll('[data-page],[data-go]').forEach(button => button.addEventListener('click', () => page(button.dataset.page || button.dataset.go)));
document.querySelector('.brand').addEventListener('click', event => { event.preventDefault(); page('home'); });
function updateLaunch() {
  const item = selected(), reason = item ? versionIssue(item.version, item) : '';
  $('launch').disabled = busy || !state.ready || !item || Boolean(reason);
  $('compatibility-warning').hidden = !reason; $('compatibility-warning').textContent = reason;
  $('loader-label').textContent = item ? item.loader.toUpperCase() : 'Vanilla / Fabric';
  $('memory-label').textContent = (state.memory || 2048) + ' MB';
  $('runtime-state').textContent = state.ready ? 'RUNTIME READY' : text('运行组件准备中', 'PREPARING RUNTIME');
  $('mod-target').textContent = item ? text('安装目标：', 'Target: ') + (item.name || item.version) + ' · ' + item.loader.toUpperCase() + ' · ' + Number(item.modCount || 0) + ' Mods' : text('先在主页选择一个已安装的实例。', 'Select an installed instance on Home first.');
  if ($('mod-kind').value === 'modpack') $('mod-target').textContent = text('搜索全部游戏版本，无需先安装游戏。整合包会新建独立实例，不修改当前版本。', 'Browse all game versions. Packs create a new instance; no existing installation is required.');
  if (window.LitePackControls) window.LitePackControls();
  updateInstallState();
}
async function refresh() {
  state = await call('state'); language = state.language || 'zh';
  const account = state.account || {}; const name = account.name || 'Player';
  $('account-name').textContent = name; $('skin-name').textContent = name; $('account-type').textContent = account.mode === 'online' ? 'MICROSOFT' : 'OFFLINE';
  $('offline-name').value = account.mode === 'offline' ? name : 'Player';
  $('language').value = language; $('memory').value = state.memory || 2048; $('motion').checked = state.motion !== false; if ($('performance-mode')) $('performance-mode').value = state.performanceMode || 'balanced';
  $('control-scale').value = state.controlScale || 100; $('client-id').value = account.clientId || '';
  if (!selectedSkin) setModel((account.mode === 'online' ? (account.model || state.model || 'classic') : (state.model || 'classic')).toLowerCase());
  $('instance-select').replaceChildren();
  (state.instances || []).forEach(item => { const option = node('option', '', (item.name || item.version) + ' · ' + item.loader.toUpperCase() + ' · ' + Number(item.modCount || 0) + ' Mods' + (versionIssue(item.version, item) ? text(' · 图形不兼容', ' · Graphics incompatible') : '')); option.value = item.id; $('instance-select').append(option); });
  if (!state.instances || !state.instances.length) { const option = node('option', '', text('先安装一个版本', 'Install a version first')); option.value = ''; $('instance-select').append(option); }
  if (state.selected) $('instance-select').value = state.selected;
  if (!$('instance-select').value && state.instances && state.instances[0]) $('instance-select').value = state.instances[0].id;
  renderInstances(); renderLoaderCapabilities(); translate(); updateLaunch();
  if (!selectedSkin) { const skin = await call('skin.read').catch(() => ({})); if (skin.base64) window.LiteSkin.load(skin.base64); }
}
function renderInstances() {
  const items = state.instances || []; $('instance-count').textContent = String(items.length); $('instance-list').replaceChildren();
  if (!items.length) return empty($('instance-list'), text('你的收藏还是空的。安装原版，或者加上 Fabric 开始模组冒险。', 'Your collection is empty. Install Vanilla or add Fabric for mods.'));
  items.forEach(item => { const row = node('article', 'list-row'); row.append(node('span','row-icon','▦')); const details = node('div','details'), reason = versionIssue(item.version, item); details.append(node('b','',item.name || item.version), node('p','',item.version + ' · ' + item.loader.toUpperCase() + (item.loaderVersion ? ' / ' + item.loaderVersion : ''))); details.append(node('p','',Number(item.modCount || 0)+' Mods · '+text('独立游戏目录','Isolated game directory'))); if (reason) details.append(node('p','',reason)); const button = node('button','secondary',reason ? text('图形不兼容','Incompatible') : text('使用此版本','Select')); button.disabled = Boolean(reason); button.addEventListener('click', () => run(button, async () => { requireVersion(item.version,item); await call('select',{instanceId:item.id}); await refresh(); page('home'); })); row.append(details,button); $('instance-list').append(row); });
}
async function catalog(force) {
  const response = await call('catalog', {refresh: force}); const old = $('version-select').value; $('version-select').replaceChildren();
  if (response.loaders) { state.loaders = response.loaders; renderLoaderCapabilities(); }
  catalogItems = response.items;
  response.items.forEach(item => { const reason = versionIssue(item.id,item), option = node('option', '', item.id + '  /  ' + item.date + (reason ? text(' · 图形不兼容，请选 1.21.x', ' · Incompatible; choose 1.21.x') : '')); option.value = item.id; option.disabled = Boolean(reason); option.title = reason; $('version-select').append(option); });
  const supported = response.items.filter(item => !versionIssue(item.id,item));
  const preferred = supported.find(item => item.id === old) || supported.find(item => /^1\.21(?:\.|$)/.test(item.id)) || supported[0];
  $('version-select').value = preferred ? preferred.id : '';
  $('version-select').dataset.loaded = 'true'; if (response.cached) toast(text('网络不可用，已载入缓存版本列表。', 'Offline: showing the cached version list.'));
  if (window.LiteSyncDownloadVersions) window.LiteSyncDownloadVersions();
  updateInstallState();
}
document.querySelectorAll('[data-loader]').forEach(button => button.addEventListener('click', () => { if (!allowLoader(button.dataset.loader)) return; loader = button.dataset.loader; document.querySelectorAll('[data-loader]').forEach(item => item.classList.toggle('selected', item === button)); if (window.LiteForgeShow) window.LiteForgeShow('version',loader); }));
$('refresh-catalog').onclick = () => run($('refresh-catalog'), () => catalog(true));
$('version-select').addEventListener('change', updateInstallState);
$('instance-select').onchange = () => run(null, async () => { const item = selected(); if (item) { requireVersion(item.version,item); await call('select', {instanceId:item.id}); } updateLaunch(); });
$('install').onclick = () => run($('install'), async () => { if (!$('version-select').dataset.loaded) throw new Error(text('请先加载版本列表。','Load the version list first.')); requireVersion($('version-select').value); const args={version:$('version-select').value,loader}; if (loader==='forge' && window.LiteForgeVersion) args.loaderVersion=window.LiteForgeVersion('version'); await call('install',args); await refresh(); toast(text('安装完成，已加入我的版本。','Installed and added to your collection.')); }, true);
$('launch').onclick = () => run($('launch'), async () => { const item=selected(); requireVersion(item.version,item); await call('launch',{instanceId:item.id}); toast(text('正在打开游戏运行窗口…','Opening the game runtime…')); }, true);
$('save-offline').onclick = () => run($('save-offline'), async () => { const name = $('offline-name').value.trim(); if (!/^[A-Za-z0-9_]{3,16}$/.test(name)) throw new Error(text('名字需要 3–16 位字母、数字或下划线。','Use 3–16 letters, digits or underscores.')); await call('accounts.offline',{name}); clearTimeout(loginTimer); loginFlow = ''; $('login-flow').hidden = true; await refresh(); toast(text('离线账号已保存。','Offline profile saved.')); });
function setModel(model) { window.LiteSkin.model = model === 'slim' ? 'slim' : 'classic'; document.querySelectorAll('[data-model]').forEach(button => button.classList.toggle('selected', button.dataset.model === window.LiteSkin.model)); }
document.querySelectorAll('[data-model]').forEach(button => button.addEventListener('click', () => run(button, async () => { setModel(button.dataset.model); await call('settings', settings()); })));
$('pick-skin').onclick = () => run($('pick-skin'), async () => { const skin = await call('skin.pick'); if (skin.base64) { selectedSkin = skin.base64; await window.LiteSkin.load(selectedSkin); } });
$('upload-skin').onclick = () => run($('upload-skin'), async () => { if (!selectedSkin) throw new Error(text('请先选择 PNG 皮肤。','Choose a PNG skin first.')); await call('accounts.skin',{base64:selectedSkin,model:window.LiteSkin.model}); await refresh(); toast(text('皮肤已更新到正版账号。','Your online skin has been updated.')); });
$('save-settings').onclick = () => run($('save-settings'), async () => { await call('settings',settings()); await refresh(); toast(text('设置已保存。','Settings saved.')); });
$('language').onchange = () => { language = $('language').value; translate(); updateLaunch(); };
$('motion').onchange = () => window.LiteSkin.motion = $('motion').checked;
$('save-client').onclick = () => run($('save-client'), async () => { await call('accounts.config',{clientId:$('client-id').value.trim()}); toast(text('应用 ID 已保存。','Client ID saved.')); });
$('save-key').onclick = () => run($('save-key'), async () => { await call('mods.config',{curseforgeKey:$('curse-key').value.trim()}); $('curse-key').value = ''; toast(text('API key 已加密保存。','API key saved securely.')); });
$('login').onclick = () => run($('login'), async () => { const flow = await call('accounts.login.start'); loginFlow = flow.flowId; $('login-code').textContent = flow.userCode; $('login-flow').hidden = false; schedulePoll(Math.max(5,flow.interval || 5)); });
function schedulePoll(seconds) { clearTimeout(loginTimer); loginTimer = setTimeout(() => { if (loginFlow && !busy) pollLogin().catch(showError); else if (loginFlow) schedulePoll(seconds); }, seconds * 1000); }
async function pollLogin() {
  if (!loginFlow) return; const response = await call('accounts.login.poll', {flowId:loginFlow});
  if (response.pending || response.status === 'pending') { schedulePoll(Math.max(5,response.interval || 5)); return; }
  loginFlow = ''; clearTimeout(loginTimer); $('login-flow').hidden = true; selectedSkin = ''; await refresh(); toast(text('登录成功，会话已安全保存。','Signed in. Your session is stored securely.'));
}
$('poll-login').onclick = () => run($('poll-login'), pollLogin);
$('open-login').onclick = () => run($('open-login'), () => call('browser.login'));
$('logout').onclick = () => run($('logout'), async () => { clearTimeout(loginTimer); loginFlow = ''; await call('accounts.logout'); $('login-flow').hidden = true; await refresh(); });
function modArgs(forSearch = false) { const kind = $('mod-kind').value; if (forSearch && kind === 'modpack') return {provider:$('mod-provider').value,kind}; const item = selected(); if (!item) throw new Error(text('安装模组或资源包需要先选择一个游戏实例；整合包可以直接搜索。','Select an instance for mods or resource packs. Modpacks can be searched directly.')); return {provider:$('mod-provider').value,kind,version:item.version,loader:item.loader,instanceId:item.id}; }
function modIcon(item) { const icon = node('div', 'row-icon mod-result-icon', String(item.title || item.id || 'M').trim().slice(0, 1).toUpperCase()); if (item.iconUrl && /^https:\/\//.test(item.iconUrl)) { const image = document.createElement('img'); image.src = item.iconUrl; image.alt = ''; image.addEventListener('error', () => image.remove()); icon.textContent = ''; icon.append(image); } return icon; }
function installMod(item, args, button) { if (args.kind === 'modpack') return window.LiteOpenPack(item,args,button); return run(button, async () => { await call('mods.install',{...args,projectId:item.id}); const kind = args.kind === 'resourcepack' ? text('资源包','resource pack') : text('模组及必需依赖','mod and required dependencies'); toast(text(kind + '已下载到当前实例。', kind + ' downloaded to the current instance.')); },true); }
function showModDetail(item, args) { if (args.kind === 'modpack') return window.LiteOpenPack(item,args); $('mod-detail-icon').replaceChildren(modIcon(item)); $('mod-detail-source').textContent = (item.source || args.provider || 'MOD').toUpperCase(); $('mod-detail-title').textContent = item.title || item.id; $('mod-detail-meta').textContent = [item.author, Number(item.downloads || 0).toLocaleString() + ' ' + text('下载', 'downloads')].filter(Boolean).join(' · '); $('mod-detail-description').textContent = item.description || text('该 Mod 未提供项目说明。', 'This mod has no project description.'); const link = $('mod-detail-link'); const validLink = item.pageUrl && /^https:\/\//.test(item.pageUrl); link.hidden = !validLink; if (validLink) link.href = item.pageUrl; $('mod-detail-install').onclick = () => installMod(item, args, $('mod-detail-install')); page('mod-detail'); }
$('mod-back').onclick = () => page('mods');
$('mod-search').onclick = () => run($('mod-search'), async () => {
  $('mod-results').replaceChildren(); $('mod-feedback').textContent = text('正在搜索官方资源…','Searching official resources…');
  try {
    const args = {...modArgs(true),query:$('mod-query').value.trim()};
    const result = await call('mods.search',args);
    if (args.kind !== $('mod-kind').value || args.provider !== $('mod-provider').value) return;
    $('mod-feedback').textContent = result.items.length ? text(`找到 ${result.items.length} 个项目`,`${result.items.length} projects found`) : text('没有找到结果，试试项目英文名或切换来源。','No results. Try the project name or another source.');
    result.items.forEach(item => { const row = node('article','list-row mod-result'); const detail = node('div','details'); const count = Number(item.downloads || 0).toLocaleString(); detail.append(node('b','',item.title),node('p','',item.description),node('p','',[(item.author || ''), count + ' ' + text('次下载','downloads')].filter(Boolean).join(' · '))); const install = node('button','secondary',args.kind === 'modpack' ? text('选版本','Versions') : text('安装','Install')); install.onclick = () => installMod(item,args,install); const more = node('button','text-button mod-more',text('详情','Details')); more.onclick = () => showModDetail(item,args); row.append(modIcon(item),detail,install,more); $('mod-results').append(row); });
  } catch (error) { $('mod-feedback').textContent = error.message; throw error; }
});
$('mod-kind').onchange = $('mod-provider').onchange = () => { $('mod-results').replaceChildren(); $('mod-feedback').textContent = $('mod-provider').value === 'curseforge' ? text('CurseForge 需要在设置中填写有效 API key；Modrinth 不需要。','CurseForge requires an API key in Settings. Modrinth does not.'):text('留空搜索可查看热门项目。','Search with no text to browse popular projects.'); updateLaunch(); };
$('mod-query').addEventListener('keydown', event => { if (event.key === 'Enter') $('mod-search').onclick(); });
$('mod-files').onclick = () => run($('mod-files'), async () => { const result = await call('mods.list',modArgs()); $('mod-file-list').replaceChildren(); if (!result.items.length) return empty($('mod-file-list'),text('文件夹已经就绪，目前没有 Mod。','Folder ready. No mods installed yet.')); result.items.forEach(item => { const row = node('article','list-row'); const detail = node('div','details'); detail.append(node('b','',item.name),node('p','',(item.size/1048576).toFixed(2)+' MB')); row.append(node('span','row-icon','◇'),detail); $('mod-file-list').append(row); }); });
function setupDownloadPage() {
  const nav = node('button'); nav.dataset.page = 'downloads'; nav.innerHTML = '<span>↓</span><label>下载</label>'; document.querySelector('nav').append(nav); nav.addEventListener('click', () => page('downloads'));
  const pageNode = node('section'); pageNode.id = 'downloads'; pageNode.className = 'page'; pageNode.innerHTML = '<div class="section-heading"><div><span class="eyebrow">LITE-MC 下载中心</span><h2>下载一个新的游戏版本。</h2></div></div><div class="card install-form"><label>版本名称<input id="download-name" maxlength="32" placeholder="例如：生存 1.20.1 Fabric"></label><label>Minecraft 版本<select id="download-version-control"><option>正在加载官方版本…</option></select></label><fieldset><legend>加载器</legend><div class="segmented"><button class="selected" data-download-loader="vanilla">原版 <small>纯净 Minecraft</small></button><button data-download-loader="fabric">Fabric <small>自动安装 Fabric API</small></button><button data-download-loader="forge">Forge <small>官方安装器 · 1.13+</small></button><button data-download-loader="liteloader">LiteLoader <small>安卓暂不可自动安装</small></button><button data-download-loader="optifine">OptiFine <small>安卓暂不可自动安装</small></button></div></fieldset><div id="download-forge-options" hidden><label>Forge 版本<select id="download-forge-version"><option value="">官方推荐 / 最新</option></select></label><button id="download-forge-refresh" class="secondary">查看官方 Forge 版本</button><p class="fine">支持 Minecraft 1.13 及以上的现代 Forge 安装器；安装期间会打开中文进度窗口。旧版暂未适配。</p></div><p class="fine">26.x 是否可用会根据设备图形运行层判断；平板已开放兼容版本。</p><p class="fine">同一个 Minecraft 版本可以重复安装，每次会创建独立实例和独立 Mod 文件夹。</p><button id="download-install" class="primary">下载并安装</button></div>'; document.querySelector('main').append(pageNode);
  pageNode.querySelector('.segmented')?.classList.add('loader-grid');
  const footer = document.querySelector('main > footer');
  if (footer) footer.before(pageNode);
  if (!pageNode.querySelector('#download-install')) return;
  const versionSelect = $('version-select'), downloadVersion = document.getElementById('download-version-control');
  const sync = () => { if (versionSelect && downloadVersion && versionSelect.options && downloadVersion.options) { const previous = downloadVersion.value; downloadVersion.innerHTML = versionSelect.innerHTML; downloadVersion.value = versionSelect.value; if (previous !== downloadVersion.value) downloadVersion.dispatchEvent(new Event('change')); } updateInstallState(); };
  downloadVersion.addEventListener('change', updateInstallState);
  $('version-select').addEventListener('change', sync); document.querySelector('[data-go="versions"]')?.addEventListener('click', () => setTimeout(sync, 20));
  let downloadLoader = 'vanilla'; document.querySelectorAll('[data-download-loader]').forEach(button => button.addEventListener('click', () => { if (!allowLoader(button.dataset.downloadLoader)) return; downloadLoader = button.dataset.downloadLoader; if(window.LiteForgeShow) window.LiteForgeShow('download',downloadLoader); document.querySelectorAll('[data-download-loader]').forEach(item => item.classList.toggle('selected', item === button)); }));
  document.getElementById('download-install').onclick = () => run(document.getElementById('download-install'), async () => { const version = document.getElementById('download-version-control').value; if (!$('version-select').dataset.loaded || !version || version.includes('加载')) throw new Error('请先加载官方版本列表'); requireVersion(version); await call('install', {version, loader:downloadLoader, loaderVersion:downloadLoader==='forge' && window.LiteForgeVersion ? window.LiteForgeVersion('download') : '', name:document.getElementById('download-name').value.trim()}); await refresh(); toast('版本安装完成，已创建新的独立实例。'); page('versions'); }, true);
  window.LiteSyncDownloadVersions = sync;
}
setupDownloadPage();
refresh().catch(error => { if (window.LiteNative) showError(error); else { $('runtime-state').textContent = 'UI PREVIEW · ANDROID REQUIRED'; empty($('instance-list'), 'UI preview — no game is installed here.'); } });
