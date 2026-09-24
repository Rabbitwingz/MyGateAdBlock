package com.local.gateadskipper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Settings, the user's block list and a short log of MyGate screens, all in SharedPreferences. */
final class Store {
    private static final String PREFS = "gate_ad_skipper";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BLOCKED = "blocked_classes";
    private static final String KEY_LOG = "screen_log";
    private static final String KEY_SKIPPED = "skipped_count";
    private static final int LOG_SIZE = 40;

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    static Set<String> blockedClasses(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_BLOCKED, new HashSet<>()));
    }

    static void setBlocked(Context c, String cls, boolean blocked) {
        Set<String> set = blockedClasses(c);
        if (blocked) set.add(cls); else set.remove(cls);
        prefs(c).edit().putStringSet(KEY_BLOCKED, set).apply();
    }

    static int skippedCount(Context c) {
        return prefs(c).getInt(KEY_SKIPPED, 0);
    }

    static void incrementSkipped(Context c) {
        prefs(c).edit().putInt(KEY_SKIPPED, skippedCount(c) + 1).apply();
    }

    /** Newest entry first. Each entry: {t: millis, cls: screen class, sum: visible ids/texts, note: what we did}. */
    static JSONArray log(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_LOG, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void addLog(Context c, String cls, String summary, String note) {
        JSONArray old = log(c);
        JSONArray out = new JSONArray();
        try {
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            entry.put("cls", cls == null ? "" : cls);
            entry.put("sum", summary == null ? "" : summary);
            entry.put("note", note == null ? "" : note);
            out.put(entry);
            for (int i = 0; i < old.length() && out.length() < LOG_SIZE; i++) out.put(old.get(i));
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(KEY_LOG, out.toString()).apply();
    }

    static void clearLog(Context c) {
        prefs(c).edit().remove(KEY_LOG).apply();
    }
}
