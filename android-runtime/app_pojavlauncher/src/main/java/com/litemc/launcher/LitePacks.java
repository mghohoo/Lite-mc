package com.litemc.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Strict client-pack reader. This class never selects or registers a playable instance.
 * Format references:
 * https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack
 * https://support.curseforge.com/support/solutions/articles/9000198500
 * https://support.curseforge.com/support/solutions/articles/9000210425
 */
public final class LitePacks {
  private static final int MAX_ENTRIES = 10000;
  private static final int MAX_FILES = 2048;
  private static final int MAX_MANIFEST = 2 * 1024 * 1024;
  private static final long MAX_ARCHIVE = 1024L * 1024 * 1024;
  private static final long MAX_ZIP_FILE = 512L * 1024 * 1024;
  private static final long MAX_EXPANDED = 2L * 1024 * 1024 * 1024;
  // LiteNetwork also enforces this per-download streaming limit.
  private static final long MAX_DOWNLOAD = 1024L * 1024 * 1024;
  private static final long MAX_CONTENT = 4L * 1024 * 1024 * 1024;
  // Official mrpack specification sources plus GitHub's release redirects.
  private static final String[] MRPACK_HOSTS = {"cdn.modrinth.com", "github.com",
      "raw.githubusercontent.com", "gitlab.com", "release-assets.githubusercontent.com",
      "objects.githubusercontent.com"};
  private static final String[] CURSE_CDN = {"mediafilez.forgecdn.net", "edge.forgecdn.net"};

  private LitePacks() {}

  public static final class Plan {
    public final String name, minecraft, loader, loaderVersion;
    private final File archive;
    private final String archiveHash;
    private final List<Download> downloads;
    private final List<CurseFile> curseFiles;
    private final List<Override> overrides;

    private Plan(File archive, String archiveHash, String name, String minecraft,
        String loader, String loaderVersion, List<Download> downloads,
        List<CurseFile> curseFiles, List<Override> overrides) {
      this.archive = archive;
      this.archiveHash = archiveHash;
      this.name = name;
      this.minecraft = minecraft;
      this.loader = loader;
      this.loaderVersion = loaderVersion;
      this.downloads = Collections.unmodifiableList(new ArrayList<>(downloads));
      this.curseFiles = Collections.unmodifiableList(new ArrayList<>(curseFiles));
      this.overrides = Collections.unmodifiableList(new ArrayList<>(overrides));
    }

    /** Only a newly-created instance directory is accepted; empty mods/ subdirs are OK.
     * All content is staged first. Callers must register the instance only after success.
     * Loader installation/support is the caller's responsibility (Forge is NOT installed here).
     */
    public synchronized void prepare(File newEmptyDirectory, LiteMods mods) throws Exception {
      if (newEmptyDirectory == null) throw invalid("缺少新的整合包实例目录");
      File root = newEmptyDirectory.getCanonicalFile();
      if (!newEmptyDirectory.getAbsoluteFile().equals(root))
        throw invalid("整合包实例目录不能经过符号链接或相对跳转");
      if (!root.isDirectory()) throw invalid("请先创建新的空实例目录，不能导入到当前实例");
      requireEmpty(root, null, new int[] {0});
      if (!archive.isFile() || !archiveHash.equals(digest(archive, "SHA-256", MAX_ARCHIVE)))
        throw invalid("整合包在检查后发生变化，请重新选择文件");

      // Resolve every precise CurseForge file before downloading any content.
      List<Download> resolved = new ArrayList<>(downloads);
      if (!curseFiles.isEmpty() && mods == null) throw invalid("CurseForge 文件需要配置对应下载服务");
      for (CurseFile file : curseFiles) {
        JSONObject info = mods.cursePackFile(file.project, file.file);
        String filename = path(string(info, "name", 255), false);
        if (filename.indexOf('/') >= 0 || !filename.toLowerCase(Locale.ROOT).endsWith(".jar"))
          throw invalid("CurseForge 整合包依赖不是有效的 Mod JAR");
        String url = string(info, "url", 8192);
        checkedUrl(url, CURSE_CDN);
        resolved.add(new Download("mods/" + filename, Collections.singletonList(url),
            hash(string(info, "sha1", 40), 40), null, -1, CURSE_CDN));
      }
      Map<String, Target> targets = targets(resolved, overrides);
      for (Target target : targets.values()) child(root, target.path);
      requireEmpty(root, null, new int[] {0});

      File stage = new File(root, ".lite-pack-" + java.util.UUID.randomUUID().toString());
      if (!stage.mkdir()) throw invalid("无法创建整合包临时目录");
      List<File> committed = new ArrayList<>();
      try {
        long total = 0;
        for (Download item : resolved) {
          File destination = child(stage, item.path);
          Exception failure = null;
          for (String url : item.urls) {
            try {
              checkedUrl(url, item.hosts);
              LiteNetwork.download(url, item.hosts, destination, item.sha1);
              if (!destination.isFile() || destination.length() > MAX_DOWNLOAD
                  || (item.size >= 0 && destination.length() != item.size)
                  || !item.sha1.equals(digest(destination, "SHA-1", MAX_DOWNLOAD))
                  || (item.sha512 != null && !item.sha512.equals(digest(destination, "SHA-512", MAX_DOWNLOAD))))
                throw invalid("整合包文件大小或哈希校验失败：" + item.path);
              failure = null;
              break;
            } catch (Exception ex) {
              failure = ex;
            }
          }
          if (failure != null) throw failure;
          total = boundedAdd(total, destination.length(), MAX_CONTENT, "整合包下载内容过大");
        }

        // Ordering is intentional: ordinary overrides first, client overrides last.
        try (ZipFile zip = new ZipFile(archive)) {
          for (Override item : overrides) {
            ZipEntry entry = zip.getEntry(item.entry);
            if (entry == null || entry.isDirectory() != item.directory || entry.getSize() != item.size)
              throw invalid("整合包覆盖文件在检查后发生变化");
            File destination = child(stage, item.path);
            if (item.directory) {
              mkdir(destination);
              continue;
            }
            mkdir(destination.getParentFile());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (InputStream input = zip.getInputStream(entry);
                FileOutputStream output = new FileOutputStream(destination)) {
              byte[] buffer = new byte[32768];
              for (int n; (n = input.read(buffer)) != -1;) {
                count = boundedAdd(count, n, MAX_ZIP_FILE, "覆盖文件解压后过大");
                total = boundedAdd(total, n, MAX_CONTENT, "整合包内容总量过大");
                digest.update(buffer, 0, n);
                output.write(buffer, 0, n);
              }
            }
            if (count != item.size || !hex(digest.digest()).equals(item.sha256))
              throw invalid("整合包覆盖文件校验失败：" + item.path);
          }
        }
        // Do not overwrite files introduced by another task while content was downloading.
        requireEmpty(root, stage, new int[] {0});
        for (Target target : targets.values()) {
          File source = child(stage, target.path), destination = child(root, target.path);
          if (target.directory) { mkdir(destination); continue; }
          if (!source.isFile() || destination.exists()) throw invalid("实例目录出现文件冲突，未覆盖已有文件");
          mkdir(destination.getParentFile());
          if (!source.renameTo(destination)) throw invalid("无法提交整合包文件：" + target.path);
          committed.add(destination);
        }
      } catch (Exception ex) {
        // Only files moved by this invocation are eligible for rollback.
        for (File file : committed) {
          if (file.getCanonicalPath().startsWith(root.getPath() + File.separator) && file.isFile()
              && !file.delete()) ex.addSuppressed(invalid("无法清理未提交的整合包文件：" + file.getName()));
        }
        throw ex;
      } finally {
        deleteStage(stage, stage);
      }
    }
  }

  public static Plan inspect(File archive) throws Exception {
    if (archive == null || !archive.isFile() || archive.length() <= 0 || archive.length() > MAX_ARCHIVE)
      throw invalid("整合包文件不存在、为空或超过 1 GiB 限额");
    File source = archive.getCanonicalFile();
    String archiveHash = digest(source, "SHA-256", MAX_ARCHIVE);
    Map<String, Entry> entries = new LinkedHashMap<>();
    JSONObject manifest;
    boolean modrinth;
    try (ZipFile zip = new ZipFile(source)) {
      Set<String> seen = new HashSet<>();
      long expanded = 0;
      Enumeration<? extends ZipEntry> enumeration = zip.entries();
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        if (entries.size() >= MAX_ENTRIES) throw invalid("整合包 ZIP 项目超过 10000 个");
        String safe = path(entry.getName(), entry.isDirectory());
        if (!seen.add(safe.toLowerCase(Locale.ROOT))) throw invalid("整合包 ZIP 包含重复或大小写冲突的路径");
        if (entry.getSize() < 0 || entry.getSize() > MAX_ZIP_FILE
            || (entry.isDirectory() && entry.getSize() != 0))
          throw invalid("ZIP 文件大小无效或单文件超过 512 MiB");
        expanded = boundedAdd(expanded, entry.getSize(), MAX_EXPANDED, "整合包解压总量超过 2 GiB");
        entries.put(entry.getName(), new Entry(entry.getName(), safe, entry.isDirectory(), entry.getSize()));
      }
      Entry mr = entries.get("modrinth.index.json"), cf = entries.get("manifest.json");
      if ((mr == null) == (cf == null)) throw invalid("需要唯一的 Modrinth 或 CurseForge 根清单，不能同时包含两种清单");
      modrinth = mr != null;
      Entry index = modrinth ? mr : cf;
      if (index.directory || index.size > MAX_MANIFEST) throw invalid("整合包清单无效或超过 2 MiB");
      byte[] bytes = read(zip.getInputStream(zip.getEntry(index.entry)), MAX_MANIFEST);
      String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      checkJsonDepth(json);
      manifest = new JSONObject(json);

      // Validate actual expanded lengths and CRCs, including ignored ZIP entries, before I/O.
      long actual = 0;
      for (Entry item : entries.values()) {
        if (item.directory) continue;
        ZipEntry entry = zip.getEntry(item.entry);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        CRC32 crc = new CRC32();
        long size = 0;
        try (InputStream input = zip.getInputStream(entry)) {
          byte[] buffer = new byte[32768];
          for (int n; (n = input.read(buffer)) != -1;) {
            size = boundedAdd(size, n, MAX_ZIP_FILE, "ZIP 单文件实际展开量超过限额");
            actual = boundedAdd(actual, n, MAX_EXPANDED, "ZIP 实际展开总量超过限额");
            digest.update(buffer, 0, n); crc.update(buffer, 0, n);
          }
        }
        if (size != item.size || crc.getValue() != entry.getCrc()) throw invalid("ZIP 文件损坏或长度校验失败");
        item.sha256 = hex(digest.digest());
      }
    }
    if (!archiveHash.equals(digest(source, "SHA-256", MAX_ARCHIVE))) throw invalid("检查期间整合包文件发生变化");

    String name = string(manifest, "name", 160), minecraft, loader = "vanilla", loaderVersion = "";
    List<Download> downloads = new ArrayList<>();
    List<CurseFile> curseFiles = new ArrayList<>();
    List<Override> overrides = new ArrayList<>();
    if (modrinth) {
      if (integer(manifest, "formatVersion", 1, 1) != 1 || !"minecraft".equals(string(manifest, "game", 40)))
        throw invalid("仅支持 Minecraft 的 Modrinth formatVersion 1");
      string(manifest, "versionId", 256);
      JSONObject dependencies = manifest.getJSONObject("dependencies");
      minecraft = version(string(dependencies, "minecraft", 80));
      Iterator<String> keys = dependencies.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if ("minecraft".equals(key)) continue;
        String selected = "fabric-loader".equals(key) ? "fabric" : "quilt-loader".equals(key) ? "quilt"
            : "forge".equals(key) ? "forge" : "neoforge".equals(key) ? "neoforge" : null;
        if (selected == null) throw invalid("尚不支持整合包依赖：" + key);
        if (!"vanilla".equals(loader)) throw invalid("暂不支持含多个加载器依赖的整合包");
        loader = selected; loaderVersion = version(string(dependencies, key, 80));
      }
      JSONArray files = manifest.getJSONArray("files");
      if (files.length() > MAX_FILES) throw invalid("整合包下载文件超过 2048 个");
      Set<String> paths = new HashSet<>();
      long total = 0;
      for (int i = 0; i < files.length(); i++) {
        JSONObject file = files.getJSONObject(i);
        String target = path(string(file, "path", 1024), false);
        if (!paths.add(target.toLowerCase(Locale.ROOT))) throw invalid("整合包清单包含重复目标路径");
        JSONObject hashes = file.getJSONObject("hashes");
        String sha1 = hash(string(hashes, "sha1", 40), 40), sha512 = hash(string(hashes, "sha512", 128), 128);
        long size = integer(file, "fileSize", 0, MAX_DOWNLOAD);
        JSONArray urls = file.getJSONArray("downloads");
        if (urls.length() < 1 || urls.length() > 16) throw invalid("整合包文件下载地址数量无效");
        boolean client = true;
        if (file.has("env")) {
          JSONObject env = file.getJSONObject("env");
          String side = environment(env, "client"); environment(env, "server");
          client = !"unsupported".equals(side); // Optional files are deliberately included.
        }
        List<String> allowed = new ArrayList<>();
        for (int j = 0; j < urls.length(); j++) {
          Object raw = urls.get(j);
          if (!(raw instanceof String)) throw invalid("下载地址必须是 HTTPS 字符串");
          String url = (String) raw;
          URI uri = validUri(url);
          for (String host : MRPACK_HOSTS) {
            if (host.equalsIgnoreCase(uri.getHost())) { allowed.add(url); break; }
          }
        }
        if (!client) continue;
        if (allowed.isEmpty()) throw invalid("整合包客户端文件不在规范允许的下载来源：" + target);
        total = boundedAdd(total, size, MAX_CONTENT, "整合包下载总量超过 4 GiB");
        downloads.add(new Download(target, allowed, sha1, sha512, size, MRPACK_HOSTS));
      }
      overrides.addAll(overrides(entries, "overrides"));
      overrides.addAll(overrides(entries, "client-overrides"));
    } else {
      if (!"minecraftModpack".equals(string(manifest, "manifestType", 40))
          || integer(manifest, "manifestVersion", 1, 1) != 1)
        throw invalid("仅支持 CurseForge minecraftModpack manifestVersion 1");
      JSONObject game = manifest.getJSONObject("minecraft");
      minecraft = version(string(game, "version", 80));
      JSONArray loaders = game.getJSONArray("modLoaders");
      if (loaders.length() > 1) throw invalid("暂不支持含多个加载器条目的 CurseForge 整合包");
      if (loaders.length() == 1) {
        JSONObject selected = loaders.getJSONObject(0);
        if (!(selected.get("primary") instanceof Boolean)) throw invalid("CurseForge primary 必须是布尔值");
        String id = string(selected, "id", 100);
        int separator = id.indexOf('-');
        if (separator < 1) throw invalid("CurseForge 加载器标识无效");
        loader = id.substring(0, separator);
        if (!loader.matches("fabric|forge|quilt|neoforge|liteloader")) throw invalid("不支持的 CurseForge 加载器：" + loader);
        loaderVersion = version(id.substring(separator + 1));
      }
      JSONArray files = manifest.getJSONArray("files");
      if (files.length() > MAX_FILES) throw invalid("整合包下载文件超过 2048 个");
      Set<String> projects = new HashSet<>();
      for (int i = 0; i < files.length(); i++) {
        JSONObject file = files.getJSONObject(i);
        String project = Long.toString(integer(file, "projectID", 1, Integer.MAX_VALUE));
        String exact = Long.toString(integer(file, "fileID", 1, Integer.MAX_VALUE));
        if (!(file.get("required") instanceof Boolean)) throw invalid("CurseForge required 必须是布尔值");
        if (!projects.add(project)) throw invalid("CurseForge 清单重复引用同一个项目");
        curseFiles.add(new CurseFile(project, exact));
      }
      String prefix = path(string(manifest, "overrides", 1024), false);
      overrides.addAll(overrides(entries, prefix));
    }
    targets(downloads, overrides); // All known paths and file-vs-directory conflicts, before download.
    return new Plan(source, archiveHash, name, minecraft, loader, loaderVersion, downloads, curseFiles, overrides);
  }

  private static List<Override> overrides(Map<String, Entry> entries, String prefix) throws IOException {
    List<Override> result = new ArrayList<>();
    Entry root = entries.get(prefix);
    if (root != null && !root.directory) throw invalid("整合包 overrides 指向了普通文件");
    for (Entry entry : entries.values()) {
      if (!entry.entry.startsWith(prefix + "/")) continue;
      String target = entry.entry.substring(prefix.length() + 1);
      if (target.isEmpty()) continue;
      result.add(new Override(entry, path(target, entry.directory)));
    }
    return result;
  }

  private static Map<String, Target> targets(List<Download> downloads, List<Override> overrides) throws IOException {
    Map<String, Target> result = new LinkedHashMap<>();
    for (Download item : downloads) register(result, item.path, false, false);
    for (Override item : overrides) register(result, item.path, item.directory, true);
    return result;
  }

  private static void register(Map<String, Target> targets, String name, boolean directory, boolean overwrite) throws IOException {
    String key = name.toLowerCase(Locale.ROOT);
    Target previous = targets.get(key);
    if (previous != null && (!previous.path.equals(name) || previous.directory != directory
        || (!directory && !overwrite))) throw invalid("整合包文件路径互相冲突：" + name);
    int slash = name.lastIndexOf('/');
    if (slash > 0) register(targets, name.substring(0, slash), true, true);
    targets.put(key, new Target(name, directory));
  }

  private static String path(String value, boolean directory) throws IOException {
    if (value == null || value.isEmpty() || value.length() > 1024 || value.startsWith("/")
        || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.matches("(?s).*[\\x00-\\x1f\\x7f].*"))
      throw invalid("整合包包含不安全路径");
    String result = directory && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    String[] parts = result.split("/", -1);
    if (parts.length > 64) throw invalid("整合包目录层级过深");
    for (String part : parts) {
      if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 255
          || part.endsWith(".") || part.endsWith(" ") || part.matches(".*[<>\"|?*].*")
          || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?"))
        throw invalid("整合包包含不安全路径：" + value);
    }
    return result;
  }

  private static File child(File root, String relative) throws IOException {
    File canonicalRoot = root.getCanonicalFile();
    File candidate = new File(canonicalRoot, path(relative, false));
    File canonical = candidate.getCanonicalFile();
    if (!canonical.getPath().startsWith(canonicalRoot.getPath() + File.separator)
        || !candidate.getAbsoluteFile().equals(canonical)) throw invalid("整合包路径越界或包含符号链接");
    return canonical;
  }

  private static void requireEmpty(File directory, File excluded, int[] count) throws IOException {
    File[] files = directory.listFiles();
    if (files == null) throw invalid("无法检查新实例目录");
    for (File file : files) {
      if (++count[0] > MAX_ENTRIES) throw invalid("实例目录并非新的空目录");
      if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw invalid("新实例目录包含符号链接");
      if (excluded != null && file.equals(excluded)) continue;
      if (!file.isDirectory()) throw invalid("整合包只能导入新的空实例，禁止覆盖已有实例文件");
      requireEmpty(file, excluded, count);
    }
  }

  private static void mkdir(File directory) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) throw invalid("无法创建整合包目录");
  }

  private static void deleteStage(File stage, File file) throws IOException {
    if (!file.exists()) return;
    File canonical = file.getCanonicalFile();
    if (!file.getAbsoluteFile().equals(canonical)
        || (!canonical.equals(stage.getCanonicalFile()) && !canonical.getPath().startsWith(stage.getCanonicalPath() + File.separator)))
      throw invalid("拒绝清理临时目录外的文件");
    if (file.isDirectory()) {
      File[] files = file.listFiles();
      if (files == null) throw invalid("无法检查整合包临时文件");
      for (File child : files) deleteStage(stage, child);
    }
    if (!file.delete()) throw invalid("无法清理整合包临时文件");
  }

  private static URI validUri(String value) throws Exception {
    if (value.length() > 8192) throw invalid("下载地址过长");
    URI uri = new URI(value);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getPort() != -1
        || uri.getFragment() != null) throw invalid("整合包仅允许无凭据的 HTTPS 下载地址");
    return uri;
  }

  private static void checkedUrl(String url, String[] hosts) throws Exception {
    String host = validUri(url).getHost();
    for (String allowed : hosts) if (allowed.equalsIgnoreCase(host)) return;
    throw invalid("整合包下载地址不在官方 CDN 白名单");
  }

  private static String string(JSONObject json, String key, int max) throws Exception {
    Object value = json.get(key);
    if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > max)
      throw invalid("整合包字段无效：" + key);
    return (String) value;
  }

  // A byte limit alone does not stop a tiny deeply-nested JSON from exhausting the parser stack.
  private static void checkJsonDepth(String json) throws IOException {
    int depth = 0;
    boolean quoted = false, escaped = false;
    for (int i = 0; i < json.length(); i++) {
      char value = json.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (value == '\\') escaped = true;
        else if (value == '"') quoted = false;
      } else if (value == '"') quoted = true;
      else if (value == '{' || value == '[') {
        if (++depth > 64) throw invalid("整合包清单嵌套层级超过 64");
      } else if (value == '}' || value == ']') {
        if (--depth < 0) throw invalid("整合包清单结构无效");
      }
    }
    if (quoted || depth != 0) throw invalid("整合包清单结构不完整");
  }

  private static long integer(JSONObject json, String key, long min, long max) throws Exception {
    Object value = json.get(key);
    if (!(value instanceof Number)) throw invalid("整合包整数字段无效：" + key);
    Number number = (Number) value;
    long result = number.longValue();
    if (number.doubleValue() != (double) result || result < min || result > max)
      throw invalid("整合包整数字段超出范围：" + key);
    return result;
  }

  private static String version(String value) throws IOException {
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}") || value.contains(".."))
      throw invalid("整合包 Minecraft/加载器版本无效");
    return value;
  }

  private static String environment(JSONObject env, String side) throws Exception {
    String value = string(env, side, 20);
    if (!value.matches("required|optional|unsupported")) throw invalid("Modrinth 环境字段无效");
    return value;
  }

  private static String hash(String value, int length) throws IOException {
    if (!value.matches("(?i)[0-9a-f]{" + length + "}")) throw invalid("整合包缺少有效文件哈希");
    return value.toLowerCase(Locale.ROOT);
  }

  private static String digest(File file, String algorithm, long limit) throws Exception {
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    long total = 0;
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[32768];
      for (int n; (n = input.read(buffer)) != -1;) {
        total = boundedAdd(total, n, limit, "文件实际大小超过限额");
        digest.update(buffer, 0, n);
      }
    }
    return hex(digest.digest());
  }

  private static byte[] read(InputStream input, int limit) throws IOException {
    try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      for (int n; (n = stream.read(buffer)) != -1;) {
        if (out.size() > limit - n) throw invalid("整合包清单超过大小限制");
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static long boundedAdd(long total, long increment, long limit, String message) throws IOException {
    if (increment < 0 || total > limit - increment) throw invalid(message);
    return total + increment;
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  private static IOException invalid(String message) { return new IOException(message); }

  private static final class Entry {
    final String entry, path; final boolean directory; final long size; String sha256;
    Entry(String entry, String path, boolean directory, long size) {
      this.entry = entry; this.path = path; this.directory = directory; this.size = size;
    }
  }
  private static final class Override {
    final String entry, path, sha256; final boolean directory; final long size;
    Override(Entry entry, String path) {
      this.entry = entry.entry; this.path = path; this.sha256 = entry.sha256;
      this.directory = entry.directory; this.size = entry.size;
    }
  }
  private static final class Download {
    final String path, sha1, sha512; final List<String> urls; final long size; final String[] hosts;
    Download(String path, List<String> urls, String sha1, String sha512, long size, String[] hosts) {
      this.path = path; this.urls = new ArrayList<>(urls); this.sha1 = sha1;
      this.sha512 = sha512; this.size = size; this.hosts = hosts.clone();
    }
  }
  private static final class CurseFile {
    final String project, file;
    CurseFile(String project, String file) { this.project = project; this.file = file; }
  }
  private static final class Target {
    final String path; final boolean directory;
    Target(String path, boolean directory) { this.path = path; this.directory = directory; }
  }
}
