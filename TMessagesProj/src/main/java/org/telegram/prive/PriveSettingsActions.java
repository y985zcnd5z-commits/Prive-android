package org.telegram.prive;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.text.SpannableStringBuilder;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.SettingsActivity;

/** Prive rows inside Telegram's own Settings screen: mode, notifications channel, appearance, link Prive Web. */
public final class PriveSettingsActions {
    private PriveSettingsActions() {}

    public static void chooseMode(SettingsActivity f) {
        Activity a = f.getParentActivity();
        if (a == null) return;
        String cur = PriveConfig.mode();
        CharSequence[] items = {"Client", "Website administrator"};
        int sel = PriveConfig.MODE_ADMIN.equals(cur) ? 1 : 0;
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("Prive mode");
        b.setItems(new CharSequence[]{(sel == 0 ? "✓  " : "     ") + items[0], (sel == 1 ? "✓  " : "     ") + items[1]}, (d, which) -> {
            String mode = which == 1 ? PriveConfig.MODE_ADMIN : PriveConfig.MODE_CLIENT;
            PriveConfig.setMode(mode);
            f.priveRefresh();
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("mode", mode);
                    PriveServer.api("POST", "/api/prefs/mode", body);
                } catch (Exception ignore) {}
            });
        });
        b.setNegativeButton("Cancel", null);
        f.showDialog(b.create());
    }

    public static void openNotificationChannel(Activity a) {
        if (a == null) return;
        PriveWcNotifier.ensureChannel(a);
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            i.putExtra(Settings.EXTRA_CHANNEL_ID, PriveWcNotifier.CHANNEL);
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.getPackageName()));
        }
        try { a.startActivity(i); } catch (Exception ignore) {}
    }

    public static void toggleAppearance(SettingsActivity f) {
        PriveConfig.setTheme(PriveConfig.isLight() ? "noir" : "light");
        f.priveRefresh();
    }

    public static void linkWeb(SettingsActivity f) {
        Activity a = f.getParentActivity();
        if (a == null) return;
        AlertDialog progress = new AlertDialog(a, AlertDialog.ALERT_TYPE_SPINNER);
        f.showDialog(progress);
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject r = PriveServer.api("POST", "/api/link/code", new JSONObject());
            AndroidUtilities.runOnUIThread(() -> {
                try { progress.dismiss(); } catch (Exception ignore) {}
                AlertDialog.Builder b = new AlertDialog.Builder(a);
                b.setTitle("Link Prive Web");
                if (r == null || !r.has("code")) {
                    b.setMessage("Couldn't get a code right now. Check your connection and try again.");
                    b.setPositiveButton("OK", null);
                } else {
                    String c = r.optString("code");
                    SpannableStringBuilder m = new SpannableStringBuilder();
                    m.append("Open priveconnect.com/web, choose your mode, then tap Settings → Websites → Link with a code from the Prive app and enter:\n\n");
                    int st = m.length();
                    m.append(c.substring(0, 4)).append(" ").append(c.substring(4));
                    m.setSpan(new android.text.style.RelativeSizeSpan(1.8f), st, m.length(), 0);
                    m.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, m.length(), 0);
                    m.append("\n\nThe code works once and expires in 5 minutes. Never share it with anyone.");
                    b.setMessage(m);
                    b.setPositiveButton("Done", null);
                }
                f.showDialog(b.create());
            });
        });
    }

    public static final String SOURCE_ANDROID = "https://github.com/y985zcnd5z-commits/prive-android";
    public static final String SOURCE_WEB = "https://github.com/y985zcnd5z-commits/prive-web";

    public static void about(SettingsActivity f) {
        Activity a = f.getParentActivity();
        if (a == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("About Prive");
        b.setMessage("Prive is an unofficial Telegram client, not affiliated with Telegram.\n\n"
            + "Prive for Android is based on Telegram for Android and licensed under the GNU GPL v2 or later. "
            + "Prive Web is based on Telegram WebK (GPL-3.0).\n\nSource code:\n" + SOURCE_ANDROID + "\n" + SOURCE_WEB);
        b.setPositiveButton("Source code", (d, w) -> org.telegram.messenger.browser.Browser.openUrl(a, SOURCE_ANDROID));
        b.setNegativeButton("Close", null);
        f.showDialog(b.create());
    }
}
