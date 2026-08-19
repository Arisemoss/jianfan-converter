package com.wenjian.shuzhai;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
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
 * 加载 assets/ebook-tool.html，提供原生文件保存 / 分享 / 打开 / 文件选择能力。
 * 保存统一走 MediaStore 拿到 content URI，再通过系统分享面板与「打开方式」
 * 选择器交给其他应用（微信、QQ、MT 管理器等），避免 file:// 暴露限制。
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
        // 关闭 WebView 自动算法暗色（API 33+ 公开 API）：页面明暗由 data-theme 完全控制，
        // 避免系统深色模式下页面被算法再变暗一次（双重变暗、颜色失真）。
        // 注意：WebSettings.setForceDarkAllowed 是 @SystemApi 隐藏 API，应用层不能调用。
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(false);
        }

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

    @SuppressWarnings("deprecation") // onBackPressed 在 API 33 废弃，但项目使用基础 Activity（非 AndroidX），保留兼容
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 系统深浅色切换（需 manifest 中 configChanges 包含 uiMode）。
     * WebView 的 prefers-color-scheme 在 targetSdk≥33 时固定跟随 app 主题
     * （isLightTheme），不会随系统变化，所以由原生侧检测并通知 JS 重新应用主题。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 通知 JS：若当前处于「跟随系统」模式则重应用主题（并同步导航栏）；
        // 手动模式不重应用，导航栏颜色由用户选择决定，不会被覆盖。
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.__systemThemeChanged && window.__systemThemeChanged();", null);
        }
    }

    private boolean isSystemDark() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 网页下载桥接：把文本内容以指定文件名保存到系统「下载」目录。
     * 返回 content URI 字符串（空字符串表示失败），网页端据此提供「分享 / 打开」。
     */
    class NativeBridge {

        @JavascriptInterface
        public String saveText(String text, String filename) {
            try {
                return saveBytes(text.getBytes(StandardCharsets.UTF_8), filename, "text/plain");
            } catch (Exception e) {
                e.printStackTrace();
                toast("保存失败：" + e.getMessage());
                return "";
            }
        }

        /**
         * 保存二进制内容（批量打包的 zip 等），网页端以 dataURL 形式传入。
         * 返回 content URI 字符串（空字符串表示失败）。
         */
        @JavascriptInterface
        public String saveBase64(String base64DataUrl, String filename, String mime) {
            try {
                String b64 = base64DataUrl;
                int comma = b64.indexOf(',');
                if (comma >= 0) b64 = b64.substring(comma + 1);
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                return saveBytes(bytes, filename, mime);
            } catch (Exception e) {
                e.printStackTrace();
                toast("保存失败：" + e.getMessage());
                return "";
            }
        }

        /** 统一保存字节到系统「下载」目录，返回 content URI 字符串（空表示失败）。 */
        private String saveBytes(byte[] bytes, String filename, String mime) throws Exception {
            String cleanMime = cleanMime(mime);

            String savedUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, cleanMime);
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
                os.write(bytes);
                os.close();
                savedUri = uri.toString();
            } else {
                // API 24-28：写入公共下载目录后注册到媒体库，换取 content URI
                // （分享/打开需要 content URI，file:// 在 API 24+ 会被拦截）
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File file = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Files.FileColumns.DATA, file.getAbsolutePath());
                    values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename);
                    values.put(MediaStore.Files.FileColumns.MIME_TYPE, cleanMime);
                    Uri inserted = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
                    savedUri = inserted != null ? inserted.toString() : Uri.fromFile(file).toString();
                } catch (Exception e) {
                    savedUri = Uri.fromFile(file).toString();
                }
            }
            toast("已保存：" + filename);
            return savedUri;
        }

        /** 分享已保存的文件到其他应用（微信、QQ、文件管理等）。 */
        @JavascriptInterface
        public void shareFile(final String uriString, final String filename, final String mime) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Uri uri = Uri.parse(uriString);
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType(cleanMime(mime));
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_SUBJECT, filename);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "分享 " + filename));
                    } catch (Exception e) {
                        e.printStackTrace();
                        toast("分享失败：" + e.getMessage());
                    }
                }
            });
        }

        /** 用其他应用打开已保存的文件（MT 管理器、微信、阅读器等）。 */
        @JavascriptInterface
        public void openFile(final String uriString, final String filename, final String mime) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Uri uri = Uri.parse(uriString);
                        Intent view = new Intent(Intent.ACTION_VIEW);
                        view.setDataAndType(uri, cleanMime(mime));
                        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(view, "用其他应用打开 " + filename));
                    } catch (Exception e) {
                        e.printStackTrace();
                        toast("打开失败：" + e.getMessage());
                    }
                }
            });
        }

        /** 去掉 MIME 的参数部分（如 text/plain;charset=utf-8 → text/plain）。 */
        private String cleanMime(String mime) {
            String m = mime;
            int semi = m.indexOf(';');
            if (semi > 0) m = m.substring(0, semi).trim();
            return m.isEmpty() ? "text/plain" : m;
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

        /**
         * 系统当前是否深色模式。WebView 的 prefers-color-scheme 在 targetSdk≥33 时
         * 固定跟随 app 主题（isLightTheme）而非系统，因此由原生侧读取系统配置
         * 桥接给 JS，供「跟随系统」模式使用。
         */
        @JavascriptInterface
        public boolean isSystemDark() {
            return (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
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
