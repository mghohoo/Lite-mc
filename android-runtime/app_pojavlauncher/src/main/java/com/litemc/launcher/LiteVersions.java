package com.litemc.launcher;

import android.app.Activity;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Lite-MC's install/instance model. The runtime prepares libraries, assets and JRE. */
public final class LiteVersions {
  private final Context context;
  private final File records;
  private JSONObject manifest;

  public LiteVersions(Context context) {
    this.context = context.getApplicationContext();
    records = new File(context.getFilesDir(), "lite-instances.json");
  }

  static String id(String value) {
    if (!value.matches("[A-Za-z0-9._-]{1,100}") || value.contains(".."))
      throw new IllegalArgumentException("Invalid version or instance ID");
    return value;
  }

  /** Product capabilities, not a claim that merely downloading a loader JAR installs it. */
  public static JSONArray loaders() throws Exception {
    JSONArray result = new JSONArray();
    for (String loader : new String[] {"vanilla", "fabric", "forge", "liteloader", "optifine"}) {
      String reason = unsupportedLoaderReason(loader);
      result.put(new JSONObject()
          .put("id", loader)
          .put("name", loaderName(loader))
          .put("automaticInstall", reason.isEmpty())
          .put("supported", reason.isEmpty())
          .put("status", reason.isEmpty() ? "supported" : "unavailable")
          .put("reason", reason)
          .put("includesFabricApi", "fabric".equals(loader)));
    }
    return result;
  }

  static String loaderName(String loader) {
    switch (loader) {
      case "vanilla": return "原版";
      case "fabric": return "Fabric";
      case "forge": return "Forge";
      case "liteloader": return "LiteLoader";
      case "optifine": return "OptiFine";
      default: throw new IllegalArgumentException("未知加载器，请重新选择。");
    }
  }

  private static String unsupportedLoaderReason(String loader) {
    loaderName(loader);
    if ("forge".equals(loader))
      return "Android 版 Forge 自动安装暂未适配：还需要独立 JVM 执行官方安装器的补丁处理。仅下载 JAR 不代表安装完成，请先使用 Fabric 或原版。";
    if ("liteloader".equals(loader))
      return "LiteLoader 自动安装暂不可用：尚未取得可验证的官方版本元数据，不会改装成原版。";
    if ("optifine".equals(loader))
      return "OptiFine 自动安装暂不可用：尚未接入可验证的官方安装流程；请参考 optifine.net/downloads，不会标记为已安装。";
    return "";
  }

  public static void requireAutomaticLoader(String loader) {
    String reason = unsupportedLoaderReason(loader);
    if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
  }

  /** Conservative runtime guard until the new renderer is validated on Android. */
  public static String compatibilityReason(String version) {
    id(version);
    if (version.equals("26") || version.startsWith("26.") || version.startsWith("26-"))
      return "当前 Android 图形运行层暂不支持 Minecraft 26.x。已确认 Mali-G615 上 RenderPearl 的 OpenGL 初始化失败，Vulkan 又缺少必要扩展；为避免启动崩溃，已禁止安装和启动。请选择 Minecraft 1.21.x。";
    return "";
  }

  public static void requireCompatibleVersion(String version) {
    String reason = compatibilityReason(version);
    if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
  }

  private JSONObject withCompatibility(JSONObject item, String version) throws Exception {
    String reason = compatibilityReasonForDevice(version);
    return item.put("supported", reason.isEmpty()).put("reason", reason)
        .put("recommendedVersion", reason.isEmpty() ? "" : "1.21.x");
  }

  private String compatibilityReasonForDevice(String version) {
    try {
      Object resources = context.getClass().getMethod("getResources").invoke(context);
      Object configuration = resources.getClass().getMethod("getConfiguration").invoke(resources);
      int smallest = configuration.getClass().getField("smallestScreenWidthDp").getInt(configuration);
      if (smallest >= 600) return "";
    } catch (Exception ignored) {
      // Test/runtime shims without Android Resources use the conservative guard.
    }
    return compatibilityReason(version);
  }

  public static File instance(String instanceId) throws IOException {
    File root = new File(Tools.DIR_GAME_NEW, "lite-instances").getCanonicalFile();
    File result = new File(root, id(instanceId)).getCanonicalFile();
    if (!result.getParentFile().equals(root)) throw new IOException("Invalid instance directory");
    if (!result.isDirectory() && !result.mkdirs())
      throw new IOException("Cannot create instance directory");
    File mods = new File(result, "mods");
    if (!mods.isDirectory() && !mods.mkdirs())
      throw new IOException("Cannot create mods directory");
    return result;
  }

  static void write(File file, byte[] bytes) throws IOException {
    if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
      throw new IOException("Cannot create directory");
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream stream = null;
    try {
      stream = atomic.startWrite();
      stream.write(bytes);
      atomic.finishWrite(stream);
    } catch (IOException ex) {
      if (stream != null) atomic.failWrite(stream);
      throw ex;
    }
  }

  static byte[] fetch(String address) throws Exception {
    URL url = new URL(address);
    if (!"https".equals(url.getProtocol())
        || url.getPort() != -1
        || url.getUserInfo() != null
        || !(url.getHost().equals("piston-meta.mojang.com")
            || url.getHost().equals("piston-data.mojang.com")
            || url.getHost().equals("launchermeta.mojang.com")
            || url.getHost().equals("meta.fabricmc.net")))
      throw new IOException("Unsupported metadata source");
    HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(30000);
    connection.setRequestProperty("User-Agent", "Lite-MC/1.1 Android");
    try {
      if (connection.getResponseCode() != 200)
        throw new IOException("Metadata server HTTP " + connection.getResponseCode());
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (output.size() + count > 16 * 1024 * 1024)
            throw new IOException("Metadata is too large");
          output.write(buffer, 0, count);
        }
        return output.toByteArray();
      }
    } finally {
      connection.disconnect();
    }
  }

  public synchronized JSONObject catalog(boolean refresh) throws Exception {
    File cache = new File(context.getFilesDir(), "lite-manifest.json");
    boolean stale = false;
    if (manifest == null || refresh) {
      try {
        if (refresh
            || !cache.isFile()
            || System.currentTimeMillis() - cache.lastModified() > 86400000L) {
          byte[] bytes = fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
          JSONObject parsed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
          parsed.getJSONArray("versions");
          write(cache, bytes);
          manifest = parsed;
        } else
          manifest =
              new JSONObject(new String(new AtomicFile(cache).readFully(), StandardCharsets.UTF_8));
      } catch (Exception ex) {
        if (!cache.isFile()) throw ex;
        manifest =
            new JSONObject(new String(new AtomicFile(cache).readFully(), StandardCharsets.UTF_8));
        stale = true;
      }
    }
    JSONArray list = new JSONArray();
    JSONArray source = manifest.getJSONArray("versions");
    for (int i = 0; i < source.length(); i++) {
      JSONObject item = source.getJSONObject(i);
      if ("release".equals(item.optString("type")))
        list.put(
            withCompatibility(new JSONObject()
                .put("id", item.getString("id"))
                .put("date", item.optString("releaseTime").split("T")[0]), item.getString("id")));
    }
    return new JSONObject().put("items", list).put("cached", stale).put("loaders", loaders());
  }

  public synchronized JSONArray installed() throws Exception {
    if (!records.isFile()) return new JSONArray();
    JSONArray all = new JSONArray(new String(new AtomicFile(records).readFully(), StandardCharsets.UTF_8));
    for (int i = 0; i < all.length(); i++) {
      JSONObject item = all.getJSONObject(i);
      withCompatibility(item, item.getString("version"));
    }
    return all;
  }

  public JSONObject find(String instanceId) throws Exception {
    JSONArray all = installed();
    for (int i = 0; i < all.length(); i++)
      if (all.getJSONObject(i).getString("id").equals(id(instanceId))) return all.getJSONObject(i);
    throw new IOException("Install this version first");
  }

  public void select(JSONObject instance) throws Exception {
    String reason = compatibilityReasonForDevice(instance.getString("version"));
    if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
    requireAutomaticLoader(instance.getString("loader"));
    String instanceId = id(instance.getString("id"));
    String profileId =
        UUID.nameUUIDFromBytes(("lite:" + instanceId).getBytes(StandardCharsets.UTF_8)).toString();
    LauncherProfiles.load();
    MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(profileId);
    if (profile == null) profile = new MinecraftProfile();
    profile.name = instance.optString("name", "Lite-MC · " + instance.getString("version"));
    profile.lastVersionId = id(instance.getString("launchVersion"));
    profile.gameDir = instance(instanceId).getAbsolutePath();
    // MainActivity resolves this value below Tools.CTRLMAP_PATH; do not store an
    // absolute path or it becomes a malformed controlmap/controlmap/... path.
    File controls = new File(Tools.CTRLMAP_PATH, "lite-mc-mobile.json");
    if (controls.isFile()) profile.controlFile = "lite-mc-mobile.json";
    LauncherProfiles.mainProfileJson.profiles.put(profileId, profile);
    LauncherProfiles.write();
    if (!LauncherPreferences.DEFAULT_PREF
        .edit()
        .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileId)
        .commit()) throw new IOException("Cannot save selected instance");
    context.getSharedPreferences("lite", 0).edit().putString("selected", instanceId).apply();
  }

  public JSONObject install(Activity activity, String version, String loader, String displayName) throws Exception {
    id(version);
    String reason = compatibilityReasonForDevice(version);
    if (!reason.isEmpty()) throw new IllegalArgumentException(reason);
    requireAutomaticLoader(loader);
    catalog(false);
    JSONObject metadata = null;
    JSONArray all = manifest.getJSONArray("versions");
    for (int i = 0; i < all.length(); i++)
      if (all.getJSONObject(i).getString("id").equals(version)) metadata = all.getJSONObject(i);
    if (metadata == null) throw new IOException("Version is not in the official manifest");
    byte[] bytes = fetch(metadata.getString("url"));
    StringBuilder hash = new StringBuilder();
    for (byte part : MessageDigest.getInstance("SHA-1").digest(bytes))
      hash.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
    if (!hash.toString().equalsIgnoreCase(metadata.getString("sha1")))
      throw new IOException("Version metadata checksum mismatch");
    write(new File(Tools.DIR_HOME_VERSION, version + "/" + version + ".json"), bytes);
    String launch = version;
    String loaderVersion = "";
    if (loader.equals("fabric")) {
      JSONArray loaders =
          new JSONArray(
              new String(
                  fetch("https://meta.fabricmc.net/v2/versions/loader/" + version),
                  StandardCharsets.UTF_8));
      for (int i = 0; i < loaders.length(); i++) {
        JSONObject entry = loaders.getJSONObject(i).getJSONObject("loader");
        if (entry.optBoolean("stable")) {
          loaderVersion = id(entry.getString("version"));
          break;
        }
      }
      if (loaderVersion.isEmpty())
        throw new IOException("No stable Fabric loader for this version");
      byte[] fabric =
          fetch(
              "https://meta.fabricmc.net/v2/versions/loader/"
                  + version
                  + "/"
                  + loaderVersion
                  + "/profile/json");
      JSONObject profile = new JSONObject(new String(fabric, StandardCharsets.UTF_8));
      if (!version.equals(profile.getString("inheritsFrom")))
        throw new IOException("Fabric base-version mismatch");
      if (!profile.optString("mainClass").matches("[A-Za-z_$][A-Za-z0-9_.$]+")
          || profile.optJSONArray("libraries") == null)
        throw new IOException("Fabric 官方版本配置不完整");
      launch = id(profile.getString("id"));
      write(new File(Tools.DIR_HOME_VERSION, launch + "/" + launch + ".json"), fabric);
    }
    String safeName = displayName == null ? "" : displayName.trim();
    if (safeName.isEmpty()) safeName = version + " " + loaderName(loader);
    if (safeName.length() > 32 || safeName.matches(".*[\\\\/:*?\"<>|].*"))
      throw new IllegalArgumentException("版本名称需要 1–32 个字符，不能包含文件路径符号");
    String instanceId = loader + "-" + version + "-" + Long.toString(System.currentTimeMillis(), 36);
    JSONObject instance =
        new JSONObject()
            .put("id", id(instanceId))
            .put("name", safeName)
            .put("version", version)
            .put("loader", loader)
            .put("loaderVersion", loaderVersion)
            .put("launchVersion", launch)
            .put("status", "installing");
    select(instance);
    CountDownLatch complete = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    JMinecraftVersionList.Version listed =
        loader.equals("vanilla")
            ? Tools.GLOBAL_GSON.fromJson(metadata.toString(), JMinecraftVersionList.Version.class)
            : null;
    new MinecraftDownloader()
        .start(
            activity,
            listed,
            launch,
            new AsyncMinecraftDownloader.DoneListener() {
              public void onDownloadDone() {
                complete.countDown();
              }

              public void onDownloadFailed(Throwable error) {
                failure.set(error);
                complete.countDown();
              }
            });
    if (!complete.await(45, TimeUnit.MINUTES))
      throw new IOException("Installation timed out; retry to reuse downloaded files");
    if (failure.get() != null)
      throw new IOException("Game preparation failed: " + failure.get().getClass().getSimpleName());
    if (!new File(Tools.DIR_HOME_VERSION, launch + "/" + launch + ".jar").isFile())
      throw new IOException("Client JAR is missing");
    // Fabric API is a mod, not a Maven loader library. Keep it in this instance's
    // mods directory; any failure must leave the instance uncommitted/unlaunchable.
    if ("fabric".equals(loader)) {
      JSONObject api = new LiteMods(context).installFabricApi(instanceId, version);
      instance.put("fabricApi", api);
    }
    synchronized (this) {
      JSONArray existing = installed();
      JSONArray updated = new JSONArray();
      for (int i = 0; i < existing.length(); i++)
        if (!existing.getJSONObject(i).getString("id").equals(instance.getString("id")))
          updated.put(existing.getJSONObject(i));
      updated.put(instance.put("installedAt", System.currentTimeMillis()).put("status", "installed"));
      write(records, updated.toString().getBytes(StandardCharsets.UTF_8));
    }
    return instance;
  }
}
