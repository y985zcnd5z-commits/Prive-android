/*
 * Prive — unofficial Telegram client. GPL v2 or later.
 * Website Connect notifications without FCM: while the keep-alive service runs, poll the Prive server
 * (/api/wc/pending) and show WhatsApp-style notifications on a separate "Website Connect" channel:
 * one notification per chat (MessagingStyle, last lines), summary group, tap opens the chat, "Mark as read".
 * Once Firebase exists, the server can push the same payload via FCM instead of polling.
 */
package org.telegram.prive;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PriveWcNotifier {
    private PriveWcNotifier() {}

    public static final String CHANNEL = "prive_website_connect";
    private static final String GROUP = "prive_wc_group";
    private static final int SUMMARY_ID = 0x5C0000;
    private static final long INTERVAL_MS = 30_000;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Map<String, List<String[]>> lines = new HashMap<>(); // convId -> [title, text, at]
    private static boolean running;

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Utilities.globalQueue.postRunnable(PriveWcNotifier::pollOnce);
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public static void start() {
        if (running) return;
        running = true;
        handler.postDelayed(tick, 5_000);
        if (org.telegram.messenger.SharedConfig.pushType == org.telegram.messenger.PushListenerController.PUSH_TYPE_FIREBASE) registerFcm(org.telegram.messenger.SharedConfig.pushString);
    }

    /** FCM data message from the Prive server ("prive_wc"): a content-free wake-up, fetch the pending messages now. */
    public static void onFcmWake() {
        Utilities.globalQueue.postRunnable(PriveWcNotifier::pollOnce);
    }

    private static String fcmSent;
    /** Tell the Prive server our FCM token so Website Connect messages are pushed instead of polled. */
    public static void registerFcm(String fcmToken) {
        if (fcmToken == null || fcmToken.isEmpty() || !PriveConfig.fcmConfigured() || fcmToken.equals(fcmSent)) return;
        Utilities.globalQueue.postRunnable(() -> {
            if (PriveServer.currentTelegramUser() == null) return;
            try {
                JSONObject b = new JSONObject();
                b.put("token", fcmToken);
                b.put("platform", "android");
                JSONObject r = PriveServer.api("POST", "/api/push/fcm", b);
                if (r != null && !r.has("error")) fcmSent = fcmToken;
            } catch (Exception ignore) {}
        });
    }

    public static void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public static boolean channelEnabled(Context c) {
        if (c == null) c = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (!androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT < 26) return true;
        ensureChannel(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(CHANNEL);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Website Connect chats", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Messages in chats started from a website's Chat on Prive button");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    private static void pollOnce() {
        if (PriveServer.currentTelegramUser() == null) return;
        long since = PriveConfig.wcSince();
        if (since == 0) { PriveConfig.setWcSince(System.currentTimeMillis()); return; } // first run: only new messages
        JSONObject r = PriveServer.api("GET", "/api/wc/pending?since=" + since, null);
        if (r == null || r.has("error")) return;
        JSONArray msgs = r.optJSONArray("messages");
        long now = r.optLong("now", System.currentTimeMillis());
        if (msgs == null || msgs.length() == 0) { PriveConfig.setWcSince(Math.max(since, now - 5000)); return; }
        long maxAt = since;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            maxAt = Math.max(maxAt, m.optLong("at"));
            String conv = m.optString("convId");
            List<String[]> l = lines.get(conv);
            if (l == null) { l = new ArrayList<>(); lines.put(conv, l); }
            l.add(new String[]{m.optString("title", "Website Connect"), m.optString("text", ""), String.valueOf(m.optLong("at")), m.optString("site", "")});
            while (l.size() > 6) l.remove(0);
        }
        PriveConfig.setWcSince(maxAt);
        final long fMax = maxAt;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> showAll(fMax));
    }

    private static void showAll(long when) {
        Context c = ApplicationLoader.applicationContext;
        ensureChannel(c);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        try {
            int total = 0;
            for (Map.Entry<String, List<String[]>> e : lines.entrySet()) {
                String conv = e.getKey();
                List<String[]> l = e.getValue();
                if (l.isEmpty()) continue;
                total += l.size();
                String title = l.get(l.size() - 1)[0], site = l.get(l.size() - 1)[3];
                Person me = new Person.Builder().setName("You").build();
                Person them = new Person.Builder().setName(title).build();
                NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me)
                        .setConversationTitle(site == null || site.isEmpty() ? title : title + " · " + site);
                for (String[] x : l) style.addMessage(x[1], Long.parseLong(x[2]), them);
                int id = SUMMARY_ID + 1 + (conv.hashCode() & 0xFFFF);
                NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                        .setSmallIcon(R.drawable.notification)
                        .setColor(0xFFC9A86A)
                        .setStyle(style)
                        .setGroup(GROUP)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setNumber(l.size())
                        .setWhen(Long.parseLong(l.get(l.size() - 1)[2]))
                        .setContentIntent(openIntent(c, conv, id))
                        .setDeleteIntent(PendingIntent.getBroadcast(c, id + 1, new Intent(c, Action.class).setAction("clear").putExtra("conv", conv), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                        .addAction(R.drawable.msg_markread, "Mark as read", PendingIntent.getBroadcast(c, id + 2, new Intent(c, Action.class).setAction("read").putExtra("conv", conv).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                nm.notify(id, b.build());
            }
            if (total > 0) {
                NotificationCompat.Builder s = new NotificationCompat.Builder(c, CHANNEL)
                        .setSmallIcon(R.drawable.notification)
                        .setColor(0xFFC9A86A)
                        .setContentTitle("Website Connect")
                        .setContentText(total + " new message" + (total == 1 ? "" : "s"))
                        .setGroup(GROUP).setGroupSummary(true).setAutoCancel(true);
                nm.notify(SUMMARY_ID, s.build());
            }
        } catch (SecurityException ignore) { /* POST_NOTIFICATIONS not granted */ }
    }

    private static PendingIntent openIntent(Context c, String conv, int id) {
        Intent i = new Intent(c, PriveWebsiteConnectActivity.class)
                .setData(Uri.parse("prive://open?conv=" + Uri.encode(conv)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void clear(String conv) {
        lines.remove(conv);
        NotificationManagerCompat nm = NotificationManagerCompat.from(ApplicationLoader.applicationContext);
        nm.cancel(SUMMARY_ID + 1 + (conv.hashCode() & 0xFFFF));
        if (lines.isEmpty()) nm.cancel(SUMMARY_ID);
    }

    public static class Action extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String conv = intent.getStringExtra("conv");
            if (conv == null) return;
            clear(conv);
            if ("read".equals(intent.getAction())) {
                PendingResult pr = goAsync();
                Utilities.globalQueue.postRunnable(() -> {
                    try { PriveServer.api("POST", "/api/conv/" + Uri.encode(conv) + "/read", new JSONObject()); } finally { pr.finish(); }
                });
            }
        }
    }
}
