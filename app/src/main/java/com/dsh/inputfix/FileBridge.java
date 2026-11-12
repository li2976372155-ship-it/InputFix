package com.dsh.inputfix;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * 文件桥 —— 当 proot 的网络命名空间和 Android 隔离时的唯一通路。
 *
 * proot 里 TCP 能握手但 HTTP 无响应，说明 8791 被 proot 自己的东西占着，
 * InputFix 的监听根本没桥接进去。但 /sdcard 是共享的，所以改用文件。
 *
 *   /sdcard/Download/InputFix/inbox/<id>.json     ← AI 写请求
 *   /sdcard/Download/InputFix/outbox/<id>.json    ← InputFix 写结果
 *   /sdcard/Download/InputFix/status.json         ← 实时状态（每 500ms 刷新）
 *   /sdcard/Download/InputFix/bridge.log          ← 事件日志
 *
 * 请求：
 *   {"op":"fill","index":0,"text":"以人民为中心","kick":true}
 *   {"op":"tap","index":0}
 *   {"op":"fields"}
 *   {"op":"status"}
 *   {"op":"clear","index":0}
 *
 * 轮询 250ms，处理完删除请求文件。结果文件保留，同名 id 会覆盖。
 */
public final class FileBridge {

    private static final String TAG = "InputFixBridge";
    private static final String BASE = "/sdcard/Download/InputFix";
    private static final String INBOX = BASE + "/inbox";
    private static final String OUTBOX = BASE + "/outbox";

    private static volatile boolean sRunning = false;
    private static volatile String sLastError = "";

    private FileBridge() {
    }

    public static void start() {
        if (sRunning) return;
        sRunning = true;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ensureDirs();
                long lastStatus = 0L;
                while (sRunning) {
                    try {
                        File dir = new File(INBOX);
                        File[] files = dir.listFiles();
                        if (files != null && files.length > 0) {
                            for (File f : files) {
                                if (f.isFile() && f.getName().endsWith(".json")) {
                                    handle(f);
                                }
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastStatus > 500) {
                            writeStatus();
                            lastStatus = now;
                        }
                        Thread.sleep(250);
                    } catch (Throwable t2) {
                        sLastError = String.valueOf(t2);
                        Bridge.sleep(500);
                    }
                }
            }
        });
        t.setName("inputfix-filebridge");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "file bridge started at " + BASE);
    }

    private static void ensureDirs() {
        try {
            new File(INBOX).mkdirs();
            new File(OUTBOX).mkdirs();
            log("bridge ready");
        } catch (Throwable t) {
            sLastError = "mkdir failed: " + t;
        }
    }

    // ── 请求处理 ──────────────────────────────────────────────────

    private static void handle(File f) {
        String id = f.getName().substring(0, f.getName().length() - 5);
        JSONObject res;
        try {
            String raw = readAll(f);
            JSONObject req = new JSONObject(raw);
            res = dispatch(req);
        } catch (Throwable t) {
            res = err("处理失败: " + t);
        }

        try {
            res.put("id", id);
            res.put("ts", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }

        writeResult(id, res);
        log(id + " -> " + (res.optBoolean("ok") ? "ok" : "fail"));
        // 删请求文件，避免重复执行
        if (!f.delete()) {
            try {
                new FileOutputStream(f).close();
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    private static JSONObject dispatch(JSONObject req) throws Exception {
        String op = req.optString("op", "status");

        if ("status".equals(op)) {
            return statusJson();
        }
        if ("fields".equals(op)) {
            return fieldsJson();
        }
        if ("focus".equals(op)) {
            FixA11y a = FixA11y.get();
            if (a == null) return err("无障碍服务未开启");
            Map<String, Object> snap = a.focusedSnapshot();
            if (snap == null) return new JSONObject().put("ok", true).put("focused", false);
            return new JSONObject().put("ok", true).put("focused", true)
                    .put("node", new JSONObject(snap));
        }
        if ("tap".equals(op)) {
            FixA11y a = FixA11y.get();
            if (a == null) return err("无障碍服务未开启");
            int index = req.optInt("index", 0);
            List<FixA11y.Field> list = a.collect();
            if (index < 0 || index >= list.size()) {
                return err("没有第 " + index + " 个输入框（共 " + list.size() + " 个）");
            }
            boolean done = a.tapReal(list.get(index));
            Bridge.reset();
            return new JSONObject().put("ok", done).put("tapped", done)
                    .put("index", index);
        }
        // 按坐标点：{"op":"tapxy","x":720,"y":260}
        if ("tapxy".equals(op)) {
            FixA11y a = FixA11y.get();
            if (a == null) return err("无障碍服务未开启");
            float x = (float) req.getDouble("x");
            float y = (float) req.getDouble("y");
            long hold = req.optLong("holdMs", 60);
            boolean done = a.tapAt(x, y, hold);
            Bridge.reset();
            return new JSONObject().put("ok", done).put("tapped", done)
                    .put("x", x).put("y", y);
        }
        // 截图给 AI 看：{"op":"screenshot"} → 返回文件路径和像素尺寸
        if ("screenshot".equals(op)) {
            return screenshot(req);
        }
        // 按文字点（无障碍读得到的元素）
        if ("taptext".equals(op)) {
            FixA11y a = FixA11y.get();
            if (a == null) return err("无障碍服务未开启");
            String t = req.optString("text", "");
            if (t.length() == 0) return err("text 为空");
            boolean done = a.tapByText(t);
            Bridge.reset();
            return new JSONObject().put("ok", done).put("tapped", done).put("text", t);
        }
        if ("clear".equals(op)) {
            return new JSONObject().put("ok", Bridge.clear()).put("cleared", true);
        }
        if ("activate".equals(op)) {
            return activate(req);
        }
        if ("deactivate".equals(op)) {
            return deactivate(req);
        }
        // 协作协议：Agent 说"我要填了"，InputFix 负责把用户引导到位
        if ("prepare".equals(op)) {
            return prepare(req);
        }
        if ("fill".equals(op)) {
            String text = req.optString("text", "");
            if (text.length() == 0) return err("text 为空");
            boolean kick = req.optBoolean("kick", true);
            boolean clearFirst = req.optBoolean("clear", true);
            long delay = req.optLong("delayMs", 45);

            if (!Bridge.isReady()) {
                // 没焦点时给一次硬等，很多场景是刚 tap 完还没接上
                Bridge.awaitIme(req.optLong("focusWaitMs", 2000));
            }
            if (!Bridge.isReady()) {
                return err("IME 未连接：先 tap 一个输入框，让它拿到焦点");
            }

            Bridge.FillResult r = Bridge.fill(text, clearFirst, delay, 200, kick);
            JSONObject o = new JSONObject()
                    .put("ok", r.ok)
                    .put("written", r.written)
                    .put("actual", r.actual)
                    .put("chars", r.chars)
                    .put("mode", r.mode)
                    .put("selectionStart", r.selectionStart)
                    .put("selectionEnd", r.selectionEnd);
            if (!r.ok && r.error != null) o.put("error", r.error);
            return o;
        }

        return err("未知 op: " + op);
    }

    // ── 截图 ─────────────────────────────────────────────────────

    /**
     * 截屏存成 PNG，返回路径和像素尺寸。
     *
     * 这是"图形识别点击"的识别层：
     *   screenshot → AI 看图判断目标坐标 → tapxy 点击
     *
     * 比 OCR 准，因为看图的就是干活的那个模型，不用二次翻译。
     */
    private static JSONObject screenshot(JSONObject req) throws Exception {
        final FixA11y a = FixA11y.get();
        if (a == null) return err("无障碍服务未开启（截图靠无障碍服务的能力）");

        String name = req.optString("name", "screen");
        final java.io.File out = new java.io.File(
                BASE + "/" + name + ".png");

        final Object lock = new Object();
        final boolean[] okFlag = new boolean[]{false};
        final String[] info = new String[]{""};

        a.screenshotTo(out, new FixA11y.Callback() {
            @Override
            public void done(boolean ok, String s) {
                okFlag[0] = ok;
                info[0] = s;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });

        synchronized (lock) {
            lock.wait(6000);
        }

        if (!okFlag[0]) {
            return err("截图失败: " + info[0]);
        }

        String path = out.getAbsolutePath();
        int w = 0, h = 0;
        int bar = info[0].indexOf('|');
        if (bar > 0) {
            path = info[0].substring(0, bar);
            String dim = info[0].substring(bar + 1);
            int x = dim.indexOf('x');
            try {
                w = Integer.parseInt(dim.substring(0, x));
                h = Integer.parseInt(dim.substring(x + 1));
            } catch (Throwable ignored) {
            }
        }

        return new JSONObject()
                .put("ok", true)
                .put("file", path)
                .put("width", w)
                .put("height", h)
                .put("tip", "把这张图给视觉模型看，判断目标坐标后调 tapxy");
    }

    // ── 输入法切换（需要 WRITE_SECURE_SETTINGS，adb 一次性授权）────────

    private static final String SELF_IME = "com.dsh.inputfix/.FixIme";
    private static final String PREFS = "inputfix";
    private static final String KEY_PREV = "prev_ime";

    /**
     * 切到 InputFix。
     *
     * 两条路：
     *   1. 有 WRITE_SECURE_SETTINGS → 直接写 default_input_method（最快）
     *   2. 没有 → 弹系统输入法选择器，让无障碍点 "InputFix"
     *
     * Android 16 已经禁止 adb 授予 WRITE_SECURE_SETTINGS，所以无 root 机器
     * 实际只能走第 2 条，且必须开启无障碍。
     */
    private static JSONObject activate(JSONObject req) throws Exception {
        android.content.Context ctx = FixApplication.ctx();
        if (ctx == null) return err("没有 Context");

        String cur = readIme(ctx);
        if (SELF_IME.equals(cur)) {
            boolean rdy = waitReady(req.optLong("waitMs", 4000));
            return new JSONObject().put("ok", rdy).put("ime", cur)
                    .put("imeReady", rdy).put("switched", false);
        }

        savePrev(ctx, cur);

        // ── 路线 1：直接写 secure settings ──
        boolean put = false;
        String permErr = null;
        try {
            put = android.provider.Settings.Secure.putString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, SELF_IME);
        } catch (Throwable t) {
            permErr = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (put && SELF_IME.equals(readIme(ctx))) {
            boolean rdy = waitReady(req.optLong("waitMs", 6000));
            return new JSONObject().put("ok", rdy).put("method", "secure_settings")
                    .put("ime", SELF_IME).put("imeReady", rdy);
        }

        // ── 路线 2：弹选择器（不需要无障碍，纯粹 startActivity）──
        // 注意：弹出和"自动点"是两件事。弹出只靠 PickerActivity，
        // 无障碍只用来尝试帮忙点一下（通常点不动，见下方 picked）。
        boolean popped = false;
        try {
            android.content.Intent it = new android.content.Intent(
                    ctx, PickerActivity.class);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            popped = true;
        } catch (Throwable t) {
            permErr = (permErr == null ? "" : permErr + " | ") + "picker: " + t;
        }

        // 无障碍开着的话，顺手试一次自动点（大概率点不动，但不试白不试）
        FixA11y a = FixA11y.get();
        boolean picked = false;
        if (popped && a != null) {
            Bridge.sleep(1200);
            picked = a.tapByText("InputFix");
            Bridge.sleep(800);
        }

        boolean rdy = waitReady(req.optLong("waitMs", 6000));
        String nowIme = readIme(ctx);
        boolean ok = SELF_IME.equals(nowIme) && rdy;

        JSONObject o = new JSONObject()
                .put("ok", ok)
                .put("method", popped ? "picker" : "none")
                .put("ime", nowIme)
                .put("imeReady", rdy)
                .put("picked", picked);
        if (!ok) {
            o.put("hint",
                    "自动切换做不到，别再试了。实测结论："
                    + "(1) Android 16 禁止 adb 授予 WRITE_SECURE_SETTINGS；"
                    + "(2) 无障碍 dispatchGesture 点不动系统输入法选择器（试过多个坐标，全部无效）；"
                    + "(3) 输入法选择器是安全对话框，不受无障碍手势控制。"
                    + "正确做法：请用户长按空格手动切到 InputFix，然后重试 fill。"
                    + "prepare 已经弹过通知了，通知对应用户来说就是「点一下」的入口。");
        }
        if (permErr != null) o.put("permError", permErr);
        return o;
    }

    private static String readIme(android.content.Context ctx) {
        try {
            return android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(),
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void savePrev(android.content.Context ctx, String cur) {
        try {
            ctx.getSharedPreferences(PREFS, 0).edit()
                    .putString(KEY_PREV, cur == null ? "" : cur).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 协作协议的核心：Agent 说「我马上要往输入框里写字了」。
     *
     * InputFix 能做的：
     *   - 如果已经是当前输入法 → 直接回 ready:true，Agent 可以 fill 了
     *   - 如果不是 → 弹一条高优先级通知，用户点一下就能切过去
     *
     * 为什么不能让 InputFix 直接切：Android 16 拿不到 WRITE_SECURE_SETTINGS，
     * 系统选择器又不接受无障碍手势。所以这一步必须由人点。
     * 但可以把「用户要自己想起该切了」变成「工具主动提醒」。
     *
     * 请求：
     *   {"op":"prepare","reason":"正在填学习通填空题","timeoutMs":60000}
     * 返回：
     *   {"ok":true,"ready":true}                    已经是 IME，直接 fill
     *   {"ok":true,"ready":false,"needUser":true}   已发通知，等用户点
     */
    private static JSONObject prepare(JSONObject req) throws Exception {
        android.content.Context ctx = FixApplication.ctx();
        if (ctx == null) return err("没有 Context");

        String cur = readIme(ctx);
        if (SELF_IME.equals(cur)) {
            return new JSONObject().put("ok", true).put("ready", true)
                    .put("needUser", false).put("ime", cur);
        }

        String reason = req.optString("reason", "需要精确写入输入框");
        boolean shown = PrepareNotifier.show(ctx, reason);

        return new JSONObject()
                .put("ok", true)
                .put("ready", false)
                .put("needUser", true)
                .put("notified", shown)
                .put("ime", cur)
                .put("hint", shown
                        ? "已通知用户。请轮询 status，等 ime.imeReady 变 true 再 fill"
                        : "通知发不出去（可能没给通知权限）。改为提示用户手动长按空格切到 InputFix");
    }

    /**
     * 切回日常输入法。
     *
     * 三级降级：
     *   1. InputMethodService.switchToPreviousInputMethod()  ← 不用权限，最可靠
     *   2. Settings.Secure 直写（有 WRITE_SECURE_SETTINGS 时才可用）
     *   3. 弹选择器（基本点不动，仅作最后尝试）
     */
    private static JSONObject deactivate(JSONObject req) throws Exception {
        android.content.Context ctx = FixApplication.ctx();
        if (ctx == null) return err("没有 Context");

        // 收工了，把「要填了」那条提醒撤掉
        PrepareNotifier.clear(ctx);

        String before = readIme(ctx);

        // ── 路线 1：IME 自己的 API，不需要任何权限 ──
        boolean switched = FixIme.switchBack();
        if (switched) {
            Bridge.sleep(800);
            String after1 = readIme(ctx);
            if (!SELF_IME.equals(after1)) {
                return new JSONObject().put("ok", true)
                        .put("method", "switchToPreviousInputMethod")
                        .put("from", before).put("ime", after1);
            }
        }

        // ── 路线 2：直写 secure settings ──
        String prev = ctx.getSharedPreferences(PREFS, 0)
                .getString(KEY_PREV, req.optString("fallback", ""));
        if (prev == null || prev.length() == 0) {
            prev = req.optString("fallback", "");
        }
        if (prev.length() > 0) {
            try {
                boolean put = android.provider.Settings.Secure.putString(
                        ctx.getContentResolver(),
                        android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, prev);
                if (put && !SELF_IME.equals(readIme(ctx))) {
                    return new JSONObject().put("ok", true)
                            .put("method", "secure_settings").put("ime", readIme(ctx));
                }
            } catch (Throwable ignored) {
            }
        }

        return new JSONObject()
                .put("ok", !SELF_IME.equals(readIme(ctx)))
                .put("method", switched ? "switch_back_failed" : "none")
                .put("ime", readIme(ctx))
                .put("hint", "switchToPreviousInputMethod 返回 false。"
                        + "常见原因：输入法列表里只有 InputFix 一个，"
                        + "或者系统不支持在 IME 内切换。");
    }

    private static boolean waitReady(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (Bridge.isReady()) return true;
            Bridge.sleep(100);
        }
        return Bridge.isReady();
    }

    // ── 状态 ─────────────────────────────────────────────────────

    private static JSONObject statusJson() throws Exception {
        Map<String, Object> ime = Bridge.status();
        JSONObject imeJson = new JSONObject();
        imeJson.put("imeReady", ime.get("imeReady"));
        imeJson.put("editor", ime.get("editor"));
        imeJson.put("attachedForMs", ime.get("attachedForMs"));

        return new JSONObject()
                .put("ok", true)
                .put("transport", "file")
                .put("base", BASE)
                .put("ime", imeJson)
                .put("a11y", FixA11y.alive())
                .put("piid", android.os.Process.myPid())
                .put("lastError", sLastError);
    }

    private static JSONObject fieldsJson() throws Exception {
        FixA11y a = FixA11y.get();
        if (a == null) return err("无障碍服务未开启");
        List<FixA11y.Field> list = a.collect();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            arr.put(new JSONObject(list.get(i).toMap()));
        }
        return new JSONObject().put("ok", true).put("count", list.size()).put("fields", arr);
    }

    private static void writeStatus() {
        try {
            File f = new File(BASE + "/status.json");
            String s = statusJson().toString(2);
            writeAll(f, s);
        } catch (Throwable t) {
            sLastError = "status write: " + t;
        }
    }

    private static void writeResult(String id, JSONObject res) {
        try {
            writeAll(new File(OUTBOX + "/" + id + ".json"), res.toString(2));
        } catch (Throwable t) {
            sLastError = "result write: " + t;
        }
    }

    // ── IO ───────────────────────────────────────────────────────

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeAll(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
            out.flush();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static void log(String msg) {
        try {
            File f = new File(BASE + "/bridge.log");
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                String line = System.currentTimeMillis() + "  " + msg + "\n";
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, msg);
    }

    private static JSONObject err(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }
}
