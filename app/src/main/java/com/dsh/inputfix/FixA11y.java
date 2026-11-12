package com.dsh.inputfix;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只做三件事：找到输入框、真实点它、读它的状态。
 *
 * 它不负责输入 —— AccessibilityNodeInfo.getInputConnection()
 * 对第三方 App 恒为 null，无障碍拿不到 IME 的输入通道。
 */
public class FixA11y extends AccessibilityService {

    private static volatile FixA11y sInstance = null;

    public static FixA11y get() {
        return sInstance;
    }

    public static boolean alive() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        // 无障碍服务由系统长期绑定，把桥挂在这里 = 进程常驻。
        // 用户日常用搜狗打字也没关系，只要无障碍开着，inbox 就一直有人扫。
        Api.start();
        FileBridge.start();
        android.util.Log.i("InputFixA11y", "connected: api+bridge started");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 主动 collect()，不依赖事件流
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    // ── 节点模型 ──────────────────────────────────────────────────

    public static final class Field {
        public int index;
        public String text = "";
        public String hint = "";
        public boolean focused;
        public boolean selected;
        public int left, top, right, bottom;
        public String cls = "";
        public String pkg = "";

        public int cx() { return (left + right) / 2; }

        public int cy() { return (top + bottom) / 2; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("index", Integer.valueOf(index));
            m.put("text", text);
            m.put("hint", hint);
            m.put("focused", Boolean.valueOf(focused));
            m.put("selected", Boolean.valueOf(selected));
            Map<String, Object> b = new LinkedHashMap<String, Object>();
            b.put("l", Integer.valueOf(left));
            b.put("t", Integer.valueOf(top));
            b.put("r", Integer.valueOf(right));
            b.put("b", Integer.valueOf(bottom));
            m.put("bounds", b);
            m.put("cx", Integer.valueOf(cx()));
            m.put("cy", Integer.valueOf(cy()));
            m.put("class", cls);
            m.put("package", pkg);
            return m;
        }
    }

    // ── 查询 ─────────────────────────────────────────────────────

    public List<Field> collect() {
        List<Field> out = new ArrayList<Field>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        walk(root, out, 0);
        for (int i = 0; i < out.size(); i++) out.get(i).index = i;
        return out;
    }

    private void walk(AccessibilityNodeInfo node, List<Field> out, int depth) {
        if (node == null || depth > 48) return;
        try {
            if (node.isEditable() && node.isVisibleToUser()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    Field f = new Field();
                    f.index = out.size();
                    CharSequence t = node.getText();
                    f.text = t == null ? "" : t.toString();
                    CharSequence h = node.getHintText();
                    f.hint = h == null ? "" : h.toString();
                    f.focused = node.isFocused();
                    f.selected = node.isSelected();
                    f.left = r.left;
                    f.top = r.top;
                    f.right = r.right;
                    f.bottom = r.bottom;
                    CharSequence cn = node.getClassName();
                    f.cls = cn == null ? "" : cn.toString();
                    CharSequence pk = node.getPackageName();
                    f.pkg = pk == null ? "" : pk.toString();
                    out.add(f);
                }
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) walk(child, out, depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按文字点一个节点。
     *
     * 关键：不能只看 getRootInActiveWindow()。输入法选择器弹出时，
     * 活跃窗口往往还是 IME 自己那个，选择器在另一个窗口里。
     * 所以必须遍历 getWindows()。
     */
    public boolean tapByText(String needle) {
        // 1) 所有窗口挨个找
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                    if (w == null) continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    if (tryTapIn(root, needle)) return true;
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) 兜底：活跃窗口
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && tryTapIn(root, needle)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean tryTapIn(AccessibilityNodeInfo root, String needle) {
        try {
            java.util.List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByText(needle);
            if (hits == null || hits.isEmpty()) return false;
            for (AccessibilityNodeInfo n : hits) {
                if (n == null) continue;
                // 自己可点就自己点
                if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                // 往上找可点的父
                AccessibilityNodeInfo p = n.getParent();
                for (int i = 0; i < 8 && p != null; i++) {
                    if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                    p = p.getParent();
                }
                // 最后用坐标硬点
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!r.isEmpty() && tapAt(r.exactCenterX(), r.exactCenterY(), 60)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 截当前屏幕，存成 PNG。不需要 MediaProjection 授权 ——
     * AccessibilityService.takeScreenshot() 是 API 30+ 自带的能力。
     *
     * 给 dsh-go 当眼睛用：AI 看图判断坐标，再调 tapxy 点。
     */
    public void screenshotTo(final java.io.File outFile, final Callback cb) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            cb.done(false, "需要 Android 11+");
            return;
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            android.graphics.Bitmap bmp = null;
                            try {
                                android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                                bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                                        hb, result.getColorSpace());
                                if (bmp == null) {
                                    cb.done(false, "wrapHardwareBuffer 返回 null");
                                    return;
                                }
                                bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                java.io.File dir = outFile.getParentFile();
                                if (dir != null) dir.mkdirs();
                                java.io.FileOutputStream fos =
                                        new java.io.FileOutputStream(outFile);
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                                fos.flush();
                                fos.close();
                                cb.done(true, outFile.getAbsolutePath()
                                        + "|" + bmp.getWidth() + "x" + bmp.getHeight());
                            } catch (Throwable t) {
                                cb.done(false, "保存失败: " + t);
                            } finally {
                                if (bmp != null) bmp.recycle();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            cb.done(false, "takeScreenshot 失败, code=" + errorCode);
                        }
                    });
        } catch (Throwable t) {
            cb.done(false, "调用失败: " + t);
        }
    }

    public interface Callback {
        void done(boolean ok, String info);
    }

    /** 当前焦点输入框的快照，用来判断"能不能开始输入"。 */
    public Map<String, Object> focusedSnapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo found = null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4000) {
            AccessibilityNodeInfo n = queue.pollFirst();
            if (n == null) continue;
            try {
                if (n.isEditable() && n.isFocused()) {
                    found = n;
                    break;
                }
                int cn = n.getChildCount();
                for (int i = 0; i < cn; i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) queue.add(c);
                }
            } catch (Throwable ignored) {
            }
        }
        if (found == null) return null;
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            CharSequence t = found.getText();
            CharSequence h = found.getHintText();
            m.put("text", t == null ? "" : t.toString());
            m.put("hint", h == null ? "" : h.toString());
            m.put("focused", Boolean.valueOf(found.isFocused()));
            m.put("editable", Boolean.valueOf(found.isEditable()));
            m.put("selectionStart", Integer.valueOf(found.getTextSelectionStart()));
            m.put("selectionEnd", Integer.valueOf(found.getTextSelectionEnd()));
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 触摸 ─────────────────────────────────────────────────────

    /**
     * 真实触摸点一下（按住 60ms）。
     *
     * 必须用 dispatchGesture，不能只 performAction(ACTION_CLICK)：
     * WebView 的 focus 流程只在真实 down/up 里跑完整，
     * ACTION_CLICK 常常只触发 JS 的 click，不给输入框焦点、不唤起 IME。
     */
    public boolean tapReal(Field f) {
        Path p = new Path();
        p.moveTo(f.cx(), f.cy());
        return dispatch(p, 60);
    }

    public boolean tapAt(float x, float y, long holdMs) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(p, holdMs);
    }

    public boolean longPressAt(float x, float y, long holdMs) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(p, holdMs);
    }

    private boolean dispatch(Path path, long holdMs) {
        try {
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, holdMs))
                    .build();
            return dispatchGesture(g, null, null);
        } catch (Throwable t) {
            return false;
        }
    }
}
