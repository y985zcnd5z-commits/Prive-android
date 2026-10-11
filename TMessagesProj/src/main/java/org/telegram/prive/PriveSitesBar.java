/*
 * Prive — unofficial Telegram client. GPL v2 or later.
 * Client mode: website shortcut chips shown above the chat list (DialogsActivity top panel).
 * Each chip opens the site inside the app (PriveSiteBrowserActivity). "+" opens Websites.
 */
package org.telegram.prive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.telegram.messenger.AndroidUtilities.dp;

public class PriveSitesBar extends HorizontalScrollView {

    public interface VisibilityListener { void onVisibilityWanted(boolean visible); }

    private static final LruCache<String, Bitmap> ICONS = new LruCache<>(40);
    private final LinearLayout row;
    private VisibilityListener listener;
    private String lastJson = null;

    public PriveSitesBar(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), 0, dp(6), 0);
        addView(row);
    }

    public void setVisibilityListener(VisibilityListener l) { listener = l; }

    /** Rebuild from the cached list now, then refresh from the server in the background. */
    public void refresh() {
        render();
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject r = PriveServer.api("GET", "/api/prefs", null);
            if (r == null || r.has("error")) return;
            String mode = r.optString("mode", null);
            if (mode != null && !"null".equals(mode)) PriveConfig.setMode(mode);
            JSONArray s = r.optJSONArray("userSites");
            if (s != null) PriveConfig.setSites(s.toString());
            AndroidUtilities.runOnUIThread(this::render);
        });
    }

    public boolean wanted() {
        return PriveConfig.MODE_CLIENT.equals(PriveConfig.mode()) && PriveConfig.sites().length() > 0;
    }

    private void render() {
        boolean want = wanted();
        if (listener != null) listener.onVisibilityWanted(want);
        if (!want) return;
        JSONArray sites = PriveConfig.sites();
        String json = sites.toString() + PriveConfig.theme();
        if (json.equals(lastJson)) return;
        lastJson = json;
        row.removeAllViews();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            final String url = s.optString("url"), name = s.optString("name", url), icon = s.optString("icon", "");
            row.addView(chip(name, TextUtils.isEmpty(icon) || "null".equals(icon) ? null : icon, false, v -> PriveSiteBrowserActivity.open(getContext(), url, name, icon)));
        }
        row.addView(chip("+", null, true, v -> PriveWebsiteConnectActivity.open(getContext(), "websites")));
    }

    /** Telegram folder-tab metrics (FilterTabsView): 44dp tall, 15sp medium, 16dp side padding, ripple, theme tab colours. */
    private LinearLayout chip(String text, String iconUrl, boolean add, OnClickListener click) {
        Context c = getContext();
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_listSelector), 2));
        l.setPadding(dp(add ? 12 : 10), 0, dp(add ? 12 : 10), 0);
        if (!add) {
            ImageView iv = new ImageView(c);
            GradientDrawable ib = new GradientDrawable();
            ib.setShape(GradientDrawable.OVAL);
            ib.setColor(PriveConfig.isLight() ? 0xFFEFEBE3 : 0xFF141628);
            iv.setBackground(ib);
            iv.setClipToOutline(true);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            l.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
            if (iconUrl != null) loadIcon(iv, iconUrl);
        }
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, add ? 20 : 15);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(160));
        tv.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_actionBarTabUnactiveText));
        tv.setTypeface(AndroidUtilities.bold());
        if (add) tv.setContentDescription("Add website");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = add ? 0 : dp(8);
        l.addView(tv, tlp);
        l.setOnClickListener(click);
        l.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        return l;
    }

    private static void loadIcon(ImageView iv, String url) {
        Bitmap cached = ICONS.get(url);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        if (!url.startsWith("https://")) return;
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream is = c.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(is);
                    if (b == null) return;
                    ICONS.put(url, b);
                    AndroidUtilities.runOnUIThread(() -> iv.setImageBitmap(b));
                }
            } catch (Exception ignore) {
            } finally { if (c != null) c.disconnect(); }
        });
    }
}
