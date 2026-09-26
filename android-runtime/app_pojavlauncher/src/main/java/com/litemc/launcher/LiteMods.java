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
    if ("mods.packVersions".equals(action)) return packVersions(args);
    if ("mods.install".equals(action)) return install(args);
    if ("mods.list".equals(action)) return list(args);
    throw new IllegalArgumentException("不支持的 Mod 操作。");
  }

  private JSONObject search(JSONObject a) throws Exception {
    String provider = provider(a), loader = a.optString("loader", "");
    String kind = kind(a);
    String version = "modpack".equals(kind) ? "" : version(a);
    if ("mod".equals(kind)) requireModLoader(loader);
    String query = a.optString("query", "").trim();
    if (query.length() > 80) throw new IllegalArgumentException("搜索词不能超过 80 个字符。");
    JSONArray items = new JSONArray();
    if ("modrinth".equals(provider)) {
      String projectType = "mod".equals(kind) ? "mod" : kind;
      String facet = "[[\"project_type:" + projectType + "\"]";
      if (!"modpack".equals(kind)) facet += ",[\"versions:" + version + "\"]";
      if ("mod".equals(kind)) facet += ",[\"categories:" + loader + "\"]";
      facet += "]";
      String facets =
          java.net.URLEncoder.encode(facet, "UTF-8");
      JSONObject response =
          LiteNetwork.getJson(
              "https://api.modrinth.com/v2/search?"
                  + (query.length() == 0 ? "" : "query=" + URLEncoder.encode(query, "UTF-8") + "&")
                  + "limit=20&index=downloads&facets="
                  + facets,
              MODRINTH,
              null);
      JSONArray hits = response.optJSONArray("hits");
      if (hits != null)
        for (int i = 0; i < hits.length(); i++) {
          JSONObject h = hits.getJSONObject(i);
          String pageUrl = h.optString("page_url", "");
          String slug = h.optString("slug", "");
          if (pageUrl.length() == 0 && slug.matches("[A-Za-z0-9_-]{1,100}"))
            pageUrl = "https://modrinth.com/" + kind + "/" + slug;
          items.put(
              item(
                  h.optString("project_id"),
                  h.optString("title"),
                  h.optString("description"),
                  h.optLong("downloads"), h.optString("icon_url"), pageUrl,
                  h.optString("author"), provider, kind));
        }
    } else {
      String key = key();
      Map<String, String> headers = new HashMap<String, String>();
      headers.put("x-api-key", key);
      String u =
          "https://api.curseforge.com/v1/mods/search?gameId=432&classId=" + curseClass(kind)
              + ("modpack".equals(kind) ? "" : "&gameVersion=" + URLEncoder.encode(version, "UTF-8"))
              + ("mod".equals(kind) ? "&modLoaderType=" + ("forge".equals(loader) ? "1" : "4") : "")
              + "&pageSize=20"
              + (query.length() == 0 ? "" : "&searchFilter=" + URLEncoder.encode(query, "UTF-8"));
      JSONArray data = LiteNetwork.getJson(u, CURSE, headers).optJSONArray("data");
      if (data != null)
        for (int i = 0; i < data.length(); i++) {
          JSONObject h = data.getJSONObject(i);
          JSONObject logo = h.optJSONObject("logo");
          JSONArray authors = h.optJSONArray("authors");
          JSONObject author = authors == null ? null : authors.optJSONObject(0);
          JSONObject links = h.optJSONObject("links");
          items.put(
              item(
                  String.valueOf(h.optLong("id")),
                  h.optString("name"),
                  h.optString("summary"),
                  h.optLong("downloadCount"), logo == null ? "" : logo.optString("url"),
                  links == null ? "" : links.optString("websiteUrl"),
                  author == null ? "" : author.optString("name"),
                  provider, kind));
        }
    }
    return new JSONObject().put("items", items);
  }

  private JSONObject install(JSONObject a) throws Exception {
    String provider = provider(a), loader = a.optString("loader", "");
    String kind = kind(a);
    if ("modpack".equals(kind))
      throw new IllegalArgumentException("整合包请从独立整合包页面下载和安装。");
    String version = version(a);
    if ("mod".equals(kind)) requireModLoader(loader);
    String project = a.optString("projectId", "");
    if (!project.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("Mod 项目 ID 无效。");
    File instance = instanceDirectory(a.optString("instanceId", ""));
    if (!"mod".equals(kind)) {
      FileInfo pack = "modrinth".equals(provider)
          ? resolveProjectFile(project, version, loader, kind)
          : resolveCurseGeneric(project, version, kind);
      File destination;
      if ("resourcepack".equals(kind)) {
        File dir = child(instance, "resourcepacks");
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("无法创建资源包文件夹。");
        destination = child(dir, pack.name);
        if (!matchesHash(destination, pack.sha1)) LiteNetwork.download(pack.url, pack.hosts, destination, pack.sha1);
      } else throw new IllegalArgumentException("整合包请从独立整合包页面下载和安装。");
      return new JSONObject().put("installed", new JSONArray().put(pack.name)).put("kind", kind);
    }
    File mods = child(instance, "mods");
    if (!mods.exists() && !mods.mkdirs()) throw new java.io.IOException("无法创建 Mods 文件夹。");
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

  private JSONObject packVersions(JSONObject args) throws Exception {
    String provider = provider(args);
    String project = packProjectId(provider, args.optString("projectId", ""));
    JSONArray items = new JSONArray();
    if ("modrinth".equals(provider)) {
      JSONObject projectInfo = LiteNetwork.getJson(
          "https://api.modrinth.com/v2/project/" + URLEncoder.encode(project, "UTF-8"), MODRINTH, null);
      if (!"modpack".equals(projectInfo.optString("project_type")))
        throw new IllegalArgumentException("该 Modrinth 项目不是整合包。");
      JSONArray versions = new JSONArray(new String(LiteNetwork.request("GET",
          "https://api.modrinth.com/v2/project/" + URLEncoder.encode(project, "UTF-8") + "/version", MODRINTH, null, null), UTF8));
      for (int i = 0; i < versions.length(); i++) {
        JSONObject version = versions.getJSONObject(i);
        if (!hasModrinthPackArchive(version)) continue;
        items.put(packVersionItem(version.optString("id"), version.optString("name"),
            version.optJSONArray("game_versions"), version.optJSONArray("loaders")));
      }
    } else {
      Map<String, String> headers = curseHeaders();
      JSONObject info = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + project, CURSE, headers).optJSONObject("data");
      if (info == null || info.optInt("classId") != curseClass("modpack"))
        throw new IllegalArgumentException("该 CurseForge 项目不是整合包。");
      JSONArray files = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + project + "/files?pageSize=50", CURSE, headers).optJSONArray("data");
      if (files != null) for (int i = 0; i < files.length(); i++) {
        JSONObject file = files.getJSONObject(i);
        if (!file.optBoolean("isAvailable", true)) continue;
        String filename = file.optString("fileName", "");
        try { safePackName(filename); } catch (IllegalArgumentException ignored) { continue; }
        if (!curseSha1(file).matches("(?i)[0-9a-f]{40}")) continue;
        JSONArray allVersions = file.optJSONArray("gameVersions");
        JSONArray gameVersions = new JSONArray();
        JSONArray loaders = new JSONArray();
        if (allVersions != null) for (int j = 0; j < allVersions.length(); j++) {
          String value = allVersions.optString(j);
          if (isLoaderLabel(value)) {
            String loader = value.toLowerCase(java.util.Locale.ROOT);
            if (!arrayContains(loaders, loader)) loaders.put(loader);
          } else if (value.length() > 0 && !arrayContains(gameVersions, value)) {
            gameVersions.put(value);
          }
        }
        items.put(packVersionItem(String.valueOf(file.getLong("id")), file.optString("displayName", file.optString("fileName")), gameVersions, loaders));
      }
    }
    return new JSONObject().put("items", items);
  }

  /** Downloads a verified pack archive only; installation belongs to LitePacks. */
  public synchronized File downloadPack(JSONObject args) throws Exception {
    String provider = provider(args);
    String project = packProjectId(provider, args.optString("projectId", ""));
    String versionId = args.optString("versionId", "");
    if (!versionId.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("整合包版本 ID 无效。");
    FileInfo pack = "modrinth".equals(provider)
        ? modrinthPackFile(project, versionId)
        : cursePackArchiveFileInfo(project, versionId);
    File root = new File(context.getFilesDir(), "lite-pack-downloads").getCanonicalFile();
    File folder = child(root, provider + "-" + versionId);
    if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("无法创建整合包缓存目录。");
    File destination = child(folder, safePackName(pack.name));
    if (!matchesHash(destination, pack.sha1)) LiteNetwork.download(pack.url, pack.hosts, destination, pack.sha1);
    return destination;
  }

  /** Native-only CurseForge manifest helper. URL and hash come exclusively from CurseForge. */
  public JSONObject cursePackFile(String project, String fileId) throws Exception {
    FileInfo file = curseManifestFileInfo(packProjectId("curseforge", project), fileId);
    return new JSONObject().put("name", file.name).put("url", file.url).put("sha1", file.sha1);
  }

  private FileInfo modrinthPackFile(String project, String versionId) throws Exception {
    JSONObject info = LiteNetwork.getJson("https://api.modrinth.com/v2/project/" + URLEncoder.encode(project, "UTF-8"), MODRINTH, null);
    if (!"modpack".equals(info.optString("project_type"))) throw new IllegalArgumentException("该 Modrinth 项目不是整合包。");
    JSONObject version = LiteNetwork.getJson("https://api.modrinth.com/v2/version/" + URLEncoder.encode(versionId, "UTF-8"), MODRINTH, null);
    String resolvedProject = info.optString("id", "");
    if (!resolvedProject.matches("[A-Za-z0-9_-]{1,100}")
        || !resolvedProject.equals(version.optString("project_id"))
        || !versionId.equals(version.optString("id")))
      throw new SecurityException("整合包版本不属于指定项目。");
    JSONObject selected = null; JSONArray files = version.optJSONArray("files");
    if (files != null) for (int i = 0; i < files.length(); i++) {
      JSONObject file = files.getJSONObject(i); String name = file.optString("filename", "");
      if (file.optBoolean("primary") && name.toLowerCase(java.util.Locale.ROOT).endsWith(".mrpack")) {
        selected = file;
        break;
      }
    }
    if (selected == null) throw new IllegalStateException("官方整合包文件元数据不完整。");
    String sha = selected.optJSONObject("hashes") == null ? "" : selected.optJSONObject("hashes").optString("sha1", "");
    if (!sha.matches("(?i)[0-9a-f]{40}")) throw new IllegalStateException("官方整合包文件缺少 SHA-1 校验值。");
    return new FileInfo(safePackName(selected.optString("filename")), selected.getString("url"), sha, new String[] {"cdn.modrinth.com"}, resolvedProject, versionId);
  }

  /** Looks up an exact CurseForge pack file after proving that the project is a modpack. */
  private FileInfo cursePackArchiveFileInfo(String project, String fileId) throws Exception {
    if (!fileId.matches("[0-9]{1,20}")) throw new IllegalArgumentException("CurseForge 文件 ID 无效。");
    Map<String, String> headers = curseHeaders();
    JSONObject info = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + project, CURSE, headers).optJSONObject("data");
    if (info == null || info.optInt("classId") != curseClass("modpack")) throw new IllegalArgumentException("该 CurseForge 项目不是整合包。");
    JSONObject chosen = curseExactFile(project, fileId, headers);
    String sha = curseSha1(chosen); if (!sha.matches("(?i)[0-9a-f]{40}")) throw new IllegalStateException("官方整合包文件缺少 SHA-1 校验值。");
    String url = chosen.optString("downloadUrl", "");
    if (url.length() == 0) url = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + project + "/files/" + fileId + "/download-url", CURSE, headers).optString("data", "");
    if (!isCurseDownloadUrl(url)) throw new SecurityException("CurseForge 未提供可验证的官方下载地址。");
    return new FileInfo(safePackName(chosen.optString("fileName")), url, sha, new String[] {"forgecdn.net", "curseforge.com"}, project, fileId);
  }

  /** Resolves a MOD entry in a CurseForge manifest; it is deliberately not restricted to packs. */
  private FileInfo curseManifestFileInfo(String project, String fileId) throws Exception {
    if (!fileId.matches("[0-9]{1,20}")) throw new IllegalArgumentException("CurseForge 文件 ID 无效。");
    Map<String, String> headers = curseHeaders();
    JSONObject chosen = curseExactFile(project, fileId, headers);
    String sha = curseSha1(chosen);
    if (!sha.matches("(?i)[0-9a-f]{40}"))
      throw new IllegalStateException("官方 Mod 文件缺少 SHA-1 校验值。");
    String url = curseDownloadUrl(project, fileId, chosen, headers);
    if (!isCurseDownloadUrl(url)) throw new SecurityException("CurseForge 未提供可验证的官方下载地址。");
    return new FileInfo(safeArchiveName(chosen.optString("fileName")), url, sha,
        new String[] {"forgecdn.net", "curseforge.com"}, project, fileId);
  }

  private static JSONObject curseExactFile(String project, String fileId, Map<String, String> headers)
      throws Exception {
    JSONObject chosen = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + project
        + "/files/" + fileId, CURSE, headers).optJSONObject("data");
    if (chosen == null || !fileId.equals(String.valueOf(chosen.optLong("id")))
        || !project.equals(String.valueOf(chosen.optLong("modId"))))
      throw new SecurityException("CurseForge 文件不属于指定项目。");
    return chosen;
  }

  private static String curseDownloadUrl(String project, String fileId, JSONObject file,
      Map<String, String> headers) throws Exception {
    String url = file.optString("downloadUrl", "");
    if (url.length() == 0)
      url = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + project + "/files/"
          + fileId + "/download-url", CURSE, headers).optString("data", "");
    return url;
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
    File mods = child(instanceDirectory(id), "mods");
    if (!mods.exists() && !mods.mkdirs()) throw new java.io.IOException("无法创建 Mods 文件夹。");
    return mods;
  }

  private File instanceDirectory(String id) throws Exception {
    if (!id.matches("[A-Za-z0-9._-]{1,100}") || id.contains(".."))
      throw new IllegalArgumentException("实例 ID 无效。");
    File root = new File(Tools.DIR_GAME_NEW, "lite-instances").getCanonicalFile();
    return child(root, id);
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

  private FileInfo resolveProjectFile(String id, String version, String loader, String kind) throws Exception {
    String url = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(id, "UTF-8") + "/version?game_versions="
        + URLEncoder.encode("[\"" + version + "\"]", "UTF-8");
    if ("mod".equals(kind)) url += "&loaders=" + URLEncoder.encode("[\"" + loader + "\"]", "UTF-8");
    JSONArray versions = new JSONArray(new String(LiteNetwork.request("GET", url, MODRINTH, null, null), UTF8));
    JSONObject chosen = null;
    for (int i = 0; i < versions.length(); i++) {
      JSONObject candidate = versions.getJSONObject(i);
      if (!arrayContains(candidate.optJSONArray("game_versions"), version)) continue;
      if ("mod".equals(kind) && !arrayContains(candidate.optJSONArray("loaders"), loader)) continue;
      if (chosen == null || (!"release".equals(chosen.optString("version_type")) && "release".equals(candidate.optString("version_type")))) chosen = candidate;
    }
    if (chosen == null) throw new IllegalStateException("该项目没有兼容 Minecraft " + version + " 的文件。");
    JSONArray fs = chosen.optJSONArray("files");
    JSONObject selected = null;
    if (fs != null) for (int i = 0; i < fs.length(); i++) {
      JSONObject f = fs.getJSONObject(i);
      String filename = f.optString("filename", "");
      if (selected == null || f.optBoolean("primary") || ("modpack".equals(kind) && filename.endsWith(".mrpack"))) selected = f;
    }
    if (selected == null) throw new IllegalStateException("项目文件元数据不完整。");
    String sha = selected.optJSONObject("hashes") == null ? "" : selected.optJSONObject("hashes").optString("sha1", "");
    if (!sha.matches("(?i)[0-9a-f]{40}")) throw new IllegalStateException("项目文件缺少官方 SHA-1 校验值。");
    return new FileInfo(safeArchiveName(selected.optString("filename")), selected.getString("url"), sha,
        new String[] {"cdn.modrinth.com"}, id, chosen.optString("id"));
  }

  private FileInfo resolveCurseGeneric(String id, String version, String kind) throws Exception {
    if (!id.matches("[0-9]{1,20}")) throw new IllegalArgumentException("CurseForge 项目 ID 无效。");
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("x-api-key", key());
    JSONArray data = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + id + "/files?gameVersion="
        + URLEncoder.encode(version, "UTF-8") + "&pageSize=50", CURSE, headers).optJSONArray("data");
    JSONObject chosen = null;
    if (data != null) for (int i = 0; i < data.length(); i++) {
      JSONObject f = data.getJSONObject(i);
      if (!f.optBoolean("isAvailable", true)) continue;
      if (chosen == null || f.optString("fileDate").compareTo(chosen.optString("fileDate")) > 0) chosen = f;
    }
    if (chosen == null) throw new IllegalStateException("该项目没有兼容 Minecraft " + version + " 的文件。");
    String download = chosen.optString("downloadUrl", "");
    if (download.length() == 0) {
      download = LiteNetwork.getJson("https://api.curseforge.com/v1/mods/" + id + "/files/" + chosen.getLong("id") + "/download-url", CURSE, headers).optString("data", "");
    }
    String sha = ""; JSONArray hashes = chosen.optJSONArray("hashes");
    if (hashes != null) for (int i = 0; i < hashes.length(); i++) if (hashes.getJSONObject(i).optInt("algo") == 1) { sha = hashes.getJSONObject(i).optString("value", ""); break; }
    if (download.length() == 0 || !sha.matches("(?i)[0-9a-f]{40}")) throw new IllegalStateException("项目文件不允许安全下载。");
    return new FileInfo(safeArchiveName(chosen.optString("fileName")), download, sha,
        new String[] {"forgecdn.net", "curseforge.com"}, id, String.valueOf(chosen.getLong("id")));
  }

  private static int curseClass(String kind) {
    if ("resourcepack".equals(kind)) return 12;
    if ("modpack".equals(kind)) return 4471;
    return 6;
  }

  /** A downloadable Modrinth pack must expose one primary .mrpack file. */
  private static boolean hasModrinthPackArchive(JSONObject version) {
    JSONArray files = version.optJSONArray("files");
    if (files == null) return false;
    for (int i = 0; i < files.length(); i++) {
      JSONObject file = files.optJSONObject(i);
      if (file == null || !file.optBoolean("primary")) continue;
      try {
        safePackName(file.optString("filename", ""));
        JSONObject hashes = file.optJSONObject("hashes");
        if (file.optString("filename", "").toLowerCase(java.util.Locale.ROOT).endsWith(".mrpack")
            && hashes != null && hashes.optString("sha1", "").matches("(?i)[0-9a-f]{40}")
            && file.optString("url", "").length() > 0) return true;
      } catch (IllegalArgumentException ignored) {
        // A malformed file entry must not be offered as a pack version.
      }
    }
    return false;
  }

  private static boolean isLoaderLabel(String value) {
    return "Forge".equalsIgnoreCase(value) || "Fabric".equalsIgnoreCase(value)
        || "NeoForge".equalsIgnoreCase(value) || "Quilt".equalsIgnoreCase(value);
  }

  private static JSONObject packVersionItem(String id, String name, JSONArray gameVersions, JSONArray loaders)
      throws Exception {
    return new JSONObject().put("id", id).put("name", name)
        .put("gameVersions", gameVersions == null ? new JSONArray() : gameVersions)
        .put("loaders", loaders == null ? new JSONArray() : loaders);
  }

  private static String packProjectId(String provider, String value) {
    String regex = "curseforge".equals(provider) ? "[0-9]{1,20}" : "[A-Za-z0-9_-]{1,100}";
    if (!value.matches(regex)) throw new IllegalArgumentException("整合包项目 ID 无效。");
    return value;
  }

  private Map<String, String> curseHeaders() throws Exception {
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("x-api-key", key());
    return headers;
  }

  private static String curseSha1(JSONObject file) {
    JSONArray hashes = file.optJSONArray("hashes");
    if (hashes != null) for (int i = 0; i < hashes.length(); i++) {
      JSONObject hash = hashes.optJSONObject(i);
      if (hash != null && hash.optInt("algo") == 1) return hash.optString("value", "");
    }
    return "";
  }

  private static boolean isCurseDownloadUrl(String value) {
    try {
      java.net.URL url = new java.net.URL(value);
      String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
      return "https".equalsIgnoreCase(url.getProtocol())
          && url.getPort() == -1 && url.getUserInfo() == null && url.getRef() == null
          && (host.equals("edge.forgecdn.net") || host.equals("mediafilez.forgecdn.net"));
    } catch (Exception ignored) { return false; }
  }

  private static String kind(JSONObject a) {
    String value = a.optString("kind", "mod");
    if (!"mod".equals(value) && !"modpack".equals(value) && !"resourcepack".equals(value))
      throw new IllegalArgumentException("资源类型无效。");
    return value;
  }

  private static String safeArchiveName(String name) {
    if (name == null || !name.matches("(?i)[^\\\\/:*?\"<>|\\p{Cntrl}]{1,180}\\.(zip|mrpack|jar|mcpack|mcmeta)"))
      throw new IllegalArgumentException("下载目标不是有效资源文件。");
    return name;
  }

  /** Allows ordinary Unicode pack titles but never directory separators or control characters. */
  private static String safePackName(String name) {
    if (name == null || !name.matches("[^\\\\/:*?\"<>|\\p{Cntrl}]{1,180}\\.(mrpack|zip)"))
      throw new IllegalArgumentException("下载目标不是有效整合包文件名。");
    return name;
  }

  private static boolean arrayContains(JSONArray values, String target) {
    if (values == null) return false;
    for (int i = 0; i < values.length(); i++) if (target.equals(values.optString(i))) return true;
    return false;
  }

  private static JSONObject item(String id, String title, String desc, long downloads, String iconUrl,
      String pageUrl, String author, String source, String kind)
      throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("title", title)
        .put("description", desc)
        .put("downloads", downloads)
        .put("iconUrl", iconUrl)
        .put("pageUrl", pageUrl)
        .put("author", author)
        .put("source", source)
        .put("kind", kind);
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
