package com.wenjian.shuzhai;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文简书斋 · 电子书处理工具 —— WebView 壳
 * 加载 assets/ebook-tool.html，提供原生文件保存与文件选择能力。
 *
 * 系统栏方案（v2）：放弃沉浸式布局与 CSS safe-area（WebView 对 env() 支持不可靠），
 * 改为标准布局：状态栏独立显示并与页面 header 同色，页面内容始终从状态栏下方开始，
 * 顶部标题不会被遮挡。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    /** 页面 header 深色（两种主题下 header 均为深色） */
    private static final int STATUS_BAR_COLOR = Color.parseColor("#1a1a1a");
    /** 浅色主题导航栏：页面背景米色 */
    private static final int NAV_BAR_LIGHT = Color.parseColor("#f5f0e6");
    /** 深色主题导航栏：页面背景墨色 */
    private static final int NAV_BAR_DARK = Color.parseColor("#16130f");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupSystemBars();

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/ebook-tool.html");
    }

    /** 标准布局：状态栏与 header 同色（深色+白图标），导航栏按当前主题着色 */
    private void setupSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(STATUS_BAR_COLOR);
            getWindow().setNavigationBarColor(NAV_BAR_LIGHT);
            applyThemeBars(false);
        }
    }

    /** 网页主题切换时同步系统导航栏外观（状态栏恒为深色+白图标，无需跟随） */
    private void applyThemeBars(boolean dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setNavigationBarColor(dark ? NAV_BAR_DARK : NAV_BAR_LIGHT);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                // 状态栏：恒为深色背景 → 白色图标（不清除 LIGHT_STATUS_BARS）
                c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                // 导航栏：浅色主题米色背景 → 深色图标；深色主题墨色背景 → 白色图标
                c.setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setNavigationBarColor(dark ? NAV_BAR_DARK : NAV_BAR_LIGHT);
            getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 网页下载桥接：把文本内容以指定文件名保存到系统「下载」目录。
     * 返回保存后的路径（空字符串表示失败）。
     */
    class NativeBridge {
        @JavascriptInterface
        public String saveText(String text, String filename) {
            try {
                String savedPath;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        toast("保存失败：无法创建下载条目");
                        return "";
                    }
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) {
                        toast("保存失败：无法打开输出流");
                        return "";
                    }
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    savedPath = Environment.DIRECTORY_DOWNLOADS + "/" + filename;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    File file = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                    fos.close();
                    savedPath = file.getAbsolutePath();
                }
                toast("已保存：" + filename);
                return savedPath;
            } catch (Exception e) {
                e.printStackTrace();
                toast("保存失败：" + e.getMessage());
                return "";
            }
        }

        @JavascriptInterface
        public void setTheme(boolean dark) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyThemeBars(dark);
                }
            });
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
