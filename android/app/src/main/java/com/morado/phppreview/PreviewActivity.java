package com.morado.phppreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Full-screen browser that shows the client website from the local PHP server. */
public class PreviewActivity extends Activity {
    private static final int REQ_FILE = 7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Rt rt;
    private Project p;
    private WebView web;
    private TextView loading;
    private String base;
    private int port;
    private volatile int myGen;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        rt = Rt.get(this);
        String id = getIntent().getStringExtra("id");
        String path = getIntent().getStringExtra("path");
        if (path == null) path = "/";
        try {
            p = Project.load(new File(rt.projects, id));
        } catch (Exception e) {
            Toast.makeText(this, "المشروع غير موجود", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new TextView(this);
        loading.setText("جاري تشغيل «" + p.name + "» ...");
        loading.setTextSize(20);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(Color.WHITE);
        root.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView menu = new TextView(this);
        menu.setText("⋮");
        menu.setTextSize(22);
        menu.setTextColor(Color.WHITE);
        menu.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(150, 40, 40, 40));
        menu.setBackground(bg);
        menu.setAlpha(0.45f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(menu, lp);
        menu.setOnClickListener(this::showMenu);

        setContentView(root);
        setupWeb();

        final String startPath = path;
        new Thread(() -> {
            try {
                rt.install();
                rt.startDb();
                synchronized (rt) {
                    port = rt.startPhp(p);
                    myGen = rt.phpGen;
                }
                base = "http://127.0.0.1:" + port;
                ui.post(() -> {
                    if (isDestroyed()) return;
                    loading.setVisibility(View.GONE);
                    web.loadUrl(base + startPath);
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("تعذر تشغيل الموقع")
                            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                            .setPositiveButton("رجوع", (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                });
            }
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme();
                String h = u.getHost() == null ? "" : u.getHost();
                if ((sc.equals("http") || sc.equals("https")) && (h.equals("127.0.0.1") || h.equals("localhost"))) {
                    if (u.getPort() != port || !sc.equals("http")) {
                        // links hard-coded to http://localhost/... -> our local server
                        String rest = u.getEncodedPath() == null ? "/" : u.getEncodedPath();
                        if (u.getEncodedQuery() != null) rest += "?" + u.getEncodedQuery();
                        if (u.getEncodedFragment() != null) rest += "#" + u.getEncodedFragment();
                        v.loadUrl(base + rest);
                        return true;
                    }
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    Toast.makeText(PreviewActivity.this, "لا يمكن فتح: " + u, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams fp) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(fp.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "تحديث الصفحة");
        pm.getMenu().add(0, 2, 0, "الصفحة الرئيسية");
        pm.getMenu().add(0, 3, 0, "رجوع");
        pm.getMenu().add(0, 4, 0, "إدارة قاعدة البيانات");
        pm.getMenu().add(0, 5, 0, "إغلاق المعاينة");
        pm.setOnMenuItemClickListener(it -> {
            if (base == null && it.getItemId() != 5) return true;
            switch (it.getItemId()) {
                case 1: web.reload(); break;
                case 2: web.loadUrl(base + "/"); break;
                case 3: if (web.canGoBack()) web.goBack(); break;
                case 4: web.loadUrl(base + rt.adminerPath(p)); break;
                case 5: finish(); break;
                default: break;
            }
            return true;
        });
        pm.show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        final int gen = myGen;
        if (isFinishing() && rt != null) new Thread(() -> rt.stopPhpIfGen(gen)).start();
        super.onDestroy();
    }
}
