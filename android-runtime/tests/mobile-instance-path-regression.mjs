// Offline directory-routing regression. Only this invocation's mkdtemp is written.
// Compiles verbatim extracted production methods, actual MinecraftProfile and
// actual JSONUtils; it does NOT launch Android, JVM Minecraft, Forge or Fabric.
// Android's absolute-child concatenation is separately modeled from AOSP:
// https://android.googlesource.com/platform/libcore/+/99031c4f347c025b51db2c55e1fd680ed93f85c6/ojluni/src/main/java/java/io/UnixFileSystem.java
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const source=path.join(root,'app_pojavlauncher/src/main/java');
const read=file=>fs.readFileSync(path.join(source,file),'utf8');
const toolsSource=read('net/kdt/pojavlaunch/Tools.java');
const versionsSource=read('com/litemc/launcher/LiteVersions.java');
const mainSource=read('net/kdt/pojavlaunch/MainActivity.java');
const jreSource=read('net/kdt/pojavlaunch/utils/JREUtils.java');
const activitySource=read('com/litemc/launcher/LiteActivity.java');

// Brace scanner recognizes comments and string/character literals: braces inside
// strings must never silently truncate the method being tested.
function method(text,marker) {
  const start=text.indexOf(marker);assert.ok(start>=0,`Production method missing: ${marker}`);
  const open=text.indexOf('{',start);let depth=0,state='code',escaped=false;
  for(let i=open;i<text.length;i++) {
    const c=text[i],next=text[i+1];
    if(state==='line'){if(c==='\n')state='code';continue;}
    if(state==='block'){if(c==='*'&&next==='/'){state='code';i++;}continue;}
    if(state==='string'||state==='char') {
      if(escaped){escaped=false;continue;}if(c==='\\'){escaped=true;continue;}
      if((state==='string'&&c==='"')||(state==='char'&&c==="'"))state='code';continue;
    }
    if(c==='/'&&next==='/'){state='line';i++;continue;}
    if(c==='/'&&next==='*'){state='block';i++;continue;}
    if(c==='"'){state='string';continue;}if(c==="'"){state='char';continue;}
    if(c==='{')depth++;if(c==='}'&&--depth===0)return text.slice(start,i+1);
  }
  throw new Error(`Unclosed production method: ${marker}`);
}
function jarBelow(dir) {
  if(!fs.existsSync(dir))return null;
  for(const item of fs.readdirSync(dir,{withFileTypes:true})) {
    const file=path.join(dir,item.name);if(item.isFile()&&item.name.endsWith('.jar'))return file;
    if(item.isDirectory()){const found=jarBelow(file);if(found)return found;}
  }
  return null;
}
const json=jarBelow(path.join(process.env.USERPROFILE||os.homedir(),'.gradle/caches/modules-2/files-2.1/org.json/json'));
if(!json)throw new Error('Existing org.json cache required; no dependency will be downloaded.');
const javaHome=process.env.JAVA_HOME||'C:/Program Files/OpenJDK/jdk-17.0.1';
const gameDirMethod=method(toolsSource,'public static File getGameDirPath(');
const clientArgsMethod=method(toolsSource,'public static String[] getMinecraftClientArgs(');
const prefix=toolsSource.match(/LAUNCHERPROFILES_RTPREFIX\s*=\s*("[^"\r\n]*")/);assert.ok(prefix,'Runtime path prefix constant missing');
const workingDirectory=jreSource.match(/chdir\(gameDirectory\s*==\s*null\s*\?\s*Tools\.DIR_GAME_NEW\s*:\s*gameDirectory\.getAbsolutePath\(\)\);/);
assert.ok(workingDirectory,'Native JVM working-directory expression changed: manually review its routing');
const mainPath=mainSource.match(/String gameDirPath\s*=\s*Tools\.getGameDirPath\(minecraftProfile\)\.getAbsolutePath\(\);\s*MCOptionUtils\.load\(gameDirPath\);/);
assert.ok(mainPath,'MainActivity options path is no longer routed by the tested directory helper');
assert.match(toolsSource,/File gamedir\s*=\s*Tools\.getGameDirPath\(minecraftProfile\)/,'Launch path must come from the tested helper');
assert.match(toolsSource,/getMinecraftClientArgs\(minecraftAccount,\s*versionInfo,\s*gamedir\)/,'Client arguments must use the resolved game directory');
assert.match(toolsSource,/launchJavaVM\(activity,\s*runtime,\s*gamedir,/,'JVM working directory must use the resolved game directory');
assert.match(versionsSource,/content\.prepare\(instance\(instanceId\)\)/,'Pack preparation must use the same instance directory as selection');
assert.match(activitySource,/versions\.select\(entry\)/,'Launch must select its saved profile');
assert.match(activitySource,/new Intent\(this,\s*MainActivity\.class\)/,'Launch must enter the runtime activity');

const sources={
  'androidx/annotation/NonNull.java':'package androidx.annotation;public @interface NonNull{}',
  'androidx/annotation/Keep.java':'package androidx.annotation;public @interface Keep{}',
  'android/util/ArrayMap.java':'package android.util;public class ArrayMap<K,V> extends java.util.HashMap<K,V>{}',
  'android/util/Log.java':'package android.util;public class Log{public static int e(String a,String b,Throwable t){return 0;}}',
  'android/content/Context.java':`package android.content;import net.kdt.pojavlaunch.prefs.LauncherPreferences.Preferences;public class Context{public final Preferences prefs=new Preferences();public Preferences getSharedPreferences(String name,int mode){return prefs;}}`,
  'net/kdt/pojavlaunch/prefs/LauncherPreferences.java':`package net.kdt.pojavlaunch.prefs;public class LauncherPreferences{public static final String PREF_KEY_CURRENT_PROFILE="profile";public static final Preferences DEFAULT_PREF=new Preferences();public static class Preferences{private final java.util.Map<String,String> values=new java.util.HashMap<>();public Preferences edit(){return this;}public Preferences putString(String key,String value){values.put(key,value);return this;}public String getString(String key,String fallback){return values.containsKey(key)?values.get(key):fallback;}public boolean commit(){return true;}public void apply(){}}}`,
  'net/kdt/pojavlaunch/value/launcherprofiles/LauncherProfiles.java':`package net.kdt.pojavlaunch.value.launcherprofiles;import net.kdt.pojavlaunch.prefs.LauncherPreferences;public class LauncherProfiles{public static final Data mainProfileJson=new Data();public static int writes;public static class Data{public final java.util.Map<String,MinecraftProfile> profiles=new java.util.HashMap<>();}public static void load(){}public static void write(){writes++;}public static MinecraftProfile getCurrentProfile(){return mainProfileJson.profiles.get(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,""));}}`,
  'net/kdt/pojavlaunch/value/MinecraftAccount.java':`package net.kdt.pojavlaunch.value;public class MinecraftAccount{public String username="Fixture_Player",accessToken="0",profileId="00000000000000000000000000000000",xuid="";public boolean isDemo(){return false;}}`,
  'net/kdt/pojavlaunch/JMinecraftVersionList.java':`package net.kdt.pojavlaunch;public class JMinecraftVersionList{public static class Arguments{public Object[] game;}public static class Version{public String id="1.20.1",inheritsFrom,assets="1.20",type="release",minecraftArguments;public Arguments arguments;}}`,
  'net/kdt/pojavlaunch/utils/DateUtils.java':`package net.kdt.pojavlaunch.utils;public class DateUtils{public static java.util.Date getOriginalReleaseDate(net.kdt.pojavlaunch.JMinecraftVersionList.Version v)throws java.text.ParseException{return null;}public static boolean dateBefore(java.util.Date d,int y,int m,int day){return false;}}`,
  'net/kdt/pojavlaunch/Tools.java':`package net.kdt.pojavlaunch;import java.io.*;import java.util.*;import java.text.ParseException;import android.util.*;import androidx.annotation.NonNull;import net.kdt.pojavlaunch.value.MinecraftAccount;import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;import net.kdt.pojavlaunch.utils.*;public class Tools{
    public static String DIR_GAME_HOME,DIR_GAME_NEW,CTRLMAP_PATH,ASSETS_PATH;
    public static final String LAUNCHERPROFILES_RTPREFIX=${prefix[1]};
    ${gameDirMethod}
    ${clientArgsMethod}
    ${method(toolsSource,'public static String fromStringArray(')}
    ${method(toolsSource,'private static String[] splitAndFilterEmpty(')}
  }`,
  'com/litemc/launcher/LiteVersions.java':`package com.litemc.launcher;import java.io.*;import java.util.*;import java.nio.charset.StandardCharsets;import org.json.*;import android.content.Context;import net.kdt.pojavlaunch.Tools;import net.kdt.pojavlaunch.prefs.LauncherPreferences;import net.kdt.pojavlaunch.value.launcherprofiles.*;public class LiteVersions{
    private final Context context;public LiteVersions(Context context){this.context=context;}
    // Compatibility/install services are outside this routing-only regression.
    private String compatibilityReasonForDevice(String version){return "";}public static void requireAutomaticLoader(String loader){}
    ${method(versionsSource,'static String id(')}
    ${method(versionsSource,'public static File instance(')}
    ${method(versionsSource,'public void select(')}
  }`,
  'InstancePathRegression.java':String.raw`import java.io.*;import java.nio.file.*;import java.util.*;import org.json.*;
import com.litemc.launcher.LiteVersions;import net.kdt.pojavlaunch.*;import net.kdt.pojavlaunch.value.*;import net.kdt.pojavlaunch.value.launcherprofiles.*;
public class InstancePathRegression{
  interface Action{void run()throws Exception;}static int passed,failed;static File fixture;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void test(String name,Action action){try{action.run();passed++;System.out.println("PASS "+name);}catch(Throwable error){failed++;System.out.println("FAIL "+name+": "+error.getClass().getSimpleName()+" "+error.getMessage());}}
  static void same(File expected,File actual)throws Exception{check(expected.getCanonicalFile().equals(actual.getCanonicalFile()),"expected="+expected+" actual="+actual);}
  static MinecraftProfile profile(String directory){MinecraftProfile p=new MinecraftProfile();p.gameDir=directory;return p;}
  static File legacy(MinecraftProfile p){if(p.gameDir!=null){if(p.gameDir.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX))return new File(p.gameDir.replace(Tools.LAUNCHERPROFILES_RTPREFIX,Tools.DIR_GAME_HOME+"/"));return new File(Tools.DIR_GAME_HOME,p.gameDir);}return new File(Tools.DIR_GAME_NEW);}
  // Normalized non-root POSIX paths: this is a model of AOSP UnixFileSystem.resolve,
  // NOT execution on an Android device. The native File constructor is tested too.
  static String androidAbsoluteChild(String parent,String absoluteChild){check(parent.startsWith("/")&&absoluteChild.startsWith("/"),"POSIX model precondition");return "/".equals(parent)?absoluteChild:parent+absoluteChild;}
  static class MCOptionUtils{static String loaded;static void load(String path){loaded=path;}}
  static String cwd;static void chdir(String path){cwd=path;}
  static void mainActivityPath(MinecraftProfile minecraftProfile){__MAIN_PATH__}
  static void runtimeCwd(File gameDirectory){__WORKING_DIR__}
  static String argument(String[] args,String key){for(int i=0;i<args.length-1;i++)if(args[i].equals(key))return args[i+1];throw new AssertionError("Missing "+key);}
  static void setHome(File home)throws Exception{home.mkdirs();Tools.DIR_GAME_HOME=home.getCanonicalPath();Tools.DIR_GAME_NEW=new File(home,".minecraft").getCanonicalPath();Tools.CTRLMAP_PATH=new File(home,"controlmap").getCanonicalPath();Tools.ASSETS_PATH=new File(home,"assets").getCanonicalPath();}
  public static void main(String[] args)throws Exception{
    fixture=new File(args[0]);fixture.mkdirs();setHome(new File(fixture,"game home 空间"));
    test("native Java File(parent, absoluteChild) reproduces the historical wrong directory",()->{
      File intended=LiteVersions.instance("pack-1.20.1");File jar=new File(intended,"mods/fixture.jar");Files.write(jar.toPath(),new byte[]{1,2,3});MinecraftProfile profile=profile(intended.getAbsolutePath());File historical=legacy(profile);
      System.out.println("EVIDENCE native OS="+System.getProperty("os.name")+" child="+intended.getAbsolutePath());System.out.println("EVIDENCE native parent+absolute="+historical.getAbsolutePath());
      check(!historical.getAbsolutePath().equals(intended.getAbsolutePath()),"Native constructor did not reproduce historical concatenation");check(!new File(historical,"mods/fixture.jar").isFile(),"Historical wrong path unexpectedly sees prepared Mod");
    });
    test("Android POSIX source model duplicates storage root with the old constructor",()->{
      String home="/storage/emulated/0/Android/data/com.litemc.launcher.android/files";String intended=home+"/.minecraft/lite-instances/pack-1.20.1";String old=androidAbsoluteChild(home,intended);
      check(old.equals(home+intended),"AOSP source model mismatch");check(!old.equals(intended),"Historical Android path was not duplicated");System.out.println("EVIDENCE AOSP MODEL old="+old);System.out.println("EVIDENCE AOSP MODEL correct="+intended);
    });
    test("current production helper preserves existing absolute gameDir without migration",()->{
      File intended=LiteVersions.instance("pack-1.20.1");MinecraftProfile profile=profile(intended.getAbsolutePath());File resolved=Tools.getGameDirPath(profile);same(intended,resolved);check(new File(resolved,"mods/fixture.jar").length()==3,"Prepared Mod is invisible through launch directory");
    });
    test("null and empty profile paths both use the default game directory",()->{same(new File(Tools.DIR_GAME_NEW),Tools.getGameDirPath(profile(null)));same(new File(Tools.DIR_GAME_NEW),Tools.getGameDirPath(profile("")));});
    test("ordinary relative profile paths remain under the launcher storage root",()->{same(new File(Tools.DIR_GAME_HOME,"custom/world"),Tools.getGameDirPath(profile("custom/world")));});
    test("launcher relative prefix is removed once, not globally replaced",()->{
      String rest="nested/"+Tools.LAUNCHERPROFILES_RTPREFIX+"world";File expected=new File(Tools.DIR_GAME_HOME,rest);File actual=Tools.getGameDirPath(profile(Tools.LAUNCHERPROFILES_RTPREFIX+rest));check(expected.getPath().equals(actual.getPath()),"Interior prefix-like text was rewritten");
      same(new File(Tools.DIR_GAME_HOME,".minecraft/lite-instances/pack-1.20.1"),Tools.getGameDirPath(profile(Tools.LAUNCHERPROFILES_RTPREFIX+".minecraft/lite-instances/pack-1.20.1")));
    });
    test("real LiteVersions selection and runtime readers share the prepared instance path",()->{
      File intended=LiteVersions.instance("pack-1.20.1");android.content.Context context=new android.content.Context();LiteVersions versions=new LiteVersions(context);
      versions.select(new JSONObject().put("id","pack-1.20.1").put("version","1.20.1").put("loader","fabric").put("launchVersion","fabric-loader-test").put("name","Prepared pack"));
      MinecraftProfile selected=LauncherProfiles.getCurrentProfile();check(selected!=null,"No selected launcher profile");same(intended,Tools.getGameDirPath(selected));check(new File(selected.gameDir).isAbsolute(),"Selected profile should preserve its canonical absolute gameDir");
      mainActivityPath(selected);same(intended,new File(MCOptionUtils.loaded));File options=new File(MCOptionUtils.loaded,"options.txt");Files.write(options.toPath(),"fixture=true".getBytes("UTF-8"));check(new File(intended,"options.txt").isFile(),"MainActivity options read another directory");
      JMinecraftVersionList.Version info=new JMinecraftVersionList.Version();info.arguments=new JMinecraftVersionList.Arguments();info.arguments.game=new Object[]{"--gameDir","$"+"{game_directory}","--assetsDir","$"+"{assets_root}"};
      File gameDir=Tools.getGameDirPath(selected);String[] arguments=Tools.getMinecraftClientArgs(new MinecraftAccount(),info,gameDir);same(intended,new File(argument(arguments,"--gameDir")));check(argument(arguments,"--gameDir").contains("game home 空间"),"Space/Unicode path was split or changed");
      runtimeCwd(gameDir);same(intended,new File(cwd));check(new File(cwd,"mods/fixture.jar").isFile(),"Native JVM working directory does not see prepared Mod");check(LauncherProfiles.writes>0,"Selection was not persisted through profile store");
    });
    test("different instance selections stay isolated and still see their own Mods",()->{
      File other=LiteVersions.instance("second-pack");Files.write(new File(other,"mods/other.jar").toPath(),new byte[]{7});LiteVersions versions=new LiteVersions(new android.content.Context());versions.select(new JSONObject().put("id","second-pack").put("version","1.20.1").put("loader","fabric").put("launchVersion","fabric-other"));File actual=Tools.getGameDirPath(LauncherProfiles.getCurrentProfile());same(other,actual);check(new File(actual,"mods/other.jar").isFile(),"Selected instance's Mod missing");check(!new File(actual,"mods/fixture.jar").exists(),"Previous instance leaked into current launch");
    });
    System.out.println("Instance path regression: "+passed+" passed, "+failed+" failed. Native Windows/JVM + AOSP POSIX model only; Minecraft/Android launch NOT tested.");if(failed>0)System.exit(1);
  }
}`.replace('__MAIN_PATH__',mainPath[0]).replace('__WORKING_DIR__',workingDirectory[0]),
};
const work=fs.mkdtempSync(path.join(os.tmpdir(),'litemc-instance-path-'));
try{
  const files=[];
  for(const [relative,contents] of Object.entries(sources)){const file=path.join(work,'src',relative);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,contents);files.push(file);}
  const classes=path.join(work,'classes');fs.mkdirSync(classes);
  const production=['net/kdt/pojavlaunch/utils/JSONUtils.java','net/kdt/pojavlaunch/value/launcherprofiles/MinecraftProfile.java','net/kdt/pojavlaunch/value/launcherprofiles/MinecraftResolution.java'].map(file=>path.join(source,file));
  const ext=process.platform==='win32'?'.exe':'';
  const compile=spawnSync(path.join(javaHome,'bin','javac'+ext),['-J-Duser.language=en','--release','8','-encoding','UTF-8','-cp',json,'-d',classes,...files,...production],{stdio:'inherit',timeout:30000});
  if(compile.error)throw compile.error;if(compile.status!==0)throw new Error('Extracted production path methods did not compile; inspect genuine dependency changes.');
  const run=spawnSync(path.join(javaHome,'bin','java'+ext),['-Dfile.encoding=UTF-8','-cp',classes+path.delimiter+json,'InstancePathRegression',path.join(work,'fixtures')],{stdio:'inherit',timeout:15000});
  if(run.error)throw run.error;process.exitCode=run.status??1;
}finally{
  const resolved=fs.realpathSync(work),temporary=fs.realpathSync(os.tmpdir());
  if(path.dirname(resolved)!==temporary||!path.basename(resolved).startsWith('litemc-instance-path-'))throw new Error('Unexpected fixture cleanup target');
  fs.rmSync(resolved,{recursive:true,force:true});
}
