package com.litemc.launcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.security.auth.x500.X500Principal;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import org.json.JSONObject;

/** Native-only Microsoft device-code and offline account service. */
public final class LiteAccounts {
  private static final String[] MS = {"login.microsoftonline.com", "login.live.com"};
  private static final String[] XBOX = {
    "user.auth.xboxlive.com", "xsts.auth.xboxlive.com", "api.minecraftservices.com"
  };
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private final Context context;
  private JSONObject state;
  private JSONObject pending;
  private String mcToken;

  public LiteAccounts(Context context) {
    this.context = context.getApplicationContext();
    this.state = read();
    this.mcToken = state.optString("mcToken", null);
  }

  public synchronized JSONObject handle(String action, JSONObject args) throws Exception {
    if ("accounts.offline".equals(action)) return offline(args.optString("name", ""));
    if ("accounts.config".equals(action)) {
      String id = args.optString("clientId", "").trim();
      if (!id.matches("[A-Za-z0-9-]{8,128}"))
        throw new IllegalArgumentException("Microsoft clientId 未配置或格式无效。");
      state.put("clientId", id);
      write();
      return snapshot();
    }
    if ("accounts.login.start".equals(action)) return start();
    if ("accounts.login.poll".equals(action)) return poll(args.optString("flowId", ""));
    if ("accounts.logout".equals(action)) {
      state = new JSONObject().put("clientId", state.optString("clientId", ""));
      mcToken = null;
      pending = null;
      write();
      LiteRuntimeAccount.clear(context);
      return snapshot();
    }
    if ("accounts.skin".equals(action))
      return skin(args.optString("base64", ""), args.optString("model", "classic"));
    throw new IllegalArgumentException("不支持的帐号操作。");
  }

  public synchronized JSONObject snapshot() {
    try {
      JSONObject out = new JSONObject();
      String mode = state.optString("mode", "offline");
      out.put("mode", mode);
      out.put("name", state.optString("name", "Player"));
      out.put("uuid", state.optString("uuid", ""));
      out.put("skinUrl", state.has("skinUrl") ? state.opt("skinUrl") : JSONObject.NULL);
      out.put("model", state.optString("model", "CLASSIC"));
      out.put("hasSession", "online".equals(mode) && state.has("refresh"));
      out.put("clientId", state.optString("clientId", ""));
      return out;
    } catch (Exception ignored) {
      return new JSONObject();
    }
  }

  public synchronized MinecraftAccount launchAccount() throws Exception {
    if ("online".equals(state.optString("mode"))
        && (mcToken == null
            || System.currentTimeMillis() >= state.optLong("mcExpires", 0) - 60000L)) refresh();
    MinecraftAccount account = new MinecraftAccount();
    account.username = state.optString("name", "Player");
    account.profileId = state.optString("uuid", offlineUuid(account.username));
    account.clientToken = "0";
    if ("online".equals(state.optString("mode"))) {
      account.accessToken = mcToken;
      account.isMicrosoft = true;
      account.msaRefreshToken = "0";
      account.expiresAt = state.optLong("mcExpires", 0);
    } else {
      account.accessToken = "0";
      account.isMicrosoft = false;
    }
    return account;
  }

  private JSONObject offline(String name) throws Exception {
    if (!name.matches("[A-Za-z0-9_]{3,16}"))
      throw new IllegalArgumentException("离线游戏名须为 3–16 位字母、数字或下划线。");
    String clientId = state.optString("clientId", "");
    state =
        new JSONObject()
            .put("clientId", clientId)
            .put("mode", "offline")
            .put("name", name)
            .put("uuid", offlineUuid(name))
            .put("model", "CLASSIC");
    mcToken = null;
    write();
    LiteRuntimeAccount.clear(context);
    return snapshot();
  }

  private JSONObject start() throws Exception {
    String id = state.optString("clientId", "");
    if (id.length() == 0)
      throw new IllegalStateException("请先在设置中配置 Lite-MC 自己注册的 Microsoft clientId。");
    Map<String, String> form = new HashMap<String, String>();
    form.put("client_id", id);
    form.put("scope", "XboxLive.signin offline_access");
    JSONObject result =
        LiteNetwork.postForm(
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", MS, form);
    if (!result.has("device_code") || !result.has("user_code"))
      throw new IllegalStateException("Microsoft 未返回设备登录信息。");
    pending = result;
    pending.put("flowId", UUID.randomUUID().toString());
    pending.put("started", System.currentTimeMillis());
    pending.put("interval", Math.max(5, result.optInt("interval", 5)));
    pending.put("nextPollAt", System.currentTimeMillis() + pending.getInt("interval") * 1000L);
    JSONObject out = new JSONObject();
    out.put("flowId", pending.getString("flowId"));
    out.put("userCode", pending.getString("user_code"));
    out.put(
        "verificationUri",
        result.optString("verification_uri", result.optString("verification_uri_complete", "")));
    out.put("interval", pending.getInt("interval"));
    return out;
  }

  private JSONObject poll(String flowId) throws Exception {
    if (pending == null || !flowId.equals(pending.optString("flowId")))
      throw new IllegalArgumentException("登录流程不存在或已过期。");
    if (System.currentTimeMillis() - pending.optLong("started")
        > pending.optLong("expires_in", 900) * 1000L) {
      pending = null;
      throw new IllegalStateException("Microsoft 登录已过期，请重新开始。");
    }
    long now = System.currentTimeMillis();
    if (now < pending.optLong("nextPollAt", 0)) return pendingReply();
    Map<String, String> form = new HashMap<String, String>();
    form.put("client_id", state.optString("clientId"));
    form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
    form.put("device_code", pending.getString("device_code"));
    try {
      JSONObject token =
          LiteNetwork.postForm(
              "https://login.microsoftonline.com/consumers/oauth2/v2.0/token", MS, form);
      complete(token);
      pending = null;
      return new JSONObject().put("pending", false).put("account", snapshot());
    } catch (LiteNetwork.OAuthException e) {
      if ("authorization_pending".equals(e.code) || "slow_down".equals(e.code)) {
        if ("slow_down".equals(e.code)) pending.put("interval", pending.optInt("interval", 5) + 5);
        pending.put(
            "nextPollAt", System.currentTimeMillis() + pending.optInt("interval", 5) * 1000L);
        return pendingReply();
      }
      pending = null;
      if ("authorization_declined".equals(e.code) || "expired_token".equals(e.code))
        throw new IllegalStateException("Microsoft 登录已取消或过期，请重新开始。");
      throw new IllegalStateException("Microsoft 登录配置无效，请检查 Lite-MC clientId 后重试。");
    }
  }

  private void refresh() throws Exception {
    String refresh = state.optString("refresh", "");
    if (refresh.length() == 0) throw new IllegalStateException("正版登录已失效，请重新登录。");
    Map<String, String> form = new HashMap<String, String>();
    form.put("client_id", state.optString("clientId"));
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refresh);
    form.put("scope", "XboxLive.signin offline_access");
    try {
      complete(
          LiteNetwork.postForm(
              "https://login.microsoftonline.com/consumers/oauth2/v2.0/token", MS, form));
    } catch (Exception e) {
      throw new IllegalStateException("正版登录已失效，请重新登录。");
    }
  }

  private JSONObject pendingReply() throws Exception {
    long wait = Math.max(0L, pending.optLong("nextPollAt") - System.currentTimeMillis());
    return new JSONObject()
        .put("pending", true)
        .put("interval", pending.optInt("interval", 5))
        .put("nextPollAt", pending.optLong("nextPollAt"))
        .put("retryAfterMs", wait);
  }

  private void complete(JSONObject microsoft) throws Exception {
    String access = microsoft.optString("access_token", "");
    String refresh = microsoft.optString("refresh_token", "");
    if (access.length() == 0 || refresh.length() == 0)
      throw new IllegalStateException("Microsoft 登录未完成。");
    JSONObject props =
        new JSONObject()
            .put("AuthMethod", "RPS")
            .put("SiteName", "user.auth.xboxlive.com")
            .put("RpsTicket", "d=" + access);
    JSONObject xbl =
        LiteNetwork.postJson(
            "https://user.auth.xboxlive.com/user/authenticate",
            XBOX,
            new JSONObject()
                .put("Properties", props)
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT"),
            null);
    JSONObject xstsProps =
        new JSONObject()
            .put("SandboxId", "RETAIL")
            .put("UserTokens", new org.json.JSONArray().put(xbl.getString("Token")));
    JSONObject xsts =
        LiteNetwork.postJson(
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            XBOX,
            new JSONObject()
                .put("Properties", xstsProps)
                .put("RelyingParty", "rp://api.minecraftservices.com/")
                .put("TokenType", "JWT"),
            null);
    String uhs =
        xsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
    JSONObject minecraft =
        LiteNetwork.postJson(
            "https://api.minecraftservices.com/authentication/login_with_xbox",
            XBOX,
            new JSONObject()
                .put("identityToken", "XBL3.0 x=" + uhs + ";" + xsts.getString("Token")),
            null);
    mcToken = minecraft.getString("access_token");
    Map<String, String> bearer = new HashMap<String, String>();
    bearer.put("Authorization", "Bearer " + mcToken);
    JSONObject profile =
        LiteNetwork.getJson("https://api.minecraftservices.com/minecraft/profile", XBOX, bearer);
    state
        .put("mode", "online")
        .put("name", profile.getString("name"))
        .put("uuid", profile.getString("id"))
        .put("refresh", refresh)
        .put("mcToken", mcToken)
        .put(
            "mcExpires",
            System.currentTimeMillis() + minecraft.optLong("expires_in", 86400) * 1000L);
    applyProfile(profile);
    write();
    LiteRuntimeAccount.write(context, launchAccount());
  }

  private JSONObject skin(String base64, String model) throws Exception {
    if (!"online".equals(state.optString("mode")))
      throw new IllegalStateException("请先登录拥有 Minecraft: Java Edition 的微软帐号。");
    if (!("slim".equalsIgnoreCase(model) || "classic".equalsIgnoreCase(model)))
      throw new IllegalArgumentException("皮肤模型必须为 classic 或 slim。");
    byte[] png;
    try {
      png = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
    } catch (Exception e) {
      throw new IllegalArgumentException("皮肤必须是 PNG 数据。");
    }
    if (png.length < 16
        || png.length > 1024 * 1024
        || png[0] != (byte) 137
        || png[1] != 80
        || png[2] != 78
        || png[3] != 71
        || png[4] != 13
        || png[5] != 10
        || png[6] != 26
        || png[7] != 10) throw new IllegalArgumentException("皮肤必须是有效 PNG 文件。");
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(png, 0, png.length, options);
    if (options.outWidth != 64 || (options.outHeight != 64 && options.outHeight != 32))
      throw new IllegalArgumentException("皮肤必须为 64x64 或 64x32 PNG。");
    if (mcToken == null || System.currentTimeMillis() >= state.optLong("mcExpires", 0) - 60000L)
      refresh();
    String boundary = "Lite" + UUID.randomUUID().toString().replace("-", "");
    java.net.HttpURLConnection c =
        (java.net.HttpURLConnection)
            new java.net.URL("https://api.minecraftservices.com/minecraft/profile/skins")
                .openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(45000);
    c.setInstanceFollowRedirects(false);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Authorization", "Bearer " + mcToken);
    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
    java.io.OutputStream out = c.getOutputStream();
    try {
      writePart(
          out,
          boundary,
          "variant",
          null,
          ("slim".equalsIgnoreCase(model) ? "slim" : "classic").getBytes(UTF8));
      writePart(out, boundary, "file", "skin.png", png);
      out.write(("--" + boundary + "--\r\n").getBytes(UTF8));
    } finally {
      out.close();
    }
    int code = c.getResponseCode();
    java.io.InputStream response = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
    if (response != null)
      try {
        byte[] discard = new byte[1024];
        while (response.read(discard) >= 0) {}
      } finally {
        response.close();
      }
    c.disconnect();
    if (code / 100 != 2) throw new IllegalStateException("皮肤上传失败，请确认帐号与 PNG 文件。");
    Map<String, String> bearer = new HashMap<String, String>();
    bearer.put("Authorization", "Bearer " + mcToken);
    applyProfile(
        LiteNetwork.getJson("https://api.minecraftservices.com/minecraft/profile", XBOX, bearer));
    write();
    return snapshot();
  }

  private static void writePart(
      java.io.OutputStream out, String b, String n, String filename, byte[] data) throws Exception {
    String header =
        "--"
            + b
            + "\r\nContent-Disposition: form-data; name=\""
            + n
            + "\""
            + (filename == null ? "" : "; filename=\"" + filename + "\"")
            + "\r\n"
            + (filename == null ? "" : "Content-Type: image/png\r\n")
            + "\r\n";
    out.write(header.getBytes(UTF8));
    out.write(data);
    out.write("\r\n".getBytes(UTF8));
  }

  private void applyProfile(JSONObject profile) throws Exception {
    org.json.JSONArray skins = profile.optJSONArray("skins");
    if (skins != null && skins.length() > 0) {
      JSONObject s = skins.getJSONObject(0);
      state.put("skinUrl", s.has("url") ? s.opt("url") : JSONObject.NULL);
      state.put("model", s.optString("variant", "CLASSIC"));
    }
  }

  private JSONObject read() {
    try {
      return new JSONObject(
          new String(
              decrypt(
                  new AtomicFile(new File(context.getFilesDir(), "lite-account.bin")).readFully()),
              UTF8));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  private void write() throws Exception {
    AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "lite-account.bin"));
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(encrypt(state.toString().getBytes(UTF8)));
      file.finishWrite(out);
    } catch (Exception e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }

  private byte[] encrypt(byte[] plain) throws Exception {
    byte[] aes = new byte[32], iv = new byte[12];
    new SecureRandom().nextBytes(aes);
    new SecureRandom().nextBytes(iv);
    Cipher a = Cipher.getInstance("AES/GCM/NoPadding");
    a.init(
        Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(aes, "AES"),
        new GCMParameterSpec(128, iv));
    byte[] sealed = a.doFinal(plain);
    Cipher r = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    r.init(Cipher.ENCRYPT_MODE, publicKey());
    byte[] wrapped = r.doFinal(aes);
    byte[] all = new byte[1 + iv.length + 2 + wrapped.length + sealed.length];
    all[0] = (byte) iv.length;
    System.arraycopy(iv, 0, all, 1, iv.length);
    all[1 + iv.length] = (byte) (wrapped.length >> 8);
    all[2 + iv.length] = (byte) wrapped.length;
    System.arraycopy(wrapped, 0, all, 3 + iv.length, wrapped.length);
    System.arraycopy(sealed, 0, all, 3 + iv.length + wrapped.length, sealed.length);
    return all;
  }

  private byte[] decrypt(byte[] all) throws Exception {
    int iv = all[0] & 255, off = 1 + iv, wrap = ((all[off] & 255) << 8) | (all[off + 1] & 255);
    if (iv != 12 || wrap < 128 || all.length <= off + 2 + wrap)
      throw new IllegalArgumentException();
    byte[] w = new byte[wrap];
    System.arraycopy(all, off + 2, w, 0, wrap);
    Cipher r = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    r.init(Cipher.DECRYPT_MODE, privateKey());
    byte[] aes = r.doFinal(w);
    Cipher a = Cipher.getInstance("AES/GCM/NoPadding");
    a.init(
        Cipher.DECRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(aes, "AES"),
        new GCMParameterSpec(128, all, 1, iv));
    return a.doFinal(all, off + 2 + wrap, all.length - off - 2 - wrap);
  }

  private PublicKey publicKey() throws Exception {
    return store().getCertificate("lite.account.v1").getPublicKey();
  }

  private PrivateKey privateKey() throws Exception {
    return (PrivateKey) store().getKey("lite.account.v1", null);
  }

  private KeyStore store() throws Exception {
    KeyStore s = KeyStore.getInstance("AndroidKeyStore");
    s.load(null);
    if (!s.containsAlias("lite.account.v1")) createKey();
    return s;
  }

  private void createKey() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
    if (Build.VERSION.SDK_INT >= 23)
      g.initialize(
          new KeyGenParameterSpec.Builder(
                  "lite.account.v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setKeySize(2048)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
              .build());
    else {
      Calendar now = Calendar.getInstance(), end = Calendar.getInstance();
      end.add(Calendar.YEAR, 20);
      g.initialize(
          new KeyPairGeneratorSpec.Builder(context)
              .setAlias("lite.account.v1")
              .setSubject(new X500Principal("CN=Lite-MC"))
              .setSerialNumber(BigInteger.ONE)
              .setStartDate(now.getTime())
              .setEndDate(end.getTime())
              .build());
    }
    g.generateKeyPair();
  }

  private static String offlineUuid(String name) {
    try {
      byte[] b = MessageDigest.getInstance("MD5").digest(("OfflinePlayer:" + name).getBytes(UTF8));
      b[6] = (byte) ((b[6] & 15) | 48);
      b[8] = (byte) ((b[8] & 63) | 128);
      StringBuilder s = new StringBuilder();
      for (byte x : b) s.append(String.format(java.util.Locale.US, "%02x", x & 255));
      return s.toString();
    } catch (Exception e) {
      throw new IllegalStateException("无法生成离线 UUID。");
    }
  }
}
