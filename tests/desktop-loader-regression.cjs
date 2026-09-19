// Real loader helpers with controlled filesystem/Java stubs; no network/game launch.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../src/main.js'), 'utf8');
function section(start, end) {
  const begin = source.indexOf(start), finish = source.indexOf(end, begin);
  assert.ok(begin >= 0 && finish > begin, `Missing production section: ${start}`);
  return source.slice(begin, finish);
}
const handlers = new Map();
let metadata = {}, configuredMajor = 21, cachedMajor = 8, networkCalls = 0;
const context = vm.createContext({
  path, process, Object, Error, JSON, Number, String, Array, RegExp,
  LOADER_NAMES: {vanilla:'Vanilla',fabric:'Fabric',forge:'Forge',liteloader:'LiteLoader',optifine:'OptiFine'},
  ipcMain: {handle:(name, handler)=>handlers.set(name, handler)},
  fs: {readFileSync:()=>JSON.stringify(metadata)},
  gameRoot:()=>path.resolve('synthetic-game-root'),
  javaMajor:async value=>value === 'configured-java' ? configuredMajor : cachedMajor,
  JAVA_RUNTIME_INDEX:'https://example.invalid/never-requested',
  send:()=>{}, getJson:async()=>{networkCalls++;throw new Error('DOWNLOAD_REQUIRED');}
});
vm.runInContext(section('async function ensureOfficialJava(', 'function createWindow()'), context);
vm.runInContext(section('function assertAutomaticLoader(', 'async function installForge('), context);
let passed = 0;
async function test(name, job) { await job(); passed++; console.log(`PASS ${name}`); }
(async()=>{
  await test('desktop capability reports Forge supported and unsafe installers unavailable',()=>{
    const items=handlers.get('loaders:get')(null,{version:'1.20.1'});
    assert.equal(items.length,5);
    assert.equal(items.find(item=>item.id==='forge').automaticInstall,true);
    assert.equal(items.find(item=>item.id==='fabric').includesFabricApi,true);
    for(const id of ['liteloader','optifine']) { const item=items.find(value=>value.id===id); assert.equal(item.supported,false);assert.ok(item.reason); }
  });
  await test('old/new numbering Forge and invalid capability requests fail closed',()=>{
    for(const version of ['1.12.2','26.1']) assert.equal(handlers.get('loaders:get')(null,{version}).find(item=>item.id==='forge').supported,false);
    assert.throws(()=>handlers.get('loaders:get')(null,{version:'../bad'}));
  });
  await test('legacy Forge rejects newer configured JDK and reuses exact cached Java 8',async()=>{
    metadata={};configuredMajor=21;cachedMajor=8;
    const result=await context.ensureOfficialJava('1.16.5','configured-java','forge');
    assert.ok(result.includes('jre-legacy'));assert.notEqual(result,'configured-java');assert.equal(networkCalls,0);
  });
  await test('modern Forge reuses exact configured JDK without network',async()=>{
    metadata={javaVersion:{majorVersion:17,component:'java-runtime-gamma'}};configuredMajor=17;cachedMajor=21;
    assert.equal(await context.ensureOfficialJava('1.20.1','configured-java','forge'),'configured-java');assert.equal(networkCalls,0);
  });
  await test('incompatible configured and cached Forge JDK initiate official download',async()=>{
    configuredMajor=21;cachedMajor=21;
    await assert.rejects(context.ensureOfficialJava('1.20.1','configured-java','forge'),/DOWNLOAD_REQUIRED/);assert.equal(networkCalls,1);
  });
  await test('vanilla keeps existing newer-Java behavior',async()=>{
    assert.equal(await context.ensureOfficialJava('1.20.1','configured-java','vanilla'),'configured-java');
  });
  await test('Forge JVM placeholders resolve and unknown substitutions reject',()=>{
    const args=context.forgeJvmArgs({id:'forge-test',arguments:{jvm:['-DlibraryDirectory=${library_directory}','-Dseparator=${classpath_separator}']}});
    assert.ok(args[0].endsWith(path.join('synthetic-game-root','libraries')));
    assert.equal(args[1],'-Dseparator='+path.delimiter);
    assert.throws(()=>context.forgeJvmArgs({id:'forge-test',arguments:{jvm:['${untrusted}']}}),/untrusted/);
  });
  console.log(`Desktop loader regression: ${passed} passed (helpers only; installer/game execution unverified)`);
})().catch(error=>{console.error(error);process.exitCode=1;});
