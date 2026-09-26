package com.litemc.launcher;

import java.io.*;
import java.util.Locale;
import java.util.zip.*;
import org.json.*;

/** Local documents are copied into one instance; never execute or extract imported JARs. */
public final class LiteLocalFiles {
  private LiteLocalFiles() {}

  public static File importFile(File instance, InputStream input, String name, String kind) throws Exception {
    boolean mod = "mod".equals(kind);
    if (!mod && !"schematic".equals(kind)) throw new IOException("未知文件类型。");
    if (name == null || name.length() > 180 || name.startsWith(".")
        || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || name.endsWith(" "))
      throw new IOException("文件名无效，请先重命名文件。");
    String lower = name.toLowerCase(Locale.ROOT);
    if (mod ? !lower.endsWith(".jar") : !(lower.endsWith(".litematic") || lower.endsWith(".schem") || lower.endsWith(".schematic")))
      throw new IOException(mod ? "请选择 .jar 格式的 Mod。" : "请选择 .litematic、.schem 或 .schematic 蓝图。");
    File directory = new File(instance, mod ? "mods" : "schematics").getCanonicalFile();
    if (!directory.getPath().startsWith(instance.getCanonicalPath() + File.separator)) throw new IOException("目录无效。");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建文件夹。");
    File target = new File(directory, name).getCanonicalFile();
    if (!directory.equals(target.getParentFile())) throw new IOException("文件名无效。");
    if (target.exists()) throw new IOException("同名文件已存在，未覆盖：" + name);
    File temporary = File.createTempFile("lite-import-", ".part", directory);
    try {
      try (OutputStream out = new FileOutputStream(temporary)) {
        byte[] buffer = new byte[32768]; long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
          total += count;
          if (total > 256L * 1024 * 1024) throw new IOException("单个文件不能超过 256 MB。");
          out.write(buffer, 0, count);
        }
        if (total == 0) throw new IOException("文件为空。");
      }
      if (mod) {
        try (ZipFile zip = new ZipFile(temporary)) {
          if (zip.getEntry("fabric.mod.json") == null && zip.getEntry("quilt.mod.json") == null
              && zip.getEntry("META-INF/mods.toml") == null && zip.getEntry("META-INF/neoforge.mods.toml") == null
              && zip.getEntry("mcmod.info") == null && zip.getEntry("META-INF/MANIFEST.MF") == null)
            throw new IOException("该 JAR 没有有效的 Mod / Java 元数据。");
        }
      } else {
        try (DataInputStream nbt = new DataInputStream(new GZIPInputStream(new FileInputStream(temporary)))) {
          if (nbt.readUnsignedByte() != 10) throw new IOException("蓝图不是有效的 NBT 文件。");
          nbt.readUTF();
        }
      }
      if (target.exists() || !temporary.renameTo(target)) throw new IOException("无法保存文件；原文件未被覆盖。");
      return target;
    } finally { if (temporary.exists()) temporary.delete(); }
  }

  public static JSONObject projection(File instance) throws Exception {
    boolean found = false, library = false;
    File[] files = new File(instance, "mods").listFiles();
    if (files != null) for (File file : files) {
      if (!file.getName().endsWith(".jar")) continue;
      try (ZipFile zip = new ZipFile(file)) {
        ZipEntry entry = zip.getEntry("fabric.mod.json");
        if (entry != null) {
          try (InputStream in = zip.getInputStream(entry)) {
            String id = new JSONObject(read(in, 1024 * 1024)).optString("id");
            found |= "litematica".equals(id);
            library |= "malilib".equals(id);
          }
        }
        // Class markers also recognize Forge ports without relying on a filename.
        found |= zip.getEntry("fi/dy/masa/litematica/Litematica.class") != null;
        library |= zip.getEntry("fi/dy/masa/malilib/MaLiLib.class") != null;
      } catch (IOException | JSONException ignored) { }
    }
    JSONArray blueprints = new JSONArray();
    File[] schematics = new File(instance, "schematics").listFiles();
    if (schematics != null) for (File file : schematics)
      if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).matches(".*\\.(litematic|schem|schematic)"))
        blueprints.put(new JSONObject().put("name", file.getName()).put("size", file.length()));
    return new JSONObject().put("installed", found).put("malilib", library).put("items", blueprints);
  }

  public static String read(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
    for (int n; (n = in.read(buffer)) != -1;) {
      if (out.size() + n > limit) throw new IOException("文件内容过大。");
      out.write(buffer, 0, n);
    }
    return out.toString("UTF-8");
  }
}
