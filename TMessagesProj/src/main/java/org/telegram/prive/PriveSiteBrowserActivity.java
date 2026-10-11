/*
 * Prive — unofficial Telegram client. GPL v2 or later.
 * Website shortcuts open like a Telegram Mini App: a full-screen sheet that slides up over the current screen,
 * with a mini-app top bar (close ✕, favicon + site title, ⋮ menu: Reload / Open in browser / Copy link), no
 * address bar, swipe the bar down to close. Native top-level WebView, so sites that block <iframe> framing
 * (CSP frame-ancestors / X-Frame-Options) still open inside Prive.
 */
package org.telegram.prive;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.telegram.messenger.AndroidUtilities.dp;

public class PriveSiteBrowserActivity extends Activity {

    public static void open(Context ctx, String url, String name) { open(ctx, url, name, null); }

    public static void open(Context ctx, String url, String name, String icon) {
        if (TextUtils.isEmpty(url)) return;
        Intent i = new Intent(ctx, PriveSiteBrowserActivity.class);
        i.putExtra("url", url);
        i.putExtra("name", name);
        if (!TextUtils.isEmpty(icon) && !"null".equals(icon)) i.putExtra("icon", icon);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        if (ctx instanceof Activity) ((Activity) ctx).overridePendingTransition(0, 0);
    }

    private WebView webView;
    private TextView titleView;
    private ImageView favView;
    private ProgressBar progress;
    private FrameLayout root;
    private LinearLayout sheet;
    private boolean closing, haveFavicon;
    private static final int DIM = 0x99000000;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra("url");
        String name = getIntent().getStringExtra("name");
        int bg = PriveConfig.bg(), fg = PriveConfig.fg(), gold = PriveConfig.gold();

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root = new FrameLayout(this);
        root.setBackgroundColor(0);

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(bg);
        float r = dp(12);
        sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setClipToOutline(true);

        // --- mini-app top bar: ✕ · favicon + title · ⋮ ---
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(icon(R.drawable.ic_layer_close, fg, v -> dismiss(), "Close"), new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT));
        favView = new ImageView(this);
        favView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setShape(GradientDrawable.OVAL);
        fbg.setColor(PriveConfig.card());
        favView.setBackground(fbg);
        favView.setClipToOutline(true);
        favView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(26), dp(26));
        flp.rightMargin = dp(10);
        bar.addView(favView, flp);
        titleView = new TextView(this);
        titleView.setTextColor(fg);
        titleView.setTextSize(17);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(TextUtils.isEmpty(name) ? hostOf(url) : name);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView more = icon(R.drawable.ic_ab_other, fg, null, "More");
        more.setOnClickListener(this::showMenu);
        bar.addView(more, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(gold));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        sheet.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webView = new WebView(this);
        webView.setBackgroundColor(bg);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " PriveApp");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public void onReceivedTitle(WebView view, String t) {
                if (TextUtils.isEmpty(getIntent().getStringExtra("name")) && !TextUtils.isEmpty(t) && !t.startsWith("http")) titleView.setText(t);
            }
            @Override public void onReceivedIcon(WebView view, Bitmap icon) {
                if (!haveFavicon && icon != null) { favView.setImageBitmap(icon); haveFavicon = true; }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if ("http".equals(sc) || "https".equals(sc)) return false; // stay in-app
                if ("prive".equals(sc) || "intent".equals(sc)) {
                    // A "Chat on Prive" button on this site: hand it to Website Connect.
                    Intent i = new Intent(PriveSiteBrowserActivity.this, PriveWebsiteConnectActivity.class);
                    if ("intent".equals(sc)) {
                        try { Intent it = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME); if (it.getData() != null) i.setData(it.getData()); } catch (Exception ignore) {}
                    } else i.setData(u);
                    startActivity(i);
                    return true;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignore) {}
                return true;
            }
        });
        sheet.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(sheet, slp);
        setContentView(root);
        if (PriveConfig.isLight() && android.os.Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
            lp.topMargin = b.top + dp(6);   // like Telegram's full-size mini app sheet: just below the status bar
            lp.leftMargin = b.left; lp.rightMargin = b.right;
            sheet.setLayoutParams(lp);
            sheet.setPadding(0, 0, 0, Math.max(b.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        // swipe the top bar down to close, like a Telegram mini app
        bar.setOnTouchListener(new View.OnTouchListener() {
            float startY; boolean dragging; VelocityTracker vt;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: startY = e.getRawY(); dragging = false; vt = VelocityTracker.obtain(); vt.addMovement(e); return true;
                    case MotionEvent.ACTION_MOVE: {
                        vt.addMovement(e);
                        float dy = e.getRawY() - startY;
                        if (!dragging && dy > dp(6)) dragging = true;
                        if (dragging) setOffset(Math.max(0, dy));
                        return true;
                    }
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                        vt.addMovement(e); vt.computeCurrentVelocity(1000);
                        float vy = vt.getYVelocity(); vt.recycle();
                        if (dragging && (sheet.getTranslationY() > sheet.getHeight() * 0.25f || vy > 2500)) dismiss();
                        else if (dragging) animateTo(0, null);
                        return true;
                    }
                }
                return false;
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
        loadFavicon(getIntent().getStringExtra("icon"));
        webView.loadUrl(url);

        // slide up
        root.post(() -> { setOffset(root.getHeight()); animateTo(0, null); });
    }

    private static String hostOf(String url) {
        try { String h = Uri.parse(url).getHost(); return h == null ? "" : h.replaceFirst("^www\\.", ""); } catch (Exception e) { return ""; }
    }

    private void setOffset(float y) {
        sheet.setTranslationY(y);
        float h = Math.max(1, root.getHeight());
        int a = (int) (((DIM >>> 24) & 0xff) * Math.max(0f, 1f - y / h));
        root.setBackgroundColor(a << 24);
    }

    private void animateTo(float target, Runnable end) {
        ValueAnimator va = ValueAnimator.ofFloat(sheet.getTranslationY(), target);
        va.setDuration(target == 0 ? 280 : 220);
        va.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        va.addUpdateListener(an -> setOffset((float) an.getAnimatedValue()));
        if (end != null) va.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { end.run(); } });
        va.start();
    }

    private void dismiss() {
        if (closing) return;
        closing = true;
        animateTo(root.getHeight(), () -> { finish(); overridePendingTransition(0, 0); });
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor, Gravity.END);
        m.getMenu().add(0, 1, 0, "Reload");
        m.getMenu().add(0, 2, 1, "Open in browser");
        m.getMenu().add(0, 3, 2, "Copy link");
        m.setOnMenuItemClickListener(it -> {
            String cur = webView == null ? null : webView.getUrl();
            if (cur == null) cur = getIntent().getStringExtra("url");
            if (it.getItemId() == 1) webView.reload();
            else if (it.getItemId() == 2) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(cur))); } catch (Exception ignore) {} }
            else if (it.getItemId() == 3) { AndroidUtilities.addToClipboard(cur); Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show(); }
            return true;
        });
        m.show();
    }

    private void loadFavicon(String icon) {
        if (TextUtils.isEmpty(icon) || !icon.startsWith("https://")) return;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(icon).openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(5000);
                try (InputStream in = c.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) AndroidUtilities.runOnUIThread(() -> { favView.setImageBitmap(b); haveFavicon = true; });
                }
            } catch (Exception ignore) {}
        });
    }

    private ImageView icon(int res, int color, View.OnClickListener l, String desc) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x22C9A86A, 1));
        v.setContentDescription(desc);
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else dismiss();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
