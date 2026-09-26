package com.litemc.launcher;

import android.os.Bundle;
import android.widget.TextView;
import net.kdt.pojavlaunch.BaseActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;

/** Isolated process UI which runs ForgeInstallMain and leaves a nonce-bound result for LiteForge. */
public final class LiteForgeInstallerActivity extends BaseActivity {
  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    TextView status = new TextView(this);
    status.setPadding(48, 48, 48, 48);
    status.setText("正在安装 Forge，请不要关闭此页面…");
    setContentView(status);
    new Thread(() -> runInstaller(), "LiteForgeInstaller").start();
  }

  private void runInstaller() {
    String resultPath = getIntent().getStringExtra(LiteForge.EXTRA_RESULT);
    String nonce = getIntent().getStringExtra(LiteForge.EXTRA_NONCE);
    String minecraft = getIntent().getStringExtra(LiteForge.EXTRA_MC);
    try {
      LiteForge.Spec spec = LiteForge.resolve(minecraft, getIntent().getStringExtra(LiteForge.EXTRA_LOADER));
      File installer = new File(getIntent().getStringExtra(LiteForge.EXTRA_INSTALLER)).getCanonicalFile();
      File helper = new File(Tools.DIR_DATA, "forge_installer/forge_installer.jar").getCanonicalFile();
      File result = new File(resultPath).getCanonicalFile();
      File root = new File(getFilesDir(), "lite-forge-results").getCanonicalFile();
      if (!installer.isFile() || !helper.isFile() || nonce == null || !nonce.matches("[0-9a-f]{32}")
          || !result.getParentFile().equals(root) || result.exists())
        throw new java.io.IOException("Forge 安装请求无效。");
      Runtime runtime = selectRuntime(installer);
      if (runtime == null) throw new java.io.IOException("没有可用的 Java 运行时。");
      ArrayList<String> args = new ArrayList<String>();
      args.add("-Djava.awt.headless=true");
      args.add("-cp");
      args.add(helper.getAbsolutePath());
      args.add("com.litemc.installer.ForgeInstallMain");
      args.add(installer.getAbsolutePath());
      args.add(new File(Tools.DIR_GAME_NEW).getCanonicalPath());
      args.add(result.getAbsolutePath());
      args.add(nonce);
      args.add(spec.minecraft);
      args.add(LiteForge.sha1(installer));
      JREUtils.launchInstallerJavaVM(this, runtime, args);
    } catch (Throwable error) {
      writeFailure(resultPath, nonce, minecraft, error);
      runOnUiThread(() -> { finish(); android.os.Process.killProcess(android.os.Process.myPid()); });
    }
  }

  private Runtime selectRuntime(File installer) throws Exception {
    int required = installerJavaVersion(installer);
    String[] mc = getIntent().getStringExtra(LiteForge.EXTRA_MC).split("\\.");
    if (mc.length > 1 && Integer.parseInt(mc[0]) == 1) {
      int minor = Integer.parseInt(mc[1]);
      int patch = mc.length > 2 ? Integer.parseInt(mc[2]) : 0;
      if (minor > 20 || (minor == 20 && patch >= 5)) required = Math.max(required, 21);
      else if (minor >= 17) required = Math.max(required, 17);
    }
    String runtimeName = MultiRTUtils.getNearestJreName(required);
    if (runtimeName == null) throw new java.io.IOException("没有兼容此 Forge 安装器的 Java 运行时。");
    return MultiRTUtils.forceReread(runtimeName);
  }

  private static int installerJavaVersion(File installer) throws Exception {
    try (ZipFile zip = new ZipFile(installer)) {
      ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
      if (manifest == null) throw new java.io.IOException("Forge 安装器缺少清单。");
      String main;
      try (java.io.InputStream input = zip.getInputStream(manifest)) {
        main = new String(LiteForge.readStream(input, 64 * 1024), "UTF-8");
      }
      int marker = main.indexOf("Main-Class:");
      int end = marker < 0 ? -1 : main.indexOf('\n', marker);
      if (marker < 0) throw new java.io.IOException("Forge 安装器没有入口类。");
      String className = main.substring(marker + 11, end < 0 ? main.length() : end).trim().replace('.', '/') + ".class";
      ZipEntry entry = zip.getEntry(className);
      if (entry == null) throw new java.io.IOException("Forge 安装器入口类缺失。");
      try (DataInputStream input = new DataInputStream(zip.getInputStream(entry))) {
        int magic = input.readInt();
        input.readUnsignedShort();
        int major = input.readUnsignedShort();
        if (magic != 0xcafebabe)
          throw new java.io.IOException("Forge 安装器格式无效。");
        return Math.max(8, major - 44);
      }
    }
  }

  @Override public void onBackPressed() {
    writeFailure(getIntent().getStringExtra(LiteForge.EXTRA_RESULT), getIntent().getStringExtra(LiteForge.EXTRA_NONCE),
        getIntent().getStringExtra(LiteForge.EXTRA_MC), new java.io.IOException("用户取消了 Forge 安装。"));
    finish();
    android.os.Process.killProcess(android.os.Process.myPid());
  }

  private void writeFailure(String resultPath, String nonce, String minecraft, Throwable error) {
    try {
      if (resultPath == null || nonce == null || minecraft == null) return;
      File result = new File(resultPath).getCanonicalFile();
      File root = new File(getFilesDir(), "lite-forge-results").getCanonicalFile();
      if (!result.getParentFile().equals(root) || result.exists()) return;
      org.json.JSONObject json = new org.json.JSONObject().put("nonce", nonce).put("success", false)
          .put("minecraft", minecraft).put("error", error.getMessage() == null ? "Forge 安装器无法启动。" : error.getMessage());
      File temp = new File(root, result.getName() + ".part-" + UUID.randomUUID().toString());
      try (FileOutputStream output = new FileOutputStream(temp)) { output.write(json.toString().getBytes("UTF-8")); }
      if (!temp.renameTo(result)) temp.delete();
    } catch (Exception ignored) { }
  }
}
