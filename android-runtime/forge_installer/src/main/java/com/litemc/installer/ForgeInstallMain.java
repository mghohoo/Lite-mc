package com.litemc.installer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.json.JSONObject;

/** Lite-MC's headless bridge to the genuine Forge Installer 2.0 client action.
 * Runs inside a separate Java VM, not Android ART. Never invoke with -javaagent.
 * Arguments: installer.jar targetRoot result.json nonce minecraft installerSha1
 * The caller must verify the official SHA-1 before dispatch; we verify it again here.
 * Forge's run() owns library downloads and ALL processors. A copied JSON is not success.
 * API: https://github.com/MinecraftForge/Installer/tree/2.0/src/main/java/net/minecraftforge/installer
 */
public final class ForgeInstallMain {
  private static final long MAX_INSTALLER = 128L * 1024 * 1024;
  private static final int MAX_JSON = 4 * 1024 * 1024;

  private ForgeInstallMain() {}

  public static void main(String[] args) {
    if (args.length != 6) {
      System.err.println("Usage: ForgeInstallMain installer.jar targetRoot result.json nonce minecraft installerSha1");
      System.exit(64);
      return;
    }
    Result result;
    try {
      result = new Result(absolute(args[2]), args[3], args[4]);
    } catch (Throwable error) {
      System.err.println("Invalid Forge result destination: " + error);
      System.exit(64);
      return;
    }
    Thread shutdown = new Thread(() -> {
      try {
        result.publish(false, "Forge 安装器在返回完整处理结果前退出；本次任务不视为安装成功。");
      } catch (Throwable error) {
        System.err.println("Unable to publish Forge shutdown failure: " + error);
      }
    }, "LiteForgeResult");
    Runtime.getRuntime().addShutdownHook(shutdown);
    boolean success = false;
    String failure = "";
    try {
      run(absolute(args[0]), absolute(args[1]), args[4], args[5], result);
      success = true;
    } catch (Throwable error) {
      Throwable cause = unwrap(error);
      cause.printStackTrace(System.err);
      failure = clean(cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
    }
    try {
      result.publish(success, failure);
    } catch (Throwable error) {
      error.printStackTrace(System.err);
      success = false;
    }
    // Publication happens BEFORE VM/process teardown. Native callers require matching nonce.
    System.exit(success ? 0 : 1);
  }

  private static void run(File installer, File target, String minecraft, String expectedSha1,
      Result result) throws Exception {
    if (!minecraft.matches("1\\.[0-9]{1,2}(?:\\.[0-9]{1,2})?")
        || Integer.parseInt(minecraft.split("\\.")[1]) < 13)
      throw new IOException("仅支持 Minecraft 1.13 及以后的 1.x 与已验证的 Forge Installer 2.0 API；1.12 旧安装器不受支持。");
    if (!expectedSha1.matches("(?i)[0-9a-f]{40}") || !installer.isFile()
        || installer.length() == 0 || installer.length() > MAX_INSTALLER
        || !expectedSha1.equalsIgnoreCase(digest(installer, "SHA-1", MAX_INSTALLER)))
      throw new IOException("Forge 官方安装器 SHA-1 校验失败，未加载任何安装器代码。");
    if (!target.isDirectory()) throw new IOException("Forge 安装目标目录尚未创建。");
    if (target.equals(result.file) || installer.equals(result.file))
      throw new IOException("安装器、安装目录和结果文件不能使用相同路径。");

    final JSONObject installProfile;
    final String expectedVersion, expectedVersionDigest;
    try (JarFile jar = new JarFile(installer)) {
      installProfile = json(jar, "install_profile.json");
      int spec = installProfile.optInt("spec", -1);
      if ((spec != 0 && spec != 1) || installProfile.optJSONArray("processors") == null
          || installProfile.optJSONArray("libraries") == null)
        throw new IOException("此 Forge 安装器不使用已验证的现代处理器格式（spec 0/1）。");
      if (!minecraft.equals(installProfile.optString("minecraft")))
        throw new IOException("Forge 安装器与请求的 Minecraft 版本不一致。");
      expectedVersion = safeId(installProfile.getString("version"));
      String resource = installProfile.getString("json");
      if (resource.startsWith("/")) resource = resource.substring(1);
      if (!resource.matches("[A-Za-z0-9._/-]+") || resource.contains("..")
          || resource.startsWith("/") || !resource.endsWith(".json"))
        throw new IOException("Forge 版本清单路径无效。");
      byte[] versionBytes = read(jar, resource);
      JSONObject version = new JSONObject(new String(versionBytes, StandardCharsets.UTF_8));
      if (!expectedVersion.equals(version.optString("id"))
          || !minecraft.equals(version.optString("inheritsFrom"))
          || version.optJSONArray("libraries") == null
          || !version.optString("mainClass").matches("[A-Za-z_$][A-Za-z0-9_.$]+"))
        throw new IOException("Forge 安装器中的游戏版本配置不完整或不一致。");
      expectedVersionDigest = hex(MessageDigest.getInstance("SHA-256").digest(versionBytes));
    }
    result.versionId = expectedVersion;
    System.setProperty("java.awt.headless", "true");
    if (System.getProperty("java.net.preferIPv4Stack") == null)
      System.setProperty("java.net.preferIPv4Stack", "true");

    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader loader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, platformParent())) {
      Thread.currentThread().setContextClassLoader(loader);
      try {
        Class<?> simple = ownClass(loader, "net.minecraftforge.installer.SimpleInstaller");
        Class<?> util = ownClass(loader, "net.minecraftforge.installer.json.Util");
        Class<?> profileType = ownClass(loader, "net.minecraftforge.installer.json.InstallV1");
        Class<?> callbackType = ownClass(loader, "net.minecraftforge.installer.actions.ProgressCallback");
        Class<?> actionsType = ownClass(loader, "net.minecraftforge.installer.actions.Actions");
        Class<?> actionType = ownClass(loader, "net.minecraftforge.installer.actions.Action");
        simple.getField("headless").setBoolean(null, true);
        Method loadProfile = util.getMethod("loadInstallProfile");
        if (loadProfile.getReturnType() != profileType || !callbackType.isInterface())
          throw new NoSuchMethodException("Unexpected Installer 2.0 profile/callback signature");
        Method getAction = actionsType.getMethod("getAction", profileType, callbackType);
        Method execute = actionType.getMethod("run", File.class, File.class);
        if (getAction.getReturnType() != actionType || execute.getReturnType() != boolean.class)
          throw new NoSuchMethodException("Unexpected Installer 2.0 action signature");

        Object profile = loadProfile.invoke(null);
        if (!minecraft.equals(profileType.getMethod("getMinecraft").invoke(profile))
            || !expectedVersion.equals(profileType.getMethod("getVersion").invoke(profile)))
          throw new IOException("官方 API 读取的安装配置与已校验清单不一致。");
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[] {callbackType}, (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            if ("toString".equals(method.getName())) return "LiteForgeProgress";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
          }
          if (args != null && args.length > 0 && args[0] instanceof String) {
            result.lastMessage = clean((String) args[0]);
            System.out.println("[Lite-MC Forge] " + result.lastMessage);
          }
          // Default methods are intercepted as well; no AWT/Swing or external agent is used.
          if (method.getReturnType() != void.class)
            throw new UnsupportedOperationException("Unsupported Forge callback: " + method.getName());
          return null;
        });
        Object client = actionsType.getField("CLIENT").get(null);
        Object action = getAction.invoke(client, profile, callback);
        if (!actionType.isInstance(action)) throw new IOException("官方安装器未返回 CLIENT action。");
        System.out.println("[Lite-MC Forge] Entering official ClientInstall.run: " + expectedVersion);
        result.runEntered = true;
        Object returned = execute.invoke(action, target, installer);
        result.runReturned = true;
        if (!Boolean.TRUE.equals(returned))
          throw new IOException("Forge 官方 CLIENT 安装失败：" + result.lastMessage);
      } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException error) {
        throw new IOException("此 Forge 安装器没有已验证的 Installer 2.0 API，未使用 GUI 或旧版兼容猜测。", error);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
    File output = child(target, "versions/" + expectedVersion + "/" + expectedVersion + ".json");
    File vanilla = child(target, "versions/" + minecraft + "/" + minecraft + ".jar");
    if (!output.isFile() || !expectedVersionDigest.equals(digest(output, "SHA-256", MAX_JSON))
        || !vanilla.isFile() || vanilla.length() == 0 || !child(target, "libraries").isDirectory())
      throw new IOException("官方安装器返回成功，但安装输出缺失或版本 JSON 与官方归档不一致。");
  }

  /** Isolate Forge/Gson from the helper and game classpath, retaining Java platform modules. */
  private static ClassLoader platformParent() throws Exception {
    try {
      return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
    } catch (NoSuchMethodException java8) {
      return null;
    }
  }

  private static Class<?> ownClass(URLClassLoader loader, String name) throws ClassNotFoundException {
    Class<?> value = Class.forName(name, true, loader);
    if (value.getClassLoader() != loader) throw new ClassNotFoundException("Unexpected Forge class origin: " + name);
    return value;
  }

  private static File absolute(String path) throws IOException {
    File value = new File(path);
    if (!value.isAbsolute() || path.indexOf('\0') >= 0) throw new IOException("需要绝对文件路径。");
    File canonical = value.getCanonicalFile();
    if (!canonical.equals(value.getAbsoluteFile())) throw new IOException("文件路径不能经过符号链接或相对跳转。");
    return canonical;
  }

  private static File child(File root, String path) throws IOException {
    File raw = new File(root, path), value = raw.getCanonicalFile();
    if (!value.equals(raw.getAbsoluteFile()) || !value.getPath().startsWith(root.getPath() + File.separator))
      throw new IOException("Forge 安装输出路径不安全。");
    return value;
  }

  private static String safeId(String value) throws IOException {
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}") || value.contains(".."))
      throw new IOException("Forge 版本 ID 无效。");
    return value;
  }

  private static JSONObject json(JarFile jar, String name) throws Exception {
    return new JSONObject(new String(read(jar, name), StandardCharsets.UTF_8));
  }

  private static byte[] read(JarFile jar, String name) throws IOException {
    JarEntry entry = jar.getJarEntry(name);
    if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_JSON)
      throw new IOException("Forge 安装器缺少大小有效的 JSON：" + name);
    try (InputStream input = jar.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      for (int count; (count = input.read(buffer)) != -1;) {
        if (output.size() > MAX_JSON - count) throw new IOException("Forge JSON 超过大小限制。");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static String digest(File file, String algorithm, long limit) throws Exception {
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    long total = 0;
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[32768];
      for (int count; (count = input.read(buffer)) != -1;) {
        if (total > limit - count) throw new IOException("Forge 文件超过大小限制。");
        total += count; digest.update(buffer, 0, count);
      }
    }
    return hex(digest.digest());
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }

  private static Throwable unwrap(Throwable error) {
    while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
    return error;
  }

  private static String clean(String value) {
    if (value == null) return "";
    String safe = value.replaceAll("[\\x00-\\x1f\\x7f]", " ");
    return safe.length() > 800 ? safe.substring(0, 800) : safe;
  }

  private static final class Result {
    final File file;
    final String nonce, minecraft;
    volatile String versionId = "", lastMessage = "未提供详细错误，请查看安装日志。";
    volatile boolean runEntered, runReturned;
    private boolean published;

    Result(File file, String nonce, String minecraft) throws IOException {
      if (!nonce.matches("[A-Za-z0-9_-]{16,128}")) throw new IOException("无效的安装任务 nonce。");
      if (!file.getParentFile().isDirectory() || file.exists()) throw new IOException("结果文件必须不存在且父目录已创建。");
      this.file = file; this.nonce = nonce; this.minecraft = minecraft;
    }

    synchronized void publish(boolean success, String error) throws Exception {
      if (published) return;
      if (file.exists()) throw new IOException("拒绝覆盖已有 Forge 任务结果。");
      JSONObject json = new JSONObject().put("nonce", nonce).put("success", success)
          .put("error", success ? "" : clean(error)).put("minecraft", minecraft).put("versionId", versionId)
          .put("officialRunEntered", runEntered).put("officialRunReturned", runReturned);
      File temporary = new File(file.getParentFile(), file.getName() + "." + nonce + ".tmp");
      if (!temporary.createNewFile()) throw new IOException("Forge 结果临时文件已存在。");
      try {
        try (FileOutputStream output = new FileOutputStream(temporary)) {
          output.write(json.toString().getBytes(StandardCharsets.UTF_8));
          output.flush(); output.getFD().sync();
        }
        try {
          Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unavailable) {
          // Same-directory completed-file rename; the destination is never streamed partially.
          Files.move(temporary.toPath(), file.toPath());
        }
        published = true;
      } finally {
        if (temporary.exists() && !temporary.delete()) System.err.println("Unable to clean Forge result temp file.");
      }
    }
  }
}
