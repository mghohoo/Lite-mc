// Offline JVM regression of verbatim production method bodies. No installer is
// executed, no network/device is used, and only a fresh OS temp folder is written.
// Reads the existing official jar; override its location with --installer <file>.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const source=path.join(root,'app_pojavlauncher/src/main/java');
const read=file=>fs.readFileSync(path.join(source,file),'utf8');
const activity=read('com/litemc/launcher/LiteForgeInstallerActivity.java');
const forge=read('com/litemc/launcher/LiteForge.java');
const multi=read('net/kdt/pojavlaunch/multirt/MultiRTUtils.java');
function method(text,marker) {
  const start=text.indexOf(marker);assert.ok(start>=0,`Missing production method: ${marker}`);
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
  throw new Error('Unclosed production method: '+marker);
}
const index=process.argv.indexOf('--installer');
const installer=path.resolve(index<0?path.join(root,'build/reports/forge-helper/forge-1.20.1-47.4.10-installer.jar'):process.argv[index+1]||'');
assert.ok(fs.existsSync(installer)&&fs.statSync(installer).isFile(),'Existing official installer fixture missing: '+installer);
const javaHome=process.env.JAVA_HOME||'C:/Program Files/OpenJDK/jdk-17.0.1';
const executable=name=>path.join(javaHome,'bin',name+(process.platform==='win32'?'.exe':''));
const work=fs.mkdtempSync(path.join(os.tmpdir(),'litemc-forge-runtime-'));
const sources={
  'com/litemc/launcher/LiteForge.java':`package com.litemc.launcher;import java.io.*;public class LiteForge {
    static final String EXTRA_MC="minecraft";
    ${method(forge,'static byte[] readStream(')}
  }`,
  // Only Intent access and Android runtime inventory are test doubles. The
  // class-header parser, runtime requirement, and nearest-positive chooser are real.
  'com/litemc/launcher/ForgeRuntimeProbe.java':`package com.litemc.launcher;
    import java.io.*;import java.util.zip.*;import net.kdt.pojavlaunch.multirt.Runtime;import net.kdt.pojavlaunch.multirt.MultiRTUtils;
    public class ForgeRuntimeProbe {
      private String minecraft;private Intent getIntent(){return new Intent();}
      private class Intent{public String getStringExtra(String key){return minecraft;}}
      public Runtime select(File installer,String minecraft)throws Exception{this.minecraft=minecraft;return selectRuntime(installer);}
      public static int header(File installer)throws Exception{return installerJavaVersion(installer);}
      ${method(activity,'private Runtime selectRuntime(')}
      ${method(activity,'private static int installerJavaVersion(')}
    }`,
  'net/kdt/pojavlaunch/multirt/MultiRTUtils.java':`package net.kdt.pojavlaunch.multirt;
    import java.util.*;import net.kdt.pojavlaunch.utils.MathUtils;
    public class MultiRTUtils {
      private static final List<Runtime> installed=new ArrayList<Runtime>();
      public static void inventory(int...versions){installed.clear();for(int version:versions)installed.add(new Runtime("java-"+version,String.valueOf(version),"test",version));}
      public static List<Runtime> getInstalledRuntimes(){return new ArrayList<Runtime>(installed);}
      public static Runtime forceReread(String name){for(Runtime runtime:installed)if(runtime.name.equals(name))return runtime;return null;}
      ${method(multi,'public static String getNearestJreName(')}
    }`,
  'ForgeRuntimeRegression.java':String.raw`import java.io.*;import java.util.*;import java.util.jar.*;import java.util.zip.*;
    import com.litemc.launcher.ForgeRuntimeProbe;import net.kdt.pojavlaunch.multirt.MultiRTUtils;
    public class ForgeRuntimeRegression {
      static int passed,failed;static File fixtures;static int fixtureId;
      interface Check{void run()throws Exception;}
      static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
      static void test(String name,Check body){try{body.run();passed++;System.out.println("PASS "+name);}catch(Throwable error){failed++;System.out.println("FAIL "+name+": "+error);}}
      static void rejects(Check body,Class<? extends Throwable> expected,String message)throws Exception{
        try{body.run();throw new AssertionError("Expected "+expected.getSimpleName());}
        catch(Throwable error){if(!expected.isInstance(error))throw new AssertionError("Unexpected failure",error);if(message!=null)check(error.getMessage()!=null&&error.getMessage().contains(message),"Wrong failure: "+error);}
      }
      static byte[] header(int major,int bodyLength,int magic)throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(buffer);
        output.writeInt(magic);output.writeShort(0);output.writeShort(major);output.write(new byte[bodyLength]);output.close();return buffer.toByteArray();
      }
      static File jar(String manifest,byte[] entry)throws Exception {
        File file=new File(fixtures,"fixture-"+(++fixtureId)+".jar");
        try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(file))){
          if(manifest!=null){output.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));output.write(manifest.getBytes("UTF-8"));output.closeEntry();}
          if(entry!=null){output.putNextEntry(new ZipEntry("fixture/Main.class"));output.write(entry);output.closeEntry();}
        }return file;
      }
      static final String MANIFEST="Manifest-Version: 1.0\r\nMain-Class: fixture.Main\r\n\r\n";
      static void runtime(File jar,String minecraft,int expected)throws Exception {
        net.kdt.pojavlaunch.multirt.Runtime selected=new ForgeRuntimeProbe().select(jar,minecraft);
        check(selected!=null&&selected.javaVersion==expected,"Minecraft "+minecraft+" expected Java "+expected+" but selected "+(selected==null?"null":selected.javaVersion));
      }
      public static void main(String[] args)throws Exception {
        fixtures=new File(args[0]);check(fixtures.mkdirs(),"Cannot create isolated fixture folder");File official=new File(args[1]);
        MultiRTUtils.inventory(21,8,17);
        test("real official 1.20.1 Forge entry is larger than eight bytes yet its header is readable",()->{
          try(JarFile jar=new JarFile(official)){
            String main=jar.getManifest().getMainAttributes().getValue("Main-Class");ZipEntry entry=jar.getEntry(main.replace('.','/')+".class");
            check(entry!=null&&entry.getSize()>8,"Official fixture does not exercise former whole-class 8-byte limit");
            int actual;try(DataInputStream input=new DataInputStream(jar.getInputStream(entry))){check(input.readInt()==0xcafebabe,"Official entry magic");input.readUnsignedShort();actual=Math.max(8,input.readUnsignedShort()-44);}
            check(ForgeRuntimeProbe.header(official)==actual,"Header parser disagrees with independent JarFile/DataInputStream read");
            System.out.println("EVIDENCE official entry="+main+" bytes="+entry.getSize()+" Java="+actual);
          }
        });
        test("real official 1.20.1 installer selects Java 17 from unordered 8/17/21 inventory",()->runtime(official,"1.20.1",17));
        final File java8=jar(MANIFEST,header(52,32768,0xcafebabe));
        test("large Java 8 class reads exact header without scanning full class",()->check(ForgeRuntimeProbe.header(java8)==8,"Wrong Java 8 requirement"));
        test("exact eight-byte Java 17 header is sufficient",()->check(ForgeRuntimeProbe.header(jar(MANIFEST,header(61,0,0xcafebabe)))==17,"Wrong Java 17 requirement"));
        test("Java 21 class header derives Java 21",()->check(ForgeRuntimeProbe.header(jar(MANIFEST,header(65,256,0xcafebabe)))==21,"Wrong Java 21 requirement"));
        test("truncated class headers fail closed",()->{for(int size=0;size<8;size++){final byte[] truncated=Arrays.copyOf(header(52,0,0xcafebabe),size);rejects(()->ForgeRuntimeProbe.header(jar(MANIFEST,truncated)),EOFException.class,null);}});
        test("invalid class magic fails closed",()->rejects(()->ForgeRuntimeProbe.header(jar(MANIFEST,header(52,64,0x12345678))),IOException.class,"格式无效"));
        test("missing manifest, main attribute or entry fail closed",()->{
          rejects(()->ForgeRuntimeProbe.header(jar(null,header(52,0,0xcafebabe))),IOException.class,"清单");
          rejects(()->ForgeRuntimeProbe.header(jar("Manifest-Version: 1.0\r\n",header(52,0,0xcafebabe))),IOException.class,"入口类");
          rejects(()->ForgeRuntimeProbe.header(jar(MANIFEST,null)),IOException.class,"入口类");
        });
        test("oversized manifest is rejected before parsing",()->rejects(()->ForgeRuntimeProbe.header(jar(MANIFEST+String.join("",Collections.nCopies(65536,"x")),header(52,0,0xcafebabe))),IOException.class,"过大"));
        test("Minecraft 1.20.4 retains Java 17 minimum",()->runtime(java8,"1.20.4",17));
        test("Minecraft 1.20.5 selects Java 21 even when installer entry targets Java 8",()->runtime(java8,"1.20.5",21));
        test("Minecraft 1.20.6 selects Java 21",()->runtime(java8,"1.20.6",21));
        test("Minecraft 1.21 selects Java 21",()->runtime(java8,"1.21",21));
        test("newer class requirement is not downgraded by Minecraft minimum",()->runtime(jar(MANIFEST,header(65,64,0xcafebabe)),"1.20.1",21));
        test("missing compatible Java version is an explicit failure",()->{
          MultiRTUtils.inventory(8);rejects(()->new ForgeRuntimeProbe().select(official,"1.20.1"),IOException.class,"运行时");MultiRTUtils.inventory(21,8,17);
        });
        System.out.println("Forge runtime regression: "+passed+" passed, "+failed+" failed. Extracted production methods + mock installed-runtime inventory; Android/JVM installer execution NOT tested.");
        if(failed>0)System.exit(1);
      }
    }`
};
try {
  const files=[];
  for(const [relative,content] of Object.entries(sources)) {
    const file=path.join(work,'src',relative);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,content);files.push(file);
  }
  files.push(path.join(source,'net/kdt/pojavlaunch/multirt/Runtime.java'),path.join(source,'net/kdt/pojavlaunch/utils/MathUtils.java'));
  const classes=path.join(work,'classes');fs.mkdirSync(classes);
  const compile=spawnSync(executable('javac'),['--release','8','-encoding','UTF-8','-d',classes,...files],{stdio:'inherit',timeout:20000});
  if(compile.error)throw compile.error;if(compile.status!==0)throw new Error('Production-method compilation failed');
  const run=spawnSync(executable('java'),['-cp',classes,'ForgeRuntimeRegression',path.join(work,'fixtures'),installer],{stdio:'inherit',timeout:15000});
  if(run.error)throw run.error;process.exitCode=run.status===0?0:1;
} finally {
  const resolved=path.resolve(work);assert.equal(path.dirname(resolved),path.resolve(os.tmpdir()));assert.ok(path.basename(resolved).startsWith('litemc-forge-runtime-'));
  fs.rmSync(resolved,{recursive:true,force:true});
}
