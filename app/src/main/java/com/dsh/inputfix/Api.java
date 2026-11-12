package com.dsh.inputfix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/**
 * 127.0.0.1:8791 —— 给 dsh-go 的 bash / curl 和 PC 脚本用的本地 API。
 *
 *   GET  /ping
 *   GET  /status
 *   GET  /fields
 *   GET  /focus
 *   POST /fill      {index, text, clear, delayMs, kick, focusWaitMs}
 *   POST /answer    {answers:[{index,text}], ...}
 *   POST /clear     {index}
 *   POST /tap       {index} 或 {x, y, holdMs}
 *   POST /ime/reset
 */
public final class Api {

    public static final int PORT = 8791;

    private static volatile boolean sRunning = false;
    private static volatile ServerSocket sServer = null;

    private Api() {}

    public static boolean isRunning() {
        return sRunning;
    }

    public static void start() {
        if (sRunning) return;
        sRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 绑所有接口：proot / 其它 UID 里访问 127.0.0.1 可能命中的
                    // 不是本进程的 socket，所以必须同时能被局域网 IP 访问。
                    ServerSocket s = new ServerSocket(PORT, 32);
                    sServer = s;
                    while (sRunning) {
                        Socket sock;
                        try {
                            sock = s.accept();
                        } catch (Throwable e) {
                            break;
                        }
                        final Socket fs = sock;
                        Thread w = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handle(fs);
                            }
                        });
                        w.setDaemon(true);
                        w.start();
                    }
                } catch (Throwable e) {
                    sRunning = false;
                }
            }
        });
        t.setName("inputfix-api");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        sRunning = false;
        try {
            if (sServer != null) sServer.close();
        } catch (Throwable ignored) {
        }
        sServer = null;
    }

    // ── 请求处理 ──────────────────────────────────────────────────

    private static void handle(Socket sock) {
        try {
            InputStream in = sock.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int contentLength = 0;
            while (true) {
                String h = readLine(in);
                if (h == null) break;
                if (h.isEmpty()) break;
                if (h.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(h.substring(15).trim());
                    } catch (Throwable ignored) {
                    }
                }
            }

            String body = "";
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n <= 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, "UTF-8");
            }

            JSONObject out = route(method, path, body);
            write(sock.getOutputStream(), out);
        } catch (Throwable ignored) {
        } finally {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static JSONObject route(String method, String path, String body) {
        try {
            if ("GET".equals(method) && "/ping".equals(path)) {
                return ok().put("app", "inputfix").put("version", 1);
            }
            if ("GET".equals(method) && "/status".equals(path)) {
                return ok()
                        .put("ime", mapToJson(Bridge.status()))
                        .put("a11y", FixA11y.alive())
                        .put("apiPort", PORT)
                        .put("bind", "0.0.0.0")
                        .put("addrs", lanAddrs())
                        .put("running", sRunning);
            }
            if ("GET".equals(method) && "/fields".equals(path)) {
                return fields();
            }
            if ("GET".equals(method) && "/focus".equals(path)) {
                FixA11y a = FixA11y.get();
                if (a == null) return err("无障碍服务未开启");
                Map<String, Object> snap = a.focusedSnapshot();
                if (snap == null) return ok().put("focused", false);
                return ok().put("focused", true).put("node", mapToJson(snap));
            }
            if ("POST".equals(method) && "/fill".equals(path)) {
                return fill(parse(body));
            }
            if ("POST".equals(method) && "/answer".equals(path)) {
                return answer(parse(body));
            }
            if ("POST".equals(method) && "/clear".equals(path)) {
                return clear(parse(body));
            }
            if ("POST".equals(method) && "/tap".equals(path)) {
                return tap(parse(body));
            }
            if ("POST".equals(method) && "/ime/reset".equals(path)) {
                Bridge.reset();
                return ok();
            }
            return err("no route: " + method + " " + path);
        } catch (Throwable t) {
            return err("server error: " + t);
        }
    }

    // ── 端点 ─────────────────────────────────────────────────────

    private static JSONObject fields() throws Exception {
        FixA11y a = FixA11y.get();
        if (a == null) return err("无障碍服务未开启，读不到输入框");
        List<FixA11y.Field> list = a.collect();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            arr.put(mapToJson(list.get(i).toMap()));
        }
        return ok().put("count", list.size()).put("fields", arr);
    }

    private static JSONObject tap(JSONObject req) throws Exception {
        FixA11y a = FixA11y.get();
        if (a == null) return err("无障碍服务未开启");

        if (req.has("x") && req.has("y")) {
            boolean done = a.tapAt((float) req.getDouble("x"),
                    (float) req.getDouble("y"),
                    req.optLong("holdMs", 60));
            return ok().put("tapped", done).put("mode", "xy");
        }

        int index = req.optInt("index", -1);
        if (index < 0) return err("缺少 index 或 x/y");
        List<FixA11y.Field> list = a.collect();
        FixA11y.Field f = null;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).index == index) {
                f = list.get(i);
                break;
            }
        }
        if (f == null) return err("没有第 " + index + " 个输入框");
        boolean done = a.tapReal(f);
        Bridge.reset();
        return ok().put("tapped", done).put("mode", "node")
                .put("field", mapToJson(f.toMap()));
    }

    private static JSONObject clear(JSONObject req) throws Exception {
        int index = req.optInt("index", 0);
        JSONObject prep = ensureFocus(index, req.optLong("focusWaitMs", 1500));
        if (prep != null) return prep;
        return ok().put("cleared", Bridge.clear());
    }

    private static JSONObject fill(JSONObject req) throws Exception {
        String text = req.optString("text", "");
        if (text.length() == 0) return err("text 为空");

        int index = req.optInt("index", 0);
        boolean clearFirst = req.optBoolean("clear", true);
        long delay = req.optLong("delayMs", 45);
        boolean kick = req.optBoolean("kick", true);
        long focusWait = req.optLong("focusWaitMs", 1500);

        JSONObject prep = ensureFocus(index, focusWait);
        if (prep != null) return prep;

        Bridge.FillResult r = Bridge.fill(text, clearFirst, delay, 200, kick);
        JSONObject out = ok()
                .put("ok", r.ok)
                .put("written", r.written)
                .put("actual", r.actual)
                .put("chars", r.chars)
                .put("mode", r.mode)
                .put("selectionStart", r.selectionStart)
                .put("selectionEnd", r.selectionEnd);
        if (!r.ok) {
            out.put("error", r.error == null ? "输入未生效" : r.error);
            FixA11y a = FixA11y.get();
            if (a != null) {
                Map<String, Object> snap = a.focusedSnapshot();
                if (snap != null) out.put("focus", mapToJson(snap));
            }
        }
        return out;
    }

    /** 批量：{"answers":[{"index":0,"text":"..."}], "kick":true, "stopOnError":false} */
    private static JSONObject answer(JSONObject req) throws Exception {
        JSONArray arr = req.optJSONArray("answers");
        if (arr == null) return err("缺少 answers 数组");

        long delay = req.optLong("delayMs", 45);
        boolean kick = req.optBoolean("kick", true);
        boolean clearFirst = req.optBoolean("clear", true);
        long focusWait = req.optLong("focusWaitMs", 1500);
        boolean stopOnError = req.optBoolean("stopOnError", false);

        JSONArray results = new JSONArray();
        int okCount = 0;
        int failCount = 0;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            int index = item.optInt("index", 0);
            String text = item.optString("text", "");
            if (text.length() == 0) continue;

            JSONObject entry = new JSONObject()
                    .put("index", index)
                    .put("expect", text);
            boolean good = false;

            JSONObject prep = ensureFocus(index, focusWait);
            if (prep != null) {
                entry.put("ok", false)
                        .put("error", prep.optString("error", "焦点没拿到"));
            } else {
                Bridge.FillResult r = Bridge.fill(text, clearFirst, delay, 180, kick);
                entry.put("ok", r.ok)
                        .put("actual", r.actual)
                        .put("selectionStart", r.selectionStart)
                        .put("selectionEnd", r.selectionEnd);
                if (!r.ok) entry.put("error", r.error == null ? "未生效" : r.error);
                good = r.ok;
            }

            results.put(entry);
            if (good) okCount++; else failCount++;
            if (!good && stopOnError) break;
        }

        return ok()
                .put("total", arr.length())
                .put("okCount", okCount)
                .put("failCount", failCount)
                .put("results", results);
    }

    /** 保证目标输入框已获得焦点并接上 IME。返回 null 表示就绪。 */
    private static JSONObject ensureFocus(int index, long focusWaitMs) throws Exception {
        FixA11y a = FixA11y.get();
        if (a != null) {
            List<FixA11y.Field> list = a.collect();
            FixA11y.Field f = null;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).index == index) {
                    f = list.get(i);
                    break;
                }
            }
            if (f == null && !list.isEmpty()) f = list.get(0);
            if (f != null && (!f.focused || !Bridge.isReady())) {
                a.tapReal(f);
                Bridge.reset();
                Bridge.sleep(150);
            }
        }
        if (!Bridge.awaitIme(focusWaitMs)) {
            return err("输入框没有接管 IME（等待 " + focusWaitMs + "ms 超时）。检查："
                    + "(1) InputFix 是否被选为当前输入法；"
                    + "(2) 输入框是否真的可编辑；"
                    + "(3) 先 /tap 再 /fill");
        }
        return null;
    }

    // ── 工具 ────────────────────────────────────────────────────

    private static JSONObject parse(String body) {
        try {
            if (body == null || body.trim().length() == 0) return new JSONObject();
            return new JSONObject(body);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 本机所有非回环 IPv4，方便调用方直接拿去用。 */
    private static JSONArray lanAddrs() {
        JSONArray arr = new JSONArray();
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        arr.put(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject mapToJson(Map<String, Object> map) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) {
                o.put(e.getKey(), mapToJson((Map<String, Object>) v));
            } else if (v == null) {
                o.put(e.getKey(), JSONObject.NULL);
            } else {
                o.put(e.getKey(), v);
            }
        }
        return o;
    }

    private static String readLine(InputStream in) {
        try {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b == -1) return sb.length() == 0 ? null : sb.toString();
                if (b == '\n') {
                    int len = sb.length();
                    if (len > 0 && sb.charAt(len - 1) == '\r') sb.deleteCharAt(len - 1);
                    return sb.toString();
                }
                sb.append((char) b);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(OutputStream out, JSONObject obj) throws Exception {
        byte[] bytes = obj.toString().getBytes("UTF-8");
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));
        out.write(bytes);
        out.flush();
    }
}
