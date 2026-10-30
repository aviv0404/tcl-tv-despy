package dev.frame.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Snapshot of launchable apps plus the user's dock pins. */
final class Apps {
    static final String INPUTS = "special:inputs";
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    // Default dock for a fresh install, in order. Apps that aren't installed are skipped.
    private static final String[] DEFAULT_PINS = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.wbd.stream",
            "com.plexapp.android",
            "com.spotify.tv.android",
            "org.smarttube.stable",
            "com.stremio.one",
            "tv.twitch.android.app",
            "com.tcl.tv",
            INPUTS,
    };

    static final class Entry {
        final String key;
        final String label;
        final ComponentName component;
        final boolean leanback;
        final Drawable banner;
        final Drawable icon;
        final int tone;

        Entry(String key, String label, ComponentName component, boolean leanback,
              Drawable banner, Drawable icon, int tone) {
            this.key = key;
            this.label = label;
            this.component = component;
            this.leanback = leanback;
            this.banner = banner;
            this.icon = icon;
            this.tone = tone;
        }

        boolean isApp() {
            return component != null;
        }

        String packageName() {
            return component != null ? component.getPackageName() : null;
        }
    }

    final List<Entry> all;
    final Map<String, Entry> byKey;

    private Apps(List<Entry> all) {
        this.all = Collections.unmodifiableList(all);
        Map<String, Entry> m = new HashMap<>();
        for (Entry e : all) m.put(e.key, e);
        this.byKey = m;
    }

    static Apps load(Context c) {
        PackageManager pm = c.getPackageManager();
        String self = c.getPackageName();
        Map<String, Entry> found = new LinkedHashMap<>();

        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) {
            add(pm, found, ri, true, self);
        }
        boolean isTv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            // On a TV, system apps without a TV entry point are phone leftovers; skip them.
            if (isTv && (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            add(pm, found, ri, false, self);
        }

        List<Entry> list = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        list.sort((a, b) -> collator.compare(a.label, b.label));

        if (pm.resolveActivity(new Intent(ACTION_VIEW_INPUTS), 0) != null) {
            list.add(0, new Entry(INPUTS, "Inputs", null, false, null, null, 0));
        }
        return new Apps(list);
    }

    private static void add(PackageManager pm, Map<String, Entry> out, ResolveInfo ri, boolean leanback, String self) {
        String pkg = ri.activityInfo.packageName;
        if (out.containsKey(pkg) || pkg.equals(self) || pkg.equals("com.android.tv.settings")) return;
        if (!ri.activityInfo.enabled || !ri.activityInfo.applicationInfo.enabled) return;
        String label = String.valueOf(ri.loadLabel(pm)).trim();
        if (label.isEmpty()) label = pkg;
        Drawable banner = null;
        if (leanback) {
            try {
                banner = ri.activityInfo.loadBanner(pm);
            } catch (Throwable ignored) {
            }
        }
        Drawable icon;
        try {
            icon = ri.loadIcon(pm);
        } catch (Throwable t) {
            icon = pm.getDefaultActivityIcon();
        }
        ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
        out.put(pkg, new Entry(pkg, label, cn, leanback, banner, icon, tone(icon)));
    }

    /** A muted background color derived from the icon, for apps without a TV banner. */
    private static int tone(Drawable d) {
        if (d == null) return 0xFF2A2F36;
        Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Drawable copy = copy(d);
        copy.setBounds(0, 0, 24, 24);
        copy.draw(new Canvas(b));
        int[] px = new int[24 * 24];
        b.getPixels(px, 0, 24, 0, 0, 24, 24);
        b.recycle();
        long r = 0, g = 0, bl = 0, n = 0;
        for (int p : px) {
            if (Color.alpha(p) < 200) continue;
            r += Color.red(p);
            g += Color.green(p);
            bl += Color.blue(p);
            n++;
        }
        if (n == 0) return 0xFF2A2F36;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[1] = Math.min(hsv[1], 0.7f);
        hsv[2] = Math.max(0.18f, Math.min(0.42f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    /** Drawables can only be attached to one view at a time, so hand out copies. */
    static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState s = d.getConstantState();
        return s != null ? s.newDrawable() : d;
    }

    static Intent launchIntent(Entry e) {
        Intent i;
        if (e.component == null) {
            i = new Intent(ACTION_VIEW_INPUTS);
        } else {
            i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(e.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setComponent(e.component);
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    static List<String> pins(SharedPreferences p) {
        String s = p.getString("pins", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_PINS));
        List<String> out = new ArrayList<>();
        for (String k : s.split("\n")) {
            if (!k.isEmpty() && !out.contains(k)) out.add(k);
        }
        return out;
    }

    static void savePins(SharedPreferences p, List<String> pins) {
        p.edit().putString("pins", TextUtils.join("\n", pins)).apply();
    }

    List<Entry> visiblePins(SharedPreferences p) {
        List<Entry> out = new ArrayList<>();
        for (String k : pins(p)) {
            Entry e = byKey.get(k);
            if (e != null) out.add(e);
        }
        return out;
    }
}
