package com.dsh.inputfix;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 核心：把"灌文本"换成"真打字"。
 *
 * ACTION_SET_TEXT / 剪贴板粘贴在 WebView 里只改 Java 侧 Editable，
 * 不 dispatch input 事件，页面 state 记不到。
 * 只有 InputConnection.commitText() 走 Blink 的 InsertTextCommand，
 * input 事件必然发出。
 */
public final class Bridge {

    private static final int READ_LIMIT = 8192;

    private static volatile InputConnection sConn = null;
    private static volatile String sEditor = "";
    private static volatile long sAttachedAt = 0L;
    private static final AtomicReference<CountDownLatch> sLatch =
            new AtomicReference<CountDownLatch>(null);

    private Bridge() {}

    public static final class FillResult {
        public final boolean ok;
        public final String written;
        public final String actual;
        public final int chars;
        public final String mode;
        public final int selectionStart;
        public final int selectionEnd;
        public final String error;

        public FillResult(boolean ok, String written, String actual, int chars,
                          String mode, int selectionStart, int selectionEnd, String error) {
            this.ok = ok;
            this.written = written;
            this.actual = actual;
            this.chars = chars;
            this.mode = mode;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
            this.error = error;
        }
    }

    // ── IME 生命周期 ──────────────────────────────────────────────

    /** 新的一轮输入会话开始：旧连接一律作废，等 onImeAttached 重新给。 */
    public static void onInputStart() {
        sConn = null;
        sEditor = "";
        sAttachedAt = 0L;
    }

    public static void onImeAttached(InputConnection c, EditorInfo info) {
        if (c == null) {
            // 连接还没建好。不要保留旧值 —— 那会把上一轮的连接当成这一轮的。
            sConn = null;
            return;
        }
        sConn = c;
        sEditor = describe(info);
        sAttachedAt = System.currentTimeMillis();
        CountDownLatch l = sLatch.getAndSet(null);
        if (l != null) l.countDown();
    }

    public static void onImeDetached() {
        sConn = null;
        sEditor = "";
        sAttachedAt = 0L;
    }

    public static boolean isReady() {
        return sConn != null;
    }

    public static Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("imeReady", Boolean.valueOf(sConn != null));
        m.put("editor", sEditor);
        m.put("attachedForMs", Long.valueOf(sAttachedAt == 0L ? 0L
                : System.currentTimeMillis() - sAttachedAt));
        return m;
    }

    public static boolean awaitIme(long timeoutMs) {
        if (sConn != null) return true;
        CountDownLatch l = new CountDownLatch(1);
        sLatch.set(l);
        boolean got;
        try {
            got = l.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            got = false;
        }
        sLatch.compareAndSet(l, null);
        return got && sConn != null;
    }

    public static void reset() {
        sConn = null;
        sEditor = "";
    }

    // ── 真正的输入 ────────────────────────────────────────────────

    public static FillResult fill(String text, boolean clearFirst,
                                  long perCharDelayMs, long settleMs, boolean kick) {
        InputConnection c = sConn;
        if (c == null) {
            return new FillResult(false, "", "", 0, "none", -1, -1,
                    "IME 未连接：目标输入框没有拿到焦点。先 tap 输入框再调用。");
        }
        if (text == null) text = "";

        if (clearFirst) {
            clearAll(c);
            sleep(60);
        }

        int written = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String s = String.valueOf(ch);
            boolean ok;
            try {
                ok = c.commitText(s, 1);
            } catch (Throwable t) {
                return new FillResult(false, text.substring(0, written), "", written,
                        "commitText", -1, -1, "commitText 抛异常: " + t.getMessage());
            }
            if (!ok) {
                return new FillResult(false, text.substring(0, written), "", written,
                        "commitText", selStart(), selEnd(),
                        "commitText 返回 false（输入框可能已失焦）");
            }
            written++;
            if (perCharDelayMs > 0) sleep(perCharDelayMs);
        }

        if (kick && text.length() > 0) {
            String last = text.substring(text.length() - 1);
            deleteBackward(c, 1);
            sleep(25);
            c.commitText(last, 1);
            sleep(25);
        }

        pinCaretToEnd(c, text);
        sleep(settleMs);

        String actual = currentText(c);
        boolean matched = actual.equals(text) || actual.endsWith(text);
        return new FillResult(
                matched, text, actual, written,
                kick ? "commitText+kick" : "commitText",
                selStart(), selEnd(),
                matched ? null : "回读不一致：框内为「" + actual + "」");
    }

    public static boolean clear() {
        InputConnection c = sConn;
        if (c == null) return false;
        clearAll(c);
        sleep(80);
        return currentText(c).length() == 0;
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private static void clearAll(InputConnection c) {
        int before = lenOf(c.getTextBeforeCursor(READ_LIMIT, 0));
        int after = lenOf(c.getTextAfterCursor(READ_LIMIT, 0));
        int total = before + after;
        if (total <= 0) return;

        c.setSelection(0, total);
        sleep(40);
        c.commitText("", 1);
        sleep(40);

        int left = lenOf(c.getTextBeforeCursor(READ_LIMIT, 0))
                + lenOf(c.getTextAfterCursor(READ_LIMIT, 0));
        if (left > 0) {
            c.setSelection(0, left);
            sleep(25);
            downUp(c, KeyEvent.KEYCODE_DEL);
            sleep(60);
        }
    }

    private static void deleteBackward(InputConnection c, int n) {
        for (int i = 0; i < n; i++) downUp(c, KeyEvent.KEYCODE_DEL);
    }

    private static void pinCaretToEnd(InputConnection c, String expected) {
        int len = lenOf(c.getTextBeforeCursor(READ_LIMIT, 0));
        if (len < 0) len = expected == null ? 0 : expected.length();
        c.setSelection(len, len);
    }

    private static String currentText(InputConnection c) {
        CharSequence before = null;
        CharSequence after = null;
        try {
            before = c.getTextBeforeCursor(READ_LIMIT, 0);
            after = c.getTextAfterCursor(64, 0);
        } catch (Throwable ignored) {
        }
        return (before == null ? "" : before.toString())
                + (after == null ? "" : after.toString());
    }

    private static int selStart() {
        try {
            InputConnection c = sConn;
            if (c == null) return -1;
            ExtractedText et = c.getExtractedText(new ExtractedTextRequest(), 0);
            return et == null ? -1 : et.selectionStart;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int selEnd() {
        try {
            InputConnection c = sConn;
            if (c == null) return -1;
            ExtractedText et = c.getExtractedText(new ExtractedTextRequest(), 0);
            return et == null ? -1 : et.selectionEnd;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int lenOf(CharSequence cs) {
        return cs == null ? 0 : cs.length();
    }

    private static void downUp(InputConnection c, int code) {
        try {
            c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Throwable ignored) {
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(EditorInfo info) {
        if (info == null) return "";
        int cls = info.inputType & android.text.InputType.TYPE_MASK_CLASS;
        String kind;
        switch (cls) {
            case android.text.InputType.TYPE_CLASS_TEXT: kind = "text"; break;
            case android.text.InputType.TYPE_CLASS_NUMBER: kind = "number"; break;
            case android.text.InputType.TYPE_CLASS_PHONE: kind = "phone"; break;
            default: kind = "other"; break;
        }
        return (info.packageName == null ? "?" : info.packageName) + "/" + kind;
    }
}
