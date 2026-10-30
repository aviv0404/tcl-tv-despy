package dev.frame.launcher;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

final class Ui {
    private static final Typeface[] FONTS = new Typeface[10];
    private static Context app;
    static float density = 2f;

    private Ui() {}

    static void init(Context c) {
        app = c.getApplicationContext();
        density = c.getResources().getDisplayMetrics().density;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    /** Manrope (variable, OFL) at the given weight, falling back to the system sans-serif. */
    static Typeface font(int weight) {
        int i = Math.max(2, Math.min(8, Math.round(weight / 100f)));
        Typeface t = FONTS[i];
        if (t == null) {
            try {
                t = new Typeface.Builder(app.getAssets(), "fonts/Manrope.ttf")
                        .setFontVariationSettings("'wght' " + (i * 100))
                        .setWeight(i * 100)
                        .build();
            } catch (Throwable ignored) {
                t = null;
            }
            if (t == null) t = Typeface.create(Typeface.SANS_SERIF, i * 100, false);
            FONTS[i] = t;
        }
        return t;
    }

    static TextView text(Context c, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static void round(View v, float radiusDp) {
        final float r = dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    static GradientDrawable rect(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static GradientDrawable ring(int color, float widthDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0);
        g.setStroke(dp(widthDp), color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static GradientDrawable oval(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    static StateListDrawable focusable(Drawable focused, Drawable normal) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[] {}, normal != null ? normal : new ColorDrawable(0));
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        return s;
    }
}
