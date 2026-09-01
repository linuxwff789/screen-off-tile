package com.screenoff.tile;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 主界面：root 权限检测、关屏/恢复测试、磁贴添加入口
 */
public class MainActivity extends Activity {

    private TextView statusText;
    private TextView rootText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        int titleColor = 0xFF1A73E8;
        int textColor = 0xFF202124;
        int subColor = 0xFF5F6368;

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTextColor(titleColor);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.app_desc);
        desc.setTextSize(13);
        desc.setTextColor(subColor);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(6), 0, dp(24));
        root.addView(desc);

        // root 状态
        rootText = new TextView(this);
        rootText.setTextSize(15);
        rootText.setTextColor(textColor);
        rootText.setPadding(0, 0, 0, dp(16));
        root.addView(rootText);

        // 磁贴状态
        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTextColor(textColor);
        statusText.setPadding(0, 0, 0, dp(24));
        root.addView(statusText);

        Button btnOff = new Button(this);
        btnOff.setText(R.string.btn_off);
        btnOff.setOnClickListener(v -> {
            if (!ScreenController.isRootAvailable()) {
                showNoRoot();
                return;
            }
            boolean ok = new ScreenController(this).turnScreenOff();
            showResult(getString(R.string.result_off), ok);
            handler.postDelayed(this::refreshStatus, 800);
        });
        root.addView(btnOff);

        Button btnOn = new Button(this);
        btnOn.setText(R.string.btn_on);
        btnOn.setOnClickListener(v -> {
            new ScreenController(this).turnScreenOn();
            refreshStatus();
        });
        root.addView(btnOn);

        Button btnTile = new Button(this);
        btnTile.setText(R.string.btn_add_tile);
        btnTile.setOnClickListener(v -> ScreenOffTileService.requestAdd(this));
        root.addView(btnTile);

        TextView hint = new TextView(this);
        hint.setText(R.string.tile_hint);
        hint.setTextSize(13);
        hint.setTextColor(subColor);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshStatus() {
        boolean root = ScreenController.isRootAvailable();
        rootText.setText(root ? getString(R.string.root_ok) : getString(R.string.root_missing));

        boolean off = new ScreenController(this).isScreenOff();
        statusText.setText(off ? getString(R.string.status_off) : getString(R.string.status_on));
    }

    private void showNoRoot() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.root_missing)
            .setMessage(R.string.root_missing_detail)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showResult(String msg, boolean ok) {
        new AlertDialog.Builder(this)
            .setTitle(ok ? R.string.dialog_ok : R.string.dialog_fail)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
