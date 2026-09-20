import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

// Executes the real product JS in a deliberately small DOM model. This is not a
// browser, layout/accessibility test, WebView test, network test or game launch.
// No dependencies, account credentials, Android device or network are involved.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const asset = 'app_pojavlauncher/src/main/assets/litemc/';
const java = 'app_pojavlauncher/src/main/java/com/litemc/launcher/';
const read = relative => readFileSync(path.join(root, relative), 'utf8');
const html = read(asset + 'index.html');
const app = read(asset + 'app.js');
const skin = read(asset + 'skin.js');
const activity = read(java + 'LiteActivity.java');
const serviceSource = ['LiteActivity.java', 'LiteAccounts.java', 'LiteMods.java'].map(file => read(java + file)).join('\n');
const controls = JSON.parse(read(asset + 'controls.json'));
const decode = value => value.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");

class Element {
  constructor(tag, attrs = {}) {
    this.tagName = tag.toUpperCase(); this.attrs = {...attrs}; this.children = []; this.parent = null;
    this.dataset = {}; this.listeners = new Map(); this._text = ''; this._value = attrs.value;
    this.disabled = 'disabled' in attrs; this.hidden = 'hidden' in attrs; this.checked = 'checked' in attrs;
    for (const [key,value] of Object.entries(attrs)) if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_,letter) => letter.toUpperCase())] = value;
    this.classList = { contains: name => this.className.split(/\s+/).includes(name), toggle: (name, value) => {
      const names = new Set(this.className.split(/\s+/).filter(Boolean));
      const add = value === undefined ? !names.has(name) : value;
      if (add) names.add(name); else names.delete(name);
      this.className = [...names].join(' '); return add;
    }};
  }
  get id() { return this.attrs.id || ''; }
  set id(value) { this.attrs.id = value; }
  get className() { return this.attrs.class || ''; }
  set className(value) { this.attrs.class = value; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this.children.forEach(child => child.parent = null); this.children = []; this._text = String(value); }
  get value() {
    if (this.tagName === 'SELECT') {
      const options = this.children.filter(child => child.tagName === 'OPTION');
      if (this._value !== undefined) return options.some(option => option.value === this._value) ? this._value : '';
      return options[0]?.value || '';
    }
    if (this.tagName === 'OPTION') return this._value ?? this.textContent;
    return this._value ?? '';
  }
  set value(value) { this._value = String(value); }
  append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } }
  replaceChildren(...children) { this.textContent = ''; if (this.tagName === 'SELECT') this._value = undefined; this.append(...children); }
  removeAttribute(name) { delete this.attrs[name]; if (name === 'value') this._value = undefined; }
  addEventListener(name, listener) { if (!this.listeners.has(name)) this.listeners.set(name, []); this.listeners.get(name).push(listener); }
  async dispatch(name) {
    if (this.disabled) return;
    const event = {preventDefault(){}, target:this};
    if (this['on'+name]) await this['on'+name](event);
    for (const listener of this.listeners.get(name) || []) await listener(event);
  }
  all() { return this.children.flatMap(child => [child, ...child.all()]); }
  matches(selector) {
    if (selector.startsWith('.')) return selector.slice(1).split('.').every(name => this.classList.contains(name));
    const match = selector.match(/^(\w+)?\[([\w-]+)(?:(\^?=)["']([^"']*)["'])?\]$/);
    if (match) {
      const [,tag,key,operator,value] = match;
      if (tag && this.tagName !== tag.toUpperCase()) return false;
      if (!Object.hasOwn(this.attrs,key)) return false;
      return !operator || (operator === '^=' ? this.attrs[key].startsWith(value) : this.attrs[key] === value);
    }
    return this.tagName === selector.toUpperCase();
  }
  querySelectorAll(selector) { return this.all().filter(element => selector.split(',').some(part => element.matches(part.trim()))); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

function documentFromHtml(source) {
  const document = new Element('document');
  const stack = [document];
  const voidTags = new Set(['meta','link','input','br','img','hr']);
  for (const match of source.matchAll(/<!--[^]*?-->|<![^>]*>|<\/?[A-Za-z][^>]*>|[^<]+/g)) {
    const token = match[0];
    if (token.startsWith('<!')) continue;
    if (token.startsWith('</')) { stack.pop(); continue; }
    if (!token.startsWith('<')) { stack.at(-1)._text += decode(token); continue; }
    const tag = token.match(/^<([\w-]+)/)[1].toLowerCase();
    const attributes = {};
    const raw = token.slice(tag.length+1, -1);
    for (const attribute of raw.matchAll(/([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) attributes[attribute[1]] = decode(attribute[2] ?? attribute[3] ?? attribute[4] ?? '');
    const element = new Element(tag, attributes); stack.at(-1).append(element);
    if (!voidTags.has(tag) && !token.endsWith('/>')) stack.push(element);
  }
  document.createElement = tag => new Element(tag);
  document.getElementById = id => document.all().find(element => element.id === id) || null;
  document.documentElement = document.querySelector('html');
  document.hidden = false;
  return document;
}

const settle = async () => { for (let count = 0; count < 5; count++) await new Promise(resolve => setImmediate(resolve)); };
async function harness(overrides = {}) {
  const document = documentFromHtml(html);
  const requests = [], timers = new Map(); let timer = 0;
  const fixture = {ready:true, account:{name:'Test_Player', mode:'offline', model:'CLASSIC'}, instances:[{id:'fabric-test', version:'1.20.1', loader:'fabric', loaderVersion:'0.16.0'}], selected:'fabric-test', language:'zh', memory:2048, motion:true, model:'classic', controlScale:100, ...overrides};
  const responses = {
    state: () => structuredClone(fixture),
    catalog: () => ({items:[{id:'1.20.1',date:'2023-06-12'}],cached:false}),
    'skin.read': () => ({}),
    'skin.pick': () => ({base64:'TEST_PNG'}),
    'mods.search': () => ({items:[{id:'test-mod',title:'<img onerror=attack()>',description:'<script>not executable</script>'}]}),
    'mods.list': () => ({items:[{name:'test.jar',size:1048576}]}),
    'accounts.login.start': () => ({flowId:'test-flow',userCode:'TEST-CODE',interval:5}),
    'accounts.login.poll': () => ({pending:true,interval:5}),
    'accounts.offline': args => { fixture.account = {mode:'offline',name:args.name}; return fixture.account; },
    settings: args => { Object.assign(fixture,args); return {saved:true}; }
  };
  const context = vm.createContext({document, console, structuredClone, setTimeout(fn,ms){const id=++timer;timers.set(id,{fn,ms});return id;},clearTimeout(id){timers.delete(id);},scrollTo(){},LiteSkin:{model:'classic',motion:true,load:async () => {}},location:{reload(){throw new Error('Language switch must not reload');}}});
  context.window = context;
  context.LiteNative = {request(id, action, payload) {
    const args = JSON.parse(payload); requests.push({action,args});
    queueMicrotask(() => {
      try { const data = responses[action] ? responses[action](args) : {}; context.LiteEvent('reply',{id,data,error:null}); }
      catch { context.LiteEvent('reply',{id,data:null,error:'Synthetic test failure'}); }
    });
  }};
  vm.runInContext(app, context, {filename:'app.js'});
  await settle();
  return {document, context, requests, fixture, timers, responses, get:id=>document.getElementById(id), run:code=>vm.runInContext(code,context)};
}

const results = [];
async function test(name, callback) {
  try { await callback(); results.push(true); console.log(`PASS ${name}`); }
  catch (error) { results.push(false); console.error(`FAIL ${name}: ${error.message}`); }
}

await test('all literal DOM IDs exist once', () => {
  const doc = documentFromHtml(html), ids = doc.all().filter(node=>node.id).map(node=>node.id);
  assert.equal(new Set(ids).size,ids.length,'Duplicate HTML IDs');
  for (const [,id] of app.matchAll(/\$\('([^']+)'\)/g)) assert.ok(ids.includes(id),`Missing #${id}`);
});
await test('translation targets do not contain interactive descendants', () => {
  const doc = documentFromHtml(html);
  for (const element of doc.querySelectorAll('[data-zh]')) {
    assert.equal(element.all().filter(child=>['INPUT','SELECT','BUTTON','CANVAS','TEXTAREA'].includes(child.tagName)).length,0,`${element.tagName} translation would delete a control`);
  }
});
await test('every UI native action has an explicit native handler', () => {
  const actions = [...app.matchAll(/call\('([^']+)'/g)].map(match=>match[1]);
  const handled = new Set([...serviceSource.matchAll(/(?:action\.equals\("([^"]+)"\)|"([^"]+)"\.equals\(action\))/g)].map(match=>match[1]||match[2]));
  assert.deepEqual([...new Set(actions)].filter(action=>!handled.has(action)),[], 'UI calls unsupported native actions');
});
await test('initial refresh preserves version selector and enables valid launch', async () => {
  const h=await harness(); assert.ok(h.get('version-select'),'Translation removed version selector'); assert.equal(h.get('launch').disabled,false);
});
await test('version navigation loads catalog and installation sends chosen loader', async () => {
  const h=await harness(); await h.document.querySelector('[data-page="versions"]').dispatch('click'); await settle();
  assert.equal(h.get('version-select').value,'1.20.1');
  await h.document.querySelector('[data-loader="fabric"]').dispatch('click'); await h.get('install').dispatch('click'); await settle();
  assert.ok(h.requests.some(request=>request.action==='install'&&request.args.loader==='fabric'&&request.args.version==='1.20.1'));
});
await test('language change updates in place without removing controls', async () => {
  const h=await harness(); h.get('language').value='en'; await h.get('language').dispatch('change');
  assert.equal(h.document.documentElement.lang,'en'); assert.ok(h.get('version-select')); assert.equal(h.get('account-name').textContent,'Test_Player');
  await h.get('save-settings').dispatch('click'); await settle(); assert.equal(h.fixture.language,'en');
});
await test('unavailable loader displays native capability reason without changing selection', async () => {
  const h=await harness({loaders:[{id:'forge',automaticInstall:false,reason:'TEST_FORGE_ANDROID_REASON'}]});
  await h.document.querySelector('[data-loader="forge"]').dispatch('click');
  assert.equal(h.get('toast').textContent,'TEST_FORGE_ANDROID_REASON');
  assert.equal(h.run('loader'),'vanilla');
  assert.equal(h.document.querySelector('[data-loader="forge"]').querySelector('small').textContent,'安卓暂不可自动安装');
  assert.ok(!h.requests.some(request=>request.action==='install'));
});
await test('loader labels include Fabric API and do not promise unsupported integrations', async () => {
  const h=await harness();
  assert.equal(h.document.querySelector('[data-loader="fabric"]').querySelector('small').textContent,'自动安装 Fabric API');
  assert.ok(!html.includes('即将支持')); assert.ok(!app.includes('将在运行核心适配后开放'));
  for (const id of ['vanilla','fabric','forge','liteloader','optifine']) assert.ok(app.includes(`data-download-loader="${id}"`));
});
await test('offline name validation and native request', async () => {
  const h=await harness(); h.get('offline-name').value='!bad'; await h.get('save-offline').dispatch('click');
  assert.ok(!h.requests.some(request=>request.action==='accounts.offline'));
  h.get('offline-name').value='Lite_Tester'; await h.get('save-offline').dispatch('click'); await settle();
  assert.ok(h.requests.some(request=>request.action==='accounts.offline'&&request.args.name==='Lite_Tester'));
  assert.equal(h.get('account-name').textContent,'Lite_Tester');
});
await test('launch disabled with no installed instance or unready runtime', async () => {
  for (const fixture of [{instances:[],selected:''},{ready:false}]) { const h=await harness(fixture); assert.equal(h.get('launch').disabled,true); }
});
await test('existing 26.x instance stays visible but cannot launch or select', async () => {
  const h=await harness({instances:[{id:'vanilla-26.3-old',version:'26.3',loader:'vanilla'}],selected:'vanilla-26.3-old'});
  assert.equal(h.get('launch').disabled,true); assert.equal(h.get('compatibility-warning').hidden,false);
  assert.ok(h.get('compatibility-warning').textContent.includes('1.21.x'));
  assert.ok(h.get('instance-list').textContent.includes('26.3'));
  assert.equal(h.get('instance-list').querySelector('button').disabled,true);
  await h.get('launch').onclick(); await settle();
  assert.ok(!h.requests.some(request=>request.action==='launch'));
});
await test('catalog disables 26.x, prefers 1.21.x and rejects programmatic installation', async () => {
  const h=await harness(); h.responses.catalog=()=>({items:[{id:'26.3',date:'2026-09-01',supported:false,reason:'26.x incompatible; choose 1.21.x'},{id:'1.21.11',date:'2025-12-01'},{id:'1.20.1',date:'2023-06-12'}]});
  await h.run('catalog(false)'); await settle();
  assert.equal(h.get('version-select').value,'1.21.11');
  assert.equal(h.get('version-select').children.find(item=>item.value==='26.3').disabled,true);
  h.get('version-select').value='26.3'; await h.get('version-select').dispatch('change');
  assert.equal(h.get('install').disabled,true);
  await h.get('install').onclick(); await settle();
  assert.ok(!h.requests.some(request=>request.action==='install'));
  assert.equal(h.get('install').disabled,true);
});
await test('catalog containing only incompatible versions leaves install disabled', async () => {
  const h=await harness(); h.responses.catalog=()=>({items:[{id:'26.3',date:'2026-09-01'}]});
  await h.run('catalog(false)'); assert.equal(h.get('version-select').value,'');assert.equal(h.get('install').disabled,true);
});
await test('Mod search renders untrusted content as text and installs to selected instance', async () => {
  const h=await harness(); h.get('mod-query').value='test'; await h.get('mod-search').dispatch('click'); await settle();
  assert.ok(h.get('mod-results').textContent.includes('<img onerror=attack()>'));
  assert.equal(h.get('mod-results').querySelectorAll('img').length,0);
  await h.get('mod-results').querySelector('button').dispatch('click'); await settle();
  const request=h.requests.find(request=>request.action==='mods.install'); assert.equal(request.args.instanceId,'fabric-test'); assert.equal(request.args.projectId,'test-mod');
});
await test('Mod folder and skin buttons are connected', async () => {
  const h=await harness(); await h.get('mod-files').dispatch('click'); await settle(); assert.ok(h.get('mod-file-list').textContent.includes('test.jar'));
  await h.get('pick-skin').dispatch('click'); await h.document.querySelector('[data-model="slim"]').dispatch('click'); await h.get('upload-skin').dispatch('click'); await settle();
  assert.ok(h.requests.some(request=>request.action==='accounts.skin'&&request.args.model==='slim'&&request.args.base64==='TEST_PNG'));
});
await test('official Alex model is not overwritten by default local Steve preference', async () => {
  const h=await harness({account:{mode:'online',name:'Alex_Test',model:'SLIM'},model:'classic'});
  assert.equal(h.context.LiteSkin.model,'slim','Official SLIM skin must use 3px arms when no local replacement is selected');
});
await test('login polling uses only public flow ID and clears on offline switch', async () => {
  const h=await harness(); await h.get('login').dispatch('click'); await settle(); assert.equal(h.get('login-code').textContent,'TEST-CODE');
  await h.get('poll-login').dispatch('click'); assert.ok(h.requests.some(request=>request.action==='accounts.login.poll'&&request.args.flowId==='test-flow'));
  await h.get('save-offline').dispatch('click'); await settle(); assert.equal(h.get('login-flow').hidden,true); assert.equal(h.run('loginFlow'),'');
});
await test('touch size is included in native state response', () => {
  const stateBranch=activity.slice(activity.indexOf('if (action.equals("state"))'),activity.indexOf('if (action.startsWith("accounts."))'));
  assert.match(stateBranch,/"controlScale"/,'controlScale persisted by settings must survive refresh');
});
await test('controls JSON includes joystick, keyboard, jump, attack, use and pause', () => {
  assert.equal(controls.version,8); assert.ok(controls.mJoystickDataList.length>0);
  const buttons=controls.mControlDataList;
  for (const code of [32,-3,-4,69,256,-9,341,84]) assert.ok(buttons.some(button=>button.keycodes.includes(code)),`Missing key ${code}`);
  assert.ok(buttons.some(button=>button.keycodes.includes(-1)),'Mobile layout must include a dedicated IME keyboard button');
  for (const button of [...buttons,...controls.mJoystickDataList]) { assert.ok(button.width>0&&button.height>0); assert.equal(button.keycodes.length,4); assert.equal(typeof button.dynamicX,'string'); assert.equal(typeof button.dynamicY,'string'); }
});
await test('remote UI requests are intercepted with a blocking fallback', () => {
  assert.match(activity,/shouldOverrideUrlLoading\([^]*?return true;/);
  assert.match(activity,/"https"\.equals\(uri\.getScheme\(\)\)/);
  assert.match(activity,/"appassets\.androidplatform\.net"\.equals\(uri\.getHost\(\)\)/);
  assert.match(activity,/getAssets\(\)\.open\(path\.substring\(1\)\)/);
  assert.match(activity,/403,\s*"Blocked"/,'Remote requests need a blocking response, even after Java formatting');
  assert.match(html,/connect-src 'none'/); assert.doesNotMatch(html,/<script\b[^>]*src=["']https?:/);
});
await test('skin renderer uses 4px/3px arms and restrained idle angles', () => {
  const calls=[], frames=[];
  const drawing=new Proxy({}, {get(target,key){if(key==='drawImage'||key==='rotate')return(...args)=>calls.push({key,args});return target[key]??(()=>{});},set(target,key,value){target[key]=value;return true;}});
  const canvas={width:320,height:280,offsetParent:{},getContext:()=>drawing};
  const context=vm.createContext({document:{hidden:false,createElement:()=>({width:64,height:64,getContext:()=>drawing}),querySelectorAll:()=>[canvas]},requestAnimationFrame:fn=>frames.push(fn),matchMedia:()=>({matches:false}),Math,Image:function(){}});
  context.window=context; vm.runInContext(skin,context,{filename:'skin.js'});
  for(const [model,width] of [['classic',4],['slim',3]]) {
    calls.length=0; context.LiteSkin.model=model; frames.shift()(5000);
    const right=calls.find(call=>call.key==='drawImage'&&call.args[1]===44&&call.args[2]===20);
    const left=calls.find(call=>call.key==='drawImage'&&call.args[1]===36&&call.args[2]===52);
    assert.equal(right?.args[3],width);assert.equal(left?.args[3],width);
    assert.ok(calls.filter(call=>call.key==='rotate').every(call=>Math.abs(call.args[0])<0.1));
  }
});

const failed=results.filter(passed=>!passed).length;
console.log(`RESULT: ${results.length-failed}/${results.length} passed. DOM/contract tests only; Android and game execution remain unverified.`);
process.exitCode=failed?1:0;
