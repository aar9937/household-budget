package com.openai.ourhomebudget.v21;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class NotificationCaptureService extends NotificationListenerService {
    private static final String PREF = "v21_auto";
    private static final String KEY = "notifications";
    private static final int MAX = 300;
    private static final Pattern MONEY = Pattern.compile("[+\\-−]?\\s*\\d[\\d,]{1,}\\s*원");
    private static final Pattern FINANCE = Pattern.compile("승인|결제|카드|취소|출금|입금|이용|체크|신용|잔액|매입");

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        try {
            Notification n = sbn.getNotification();
            Bundle e = n.extras;
            String title = str(e.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(e.getCharSequence(Notification.EXTRA_TEXT));
            String big = str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
            StringBuilder lineText = new StringBuilder();
            CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence cs : lines) if (cs != null) lineText.append(cs).append('\n');
            }
            String combined = title + "\n" + text + "\n" + big + "\n" + lineText;
            if (!MONEY.matcher(combined).find() || !FINANCE.matcher(combined).find()) return;

            JSONObject o = new JSONObject();
            o.put("key", sbn.getKey());
            o.put("pkg", sbn.getPackageName());
            o.put("time", sbn.getPostTime());
            o.put("title", title);
            o.put("text", text);
            o.put("bigText", big);
            o.put("lines", lineText.toString().trim());
            append(this, o);
        } catch (Exception ignored) {}
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    public static JSONArray getQueue(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void clearQueue(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, "[]").apply();
    }

    private static synchronized void append(Context c, JSONObject item) {
        JSONArray old = getQueue(c);
        JSONArray out = new JSONArray();
        String key = item.optString("key");
        for (int i = Math.max(0, old.length() - (MAX - 1)); i < old.length(); i++) {
            JSONObject x = old.optJSONObject(i);
            if (x != null && !key.equals(x.optString("key"))) out.put(x);
        }
        out.put(item);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, out.toString()).apply();
    }

    public static synchronized void removeKeys(Context c, String jsonKeys) {
        try {
            JSONArray ks = new JSONArray(jsonKeys);
            Set<String> remove = new HashSet<>();
            for (int i = 0; i < ks.length(); i++) remove.add(ks.optString(i));

            JSONArray old = getQueue(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject x = old.optJSONObject(i);
                if (x != null && !remove.contains(x.optString("key"))) out.put(x);
            }
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, out.toString()).apply();
        } catch (Exception ignored) {}
    }
}
