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
 * 系统栏方案（v4 保守版）：仅使用 API 21+ 的基础方法（setStatusBarColor /
 * setNavigationBarColor / SYSTEM_UI_FLAG 常量），不引用任何高版本系统栏类，
 * 避免旧设备上类验证导致的闪退。状态栏与页面 header 同色，内容从状态栏下方开始。
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

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                toast("页面加载失败：" + description);
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

    /**
     * 主题切换时同步导航栏颜色与图标。
     * 只用 API 21+ 方法与编译期常量（SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR 在
     * API 26 以下无效果但不报错），不引用高版本系统栏类。
     */
    private void applyThemeBars(boolean dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setNavigationBarColor(dark ? NAV_BAR_DARK : NAV_BAR_LIGHT);
            int flags = 0;
            if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
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
