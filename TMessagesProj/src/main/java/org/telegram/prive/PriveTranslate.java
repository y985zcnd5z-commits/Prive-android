package org.telegram.prive;

import android.app.Activity;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Prive per-chat translation. Telegram Premium accounts keep Telegram's own engine; everyone else is translated by
 * the Prive server (/api/translate: DeepL Pro, cached server-side). Telegram's chat translation UI (top bar with
 * "Show original", translated bubbles) is reused; Prive adds the per-chat language picker and the engine.
 */
public final class PriveTranslate {
    private PriveTranslate() {}

    // Preferred languages first; the rest follows alphabetically (server list when available).
    private static final String[][] PRIORITY = {{"EN", "English"}, {"AR", "Arabic"}, {"PT", "Portuguese"}, {"ES", "Spanish"}, {"RU", "Russian"}, {"RO", "Romanian"}, {"FR", "French"}, {"HU", "Hungarian"}, {"BG", "Bulgarian"}, {"CS", "Czech"}};
    private static volatile List<String[]> languages;

    public static boolean available() {
        return !TextUtils.isEmpty(PriveServer.token());
    }

    /** Use the Prive engine for this account (no Telegram Premium). */
    public static boolean usePrive(int account) {
        return !UserConfig.getInstance(account).isPremium() && available();
    }

    public interface Done { void run(String[] results); }

    /** Translate texts into lang (ISO code). Runs off the UI thread; results null on failure. */
    public static void translate(List<String> texts, String lang, Done done) {
        Utilities.globalQueue.postRunnable(() -> {
            String[] out = null;
            try {
                JSONObject body = new JSONObject();
                body.put("target", lang == null ? "EN" : lang.split("[-_]")[0].toUpperCase());
                JSONArray arr = new JSONArray();
                for (String t : texts) arr.put(t == null ? "" : t);
                body.put("texts", arr);
                JSONObject r = PriveServer.api("POST", "/api/translate", body);
                JSONArray tr = r == null ? null : r.optJSONArray("translations");
                if (tr != null) {
                    out = new String[texts.size()];
                    for (int i = 0; i < out.length && i < tr.length(); i++) {
                        JSONObject o = tr.optJSONObject(i);
                        out[i] = o == null ? null : o.optString("text", null);
                    }
                }
            } catch (Exception ignore) {}
            final String[] res = out;
            done.run(res);
        });
    }

    private static List<String[]> fallback() {
        List<String[]> l = new ArrayList<>();
        for (String[] p : PRIORITY) l.add(p);
        return l;
    }

    private static void loadLanguages(Runnable then) {
        Utilities.globalQueue.postRunnable(() -> {
            try { PriveServer.ensureToken(); } catch (Exception ignore) {}
            if (languages == null) {
                List<String[]> l = new ArrayList<>();
                try {
                    JSONObject r = PriveServer.api("GET", "/api/translate/languages", null);
                    JSONArray a = r == null ? null : r.optJSONArray("languages");
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        l.add(new String[]{o.getString("code"), o.getString("name")});
                    }
                } catch (Exception ignore) {}
                if (!l.isEmpty()) languages = l;
            }
            AndroidUtilities.runOnUIThread(then);
        });
    }

    private static void remember(long dialogId, String code) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("chat", "tg:" + dialogId);
                b.put("lang", code == null ? JSONObject.NULL : code);
                PriveServer.api("POST", "/api/translate/chat", b);
            } catch (Exception ignore) {}
        });
    }

    /** The per-chat language button: searchable list (Off + all languages); applies and remembers the choice. */
    public static void showPicker(BaseFragment fragment, long dialogId) {
        Activity a = fragment == null ? null : fragment.getParentActivity();
        if (a == null) return;
        int account = fragment.getCurrentAccount();
        loadLanguages(() -> {
            if (!available() && !UserConfig.getInstance(account).isPremium()) {
                BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.error, "Couldn't reach Prive. Check your connection and try again.").show();
                return;
            }
            if (languages == null) languages = fallback();
            buildPicker(fragment, a, account, dialogId);
        });
    }

    private static void buildPicker(BaseFragment fragment, Activity a, int account, long dialogId) {
        TranslateController tc = MessagesController.getInstance(account).getTranslateController();
        boolean on = tc.isTranslatingDialog(dialogId);
        String cur = on ? tc.getDialogTranslateTo(dialogId) : null;

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(a);
        search.setHint("Search");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        search.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        search.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));
        ((LinearLayout.LayoutParams) search.getLayoutParams()).setMargins(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), AndroidUtilities.dp(4));
        ScrollView sv = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(380)));

        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("Translate this chat");
        b.setView(root);
        b.setNegativeButton("Cancel", null);
        AlertDialog dlg = b.create();

        List<String[]> items = new ArrayList<>();
        items.add(new String[]{"OFF", "Off"});
        items.addAll(languages);
        List<View> rows = new ArrayList<>();
        for (String[] it : items) {
            boolean sel = "OFF".equals(it[0]) ? cur == null : cur != null && cur.equalsIgnoreCase(it[0]);
            TextView t = new TextView(a);
            t.setText(it[1]);
            t.setTag(it[0] + " " + it[1].toLowerCase());
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            t.setTextColor(Theme.getColor(sel ? Theme.key_dialogTextBlue : Theme.key_dialogTextBlack));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
            t.setBackground(Theme.getSelectorDrawable(false));
            if (sel) t.setCompoundDrawablesWithIntrinsicBounds(0, 0, org.telegram.messenger.R.drawable.checkbig, 0);
            t.setOnClickListener(v -> {
                dlg.dismiss();
                apply(fragment, account, dialogId, "OFF".equals(it[0]) ? null : it[0], it[1]);
            });
            list.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));
            rows.add(t);
        }
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int bf, int c) {}
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase();
                for (View r : rows) r.setVisibility(q.isEmpty() || String.valueOf(r.getTag()).toLowerCase().contains(q) ? View.VISIBLE : View.GONE);
            }
        });
        fragment.showDialog(dlg);
    }

    private static void apply(BaseFragment fragment, int account, long dialogId, String code, String name) {
        TranslateController tc = MessagesController.getInstance(account).getTranslateController();
        if (code == null) {
            tc.toggleTranslatingDialog(dialogId, false);
            BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.msg_translate, "Translation off for this chat").show();
        } else {
            tc.setHideTranslateDialog(dialogId, false, true);
            tc.setDialogTranslateTo(dialogId, code.toLowerCase());
            tc.toggleTranslatingDialog(dialogId, true);
            BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.msg_translate, "Translating this chat into " + name).show();
        }
        remember(dialogId, code);
    }
}
