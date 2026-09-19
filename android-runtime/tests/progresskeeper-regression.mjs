// Compiles actual ProgressKeeper/ProgressState/listener sources on a plain JVM.
// No copied production logic, Gradle lock, Android emulator, account or network.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const work=fs.mkdtempSync(path.join(os.tmpdir(),'litemc-progress-test-'));
const javaHome=process.env.JAVA_HOME||'C:/Program Files/OpenJDK/jdk-17.0.1';
const javaSource=String.raw`
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.kdt.pojavlaunch.progresskeeper.*;

public class ProgressRegression {
  interface Case { void run() throws Exception; }
  static int passed,failed,raceCallbacks,raceIterations;
  static Object field(String name)throws Exception {Field f=ProgressKeeper.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
  static void reset()throws Exception {((Map)field("sProgressListeners")).clear();((Map)field("sProgressStates")).clear();((List)field("sTaskCountListeners")).clear();}
  static int listeners()throws Exception{return ((List)field("sTaskCountListeners")).size();}
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void start(String name){ProgressKeeper.submitProgress(name,0,0);}
  static void end(String name){ProgressKeeper.submitProgress(name,-1,-1);}
  static void test(String name,Case c)throws Exception {
    reset();try {c.run();passed++;System.out.println("PASS "+name);}
    catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e.getClass().getSimpleName()+" "+e.getMessage());}
    finally {reset();}
  }
  public static void main(String[] args)throws Exception {
    test("zero tasks invokes exactly once without retaining a listener",()->{
      AtomicInteger count=new AtomicInteger();ProgressKeeper.waitUntilDone(count::incrementAndGet);
      check(count.get()==1,"immediate callback count="+count);check(listeners()==0,"immediate listener leak");
      start("later");end("later");check(count.get()==1,"immediate callback unexpectedly ran again");
    });
    test("pending waiter removes itself after one completion",()->{
      start("work");AtomicInteger count=new AtomicInteger();ProgressKeeper.waitUntilDone(count::incrementAndGet);
      check(count.get()==0,"callback early");check(listeners()==1,"waiter not registered");end("work");
      check(count.get()==1,"callback count="+count);check(listeners()==0,"completed waiter leak");
      start("later");end("later");check(count.get()==1,"callback repeated");
    });
    test("32 waiters each self-remove without skipping listeners",()->{
      start("work");AtomicInteger count=new AtomicInteger();for(int i=0;i<32;i++)ProgressKeeper.waitUntilDone(count::incrementAndGet);
      check(listeners()==32,"registration count");end("work");check(count.get()==32,"delivered="+count);check(listeners()==0,"waiter leak");
    });
    test("task-count listener can remove itself during immediate registration",()->{
      AtomicInteger count=new AtomicInteger();TaskCountListener listener=new TaskCountListener(){public void onUpdateTaskCount(int n){count.incrementAndGet();ProgressKeeper.removeTaskCountListener(this);}};
      ProgressKeeper.addTaskCountListener(listener);check(count.get()==1,"initial event");check(listeners()==0,"registered after callback removed itself");start("later");end("later");check(count.get()==1,"self-removed listener called again");
    });
    test("multiple task-count listeners can remove themselves during delivery",()->{
      AtomicInteger count=new AtomicInteger();
      for(int i=0;i<8;i++){TaskCountListener listener=new TaskCountListener(){public void onUpdateTaskCount(int n){count.incrementAndGet();ProgressKeeper.removeTaskCountListener(this);}};ProgressKeeper.addTaskCountListener(listener,false);}
      start("work");check(count.get()==8,"delivered="+count);check(listeners()==0,"removed listeners retained");end("work");
    });
    test("waiter callback may start and finish another task without firing twice",()->{
      start("work");AtomicInteger count=new AtomicInteger();ProgressKeeper.waitUntilDone(()->{int n=count.incrementAndGet();check(n==1,"reentrant callback count="+n);start("nested");end("nested");});
      end("work");check(count.get()==1,"nested task repeated callback");check(listeners()==0,"nested waiter leak");
    });
    test("throwing completion callback does not retain its waiter",()->{
      start("work");ProgressKeeper.waitUntilDone(()->{throw new IllegalStateException("synthetic callback failure");});
      try{end("work");}catch(IllegalStateException expected){}
      check(listeners()==0,"failed completion callback retained waiter");
    });
    test("nested completion does not redeliver another waiter from an old snapshot",()->{
      start("work");AtomicInteger first=new AtomicInteger(),second=new AtomicInteger();
      ProgressKeeper.waitUntilDone(()->{check(first.incrementAndGet()==1,"first waiter repeated");start("nested");end("nested");});
      ProgressKeeper.waitUntilDone(second::incrementAndGet);end("work");
      check(first.get()==1,"first waiter count="+first);check(second.get()==1,"second waiter count="+second);check(listeners()==0,"nested snapshot listener leak");
    });
    test("register-versus-complete race invokes exactly once in 1000 rounds",()->{
      for(int i=0;i<1000;i++){
        String name="race-"+i;start(name);AtomicInteger count=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<>();CountDownLatch go=new CountDownLatch(1);
        Thread register=new Thread(()->{try{go.await();ProgressKeeper.waitUntilDone(count::incrementAndGet);}catch(Throwable e){error.set(e);}});
        Thread complete=new Thread(()->{try{go.await();end(name);}catch(Throwable e){error.set(e);}});
        register.start();complete.start();go.countDown();register.join(2000);complete.join(2000);
        check(!register.isAlive()&&!complete.isAlive(),"deadlock round="+i);check(error.get()==null,"concurrent exception");
        check(count.get()==1,"race callback count="+count+" round="+i);check(listeners()==0,"race waiter leak");
        raceCallbacks+=count.get();raceIterations++;
      }
    });
    System.out.println("ProgressKeeper regression: "+passed+" passed, "+failed+" failed; race rounds="+raceIterations+", race callbacks="+raceCallbacks+", final tasks="+ProgressKeeper.getTaskCount()+", retained waiters="+listeners());
    if(failed>0)System.exit(1);
  }
}`;

try {
  const source=path.join(work,'ProgressRegression.java');fs.writeFileSync(source,javaSource);
  const classes=path.join(work,'classes');fs.mkdirSync(classes);
  const packageDir=path.join(root,'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/progresskeeper');
  const production=['ProgressKeeper.java','ProgressState.java','ProgressListener.java','TaskCountListener.java'].map(file=>path.join(packageDir,file));
  const compile=spawnSync(path.join(javaHome,'bin',process.platform==='win32'?'javac.exe':'javac'),['--release','8','-encoding','UTF-8','-d',classes,source,...production],{stdio:'inherit',timeout:30000});
  if(compile.error)throw compile.error;if(compile.status!==0)throw new Error(`Progress compilation failed (${compile.status})`);
  const result=spawnSync(path.join(javaHome,'bin',process.platform==='win32'?'java.exe':'java'),['-cp',classes,'ProgressRegression'],{stdio:'inherit',timeout:30000});
  if(result.error)throw result.error;process.exitCode=result.status??1;
} finally {
  // Only remove this invocation's mkdtemp directory after checking its resolved scope.
  const resolved=fs.realpathSync(work),parent=fs.realpathSync(os.tmpdir());
  if(path.dirname(resolved)!==parent||!path.basename(resolved).startsWith('litemc-progress-test-'))throw new Error('Refusing to remove an unexpected fixture path');
  fs.rmSync(resolved,{recursive:true,force:true});
}
