import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
function jarBelow(dir) { for(const entry of fs.readdirSync(dir,{withFileTypes:true})) { const file=path.join(dir,entry.name); if(entry.isFile()&&file.endsWith('.jar'))return file; if(entry.isDirectory()){const result=jarBelow(file);if(result)return result;} } }
const json=jarBelow(path.join(os.homedir(),'.gradle/caches/modules-2/files-2.1/org.json/json'));
const java=process.env.JAVA_HOME || 'C:/Program Files/OpenJDK/jdk-17.0.1';
const work=fs.mkdtempSync(path.join(os.tmpdir(),'lite-local-tools-'));
const source=path.join(root,'app_pojavlauncher/src/main/java/com/litemc/launcher');
function run(executable,args) { const result=spawnSync(path.join(java,'bin',executable),args,{stdio:'inherit'});if(result.error)throw result.error;if(result.status!==0)throw new Error(executable+' failed'); }
try {
  run('javac',['-encoding','UTF-8','-cp',json,'-d',work,path.join(source,'LiteLocalFiles.java'),path.join(source,'LiteControls.java'),path.join(root,'tests/LocalToolsRegression.java')]);
  run('java',['-cp',work+path.delimiter+json,'com.litemc.launcher.LocalToolsRegression',path.join(work,'fixtures'),path.join(root,'app_pojavlauncher/src/main/assets/litemc/controls.json')]);
} finally { fs.rmSync(work,{recursive:true,force:true}); }
