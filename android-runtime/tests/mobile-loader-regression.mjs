// JVM regression for the real LiteVersions/LiteMods product code. No live network/device.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const javaHome = process.env.JAVA_HOME || 'C:/Program Files/OpenJDK/jdk-17.0.1';
const cache = path.join(process.env.USERPROFILE || os.homedir(), '.gradle/caches/modules-2/files-2.1/org.json/json');
function jarBelow(directory) {
  if (!fs.existsSync(directory)) return null;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isFile() && entry.name.endsWith('.jar')) return file;
    if (entry.isDirectory()) { const match = jarBelow(file); if (match) return match; }
  }
}
const json = jarBelow(cache);
if (!json) throw new Error('This test requires the existing Gradle org.json cache. No dependency is downloaded.');
const android = path.join(process.env.LOCALAPPDATA, 'Android/Sdk/platforms/android-37.0/android.jar');
const gson = path.join(root, 'app_pojavlauncher/libs/gson-2.8.6.jar');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'litemc-mobile-loader-'));
const sources = {
  'android/content/Context.java': `package android.content; public class Context {
    private final java.io.File files; public Context(java.io.File f){files=f;} public Context getApplicationContext(){return this;} public java.io.File getFilesDir(){return files;}
    public net.kdt.pojavlaunch.prefs.LauncherPreferences.Preferences getSharedPreferences(String n,int m){return new net.kdt.pojavlaunch.prefs.LauncherPreferences.Preferences();}
  }`,
  'android/app/Activity.java': `package android.app; public class Activity extends android.content.Context {public Activity(java.io.File f){super(f);}}`,
  'net/kdt/pojavlaunch/Tools.java': `package net.kdt.pojavlaunch; public class Tools {public static String DIR_GAME_NEW,DIR_HOME_VERSION,CTRLMAP_PATH;public static final com.google.gson.Gson GLOBAL_GSON=new com.google.gson.Gson();}`,
  'net/kdt/pojavlaunch/JMinecraftVersionList.java': `package net.kdt.pojavlaunch; public class JMinecraftVersionList {public static class Version{}}`,
  'net/kdt/pojavlaunch/prefs/LauncherPreferences.java': `package net.kdt.pojavlaunch.prefs; public class LauncherPreferences {
    public static final String PREF_KEY_CURRENT_PROFILE="profile";public static final Preferences DEFAULT_PREF=new Preferences();
    public static class Preferences{public String getString(String n,String d){return d;}public Preferences edit(){return this;}public Preferences putString(String n,String v){return this;}public boolean commit(){return true;}public void apply(){}}
  }`,
  'net/kdt/pojavlaunch/value/launcherprofiles/MinecraftProfile.java': `package net.kdt.pojavlaunch.value.launcherprofiles; public class MinecraftProfile {public String name,lastVersionId,gameDir,controlFile;}`,
  'net/kdt/pojavlaunch/value/launcherprofiles/LauncherProfiles.java': `package net.kdt.pojavlaunch.value.launcherprofiles; public class LauncherProfiles {public static final Data mainProfileJson=new Data();public static void load(){}public static void write(){}public static class Data{public java.util.Map<String,MinecraftProfile> profiles=new java.util.HashMap<>();}}`,
  'net/kdt/pojavlaunch/tasks/AsyncMinecraftDownloader.java': `package net.kdt.pojavlaunch.tasks;public class AsyncMinecraftDownloader {public interface DoneListener{void onDownloadDone();void onDownloadFailed(Throwable t);}}`,
  'net/kdt/pojavlaunch/tasks/MinecraftDownloader.java': `package net.kdt.pojavlaunch.tasks;public class MinecraftDownloader {public void start(android.app.Activity a,net.kdt.pojavlaunch.JMinecraftVersionList.Version v,String id,AsyncMinecraftDownloader.DoneListener l){throw new AssertionError("Runtime downloading is outside this test");}}`,
  'com/litemc/launcher/LiteNetwork.java': `package com.litemc.launcher;import java.io.*;import java.nio.file.*;import java.util.*;import org.json.*;
    public class LiteNetwork {
      public static int requests,downloads;public static String lastUrl;public static final Map<String,String> versions=new HashMap<>();
      public static JSONObject getJson(String u,String[] h,Map<String,String> headers)throws Exception{return new JSONObject(new String(request("GET",u,h,headers,null),"UTF-8"));}
      public static byte[] request(String m,String u,String[] h,Map<String,String> headers,byte[] body)throws Exception{
        requests++;lastUrl=u;
        String key=u.contains("/project/")?u.substring(u.indexOf("/project/")+9,u.indexOf("/version?")):u.substring(u.lastIndexOf('/')+1);
        if(!versions.containsKey(key))throw new IOException("unmocked metadata");return versions.get(key).getBytes("UTF-8");
      }
      public static void download(String u,String[] hosts,File f,String hash)throws Exception{
        if(hosts.length!=1||!"cdn.modrinth.com".equals(hosts[0]))throw new AssertionError("mod files must be CDN restricted");
        downloads++;f.getParentFile().mkdirs();Files.write(f.toPath(),new byte[]{1,2,3});
      }
    }`,
  'com/litemc/launcher/MobileLoaderRegression.java': String.raw`package com.litemc.launcher;
import java.io.*;import java.nio.file.*;import org.json.*;import net.kdt.pojavlaunch.Tools;
public class MobileLoaderRegression {
  static int tests;
  static final String HASH="7037807198c22a7d2b0807371d763779a84fdfcf";
  static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
  interface Action{void run()throws Exception;}
  static void rejects(Action a,String text)throws Exception{try{a.run();throw new AssertionError("Expected rejection: "+text);}catch(IllegalArgumentException|IllegalStateException e){check(e.getMessage().contains(text),"wrong error: "+e);}}
  static void pass(String n){tests++;System.out.println("PASS "+n);}
  static JSONObject version(String project,String id,String loader)throws Exception{
    return new JSONObject().put("project_id",project).put("id",id).put("version_type","release")
      .put("game_versions",new JSONArray().put("1.20.1")).put("loaders",new JSONArray().put(loader))
      .put("files",new JSONArray().put(new JSONObject().put("filename",project+".jar").put("primary",true)
        .put("url","https://cdn.modrinth.com/data/"+project+".jar").put("hashes",new JSONObject().put("sha1",HASH))))
      .put("dependencies",new JSONArray());
  }
  static void api(JSONObject candidate)throws Exception{LiteNetwork.versions.clear();LiteNetwork.versions.put("fabric-api",new JSONArray().put(candidate).toString());}
  public static void main(String[] args)throws Exception{
    File base=new File(args[0]);base.mkdirs();Tools.DIR_GAME_NEW=new File(base,"game").getPath();Tools.DIR_HOME_VERSION=new File(base,"versions").getPath();Tools.CTRLMAP_PATH=new File(base,"controls").getPath();
    android.content.Context context=new android.content.Context(base);LiteMods mods=new LiteMods(context);
    JSONArray capabilities=LiteVersions.loaders();check(capabilities.length()==5,"capability count");
    for(int i=0;i<capabilities.length();i++){JSONObject l=capabilities.getJSONObject(i);boolean enabled=i<2;check(l.getBoolean("automaticInstall")==enabled,"capability state");check(enabled||!l.getString("reason").isEmpty(),"unsupported loader reason");}
    pass("five loaders report honest automatic-install capabilities");
    for(String loader:new String[]{"forge","liteloader","optifine"}){
      rejects(()->new LiteVersions(context).install(null,"1.20.1",loader,"test"),LiteVersions.loaderName(loader));
    }
    check(LiteNetwork.requests==0,"unsupported loader performed network");check(!new File(base,"lite-instances.json").exists(),"unsupported loader committed record");pass("unsupported installers reject before network or installed record");
    api(version("fabric-api","api-v1","fabric"));
    JSONObject result=mods.installFabricApi("fabric-1.20.1-test","1.20.1");
    File jar=new File(Tools.DIR_GAME_NEW,"lite-instances/fabric-1.20.1-test/mods/fabric-api.jar");
    check(jar.length()==3,"API not in instance mods");check(result.getJSONArray("files").getJSONObject(0).getString("sha1").equals(HASH),"missing hash metadata");
    check(LiteNetwork.lastUrl.contains("%5B%22fabric%22%5D")&&LiteNetwork.lastUrl.contains("1.20.1"),"missing API compatibility filters");pass("Fabric API installs into isolated mods with loader/version filters and metadata");
    int downloads=LiteNetwork.downloads;mods.installFabricApi("fabric-1.20.1-test","1.20.1");check(LiteNetwork.downloads==downloads,"valid API was re-downloaded");pass("verified existing Fabric API is reused");
    Files.write(jar.toPath(),new byte[]{9});mods.installFabricApi("fabric-1.20.1-test","1.20.1");check(LiteNetwork.downloads==downloads+1,"corrupt API not repaired");pass("corrupt Fabric API is repaired");
    JSONObject noHash=version("fabric-api","api-v1","fabric");noHash.getJSONArray("files").getJSONObject(0).put("hashes",new JSONObject());api(noHash);
    rejects(()->mods.installFabricApi("bad-hash","1.20.1"),"SHA-1");check(!new File(Tools.DIR_GAME_NEW,"lite-instances/bad-hash/mods/fabric-api.jar").exists(),"bad hash written");pass("missing official hash cannot install");
    api(version("fabric-api","api-v1","forge"));rejects(()->mods.installFabricApi("bad-loader","1.20.1"),"兼容");pass("wrong-loader API metadata rejected");
    JSONObject root=version("fabric-api","api-v1","fabric");root.put("dependencies",new JSONArray().put(new JSONObject().put("dependency_type","required").put("project_id","dependency").put("version_id","pinned-v1")));api(root);
    LiteNetwork.versions.put("pinned-v1",version("dependency","pinned-v1","forge").toString());rejects(()->mods.installFabricApi("bad-dependency","1.20.1"),"不兼容");pass("incompatible pinned required dependency rejected");
    api(version("fabric-api","api-v1","fabric"));rejects(()->mods.installFabricApi("../escape","1.20.1"),"实例");pass("instance traversal rejected");
    LiteNetwork.versions.clear();LiteNetwork.versions.put("forge-mod",new JSONArray().put(version("forge-mod","f-v1","forge")).toString());
    mods.handle("mods.install",new JSONObject().put("provider","modrinth").put("projectId","forge-mod").put("loader","forge").put("version","1.20.1").put("instanceId","forge-1.20.1-test"));
    check(LiteNetwork.lastUrl.contains("%5B%22forge%22%5D"),"Forge request used Fabric filter");pass("Forge mod context uses Forge compatibility filter");
    System.out.println("Mobile loader regression: "+tests+" passed");
  }
}`,
};
try {
  const files = [];
  for (const [relative, content] of Object.entries(sources)) {
    const filename = path.join(work, 'src', relative);fs.mkdirSync(path.dirname(filename), {recursive:true});fs.writeFileSync(filename,content);files.push(filename);
  }
  const classes=path.join(work,'classes');fs.mkdirSync(classes);
  const classpath=[json,gson,android].join(path.delimiter);
  const production=['LiteVersions.java','LiteMods.java'].map(name=>path.join(root,'app_pojavlauncher/src/main/java/com/litemc/launcher',name));
  const compile=spawnSync(path.join(javaHome,'bin/javac.exe'),['--release','8','-encoding','UTF-8','-cp',classpath,'-d',classes,...files,...production],{stdio:'inherit'});
  if(compile.error)throw compile.error;if(compile.status!==0)throw new Error('Java compilation failed');
  const run=spawnSync(path.join(javaHome,'bin/java.exe'),['-cp',classes+path.delimiter+classpath,'com.litemc.launcher.MobileLoaderRegression',path.join(work,'fixtures')],{stdio:'inherit',timeout:15000});
  if(run.error)throw run.error;if(run.status!==0)throw new Error('Mobile loader regression failed');
} finally { fs.rmSync(work,{recursive:true,force:true}); }
