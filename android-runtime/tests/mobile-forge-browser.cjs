// Real Chromium + real UI scripts, with a deliberately fake native bridge.
// No Forge installer, external network, Android device or Minecraft is started.
// Run with ../node_modules/electron/dist/electron.exe tests/mobile-forge-browser.cjs.
const {app,BrowserWindow}=require('electron');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
if(process.type==='renderer') {
  window.testErrors=[];window.addEventListener('error',event=>window.testErrors.push(event.message));
  window.addEventListener('unhandledrejection',event=>window.testErrors.push(String(event.reason)));
  window.testRequests=[];window.testForgeFailure=false;window.testForgeDelay=10;window.testUnsupportedForge=false;
  window.LiteNative={request(id,action,json){
    const args=JSON.parse(json);window.testRequests.push({action,args});
    const fail=window.testForgeFailure,delay=action==='forge.versions'?window.testForgeDelay:10;
    setTimeout(()=>{
      let data={},error=null;
      if(action==='state')data={ready:true,language:'zh',account:{name:'Player',mode:'offline'},instances:[],selected:'',loaders:window.testUnsupportedForge?[{id:'forge',automaticInstall:false,reason:'TEST_FORGE_UNAVAILABLE'}]:[]};
      if(action==='catalog')data={items:['1.20.1','1.19.4','1.12.2'].map(id=>({id,date:'test',supported:true}))};
      if(action==='forge.versions') {
        if(fail)error='TEST_FORGE_NETWORK_ERROR';
        else if(args.version==='1.12.2')error='TEST_FORGE_REQUIRES_1_13';
        else data={items:(args.version==='1.20.1'?['47.3.22','47.2.0']:['45.3.0']).map(id=>({id,name:'Forge '+id}))};
      }
      // Intentional failure: tests must never render a fake completed installation.
      if(action==='install')error='TEST_INSTALL_NOT_EXECUTED';
      window.LiteEvent('reply',{id,data,error});
    },delay);
  }};
} else {
  app.whenReady().then(async()=>{
    const win=new BrowserWindow({width:430,height:920,show:false,webPreferences:{preload:__filename,contextIsolation:false,sandbox:false,backgroundThrottling:false}});
    let passed=0;
    const evaluate=code=>win.webContents.executeJavaScript(code);
    const waitFor=async code=>{for(let i=0;i<160;i++){if(await evaluate(code))return;await new Promise(resolve=>setTimeout(resolve,25));}throw new Error('Timeout: '+code);};
    const pass=name=>{passed++;console.log('PASS '+name);};
    const reports=path.join(root,'build/reports/mobile-forge');fs.mkdirSync(reports,{recursive:true});
    const capture=async name=>{await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');fs.writeFileSync(path.join(reports,name),(await win.webContents.capturePage()).toPNG());};
    // Product HTML is local; deliberately make unexpected network use fail closed.
    const external=[];
    win.webContents.session.webRequest.onBeforeRequest({urls:['http://*/*','https://*/*']},(details,callback)=>{external.push(details.url);callback({cancel:true});});
    try {
      await win.loadFile(path.join(root,'app_pojavlauncher/src/main/assets/litemc/index.html'));await waitFor('state.ready');
      assert.equal(await evaluate("typeof LiteForgeShow==='function' && typeof LiteForgeVersion==='function'"),true,'Forge module not loaded');
      for(const scope of ['version','download']) for(const suffix of ['options','version','refresh']) {
        assert.equal(await evaluate(`document.querySelectorAll('#${scope}-forge-${suffix}').length`),1,`missing/duplicate ${scope}-${suffix}`);
      }
      pass('Forge module and static/dynamic controls are registered exactly once');

      await evaluate("document.querySelector('[data-loader=forge]').click();$('version-forge-refresh').click()");await waitFor('!busy');
      assert.equal(await evaluate("testRequests.filter(r=>r.action==='forge.versions').length"),0,'metadata request made before Minecraft catalog loaded');
      assert.equal(await evaluate("$('version-forge-options').hidden"),false);
      pass('Forge requires loaded Minecraft catalog before requesting official versions');

      await evaluate("page('versions')");await waitFor("$('version-select').dataset.loaded==='true'");
      await evaluate("$('version-forge-refresh').click()");await waitFor("!busy && $('version-forge-version').options.length===3");
      assert.deepEqual(await evaluate("testRequests.filter(r=>r.action==='forge.versions').at(-1).args"),{version:'1.20.1'});
      assert.equal(await evaluate("$('version-forge-version').value"),'');
      await evaluate("$('version-forge-version').value='47.3.22';$('install').click()");await waitFor('!busy');
      assert.deepEqual(await evaluate("testRequests.filter(r=>r.action==='install').at(-1).args"),{version:'1.20.1',loader:'forge',loaderVersion:'47.3.22'});
      assert.equal(await evaluate("$('toast').textContent"),'TEST_INSTALL_NOT_EXECUTED');
      pass('collection page queries exact Minecraft version and sends pinned Forge version');
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'phone Forge controls overflow');
      await capture('forge-versions-phone.png');

      await evaluate("$('version-forge-version').value='';$('install').click()");await waitFor('!busy');
      assert.equal(await evaluate("testRequests.filter(r=>r.action==='install').at(-1).args.loaderVersion"),'');
      await evaluate("document.querySelector('[data-loader=fabric]').click()");
      assert.equal(await evaluate("$('version-forge-options').hidden"),true);
      await evaluate("document.querySelector('[data-loader=forge]').click();$('version-select').value='1.19.4';$('version-select').dispatchEvent(new Event('change'))");
      assert.equal(await evaluate("$('version-forge-version').options.length"),1);
      assert.equal(await evaluate("LiteForgeVersion('version')"),'');
      pass('official default is preserved and changing Minecraft clears pinned Forge choice');

      await evaluate("window.testForgeFailure=true;$('version-forge-refresh').click()");await waitFor('!busy');
      assert.equal(await evaluate("$('toast').textContent"),'TEST_FORGE_NETWORK_ERROR');
      assert.equal(await evaluate("$('version-forge-refresh').disabled"),false,'failed refresh cannot retry');
      await evaluate("window.testForgeFailure=false;$('version-forge-refresh').click()");await waitFor("!busy && $('version-forge-version').options.length===2");
      assert.equal(await evaluate("$('version-forge-version').options[1].value"),'45.3.0');
      pass('failed official-version request reports error and allows retry');

      await evaluate("window.testForgeDelay=150;$('version-forge-refresh').click();$('version-select').value='1.20.1';$('version-select').dispatchEvent(new Event('change'))");await waitFor('!busy');
      assert.equal(await evaluate("$('version-forge-version').options.length"),1,'stale metadata from previous Minecraft inserted');
      assert.equal(await evaluate("LiteForgeVersion('version')"),'');
      await evaluate('window.testForgeDelay=10');
      pass('late official-version response cannot populate another Minecraft version');

      await evaluate("page('downloads');document.querySelector('[data-download-loader=forge]').click();$('download-forge-refresh').click()");await waitFor("!busy && $('download-forge-version').options.length===3");
      assert.equal(await evaluate("$('download-forge-options').hidden"),false);
      await evaluate("$('download-name').value='测试 Forge 实例';$('download-forge-version').value='47.2.0';$('download-install').click()");await waitFor('!busy');
      assert.deepEqual(await evaluate("testRequests.filter(r=>r.action==='install').at(-1).args"),{version:'1.20.1',loader:'forge',loaderVersion:'47.2.0',name:'测试 Forge 实例'});
      assert.equal(await evaluate("$('downloads').classList.contains('active')"),true,'failed install navigated to success');
      await capture('forge-download-phone.png');
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'phone download Forge controls overflow');
      pass('download page sends independent pinned Forge choice and keeps failure visible');

      await evaluate("$('download-version-control').value='1.19.4';$('download-version-control').dispatchEvent(new Event('change'))");
      assert.equal(await evaluate("$('download-forge-version').options.length"),1);
      assert.equal(await evaluate("LiteForgeVersion('download')"),'');
      await evaluate("$('download-forge-refresh').click()");await waitFor("!busy && $('download-forge-version').options.length===2");
      assert.deepEqual(await evaluate("testRequests.filter(r=>r.action==='forge.versions').at(-1).args"),{version:'1.19.4'});
      await evaluate("$('download-forge-version').value='45.3.0';$('version-select').value='1.20.1';$('version-select').dispatchEvent(new Event('change'))");
      assert.equal(await evaluate("LiteForgeVersion('download')"),'','synchronized Minecraft selection retained a wrong Forge version');
      assert.equal(await evaluate("$('download-forge-version').options.length"),1,'download UI still displays old Minecraft Forge versions after synchronization');
      pass('download choices reset for direct and synchronized Minecraft changes');

      await evaluate("$('download-version-control').value='1.12.2';$('download-version-control').dispatchEvent(new Event('change'));$('download-forge-refresh').click()");await waitFor('!busy');
      assert.equal(await evaluate("$('toast').textContent"),'TEST_FORGE_REQUIRES_1_13');
      assert.equal(await evaluate("$('download-forge-version').options.length"),1);
      pass('native legacy-Forge rejection is displayed without fabricated version choices');

      await evaluate("document.querySelector('[data-loader=vanilla]').click();document.querySelector('[data-download-loader=vanilla]').click();window.testUnsupportedForge=true;refresh()");
      await waitFor("state.loaders.length===1");
      await evaluate("document.querySelector('[data-loader=forge]').click();document.querySelector('[data-download-loader=forge]').click()");
      assert.equal(await evaluate('loader'),'vanilla');
      assert.equal(await evaluate("$('version-forge-options').hidden && $('download-forge-options').hidden"),true);
      assert.equal(await evaluate("$('toast').textContent"),'TEST_FORGE_UNAVAILABLE');
      pass('explicit unsupported native capability overrides Forge fallback on both pages');

      win.setSize(1180,850);await evaluate("window.testUnsupportedForge=false;refresh()");await waitFor('state.loaders.length===0');
      await evaluate("$('download-version-control').value='1.20.1';$('download-version-control').dispatchEvent(new Event('change'));document.querySelector('[data-download-loader=forge]').click();$('download-forge-refresh').click()");await waitFor("!busy && $('download-forge-version').options.length===3");
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'tablet Forge controls overflow');
      await capture('forge-download-tablet.png');
      assert.deepEqual(await evaluate('window.testErrors'),[]);assert.deepEqual(external,[],'UI attempted an external request');
      pass('phone/tablet layout has no horizontal overflow, JS errors or external UI requests');
      console.log(`Forge browser regression: ${passed}/${passed} passed. Mock bridge only; real Forge/Android/game execution remains unverified.`);app.exit(0);
    }catch(error){console.error(`FAIL after ${passed} passes`,error);app.exit(1);}
  });
}
