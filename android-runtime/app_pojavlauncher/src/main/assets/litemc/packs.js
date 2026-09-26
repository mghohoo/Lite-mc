'use strict';
(() => {
  let project, source, versions = [];
  const current = () => versions.find(item => item.id === $('pack-version').value);
  function controls() {
    const entry = current();
    const loaders = entry ? entry.loaders || [] : [];
    const supported = entry && (loaders.length === 0 || loaders.some(item => ['fabric','vanilla','minecraft','forge'].includes(item)));
    $('pack-install').disabled = busy || !supported;
    $('pack-download').disabled = busy || !entry;
    $('pack-retry').disabled = busy || !project;
    $('pack-game-filter').disabled = busy;
    $('pack-version').disabled = busy || !$('pack-version').options.length;
    $('pack-name').disabled = busy;
    $('pack-compatibility').textContent = !entry ? '没有可用版本。试试其他 Minecraft 版本，或刷新重试。'
      : 'Minecraft ' + (entry.gameVersions || []).join(' / ') + ' · ' + (loaders.join(' / ') || '以包内清单为准')
        + (supported ? '。安装时会再次核对包内清单及运行层兼容性。' : '。当前 APK 暂不能自动安装此加载器，可以先下载整合包文件。');
  }
  function renderVersions() {
    $('pack-version').replaceChildren();
    const game = $('pack-game-filter').value;
    for (const entry of versions) {
      if (game && !(entry.gameVersions || []).includes(game)) continue;
      const option = node('option','',entry.name + ' · MC ' + (entry.gameVersions || []).join(' / '));
      option.value = entry.id; $('pack-version').append(option);
    }
    $('pack-version').disabled = !$('pack-version').options.length;
    controls();
  }
  async function loadVersions() {
    versions = []; $('pack-version').replaceChildren(); controls();
    $('pack-status').textContent = '正在读取官方发布版本…';
    try {
      const result = await call('mods.packVersions',{provider:source,projectId:project.id});
      versions = result.items || [];
      $('pack-game-filter').replaceChildren();
      const all = node('option','','全部 Minecraft 版本'); all.value = ''; $('pack-game-filter').append(all);
      const games = [...new Set(versions.flatMap(item => item.gameVersions || []))];
      for (const game of games) { const option = node('option','',game); option.value = game; $('pack-game-filter').append(option); }
      renderVersions();
      $('pack-status').textContent = versions.length ? `已加载 ${versions.length} 个发布版本。请先选版本，再选择安装或保存文件。` : '此项目暂时没有可下载的发布版本。';
    } catch (error) { $('pack-status').textContent = error.message; throw error; }
  }
  window.LiteOpenPack = (item,args,button) => run(button,async () => {
    project = item; source = args.provider;
    $('pack-icon').replaceChildren(modIcon(item)); $('pack-title').textContent = item.title || item.id;
    $('pack-source').textContent = source.toUpperCase(); $('pack-meta').textContent = Number(item.downloads || 0).toLocaleString() + ' 次下载';
    $('pack-description').textContent = item.description || ''; $('pack-name').value = '';
    page('pack-detail'); await loadVersions();
  });
  $('pack-game-filter').onchange = renderVersions;
  window.LitePackControls = controls;
  $('pack-version').onchange = () => { $('pack-status').textContent = ''; controls(); };
  $('pack-retry').onclick = () => run($('pack-retry'),loadVersions);
  async function perform(action) {
    const entry = current(); if (!entry) throw new Error('请先选择一个整合包版本。');
    controls();
    $('pack-status').textContent = action === 'packs.install' ? '正在安装独立实例，请保持应用在前台…' : '正在下载归档，完成后请选择保存位置…';
    try {
      const result = await call(action,{provider:source,projectId:project.id,versionId:entry.id,name:$('pack-name').value.trim()});
      if (result.cancelled) { $('pack-status').textContent = '已取消保存，下载缓存保留，再次下载可以复用。'; return; }
      if (action === 'packs.install') {
        await refresh(); $('pack-status').textContent = '安装完成，已加入“我的版本”。';
        toast('整合包已安装为独立实例。'); page('versions');
      } else { $('pack-status').textContent = '已保存：' + result.name; toast('整合包文件已保存到你选择的位置。'); }
    } catch (error) { $('pack-status').textContent = '未完成：' + error.message; throw error; }
    finally { controls(); }
  }
  $('pack-download').onclick = () => run($('pack-download'),() => perform('packs.download'),true);
  $('pack-install').onclick = () => run($('pack-install'),() => perform('packs.install'),true);
})();
