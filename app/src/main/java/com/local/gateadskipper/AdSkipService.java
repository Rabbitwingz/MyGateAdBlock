package com.local.gateadskipper;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Closes MyGate's full-screen ad. A screen counts as an ad when:
 *  1. it is a known ad-SDK activity (AdMob / Ad Manager, Meta, etc.), or
 *  2. the user marked its class as an ad in the app, or
 *  3. it opens within ARM_WINDOW_MS of tapping Approve/Deny and has ad markers ("Ad", "Sponsored", a close/skip button id).
 * To close it, the service taps a close/skip button or presses Back, and retries while a countdown runs.
 */
public class AdSkipService extends AccessibilityService {
    static final String TARGET_PACKAGE = "com.mygate.user";

    private static final long ARM_WINDOW_MS = 10_000;
    private static final long DISMISS_COOLDOWN_MS = 1_200;
    private static final long CONTENT_CHECK_INTERVAL_MS = 400;
    private static final long[] RETRY_DELAYS_MS = {900, 2_000, 3_500, 5_500};
    private static final int MAX_NODES = 600;

    static final Set<String> KNOWN_AD_ACTIVITIES = new HashSet<>(Arrays.asList(
            "com.google.android.gms.ads.AdActivity",
            "com.google.android.gms.ads.OutOfContextTestingActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "com.facebook.ads.internal.ipc.RemoteANActivity",
            "com.applovin.adview.AppLovinFullscreenActivity",
            "com.applovin.adview.AppLovinInterstitialActivity",
            "com.inmobi.ads.rendering.InMobiAdActivity",
            "com.inmobi.rendering.InMobiAdActivity",
            "com.unity3d.services.ads.adunit.AdUnitActivity",
            "com.ironsource.sdk.controller.ControllerActivity",
            "com.vungle.warren.AdActivity",
            "com.mbridge.msdk.activity.MBCommonActivity"));

    /** Button labels that mean the user just answered a gate request. */
    private static final String[] ANSWER_WORDS = {
            "approve", "approved", "allow", "deny", "denied", "reject", "decline",
            "accept", "let in", "leave at gate", "wait at gate", "collect at gate", "send in"};

    /** Exact (case-insensitive) texts that label an ad. */
    private static final Set<String> AD_LABELS = new HashSet<>(Arrays.asList(
            "ad", "ads", "sponsored", "advertisement", "promoted", "promotion", "ad •", "• ad"));

    private static final Set<String> CLOSE_LABELS = new HashSet<>(Arrays.asList(
            "close", "close ad", "skip", "skip ad", "skip ads", "dismiss", "no thanks", "not now",
            "maybe later", "×", "✕", "✖", "x", "⨯"));

    private static final String[] CLOSE_ID_PARTS = {"close", "skip", "cross", "dismiss", "cancel"};
    private static final String[] AD_ID_PARTS = {"interstitial", "ad_view", "adview", "ad_container",
            "ad_image", "ad_banner", "native_ad", "sponsor", "promo", "ad_close", "adclose"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long armedUntil;
    private long lastDismissAt;
    private long lastContentCheck;
    private String currentClass;
    /** Class of the screen we are currently trying to close, or null. */
    private String dismissTarget;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null || !TARGET_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!Store.isEnabled(this)) return;

        long now = SystemClock.uptimeMillis();
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                String label = eventLabel(event);
                if (containsAny(label, ANSWER_WORDS)) {
                    armedUntil = now + ARM_WINDOW_MS;
                    Store.addLog(this, currentClass, "tapped: " + label, "armed for 10 s");
                }
                break;

            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                String cls = event.getClassName() == null ? "" : event.getClassName().toString();
                currentClass = cls;
                if (!cls.equals(dismissTarget)) dismissTarget = null;
                AccessibilityNodeInfo root = getRootInActiveWindow();
                String reason = adReason(cls, root, now);
                Store.addLog(this, cls, summarize(root), reason == null ? "" : "AD: " + reason);
                if (reason != null) dismiss(cls, root);
                break;

            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                // The ad can finish loading after the screen opens, so recheck while armed.
                if (now > armedUntil || now - lastContentCheck < CONTENT_CHECK_INTERVAL_MS) break;
                lastContentCheck = now;
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (adReason(currentClass, r, now) != null) dismiss(currentClass, r);
                break;
        }
    }

    /** Why this screen is an ad, or null if it isn't. */
    private String adReason(String cls, AccessibilityNodeInfo root, long now) {
        if (cls != null && KNOWN_AD_ACTIVITIES.contains(cls)) return "known ad SDK screen";
        if (cls != null && Store.blockedClasses(this).contains(cls)) return "on your block list";
        if (now > armedUntil || root == null) return null;
        Scan s = scan(root);
        // Don't close the approve/deny screen itself.
        if (s.hasAnswerButton) return null;
        if (s.hasAdLabel) return "labelled as an ad after approve/deny";
        if (s.hasAdId) return "ad view after approve/deny";
        return null;
    }

    private void dismiss(String cls, AccessibilityNodeInfo root) {
        long now = SystemClock.uptimeMillis();
        if (now - lastDismissAt < DISMISS_COOLDOWN_MS) return;
        lastDismissAt = now;
        armedUntil = 0;
        dismissTarget = cls;
        Store.incrementSkipped(this);
        closeOnce(root);
        // Some ads block Back or hide the close button until a countdown ends, so try again until the screen changes.
        for (long delay : RETRY_DELAYS_MS) {
            handler.postDelayed(() -> {
                if (dismissTarget != null && dismissTarget.equals(currentClass)) {
                    closeOnce(getRootInActiveWindow());
                }
            }, delay);
        }
    }

    private void closeOnce(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo close = root == null ? null : scan(root).closeButton;
        if (close != null && clickSelfOrParent(close)) return;
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    // ---- node scanning ----

    private static final class Scan {
        boolean hasAdLabel, hasAdId, hasAnswerButton;
        AccessibilityNodeInfo closeButton;
    }

    private Scan scan(AccessibilityNodeInfo root) {
        Scan s = new Scan();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < MAX_NODES) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            String text = norm(n.getText());
            String desc = norm(n.getContentDescription());
            String id = shortId(n.getViewIdResourceName());

            if (AD_LABELS.contains(text) || AD_LABELS.contains(desc)) s.hasAdLabel = true;
            if (containsAny(id, AD_ID_PARTS)) s.hasAdId = true;
            if (n.isVisibleToUser() && (startsWithAny(text, ANSWER_WORDS) || startsWithAny(desc, ANSWER_WORDS))) {
                s.hasAnswerButton = true;
            }
            if (s.closeButton == null && n.isVisibleToUser() && isCloseNode(text, desc, id)) s.closeButton = n;

            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return s;
    }

    private static boolean isCloseNode(String text, String desc, String id) {
        return CLOSE_LABELS.contains(text) || CLOSE_LABELS.contains(desc)
                || text.startsWith("skip") || desc.startsWith("skip") || desc.startsWith("close")
                || containsAny(id, CLOSE_ID_PARTS);
    }

    private static boolean clickSelfOrParent(AccessibilityNodeInfo n) {
        for (int depth = 0; n != null && depth < 4; depth++, n = n.getParent()) {
            if (n.isClickable() && n.isEnabled()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    /** A short list of view ids and texts on screen, shown in the app so you can recognise the ad. */
    private static String summarize(AccessibilityNodeInfo root) {
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0, items = 0;
        while (!queue.isEmpty() && seen++ < 300 && items < 14) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            String id = shortId(n.getViewIdResourceName());
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            String piece = !id.isEmpty() ? (t != null ? id + "=\"" + clip(t) + "\"" : id)
                    : (t != null ? "\"" + clip(t) + "\"" : null);
            if (piece != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(piece);
                items++;
            }
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return sb.toString();
    }

    // ---- string helpers ----

    private static String eventLabel(AccessibilityEvent e) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence t : e.getText()) sb.append(t).append(' ');
        if (e.getContentDescription() != null) sb.append(e.getContentDescription());
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String norm(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String shortId(String fullId) {
        if (fullId == null) return "";
        int slash = fullId.indexOf('/');
        return (slash >= 0 ? fullId.substring(slash + 1) : fullId).toLowerCase(Locale.ROOT);
    }

    private static String clip(CharSequence cs) {
        String s = cs.toString().replace('\n', ' ').trim();
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    private static boolean containsAny(String s, String[] parts) {
        if (s == null || s.isEmpty()) return false;
        for (String p : parts) if (s.contains(p)) return true;
        return false;
    }

    private static boolean startsWithAny(String s, String[] parts) {
        if (s == null || s.isEmpty()) return false;
        for (String p : parts) if (s.startsWith(p)) return true;
        return false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
