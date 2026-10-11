/*
 * Prive — unofficial Telegram client. GPL v2 or later.
 * After Telegram login, once: (1) the mode chooser (client / website administrator) if the server has no mode
 * for this user yet, then (2) a one-time battery-optimisation exemption prompt so the keep-alive connection
 * (and therefore notifications) isn't killed by Android.
 */
package org.telegram.prive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

public final class PriveFirstRun {
    private PriveFirstRun() {}

    private static boolean checking, choosing;

    public static void check(Activity a, Runnable onPrefsLoaded) {
        if (a == null || checking || PriveServer.currentTelegramUser() == null) return;
        if (PriveConfig.mode() != null) { maybeBattery(a); return; }
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject r = PriveServer.api("GET", "/api/prefs", null);
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (r == null || r.has("error")) return; // offline: try again next resume
                String m = r.optString("mode", null);
                if (r.optJSONArray("userSites") != null) PriveConfig.setSites(r.optJSONArray("userSites").toString());
                if (m != null && !"null".equals(m) && !m.isEmpty()) {
                    PriveConfig.setMode(m);
                    if (onPrefsLoaded != null) onPrefsLoaded.run();
                    maybeBattery(a);
                } else if (!choosing && !a.isFinishing()) {
                    choosing = true;
                    PriveWebsiteConnectActivity.open(a, "choose");
                }
            });
        });
    }

    static void chooserDone() { choosing = false; }

    public static boolean ignoringBatteryOptimizations(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    private static void maybeBattery(Activity a) {
        if (PriveConfig.batteryPrompted() || ignoringBatteryOptimizations(a) || a.isFinishing()) return;
        PriveConfig.setBatteryPrompted();
        new AlertDialog.Builder(a)
                .setTitle("Get messages instantly")
                .setMessage("Prive keeps a light connection open so new messages and Website Connect chats arrive right away, like WhatsApp. "
                        + "Allow Prive to run in the background so Android doesn't pause it to save battery.")
                .setPositiveButton("Allow", (d, w) -> requestExemption(a))
                .setNegativeButton("Not now", null)
                .show();
    }

    @android.annotation.SuppressLint("BatteryLife")
    public static void requestExemption(Activity a) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            try { a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignore) {}
        }
    }
}
