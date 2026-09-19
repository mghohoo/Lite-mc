package com.litemc.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpsURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Fetches only Mojang's signed launcher metadata; game archives are never sourced from third parties. */
final class MojangRepository {
    static final String MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    static List<String> releases() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(MANIFEST).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "Lite-MC-Android/0.1");
        try {
            if (connection.getResponseCode() / 100 != 2) throw new IllegalStateException("Mojang manifest request failed");
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null;) json.append(line);
            }
            JSONArray versions = new JSONObject(json.toString()).getJSONArray("versions");
            List<String> result = new ArrayList<>();
            for (int i = 0; i < versions.length(); i++) {
                JSONObject version = versions.getJSONObject(i);
                if ("release".equals(version.optString("type"))) result.add(version.getString("id"));
            }
            return result;
        } finally { connection.disconnect(); }
    }
}
