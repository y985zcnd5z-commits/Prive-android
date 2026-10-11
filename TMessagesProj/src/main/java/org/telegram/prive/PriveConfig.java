/*
 * Prive — unofficial Telegram client. GPL v2 or later (same as Telegram for Android).
 * Small per-install Prive settings: mode (client / website administrator), cached website shortcuts,
 * keep-alive + battery prompt flags, Website Connect notification cursor.
 */
package org.telegram.prive;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

public final class PriveConfig {
    private PriveConfig() {}

    public static final String MODE_CLIENT = "client", MODE_ADMIN = "admin";

    static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prive_config", Context.MODE_PRIVATE);
    }

    /** True only when the build was made with a real google-services.json (see prive.gradle / README). */
    public static boolean fcmConfigured() {
        return BuildConfig.PRIVE_FCM_CONFIGURED;
    }

    /** Keep-alive foreground connection: default ON unless real FCM push is configured. */
    public static boolean keepAliveDefault() {
        return !fcmConfigured();
    }

    public static String mode() { return prefs().getString("mode", null); }
    public static void setMode(String m) { prefs().edit().putString("mode", m).apply(); }

    public static JSONArray sites() {
        try { return new JSONArray(prefs().getString("sites", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    public static void setSites(String json) { prefs().edit().putString("sites", json == null ? "[]" : json).apply(); }

    public static boolean batteryPrompted() { return prefs().getBoolean("batteryPrompted", false); }
    public static void setBatteryPrompted() { prefs().edit().putBoolean("batteryPrompted", true).apply(); }

    public static long wcSince() { return prefs().getLong("wcSince", 0); }
    public static void setWcSince(long t) { prefs().edit().putLong("wcSince", t).apply(); }

    public static String theme() { return prefs().getString("theme", "noir"); }
    public static void setTheme(String t) { prefs().edit().putString("theme", t).apply(); }
    public static boolean isLight() { return "light".equals(theme()); }

    // Prive Noir / Light palette for native Prive screens.
    public static int bg() { return isLight() ? 0xFFF7F5F0 : 0xFF0A0B12; }
    public static int fg() { return isLight() ? 0xFF0A0B12 : 0xFFF3EDE2; }
    public static int muted() { return isLight() ? 0xFF5B6475 : 0xFFA89F90; }
    public static int gold() { return isLight() ? 0xFF7D5F22 : 0xFFC9A86A; }
    public static int card() { return isLight() ? 0xFFFFFFFF : 0xFF0E1020; }
    public static int border() { return isLight() ? 0xFFE6E1D6 : 0x38C9A86A; }
}
