/*
 * Prive — unofficial Telegram client. GPL v2 or later (same as Telegram for Android).
 *
 * v1 Website Connect / My Website screens: a WebView on the Prive backend's /app UI, authenticated with a
 * server token obtained by linking the logged-in Telegram account (PriveServer.link). Also handles the
 * website button deep link  prive://connect?site=<siteKey>&listing=<listingId>  (and the verified App Link
 * https://priveconnect.com/app/?connect=...). In-WebView prive:// / intent:// / same-origin links stay in the app.
 * Website Connect chats run on the Prive server, NOT on Telegram: no Telegram contact/username is exposed.
 */
package org.telegram.prive;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.widget.Toast;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

public class PriveWebsiteConnectActivity extends Activity {

    public static final String EXTRA_TAB = "prive_tab"; // "wc" | "site" | "settings" | "choose"

    public static void open(Context ctx, String tab) {
        Intent i = new Intent(ctx, PriveWebsiteConnectActivity.class);
        i.putExtra(EXTRA_TAB, tab);
        ctx.startActivity(i);
    }

    private WebView webView;
    private TextView status;
    private View titleBar, rootView, hairline;
    private TextView titleView;
    private String currentTab;

    private void applySystemBars(int color) {
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            int f = getWindow().getDecorView().getSystemUiVisibility();
            boolean light = PriveConfig.isLight();
            f = light ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            getWindow().getDecorView().setSystemUiVisibility(f);
        }
    }

    private void restyleNative() {
        int bg = PriveConfig.bg();
        if (titleBar != null) titleBar.setBackgroundColor(bg);
        if (titleView != null) titleView.setTextColor(PriveConfig.fg());
        if (rootView != null) rootView.setBackgroundColor(bg);
        if (titleBar instanceof LinearLayout && ((LinearLayout) titleBar).getChildAt(0) instanceof android.widget.ImageView)
            ((android.widget.ImageView) ((LinearLayout) titleBar).getChildAt(0)).setColorFilter(PriveConfig.gold());
        applySystemBars(bg);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Website Connect chats must not be screenshotted / screen-recorded (anti contact-leak).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        String tab = getIntent().getStringExtra(EXTRA_TAB);
        String connectSite = null, connectListing = null, openConv = null;
        Uri data = getIntent().getData();
        if (data != null && "prive".equals(data.getScheme()) && "open".equals(data.getHost())) {
            openConv = data.getQueryParameter("conv"); // from a Website Connect notification
            if (openConv != null) PriveWcNotifier.clear(openConv);
            data = null;
            tab = "websites";
        }
        String openFeed = null;
        if (data != null && "prive".equals(data.getScheme()) && "updates".equals(data.getHost())) {
            openFeed = data.getQueryParameter("feed"); // from a Website updates notification
            if (openFeed != null) PriveUpdatesNotifier.clear(openFeed);
            data = null;
            tab = "websites";
        }
        if (data != null) {
            // prive://connect?site=..&listing=..  or verified App Link https://priveconnect.com/app/?connect=..&listing=..
            String[] c = parseConnect(data);
            if (c != null) {
                connectSite = c[0]; connectListing = c[1];
            }
            tab = "websites";
        }
        if (tab == null) tab = "websites";
        final String fTab = tab, fSite = connectSite, fListing = connectListing, fOpen = openConv, fFeed = openFeed;
        currentTab = tab;
        final int BG = PriveConfig.bg(), FG = PriveConfig.fg(), GOLD = PriveConfig.gold();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG); // Prive Noir / Light top bar
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(GOLD);
        back.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        back.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x22C9A86A, 1));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(AndroidUtilities.dp(54), ViewGroup.LayoutParams.MATCH_PARENT));
        View gap = new View(this);
        bar.addView(gap, new LinearLayout.LayoutParams(AndroidUtilities.dp(18), 1));
        TextView title = new TextView(this);
        title.setText("settings".equals(tab) ? "Prive" : "choose".equals(tab) ? "Prive" : "Websites");
        title.setTextColor(FG);
        titleView = title;
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        bar.addView(title);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));
        View hair = new View(this); // gold hairline divider
        hair.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00C9A86A, 0xAAC9A86A, 0x00C9A86A}));
        root.addView(hair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        hairline = hair;
        titleBar = bar;
        rootView = root;

        FrameLayout content = new FrameLayout(this);
        webView = new WebView(this);
        webView.setVisibility(View.INVISIBLE);
        content.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(16);
        status.setTextColor(PriveConfig.muted());
        status.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        status.setText(LocaleController.getString(R.string.WebsiteConnectLinking));
        content.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        // Edge-to-edge (targetSdk 35+): keep the title bar below the status bar and the content above the nav bar / keyboard.
        applySystemBars(BG);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        root.setBackgroundColor(BG);
        content.setBackgroundColor(BG);
        webView.setBackgroundColor(BG);
        ViewCompat.requestApplyInsets(root);

        configureWebView();
        // The app opts into OnBackInvokedCallback (manifest), so onBackPressed() isn't called on API 33+; route it explicitly.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }

        final TLRPC.User user = PriveServer.currentTelegramUser();
        if (user == null) {
            status.setText("Log in with your Telegram number first.");
            startActivity(new Intent(this, LaunchActivity.class));
            finish();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            PriveServer.LinkResult r = PriveServer.linkIfNeeded(user);
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinishing()) return;
                if (r.token == null) {
                    status.setText(LocaleController.getString(R.string.WebsiteConnectError) + "\n\n" + r.error + "\n\n" + PriveServer.baseUrl());
                    return;
                }
                StringBuilder url = new StringBuilder(PriveServer.baseUrl()).append("/app/?embed=android&theme=").append(PriveConfig.theme()).append("&tab=").append(fTab);
                if (!TextUtils.isEmpty(fOpen)) url.append("&open=").append(Uri.encode(fOpen));
                if (!TextUtils.isEmpty(fFeed)) url.append("&updates=").append(Uri.encode(fFeed));
                if (!TextUtils.isEmpty(fSite)) {
                    url.append("&connect=").append(Uri.encode(fSite));
                    if (!TextUtils.isEmpty(fListing)) url.append("&listing=").append(Uri.encode(fListing));
                }
                // Token goes in the fragment: never sent over the network as part of the URL.
                url.append("#token=").append(Uri.encode(r.token));
                webView.loadUrl(url.toString());
            });
        });
    }

    /** Returns {site, listing} for a Website Connect link, or null if the URI carries none. */
    private static String[] parseConnect(Uri u) {
        if (u == null || !u.isHierarchical()) return null;
        String site = u.getQueryParameter("site");
        if (TextUtils.isEmpty(site)) site = u.getQueryParameter("connect");
        if (TextUtils.isEmpty(site)) return null;
        return new String[]{site, u.getQueryParameter("listing")};
    }

    private static boolean isOwnOrigin(Uri u) {
        if (u == null) return false;
        Uri base = Uri.parse(PriveServer.baseUrl());
        String h = u.getHost();
        return h != null && TextUtils.equals(u.getScheme(), base.getScheme())
                && (h.equalsIgnoreCase(base.getHost()) || h.equalsIgnoreCase("www." + base.getHost()))
                && u.getPort() == base.getPort();
    }

    /** Open a Website Connect chat inside this WebView (token is already in the page's localStorage). */
    private void openConnectInWebView(String site, String listing) {
        StringBuilder url = new StringBuilder(PriveServer.baseUrl()).append("/app/?embed=android&theme=").append(PriveConfig.theme()).append("&tab=websites");
        if (!TextUtils.isEmpty(site)) {
            url.append("&connect=").append(Uri.encode(site));
            if (!TextUtils.isEmpty(listing)) url.append("&listing=").append(Uri.encode(listing));
        }
        webView.loadUrl(url.toString());
    }

    /** true = handled (don't let the WebView load it). */
    private boolean handleNavigation(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if ("prive".equals(scheme)) {
            String[] c = parseConnect(u);
            openConnectInWebView(c == null ? null : c[0], c == null ? null : c[1]);
            return true;
        }
        if ("intent".equals(scheme)) {
            try {
                Intent it = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                Uri d = it.getData();
                String pkg = it.getPackage();
                if ((d != null && "prive".equalsIgnoreCase(d.getScheme())) || getPackageName().equals(pkg)) {
                    String[] c = parseConnect(d);
                    openConnectInWebView(c == null ? null : c[0], c == null ? null : c[1]);
                    return true;
                }
                String fb = it.getStringExtra("browser_fallback_url");
                if (fb != null && isOwnOrigin(Uri.parse(fb))) { webView.loadUrl(fb); return true; }
                it.addCategory(Intent.CATEGORY_BROWSABLE);
                it.setComponent(null);
                it.setSelector(null);
                try { startActivity(it); } catch (Exception e) {
                    if (fb != null) try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb))); } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
            return true;
        }
        // Stay inside the Prive server; open anything else externally.
        if (isOwnOrigin(u)) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignore) {}
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Lets prive-connect.js know it runs inside the app (it then uses prive:// instead of intent://).
        s.setUserAgentString(s.getUserAgentString() + " PriveApp");
        webView.setWebChromeClient(new WebChromeClient());
        // Copy / Share for the My Website embed snippet, so nothing has to leave the app. Only callable from our own pages.
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface public void copy(String text) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (webView == null || !isOwnOrigin(Uri.parse(String.valueOf(webView.getUrl())))) return;
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Prive", text));
                    Toast.makeText(PriveWebsiteConnectActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                });
            }
            /** The web chat screen has its own Telegram-style action bar; hide the native one meanwhile (single header). */
            @JavascriptInterface public void setTitleBarVisible(boolean visible) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (titleBar != null) titleBar.setVisibility(visible ? View.VISIBLE : View.GONE);
                    if (hairline != null) hairline.setVisibility(visible ? View.VISIBLE : View.GONE);
                    restyleNative();
                });
            }
            /** Mode chosen in the web chooser / settings ("client" | "admin"). */
            @JavascriptInterface public void setMode(String mode) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (webView == null || !isOwnOrigin(Uri.parse(String.valueOf(webView.getUrl())))) return;
                    if (!PriveConfig.MODE_CLIENT.equals(mode) && !PriveConfig.MODE_ADMIN.equals(mode)) return;
                    PriveConfig.setMode(mode);
                    PriveFirstRun.chooserDone();
                    if ("choose".equals(currentTab)) {
                        if (PriveConfig.MODE_CLIENT.equals(mode)) { finish(); return; } // back to the chat list (shortcuts appear there)
                        currentTab = "websites";
                        if (titleView != null) titleView.setText("Websites");
                        webView.loadUrl(PriveServer.baseUrl() + "/app/?embed=android&tab=websites");
                    }
                });
            }
            @JavascriptInterface public void setTheme(String theme) {
                AndroidUtilities.runOnUIThread(() -> {
                    PriveConfig.setTheme("light".equals(theme) ? "light" : "noir");
                    restyleNative();
                });
            }
            /** Client-mode website shortcut: open in the native in-app browser (no iframe framing limits). */
            @JavascriptInterface public void openSite(String url, String name) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (webView == null || !isOwnOrigin(Uri.parse(String.valueOf(webView.getUrl())))) return;
                    Uri u = Uri.parse(url);
                    if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) return;
                    PriveSiteBrowserActivity.open(PriveWebsiteConnectActivity.this, url, name);
                });
            }
            @JavascriptInterface public void sitesChanged(String json) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (webView == null || !isOwnOrigin(Uri.parse(String.valueOf(webView.getUrl())))) return;
                    PriveConfig.setSites(json);
                });
            }
            @JavascriptInterface public void share(String text) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (webView == null || !isOwnOrigin(Uri.parse(String.valueOf(webView.getUrl())))) return;
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    try { startActivity(Intent.createChooser(i, "Share")); } catch (Exception ignore) {}
                });
            }
        }, "PriveAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                status.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        // Let the page close an open chat / sheet first (like Telegram's back), then WebView history, then leave.
        webView.evaluateJavascript("(window.priveBack && window.priveBack()) ? 'y' : 'n'", r -> {
            if (r != null && r.contains("y")) return;
            if (webView != null && webView.canGoBack()) webView.goBack(); else finish();
        });
    }

    @Override
    protected void onDestroy() {
        if ("choose".equals(currentTab)) PriveFirstRun.chooserDone();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
