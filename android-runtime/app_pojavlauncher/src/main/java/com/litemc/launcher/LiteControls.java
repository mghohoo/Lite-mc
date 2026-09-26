package com.litemc.launcher;

import java.io.*;
import java.util.*;
import org.json.*;

/** One saved touch-key profile, applied to every Lite-MC instance before launch. */
public final class LiteControls {
  private static final String[] NAMES = {"跳跃", "攻击", "使用", "潜行", "疾跑", "背包", "暂停", "文字输入"};
  private static final int[] DEFAULTS = {32, -3, -4, 340, 341, 69, 256, 84};
  private LiteControls() {}

  public static JSONObject validate(JSONObject input) throws Exception {
    int scale = input.optInt("scale", 100), opacity = input.optInt("opacity", 85);
    if (scale < 60 || scale > 150 || opacity < 30 || opacity > 100) throw new IOException("按钮大小或透明度超出范围。");
    JSONObject bindings = input.optJSONObject("bindings"), clean = new JSONObject();
    for (int i = 0; i < NAMES.length; i++) {
      int key = bindings == null ? DEFAULTS[i] : bindings.optInt(NAMES[i], DEFAULTS[i]);
      if (!validKey(key)) throw new IOException("无效按键：" + NAMES[i]);
      clean.put(NAMES[i], key);
    }
    return new JSONObject().put("scale", scale).put("opacity", opacity).put("bindings", clean)
        .put("projection", input.optBoolean("projection", true))
        .put("sprintToggle", input.optBoolean("sprintToggle", true))
        .put("sneakToggle", input.optBoolean("sneakToggle", true))
        .put("extraChat", input.optBoolean("extraChat", false));
  }

  private static boolean validKey(int k) {
    return k == -3 || k == -4 || k == -6 || k == 32 || k == 39 || k == 44 || k == 45 || k == 46 || k == 47
        || k >= 48 && k <= 57 || k >= 65 && k <= 90 || k >= 256 && k <= 269
        || k >= 290 && k <= 301 || k >= 340 && k <= 347;
  }

  public static JSONObject layout(JSONObject template, JSONObject settings, File instance) throws Exception {
    JSONObject clean = validate(settings);
    JSONObject result = new JSONObject(template.toString());
    JSONArray buttons = result.getJSONArray("mControlDataList"), filtered = new JSONArray();
    JSONObject bindings = clean.getJSONObject("bindings");
    for (int i = 0; i < buttons.length(); i++) {
      JSONObject button = buttons.getJSONObject(i); String name = button.optString("name");
      if ("文字输入".equals(name) && !clean.getBoolean("extraChat")) continue;
      if (bindings.has(name)) button.put("keycodes", new JSONArray().put(bindings.getInt(name)).put(0).put(0).put(0));
      button.put("opacity", clean.getInt("opacity") / 100.0);
      if ("疾跑".equals(name)) button.put("isToggle", clean.getBoolean("sprintToggle"));
      if ("潜行".equals(name)) button.put("isToggle", clean.getBoolean("sneakToggle"));
      if ("菜单".equals(name)) button.put("bgColor", 0xff235d49).put("strokeColor", 0xff8cf3c4).put("opacity", 0.95);
      // Leave space for the prominent menu at larger user-selected scales.
      if ("聊天输入".equals(name)) button.put("dynamicX", "${screen_width} / 2 - ${width} - px(52)");
      if ("键盘".equals(name)) button.put("dynamicX", "${screen_width} / 2 + px(52)");
      filtered.put(button);
    }
    if (clean.getBoolean("projection") && LiteLocalFiles.projection(instance).getBoolean("installed")) {
      JSONObject hotkey = projectionHotkey(instance);
      filtered.put(new JSONObject().put("name", hotkey.getString("label"))
          .put("width", 88).put("height", 42)
          .put("dynamicX", "${screen_width} / 2 - ${width} / 2").put("dynamicY", "px(78)")
          .put("keycodes", hotkey.getJSONArray("keys")).put("bgColor", 0xff233d55)
          .put("strokeColor", 0xffa4ceff).put("strokeWidth", 1).put("cornerRadius", 16)
          .put("opacity", clean.getInt("opacity") / 100.0).put("displayInGame", true).put("displayInMenu", false));
    }
    result.put("mControlDataList", filtered);
    return result;
  }

  public static JSONObject projectionHotkey(File instance) throws Exception {
    File config = new File(instance, "config/litematica.json");
    JSONObject hotkeys = new JSONObject();
    if (config.isFile()) try (InputStream in = new FileInputStream(config)) {
      JSONObject found = new JSONObject(LiteLocalFiles.read(in, 4 * 1024 * 1024)).optJSONObject("Hotkeys");
      if (found != null) hotkeys = found;
    } catch (IOException | JSONException ignored) {
      // A damaged optional mod configuration must not prevent the game from starting.
    }
    // Recent ports offer a direct load browser; older versions use their main menu.
    for (String name : new String[]{"openLoadSchematicsScreen", "openGuiSchematicLoad", "openGuiMainMenu", "openMainMenuScreen"}) {
      JSONObject value = hotkeys.optJSONObject(name);
      if (value == null) continue;
      JSONArray keys = parseKeys(value.optString("keys", ""));
      if (keys.length() > 0) return new JSONObject().put("keys", keys)
          .put("label", name.contains("MainMenu") ? "投影菜单" : "加载投影");
    }
    return new JSONObject().put("keys", new JSONArray().put(77)).put("label", "投影菜单");
  }

  static JSONArray parseKeys(String text) {
    JSONArray out = new JSONArray();
    for (String part : text.split(",")) {
      String key = part.trim().toUpperCase(Locale.ROOT); int code;
      if (key.matches("[A-Z0-9]")) code = key.charAt(0);
      else if (key.matches("F([1-9]|1[0-2])")) code = 289 + Integer.parseInt(key.substring(1));
      else if ("L_CTRL".equals(key) || "LEFT_CONTROL".equals(key)) code = 341;
      else if ("L_SHIFT".equals(key) || "LEFT_SHIFT".equals(key)) code = 340;
      else if ("L_ALT".equals(key) || "LEFT_ALT".equals(key)) code = 342;
      else if ("R_CTRL".equals(key) || "RIGHT_CONTROL".equals(key)) code = 345;
      else if ("R_SHIFT".equals(key) || "RIGHT_SHIFT".equals(key)) code = 344;
      else if ("R_ALT".equals(key) || "RIGHT_ALT".equals(key)) code = 346;
      else if ("SPACE".equals(key)) code = 32;
      else return new JSONArray();
      out.put(code);
      if (out.length() > 4) return new JSONArray();
    }
    return out;
  }
}
