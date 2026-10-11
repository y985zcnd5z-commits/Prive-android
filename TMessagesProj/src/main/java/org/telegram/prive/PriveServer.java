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
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Links the logged-in Telegram account to a Prive server account, VERIFIED by Telegram:
 * POST /api/tg/begin -> {nonce, secret, bot}; this app silently sends /start <nonce> to the Prive bot with the
 * user's own MTProto session (messages.startBot); the server's bot sees the Telegram-verified sender id;
 * POST /api/tg/finish {nonce, secret, telegramUserId} -> {token}. The bot chat is removed afterwards.
 * Legacy unverified /api/telegram/link is only used if the server has no bot configured.
 * Blocking call: run it off the UI thread.
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

    private static JSONObject postJson(String path, JSONObject body, int[] status) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod(body == null ? "GET" : "POST");
            String t = token();
            if (t != null) c.setRequestProperty("Authorization", "Bearer " + t);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            if (status != null) status[0] = code;
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(bos.size() > 0 ? bos.toString("UTF-8") : "{}");
        } finally {
            c.disconnect();
        }
    }

    /** Blocking MTProto call on the current account. */
    private static TLObject invoke(TLObject req) throws Exception {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final TLObject[] out = new TLObject[1];
        final TLRPC.TL_error[] err = new TLRPC.TL_error[1];
        ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(req, (res, e) -> { out[0] = res; err[0] = e; latch.countDown(); });
        if (!latch.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new Exception("timeout");
        if (err[0] != null) throw new Exception(err[0].text);
        return out[0];
    }

    private static volatile long lastVerifiedAttempt;

    /** Verified link through the Prive bot. Returns null result (not error) when the server has no bot. */
    private static LinkResult verifiedLink(TLRPC.User user) throws Exception {
        JSONObject cfg = postJson("/api/tg/config", null, null);
        if (!cfg.optBoolean("enabled")) return null;
        LinkResult r = new LinkResult();
        if (System.currentTimeMillis() - lastVerifiedAttempt < 20000) { r.error = "busy"; return r; }
        lastVerifiedAttempt = System.currentTimeMillis();
        int[] st = new int[1];
        JSONObject b = postJson("/api/tg/begin", new JSONObject(), st);
        if (st[0] >= 400) { r.error = b.optString("error", "HTTP " + st[0]); return r; }
        String nonce = b.getString("nonce"), secret = b.getString("secret"), bot = b.getString("bot");

        TLRPC.TL_contacts_resolveUsername rq = new TLRPC.TL_contacts_resolveUsername();
        rq.username = bot;
        TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) invoke(rq);
        TLRPC.User botUser = null;
        for (TLRPC.User u : rp.users) if (u.bot && bot.equalsIgnoreCase(org.telegram.messenger.UserObject.getPublicUsername(u))) botUser = u;
        if (botUser == null) { r.error = "bot not found"; return r; }
        final TLRPC.User fBot = botUser;
        final int account = UserConfig.selectedAccount;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).putUser(fBot, false));

        TLRPC.TL_messages_startBot sb = new TLRPC.TL_messages_startBot();
        TLRPC.TL_inputUser iu = new TLRPC.TL_inputUser();
        iu.user_id = botUser.id; iu.access_hash = botUser.access_hash;
        TLRPC.TL_inputPeerUser ip = new TLRPC.TL_inputPeerUser();
        ip.user_id = botUser.id; ip.access_hash = botUser.access_hash;
        sb.bot = iu; sb.peer = ip; sb.random_id = org.telegram.messenger.Utilities.random.nextLong(); sb.start_param = nonce;
        TLObject upd = invoke(sb);
        if (upd instanceof TLRPC.Updates) MessagesController.getInstance(account).processUpdates((TLRPC.Updates) upd, false);

        JSONObject fin = new JSONObject();
        fin.put("nonce", nonce); fin.put("secret", secret); fin.put("telegramUserId", String.valueOf(user.id));
        try {
            for (int i = 0; i < 25; i++) {
                Thread.sleep(i == 0 ? 800 : 1200);
                JSONObject f = postJson("/api/tg/finish", fin, st);
                if (st[0] == 202) continue;
                if (st[0] >= 400) { r.error = f.optString("error", "HTTP " + st[0]); return r; }
                r.token = f.getString("token");
                r.isNew = f.optBoolean("isNew");
                prefs().edit().putString("token_" + user.id, r.token).remove("linkSecret_" + user.id).apply();
                return r;
            }
            r.error = "timeout";
            return r;
        } finally {
            // remove the (now empty) Prive bot chat so it never shows in the chat list
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).deleteDialog(fBot.id, 0), 1500);
        }
    }

    /** Uses the stored (verified) token if there is one; links only when needed. */
    public static LinkResult linkIfNeeded(TLRPC.User user) {
        String t = prefs().getString("token_" + user.id, null);
        if (t != null) { LinkResult r = new LinkResult(); r.token = t; return r; }
        return link(user);
    }

    public static LinkResult link(TLRPC.User user) {
        try {
            LinkResult v = verifiedLink(user);
            if (v != null) return v;
        } catch (Exception e) {
            LinkResult r = new LinkResult();
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        }
        return legacyLink(user);
    }

    /** Old trust-on-first-use link; the server refuses it once the Prive bot is configured. */
    private static LinkResult legacyLink(TLRPC.User user) {
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
