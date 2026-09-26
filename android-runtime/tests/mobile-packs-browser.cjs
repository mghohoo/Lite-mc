// Actual Chromium UI + fake bridge. Does not claim Android/game execution.
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
if(process.type==='renderer') {
  window.testErrors=[];window.addEventListener('error',e=>window.testErrors.push(e.message));
  window.testRequests=[];window.testFail=false;window.testCancel=false;
  window.LiteNative={request(id,action,json){const args=JSON.parse(json);window.testRequests.push({action,args});setTimeout(()=>{
    let data={},error=null;
    if(action==='state')data={ready:true,language:'zh',account:{name:'Player',mode:'offline'},instances:[],selected:''};
    if(action==='mods.search') {
      if(window.testFail)error='网络暂时不可用，请重试。';
      else data={items:[{id:'abc',title:'测试优化整合包 <img onerror=alert(1)>',description:'独立版本，不覆盖你原来的游戏。',downloads:1234567,kind:'modpack'}]};
    }
    if(action==='mods.packVersions')data={items:[{id:'fabric-v',name:'稳定版 Fabric',gameVersions:['1.20.1'],loaders:['fabric']},{id:'forge-v',name:'Forge 特别版',gameVersions:['1.21.1'],loaders:['forge']},{id:'quilt-v',name:'Quilt 特别版',gameVersions:['1.19.4'],loaders:['quilt']}]};
    if(action==='packs.download')data=window.testCancel?{cancelled:true}:{saved:true,name:'测试整合包.mrpack'};
    if(action==='packs.install')error='测试注入：某个 Mod 下载校验失败，未提交新实例。';
    window.LiteEvent('reply',{id,data,error});
  },10);}};
} else {
  app.whenReady().then(async()=>{
    const win=new BrowserWindow({width:430,height:920,show:false,webPreferences:{preload:__filename,contextIsolation:false,sandbox:false,backgroundThrottling:false}});
    const evaluate=code=>win.webContents.executeJavaScript(code);
    const waitFor=async code=>{for(let i=0;i<100;i++){if(await evaluate(code))return;await new Promise(r=>setTimeout(r,25));}throw new Error('Timeout '+code);};
    const screenshots=path.join(root,'build/reports/mobile-packs');fs.mkdirSync(screenshots,{recursive:true});
    const capture=async name=>{await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');await new Promise(r=>setTimeout(r,120));fs.writeFileSync(path.join(screenshots,name),(await win.webContents.capturePage()).toPNG());};
    try {
      await win.loadFile(path.join(root,'app_pojavlauncher/src/main/assets/litemc/index.html'));await waitFor('state.ready');
      await evaluate("page('mods');$('mod-kind').value='modpack';$('mod-kind').onchange();$('mod-search').click()");
      await waitFor("!busy && $('mod-results').children.length===1");
      const search=await evaluate("window.testRequests.find(r=>r.action==='mods.search').args");
      assert.deepEqual(search,{provider:'modrinth',kind:'modpack',query:''},'search must not carry selected version/instance');
      assert.equal(await evaluate("$('mod-results').querySelector('img')===null"),true,'title was interpreted as markup');
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'phone search overflow');
      await capture('search-phone.png');
      await evaluate("$('mod-results').querySelector('button').click()");await waitFor("!busy && $('pack-version').options.length===3");
      assert.equal(await evaluate("$('pack-detail').classList.contains('active')"),true);
      assert.equal(await evaluate("$('pack-install').disabled"),false);
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'phone pack overflow');
      await capture('versions-phone.png');
      await evaluate("$('pack-game-filter').value='1.21.1';$('pack-game-filter').onchange()");
      assert.equal(await evaluate("$('pack-install').disabled"),false,'modern Forge pack install must be enabled');
      await evaluate("$('pack-install').click()");await waitFor("!busy && $('pack-status').textContent.includes('校验失败')");
      const forgeInstall=await evaluate("window.testRequests.find(r=>r.action==='packs.install').args");
      assert.equal(forgeInstall.versionId,'forge-v');assert.equal(forgeInstall.projectId,'abc');
      assert.equal(await evaluate("$('pack-detail').classList.contains('active')"),true,'failed Forge install must not show success page');
      await evaluate("$('pack-game-filter').value='1.19.4';$('pack-game-filter').onchange()");
      assert.equal(await evaluate("$('pack-install').disabled"),true,'unsupported Quilt install must be disabled');
      assert.equal(await evaluate("$('pack-download').disabled"),false,'Quilt archive download stays available');
      await evaluate("$('pack-download').click()");await waitFor("!busy && $('pack-status').textContent.includes('已保存')");
      const download=await evaluate("window.testRequests.find(r=>r.action==='packs.download').args");
      assert.equal(download.versionId,'quilt-v');assert.equal(download.projectId,'abc');assert.equal(download.instanceId,undefined);
      await evaluate("window.testCancel=true;$('pack-download').click()");await waitFor("!busy && $('pack-status').textContent.includes('已取消')");
      await evaluate("$('pack-game-filter').value='1.20.1';$('pack-game-filter').onchange();$('pack-install').click()");
      await waitFor("!busy && $('pack-status').textContent.includes('校验失败')");
      assert.equal(await evaluate("$('pack-detail').classList.contains('active')"),true,'failed install must not show success page');
      await evaluate("page('mods');window.testFail=true;$('mod-search').click()");await waitFor("!busy && $('mod-feedback').textContent.includes('网络')");
      assert.equal(await evaluate("$('mod-results').children.length"),0,'stale search results retained after failure');
      await evaluate("window.testFail=false;$('mod-query').value='优化';$('mod-query').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter'}))");await waitFor("!busy && $('mod-results').children.length===1");
      win.setSize(1180,850);await evaluate("$('mod-results').querySelector('button').click()");await waitFor("!busy && $('pack-version').options.length===3");
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'tablet pack overflow');
      await capture('versions-tablet.png');
      assert.deepEqual(await evaluate('window.testErrors'),[]);
      console.log('PASS no-instance search, popular search, version page, pinned download, enabled Forge / unsupported Quilt, cancel/error/retry, XSS safety, phone/tablet layout');app.exit(0);
    }catch(error){console.error(error);app.exit(1);}
  });
}
