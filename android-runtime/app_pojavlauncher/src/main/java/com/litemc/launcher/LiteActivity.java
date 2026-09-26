package com.litemc.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.kdt.mcgui.ProgressLayout;
import java.io.*;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import org.json.JSONObject;
import org.json.JSONArray;

/** Independent Lite-MC product UI. Untrusted pages never receive the native bridge. */
public final class LiteActivity extends Activity {
  private static final String ORIGIN = "https://appassets.androidplatform.net";
  private WebView web;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LiteAccounts accounts;
  private LiteMods mods;
  private LiteVersions versions;
  private SharedPreferences preferences;
  private volatile boolean ready, destroyed;
  private String pickerRequest;
  private String pickerInstance, pickerKind;
  private File pickerExport;
  private String loginUri;
  private long lastProgress;
  private final ProgressListener progress =
      new ProgressListener() {
        public void onProgressStarted() {
          event("progress", object("message", "Preparing game files", "percent", 0));
        }

        public void onProgressUpdated(int percent, int resource, Object... values) {
          if (System.currentTimeMillis() - lastProgress < 180) return;
          lastProgress = System.currentTimeMillis();
          String message = "Downloading game files";
          try {
            if (resource > 0) message = getString(resource, values);
          } catch (Exception ignored) {
          }
          event("progress", object("message", message, "percent", percent));
        }

        public void onProgressEnded() {
          event("progress", object("message", "Ready", "percent", 100));
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(11, 20, 33));
    getWindow().setNavigationBarColor(Color.rgb(11, 20, 33));
    if (android.os.Build.VERSION.SDK_INT < 23 || !Tools.checkStorageRoot(this)) {
      android.widget.TextView error = new android.widget.TextView(this);
      error.setText("Lite-MC requires Android 6+ and accessible app storage.");
      setContentView(error);
      return;
    }
    LauncherPreferences.loadPreferences(this);
    preferences = getSharedPreferences("lite", MODE_PRIVATE);
    accounts = new LiteAccounts(this);
    mods = new LiteMods(this);
    versions = new LiteVersions(this);
    AsyncAssetManager.unpackComponents(this);
    AsyncAssetManager.unpackSingleFiles(this);
    ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, progress);
    web = new WebView(this);
    web.setBackgroundColor(Color.rgb(11, 20, 33));
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSupportMultipleWindows(false);
    settings.setDomStorageEnabled(false);
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return true;
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            // Only decoded raster images from resource providers may cross the local UI boundary.
            if ("https".equals(uri.getScheme()) && uri.getPort() == -1 && uri.getUserInfo() == null
                && ("cdn.modrinth.com".equals(uri.getHost()) || "media.forgecdn.net".equals(uri.getHost())
                    || "mediafilez.forgecdn.net".equals(uri.getHost()))
                && "GET".equals(request.getMethod())) {
              try {
                byte[] bytes = LiteNetwork.image(uri.toString());
                android.graphics.BitmapFactory.Options image = new android.graphics.BitmapFactory.Options();
                image.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, image);
                if (image.outWidth > 0 && image.outHeight > 0 && image.outWidth <= 4096 && image.outHeight <= 4096
                    && ("image/png".equals(image.outMimeType) || "image/jpeg".equals(image.outMimeType)
                        || "image/webp".equals(image.outMimeType)))
                  return new WebResourceResponse(image.outMimeType, null, new ByteArrayInputStream(bytes));
              } catch (Exception ignored) { }
            }
            if ("https".equals(uri.getScheme())
                && "appassets.androidplatform.net".equals(uri.getHost())
                && uri.getPort() == -1) {
              String path = uri.getPath();
              if (path != null && path.matches("/litemc/[a-zA-Z0-9_-]+\\.(html|js|css|svg)")) {
                try {
                  String type =
                      path.endsWith(".html")
                          ? "text/html"
                          : path.endsWith(".js")
                              ? "application/javascript"
                              : path.endsWith(".css") ? "text/css" : "image/svg+xml";
                  return new WebResourceResponse(
                      type,
                      "UTF-8",
                      200,
                      "OK",
                      Collections.singletonMap("X-Content-Type-Options", "nosniff"),
                      getAssets().open(path.substring(1)));
                } catch (IOException ignored) {
                }
              }
            }
            return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
          }
        });
    web.addJavascriptInterface(new Bridge(), "LiteNative");
    setContentView(web);
    web.loadUrl(ORIGIN + "/litemc/index.html");
    ProgressKeeper.waitUntilDone(
        () ->
            submit(
                () -> {
                  try {
                    AsyncAssetManager.assertReady();
                    try (InputStream input = getAssets().open("litemc/controls.json")) {
                      // MainActivity resolves profile.controlFile relative to .minecraft/controlmap.
                      File template = new File(Tools.CTRLMAP_PATH, "lite-mc-mobile.json");
                      if (!template.isFile()) LiteVersions.write(template, readBounded(input, 256000));
                    }
                    MinecraftAccount local = new MinecraftAccount();
                    local.username = "Player";
                    if (LiteRuntimeAccount.read(this) == null)
                      LiteRuntimeAccount.write(this, local);
                    PojavProfile.setCurrentProfile(this, LiteRuntimeAccount.PROFILE);
                    ready = true;
                    event("ready", object("ready", true));
                  } catch (Exception ex) {
                    event("error", object("message", "Runtime initialization failed"));
                  }
                }));
  }

  @Override
  protected void onResume() {
    super.onResume();
    ContextExecutor.setActivity(this);
    if (web != null) web.onResume();
  }

  @Override
  protected void onPause() {
    if (web != null) web.onPause();
    ContextExecutor.clearActivity();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, progress);
    worker.shutdown();
    if (web != null) {
      web.removeJavascriptInterface("LiteNative");
      web.destroy();
    }
    super.onDestroy();
  }

  private void submit(Runnable action) {
    if (destroyed) return;
    try {
      worker.execute(() -> { if (!destroyed) action.run(); });
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
      // A finishing Activity may receive one last initialization or picker callback.
    }
  }

  @Override
  public void onBackPressed() {
    if (web != null) web.evaluateJavascript("window.LiteBack&&window.LiteBack()", null);
    else super.onBackPressed();
  }

  private final class Bridge {
    @JavascriptInterface
    public void request(String requestId, String action, String json) {
      if (destroyed
          || requestId == null
          || !requestId.matches("[0-9]{1,12}")
          || action == null
          || json == null
          || json.length() > 1500000) return;
      submit(
          () -> {
            try {
              JSONObject args = new JSONObject(json);
              if (action.equals("packs.download")) {
                if (pickerRequest != null) throw new IllegalStateException("请先完成当前文件选择。");
                event("progress", object("message", "正在从官方源下载整合包…", "percent", -1));
                File archive = mods.downloadPack(args);
                pickerRequest = requestId;
                pickerExport = archive;
                runOnUiThread(() -> {
                  try {
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, archive.getName()), 403);
                  } catch (Exception ex) {
                    pickerRequest = null; pickerExport = null;
                    reply(requestId, null, "无法打开保存窗口。下载缓存已保留，可以重试。");
                  }
                });
                return;
              }
              if (action.equals("files.import")) {
                if (pickerRequest != null) throw new IllegalStateException("请先完成当前文件选择。");
                JSONObject entry = versions.find(args.getString("instanceId"));
                String kind = args.getString("kind");
                if (!kind.equals("mod") && !kind.equals("schematic")) throw new IOException("文件类型无效。");
                if (kind.equals("mod") && "vanilla".equals(entry.getString("loader")))
                  throw new IOException("当前实例是原版，请先选择安装了加载器的实例。");
                pickerRequest = requestId;
                pickerInstance = entry.getString("id");
                pickerKind = kind;
                runOnUiThread(() -> {
                  try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addCategory(Intent.CATEGORY_OPENABLE), 402);
                  } catch (Exception ex) { pickerRequest = null; reply(requestId, null, "无法打开系统文件选择器。"); }
                });
                return;
              }
              if (action.equals("skin.pick")) {
                if (pickerRequest != null)
                  throw new IllegalStateException("A file picker is already open");
                pickerRequest = requestId;
                runOnUiThread(
                    () -> {
                      Intent intent =
                          new Intent(Intent.ACTION_OPEN_DOCUMENT)
                              .setType("image/png")
                              .addCategory(Intent.CATEGORY_OPENABLE);
                      startActivityForResult(intent, 401);
                    });
                return;
              }
              reply(requestId, dispatch(action, args), null);
            } catch (Exception error) {
              // Do not serialize stack traces, auth responses or URLs with query parameters.
              String message = error.getMessage();
              if (message == null
                  || message.length() > 250
                  || message.contains("token")
                  || message.contains("Bearer"))
                message = "Operation failed (" + error.getClass().getSimpleName() + ")";
              reply(requestId, null, message);
            }
          });
    }
  }

  private JSONObject dispatch(String action, JSONObject args) throws Exception {
    if (action.equals("state"))
      return object(
          "ready",
          ready,
          "account",
          accounts.snapshot(),
          "instances",
          versions.installed(),
          "loaders",
          LiteVersions.loaders(),
          "selected",
          preferences.getString("selected", ""),
          "language",
          preferences.getString("language", "zh"),
          "memory",
          LauncherPreferences.PREF_RAM_ALLOCATION,
          "motion",
          preferences.getBoolean("motion", true),
          "model",
          preferences.getString("model", "classic"),
          "performanceMode",
          preferences.getString("performanceMode", LauncherPreferences.PREF_SUSTAINED_PERFORMANCE ? "performance" : "balanced"),
          "android26Supported",
          isTabletRuntime(),
          "controlScale",
          LauncherPreferences.DEFAULT_PREF.getInt("buttonscale", 100));
    if (action.equals("controls.read")) return readControls();
    if (action.equals("controls.save")) {
      JSONObject controls = LiteControls.validate(args);
      LiteVersions.write(new File(getFilesDir(), "lite-global-controls.json"), controls.toString().getBytes("UTF-8"));
      LauncherPreferences.DEFAULT_PREF.edit().putInt("buttonscale", controls.getInt("scale")).apply();
      LauncherPreferences.loadPreferences(this);
      return controls;
    }
    if (action.equals("projection.status")) {
      JSONObject entry = versions.find(args.getString("instanceId"));
      File instance = LiteVersions.instance(entry.getString("id"));
      return LiteLocalFiles.projection(instance).put("shortcut", LiteControls.projectionHotkey(instance).getString("label"));
    }
    if (action.equals("skin.read")) {
      JSONObject account = accounts.snapshot();
      File local = new File(getFilesDir(), "lite-skin.png");
      if ("online".equals(account.optString("mode"))
          && !account.isNull("skinUrl")
          && !account.optString("skinUrl").isEmpty()) {
        String address = account.getString("skinUrl");
        if (address.startsWith("http://textures.minecraft.net/"))
          address = "https" + address.substring(4);
        File cached = new File(getFilesDir(), "lite-online-skin.png");
        byte[] bytes;
        if (cached.isFile() && address.equals(preferences.getString("skinUrl", ""))) {
          try (InputStream input = new FileInputStream(cached)) {
            bytes = readBounded(input, 1048576);
          }
        } else {
          bytes =
              LiteNetwork.request(
                  "GET", address, new String[] {"textures.minecraft.net"}, null, null);
          if (bytes.length > 1048576) throw new IOException("Skin is too large");
          LiteVersions.write(cached, bytes);
          preferences.edit().putString("skinUrl", address).apply();
        }
        if (bytes.length > 1048576) throw new IOException("Skin is too large");
        return object(
            "base64",
            Base64.encodeToString(bytes, Base64.NO_WRAP),
            "model",
            account.optString("model", "CLASSIC"));
      }
      if (!local.isFile()) return new JSONObject();
      try (InputStream input = new FileInputStream(local)) {
        return object("base64", Base64.encodeToString(readBounded(input, 1048576), Base64.NO_WRAP));
      }
    }
    if (action.startsWith("accounts.")) {
      JSONObject result = accounts.handle(action, args);
      if (action.equals("accounts.login.start")) loginUri = result.optString("verificationUri");
      if (action.equals("accounts.logout")) LiteRuntimeAccount.clear(this);
      return result;
    }
    if (action.startsWith("mods.")) {
      if (action.equals("mods.install") || action.equals("mods.list")
          || (action.equals("mods.search") && !"modpack".equals(args.optString("kind")))) {
        JSONObject instance = versions.find(args.getString("instanceId"));
        args.put("version", instance.getString("version"))
            .put("loader", instance.getString("loader"));
      }
      return mods.handle(action, args);
    }
    if (action.equals("catalog")) return versions.catalog(args.optBoolean("refresh"));
    if (action.equals("forge.versions")) return object("items", LiteForge.versions(args.getString("version")));
    if (action.equals("loaders")) return object("items", LiteVersions.loaders());
    if (action.equals("settings")) {
      String language = args.optString("language", "zh");
      if (!language.equals("zh") && !language.equals("en"))
        throw new IllegalArgumentException("Unsupported language");
      int memory = args.optInt("memory", 2048);
      if (memory < 512 || memory > 8192)
        throw new IllegalArgumentException("Memory must be between 512 and 8192 MB");
      preferences
          .edit()
          .putString("language", language)
          .putBoolean("motion", args.optBoolean("motion", true))
          .putString("model", args.optString("model", "classic"))
          .putString("performanceMode", normalizePerformanceMode(args.optString("performanceMode", "balanced")))
          .apply();
      String performanceMode = normalizePerformanceMode(args.optString("performanceMode", "balanced"));
      LauncherPreferences.DEFAULT_PREF
          .edit()
          .putInt("allocation", memory)
          .putInt("buttonscale", Math.max(60, Math.min(150, args.optInt("controlScale", 100))))
          .putBoolean("sustainedPerformance", "performance".equals(performanceMode))
          .apply();
      LauncherPreferences.loadPreferences(this);
      return object("saved", true);
    }
    if (action.equals("browser.login")) {
      if (loginUri == null) throw new IllegalStateException("Start Microsoft sign-in first");
      Uri uri = Uri.parse(loginUri);
      String host = uri.getHost();
      if (!"https".equals(uri.getScheme())
          || uri.getPort() != -1
          || uri.getUserInfo() != null
          || !("microsoft.com".equals(host)
              || "www.microsoft.com".equals(host)
              || "login.microsoftonline.com".equals(host)
              || "login.live.com".equals(host)))
        throw new IllegalStateException("Invalid Microsoft verification address");
      runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, uri)));
      return object("opened", true);
    }
    if (!ready) throw new IllegalStateException("Runtime is still preparing, please wait");
    if (ProgressKeeper.hasOngoingTasks()) throw new IllegalStateException("A game preparation task is still running");
    if (action.equals("packs.install")) {
      event("progress", object("message", "正在下载并检查整合包清单…", "percent", -1));
      LitePacks.Plan plan = LitePacks.inspect(mods.downloadPack(args));
      if (!"fabric".equals(plan.loader) && !"vanilla".equals(plan.loader) && !"forge".equals(plan.loader))
        throw new IOException("此整合包需要 " + plan.loader + " " + plan.loaderVersion
            + "。当前 APK 暂不能自动安装该加载器，请使用“仅下载整合包文件”。");
      String name = args.optString("name", "").trim();
      if (name.isEmpty()) {
        name = plan.name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
        if (name.length() > 32) name = name.substring(0, 32);
      }
      MinecraftAccount install = new MinecraftAccount(); install.username = "Player";
      LiteRuntimeAccount.write(this, install);
      PojavProfile.setCurrentProfile(this, LiteRuntimeAccount.PROFILE);
      JSONObject installed = versions.install(this, plan.minecraft, plan.loader, name,
          plan.loaderVersion, directory -> {
            event("progress", object("message", "正在下载整合包的 Mod 与配置，请保持应用在前台…", "percent", -1));
            plan.prepare(directory, mods);
          });
      return object("instance", installed);
    }
    if (action.equals("install")) {
      // Validate capability before touching the account/profile used by the runtime.
      LiteVersions.requireAutomaticLoader(args.getString("loader"));
      // Installation needs no account refresh and never blocks on Microsoft authentication.
      MinecraftAccount install = new MinecraftAccount();
      install.username = "Player";
      LiteRuntimeAccount.write(this, install);
      PojavProfile.setCurrentProfile(this, LiteRuntimeAccount.PROFILE);
      return versions.install(this, args.getString("version"), args.getString("loader"), args.optString("name", ""),
          args.optString("loaderVersion", ""), null);
    }
    if (action.equals("select")) {
      JSONObject entry = versions.find(args.getString("instanceId"));
      versions.select(entry);
      return entry;
    }
    if (action.equals("launch")) {
      AsyncAssetManager.assertReady();
      JSONObject entry = versions.find(args.getString("instanceId"));
      versions.select(entry);
      prepareControls(entry);
      File expectedDirectory = LiteVersions.instance(entry.getString("id"));
      File actualDirectory = Tools.getGameDirPath(
          net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.getCurrentProfile()).getCanonicalFile();
      if (!expectedDirectory.equals(actualDirectory))
        throw new IOException("游戏目录与整合包目录不一致，已阻止启动空实例。请重新选择版本。");
      String version = LiteVersions.id(entry.getString("launchVersion"));
      File client = new File(Tools.DIR_HOME_VERSION, version + "/" + version + ".jar");
      if (client.length() == 0)
        throw new IOException("Client is missing. Reinstall to repair this version.");
      String runtimeName = Tools.getSelectedRuntime(net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.getCurrentProfile());
      if (runtimeName == null || !new File(Tools.MULTIRT_HOME, runtimeName + "/release").isFile())
        throw new IOException("Java runtime is missing. Reinstall this version to repair it.");
      MinecraftAccount account = accounts.launchAccount();
      LiteRuntimeAccount.write(this, account);
      PojavProfile.setCurrentProfile(this, LiteRuntimeAccount.PROFILE);
      runOnUiThread(
          () ->
              startActivity(
                  new Intent(this, MainActivity.class)
                      .putExtra(MainActivity.INTENT_MINECRAFT_VERSION, version)
                      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)));
      return object("started", true);
    }
    throw new IllegalArgumentException("Unsupported action");
  }

  private static String normalizePerformanceMode(String value) {
    if ("performance".equals(value) || "powersave".equals(value)) return value;
    return "balanced";
  }

  private JSONObject readControls() throws Exception {
    File saved = new File(getFilesDir(), "lite-global-controls.json");
    JSONObject result = new JSONObject();
    if (saved.isFile()) try (InputStream in = new FileInputStream(saved)) {
      result = new JSONObject(LiteLocalFiles.read(in, 65536));
    }
    result.put("scale", LauncherPreferences.DEFAULT_PREF.getInt("buttonscale", 100));
    return LiteControls.validate(result);
  }

  private void prepareControls(JSONObject entry) throws Exception {
    JSONObject template;
    try (InputStream in = new FileInputStream(new File(Tools.CTRLMAP_PATH, "lite-mc-mobile.json"))) {
      template = new JSONObject(LiteLocalFiles.read(in, 256000));
    }
    String id = LiteVersions.id(entry.getString("id"));
    JSONObject layout = LiteControls.layout(template, readControls(), LiteVersions.instance(id));
    String name = "lite-generated-" + id + ".json";
    LiteVersions.write(new File(Tools.CTRLMAP_PATH, name), layout.toString().getBytes("UTF-8"));
    net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.getCurrentProfile().controlFile = name;
    net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.write();
  }

  private boolean isTabletRuntime() {
    return getResources().getConfiguration().smallestScreenWidthDp >= 600;
  }

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (code == 403 && pickerRequest != null) {
      final String request = pickerRequest;
      final File archive = pickerExport;
      pickerRequest = null; pickerExport = null;
      submit(() -> {
        if (result != RESULT_OK || data == null || data.getData() == null) {
          reply(request, object("cancelled", true), null); return;
        }
        Uri uri = data.getData();
        try {
          if (archive == null || !"content".equals(uri.getScheme())) throw new IOException("保存位置无效。");
          try (InputStream in = new FileInputStream(archive);
              OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("无法写入所选位置。");
            byte[] buffer = new byte[65536];
            for (int count; (count = in.read(buffer)) != -1;) out.write(buffer, 0, count);
          }
          reply(request, object("saved", true, "name", archive.getName()), null);
        } catch (Exception ex) { reply(request, null, "整合包保存失败，缓存已保留。请检查存储空间后重试。"); }
      });
      return;
    }
    if (code == 402 && pickerRequest != null) {
      final String request = pickerRequest, instanceId = pickerInstance, kind = pickerKind;
      pickerRequest = null; pickerInstance = null; pickerKind = null;
      submit(() -> {
        JSONArray installed = new JSONArray(), failed = new JSONArray();
        try {
          if (result != RESULT_OK || data == null) { reply(request, object("cancelled", true), null); return; }
          java.util.LinkedHashSet<Uri> uris = new java.util.LinkedHashSet<>();
          if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
          else if (data.getData() != null) uris.add(data.getData());
          if (uris.isEmpty() || uris.size() > 20) throw new IOException("每次请选择 1–20 个文件。");
          File instance = LiteVersions.instance(versions.find(instanceId).getString("id"));
          for (Uri uri : uris) {
            String name = "所选文件";
            try {
              if (!"content".equals(uri.getScheme())) throw new IOException("请选择系统文档中的文件。");
              try (android.database.Cursor cursor = getContentResolver().query(uri,
                  new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("无法读取文件名。");
                name = cursor.getString(0);
              }
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法读取文件。");
                installed.put(LiteLocalFiles.importFile(instance, in, name, kind).getName());
              }
            } catch (Exception ex) { failed.put(object("name", name, "error", ex.getMessage() == null ? "导入失败" : ex.getMessage())); }
          }
          reply(request, object("installed", installed, "failed", failed), null);
        } catch (Exception ex) { reply(request, null, ex.getMessage()); }
      });
      return;
    }
    if (code != 401 || pickerRequest == null) return;
    String request = pickerRequest;
    pickerRequest = null;
    submit(
        () -> {
          if (result != RESULT_OK || data == null || data.getData() == null) {
            reply(request, object("cancelled", true), null);
            return;
          }
          try (InputStream input = getContentResolver().openInputStream(data.getData())) {
            byte[] bytes = readBounded(input, 1048576);
            android.graphics.BitmapFactory.Options options =
                new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth != 64
                || (options.outHeight != 64 && options.outHeight != 32)
                || !"image/png".equals(options.outMimeType))
              throw new IOException("Use a 64x64 or 64x32 PNG skin");
            LiteVersions.write(new File(getFilesDir(), "lite-skin.png"), bytes);
            reply(request, object("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)), null);
          } catch (Exception error) {
            reply(request, null, "Use a valid 64x64 or 64x32 PNG skin (max 1 MB)");
          }
        });
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    if (input == null) throw new IOException("File unavailable");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (output.size() + count > limit) throw new IOException("File too large");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private void reply(String id, JSONObject data, String error) {
    event("reply", object("id", id, "data", data, "error", error));
  }

  private void event(String name, JSONObject data) {
    if (destroyed) return;
    runOnUiThread(
        () -> {
          if (!destroyed && web != null)
            web.evaluateJavascript(
                "window.LiteEvent&&window.LiteEvent(" + JSONObject.quote(name) + "," + data + ")",
                null);
        });
  }

  private static JSONObject object(Object... entries) {
    JSONObject object = new JSONObject();
    try {
      for (int i = 0; i < entries.length; i += 2)
        object.put((String) entries[i], entries[i + 1] == null ? JSONObject.NULL : entries[i + 1]);
    } catch (Exception ignored) {
    }
    return object;
  }
}
