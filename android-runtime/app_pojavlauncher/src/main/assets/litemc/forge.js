'use strict';
(() => {
  const setups = {};
  for (const scope of ['version','download']) {
    const get = suffix => document.getElementById(scope+'-forge-'+suffix);
    const base = document.getElementById(scope==='version' ? 'version-select' : 'download-version-control');
    if (!base || !get('options')) continue;
    const select = get('version');
    function reset() {
      select.replaceChildren(); const option=node('option','','官方推荐 / 最新'); option.value='';select.append(option);
      select.dataset.minecraft=base.value;
    }
    reset(); base.addEventListener('change',reset);
    get('refresh').onclick=()=>run(get('refresh'),async()=>{
      const version=base.value;
      if (!version || !$('version-select').dataset.loaded) throw new Error('请先加载并选择 Minecraft 版本。');
      const result=await call('forge.versions',{version});
      if(base.value!==version)return;
      reset();
      for(const entry of result.items || []) {
        const option=node('option','',entry.name || entry.id);option.value=entry.id;select.append(option);
      }
      toast(result.items && result.items.length ? '已加载官方 Forge 版本。' : '官方源没有此 Minecraft 版本的 Forge 安装器。');
    });
    setups[scope]={base,select,options:get('options'),reset};
  }
  window.LiteForgeShow=(scope,loader)=>{const entry=setups[scope];if(entry){entry.options.hidden=loader!=='forge';if(entry.select.dataset.minecraft!==entry.base.value)entry.reset();}};
  window.LiteForgeVersion=scope=>{const entry=setups[scope];return entry && entry.select.dataset.minecraft===entry.base.value ? entry.select.value : '';};
})();
