package com.litemc.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final LaunchEngine engine = new RuntimeUnavailableEngine();
    private Spinner versionPicker;
    private EditText username;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int padding = 36;
        root.setPadding(padding, padding, padding, padding);
        username = new EditText(this); username.setHint("离线游戏名（3–16 位）"); root.addView(username);
        versionPicker = new Spinner(this); root.addView(versionPicker);
        Button refresh = new Button(this); refresh.setText("加载 Mojang 正式版"); root.addView(refresh);
        Button launch = new Button(this); launch.setText("启动 Minecraft"); root.addView(launch);
        status = new TextView(this); root.addView(status); setContentView(root);
        refresh.setOnClickListener(v -> loadReleases());
        launch.setOnClickListener(v -> startSelected());
        loadReleases();
    }

    private void loadReleases() {
        status.setText("正在读取 Mojang 官方版本列表…");
        Executors.newSingleThreadExecutor().execute(() -> {
            try { List<String> versions = MojangRepository.releases(); runOnUiThread(() -> {
                versionPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, versions));
                status.setText("已加载 " + versions.size() + " 个正式版。");
            }); } catch (Exception e) { runOnUiThread(() -> status.setText("版本列表加载失败：" + e.getMessage())); }
        });
    }

    private void startSelected() {
        String name = username.getText().toString().trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) { status.setText("离线游戏名须为 3–16 位字母、数字或下划线。"); return; }
        Object selected = versionPicker.getSelectedItem();
        if (selected == null) { status.setText("请先加载版本列表。"); return; }
        LaunchEngine.Result result = engine.launch(new LaunchEngine.LaunchRequest(selected.toString(), name, false));
        status.setText(result.message);
    }
}
