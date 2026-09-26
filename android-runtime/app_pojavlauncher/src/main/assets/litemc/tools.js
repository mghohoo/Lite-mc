'use strict';
// Local files and touch controls share one tools area; they add no bottom navigation tabs.
(() => {
  let profile;
  const defaults = {scale:100, opacity:85, projection:true, sprintToggle:true, sneakToggle:true, extraChat:false,
    bindings:{跳跃:32, 攻击:-3, 使用:-4, 潜行:340, 疾跑:341, 背包:69, 暂停:256, 文字输入:84}};
  const labels = new Map([[-3,'鼠标左键'],[-4,'鼠标右键'],[-6,'鼠标中键'],[32,'空格'],[256,'Esc'],[257,'回车'],[258,'Tab'],[259,'退格'],[340,'左 Shift'],[341,'左 Ctrl'],[342,'左 Alt'],[344,'右 Shift'],[345,'右 Ctrl'],[346,'右 Alt']]);
  for (let k=48;k<=57;k++) labels.set(k,String.fromCharCode(k));
  for (let k=65;k<=90;k++) labels.set(k,String.fromCharCode(k));
  for (let k=290;k<=301;k++) labels.set(k,'F'+(k-289));
  const target = () => { const item=selected(); if (!item) throw new Error('先在主页选择一个已安装实例。'); return item; };
  function fileRows(element, items) {
    element.replaceChildren();
    if (!items.length) return empty(element,'这里还没有文件。');
    for(const item of items) {
      const row=node('article','list-row'), details=node('div','details');
      details.append(node('b','',item.name),node('p','',(item.size/1048576).toFixed(2)+' MB'));
      row.append(details); element.append(row);
    }
  }
  async function localFiles() {
    const item=target();
    $('local-target').textContent='当前实例：'+(item.name || item.version)+' · '+item.loader.toUpperCase();
    $('local-mod-import').disabled=item.loader==='vanilla';
    const [mods, projection]=await Promise.all([call('mods.list',{instanceId:item.id}),call('projection.status',{instanceId:item.id})]);
    fileRows($('local-mod-list'),mods.items || []); fileRows($('schematic-list'),projection.items || []);
    $('projection-badge').textContent=projection.installed ? '已安装 Litematica' : '未安装投影';
    $('projection-status').textContent=projection.installed
      ? (projection.malilib ? '已检测到投影与 MaLiLib。' : '未检测到 MaLiLib，请按投影版本补齐依赖。')+'下次启动会使用“'+projection.shortcut+'”按钮，可在全局按键中关闭。'
      : '当前实例未检测到 Litematica。可先导入蓝图，再从资源中心安装与此版本匹配的 Litematica 和 MaLiLib。';
  }
  async function importFiles(kind) {
    const item=target(), result=await call('files.import',{instanceId:item.id,kind});
    if(result.cancelled) return;
    const panel=$('import-result'); panel.hidden=false; panel.replaceChildren();
    panel.append(node('h3','',`已导入 ${(result.installed || []).length} 个文件`));
    for(const name of result.installed || []) panel.append(node('p','fine','✓ '+name));
    for(const failure of result.failed || []) panel.append(node('p','fine',failure.name+'：'+failure.error));
    await localFiles();
  }
  function showProfile(value) {
    profile=value; $('global-scale').value=value.scale; $('global-opacity').value=value.opacity;
    $('global-sprint').checked=value.sprintToggle; $('global-sneak').checked=value.sneakToggle;
    $('global-projection').checked=value.projection; $('global-extra-chat').checked=value.extraChat;
    $('global-bindings').replaceChildren();
    for(const [name,key] of Object.entries(value.bindings)) {
      const label=node('label','',name), select=node('select'); select.dataset.action=name;
      for(const [code,display] of labels) { const option=node('option','',display); option.value=code; select.append(option); }
      if(!labels.has(key)) { const option=node('option','','按键 '+key); option.value=key; select.append(option); }
      select.value=key; label.append(select); $('global-bindings').append(label);
    }
    updateRanges();
  }
  function updateRanges() { $('global-scale-label').textContent=$('global-scale').value+'%'; $('global-opacity-label').textContent=$('global-opacity').value+'%'; }
  $('global-scale').oninput=updateRanges; $('global-opacity').oninput=updateRanges;
  $('global-save').onclick=()=>run($('global-save'),async()=>{
    if(!profile) throw new Error('请等待按键设置加载。');
    const bindings={}; $('global-bindings').querySelectorAll('select').forEach(select=>bindings[select.dataset.action]=Number(select.value));
    const value={scale:Number($('global-scale').value),opacity:Number($('global-opacity').value),bindings,
      sprintToggle:$('global-sprint').checked,sneakToggle:$('global-sneak').checked,
      projection:$('global-projection').checked,extraChat:$('global-extra-chat').checked};
    showProfile(await call('controls.save',value));
    $('control-scale').value=value.scale;
    $('global-feedback').textContent='已保存。所有实例下次启动生效；投影按钮会根据当前实例自动显示。';
  });
  $('global-reset').onclick=()=>{showProfile(JSON.parse(JSON.stringify(defaults)));$('global-feedback').textContent='已恢复默认，请点击保存应用。';};
  $('local-mod-import').onclick=()=>run($('local-mod-import'),()=>importFiles('mod'),true);
  $('schematic-import').onclick=()=>run($('schematic-import'),()=>importFiles('schematic'),true);
  $('local-refresh').onclick=()=>run($('local-refresh'),localFiles);
  window.LiteToolsPage=name=>{
    if(name==='local-files') { $('page-title').textContent='本地资源'; localFiles().catch(error=>{ $('local-target').textContent=error.message; showError(error); }); }
    if(name==='global-controls') { $('page-title').textContent='全局按键'; call('controls.read').then(showProfile).catch(showError); }
  };
})();
