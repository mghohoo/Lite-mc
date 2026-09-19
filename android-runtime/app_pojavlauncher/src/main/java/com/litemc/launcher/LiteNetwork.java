package com.litemc.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Map;
import org.json.JSONObject;

/** Small, deliberately strict HTTPS client shared by Lite product services. */
public final class LiteNetwork {
  private static final int CONNECT_TIMEOUT = 15000;
  private static final int READ_TIMEOUT = 45000;
  private static final int MAX_RESPONSE = 2 * 1024 * 1024;

  private LiteNetwork() {}

  /** OAuth's public protocol error code only; never contains a response body or token. */
  public static final class OAuthException extends IOException {
    public final String code;

    OAuthException(String code) {
      super("OAuth 请求未完成：" + code);
      this.code = code;
    }
  }

  public static JSONObject getJson(String url, String[] allowedHosts, Map<String, String> headers)
      throws Exception {
    return new JSONObject(new String(request("GET", url, allowedHosts, headers, null), "UTF-8"));
  }

  public static JSONObject postForm(String url, String[] allowedHosts, Map<String, String> values)
      throws Exception {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (body.length() > 0) body.append('&');
      body.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
    }
    java.util.HashMap<String, String> headers = new java.util.HashMap<String, String>();
    headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
    return new JSONObject(
        new String(
            request("POST", url, allowedHosts, headers, body.toString().getBytes("UTF-8")),
            "UTF-8"));
  }

  public static JSONObject postJson(
      String url, String[] allowedHosts, JSONObject value, Map<String, String> headers)
      throws Exception {
    java.util.HashMap<String, String> all = new java.util.HashMap<String, String>();
    if (headers != null) all.putAll(headers);
    all.put("Content-Type", "application/json; charset=UTF-8");
    return new JSONObject(
        new String(
            request("POST", url, allowedHosts, all, value.toString().getBytes("UTF-8")), "UTF-8"));
  }

  public static byte[] request(
      String method, String target, String[] allowedHosts, Map<String, String> headers, byte[] body)
      throws Exception {
    URL url = checkedUrl(target, allowedHosts);
    for (int redirects = 0; redirects <= 3; redirects++) {
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      c.setConnectTimeout(CONNECT_TIMEOUT);
      c.setReadTimeout(READ_TIMEOUT);
      c.setInstanceFollowRedirects(false);
      c.setRequestMethod(method);
      c.setRequestProperty("User-Agent", "Lite-MC-Android/1.0");
      c.setRequestProperty("Accept", "application/json");
      if (headers != null)
        for (Map.Entry<String, String> h : headers.entrySet())
          c.setRequestProperty(h.getKey(), h.getValue());
      if (body != null) {
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        OutputStream out = c.getOutputStream();
        try {
          out.write(body);
        } finally {
          out.close();
        }
      }
      int code = c.getResponseCode();
      if (code >= 300 && code < 400) {
        String location = c.getHeaderField("Location");
        c.disconnect();
        if (location == null) throw new IOException("服务器返回了无效重定向。");
        URL next = checkedUrl(new URL(url, location).toString(), allowedHosts);
        if (body != null || (headers != null && !headers.isEmpty() && !url.getHost().equals(next.getHost())))
          throw new IOException("Authenticated service redirects are not allowed");
        url = next;
        continue;
      }
      InputStream input = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
      byte[] result = readBounded(input, MAX_RESPONSE);
      c.disconnect();
      if (code < 200 || code >= 300) {
        String oauthCode = "";
        try {
          oauthCode = new JSONObject(new String(result, "UTF-8")).optString("error", "");
        } catch (Exception ignored) {
        }
        if (oauthCode.matches("[a-z_]{1,64}")) throw new OAuthException(oauthCode);
        throw new IOException("服务请求失败（HTTP " + code + "）。");
      }
      return result;
    }
    throw new IOException("重定向次数过多。");
  }

  public static void download(String target, String[] allowedHosts, File destination, String sha1)
      throws Exception {
    URL url = checkedUrl(target, allowedHosts);
    HttpURLConnection c = null;
    for (int redirects = 0; redirects <= 3; redirects++) {
      c = (HttpURLConnection) url.openConnection();
      c.setConnectTimeout(CONNECT_TIMEOUT);
      c.setReadTimeout(READ_TIMEOUT);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", "Lite-MC-Android/1.0");
      int code = c.getResponseCode();
      if (code >= 300 && code < 400) {
        String location = c.getHeaderField("Location");
        c.disconnect();
        if (location == null) throw new IOException("下载重定向无效。");
        url = checkedUrl(new URL(url, location).toString(), allowedHosts);
        c = null;
        continue;
      }
      if (code / 100 != 2) {
        c.disconnect();
        throw new IOException("下载失败（HTTP " + code + "）。");
      }
      break;
    }
    if (c == null) throw new IOException("下载重定向次数过多。");
    File parent = destination.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建下载目录。");
    File temp = new File(parent, destination.getName() + ".part");
    MessageDigest digest = MessageDigest.getInstance("SHA-1");
    InputStream in = c.getInputStream();
    OutputStream out = new FileOutputStream(temp);
    byte[] buffer = new byte[32768];
    long total = 0;
    try {
      for (int n; (n = in.read(buffer)) >= 0; ) {
        total += n;
        if (total > 1024L * 1024L * 1024L) throw new IOException("文件过大。");
        out.write(buffer, 0, n);
        digest.update(buffer, 0, n);
      }
    } finally {
      try {
        in.close();
      } finally {
        out.close();
        c.disconnect();
      }
    }
    if (sha1 == null
        || !sha1.matches("(?i)[0-9a-f]{40}")
        || !sha1.equalsIgnoreCase(hex(digest.digest()))) {
      temp.delete();
      throw new IOException("下载文件校验失败。");
    }
    if (destination.exists() && !destination.delete()) {
      temp.delete();
      throw new IOException("无法替换已有文件。");
    }
    if (!temp.renameTo(destination)) {
      temp.delete();
      throw new IOException("无法完成文件安装。");
    }
  }

  private static URL checkedUrl(String target, String[] allowedHosts) throws Exception {
    URL url = new URL(target);
    if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("仅允许 HTTPS 地址。");
    String host = url.getHost().toLowerCase(java.util.Locale.US);
    boolean allowed = false;
    for (String candidate : allowedHosts)
      if (host.equals(candidate) || host.endsWith("." + candidate)) {
        allowed = true;
        break;
      }
    if (!allowed) throw new IOException("下载地址不在允许的服务域名中。");
    return url;
  }

  private static byte[] readBounded(InputStream input, int max) throws IOException {
    if (input == null) return new byte[0];
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try {
      for (int n; (n = input.read(buffer)) >= 0; ) {
        if (out.size() + n > max) throw new IOException("服务器响应过大。");
        out.write(buffer, 0, n);
      }
    } finally {
      input.close();
    }
    return out.toByteArray();
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
    return out.toString();
  }
}
