// Default: real LiteMods/LitePacks with deterministic Android/network test doubles.
// Uses cached dependencies and an invocation-owned temporary directory. No Gradle.
// --live-smoke explicitly enables real HTTPS for fixed FO 1.20.1 (client files <=50 MiB).
// No mode uses user credentials, an Android device or an installed game instance.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
function jarBelow(dir) {
  if(!fs.existsSync(dir))return null;
  for(const item of fs.readdirSync(dir,{withFileTypes:true})) {
    const file=path.join(dir,item.name);
    if(item.isFile()&&item.name.endsWith('.jar'))return file;
    if(item.isDirectory()){const found=jarBelow(file);if(found)return found;}
  }
  return null;
}
const json=jarBelow(path.join(process.env.USERPROFILE||os.homedir(),'.gradle/caches/modules-2/files-2.1/org.json/json'));
if(!json)throw new Error('Cached org.json JAR is required; this test does not download dependencies.');
const sdk=process.env.ANDROID_SDK_ROOT||process.env.ANDROID_HOME||path.join(process.env.LOCALAPPDATA||'','Android/Sdk');
const android=path.join(sdk,'platforms/android-37.0/android.jar');
if(!fs.existsSync(android))throw new Error('Android SDK platform android-37.0/android.jar is required for compilation.');
const javaHome=process.env.JAVA_HOME||'C:/Program Files/OpenJDK/jdk-17.0.1';
const liveSmoke=process.argv.includes('--live-smoke');
if(liveSmoke)console.log('LIVE NETWORK OPT-IN: real official APIs + FO 1.20.1 client files, declared total <=50 MiB; archive <=1 MiB separately. Temporary directory only, no game install.');
const inspectArgument=process.argv.indexOf('--inspect-archive');
let inspectArchive;
if(inspectArgument>=0) {
  const candidate=process.argv[inspectArgument+1];
  if(!candidate||!fs.existsSync(candidate)||!fs.statSync(candidate).isFile())throw new Error('--inspect-archive requires an existing local archive. No download is performed.');
  if(process.argv.includes('--search-only')||liveSmoke)throw new Error('Choose only one mode: search-only, inspect-archive or live-smoke.');
  inspectArchive=fs.realpathSync(candidate);
}
const work=fs.mkdtempSync(path.join(os.tmpdir(),'litemc-packs-test-'));
const sources={
  'android/content/Context.java': `package android.content; public class Context {
    private final java.io.File root; public Context(java.io.File root){this.root=root;root.mkdirs();}
    public Context getApplicationContext(){return this;} public java.io.File getFilesDir(){return root;}
    public java.io.File getCacheDir(){java.io.File c=new java.io.File(root,"cache");c.mkdirs();return c;}
  }`,
  'android/util/AtomicFile.java': `package android.util; public class AtomicFile {
    private final java.io.File file; public AtomicFile(java.io.File file){this.file=file;}
    public byte[] readFully()throws java.io.IOException{return java.nio.file.Files.readAllBytes(file.toPath());}
    public java.io.FileOutputStream startWrite()throws java.io.IOException{file.getParentFile().mkdirs();return new java.io.FileOutputStream(file);}
    public void finishWrite(java.io.FileOutputStream out)throws java.io.IOException{out.close();}
    public void failWrite(java.io.FileOutputStream out)throws java.io.IOException{out.close();}
  }`,
  'net/kdt/pojavlaunch/Tools.java': `package net.kdt.pojavlaunch; public class Tools {public static String DIR_GAME_NEW;}`,
  'com/litemc/launcher/LiteVersions.java': `package com.litemc.launcher; public class LiteVersions {
    public static String loaderName(String value){return value;}
    public static void requireCompatibleVersion(String version){}
    public static String id(String value){if(!value.matches("[A-Za-z0-9._-]{1,100}")||value.contains(".."))throw new IllegalArgumentException("invalid id");return value;}
  }`,
  'com/litemc/launcher/LiteNetwork.java': String.raw`package com.litemc.launcher;
import java.io.*;import java.nio.file.*;import java.util.*;import org.json.*;
public class LiteNetwork {
  public static final byte[] PAYLOAD={1,2,3};
  public static final String HASH="7037807198c22a7d2b0807371d763779a84fdfcf";
  public static final List<String> urls=new ArrayList<>();
  public static final List<File> destinations=new ArrayList<>();
  public static final Map<String,String> responses=new HashMap<>();
  public static JSONObject searchReply=new JSONObject(); public static boolean failDownload;
  public static void reset(){urls.clear();destinations.clear();responses.clear();searchReply=new JSONObject();failDownload=false;}
  public static JSONObject getJson(String u,String[] hosts,Map<String,String> headers)throws Exception{return new JSONObject(new String(request("GET",u,hosts,headers,null),"UTF-8"));}
  public static byte[] request(String method,String u,String[] hosts,Map<String,String> headers,byte[] body)throws Exception{
    urls.add(u);String p=new java.net.URI(u).getPath();
    if(p.endsWith("/search"))return searchReply.toString().getBytes("UTF-8");
    String result=responses.containsKey(u)?responses.get(u):responses.get(p);
    if(result==null)throw new AssertionError("Unmocked metadata endpoint: "+p);
    return result.getBytes("UTF-8");
  }
  public static void download(String u,String[] hosts,File destination,String sha1)throws Exception{
    urls.add(u);destinations.add(destination);
    if(failDownload)throw new IOException("Synthetic transfer failure");
    if(!HASH.equalsIgnoreCase(sha1))throw new IOException("Synthetic digest mismatch");
    destination.getParentFile().mkdirs();Files.write(destination.toPath(),PAYLOAD);
  }
}`,
  'com/litemc/launcher/MobilePacksRegression.java': String.raw`package com.litemc.launcher;
import java.io.*;import java.nio.file.*;import java.net.*;import java.security.*;import java.util.*;import java.lang.reflect.*;import java.util.zip.*;import org.json.*;
import net.kdt.pojavlaunch.Tools;
public class MobilePacksRegression {
  interface Action{void run()throws Exception;}
  static File base;static int passed,failed,serial;static LiteMods mods;static android.content.Context context;
  static final String HASH=LiteNetwork.HASH,P="P1234567",V="V0000001",CF="12345",CFF="77777";
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void test(String name,Action action)throws Exception{
    LiteNetwork.reset();File fixture=new File(base,"case-"+(++serial));fixture.mkdirs();context=new android.content.Context(new File(fixture,"private"));Tools.DIR_GAME_NEW=new File(fixture,"game").getPath();mods=new LiteMods(context);
    try{action.run();passed++;System.out.println("PASS "+name);}catch(Throwable error){failed++;System.out.println("FAIL "+name+": "+error.getClass().getSimpleName()+" "+error.getMessage());}
  }
  static void rejects(Action action)throws Exception{boolean rejected=false;try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException|SecurityException|org.json.JSONException expected){rejected=true;}check(rejected,"Expected explicit rejection");}
  static JSONObject args(String provider,String kind)throws Exception{return new JSONObject().put("provider",provider).put("kind",kind);}
  static String param(String url,String name)throws Exception{
    String query=new URI(url).getRawQuery();if(query==null)return null;
    for(String item:query.split("&")){String[] parts=item.split("=",2);if(URLDecoder.decode(parts[0],"UTF-8").equals(name))return parts.length==1?"":URLDecoder.decode(parts[1],"UTF-8");}return null;
  }
  static String last(){return LiteNetwork.urls.get(LiteNetwork.urls.size()-1);}
  static JSONArray facets()throws Exception{return new JSONArray(param(last(),"facets"));}
  static boolean facet(String value)throws Exception{JSONArray list=facets();for(int i=0;i<list.length();i++){JSONArray row=list.getJSONArray(i);for(int j=0;j<row.length();j++)if(value.equals(row.getString(j)))return true;}return false;}
  static void searchFixture()throws Exception{
    JSONObject modrinth=new JSONObject().put("project_id",P).put("title","Test pack").put("description","<b>plain text</b>").put("downloads",42);
    JSONObject curse=new JSONObject().put("id",12345).put("name","Test pack").put("summary","description").put("downloadCount",42);
    LiteNetwork.searchReply=new JSONObject().put("hits",new JSONArray().put(modrinth)).put("data",new JSONArray().put(curse));
  }
  static void configureCurse()throws Exception{mods.handle("mods.config",new JSONObject().put("curseforgeKey","fixture-only-not-a-real-api-key"));}
  static Object invoke(Object target,String method,Class<?>[] types,Object...args)throws Exception{
    try{return target.getClass().getMethod(method,types).invoke(target,args);}
    catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new AssertionError(cause);}
  }
  static JSONObject packArgs(String provider)throws Exception{return args(provider,"modpack").put("projectId",provider.equals("modrinth")?P:CF).put("versionId",provider.equals("modrinth")?V:CFF);}
  static JSONObject mrVersion(String project,String version)throws Exception{
    return new JSONObject().put("id",version).put("project_id",project).put("name","Pack version 1").put("version_number","1.0")
      .put("date_published","2026-01-01T00:00:00Z").put("version_type","release").put("game_versions",new JSONArray().put("1.20.1")).put("loaders",new JSONArray().put("fabric"))
      .put("files",new JSONArray().put(new JSONObject().put("filename","测试 整合包.mrpack").put("primary",true).put("url","https://cdn.modrinth.com/data/"+project+"/versions/"+version+"/pack.mrpack").put("hashes",new JSONObject().put("sha1",HASH))));
  }
  static void mrFixture()throws Exception{
    LiteNetwork.responses.put("/v2/project/"+P,new JSONObject().put("id",P).put("project_type","modpack").toString());
    JSONObject version=mrVersion(P,V);LiteNetwork.responses.put("/v2/version/"+V,version.toString());
    LiteNetwork.responses.put("/v2/project/"+P+"/version",new JSONArray().put(version).toString());
  }
  static JSONObject cfFile()throws Exception{
    return new JSONObject().put("id",Long.parseLong(CFF)).put("modId",Long.parseLong(CF)).put("isAvailable",true).put("displayName","Pack release")
      .put("fileName","测试 整合包.zip").put("fileDate","2026-01-01T00:00:00Z").put("gameVersions",new JSONArray().put("1.20.1").put("Fabric"))
      .put("downloadUrl","https://edge.forgecdn.net/files/777/777/pack.zip").put("hashes",new JSONArray().put(new JSONObject().put("algo",1).put("value",HASH)));
  }
  static void cfFixture()throws Exception{
    configureCurse();LiteNetwork.responses.put("/v1/mods/"+CF,new JSONObject().put("data",new JSONObject().put("id",Long.parseLong(CF)).put("classId",4471)).toString());
    JSONObject file=cfFile(),decoy=cfFile().put("id",88888).put("fileName","newer-but-not-selected.zip").put("fileDate","2026-09-01T00:00:00Z");LiteNetwork.responses.put("/v1/mods/"+CF+"/files",new JSONObject().put("data",new JSONArray().put(decoy).put(file)).toString());
    LiteNetwork.responses.put("/v1/mods/"+CF+"/files/"+CFF,new JSONObject().put("data",file).toString());
    LiteNetwork.responses.put("/v1/mods/"+CF+"/files/"+CFF+"/download-url",new JSONObject().put("data",file.getString("downloadUrl")).toString());
  }
  static File download(String provider)throws Exception{return(File)invoke(mods,"downloadPack",new Class<?>[]{JSONObject.class},packArgs(provider));}
  static JSONObject mrIndex()throws Exception{return new JSONObject().put("formatVersion",1).put("game","minecraft").put("versionId","1.0").put("name","Fixture pack")
    .put("dependencies",new JSONObject().put("minecraft","1.20.1").put("fabric-loader","0.16.0")).put("files",new JSONArray());}
  static JSONObject cfIndex()throws Exception{return new JSONObject().put("manifestType","minecraftModpack").put("manifestVersion",1).put("name","CF fixture").put("version","1.0").put("overrides","overrides")
    .put("minecraft",new JSONObject().put("version","1.20.1").put("modLoaders",new JSONArray().put(new JSONObject().put("id","fabric-0.16.0").put("primary",true))))
    .put("files",new JSONArray().put(new JSONObject().put("projectID",Long.parseLong(CF)).put("fileID",Long.parseLong(CFF)).put("required",true)));}
  static String digest(byte[] bytes,String algorithm)throws Exception{StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance(algorithm).digest(bytes))out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
  static JSONObject mrFile(String name)throws Exception{return new JSONObject().put("path",name).put("hashes",new JSONObject().put("sha1",HASH).put("sha512",digest(LiteNetwork.PAYLOAD,"SHA-512")))
    .put("downloads",new JSONArray().put("https://cdn.modrinth.com/data/test/fixture.jar")).put("fileSize",3).put("env",new JSONObject().put("client","required").put("server","required"));}
  static File zip(Map<String,byte[]> files)throws Exception{
    File file=new File(context.getCacheDir(),"fixture-"+UUID.randomUUID()+".zip");try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(file))){for(Map.Entry<String,byte[]> entry:files.entrySet()){output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();}}return file;
  }
  static LinkedHashMap<String,byte[]> mrArchive(JSONObject index)throws Exception{LinkedHashMap<String,byte[]> files=new LinkedHashMap<>();files.put("modrinth.index.json",index.toString().getBytes("UTF-8"));return files;}
  static Object inspect(File file)throws Exception{
    try{return Class.forName("com.litemc.launcher.LitePacks").getMethod("inspect",File.class).invoke(null,file);}
    catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new AssertionError(cause);}
  }
  static File prepare(Object plan)throws Exception{File target=new File(context.getFilesDir(),"target-"+UUID.randomUUID());target.mkdirs();invoke(plan,"prepare",new Class<?>[]{File.class,LiteMods.class},target,mods);return target;}
  static void rejectArchive(Map<String,byte[]> files)throws Exception{File archive=zip(files);rejects(()->prepare(inspect(archive)));}
  static String field(Object object,String name)throws Exception{return(String)object.getClass().getField(name).get(object);}

  // A fixture-only in-memory provider makes the real CurseForge encrypted-key code
  // callable on the JVM. It stores no real user credential and is never installed in Android.
  public static class TestProvider extends Provider{public TestProvider(){super("LitePackFixture",1.0,"Test only");put("KeyStore.AndroidKeyStore",FixtureStore.class.getName());}}
  public static class FixtureStore extends KeyStoreSpi {
    static final KeyPair PAIR=create();static KeyPair create(){try{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}catch(Exception e){throw new RuntimeException(e);}}
    public Key engineGetKey(String a,char[] p){return PAIR.getPrivate();}
    public java.security.cert.Certificate engineGetCertificate(String a){return new java.security.cert.Certificate("fixture"){public byte[] getEncoded(){return new byte[0];}public void verify(PublicKey p){}public void verify(PublicKey p,String s){}public String toString(){return "fixture certificate";}public PublicKey getPublicKey(){return PAIR.getPublic();}};}
    public java.security.cert.Certificate[] engineGetCertificateChain(String a){return new java.security.cert.Certificate[]{engineGetCertificate(a)};}
    public Date engineGetCreationDate(String a){return new Date(0);}public void engineSetKeyEntry(String a,Key k,char[] p,java.security.cert.Certificate[] c){}public void engineSetKeyEntry(String a,byte[] k,java.security.cert.Certificate[] c){}public void engineSetCertificateEntry(String a,java.security.cert.Certificate c){}public void engineDeleteEntry(String a){}
    public Enumeration<String> engineAliases(){return Collections.enumeration(Collections.singleton("lite.curse.v1"));}public boolean engineContainsAlias(String a){return true;}public int engineSize(){return 1;}public boolean engineIsKeyEntry(String a){return true;}public boolean engineIsCertificateEntry(String a){return false;}public String engineGetCertificateAlias(java.security.cert.Certificate c){return "lite.curse.v1";}public void engineStore(OutputStream s,char[] p){}public void engineLoad(InputStream s,char[] p){}
  }

  public static void main(String[] command)throws Exception{
    base=new File(command[0]);base.mkdirs();
    if(command.length>2&&"inspect-archive".equals(command[1])){
      Object plan=inspect(new File(command[2]));System.out.println(new JSONObject().put("name",field(plan,"name")).put("minecraft",field(plan,"minecraft")).put("loader",field(plan,"loader")).put("loaderVersion",field(plan,"loaderVersion")).toString());return;
    }
    Security.addProvider(new TestProvider());
    test("Modrinth packs browse with no instance, version, loader or query",()->{
      searchFixture();JSONObject result=mods.handle("mods.search",args("modrinth","modpack"));check(result.getJSONArray("items").length()==1,"missing pack result");
      check(facet("project_type:modpack"),"wrong project type");check(facets().length()==1,"unwanted instance/version/loader facet");check(param(last(),"query")==null||"".equals(param(last(),"query")),"blank browsing query changed");check(!new File(Tools.DIR_GAME_NEW).exists(),"search created an instance");
    });
    test("pack search encodes Unicode query without requiring an instance",()->{
      searchFixture();String query="轻量 pack & adventure";mods.handle("mods.search",args("modrinth","modpack").put("query",query));check(query.equals(param(last(),"query")),"query lost or query injection");
    });
    test("stale instance Minecraft version and loader cannot constrain pack browsing",()->{
      searchFixture();mods.handle("mods.search",args("modrinth","modpack").put("query","").put("version","1.20.1").put("loader","forge"));check(facets().length()==1&&facet("project_type:modpack"),"instance compatibility leaked into independent pack browsing");
    });
    test("ordinary Fabric and Forge searches keep compatibility filters",()->{
      for(String loader:new String[]{"fabric","forge"}){searchFixture();mods.handle("mods.search",args("modrinth","mod").put("query","test").put("version","1.20.1").put("loader",loader));check(facet("project_type:mod")&&facet("versions:1.20.1")&&facet("categories:"+loader),"ordinary mod filters changed");}
    });
    test("ordinary mods still reject missing version or vanilla loader",()->{
      rejects(()->mods.handle("mods.search",args("modrinth","mod").put("query","test").put("loader","fabric")));
      rejects(()->mods.handle("mods.search",args("modrinth","mod").put("query","test").put("version","1.20.1").put("loader","vanilla")));check(LiteNetwork.urls.isEmpty(),"invalid search performed network");
    });
    test("invalid provider, kind, filter and oversized query reject before network",()->{
      rejects(()->mods.handle("mods.search",args("unknown","modpack")));rejects(()->mods.handle("mods.search",args("modrinth","unknown")));
      rejects(()->mods.handle("mods.search",args("modrinth","mod").put("query","test").put("loader","fabric").put("version","../escape")));
      rejects(()->mods.handle("mods.search",args("modrinth","modpack").put("query",String.join("",Collections.nCopies(81,"x")))));check(LiteNetwork.urls.isEmpty(),"invalid search performed network");
    });
    test("resource-pack searches keep their own type and version filter",()->{
      searchFixture();mods.handle("mods.search",args("modrinth","resourcepack").put("version","1.20.1").put("query","test"));check(facet("project_type:resourcepack")&&facet("versions:1.20.1"),"resourcepack search regressed");check(facets().length()==2,"resourcepack got loader filter");
    });
    test("CurseForge packs use class 4471 without mandatory game version",()->{
      configureCurse();searchFixture();mods.handle("mods.search",args("curseforge","modpack"));check("4471".equals(param(last(),"classId")),"incorrect pack class");check(param(last(),"gameVersion")==null,"empty gameVersion should be omitted");check(param(last(),"modLoaderType")==null,"pack got loader constraint");check(param(last(),"searchFilter")==null||"".equals(param(last(),"searchFilter")),"browse query changed");
    });
    test("CurseForge query is encoded and stale instance filters are ignored for packs",()->{
      configureCurse();searchFixture();mods.handle("mods.search",args("curseforge","modpack").put("version","1.20.1").put("query","轻量 & test").put("loader","fabric"));check(param(last(),"gameVersion")==null,"instance version leaked");check("轻量 & test".equals(param(last(),"searchFilter")),"query encoding");check(param(last(),"modLoaderType")==null,"instance loader leaked");
    });
    test("CurseForge without a configured key fails without a network request",()->{
      rejects(()->mods.handle("mods.search",args("curseforge","modpack")));check(LiteNetwork.urls.isEmpty(),"missing key called provider");
    });
    if(command.length>1&&"search-only".equals(command[1])){System.out.println("Mobile pack search regression: "+passed+" passed, "+failed+" failed; pack archive tests not requested.");if(failed>0)System.exit(1);return;}
    test("Modrinth official pack versions expose exact IDs and compatibility",()->{
      mrFixture();JSONArray items=mods.handle("mods.packVersions",packArgs("modrinth")).getJSONArray("items");check(items.length()==1,"version count");JSONObject item=items.getJSONObject(0);check(V.equals(item.getString("id")),"wrong version id");check(item.getString("name").length()>0,"no version label");check(item.getJSONArray("gameVersions").getString(0).equals("1.20.1"),"game version");check(item.getJSONArray("loaders").getString(0).equals("fabric"),"loader");
    });
    test("selected Modrinth archive downloads to private cache and reuses verified bytes",()->{
      mrFixture();File first=download("modrinth");check(first.length()==3,"archive not written");check(first.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator),"download escaped private cache");check(first.getName().endsWith(".mrpack"),"lost pack extension");int count=LiteNetwork.destinations.size();File second=download("modrinth");check(second.equals(first),"cache changed");check(LiteNetwork.destinations.size()==count,"verified archive downloaded twice");
    });
    test("selected Modrinth version must belong to the requested project",()->{
      mrFixture();LiteNetwork.responses.put("/v2/version/"+V,mrVersion("OTHER123",V).toString());rejects(()->download("modrinth"));check(LiteNetwork.destinations.isEmpty(),"foreign version downloaded");
    });
    test("Modrinth project slug resolves to its canonical project ID",()->{
      mrFixture();LiteNetwork.responses.put("/v2/project/test-pack",LiteNetwork.responses.get("/v2/project/"+P));
      File file=(File)invoke(mods,"downloadPack",new Class<?>[]{JSONObject.class},packArgs("modrinth").put("projectId","test-pack"));check(file.length()==3,"slug pack was not downloaded");
    });
    test("Modrinth selected file requires a primary mrpack and the exact version ID",()->{
      mrFixture();JSONObject version=mrVersion(P,V);version.getJSONArray("files").getJSONObject(0).put("primary",false);LiteNetwork.responses.put("/v2/version/"+V,version.toString());rejects(()->download("modrinth"));check(LiteNetwork.destinations.isEmpty(),"nonprimary pack downloaded");
      mrFixture();LiteNetwork.responses.put("/v2/version/"+V,mrVersion(P,"OTHER001").toString());rejects(()->download("modrinth"));check(LiteNetwork.destinations.isEmpty(),"wrong version ID downloaded");
    });
    test("pack downloads reject non-pack projects and absent official hashes",()->{
      mrFixture();LiteNetwork.responses.put("/v2/project/"+P,new JSONObject().put("id",P).put("project_type","mod").toString());rejects(()->download("modrinth"));check(LiteNetwork.destinations.isEmpty(),"mod project downloaded as pack");
      mrFixture();JSONObject version=mrVersion(P,V);version.getJSONArray("files").getJSONObject(0).put("hashes",new JSONObject());LiteNetwork.responses.put("/v2/version/"+V,version.toString());rejects(()->download("modrinth"));check(LiteNetwork.destinations.isEmpty(),"unhashed pack downloaded");
    });
    test("CurseForge exact pack file is downloaded rather than newest unrelated file",()->{
      cfFixture();File file=download("curseforge");check(file.length()==3,"CurseForge selected archive absent");check(file.getName().equals("测试 整合包.zip"),"newest file substituted for selected ID");check(LiteNetwork.urls.stream().anyMatch(u->u.endsWith("/files/"+CFF)),"no exact file request");check(LiteNetwork.urls.stream().noneMatch(u->u.contains("/files?")),"download selected using first page rather than exact file endpoint");
    });
    test("CurseForge versions separate Minecraft versions from loader labels",()->{
      cfFixture();JSONArray items=mods.handle("mods.packVersions",packArgs("curseforge")).getJSONArray("items");check(items.length()==2,"official versions lost");JSONObject item=items.getJSONObject(1);check(CFF.equals(item.getString("id")),"exact version ID lost");check(item.getJSONArray("gameVersions").length()==1&&item.getJSONArray("gameVersions").getString(0).equals("1.20.1"),"loader label in Minecraft version list");check(item.getJSONArray("loaders").getString(0).equals("fabric"),"Fabric capability missing");
    });
    test("CurseForge selected metadata must match both project and file IDs",()->{
      cfFixture();LiteNetwork.responses.put("/v1/mods/"+CF+"/files/"+CFF,new JSONObject().put("data",cfFile().put("modId",99999)).toString());rejects(()->download("curseforge"));check(LiteNetwork.destinations.isEmpty(),"foreign project file downloaded");
      cfFixture();LiteNetwork.responses.put("/v1/mods/"+CF+"/files/"+CFF,new JSONObject().put("data",cfFile().put("id",99999)).toString());rejects(()->download("curseforge"));check(LiteNetwork.destinations.isEmpty(),"wrong file ID downloaded");
    });
    test("CurseForge pack dependency resolves an exact MOD file, not only class-4471 packs",()->{
      cfFixture();LiteNetwork.responses.put("/v1/mods/"+CF,new JSONObject().put("data",new JSONObject().put("id",Long.parseLong(CF)).put("classId",6)).toString());
      JSONObject dependency=cfFile().put("fileName","dependency.jar").put("downloadUrl","https://edge.forgecdn.net/files/777/777/dependency.jar");
      LiteNetwork.responses.put("/v1/mods/"+CF+"/files",new JSONObject().put("data",new JSONArray().put(dependency)).toString());LiteNetwork.responses.put("/v1/mods/"+CF+"/files/"+CFF,new JSONObject().put("data",dependency).toString());
      JSONObject result=(JSONObject)invoke(mods,"cursePackFile",new Class<?>[]{String.class,String.class},CF,CFF);check(result.getString("name").equals("dependency.jar"),"wrong dependency file");check(result.getString("sha1").equals(HASH),"dependency hash missing");
    });
    test("safe MR archive exposes its exact game and loader requirements",()->{
      Object plan=inspect(zip(mrArchive(mrIndex())));check(field(plan,"name").equals("Fixture pack"),"name");check(field(plan,"minecraft").equals("1.20.1"),"game version");check(field(plan,"loader").equals("fabric"),"loader");check(field(plan,"loaderVersion").equals("0.16.0"),"loader version");prepare(plan);
    });
    test("MR required and optional client files install while server-only files are skipped",()->{
      JSONObject required=mrFile("mods/required.jar"),optional=mrFile("mods/optional.jar"),server=mrFile("mods/server.jar");optional.getJSONObject("env").put("client","optional");server.getJSONObject("env").put("client","unsupported");
      JSONObject index=mrIndex().put("files",new JSONArray().put(required).put(optional).put(server));File target=prepare(inspect(zip(mrArchive(index))));check(new File(target,"mods/required.jar").isFile(),"missing required");check(new File(target,"mods/optional.jar").isFile(),"missing optional");check(!new File(target,"mods/server.jar").exists(),"server-only downloaded");check(LiteNetwork.destinations.size()==2,"unexpected client file count");
    });
    test("client overrides win over shared overrides without copying server overrides",()->{
      LinkedHashMap<String,byte[]> files=mrArchive(mrIndex());files.put("overrides/config/test.txt","shared".getBytes("UTF-8"));files.put("client-overrides/config/test.txt","client".getBytes("UTF-8"));files.put("server-overrides/secret.txt","server".getBytes("UTF-8"));File target=prepare(inspect(zip(files)));check(new String(Files.readAllBytes(new File(target,"config/test.txt").toPath()),"UTF-8").equals("client"),"client override precedence");check(!new File(target,"secret.txt").exists(),"server override copied");
    });
    test("pack extraction rejects traversal and absolute ZIP entry paths",()->{
      for(String name:new String[]{"../outside.txt","/absolute.txt","overrides/../../outside.txt","overrides/C:/escape.txt","overrides/..\\outside.txt"}){LinkedHashMap<String,byte[]> files=mrArchive(mrIndex());files.put(name,new byte[]{1});rejectArchive(files);}
    });
    test("pack manifests reject missing hashes, foreign URLs and invalid format",()->{
      JSONObject file=mrFile("mods/test.jar");file.put("hashes",new JSONObject());rejectArchive(mrArchive(mrIndex().put("files",new JSONArray().put(file))));
      file=mrFile("mods/test.jar");file.put("downloads",new JSONArray().put("https://attacker.invalid/test.jar"));rejectArchive(mrArchive(mrIndex().put("files",new JSONArray().put(file))));
      rejectArchive(mrArchive(mrIndex().put("formatVersion",99)));
    });
    test("MR files are verified against both declared length and SHA512",()->{
      JSONObject file=mrFile("mods/test.jar").put("fileSize",4);rejectArchive(mrArchive(mrIndex().put("files",new JSONArray().put(file))));
      file=mrFile("mods/test.jar");file.getJSONObject("hashes").put("sha512",String.join("",Collections.nCopies(128,"0")));rejectArchive(mrArchive(mrIndex().put("files",new JSONArray().put(file))));
    });
    test("ambiguous pack formats and oversized manifests are rejected",()->{
      LinkedHashMap<String,byte[]> files=mrArchive(mrIndex());files.put("manifest.json",cfIndex().toString().getBytes("UTF-8"));rejectArchive(files);
      char[] padding=new char[2*1024*1024+1];Arrays.fill(padding,'x');rejectArchive(mrArchive(mrIndex().put("description",new String(padding))));
    });
    test("ZIP entry count is bounded even for tiny ignored files",()->{
      LinkedHashMap<String,byte[]> files=mrArchive(mrIndex());for(int i=0;i<10001;i++)files.put("ignored/"+i,new byte[0]);rejectArchive(files);
    });
    test("CurseForge manifest prepares the exact dependency JAR and overrides",()->{
      cfFixture();JSONObject dependency=cfFile().put("fileName","dependency.jar").put("downloadUrl","https://edge.forgecdn.net/files/777/777/dependency.jar");LiteNetwork.responses.put("/v1/mods/"+CF+"/files/"+CFF,new JSONObject().put("data",dependency).toString());
      LinkedHashMap<String,byte[]> files=new LinkedHashMap<>();files.put("manifest.json",cfIndex().toString().getBytes("UTF-8"));files.put("overrides/config/a.txt","config".getBytes("UTF-8"));Object plan=inspect(zip(files));check(field(plan,"minecraft").equals("1.20.1")&&field(plan,"loader").equals("fabric"),"CF requirements incorrect");File target=prepare(plan);check(new File(target,"mods/dependency.jar").length()==3,"CF dependency missing");check(new File(target,"config/a.txt").isFile(),"CF override missing");
    });
    test("existing instance content is never overwritten by pack preparation",()->{
      Object plan=inspect(zip(mrArchive(mrIndex())));File target=new File(context.getFilesDir(),"existing");target.mkdirs();File keep=new File(target,"world.dat");Files.write(keep.toPath(),new byte[]{7,8});rejects(()->invoke(plan,"prepare",new Class<?>[]{File.class,LiteMods.class},target,mods));check(Arrays.equals(Files.readAllBytes(keep.toPath()),new byte[]{7,8}),"existing content modified");
    });
    test("failed client download cannot publish a partially prepared instance",()->{
      JSONObject index=mrIndex().put("files",new JSONArray().put(mrFile("mods/test.jar")));Object plan=inspect(zip(mrArchive(index)));File target=new File(context.getFilesDir(),"failed");target.mkdirs();LiteNetwork.failDownload=true;rejects(()->invoke(plan,"prepare",new Class<?>[]{File.class,LiteMods.class},target,mods));check(!new File(target,"mods/test.jar").exists(),"partial file published");
    });
    System.out.println("Mobile packs regression: "+passed+" passed, "+failed+" failed. JVM fixtures only; real API/Android/game execution unverified.");if(failed>0)System.exit(1);
  }
}`,
};

if(liveSmoke) {
  if(process.argv.includes('--search-only'))throw new Error('Choose either --search-only or --live-smoke.');
  delete sources['com/litemc/launcher/LiteNetwork.java'];
  delete sources['com/litemc/launcher/MobilePacksRegression.java'];
  sources['com/litemc/launcher/LivePacksSmoke.java']=String.raw`package com.litemc.launcher;
import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;import java.util.zip.*;import java.util.stream.*;import org.json.*;import net.kdt.pojavlaunch.Tools;
/** Explicit opt-in live test: fixed FO 1.20.1, at most 50 MiB declared client content. */
public final class LivePacksSmoke {
  static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
  static JSONObject index(File archive)throws Exception{try(ZipFile zip=new ZipFile(archive);InputStream in=zip.getInputStream(zip.getEntry("modrinth.index.json"));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;){check(out.size()+n<=2*1024*1024,"Index exceeds smoke limit");out.write(buffer,0,n);}return new JSONObject(new String(out.toByteArray(),"UTF-8"));}}
  static String sha1(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-1");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
  static boolean contains(JSONArray list,String value){if(list==null)return false;for(int i=0;i<list.length();i++)if(value.equals(list.optString(i)))return true;return false;}
  public static void main(String[] args)throws Exception{
    File fixture=new File(args[0]);fixture.mkdirs();File reference=new File(args[1]);
    JSONObject referenceIndex=index(reference);String release=referenceIndex.getString("versionId");
    check("Fabulously Optimized".equals(referenceIndex.getString("name")),"Only Fabulously Optimized is allowed in live smoke");
    check("1.20.1".equals(referenceIndex.getJSONObject("dependencies").getString("minecraft")),"Live smoke only permits Minecraft 1.20.1");
    LiteMods mods=new LiteMods(new android.content.Context(new File(fixture,"private")));Tools.DIR_GAME_NEW=new File(fixture,"unused-game-root").getPath();
    JSONArray results=mods.handle("mods.search",new JSONObject().put("provider","modrinth").put("kind","modpack").put("query","Fabulously Optimized")).getJSONArray("items");
    String project=null;for(int i=0;i<results.length();i++){JSONObject item=results.getJSONObject(i);if("Fabulously Optimized".equals(item.getString("title"))){project=item.getString("id");break;}}
    check(project!=null,"Official search did not return the requested pack");System.out.println("LIVE PASS search: Fabulously Optimized, no installed-instance context");
    JSONArray versions=mods.handle("mods.packVersions",new JSONObject().put("provider","modrinth").put("projectId",project)).getJSONArray("items");
    String versionId=null;String referenceHash=sha1(reference);
    for(int i=0;i<versions.length();i++){
      JSONObject item=versions.getJSONObject(i);if(!contains(item.optJSONArray("gameVersions"),"1.20.1")||!contains(item.optJSONArray("loaders"),"fabric")||!item.optString("name").contains(release))continue;
      String candidate=item.getString("id");check(candidate.matches("[A-Za-z0-9_-]{1,100}"),"Invalid official version ID");
      JSONObject detail=LiteNetwork.getJson("https://api.modrinth.com/v2/version/"+candidate,new String[]{"api.modrinth.com"},null);JSONArray files=detail.getJSONArray("files");
      for(int j=0;j<files.length();j++){JSONObject file=files.getJSONObject(j);if(file.optBoolean("primary")&&referenceHash.equals(file.getJSONObject("hashes").optString("sha1"))){check(file.getLong("size")<=1024*1024,"Archive exceeds smoke limit");versionId=candidate;break;}}
      if(versionId!=null)break;
    }
    check(versionId!=null,"Could not match official version metadata to the reviewed reference archive");System.out.println("LIVE PASS versions: exact official release "+release+" ("+versionId+")");
    File archive=mods.downloadPack(new JSONObject().put("provider","modrinth").put("projectId",project).put("versionId",versionId));check(referenceHash.equals(sha1(archive)),"Downloaded archive differs from the reviewed official pack");System.out.println("LIVE PASS archive: "+archive.length()+" bytes, official SHA-1 verified");
    JSONObject manifest=index(archive);JSONArray files=manifest.getJSONArray("files");long total=0;int clientFiles=0;
    for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i),env=file.optJSONObject("env");if(env!=null&&"unsupported".equals(env.optString("client")))continue;long size=file.getLong("fileSize");check(size>=0&&size<=50L*1024*1024,"Invalid client file size");total+=size;clientFiles++;}
    check(total<=50L*1024*1024,"Declared client files exceed the 50 MiB live-smoke limit");System.out.println("LIVE PLAN content: "+clientFiles+" client files, "+total+" declared bytes (limit 52428800)");
    LitePacks.Plan plan=LitePacks.inspect(archive);check("1.20.1".equals(plan.minecraft)&&"fabric".equals(plan.loader),"Unexpected live pack requirements");File output=new File(fixture,"prepared-only-not-an-instance");output.mkdirs();plan.prepare(output,mods);
    long preparedBytes,preparedFiles;try(Stream<Path> paths=Files.walk(output.toPath())){preparedFiles=paths.filter(Files::isRegularFile).count();}try(Stream<Path> paths=Files.walk(output.toPath())){preparedBytes=paths.filter(Files::isRegularFile).mapToLong(p->p.toFile().length()).sum();}
    check(preparedFiles>=clientFiles,"Prepared content file count is unexpectedly short");check(!new File(Tools.DIR_GAME_NEW).exists(),"Live smoke touched the game instance root");
    System.out.println("LIVE PASS prepare: "+preparedFiles+" files, "+preparedBytes+" bytes; "+plan.minecraft+" / "+plan.loader+" "+plan.loaderVersion);
    System.out.println("LIVE PASS cleanup scope: temporary fixtures only; no Android, no game, no real installed instance.");
  }
}`;
}

try {
  const files=[];
  for(const [relative,contents] of Object.entries(sources)) {
    const file=path.join(work,'src',relative);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,contents);files.push(file);
  }
  const productionDir=path.join(root,'app_pojavlauncher/src/main/java/com/litemc/launcher');
  const production=['LiteMods.java','LitePacks.java',...(liveSmoke?['LiteNetwork.java']:[])].filter(file=>fs.existsSync(path.join(productionDir,file))).map(file=>path.join(productionDir,file));
  const classes=path.join(work,'classes');fs.mkdirSync(classes);
  const classpath=[json,android].join(path.delimiter);
  const extension=process.platform==='win32'?'.exe':'';
  const compile=spawnSync(path.join(javaHome,'bin','javac'+extension),['-J-Duser.language=en','-J-Duser.country=US','--release','8','-encoding','UTF-8','-cp',classpath,'-d',classes,...files,...production],{stdio:'inherit',timeout:30000});
  if(compile.error)throw compile.error;if(compile.status!==0)throw new Error('Real product Java compilation failed; update only genuine dependency stubs, not test expectations.');
  const mode=liveSmoke?[path.join(root,'build/reports/mobile-packs/live-pack.mrpack')]:inspectArchive?['inspect-archive',inspectArchive]:process.argv.includes('--search-only')?['search-only']:[];
  const run=spawnSync(path.join(javaHome,'bin','java'+extension),['-cp',classes+path.delimiter+classpath,liveSmoke?'com.litemc.launcher.LivePacksSmoke':'com.litemc.launcher.MobilePacksRegression',path.join(work,'fixtures'),...mode],{stdio:'inherit',timeout:liveSmoke?300000:30000});
  if(run.error)throw run.error;process.exitCode=run.status??1;
} finally {
  const resolved=fs.realpathSync(work),parent=fs.realpathSync(os.tmpdir());
  if(path.dirname(resolved)!==parent||!path.basename(resolved).startsWith('litemc-packs-test-'))throw new Error('Refusing unexpected cleanup path');
  fs.rmSync(resolved,{recursive:true,force:true});
}
