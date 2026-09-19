package com.litemc.launcher;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

/** Native-only, encrypted cross-process handoff. Never send this DTO to the WebView. */
public final class LiteRuntimeAccount {
  public static final String PROFILE = "lite-session";

  private static SecretKey key() throws Exception {
    if (android.os.Build.VERSION.SDK_INT < 23)
      throw new IllegalStateException("Android 6 or newer is required");
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias("lite.runtime.v1")) {
      KeyGenerator gen =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      gen.init(
          new KeyGenParameterSpec.Builder(
                  "lite.runtime.v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      gen.generateKey();
    }
    return (SecretKey) store.getKey("lite.runtime.v1", null);
  }

  public static synchronized void write(Context context, MinecraftAccount account)
      throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] payload =
        cipher.doFinal(Tools.GLOBAL_GSON.toJson(account).getBytes(StandardCharsets.UTF_8));
    AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "lite-runtime.bin"));
    FileOutputStream stream = null;
    try {
      stream = file.startWrite();
      stream.write(cipher.getIV().length);
      stream.write(cipher.getIV());
      stream.write(payload);
      file.finishWrite(stream);
    } catch (Exception ex) {
      if (stream != null) file.failWrite(stream);
      throw ex;
    }
  }

  public static synchronized MinecraftAccount read(Context context) {
    try {
      byte[] bytes =
          new AtomicFile(new File(context.getFilesDir(), "lite-runtime.bin")).readFully();
      int length = bytes[0] & 255;
      if (length != 12 || bytes.length < 30 || bytes.length > 65536) return null;
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 1, length));
      return Tools.GLOBAL_GSON.fromJson(
          new String(
              cipher.doFinal(bytes, length + 1, bytes.length - length - 1), StandardCharsets.UTF_8),
          MinecraftAccount.class);
    } catch (Exception ex) {
      return null;
    }
  }

  public static synchronized void clear(Context context) {
    new AtomicFile(new File(context.getFilesDir(), "lite-runtime.bin")).delete();
  }
}
