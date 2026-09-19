package com.litemc.launcher;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.crypto.Cipher;
import net.kdt.pojavlaunch.Tools;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent Modrinth/CurseForge mod service with explicit loader compatibility. */
public final class LiteMods {
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final String[] MODRINTH = {"api.modrinth.com", "cdn.modrinth.com"};
  private static final String[] CURSE = {"api.curseforge.com", "curseforge.com", "forgecdn.net"};
  private final Context context;

  public LiteMods(Context context) {
    this.context = context.getApplicationContext();
  }

  /** Called by version installation before committing the new Fabric instance. */
  public synchronized JSONObject installFabricApi(String instanceId, String version) throws Exception {
    JSONObject request = new JSONObject()
        .put("instanceId", instanceId)
        .put("version", version)
        .put("loader", "fabric")
        .put("provider", "modrinth")
        .put("projectId", "fabric-api");
    return install(request).put("source", "modrinth").put("project", "fabric-api");
  }

  public synchronized JSONObject handle(String action, JSONObject args) throws Exception {
    if ("mods.config".equals(action)) {
      String key = args.optString("curseforgeKey", "").trim();
      if (!key.matches("[^\\s]{16,190}"))
        throw new IllegalArgumentException("CurseForge API Key 格式无效。");
      saveKey(key);
      return new JSONObject().put("configured", true);
    }
    if ("mods.search".equals(action)) return search(args);
    if ("mods.install".equals(action)) return install(args);
    if ("mods.list".equals(action)) return list(args);
    throw new IllegalArgumentException("不支持的 Mod 操作。");
  }

  private JSONObject search(JSONObject a) throws Exception {
    String provider = provider(a), version = version(a), loader = a.optString("loader", "");
    requireModLoader(loader);
    String query = a.optString("query", "").trim();
    if (query.length() == 0 || query.length() > 80)
      throw new IllegalArgumentException("请输入 1–80 个字符的 Mod 名称。");
    JSONArray items = new JSONArray();
    if ("modrinth".equals(provider)) {
      String facets =
          java.net.URLEncoder.encode(
              "[[\"project_type:mod\"],[\"versions:" + version + "\"],[\"categories:" + loader + "\"]]",
              "UTF-8");
      JSONObject response =
          LiteNetwork.getJson(
              "https://api.modrinth.com/v2/search?query="
                  + URLEncoder.encode(query, "UTF-8")
                  + "&limit=20&index=downloads&facets="
                  + facets,
              MODRINTH,
              null);
      JSONArray hits = response.optJSONArray("hits");
      if (hits != null)
        for (int i = 0; i < hits.length(); i++) {
          JSONObject h = hits.getJSONObject(i);
          items.put(
              item(
                  h.optString("project_id"),
                  h.optString("title"),
                  h.optString("description"),
                  h.optLong("downloads")));
        }
    } else {
      String key = key();
      Map<String, String> headers = new HashMap<String, String>();
      headers.put("x-api-key", key);
      String u =
          "https://api.curseforge.com/v1/mods/search?gameId=432&classId=6&gameVersion="
              + URLEncoder.encode(version, "UTF-8")
              + "&modLoaderType=" + ("forge".equals(loader) ? "1" : "4") + "&pageSize=20&searchFilter="
              + URLEncoder.encode(query, "UTF-8");
      JSONArray data = LiteNetwork.getJson(u, CURSE, headers).optJSONArray("data");
      if (data != null)
        for (int i = 0; i < data.length(); i++) {
          JSONObject h = data.getJSONObject(i);
          items.put(
              item(
                  String.valueOf(h.optLong("id")),
                  h.optString("name"),
                  h.optString("summary"),
                  h.optLong("downloadCount")));
        }
    }
    return new JSONObject().put("items", items);
  }

  private JSONObject install(JSONObject a) throws Exception {
    String provider = provider(a), version = version(a), loader = a.optString("loader", "");
    requireModLoader(loader);
    String project = a.optString("projectId", "");
    if (!project.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("Mod 项目 ID 无效。");
    File mods = modsDirectory(a.optString("instanceId", ""));
    ArrayList<FileInfo> files = new ArrayList<FileInfo>();
    if ("modrinth".equals(provider))
      resolveModrinth(project, "", version, loader, 0, new HashSet<String>(), files);
    else resolveCurse(project, version, loader, 0, new HashSet<String>(), files);
    JSONArray installed = new JSONArray();
    JSONArray metadata = new JSONArray();
    HashSet<String> names = new HashSet<String>();
    for (FileInfo info : files) {
      if (!names.add(info.name)) continue;
      File target = child(mods, info.name);
      if (!matchesHash(target, info.sha1)) LiteNetwork.download(info.url, info.hosts, target, info.sha1);
      installed.put(info.name);
      metadata.put(new JSONObject().put("name", info.name).put("sha1", info.sha1)
          .put("projectId", info.projectId).put("versionId", info.versionId));
    }
    if (installed.length() == 0) throw new IllegalStateException("没有可安装的兼容 Mod 文件。");
    return new JSONObject().put("installed", installed).put("files", metadata);
  }

  private JSONObject list(JSONObject a) throws Exception {
    File dir = modsDirectory(a.optString("instanceId", ""));
    JSONArray items = new JSONArray();
    File[] files = dir.listFiles();
    if (files != null)
      for (File f : files)
        if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".jar"))
          items.put(new JSONObject().put("name", f.getName()).put("size", f.length()));
    return new JSONObject().put("items", items);
  }

  private void resolveModrinth(
      String id,
      String pinned,
      String version,
      String loader,
      int depth,
      HashSet<String> seen,
      ArrayList<FileInfo> out)
      throws Exception {
    if (depth > 20) throw new IllegalStateException("Mod 必需依赖层级过深。");
    String visit = (pinned.length() > 0 ? pinned : id);
    if (!visit.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Mod 依赖 ID 无效。");
    if (seen.size() >= 100) throw new IllegalStateException("Mod 依赖数量过多。");
    if (!seen.add(visit)) return;
    JSONObject candidate;
    if (pinned.length() > 0)
      candidate =
          LiteNetwork.getJson(
              "https://api.modrinth.com/v2/version/" + URLEncoder.encode(pinned, "UTF-8"),
              MODRINTH,
              null);
    else {
      String url =
          "https://api.modrinth.com/v2/project/"
              + URLEncoder.encode(id, "UTF-8")
              + "/version?loaders="
              + URLEncoder.encode("[\"" + loader + "\"]", "UTF-8")
              + "&game_versions="
              + URLEncoder.encode("[\"" + version + "\"]", "UTF-8");
      byte[] raw = LiteNetwork.request("GET", url, MODRINTH, null, null);
      JSONArray versions = new JSONArray(new String(raw, UTF8));
      candidate = null;
      for (int i = 0; i < versions.length(); i++) {
        JSONObject item = versions.getJSONObject(i);
        if (!arrayContains(item.optJSONArray("game_versions"), version)
            || !arrayContains(item.optJSONArray("loaders"), loader)) continue;
        if (candidate == null
            || (!"release".equals(candidate.optString("version_type"))
                && "release".equals(item.optString("version_type")))) candidate = item;
      }
      if (candidate == null)
        throw new IllegalStateException("该 Mod 没有兼容 Minecraft " + version + " / " + LiteVersions.loaderName(loader) + " 的版本。");
    }
    if (!arrayContains(candidate.optJSONArray("game_versions"), version)
        || !arrayContains(candidate.optJSONArray("loaders"), loader))
      throw new IllegalStateException("指定的 Mod 依赖版本不兼容当前 Minecraft/" + LiteVersions.loaderName(loader) + "。");
    JSONArray deps = candidate.optJSONArray("dependencies");
    if (deps != null)
      for (int i = 0; i < deps.length(); i++) {
        JSONObject d = deps.getJSONObject(i);
        if ("required".equals(d.optString("dependency_type"))) {
          String dep = d.optString("project_id", "");
          String versionId = d.optString("version_id", "");
          if (dep.length() > 0 || versionId.length() > 0)
            resolveModrinth(dep, versionId, version, loader, depth + 1, seen, out);
        }
      }
    JSONArray fs = candidate.optJSONArray("files");
    JSONObject selected = null;
    if (fs != null)
      for (int i = 0; i < fs.length(); i++) {
        JSONObject f = fs.getJSONObject(i);
        if (selected == null || f.optBoolean("primary")) selected = f;
      }
    if (selected == null) throw new IllegalStateException("Mod 文件元数据不完整。");
    String hash =
        selected.optJSONObject("hashes") == null
            ? ""
            : selected.optJSONObject("hashes").optString("sha1", "");
    addFile(
        out,
        new FileInfo(
            safeName(selected.optString("filename")), selected.getString("url"), hash,
            new String[] {"cdn.modrinth.com"}, candidate.optString("project_id"), candidate.optString("id")));
  }

  private void resolveCurse(
      String id, String version, String loader, int depth, HashSet<String> seen, ArrayList<FileInfo> out)
      throws Exception {
    if (depth > 20) throw new IllegalStateException("Mod 必需依赖层级过深。");
    if (!id.matches("[0-9]{1,20}")) throw new IllegalArgumentException("CurseForge 项目 ID 无效。");
    if (seen.size() >= 100) throw new IllegalStateException("Mod 依赖数量过多。");
    if (!seen.add(id)) return;
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("x-api-key", key());
    String url =
        "https://api.curseforge.com/v1/mods/"
            + id
            + "/files?gameVersion="
            + URLEncoder.encode(version, "UTF-8")
            + "&modLoaderType=" + ("forge".equals(loader) ? "1" : "4") + "&pageSize=50";
    JSONArray data = LiteNetwork.getJson(url, CURSE, headers).optJSONArray("data");
    JSONObject chosen = null;
    if (data != null)
      for (int i = 0; i < data.length(); i++) {
        JSONObject f = data.getJSONObject(i);
        if (!f.optBoolean("isAvailable", true)) continue;
        if (chosen == null || f.optString("fileDate").compareTo(chosen.optString("fileDate")) > 0)
          chosen = f;
      }
    if (chosen == null) throw new IllegalStateException("该 Mod 没有兼容当前 Minecraft/" + LiteVersions.loaderName(loader) + " 的文件。");
    JSONArray deps = chosen.optJSONArray("dependencies");
    if (deps != null)
      for (int i = 0; i < deps.length(); i++) {
        JSONObject d = deps.getJSONObject(i);
        if (d.optInt("relationType") == 3)
          resolveCurse(String.valueOf(d.getLong("modId")), version, loader, depth + 1, seen, out);
      }
    String download = chosen.optString("downloadUrl", "");
    if (download.length() == 0) {
      JSONObject response =
          LiteNetwork.getJson(
              "https://api.curseforge.com/v1/mods/"
                  + id
                  + "/files/"
                  + chosen.getLong("id")
                  + "/download-url",
              CURSE,
              headers);
      download = response.optString("data", "");
    }
    if (download.length() == 0) throw new IllegalStateException("该 CurseForge 文件不允许第三方下载。");
    String sha = "";
    JSONArray hashes = chosen.optJSONArray("hashes");
    if (hashes != null)
      for (int i = 0; i < hashes.length(); i++) {
        JSONObject h = hashes.getJSONObject(i);
        if (h.optInt("algo") == 1) {
          sha = h.optString("value", "");
          break;
        }
      }
    addFile(out, new FileInfo(safeName(chosen.optString("fileName")), download, sha,
        new String[] {"forgecdn.net", "curseforge.com"}, id, String.valueOf(chosen.getLong("id"))));
  }

  private File modsDirectory(String id) throws Exception {
    if (!id.matches("[A-Za-z0-9._-]{1,100}") || id.contains(".."))
      throw new IllegalArgumentException("实例 ID 无效。");
    File root = new File(Tools.DIR_GAME_NEW, "lite-instances").getCanonicalFile();
    File instance = child(root, id);
    File mods = child(instance, "mods");
    if (!mods.exists() && !mods.mkdirs()) throw new java.io.IOException("无法创建 Mods 文件夹。");
    return mods;
  }

  private static File child(File parent, String name) throws Exception {
    File child = new File(parent, name).getCanonicalFile();
    String base = parent.getCanonicalPath();
    if (!child.getPath().startsWith(base + File.separator)) throw new SecurityException("文件路径无效。");
    return child;
  }

  private static void addFile(ArrayList<FileInfo> files, FileInfo file) {
    if (!file.sha1.matches("(?i)[0-9a-f]{40}"))
      throw new IllegalArgumentException("Mod 文件缺少有效的官方 SHA-1 校验值。");
    if (files.size() >= 100) throw new IllegalStateException("Mod 及其必需依赖超过 100 个文件。");
    files.add(file);
  }

  private static boolean arrayContains(JSONArray values, String target) {
    if (values == null) return false;
    for (int i = 0; i < values.length(); i++) if (target.equals(values.optString(i))) return true;
    return false;
  }

  private static JSONObject item(String id, String title, String desc, long downloads)
      throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("title", title)
        .put("description", desc)
        .put("downloads", downloads);
  }

  private static String safeName(String name) {
    if (name == null || !name.matches("[A-Za-z0-9._+() -]{1,180}\\.jar"))
      throw new IllegalArgumentException("下载目标不是有效 Mod JAR 文件。");
    return name;
  }

  private static String provider(JSONObject a) {
    String p = a.optString("provider", "");
    if (!"modrinth".equals(p) && !"curseforge".equals(p))
      throw new IllegalArgumentException("Mod 提供方无效。");
    return p;
  }

  private static String version(JSONObject a) {
    String v = a.optString("version", "");
    if (!v.matches("[A-Za-z0-9._-]{1,40}")) throw new IllegalArgumentException("游戏版本无效。");
    return v;
  }

  private static void requireModLoader(String l) {
    if (!"fabric".equals(l) && !"forge".equals(l)) throw new IllegalArgumentException("请选择 Fabric 或 Forge 实例；原版不能加载这些 Mod。");
  }

  private static boolean matchesHash(File file, String expected) throws Exception {
    if (!file.isFile() || file.length() == 0 || !expected.matches("(?i)[0-9a-f]{40}")) return false;
    MessageDigest hash = MessageDigest.getInstance("SHA-1");
    try (FileInputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[32768];
      for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, 0, count);
    }
    StringBuilder actual = new StringBuilder();
    for (byte value : hash.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return expected.equalsIgnoreCase(actual.toString());
  }

  private String key() throws Exception {
    try {
      return new String(
          decrypt(new AtomicFile(new File(context.getFilesDir(), "lite-curse.bin")).readFully()),
          UTF8);
    } catch (Exception e) {
      throw new IllegalStateException("请先配置 CurseForge API Key。");
    }
  }

  private void saveKey(String value) throws Exception {
    AtomicFile f = new AtomicFile(new File(context.getFilesDir(), "lite-curse.bin"));
    FileOutputStream out = null;
    try {
      out = f.startWrite();
      out.write(encrypt(value.getBytes(UTF8)));
      f.finishWrite(out);
    } catch (Exception e) {
      if (out != null) f.failWrite(out);
      throw e;
    }
  }

  private byte[] encrypt(byte[] p) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    c.init(Cipher.ENCRYPT_MODE, store().getCertificate("lite.curse.v1").getPublicKey());
    return c.doFinal(p);
  }

  private byte[] decrypt(byte[] p) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    c.init(Cipher.DECRYPT_MODE, (java.security.PrivateKey) store().getKey("lite.curse.v1", null));
    return c.doFinal(p);
  }

  private KeyStore store() throws Exception {
    KeyStore s = KeyStore.getInstance("AndroidKeyStore");
    s.load(null);
    if (!s.containsAlias("lite.curse.v1")) {
      java.security.KeyPairGenerator g =
          java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
      if (android.os.Build.VERSION.SDK_INT >= 23)
        g.initialize(
            new android.security.keystore.KeyGenParameterSpec.Builder(
                    "lite.curse.v1",
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setEncryptionPaddings(
                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .build());
      else {
        java.util.Calendar n = java.util.Calendar.getInstance(),
            e = java.util.Calendar.getInstance();
        e.add(java.util.Calendar.YEAR, 20);
        g.initialize(
            new android.security.KeyPairGeneratorSpec.Builder(context)
                .setAlias("lite.curse.v1")
                .setSubject(new javax.security.auth.x500.X500Principal("CN=Lite-MC"))
                .setSerialNumber(java.math.BigInteger.ONE)
                .setStartDate(n.getTime())
                .setEndDate(e.getTime())
                .build());
      }
      g.generateKeyPair();
    }
    return s;
  }

  private static final class FileInfo {
    final String name, url, sha1, projectId, versionId;
    final String[] hosts;

    FileInfo(String n, String u, String h, String[] hosts, String project, String version) {
      name = n;
      url = u;
      sha1 = h;
      this.hosts = hosts;
      projectId = project;
      versionId = version;
    }
  }
}
