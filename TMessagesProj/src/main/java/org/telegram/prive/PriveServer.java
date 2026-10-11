/*
 * Prive — unofficial Telegram client. GPL v2 or later (same as Telegram for Android).
 * Client for the Prive Website Connect backend (see /server in the Prive repo).
 */
package org.telegram.prive;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Links the logged-in Telegram account to a Prive server account.
 * POST {PRIVE_SERVER_URL}/api/telegram/link  ->  { token, linkSecret, user }
 * The linkSecret is stored per Telegram user id (trust-on-first-use, see server README) and must be
 * presented on every later link. Blocking call: run it off the UI thread.
 */
public final class PriveServer {

    public static final class LinkResult {
        public String token;
        public String error;
        public boolean isNew;
    }

    private PriveServer() {}

    public static String baseUrl() {
        String u = BuildVars.PRIVE_SERVER_URL;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prive_server", Context.MODE_PRIVATE);
    }

    /** Returns null when no Telegram account is logged in. */
    public static TLRPC.User currentTelegramUser() {
        UserConfig cfg = UserConfig.getInstance(UserConfig.selectedAccount);
        if (cfg == null || !cfg.isClientActivated()) return null;
        return cfg.getCurrentUser();
    }

    /** Stored Prive server token for the logged-in Telegram user (null if not linked yet). */
    public static String token() {
        TLRPC.User u = currentTelegramUser();
        return u == null ? null : prefs().getString("token_" + u.id, null);
    }

    /** Blocking: returns a token, linking first if needed. */
    public static String ensureToken() {
        String t = token();
        if (t != null) return t;
        TLRPC.User u = currentTelegramUser();
        if (u == null) return null;
        LinkResult r = link(u);
        return r.token;
    }

    /** Blocking JSON call to the Prive server with the stored token. Returns null on failure; relinks once on 401. */
    public static JSONObject api(String method, String path, JSONObject body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String t = ensureToken();
            if (t == null) return null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestMethod(method);
                c.setRequestProperty("Authorization", "Bearer " + t);
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                }
                int code = c.getResponseCode();
                if (code == 401 && attempt == 0) {
                    TLRPC.User u = currentTelegramUser();
                    if (u != null) prefs().edit().remove("token_" + u.id).apply();
                    continue;
                }
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (is != null && (n = is.read(buf)) > 0) bos.write(buf, 0, n);
                JSONObject res = new JSONObject(bos.size() > 0 ? bos.toString("UTF-8") : "{}");
                if (code >= 400 && !res.has("error")) res.put("error", "HTTP " + code);
                return res;
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    public static LinkResult link(TLRPC.User user) {
        LinkResult r = new LinkResult();
        HttpURLConnection c = null;
        try {
            String tgId = String.valueOf(user.id);
            JSONObject body = new JSONObject();
            body.put("telegramUserId", tgId);
            if (user.phone != null) body.put("phone", user.phone);
            if (user.first_name != null) body.put("firstName", user.first_name);
            if (user.last_name != null) body.put("lastName", user.last_name);
            String uname = org.telegram.messenger.UserObject.getPublicUsername(user);
            if (uname != null) body.put("username", uname);
            String secret = prefs().getString("linkSecret_" + tgId, null);
            if (secret != null) body.put("linkSecret", secret);

            c = (HttpURLConnection) new URL(baseUrl() + "/api/telegram/link").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (BuildConfig.PRIVE_APP_LINK_KEY.length() > 0) c.setRequestProperty("X-Prive-App-Key", BuildConfig.PRIVE_APP_LINK_KEY);
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject res = new JSONObject(bos.size() > 0 ? bos.toString("UTF-8") : "{}");
            if (code >= 400) {
                r.error = res.optString("message", res.optString("error", "HTTP " + code));
                return r;
            }
            r.token = res.getString("token");
            r.isNew = res.optBoolean("isNew");
            String newSecret = res.optString("linkSecret", null);
            SharedPreferences.Editor e = prefs().edit();
            if (newSecret != null) e.putString("linkSecret_" + tgId, newSecret);
            e.putString("token_" + tgId, r.token).apply();
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }
}
