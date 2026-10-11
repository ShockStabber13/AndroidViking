package com.shockstabber.lightningbridge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.Locale;

/** Proof of concept: human verification in WebView, capture the site's *authorized* result. */
public class MainActivity extends Activity {
    private static final int BLACK = Color.BLACK;
    private static final int GREEN = Color.rgb(21, 190, 25);
    private static final int WHITE = Color.WHITE;
    private static final String DEFAULT_HINT = "https://vik1ngfile.site/f/FILE_ID";

    private BridgeServer server;
    private WebView browser;
    private EditText address;
    private TextView status;
    private TextView endpoint;
    private String currentPage = "";

    // HAR confirmed this host POSTS to /f/<id> after a real Turnstile solve,
    // returning JSON with both 'link' and 'direct-link'. Capture only that response.
    private static final String CAPTURE_JS = "(function(){"
        + "if(window.__lightningCaptureInstalled)return;"
        + "window.__lightningCaptureInstalled=true;"
        + "var p=XMLHttpRequest.prototype,open=p.open,send=p.send;"
        + "p.open=function(method,url){"
        + "var u;try{u=new URL(url,location.href)}catch(e){u=null;}"
        + "this.__lightningWatch=(String(method).toUpperCase()==='POST'&&u&&u.origin===location.origin&&u.pathname.indexOf('/f/')===0);"
        + "return open.apply(this,arguments);};"
        + "p.send=function(){"
        + "if(this.__lightningWatch)this.addEventListener('load',function(){"
        + "if(this.status!==200)return;"
        + "try{var result=JSON.parse(this.responseText);"
        + "if(typeof result['direct-link']==='string')window.LightningReceiver.onDirectLink(result['direct-link']);"
        + "}catch(e){}"
        + "});"
        + "return send.apply(this,arguments);};"
        + "})();";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);

        try { server = new BridgeServer(); }
        catch (IOException e) { server = null; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);
        root.setPadding(dp(14), dp(10), dp(14), dp(6));
        setContentView(root);

        TextView title = label("⚡ LIGHTNING BRIDGE", 20, GREEN);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        root.addView(label("SwitchGamesBrowser • Android proof of concept", 12, WHITE));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(WHITE);
        address.setHintTextColor(0xff999999);
        address.setBackground(tintBackground(0xff111111, GREEN));
        address.setTextSize(13);
        address.setHint(DEFAULT_HINT);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(address, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions);
        Button load = actionButton("OPEN PAGE");
        actions.addView(load, new LinearLayout.LayoutParams(0, dp(47), 1f));
        Button copy = actionButton("COPY HANDOFF");
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, dp(47), 1f);
        copyParams.leftMargin = dp(8);
        actions.addView(copy, copyParams);

        status = label("Waiting for page", 14, WHITE);
        status.setPadding(0, dp(6), 0, dp(4));
        root.addView(status);
        endpoint = label(server == null ? "Wi-Fi server failed to start" : "LAN endpoint: " + server.endpoint(), 11, GREEN);
        endpoint.setMaxLines(3);
        root.addView(endpoint);
        root.addView(label("Keep this screen open during the handoff. No automatic CAPTCHA solving.", 11, WHITE));

        browser = new WebView(this);
        browser.setBackgroundColor(BLACK);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(browser, true);
        browser.addJavascriptInterface(new CaptureReceiver(), "LightningReceiver");
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageCommitVisible(WebView v, String url) {
                // Install as early as possible: Turnstile can sometimes complete quickly.
                if (isHostUrl(url)) v.evaluateJavascript(CAPTURE_JS, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (isHostUrl(url)) {
                    status.setText("Page loaded. Complete verification if requested…");
                    // Hook is installed before user interacts; CF widget runs normally.
                    v.evaluateJavascript(CAPTURE_JS, null);
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!request.isForMainFrame()) return false;
                return !isHostUrl(u.toString());
            }
        });
        browser.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            // Site button can trigger a download without a document navigation.
            if (isTrustedStorageUrl(url)) onCaptured(url);
        });
        LinearLayout.LayoutParams browserParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        browserParams.topMargin = dp(8);
        root.addView(browser, browserParams);

        load.setOnClickListener(v -> {
            String value = address.getText().toString().trim();
            if (!isHostUrl(value)) {
                status.setText("Enter a HTTPS ViKiNG FiLE /f/ URL.");
                return;
            }
            currentPage = value;
            if (server != null) {
                server.rotateSession();
                endpoint.setText("LAN endpoint: " + server.endpoint());
            }
            status.setText("Loading ViKiNG FiLE…");
            browser.loadUrl(value);
        });
        copy.setOnClickListener(v -> {
            if (server == null) return;
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Lightning Bridge handoff (secret)", server.endpoint()));
            status.setText("Handoff endpoint copied. Treat it as a secret.");
        });
    }

    private class CaptureReceiver {
        @JavascriptInterface public void onDirectLink(String signedUrl) {
            // JavaScript interfaces can be called by embedded frames. Accept only known
            // signed storage hosts, HTTPS, and only after the user loaded a valid page.
            if (!isHostUrl(currentPage) || !isTrustedStorageUrl(signedUrl)) return;
            runOnUiThread(() -> onCaptured(signedUrl));
        }
    }

    private void onCaptured(String signedUrl) {
        if (server == null || !isTrustedStorageUrl(signedUrl)) return;
        server.setSignedUrl(signedUrl);
        status.setText("✓ Signed direct URL captured. Ready for Wi-Fi handoff.");
        endpoint.setText("LAN endpoint: " + server.endpoint());
    }

    private static boolean isHostUrl(String value) {
        try {
            Uri u = Uri.parse(value);
            String host = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme())
                && host != null && (host.equalsIgnoreCase("vik1ngfile.site") || host.equalsIgnoreCase("vikingfile.com"))
                && u.getPath() != null && u.getPath().startsWith("/f/");
        } catch (Exception ignored) { return false; }
    }

    private static boolean isTrustedStorageUrl(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            Uri u = Uri.parse(value);
            String host = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme()) && host != null
                && (host.equalsIgnoreCase("cloud.ovh.net") || host.toLowerCase(Locale.ROOT).endsWith(".cloud.ovh.net"));
        } catch (Exception ignored) { return false; }
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(BLACK);
        b.setAllCaps(false);
        b.setBackground(tintBackground(GREEN, GREEN));
        return b;
    }
    private GradientDrawable tintBackground(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(7));
        d.setStroke(dp(1), stroke);
        return d;
    }
    @Override public void onBackPressed() {
        if (browser != null && browser.canGoBack()) browser.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (browser != null) {
            browser.removeJavascriptInterface("LightningReceiver");
            browser.destroy();
        }
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        super.onDestroy();
    }
}
