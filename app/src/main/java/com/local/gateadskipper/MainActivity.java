package com.local.gateadskipper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/** Setup and "learn mode": shows recent MyGate screens; tap one to mark it as an ad. */
public class MainActivity extends Activity {
    private LinearLayout root;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();

        TextView title = text("Gate Ad Skipper", 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        boolean serviceOn = isServiceEnabled();
        TextView status = text(serviceOn
                ? "✅ Accessibility service is ON. Ads skipped so far: " + Store.skippedCount(this)
                : "⚠️ Accessibility service is OFF. Turn it on so ads can be closed.", 15);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        if (!serviceOn) {
            root.addView(text("Settings → Accessibility → Installed apps (or Downloaded apps) → Gate Ad Skipper → On.\n\n"
                    + "Android 13+: if the switch is greyed out ('Restricted setting'), go to Settings → Apps → "
                    + "Gate Ad Skipper → ⋮ (top right) → 'Allow restricted settings', then try again.", 13));
            Button open = new Button(this);
            open.setText("Open Accessibility settings");
            open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            root.addView(open);
        }

        Switch enabled = new Switch(this);
        enabled.setText("Skip MyGate ads");
        enabled.setChecked(Store.isEnabled(this));
        enabled.setOnCheckedChangeListener((b, on) -> Store.setEnabled(this, on));
        enabled.setPadding(0, dp(8), 0, dp(8));
        root.addView(enabled);

        TextView learnHeader = text("Recent MyGate screens", 18);
        learnHeader.setTypeface(Typeface.DEFAULT_BOLD);
        learnHeader.setPadding(0, dp(16), 0, dp(4));
        root.addView(learnHeader);
        root.addView(text("If an ad still gets through, open this app and find it below (it opens just after your "
                + "'tapped: approve/deny' entry). Tap it to block that screen from now on. Tap again to unblock. "
                + "Don't block MyGate's main or approval screen.", 13));

        Set<String> blocked = Store.blockedClasses(this);
        JSONArray log = Store.log(this);
        if (log.length() == 0) {
            root.addView(text("\nNothing yet. Open MyGate (or wait for the next visitor request) and come back.", 13));
        }
        for (int i = 0; i < log.length(); i++) {
            JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            root.addView(logRow(e, blocked));
        }

        Button clear = new Button(this);
        clear.setText("Clear log");
        clear.setOnClickListener(v -> { Store.clearLog(this); render(); });
        root.addView(clear);
    }

    private View logRow(JSONObject e, Set<String> blocked) {
        String cls = e.optString("cls");
        String sum = e.optString("sum");
        String note = e.optString("note");
        boolean isTapEntry = sum.startsWith("tapped: ");
        boolean isBlocked = blocked.contains(cls);
        boolean isKnown = AdSkipService.KNOWN_AD_ACTIVITIES.contains(cls);

        StringBuilder sb = new StringBuilder();
        sb.append(timeFmt.format(new Date(e.optLong("t")))).append('\n');
        if (isTapEntry) {
            sb.append("👆 ").append(sum);
        } else {
            sb.append(simpleName(cls));
            if (isBlocked) sb.append("   [BLOCKED]");
            else if (isKnown) sb.append("   [ad SDK – always blocked]");
            if (!sum.isEmpty()) sb.append('\n').append(sum);
        }
        if (!note.isEmpty()) sb.append("\n→ ").append(note);

        TextView row = text(sb.toString(), 12);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackgroundColor(isBlocked ? 0x33E53935 : (note.startsWith("AD:") ? 0x3343A047 : 0x14888888));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        if (!isTapEntry && !isKnown && !TextUtils.isEmpty(cls)) {
            row.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(isBlocked ? "Unblock this screen?" : "Block this screen as an ad?")
                    .setMessage(cls + (isBlocked ? "" : "\n\nWhenever MyGate shows this screen it will be closed right away."))
                    .setPositiveButton(isBlocked ? "Unblock" : "Block", (d, w) -> {
                        Store.setBlocked(this, cls, !isBlocked);
                        render();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        return row;
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, AdSkipService.class);
        for (String s : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    private static String simpleName(String cls) {
        if (TextUtils.isEmpty(cls)) return "(unknown screen)";
        return cls.startsWith(AdSkipService.TARGET_PACKAGE) ? cls.substring(AdSkipService.TARGET_PACKAGE.length()) : cls;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
