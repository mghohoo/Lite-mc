package com.litemc.launcher;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.json.*;

public class LocalToolsRegression {
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  interface Task { void run() throws Exception; }
  static void rejects(Task action) throws Exception {
    try { action.run(); } catch (IOException e) { return; }
    throw new AssertionError("invalid import accepted");
  }
  static byte[] jar(String id) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("fabric.mod.json")); zip.write(("{\"id\":\""+id+"\"}").getBytes("UTF-8")); zip.closeEntry();
    }
    return out.toByteArray();
  }
  static File put(File instance, byte[] bytes, String name, String kind) throws Exception {
    return LiteLocalFiles.importFile(instance, new ByteArrayInputStream(bytes), name, kind);
  }
  public static void main(String[] args) throws Exception {
    File base = new File(args[0]), first = new File(base,"first"), second = new File(base,"second");
    first.mkdirs(); second.mkdirs(); byte[] mod=jar("example");
    File installed=put(first,mod,"中文模组.jar","mod");
    check(installed.getParentFile().equals(new File(first,"mods")),"incorrect folder");
    check(!new File(second,"mods/中文模组.jar").exists(),"instance contamination");
    rejects(()->put(first,new byte[]{1},"中文模组.jar","mod"));
    check(java.util.Arrays.equals(Files.readAllBytes(installed.toPath()),mod),"existing file overwritten");
    rejects(()->put(first,mod,"../escape.jar","mod"));
    rejects(()->put(first,mod,"bad\\escape.jar","mod"));
    rejects(()->put(first,mod,"script.exe","mod"));
    rejects(()->put(first,new byte[]{1,2,3},"bad.jar","mod"));
    check(!new File(first,"mods/bad.jar").exists(),"invalid JAR committed");
    check(new File(first,"mods").listFiles().length==1,"temporary files leaked");
    System.out.println("PASS isolated import, duplicate preservation, path and JAR validation, cleanup");
    put(first,jar("litematica"),"renamed.jar","mod"); put(first,jar("malilib"),"dependency.jar","mod");
    JSONObject projection=LiteLocalFiles.projection(first);
    check(projection.getBoolean("installed")&&projection.getBoolean("malilib"),"metadata detection failed");
    check(!LiteLocalFiles.projection(second).getBoolean("installed"),"projection leaked to other instance");
    ByteArrayOutputStream raw=new ByteArrayOutputStream();
    try (DataOutputStream out=new DataOutputStream(new GZIPOutputStream(raw))) {out.writeByte(10);out.writeUTF("");out.writeByte(0);}
    put(first,raw.toByteArray(),"house.litematic","schematic");
    rejects(()->put(first,new byte[]{1},"broken.litematic","schematic"));
    check(LiteLocalFiles.projection(first).getJSONArray("items").length()==1,"blueprint listing failed");
    System.out.println("PASS renamed Litematica and MaLiLib detection, blueprint import and listing");
    JSONObject settings=LiteControls.validate(new JSONObject().put("bindings",new JSONObject().put("跳跃",74)).put("opacity",65));
    JSONObject template=new JSONObject(Files.readString(Path.of(args[1])));
    JSONObject layout=LiteControls.layout(template,settings,first);
    boolean jump=false,projectionButton=false,menu=false;
    for(Object value:layout.getJSONArray("mControlDataList")) {
      JSONObject b=(JSONObject)value;String name=b.getString("name");
      if(name.equals("跳跃"))jump=b.getJSONArray("keycodes").getInt(0)==74;
      if(name.equals("投影菜单"))projectionButton=true;
      if(name.equals("菜单"))menu=b.getJSONArray("keycodes").getInt(0)==-9;
      check(!name.equals("文字输入"),"cluttered extra chat should default hidden");
    }
    check(jump&&projectionButton&&menu,"profile generation failed");
    check(!LiteControls.layout(template,settings,second).toString().contains("投影菜单"),"projection shown without mod");
    check(!LiteControls.layout(template,new JSONObject().put("projection",false),first).toString().contains("投影菜单"),"projection toggle ignored");
    check(template.toString().contains("文字输入"),"template mutated");
    rejects(()->LiteControls.validate(new JSONObject().put("scale",500)));
    rejects(()->LiteControls.validate(new JSONObject().put("bindings",new JSONObject().put("跳跃",-999))));
    System.out.println("PASS global bindings, fixed menu, per-instance projection visibility, input validation");
    File config=new File(first,"config/litematica.json");config.getParentFile().mkdirs();
    Files.writeString(config.toPath(),"{\"Hotkeys\":{\"openGuiMainMenu\":{\"keys\":\"L_CTRL,K\"}}}");
    JSONArray keys=LiteControls.projectionHotkey(first).getJSONArray("keys");
    check(keys.getInt(0)==341&&keys.getInt(1)==75,"custom projection shortcut ignored");
    Files.writeString(config.toPath(),"{\"Hotkeys\":{\"openLoadSchematicsScreen\":{\"keys\":\"F8\"}}}");
    check(LiteControls.projectionHotkey(first).getString("label").equals("加载投影"),"direct loader not selected");
    check(LiteControls.projectionHotkey(first).getJSONArray("keys").getInt(0)==297,"wrong F8 code");
    Files.writeString(config.toPath(),"invalid json");
    check(LiteControls.projectionHotkey(first).getJSONArray("keys").getInt(0)==77,"broken optional config blocked launch");
    System.out.println("PASS existing projection chord and direct load hotkey respected");
  }
}
