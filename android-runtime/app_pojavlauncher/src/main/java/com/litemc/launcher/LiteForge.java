package com.litemc.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import net.kdt.pojavlaunch.Tools;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real, verified Forge installation bridge for Lite-MC. */
public final class LiteForge {
  static final String EXTRA_MC = "com.litemc.launcher.FORGE_MC";
  static final String EXTRA_LOADER = "com.litemc.launcher.FORGE_LOADER";
  static final String EXTRA_INSTALLER = "com.litemc.launcher.FORGE_INSTALLER";
  static final String EXTRA_RESULT = "com.litemc.launcher.FORGE_RESULT";
  static final String EXTRA_NONCE = "com.litemc.launcher.FORGE_NONCE";
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final String MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
  private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");

  private LiteForge() {}

  public static void requireSupportedVersion(String minecraft) {
    if (minecraft == null || !minecraft.matches("[0-9]+(?:\\.[0-9]+){1,3}"))
      throw new IllegalArgumentException("Minecraft 版本无效。");
    String[] pieces = minecraft.split("\\.");
    if (Integer.parseInt(pieces[0]) != 1 || Integer.parseInt(pieces[1]) < 13)
      throw new IllegalArgumentException("Forge 自动安装仅支持 Minecraft 1.13 及更高版本。");
  }

  /** Lists official exact versions and prefers Forge's official promotion. */
  public static JSONArray versions(String minecraft) throws Exception {
    requireSupportedVersion(minecraft);
    Matcher matcher = VERSION.matcher(new String(get(MAVEN + "maven-metadata.xml", 1024 * 1024), UTF8));
    JSONArray choices = new JSONArray();
    String prefix = minecraft + "-";
    while (matcher.find()) {
      String full = matcher.group(1);
      if (!full.startsWith(prefix)) continue;
      String loader = full.substring(prefix.length());
      if (loader.matches("[0-9][A-Za-z0-9._-]{0,60}"))
        choices.put(new JSONObject().put("id", loader).put("name", full));
    }
    if (choices.length() == 0) throw new IllegalStateException("官方 Forge 源没有此 Minecraft 版本。");
    String promoted = "";
    try {
      JSONObject promotions = new JSONObject(new String(get("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json", 256 * 1024), UTF8)).optJSONObject("promos");
      if (promotions != null) promoted = promotions.optString(minecraft + "-recommended", promotions.optString(minecraft + "-latest", ""));
    } catch (Exception ignored) { }
    int selected = choices.length() - 1;
    for (int i = 0; i < choices.length(); i++) if (promoted.equals(choices.getJSONObject(i).optString("id"))) selected = i;
    choices.getJSONObject(selected).put("recommended", true);
    return choices;
  }

  /** Runs only on the caller's install worker, waits for an isolated JVM nonce result. */
  public static JSONObject install(Activity activity, String minecraft, String pinnedLoader) throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Forge 安装必须在后台执行。");
    requireSupportedVersion(minecraft);
    Spec spec = resolve(minecraft, pinnedLoader);
    ensureBaseGame();
    File installer = downloadOfficialInstaller(activity, spec);
    File results = new File(activity.getFilesDir(), "lite-forge-results");
    if (!results.isDirectory() && !results.mkdirs()) throw new java.io.IOException("无法创建 Forge 安装状态目录。");
    String nonce = UUID.randomUUID().toString().replace("-", "");
    File result = new File(results, "forge-" + nonce + ".json");
    if (result.exists()) throw new java.io.IOException("Forge 安装状态文件冲突，请重试。");
    Intent intent = new Intent(activity, LiteForgeInstallerActivity.class)
        .putExtra(EXTRA_MC, spec.minecraft).putExtra(EXTRA_LOADER, spec.loader)
        .putExtra(EXTRA_INSTALLER, installer.getAbsolutePath()).putExtra(EXTRA_RESULT, result.getAbsolutePath())
        .putExtra(EXTRA_NONCE, nonce);
    activity.runOnUiThread(() -> {
      try { activity.startActivity(intent); }
      catch (Throwable error) { publishFailure(result, nonce, spec.minecraft, "无法启动 Forge 安装进程。"); }
    });
    JSONObject status = awaitResult(result, nonce, spec, 45 * 60 * 1000L);
    if (!status.optBoolean("success") || !spec.launchVersion.equals(status.optString("versionId")))
      throw new java.io.IOException("Forge 安装失败：" + safeError(status.optString("error")));
    verifyInstalled(spec);
    return new JSONObject().put("launchVersion", spec.launchVersion).put("loaderVersion", spec.loader);
  }

  static Spec resolve(String minecraft, String pin) throws Exception {
    JSONArray choices = versions(minecraft);
    String loader = pin == null ? "" : pin.trim();
    String prefix = minecraft + "-";
    if (loader.startsWith(prefix)) loader = loader.substring(prefix.length());
    if (!loader.isEmpty() && !loader.matches("[0-9][A-Za-z0-9._-]{0,60}")) throw new IllegalArgumentException("Forge 加载器版本无效。");
    if (loader.isEmpty()) {
      for (int i = 0; i < choices.length(); i++)
        if (choices.getJSONObject(i).optBoolean("recommended")) { loader = choices.getJSONObject(i).getString("id"); break; }
      if (loader.isEmpty()) loader = choices.getJSONObject(choices.length() - 1).getString("id");
    }
    for (int i = 0; i < choices.length(); i++) if (loader.equals(choices.getJSONObject(i).getString("id"))) return new Spec(minecraft, loader);
    throw new IllegalArgumentException("官方 Forge 源没有指定的精确加载器版本：" + loader);
  }

  static File downloadOfficialInstaller(Context context, Spec spec) throws Exception {
    String base = MAVEN + spec.mavenVersion + "/forge-" + spec.mavenVersion + "-installer.jar";
    String expected = new String(get(base + ".sha1", 1024), UTF8).trim().split("\\s+")[0];
    if (!expected.matches("(?i)[0-9a-f]{40}")) throw new java.io.IOException("Forge 官方 SHA-1 元数据无效。");
    File cache = new File(context.getCacheDir(), "lite-forge");
    if (!cache.isDirectory() && !cache.mkdirs()) throw new java.io.IOException("无法创建 Forge 缓存目录。");
    File output = new File(cache, "forge-" + spec.mavenVersion + "-installer.jar");
    if (matches(output, expected)) return output;
    File part = new File(cache, output.getName() + ".part-" + UUID.randomUUID());
    HttpsURLConnection connection = (HttpsURLConnection) new URL(base).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
    try {
      if (connection.getResponseCode() != 200) throw new java.io.IOException("Forge 官方源 HTTP " + connection.getResponseCode());
      try (InputStream input = connection.getInputStream(); FileOutputStream stream = new FileOutputStream(part)) {
        byte[] buffer = new byte[32768]; long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
          total += count;
          if (total > 128L * 1024 * 1024) throw new java.io.IOException("Forge 安装器超过大小限制。");
          stream.write(buffer, 0, count);
        }
      }
      if (!matches(part, expected)) throw new java.io.IOException("Forge 安装器校验失败。");
      // Replace only this cache entry, never a player's game directory.
      if (output.exists() && !output.delete()) throw new java.io.IOException("无法更新 Forge 安装器缓存。");
      if (!part.renameTo(output)) throw new java.io.IOException("无法保存 Forge 安装器。");
      return output;
    } finally { connection.disconnect(); if (part.exists()) part.delete(); }
  }

  static void verifyInstalled(Spec spec) throws Exception {
    File profile = new File(Tools.DIR_HOME_VERSION, spec.launchVersion + "/" + spec.launchVersion + ".json");
    if (!profile.isFile() || profile.length() < 64) throw new java.io.IOException("Forge 未生成版本配置。");
    JSONObject json = new JSONObject(new String(read(profile, 4 * 1024 * 1024), UTF8));
    if (!spec.launchVersion.equals(json.optString("id")) || !spec.minecraft.equals(json.optString("inheritsFrom")) || !json.optString("mainClass").matches("[A-Za-z_$][A-Za-z0-9_.$]+"))
      throw new java.io.IOException("Forge 版本配置不匹配。");
    JSONArray libraries = json.optJSONArray("libraries"); boolean forge = false;
    if (libraries != null) for (int i = 0; i < libraries.length(); i++) {
      JSONObject library = libraries.optJSONObject(i);
      if (library != null && library.optString("name").startsWith("net.minecraftforge:forge:")) { forge = true; break; }
    }
    if (!forge) throw new java.io.IOException("Forge 未生成必需的库配置。");
  }

  private static void ensureBaseGame() throws Exception {
    File root = new File(Tools.DIR_GAME_NEW);
    if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Minecraft 根目录不可用。");
    File profiles = new File(root, "launcher_profiles.json");
    if (!profiles.isFile()) try (FileOutputStream stream = new FileOutputStream(profiles)) { stream.write("{\"profiles\":{}}".getBytes(UTF8)); }
  }

  private static JSONObject awaitResult(File result, String nonce, Spec spec, long timeout) throws Exception {
    long end = System.currentTimeMillis() + timeout;
    while (System.currentTimeMillis() < end) {
      if (result.isFile() && result.length() > 1) try {
        JSONObject json = new JSONObject(new String(read(result, 64 * 1024), UTF8));
        if (!nonce.equals(json.optString("nonce")) || !spec.minecraft.equals(json.optString("minecraft"))) throw new SecurityException("Forge 安装结果校验失败。");
        return json;
      } catch (org.json.JSONException ignored) { }
      Thread.sleep(250);
    }
    throw new java.io.IOException("Forge 安装超时，请查看安装日志后重试。");
  }

  static void publishFailure(File result, String nonce, String minecraft, String error) {
    try {
      if (result.exists()) return;
      JSONObject json = new JSONObject().put("nonce", nonce).put("success", false)
          .put("minecraft", minecraft).put("error", error);
      File temporary = new File(result.getParentFile(), result.getName() + ".part");
      try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(json.toString().getBytes(UTF8)); }
      if (!temporary.renameTo(result)) temporary.delete();
    } catch (Exception ignored) { }
  }

  private static byte[] get(String address, int limit) throws Exception {
    URL url = new URL(address);
    if (!"https".equals(url.getProtocol()) || url.getPort() != -1 || url.getUserInfo() != null || !("maven.minecraftforge.net".equals(url.getHost()) || "files.minecraftforge.net".equals(url.getHost()))) throw new java.io.IOException("非官方 Forge 下载源。");
    HttpsURLConnection c = (HttpsURLConnection) url.openConnection(); c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(60000);
    try {
      if (c.getResponseCode() != 200) throw new java.io.IOException("Forge 官方源 HTTP " + c.getResponseCode());
      try (InputStream input = c.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[16384];
        for (int count; (count = input.read(buffer)) != -1;) { if (output.size() + count > limit) throw new java.io.IOException("Forge 下载文件过大。"); output.write(buffer, 0, count); }
        return output.toByteArray();
      }
    } finally { c.disconnect(); }
  }

  static byte[] read(File file, int limit) throws Exception {
    try (InputStream input = new FileInputStream(file)) { return readStream(input, limit); }
  }
  static byte[] readStream(InputStream input, int limit) throws Exception {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      for (int count; (count = input.read(buffer)) != -1;) { if (output.size() + count > limit) throw new java.io.IOException("Forge 文件过大。"); output.write(buffer, 0, count); }
      return output.toByteArray();
    }
  }
  static String sha1(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[32768]; long total = 0;
      for (int count; (count = input.read(buffer)) != -1;) {
        total += count;
        if (total > 128L * 1024 * 1024) throw new java.io.IOException("Forge 安装器超过大小限制。");
        digest.update(buffer, 0, count);
      }
    }
    StringBuilder value = new StringBuilder();
    for (byte b : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }
  private static boolean matches(File file, String expected) throws Exception { return file.isFile() && file.length() > 0 && sha1(file).equalsIgnoreCase(expected); }
  private static String sha1(byte[] data) throws Exception { StringBuilder value = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-1").digest(data)) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return value.toString(); }
  private static String safeError(String error) { return error == null || error.length() == 0 ? "请查看安装日志后重试。" : error; }

  static final class Spec { final String minecraft, loader, launchVersion, mavenVersion; Spec(String mc, String loader) { minecraft = mc; this.loader = loader; launchVersion = mc + "-forge-" + loader; mavenVersion = mc + "-" + loader; } }
}
