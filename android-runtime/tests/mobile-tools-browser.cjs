// Real Chromium DOM/layout test with a fake native bridge, not an Android/game test.
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
const defaults={scale:100,opacity:85,projection:true,sprintToggle:true,sneakToggle:true,extraChat:false,bindings:{跳跃:32,攻击:-3,使用:-4,潜行:340,疾跑:341,背包:69,暂停:256,文字输入:84}};
if(process.type==='renderer') {
  window.testErrors=[]; window.addEventListener('error',e=>window.testErrors.push(e.message));
  window.testRequests=[]; let saved=defaults;
  window.LiteNative={request(id,action,json){const args=JSON.parse(json);window.testRequests.push({action,args});setTimeout(()=>{
    let data={};
    if(action==='state')data={ready:true,language:'zh',account:{name:'Test',mode:'offline'},instances:[{id:'fabric-test',name:'生存世界',version:'1.20.1',loader:'fabric'}],selected:'fabric-test'};
    if(action==='controls.read')data=saved;
    if(action==='controls.save')data=saved=args;
    if(action==='mods.list')data={items:[{name:'litematica.jar',size:1024000}]};
    if(action==='projection.status')data={installed:true,malilib:true,shortcut:'投影菜单',items:[{name:'樱花小屋.litematic',size:123456}]};
    if(action==='files.import')data={installed:['测试模组.jar'],failed:[{name:'已有.jar',error:'同名文件已存在，未覆盖'}]};
    window.LiteEvent('reply',{id,data,error:null});
  },10);}};
} else {
  app.whenReady().then(async()=>{
    const win=new BrowserWindow({width:430,height:920,show:false,webPreferences:{preload:__filename,contextIsolation:false,sandbox:false}});
    const evaluate=code=>win.webContents.executeJavaScript(code);
    const waitFor=async code=>{for(let i=0;i<100;i++){if(await evaluate(code))return;await new Promise(r=>setTimeout(r,25));}throw new Error('Timeout: '+code);};
    const screenshots=path.join(root,'build/reports/mobile-tools');fs.mkdirSync(screenshots,{recursive:true});
    try {
      await win.loadFile(path.join(root,'app_pojavlauncher/src/main/assets/litemc/index.html'));
      await waitFor('state.ready');
      assert.deepEqual(await evaluate('window.testErrors'),[]);
      await evaluate("page('global-controls')");
      await waitFor("document.querySelectorAll('#global-bindings select').length===8");
      assert.equal(await evaluate("getComputedStyle(document.getElementById('performance-mode')).display!=='none'"),true);
      assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'),true,'phone overflows');
      await evaluate("document.getElementById('global-scale').value='120'; document.querySelector('#global-bindings select').value='74'; document.getElementById('global-save').click()");
      await waitFor("document.getElementById('global-feedback').textContent.includes('已保存')");
      const save=await evaluate("window.testRequests.find(x=>x.action==='controls.save').args");
      assert.equal(save.scale,120);assert.equal(save.bindings['跳跃'],74);
      await evaluate("document.getElementById('global-scale').value='100';page('home');page('global-controls')");await waitFor("document.getElementById('global-scale').value==='120' && document.getElementById('global-scale-label').textContent==='120%'");
      fs.writeFileSync(path.join(screenshots,'controls-phone.png'),(await win.webContents.capturePage()).toPNG());
      await evaluate("page('local-files')");await waitFor("document.getElementById('projection-badge').textContent==='已安装 Litematica'");
      await evaluate("document.getElementById('local-mod-import').click()");await waitFor("document.getElementById('import-result').textContent.includes('同名文件')");
      await waitFor('!busy');
      const imported=await evaluate("window.testRequests.find(x=>x.action==='files.import').args");
      assert.deepEqual(imported,{instanceId:'fabric-test',kind:'mod'});
      assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'),true);
      fs.writeFileSync(path.join(screenshots,'files-phone.png'),(await win.webContents.capturePage()).toPNG());
      win.setSize(1180,850);await evaluate("page('global-controls')");await waitFor("document.querySelectorAll('#global-bindings select').length===8");
      await evaluate("document.querySelector('#global-controls details').open=true");
      assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'),true,'tablet overflows');
      fs.writeFileSync(path.join(screenshots,'controls-tablet.png'),(await win.webContents.capturePage()).toPNG());
      assert.deepEqual(await evaluate('window.testErrors'),[]);
      console.log('PASS Chromium phone/tablet layout, global save/reopen, import target/results, no script errors');app.exit(0);
    } catch(error) { console.error(error);app.exit(1); }
  });
}
