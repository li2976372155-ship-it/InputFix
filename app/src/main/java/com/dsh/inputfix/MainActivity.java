package com.dsh.inputfix;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

public class MainActivity extends Activity {

    private TextView status;
    private volatile boolean alive = true;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Api.start();
        startBridge();
        setContentView(build());

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (alive && !isFinishing()) {
                    Bridge.sleep(1000);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (alive && !isFinishing()) refresh();
                        }
                    });
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 拉起前台服务，让 8791 常驻；顺便申请通知权限（Android 13+）。 */
    private void startBridge() {
        try {
            Intent svc = new Intent(this, FixService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) {
            android.util.Log.e("InputFix", "startBridge failed: " + t);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    private ScrollView build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(48));

        TextView title = new TextView(this);
        title.setText("InputFix");
        title.setTextSize(26f);
        title.setTextColor(Color.parseColor("#111111"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("把「粘贴」换成「真打字」。专治 WebView 填空不保存。");
        sub.setTextSize(13f);
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setPadding(0, dp(6), 0, dp(24));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(12f);
        status.setTextColor(Color.parseColor("#333333"));
        status.setBackgroundColor(Color.parseColor("#F2F2F2"));
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setTypeface(Typeface.MONOSPACE);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(makeButton("① 启用 InputFix 输入法", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        }));

        root.addView(makeButton("② 切换到 InputFix（当前输入法）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }
        }));

        root.addView(makeButton("③ 开启无障碍（定位输入框用）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));

        root.addView(makeButton("自检：填一次当前焦点框", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final Bridge.FillResult r =
                                Bridge.fill("输入测试 abc123", true, 45, 200, true);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                StringBuilder sb = new StringBuilder();
                                sb.append("自检结果：").append(r.ok ? "通过" : "失败").append('\n');
                                sb.append("mode     = ").append(r.mode).append('\n');
                                sb.append("written  = ").append(r.written).append('\n');
                                sb.append("actual   = ").append(r.actual).append('\n');
                                sb.append("selStart = ").append(r.selectionStart).append('\n');
                                sb.append("selEnd   = ").append(r.selectionEnd).append('\n');
                                if (r.error != null) sb.append("error    = ").append(r.error);
                                status.setText(sb.toString());
                            }
                        });
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        }));

        root.addView(makeButton("刷新状态", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        return sv;
    }

    private Button makeButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13f);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        Map<String, Object> ime = Bridge.status();
        String focusText = "无";
        String sel = "";
        FixA11y a = FixA11y.get();
        if (a != null) {
            Map<String, Object> snap = a.focusedSnapshot();
            if (snap != null) {
                focusText = String.valueOf(snap.get("text"));
                sel = snap.get("selectionStart") + " → " + snap.get("selectionEnd");
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("API       127.0.0.1:").append(Api.PORT)
                .append(Api.isRunning() ? "  (运行中)" : "  (已停止)").append('\n');
        sb.append("IME ready ").append(ime.get("imeReady"))
                .append("  editor=").append(ime.get("editor")).append('\n');
        sb.append("无障碍     ").append(FixA11y.alive() ? "已连接" : "没开").append('\n');
        sb.append("焦点框     ").append(focusText).append('\n');
        if (sel.length() > 0) sb.append("selection  ").append(sel);
        status.setText(sb.toString());
    }
}
