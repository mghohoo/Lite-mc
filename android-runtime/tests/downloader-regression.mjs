// Runs the real downloader on the JVM with deterministic Android/network test doubles.
// No Gradle, Android device, live network, or user Minecraft files are used.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'app_pojavlauncher/src/main/java');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'litemc-downloader-test-'));
const gson = path.join(root, 'app_pojavlauncher/libs/gson-2.8.6.jar');
const javaHome = process.env.JAVA_HOME || 'C:/Program Files/OpenJDK/jdk-17.0.1';
const stubs = {
  'android/app/Activity.java': `package android.app; public class Activity { public String getString(int id) { return "runtime failure"; } }`,
  'android/util/Log.java': `package android.util; public class Log { public static int i(String a,String b){return 0;} public static int i(String a,String b,Throwable t){return 0;} public static int w(String a,String b){return 0;} }`,
  'com/kdt/mcgui/ProgressLayout.java': `package com.kdt.mcgui; public class ProgressLayout { public static final int DOWNLOAD_MINECRAFT=0; public static void setProgress(int a,int b,int c,Object... d){} public static void clearProgress(int a){} }`,
  'net/kdt/pojavlaunch/PojavApplication.java': `package net.kdt.pojavlaunch; public class PojavApplication { public static final java.util.concurrent.Executor sExecutorService=Runnable::run; }`,
  'net/kdt/pojavlaunch/R.java': `package net.kdt.pojavlaunch; public class R { public static class string { public static final int newdl_starting=0,newdl_downloading_game_files=1,newdl_downloading_game_files_size=2,newdl_extracting_native_libraries=3,newdl_downloading_metadata=4,exception_failed_to_unpack_jre17=5; } }`,
  'net/kdt/pojavlaunch/NewJREUtil.java': `package net.kdt.pojavlaunch; public class NewJREUtil { public static boolean installNewJreIfNeeded(android.app.Activity a,JMinecraftVersionList.Version v){return true;} }`,
  'net/kdt/pojavlaunch/prefs/LauncherPreferences.java': `package net.kdt.pojavlaunch.prefs; public class LauncherPreferences { public static boolean PREF_VERIFY_MANIFEST=true,PREF_CHECK_LIBRARY_SHA=true; }`,
  'net/kdt/pojavlaunch/Tools.java': `package net.kdt.pojavlaunch;
    import java.io.*; import java.nio.file.*; import java.nio.charset.*; import java.security.*;
    public class Tools {
      public static String DIR_HOME_VERSION,DIR_HOME_LIBRARY,DIR_CACHE,DIR_DATA,DIR_GAME_NEW,ASSETS_PATH,OBSOLETE_RESOURCES_PATH;
      public static boolean online; public static final com.google.gson.Gson GLOBAL_GSON=new com.google.gson.Gson();
      public static boolean isOnline(android.app.Activity a){return online;} public static boolean isDemoProfile(android.app.Activity a){return false;}
      public static void switchDemo(boolean d){} public static boolean isValidString(String s){return s!=null&&!s.isEmpty();}
      public static String read(File f)throws IOException{return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
      public static String read(String f)throws IOException{return read(new File(f));}
      public static void preProcessLibraries(net.kdt.pojavlaunch.value.DependentLibrary[] libs){for(net.kdt.pojavlaunch.value.DependentLibrary l:libs)if(l.name.equals("needs-substitution"))l.name="substituted";}
      public static String artifactToPath(net.kdt.pojavlaunch.value.DependentLibrary l){return l.name+".jar";}
      public static boolean compareSHA1(File f,String expected){try{return sha(Files.readAllBytes(f.toPath())).equalsIgnoreCase(expected);}catch(Exception e){return false;}}
      public static String sha(byte[] b)throws Exception {StringBuilder r=new StringBuilder(); for(byte v:MessageDigest.getInstance("SHA-1").digest(b))r.append(String.format("%02x",v));return r.toString();}
      public interface DownloaderFeedback{void updateProgress(int current,int maximum);}
    }`,
  'net/kdt/pojavlaunch/utils/FileUtils.java': `package net.kdt.pojavlaunch.utils; import java.io.*; public class FileUtils {
      public static void ensureParentDirectory(File f)throws IOException{ensureDirectory(f.getParentFile());}
      public static void ensureDirectory(File f)throws IOException{if(!f.isDirectory()&&!f.mkdirs())throw new IOException("mkdir failed");}
      public static String getFileName(String s){return new File(s).getName();}
      public static String removeExtension(String s){return s.substring(0,s.lastIndexOf('.'));}
    }`,
  'org/apache/commons/io/FileUtils.java': `package org.apache.commons.io; import java.io.*; public class FileUtils { public static void copyFile(File a,File b,boolean p)throws IOException{java.nio.file.Files.copy(a.toPath(),b.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);} }`,
  'net/kdt/pojavlaunch/mirrors/MirrorTamperedException.java': `package net.kdt.pojavlaunch.mirrors; public class MirrorTamperedException extends java.io.IOException {}`,
  'net/kdt/pojavlaunch/mirrors/DownloadMirror.java': `package net.kdt.pojavlaunch.mirrors;
    import java.io.*; import java.nio.file.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*; import net.kdt.pojavlaunch.Tools;
    public class DownloadMirror {
      public static final int DOWNLOAD_CLASS_LIBRARIES=0,DOWNLOAD_CLASS_METADATA=1,DOWNLOAD_CLASS_ASSETS=2;
      public static final AtomicInteger calls=new AtomicInteger(); public static volatile boolean fail,block; public static volatile CountDownLatch entered=new CountDownLatch(1);
      public static byte[] payload={1,2,3};
      public static boolean isMirrored(){return false;}
      public static String downloadStringMirrored(int c,String u)throws IOException{calls.incrementAndGet();return "invalid";}
      public static void downloadFileMirrored(int c,String u,File f)throws IOException{downloadFileMirrored(c,u,f,null,(a,b)->{});}
      public static void downloadFileMirrored(int c,String u,File f,byte[] b,Tools.DownloaderFeedback progress)throws IOException{
        calls.incrementAndGet(); entered.countDown();
        if(block)try{Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("cancelled",e);}
        if(fail){Files.write(f.toPath(),new byte[]{1});throw new IOException("network failed");}
        Files.write(f.toPath(),payload);progress.updateProgress(payload.length,payload.length);
      }
    }`,
  'net/kdt/pojavlaunch/utils/DownloadUtils.java': `package net.kdt.pojavlaunch.utils; import java.io.*; import java.util.concurrent.Callable;
    public class DownloadUtils {
      public static class SHA1VerificationException extends IOException {}
      public static <T>T ensureSha1(File f,String hash,Callable<T> download)throws IOException{
        if(f.isFile()&&(hash==null||net.kdt.pojavlaunch.Tools.compareSHA1(f,hash)))return null;
        try{return download.call();}catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
      }
    }`,
  'net/kdt/pojavlaunch/tasks/AsyncMinecraftDownloader.java': `package net.kdt.pojavlaunch.tasks; public class AsyncMinecraftDownloader {
    public static net.kdt.pojavlaunch.JMinecraftVersionList.Version getListedVersion(String id){return null;}
    public interface DoneListener{void onDownloadDone();void onDownloadFailed(Throwable failure);}
  }`,
  'net/kdt/pojavlaunch/tasks/NativesExtractor.java': `package net.kdt.pojavlaunch.tasks; public class NativesExtractor { public NativesExtractor(java.io.File f){} public void extractFromAar(java.io.File f){} }`,
  'DownloaderRegression.java': String.raw`import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.tasks.*;
import net.kdt.pojavlaunch.mirrors.*;

public class DownloaderRegression {
  static int passed;
  static class Result implements AsyncMinecraftDownloader.DoneListener {
    volatile int done,failed; volatile Throwable error;
    public void onDownloadDone(){done++;} public void onDownloadFailed(Throwable e){failed++;error=e;}
  }
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
  static void pass(String name){passed++;System.out.println("PASS "+name);}
  static void init(File root,String name)throws Exception {
    File base=new File(root,name);base.mkdirs();
    Tools.DIR_GAME_NEW=base.getAbsolutePath();Tools.DIR_DATA=Tools.DIR_GAME_NEW;
    Tools.DIR_HOME_VERSION=new File(base,"versions").getPath();Tools.DIR_HOME_LIBRARY=new File(base,"libraries").getPath();
    Tools.ASSETS_PATH=new File(base,"assets").getPath();Tools.OBSOLETE_RESOURCES_PATH=new File(base,"resources").getPath();Tools.DIR_CACHE=new File(base,"cache").getPath();
    Tools.online=true;DownloadMirror.calls.set(0);DownloadMirror.fail=false;DownloadMirror.block=false;DownloadMirror.entered=new CountDownLatch(1);
  }
  static File write(String relative,String content)throws Exception {return writeBytes(relative,content.getBytes(StandardCharsets.UTF_8));}
  static File writeBytes(String relative,byte[] content)throws Exception {File f=new File(Tools.DIR_GAME_NEW,relative);f.getParentFile().mkdirs();Files.write(f.toPath(),content);return f;}
  static void version(String id,String extra)throws Exception {write("versions/"+id+"/"+id+".json","{\"id\":\""+id+"\""+extra+"}");}
  static String client(){return ",\"downloads\":{\"client\":{\"url\":\"https://official.invalid/client.jar\",\"size\":3}}";}
  static Result run(String id){Result r=new Result();new MinecraftDownloader().start(new android.app.Activity(),null,id,r);return r;}
  static void success(Result r){check(r.done==1&&r.failed==0,"expected success: "+r.error);}
  static void failure(Result r){check(r.done==0&&r.failed==1,"expected one failed callback, not launch: "+r.error);}
  public static void main(String[] args)throws Exception {
    File root=new File(args[0]);
    init(root,"empty");version("empty",",\"libraries\":[]");success(run("empty"));check(DownloadMirror.calls.get()==0,"empty network");pass("zero-task plan");

    init(root,"cache-online");version("cached",client()+",\"libraries\":[{\"name\":\"test-lib\",\"url\":\"https://official.invalid/\"}]");
    writeBytes("versions/cached/cached.jar",new byte[]{1,2,3});writeBytes("libraries/test-lib.jar",new byte[]{4,5});
    success(run("cached"));check(DownloadMirror.calls.get()==0,"cached startup performed network I/O");pass("fully cached online launch has no HEAD/hash/download requests");
    Tools.online=false;success(run("cached"));check(DownloadMirror.calls.get()==0,"offline startup network");pass("fully cached offline launch");

    init(root,"offline-missing");Tools.online=false;version("missing",client());Result missing=run("missing");failure(missing);check(DownloadMirror.calls.get()==0,"missing offline network");pass("offline missing client fails once");
    version("missing-lib",",\"libraries\":[{\"name\":\"test-lib\",\"url\":\"https://official.invalid/\"}]");failure(run("missing-lib"));check(DownloadMirror.calls.get()==0,"missing library network");pass("offline missing required Maven library fails");
    version("excluded-lib",",\"libraries\":[{\"name\":\"excluded\",\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"osx\"}}]}]");success(run("excluded-lib"));check(DownloadMirror.calls.get()==0,"excluded library download");pass("platform-excluded library is not required offline");

    init(root,"repair");version("repair",client());File target=writeBytes("versions/repair/repair.jar",new byte[0]);success(run("repair"));check(target.length()==3,"empty cache not repaired");pass("zero-byte client is repaired");
    writeBytes("versions/repair/repair.jar",new byte[]{9});success(run("repair"));check(target.length()==3,"truncated cache not repaired");pass("truncated client is repaired");

    init(root,"network-fail");version("broken",client());DownloadMirror.fail=true;failure(run("broken"));check(!new File(Tools.DIR_HOME_VERSION,"broken/broken.jar").exists(),"partial client published");
    check(Arrays.stream(new File(Tools.DIR_HOME_VERSION,"broken").list()).noneMatch(s->s.endsWith(".part")),"partial not cleaned");pass("failed transfer never publishes a partial cache file");
    version("broken-lib",",\"libraries\":[{\"name\":\"required-lib\",\"url\":\"https://official.invalid/\"}]");failure(run("broken-lib"));pass("failed required Maven library does not report successful install");

    init(root,"substitution");version("substitution",",\"libraries\":[{\"name\":\"needs-substitution\",\"downloads\":{\"artifact\":{\"size\":999,\"url\":\"https://official.invalid/new.jar\"}}}]");
    writeBytes("libraries/substituted.jar",new byte[]{1,2,3});Tools.online=false;success(run("substitution"));check(DownloadMirror.calls.get()==0,"substituted library network");pass("substituted library does not trust obsolete original artifact size");

    init(root,"inherited");version("child",",\"inheritsFrom\":\"base\"");version("base",client());writeBytes("versions/base/base.jar",new byte[]{1,2,3});Tools.online=false;success(run("child"));check(new File(Tools.DIR_HOME_VERSION,"child/child.jar").length()==3,"inherited jar missing");pass("offline inherited cached jar copied");
    version("base",",\"inheritsFrom\":\"child\"");failure(run("child"));check(DownloadMirror.calls.get()==0,"cycle network");pass("cyclic inheritance fails without stack overflow");

    init(root,"cancel");version("cancel",client());DownloadMirror.block=true;Result cancelled=new Result();Thread thread=new Thread(()->new MinecraftDownloader().start(new android.app.Activity(),null,"cancel",cancelled));thread.start();
    check(DownloadMirror.entered.await(2,TimeUnit.SECONDS),"download did not start");thread.interrupt();thread.join(2000);check(!thread.isAlive(),"cancel did not stop wait");failure(cancelled);check(cancelled.error instanceof InterruptedException,"cancel type");pass("interrupted preparation fails instead of launching game");
    System.out.println("Downloader regression: "+passed+" passed");
  }
}`,
};

for (const annotation of ['NonNull', 'Nullable', 'Keep']) {
  stubs[`androidx/annotation/${annotation}.java`] = `package androidx.annotation; public @interface ${annotation} {}`;
}
const production = [
  'net/kdt/pojavlaunch/tasks/MinecraftDownloader.java',
  'net/kdt/pojavlaunch/tasks/SpeedCalculator.java',
  'net/kdt/pojavlaunch/JMinecraftVersionList.java',
  'net/kdt/pojavlaunch/JAssets.java',
  'net/kdt/pojavlaunch/JAssetInfo.java',
  'net/kdt/pojavlaunch/value/DependentLibrary.java',
  'net/kdt/pojavlaunch/value/MinecraftClientInfo.java',
  'net/kdt/pojavlaunch/value/MinecraftLibraryArtifact.java',
];
try {
  const files = [];
  for (const [name, content] of Object.entries(stubs)) {
    const location = path.join(work, 'src', name);
    fs.mkdirSync(path.dirname(location), { recursive: true });
    fs.writeFileSync(location, content);
    files.push(location);
  }
  const classes = path.join(work, 'classes');
  fs.mkdirSync(classes);
  const compile = spawnSync(path.join(javaHome, 'bin/javac.exe'), [
    '--release', '8', '-encoding', 'UTF-8', '-cp', gson, '-d', classes,
    ...files, ...production.map(name => path.join(source, name)),
  ], { stdio: 'inherit' });
  if (compile.error) throw compile.error;
  if (compile.status !== 0) throw new Error(`javac failed (${compile.status})`);
  const result = spawnSync(path.join(javaHome, 'bin/java.exe'), [
    '-cp', `${classes}${path.delimiter}${gson}`, 'DownloaderRegression', path.join(work, 'fixtures'),
  ], { stdio: 'inherit', timeout: 20000 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Downloader tests failed (${result.status})`);
} finally {
  // work was created by mkdtemp above and contains only this test's fixtures/classes.
  fs.rmSync(work, { recursive: true, force: true });
}
