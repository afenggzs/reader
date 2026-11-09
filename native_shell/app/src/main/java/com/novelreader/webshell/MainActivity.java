package com.novelreader.webshell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 小说阅读器 · 网页版外壳。
 *
 * 不打包任何渲染引擎，界面完全由 `assets/index.html`（仓库里
 * `网页版/reader-prototype.html` 的副本）绘制，原生这一侧只负责：
 *
 *  1. 把 assets 映射成一个 https 源交给 WebView（[WebViewAssetLoader]）；
 *  2. 沉浸式布局，页面的 `env(safe-area-inset-*)` 才有真实值；
 *  3. `<input type="file">` 的文件选择（WebView 默认不支持，必须实现
 *     [WebChromeClient#onShowFileChooser]，否则「导入」点了没反应）；
 *  4. 返回键分层（二级面板 → 目录 → 菜单 → 退出）。
 *
 * **任何异常都不许直接崩。** 这个壳子最终跑在别人的手机上，一句
 * 「点开就退」我这边看不到任何信息。所以启动全程包在 try/catch 里，
 * 出错就把异常栈画在屏幕上；页面加载失败/超时也会挂一条提示条。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    /** WebViewAssetLoader 的默认虚拟域名（见 WebViewAssetLoader.DEFAULT_DOMAIN）。 */
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String START_URL = ORIGIN + "/assets/index.html";
    private static final int PAPER = 0xFFEFE6CE;
    private static final int INK = 0xFF3A2E22;

    private FrameLayout root;
    private WebView web;
    private TextView banner;
    private ValueCallback<Uri[]> fileCallback;

    /**
     * API 33+ 的 OnBackInvokedCallback。故意用 Object 存：这个类型在 33 以下
     * 不存在，写成字段类型会让低版本 ART 在类校验阶段就去找它。
     */
    private Object backCallback;

    private boolean pageLoaded;

    /**
     * 当前是否处于沉浸态（阅读页、且菜单/目录都没展开）。
     * 由网页侧的 `NativeBars.setImmersive()` 驱动 —— 页面才知道自己现在显示的是书架还是正文。
     * 初值 false：冷启动落在书架，系统栏本来就该在。
     */
    private boolean immersive;

    /** 状态栏高度（px）。用「忽略可见性」的 insets 取，切到沉浸态也不会变成 0。 */
    private int statusBarPx;

    /** 最近一次注进页面的 `--safe-top`（dp），相同就不重复下发。 */
    private int injectedSafeTop = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            start();
        } catch (Throwable t) {
            showFatal("启动失败", t);
        }
    }

    // ---------------------------------------------------------------- 启动

    @SuppressLint("SetJavaScriptEnabled")
    private void start() {
        root = new FrameLayout(this);
        root.setBackgroundColor(PAPER);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(PAPER);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebContentsDebuggingEnabled(true);
        // 页面通过这个桥告诉我们「现在该不该藏系统栏」。
        // 必须在 loadUrl 之前挂上，否则首帧的同步调用找不到对象。
        web.addJavascriptInterface(new BarsBridge(), "NativeBars");
        // 长按一律由网页自己处理（书架卡片长按 500ms = 删除）。
        // 不返回 true 的话，WebView 会先进文本选择模式，弹出一排「复制／分享／全选」，
        // 跟长按删除抢同一个手势。网页的 pointerdown 计时器不受影响 —— 它早就收到了事件。
        web.setOnLongClickListener(v -> true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // IndexedDB / localStorage 能用的前提，正文就存在 IndexedDB 里
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 纯本地页面，不需要碰文件系统与 content://
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 字号只认页面自己的设置，别被系统「显示大小」放大
        s.setTextZoom(100);
        // 系统深色模式不要给网页强制反色，否则四套背景色全废。
        // 用 androidx 的兼容接口：WebSettings.setForceDarkAllowed 不在公开 SDK 里。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = loader.shouldInterceptRequest(request.getUrl());
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 页面里不该有外链；真有也不许把阅读器变成一个浏览器
                return !ORIGIN.equals(request.getUrl().getScheme() + "://" + request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                hideBanner();
                // 页面（重新）建好了，之前注入的 CSS 变量已经没了，补一次；
                // 同时把当前状态再推给页面已有的观察器
                injectedSafeTop = -1;
                injectSafeTop();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showBanner("页面加载失败：" + error.getErrorCode() + " "
                            + error.getDescription() + "\n" + request.getUrl());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse res) {
                if (request.isForMainFrame()) {
                    showBanner("页面返回 HTTP " + res.getStatusCode() + "\n" + request.getUrl());
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // 网页版的 accept 是 .txt/.epub，但不少文件管理器只按 MIME 过滤，
                // 中文 txt 还常常被标成 application/octet-stream，epub 更是常被标成
                // application/zip 甚至 octet-stream。这里干脆不过滤，让用户自己挑 ——
                // 选错了页面会回「解析失败：不是有效的 EPUB / 未能识别到任何章节」。
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择小说文件"), REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    fileCallback = null;
                    showBanner("打开文件选择器失败：" + t);
                    return false;
                }
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        setupSystemBars();
        registerBack();
        web.loadUrl(START_URL);
        watchdog();
    }

    /** 本地资源正常几十毫秒就画完了；6 秒还没 finished 就把线索挂到屏幕上。 */
    private void watchdog() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pageLoaded && !isFinishing()) {
                    showBanner("页面 6 秒内没有加载完\nURL：" + START_URL
                            + "\nWebView：" + webViewVersion());
                }
            }
        }, 6000);
    }

    // ------------------------------------------------------------ 系统栏

    /**
     * 系统栏策略只有一条：**阅读页以外的任何地方都要有状态栏**。
     *
     * 书架的顶栏、菜单的应用栏都是浅色纸底，上面缺一条状态栏就不像正常应用；
     * 只有纯阅读态（正文铺满、页脚自己画电量与时间）才全屏沉浸。
     * 什么时候该切由网页通过 [BarsBridge] 告知 —— 页面才知道自己显示的是书架还是正文。
     */
    private void setupSystemBars() {
        Window w = getWindow();
        // 全程 edge-to-edge：系统栏显隐不改变 WebView 尺寸，
        // 页面只靠 env(safe-area-inset-*) 让位，正文不会因为开一次菜单就重新分页。
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 否则系统会在透明导航条后面垫一层半透明遮罩，纸色被压暗一截
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        }
        // 取「忽略可见性」的状态栏高度：沉浸态下 insets 会变 0，但布局需要的是设备常量，
        // 否则一进阅读页把值刷成 0，退回书架时顶栏会先贴顶再跳下来。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                top = Api30.stableStatusBarTop(insets);
            } else {
                //noinspection deprecation
                top = insets.getStableInsetTop();
            }
            if (top > 0 && top != statusBarPx) {
                statusBarPx = top;
                injectSafeTop();
            }
            return insets;
        });
        applySystemBars(false);   // 冷启动落在书架：状态栏该在
    }

    private void applySystemBars(boolean hide) {
        immersive = hide;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Api30.setBars(getWindow(), hide);
            } else {
                legacyBars(hide);
            }
        } catch (Throwable ignored) {
            // 系统栏切换失败不值得中断阅读，页面自己的留白还能兜着
        }
    }

    @SuppressWarnings("deprecation")
    private void legacyBars(boolean hide) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (hide) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        } else {
            // 纸底是浅色，状态栏图标必须转深色，否则白字白底什么都看不见
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /**
     * WebView 的 `env(safe-area-inset-top)` 在新内核上是对的，老内核可能恒为 0。
     * 把实测值写成 CSS 变量盖住它，页面顶栏就不会被状态栏压住。
     */
    private void injectSafeTop() {
        if (web == null || statusBarPx <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int dp = Math.round(statusBarPx / (density <= 0f ? 1f : density));
        if (dp == injectedSafeTop) {
            return;
        }
        injectedSafeTop = dp;
        web.evaluateJavascript(
                "document.documentElement.style.setProperty('--safe-top','" + dp + "px')", null);
    }

    /** 网页 → 原生的单向通道。可能从 WebView 的 JS 线程调进来，一律转到主线程处理。 */
    public final class BarsBridge {
        @JavascriptInterface
        public void setImmersive(final boolean hide) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        applySystemBars(hide);
                    }
                }
            });
        }
    }

    /** API 30+ 的系统栏接口。单独一个类，低版本 ART 不会去解析它。 */
    private static final class Api30 {
        static void setBars(Window w, boolean hide) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c == null) {
                return;
            }
            if (hide) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        }

        static int stableStatusBarTop(WindowInsets insets) {
            return insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
        }
    }

    // ------------------------------------------------------------ 返回键

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = Api33.register(this, new Runnable() {
                @Override
                public void run() {
                    handleBack();
                }
            });
        }
    }

    private static final class Api33 {
        static Object register(Activity a, final Runnable action) {
            android.window.OnBackInvokedCallback cb = action::run;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            return cb;
        }

        static void unregister(Activity a, Object cb) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) cb);
        }
    }

    /**
     * 返回键分层：二级面板 → 目录 → 菜单 → 回书架 → 退出。
     * 网页版是单页应用，状态都在自己的 JS 里，所以只能让它自己点自己的按钮。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (web == null) {
            finish();
            return;
        }
        try {
            web.evaluateJavascript(BACK_JS, value -> {
                boolean handled = value != null && value.contains("\"ok\"");
                if (!handled) {
                    finish();
                }
            });
        } catch (Throwable t) {
            finish();
        }
    }

    // ------------------------------------------------------------ 兜底展示

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showBanner(String msg) {
        try {
            if (root == null) {
                return;
            }
            if (banner == null) {
                banner = new TextView(this);
                banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                banner.setTextColor(0xFF7A2E1E);
                banner.setBackgroundColor(0xFFF6E2C8);
                banner.setPadding(dp(16), dp(10), dp(16), dp(10));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM;
                root.addView(banner, lp);
            }
            banner.setText(msg);
            banner.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
            // 连提示条都挂不上就只能算了，别把原始错误再盖掉
        }
    }

    private void hideBanner() {
        if (banner != null) {
            banner.setVisibility(View.GONE);
        }
    }

    private void showFatal(String title, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextView tv = new TextView(this);
        tv.setText(title + "\n\n" + sw + "\n\n" + deviceInfo());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(INK);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(18), dp(18), dp(18), dp(18));
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(PAPER);
        sc.addView(tv);
        setContentView(sc);
    }

    private String deviceInfo() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "机型 " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "WebView " + webViewVersion();
    }

    private String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
                return p == null ? "未知" : p.versionName;
            }
            return "API<26 未知";
        } catch (Throwable t) {
            return "读取失败(" + t.getClass().getSimpleName() + ")";
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        // 切到后台再回来，系统会把被藏起来的系统栏放回去（也可能把本该显示的藏掉），
        // 按当前状态重设一次。网页那边的 visibilitychange 也会重推，两边互为兜底。
        applySystemBars(immersive);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            if (cb != null) {
                cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        try {
            if (backCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Api33.unregister(this, backCallback);
                backCallback = null;
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
            }
            if (web != null) {
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) {
                    parent.removeView(web);
                }
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
            // 退出路径上的异常不值得让进程崩
        }
        super.onDestroy();
    }

    /**
     * 返回 'ok' 表示「已经处理掉了，别退出」；其余一律让原生退出。
     * 兜底：任何一步抛错都返回 'none'，避免返回键在网页卡死时完全失灵。
     *
     * 层级：二级面板 → 目录 → 菜单 → 阅读页 → 退出。
     * **阅读页的判定只能看 `#reading.active`**：三个 tab 页是常驻的
     * （`.screen.tab-page{display:flex}`，靠 translateX 错开，阅读页再以 z-index:20 压在上面），
     * 所以「书架是否 display:none」在阅读页里恒为 false —— 曾经按那个条件判断，
     * 导致在正文里按返回直接 finish()，连进度都来不及落盘。
     * 关闭阅读页走 `menuBack.click()`：它的回调是 `closeMenu() + renderShelf() + showScreen("bookshelf")`，
     * `showScreen` 内部还会兜底存一次进度。
     */
    private static final String BACK_JS =
            "(function(){"
                    + "try{"
                    + "var $=function(id){return document.getElementById(id);};"
                    + "var sp=$('subPanel');"
                    + "if(sp&&sp.classList.contains('show')){$('subBack').click();return 'ok';}"
                    + "var toc=$('toc');"
                    + "if(toc&&toc.classList.contains('show')){$('tocClose').click();return 'ok';}"
                    + "var menu=$('menu');"
                    + "if(menu&&menu.classList.contains('show')){$('menuScrim').click();return 'ok';}"
                    + "var reading=$('reading');"
                    + "if(reading&&reading.classList.contains('active')){$('menuBack').click();return 'ok';}"
                    + "return 'none';"
                    + "}catch(e){return 'none';}"
                    + "})();";
}
