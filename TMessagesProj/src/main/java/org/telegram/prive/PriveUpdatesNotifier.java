/*
 * Prive — unofficial Telegram client. GPL v2 or later.
 * "Website updates" notifications: posts/changes on websites the user follows (FCM data "prive_update" from the
 * Prive server). Own channel, one notification per site (inbox style, last 5 lines) plus a group summary;
 * tapping opens that site's Updates feed in Websites.
 */
package org.telegram.prive;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PriveUpdatesNotifier {
    public static final String CHANNEL = "prive_site_updates";
    private static final String GROUP = "prive_updates_group";
    private static final int SUMMARY_ID = 0x5A170000;
    private static final Map<String, List<String>> lines = new HashMap<>();
    private static final Map<String, String> names = new HashMap<>();

    private PriveUpdatesNotifier() {}

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Website updates", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("New posts and changes on websites you follow (mute a site in Websites)");
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    public static void show(String feed, String site, String title, String body) {
        if (TextUtils.isEmpty(feed)) return;
        AndroidUtilities.runOnUIThread(() -> {
            Context c = ApplicationLoader.applicationContext;
            ensureChannel(c);
            List<String> l = lines.get(feed);
            if (l == null) { l = new ArrayList<>(); lines.put(feed, l); }
            String line = TextUtils.isEmpty(title) ? (body == null ? "" : body) : title;
            l.add(line);
            while (l.size() > 5) l.remove(0);
            String name = TextUtils.isEmpty(site) ? "Website update" : site;
            names.put(feed, name);
            int id = SUMMARY_ID + 1 + (feed.hashCode() & 0xFFFF);
            NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle().setBigContentTitle(name);
            for (String x : l) style.addLine(x);
            if (l.size() > 1) style.setSummaryText(l.size() + " updates");
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setColor(0xFFC9A86A)
                    .setContentTitle(name)
                    .setContentText(line)
                    .setSubText("Website updates")
                    .setStyle(l.size() > 1 ? style : new NotificationCompat.BigTextStyle().bigText(TextUtils.isEmpty(body) || line.equals(body) ? line : line + "\n" + body))
                    .setGroup(GROUP)
                    .setNumber(l.size())
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                    .setContentIntent(open(c, feed, id))
                    .setDeleteIntent(null);
            try {
                NotificationManagerCompat nm = NotificationManagerCompat.from(c);
                nm.notify(id, b.build());
                if (lines.size() > 1) {
                    NotificationCompat.InboxStyle sum = new NotificationCompat.InboxStyle();
                    for (Map.Entry<String, List<String>> e : lines.entrySet()) sum.addLine(names.get(e.getKey()) + ": " + e.getValue().get(e.getValue().size() - 1));
                    nm.notify(SUMMARY_ID, new NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.notification).setColor(0xFFC9A86A)
                            .setContentTitle("Website updates").setStyle(sum).setGroup(GROUP).setGroupSummary(true).setAutoCancel(true).build());
                }
            } catch (SecurityException ignore) { /* POST_NOTIFICATIONS not granted */ }
        });
    }

    public static void clear(String feed) {
        lines.remove(feed);
        try { NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(SUMMARY_ID + 1 + (feed.hashCode() & 0xFFFF)); } catch (Exception ignore) {}
        if (lines.isEmpty()) try { NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(SUMMARY_ID); } catch (Exception ignore) {}
    }

    private static PendingIntent open(Context c, String feed, int id) {
        Intent i = new Intent(c, PriveWebsiteConnectActivity.class)
                .setData(Uri.parse("prive://updates?feed=" + Uri.encode(feed)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
