package com.dsh.inputfix;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 三步自证：EditText -> WebView -> 结论。
 *
 * EditText 先通，说明 IME 通道本身没问题；
 * 再测 WebView，才知道问题是不是出在 WebView 的 focus 传递上。
 */
public class TestActivity extends Activity {

    private static final String TAG = "InputFixTest";

    private static final String HTML =
            "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body style='font-family:sans-serif;padding:12px'>"
                    + "<input id='q' placeholder='webview input' "
                    + "style='font-size:20px;width:96%;padding:8px;border:2px solid #4a9'>"
                    + "<div id='log' style='margin-top:10px;font-family:monospace;font-size:12px'></div>"
                    + "<script>"
                    + "window.__events=[];var q=document.getElementById('q');"
                    + "function paint(){document.getElementById('log').textContent="
                    + "'events('+window.__events.length+') '+JSON.stringify(window.__events);}"
                    + "q.addEventListener('input',function(e){window.__events.push('input:'+(e.inputType||'legacy'));paint();});"
                    + "q.addEventListener('change',function(e){window.__events.push('change');paint();});"
                    + "q.addEventListener('keyup',function(e){window.__events.push('keyup');paint();});"
                    + "window.__dump=function(){return JSON.stringify({value:q.value,"
                    + "events:window.__events,selStart:q.selectionStart,selEnd:q.selectionEnd});};"
                    + "</script></body></html>";

    private EditText editText;
    private WebView webView;
    private TextView output;

    private String editResult = "(pending)";
    private String webResult = "(pending)";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        output = new TextView(this);
        output.setTextSize(11f);
        output.setTextColor(Color.parseColor("#111111"));
        output.setBackgroundColor(Color.parseColor("#EEEEEE"));
        output.setPadding(20, 20, 20, 20);
        output.setText("running...");
        root.addView(output, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editText = new EditText(this);
        editText.setHint("原生 EditText");
        editText.setTextSize(18f);
        root.addView(editText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button again = new Button(this);
        again.setText("重新自测");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest();
            }
        });
        root.addView(again);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        runTest();
                    }
                }, 600);
            }
        });
        webView.loadDataWithBaseURL("https://inputfix.test/", HTML, "text/html", "UTF-8", null);
    }

    private void runTest() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                testEditText();
                testWebView();
                final String a = editResult;
                final String b = webResult;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        output.setText("EDIT: " + a + "\n\nWEB : " + b);
                    }
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void testEditText() {
        final Object lock = new Object();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                editText.setText("");
                editText.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED);
                synchronized (lock) { lock.notifyAll(); }
            }
        });
        try { synchronized (lock) { lock.wait(1500); } } catch (InterruptedException ignored) {}

        Bridge.sleep(1200);
        boolean ready = Bridge.awaitIme(3000);
        Bridge.FillResult r = Bridge.fill("原生测试ABC", true, 40, 200, true);

        final String[] viewText = new String[1];
        final Object l2 = new Object();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                viewText[0] = editText.getText().toString();
                synchronized (l2) { l2.notifyAll(); }
            }
        });
        try { synchronized (l2) { l2.wait(1000); } } catch (InterruptedException ignored) {}

        editResult = "ready=" + ready + " fillOk=" + r.ok + " sel=" + r.selectionStart + "/" + r.selectionEnd
                + " view=[" + viewText[0] + "] err=" + r.error;
        Log.i(TAG, "EDIT " + editResult);
    }

    private void testWebView() {
        final Object lock = new Object();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript("document.getElementById('q').focus();1", null);
                webView.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED);
                synchronized (lock) { lock.notifyAll(); }
            }
        });
        try { synchronized (lock) { lock.wait(1500); } } catch (InterruptedException ignored) {}

        Bridge.sleep(1200);
        boolean ready = Bridge.awaitIme(3000);
        Bridge.FillResult r = Bridge.fill("网页测试XYZ", true, 40, 200, true);

        final String[] dump = new String[1];
        final Object l2 = new Object();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript("window.__dump()", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        dump[0] = v;
                        synchronized (l2) { l2.notifyAll(); }
                    }
                });
            }
        });
        try { synchronized (l2) { l2.wait(2000); } } catch (InterruptedException ignored) {}

        webResult = "ready=" + ready + " fillOk=" + r.ok + " sel=" + r.selectionStart + "/" + r.selectionEnd
                + " dump=" + dump[0] + " err=" + r.error;
        Log.i(TAG, "WEB " + webResult);
    }
}
