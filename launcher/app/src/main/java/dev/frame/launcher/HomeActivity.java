package dev.frame.launcher;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeActivity extends Activity {
    private static final String TAG = "Frame";
    private static final String ALL_APPS = "special:all";
    private static final int DOCK_W = 88, DOCK_H = 50;
    private static final int GRID_W = 124, GRID_H = 70, GRID_COLS = 6;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final TimeInterpolator EASE = new DecelerateInterpolator(1.6f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Backdrop backdrop;
    private Apps apps;
    private boolean firstRender = true;

    private FrameLayout root, homeLayer, sheetLayer;
    private GlassView dock, appsLayer;
    private LinearLayout topButtons, dockRow, grid, sheetBox;
    private HScroll dockScroll;
    private Scroll gridScroll;
    private TextView clock, date, credit, toast, buttonHint, caption;
    private View sheetReturnFocus, captionFor, okDownView;
    private boolean okLongDone;
    private String movingKey;
    private boolean timeReceiverOn;

    private final Runnable reload = this::loadApps;
    private final Runnable hideToast = () -> toast.animate().alpha(0f).setDuration(220);

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 400);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        prefs = getSharedPreferences("frame", MODE_PRIVATE);
        backdrop = new Backdrop(this);
        buildUi();
        setContentView(root);
        backdrop.setListener(this::showCredit);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        register(packageReceiver, f);
        loadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        t.addAction(Intent.ACTION_LOCALE_CHANGED);
        register(timeReceiver, t);
        timeReceiverOn = true;
        updateClock();
        backdrop.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (timeReceiverOn) {
            unregisterReceiver(timeReceiver);
            timeReceiverOn = false;
        }
        backdrop.stop();
        if (movingKey != null) finishMove();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already on the home screen: reset to the start of the dock.
        boolean alreadyHome = hasWindowFocus()
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0;
        if (movingKey != null) finishMove();
        closeSheet(false);
        closeApps(false);
        if (alreadyHome && dockRow.getChildCount() > 0) {
            dockScroll.smoothScrollTo(0, 0);
            dockRow.getChildAt(0).requestFocus();
        }
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }

    // ---------------------------------------------------------------- layout

    private void buildUi() {
        root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(new BackdropView(this, backdrop), new FrameLayout.LayoutParams(MATCH, MATCH));

        homeLayer = new FrameLayout(this);
        root.addView(homeLayer, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Clock + date, top-left.
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 88, 300, Color.WHITE);
        clock.setLetterSpacing(-0.02f);
        clock.setShadowLayer(Ui.dp(16), 0, Ui.dp(2), 0x47000000);
        date = Ui.text(this, 18, 500, 0xEBFFFFFF);
        date.setShadowLayer(Ui.dp(10), 0, Ui.dp(1), 0x59000000);
        // Explicit WRAP: a vertical LinearLayout defaults children to MATCH_PARENT width.
        clockBox.addView(clock, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(WRAP, WRAP);
        dl.topMargin = Ui.dp(6);
        dl.leftMargin = Ui.dp(4);
        clockBox.addView(date, dl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.LEFT);
        cl.leftMargin = Ui.dp(52);
        cl.topMargin = Ui.dp(34);
        homeLayer.addView(clockBox, cl);

        // Utility buttons, top-right.
        topButtons = new LinearLayout(this);
        topButtons.setOrientation(LinearLayout.VERTICAL);
        topButtons.setGravity(Gravity.RIGHT);
        topButtons.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(roundButton(Glyph.IMAGE, "Next photo", v -> nextPhoto()));
        View gear = roundButton(Glyph.GEAR, "Settings", this::openSettings);
        ((LinearLayout.LayoutParams) gear.getLayoutParams()).leftMargin = Ui.dp(12);
        buttons.addView(gear);
        topButtons.addView(buttons, new LinearLayout.LayoutParams(WRAP, WRAP));
        buttonHint = Ui.text(this, 12, 600, Color.WHITE);
        buttonHint.setAlpha(0f);
        buttonHint.setShadowLayer(Ui.dp(6), 0, Ui.dp(1), 0x66000000);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(WRAP, WRAP);
        hl.topMargin = Ui.dp(10);
        hl.rightMargin = Ui.dp(2);
        topButtons.addView(buttonHint, hl);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.RIGHT);
        tl.rightMargin = Ui.dp(52);
        tl.topMargin = Ui.dp(42);
        homeLayer.addView(topButtons, tl);

        // Frosted dock, bottom-center.
        dock = new GlassView(this, backdrop, 24, 0x47101418, 0x24FFFFFF);
        dockScroll = new HScroll(this);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        dockRow.setClipChildren(false);
        // The 1.1x focus lift overflows the 50dp row height into the padding.
        dockRow.setClipToPadding(false);
        dockScroll.addView(dockRow, new FrameLayout.LayoutParams(WRAP, WRAP));
        dockScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> placeCaption(false));
        dock.addView(dockScroll, new FrameLayout.LayoutParams(WRAP, WRAP));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.leftMargin = dlp.rightMargin = Ui.dp(24);
        dlp.bottomMargin = Ui.dp(30);
        dock.setAlpha(0f);
        homeLayer.addView(dock, dlp);

        // Name of the focused dock app, floating above its tile.
        caption = Ui.text(this, 13, 600, Color.WHITE);
        caption.setSingleLine(true);
        caption.setAlpha(0f);
        caption.setShadowLayer(Ui.dp(6), 0, Ui.dp(1), 0x99000000);
        FrameLayout.LayoutParams cpl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cpl.bottomMargin = Ui.dp(30 + DOCK_H + 24 + 12);
        homeLayer.addView(caption, cpl);

        credit = Ui.text(this, 10, 500, 0x8CFFFFFF);
        credit.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), 0x80000000);
        FrameLayout.LayoutParams crl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.RIGHT);
        crl.rightMargin = Ui.dp(20);
        crl.bottomMargin = Ui.dp(9);
        homeLayer.addView(credit, crl);

        // All apps overlay.
        appsLayer = new GlassView(this, backdrop, 0, 0xC20B0D11, 0);
        appsLayer.setVisibility(View.GONE);
        appsLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(48), Ui.dp(38), Ui.dp(48), 0);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        head.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        TextView title = Ui.text(this, 26, 600, Color.WHITE);
        title.setText("All apps");
        head.addView(title);
        TextView hint = Ui.text(this, 12, 500, 0x99FFFFFF);
        hint.setText("Hold OK for options  \u00B7  Back to close");
        LinearLayout.LayoutParams hil = new LinearLayout.LayoutParams(WRAP, WRAP);
        hil.leftMargin = Ui.dp(16);
        hil.bottomMargin = Ui.dp(3);
        head.addView(hint, hil);
        col.addView(head);
        gridScroll = new Scroll(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, Ui.dp(14), 0, Ui.dp(40));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        gridScroll.addView(grid, new FrameLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        gl.topMargin = Ui.dp(6);
        col.addView(gridScroll, gl);
        appsLayer.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(appsLayer, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Options sheet.
        sheetLayer = new FrameLayout(this);
        sheetLayer.setBackgroundColor(0x8C000000);
        sheetLayer.setVisibility(View.GONE);
        sheetLayer.setClickable(true);
        GlassView sheet = new GlassView(this, backdrop, 22, 0xE0121519, 0x29FFFFFF);
        sheetBox = new LinearLayout(this);
        sheetBox.setOrientation(LinearLayout.VERTICAL);
        sheetBox.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(10));
        sheet.addView(sheetBox, new FrameLayout.LayoutParams(MATCH, WRAP));
        sheetLayer.addView(sheet, new FrameLayout.LayoutParams(Ui.dp(340), WRAP, Gravity.CENTER));
        root.addView(sheetLayer, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Toast pill.
        toast = Ui.text(this, 14, 600, Color.WHITE);
        toast.setBackground(Ui.rect(0xE6121519, 20));
        toast.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10));
        toast.setAlpha(0f);
        FrameLayout.LayoutParams ttl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ttl.bottomMargin = Ui.dp(150);
        root.addView(toast, ttl);
    }

    private View roundButton(int glyph, String hint, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(hint);
        b.setBackground(Ui.focusable(Ui.oval(0x4DFFFFFF), Ui.oval(0x1FFFFFFF)));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Glyph(glyph, Color.WHITE));
        b.addView(iv, new FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(150).setInterpolator(EASE);
            if (has) buttonHint.setText(hint);
            buttonHint.animate().alpha(has ? 1f : 0f).setDuration(150);
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        return b;
    }

    // ---------------------------------------------------------------- tiles

    private FrameLayout card(Apps.Entry e, int wDp, int hDp, float radius, boolean large) {
        FrameLayout c = new FrameLayout(this);
        c.setDuplicateParentStateEnabled(true);
        Ui.round(c, radius);
        c.setForeground(Ui.focusable(Ui.ring(Color.WHITE, 2.5f, radius), null));
        // Grid tiles carry their name underneath, so their cards show the icon alone.
        String inner = large ? null : (e == null ? "All apps" : e.label);
        if (e == null || !e.isApp()) {
            c.setBackground(Ui.rect(0x2EFFFFFF, radius));
            Drawable g = new Glyph(e == null ? Glyph.GRID : Glyph.INPUT, Color.WHITE);
            c.addView(iconLabel(g, inner, large ? 32 : 20, 10),
                    new FrameLayout.LayoutParams(MATCH, MATCH));
        } else if (e.banner != null) {
            c.setBackground(Ui.rect(e.tone, radius));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(Apps.copy(e.banner));
            c.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            c.setBackground(Ui.rect(e.tone, radius));
            c.addView(iconLabel(Apps.copy(e.icon), inner, large ? 40 : 24, 10),
                    new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        return c;
    }

    private View iconLabel(Drawable icon, String text, int iconDp, float sp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(icon);
        l.addView(iv, new LinearLayout.LayoutParams(Ui.dp(iconDp), Ui.dp(iconDp)));
        if (text == null) return l;
        TextView t = Ui.text(this, sp, 600, 0xF2FFFFFF);
        t.setText(text);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.topMargin = Ui.dp(4);
        l.addView(t, tl);
        return l;
    }

    private View dockTile(Apps.Entry e) {
        String key = e == null ? ALL_APPS : e.key;
        String name = e == null ? "All apps" : e.label;
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setClipChildren(false);
        t.setTag(key);
        t.setContentDescription(name);
        FrameLayout card = card(e, DOCK_W, DOCK_H, 10, false);
        t.addView(card, new LinearLayout.LayoutParams(Ui.dp(DOCK_W), Ui.dp(DOCK_H)));
        // Icon cards already print their name; only banner cards need the floating caption.
        final boolean captioned = e != null && e.isApp() && e.banner != null;
        // A shadow would show through the translucent glass of the special cards.
        final boolean solid = e != null && e.isApp();
        t.setOnFocusChangeListener((v, has) -> {
            lift(v, has, 1.1f);
            if (solid) card.animate().translationZ(has ? Ui.dp(8) : 0f).setDuration(170).setInterpolator(EASE);
            if (has) {
                if (captioned) showCaption(v, name);
                else hideCaption();
            } else if (captionFor == v) {
                // Deferred so a move to another captioned tile slides instead of blinking.
                caption.post(() -> {
                    if (captionFor == v) hideCaption();
                });
            }
        });
        if (e == null) {
            t.setOnClickListener(v -> openApps());
        } else {
            t.setOnClickListener(v -> launch(e, card));
            t.setOnLongClickListener(v -> {
                dockSheet(e);
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(DOCK_W), Ui.dp(DOCK_H));
        lp.leftMargin = lp.rightMargin = Ui.dp(5);
        t.setLayoutParams(lp);
        return t;
    }

    private void showCaption(View tile, String name) {
        boolean visible = captionFor != null && caption.getAlpha() > 0f;
        captionFor = tile;
        caption.setText(name);
        caption.animate().cancel();
        caption.animate().alpha(1f).setDuration(visible ? 120 : 170);
        // Position after layout so new tiles and in-flight scrolls are accounted for.
        caption.post(() -> placeCaption(visible));
    }

    private void hideCaption() {
        captionFor = null;
        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(120);
    }

    private void placeCaption(boolean animate) {
        View t = captionFor;
        if (t == null || !t.isAttachedToWindow()) return;
        float center = dock.getLeft() + dockScroll.getLeft() + dockRow.getLeft()
                - dockScroll.getScrollX() + t.getLeft() + t.getWidth() / 2f;
        float x = center - homeLayer.getWidth() / 2f;
        if (animate) caption.animate().translationX(x).setDuration(140).setInterpolator(EASE);
        else caption.setTranslationX(x);
    }

    private View gridTile(Apps.Entry e, boolean pinned) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setClipChildren(false);
        t.setTag(e.key);
        t.setContentDescription(e.label);
        FrameLayout card = card(e, GRID_W, GRID_H, 12, true);
        if (pinned) {
            View dot = new View(this);
            // Outlined so it stays visible on white banners (Netflix, YouTube).
            GradientDrawable dg = Ui.oval(Color.WHITE);
            dg.setStroke(Ui.dp(1.5f), 0x8C000000);
            dot.setBackground(dg);
            dot.setElevation(Ui.dp(2));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Ui.dp(9), Ui.dp(9), Gravity.TOP | Gravity.RIGHT);
            dl.topMargin = dl.rightMargin = Ui.dp(7);
            card.addView(dot, dl);
        }
        t.addView(card, new LinearLayout.LayoutParams(Ui.dp(GRID_W), Ui.dp(GRID_H)));
        TextView label = Ui.text(this, 13, 600, Color.WHITE);
        label.setText(e.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0.72f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(MATCH, WRAP);
        ll.topMargin = Ui.dp(8);
        t.addView(label, ll);
        final boolean solid = e.isApp();
        t.setOnFocusChangeListener((v, has) -> {
            lift(v, has, 1.08f);
            if (solid) card.animate().translationZ(has ? Ui.dp(8) : 0f).setDuration(170).setInterpolator(EASE);
            label.animate().alpha(has ? 1f : 0.72f).setDuration(170);
        });
        t.setOnClickListener(v -> launch(e, card));
        t.setOnLongClickListener(v -> {
            gridSheet(e);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(GRID_W), WRAP);
        lp.leftMargin = lp.rightMargin = Ui.dp(10);
        lp.topMargin = lp.bottomMargin = Ui.dp(9);
        t.setLayoutParams(lp);
        return t;
    }

    private static void lift(View v, boolean on, float scale) {
        v.animate()
                .scaleX(on ? scale : 1f)
                .scaleY(on ? scale : 1f)
                .translationZ(on ? Ui.dp(12) : 0f)
                .setDuration(170)
                .setInterpolator(EASE);
    }

    // ---------------------------------------------------------------- rendering

    private void loadApps() {
        final Context app = getApplicationContext();
        io.execute(() -> {
            final Apps loaded;
            try {
                loaded = Apps.load(app);
            } catch (Throwable t) {
                Log.e(TAG, "App query failed", t);
                return;
            }
            handler.post(() -> {
                if (isDestroyed()) return;
                apps = loaded;
                if (movingKey != null && !loaded.byKey.containsKey(movingKey)) finishMove();
                renderDock(null);
                if (appsLayer.getVisibility() == View.VISIBLE) renderGrid(null);
                if (firstRender) {
                    firstRender = false;
                    topButtons.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                    if (dockRow.getChildCount() > 0 && sheetLayer.getVisibility() != View.VISIBLE
                            && appsLayer.getVisibility() != View.VISIBLE) {
                        dockRow.getChildAt(0).requestFocus();
                    }
                    dock.animate().alpha(1f).setDuration(280);
                }
                if (movingKey != null) markMoving();
            });
        });
    }

    private void renderDock(String focusKey) {
        if (apps == null) return;
        View f = getCurrentFocus();
        int focusIndex = -1;
        if (f != null && f.getParent() == dockRow) {
            focusIndex = dockRow.indexOfChild(f);
            if (focusKey == null) focusKey = (String) f.getTag();
        }
        boolean hold = focusIndex >= 0 || focusKey != null;
        if (hold) holdFocus(dockScroll);
        dockRow.removeAllViews();
        for (Apps.Entry e : apps.visiblePins(prefs)) dockRow.addView(dockTile(e));
        dockRow.addView(dockTile(null));
        // Where focus lands when the remote is first used (e.g. leaving touch mode).
        dockRow.getChildAt(0).setFocusedByDefault(true);
        if (hold) {
            View t = focusKey != null ? dockRow.findViewWithTag(focusKey) : null;
            if (t == null) t = dockRow.getChildAt(Math.max(0, Math.min(focusIndex, dockRow.getChildCount() - 1)));
            releaseFocus(dockScroll, t);
        }
    }

    private void renderGrid(String focusKey) {
        if (apps == null) return;
        View f = getCurrentFocus();
        boolean hadFocus = f != null && isInside(f, grid);
        if (focusKey == null && hadFocus) focusKey = (String) f.getTag();
        if (hadFocus) holdFocus(gridScroll);
        grid.removeAllViews();
        List<String> pins = Apps.pins(prefs);
        LinearLayout row = null;
        for (int i = 0; i < apps.all.size(); i++) {
            if (i % GRID_COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                grid.addView(row);
            }
            Apps.Entry e = apps.all.get(i);
            row.addView(gridTile(e, pins.contains(e.key)));
        }
        if (hadFocus || focusKey != null) {
            View t = focusKey != null ? grid.findViewWithTag(focusKey) : null;
            if (t == null) t = firstTile(grid);
            if (hadFocus) releaseFocus(gridScroll, t);
            else if (t != null) t.requestFocus();
        }
    }

    /** Park focus on the scroll container so rebuilding its children doesn't bounce focus elsewhere. */
    private static void holdFocus(ViewGroup scroll) {
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.setFocusable(true);
        scroll.requestFocus();
    }

    private static void releaseFocus(ViewGroup scroll, View target) {
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (target != null) target.requestFocus();
        scroll.setFocusable(false);
    }

    private static boolean isInside(View v, ViewGroup g) {
        ViewParent p = v.getParent();
        while (p != null) {
            if (p == g) return true;
            p = p.getParent();
        }
        return false;
    }

    private static View firstTile(LinearLayout grid) {
        if (grid.getChildCount() == 0) return null;
        ViewGroup row = (ViewGroup) grid.getChildAt(0);
        return row.getChildCount() > 0 ? row.getChildAt(0) : null;
    }

    // ---------------------------------------------------------------- overlays

    private void openApps() {
        if (apps == null) return;
        renderGrid(null);
        gridScroll.scrollTo(0, 0);
        appsLayer.animate().cancel();
        appsLayer.setAlpha(0f);
        appsLayer.setVisibility(View.VISIBLE);
        appsLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View first = firstTile(grid);
        if (first != null) first.requestFocus();
        homeLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        appsLayer.animate().alpha(1f).setDuration(180);
    }

    private void closeApps(boolean animate) {
        if (appsLayer.getVisibility() != View.VISIBLE) return;
        homeLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View all = dockRow.findViewWithTag(ALL_APPS);
        if (all != null) all.requestFocus();
        appsLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        appsLayer.animate().cancel();
        if (animate) {
            appsLayer.animate().alpha(0f).setDuration(150).withEndAction(() -> appsLayer.setVisibility(View.GONE));
        } else {
            appsLayer.setVisibility(View.GONE);
        }
    }

    private void openSheet(String title, List<String> labels, List<Runnable> actions) {
        sheetReturnFocus = getCurrentFocus();
        sheetBox.removeAllViews();
        TextView t = Ui.text(this, 13, 700, 0x99FFFFFF);
        t.setText(title);
        t.setLetterSpacing(0.03f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(10));
        sheetBox.addView(t);
        View first = null;
        for (int i = 0; i < labels.size(); i++) {
            TextView b = Ui.text(this, 16, 600, Color.WHITE);
            b.setText(labels.get(i));
            b.setPadding(Ui.dp(16), Ui.dp(13), Ui.dp(16), Ui.dp(13));
            b.setFocusable(true);
            b.setClickable(true);
            b.setBackground(Ui.focusable(Ui.rect(0x33FFFFFF, 12), null));
            final Runnable action = actions.get(i);
            b.setOnClickListener(v -> {
                closeSheet(true);
                action.run();
            });
            sheetBox.addView(b, new LinearLayout.LayoutParams(MATCH, WRAP));
            if (first == null) first = b;
        }
        sheetLayer.animate().cancel();
        sheetLayer.setAlpha(0f);
        sheetLayer.setVisibility(View.VISIBLE);
        if (first != null) first.requestFocus();
        homeLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        appsLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sheetLayer.animate().alpha(1f).setDuration(140);
    }

    private void closeSheet(boolean restoreFocus) {
        if (sheetLayer.getVisibility() != View.VISIBLE) return;
        if (appsLayer.getVisibility() == View.VISIBLE) {
            appsLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        } else {
            homeLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        View f = sheetReturnFocus;
        sheetReturnFocus = null;
        if (restoreFocus && f != null && f.isAttachedToWindow()) f.requestFocus();
        sheetLayer.animate().cancel();
        sheetLayer.setVisibility(View.GONE);
    }

    private void dockSheet(Apps.Entry e) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Move");
        actions.add(() -> startMove(e.key));
        labels.add("Remove from dock");
        actions.add(() -> unpin(e.key));
        if (e.isApp()) {
            labels.add("App info");
            actions.add(() -> appInfo(e));
        }
        labels.add("Cancel");
        actions.add(() -> { });
        openSheet(e.label, labels, actions);
    }

    private void gridSheet(Apps.Entry e) {
        boolean pinned = Apps.pins(prefs).contains(e.key);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(pinned ? "Remove from dock" : "Pin to dock");
        actions.add(() -> {
            if (pinned) unpin(e.key);
            else pin(e.key);
        });
        if (e.isApp()) {
            labels.add("App info");
            actions.add(() -> appInfo(e));
        }
        labels.add("Cancel");
        actions.add(() -> { });
        openSheet(e.label, labels, actions);
    }

    private void showToast(String text, long ms) {
        handler.removeCallbacks(hideToast);
        toast.setText(text);
        toast.animate().alpha(1f).setDuration(160);
        if (ms > 0) handler.postDelayed(hideToast, ms);
    }

    private void showCredit(String text) {
        credit.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            credit.setText(text);
            credit.animate().alpha(1f).setDuration(600);
        });
    }

    // ---------------------------------------------------------------- pins

    private void pin(String key) {
        List<String> pins = Apps.pins(prefs);
        if (!pins.contains(key)) pins.add(key);
        Apps.savePins(prefs, pins);
        renderDock(null);
        if (appsLayer.getVisibility() == View.VISIBLE) renderGrid(null);
        showToast("Pinned to dock", 1800);
    }

    private void unpin(String key) {
        List<String> pins = Apps.pins(prefs);
        pins.remove(key);
        Apps.savePins(prefs, pins);
        renderDock(null);
        if (appsLayer.getVisibility() == View.VISIBLE) renderGrid(null);
        showToast("Removed from dock", 1800);
    }

    private void startMove(String key) {
        View t = dockRow.findViewWithTag(key);
        if (t == null) return;
        movingKey = key;
        t.requestFocus();
        markMoving();
        showToast("\u25C0 \u25B6 to move  \u00B7  OK when done", 0);
    }

    private void markMoving() {
        View t = dockRow.findViewWithTag(movingKey);
        if (t != null) t.animate().translationY(-Ui.dp(8)).setDuration(150).setInterpolator(EASE);
    }

    private void moveBy(int delta) {
        if (apps == null) return;
        List<String> keys = new ArrayList<>();
        for (Apps.Entry e : apps.visiblePins(prefs)) keys.add(e.key);
        int i = keys.indexOf(movingKey), j = i + delta;
        if (i < 0 || j < 0 || j >= keys.size()) return;
        Collections.swap(keys, i, j);
        // Keep pins of apps that are temporarily missing (e.g. disabled) at the end.
        for (String k : Apps.pins(prefs)) if (!keys.contains(k)) keys.add(k);
        Apps.savePins(prefs, keys);
        renderDock(movingKey);
        View t = dockRow.findViewWithTag(movingKey);
        if (t != null) t.setTranslationY(-Ui.dp(8));
    }

    private void finishMove() {
        View t = dockRow.findViewWithTag(movingKey);
        movingKey = null;
        if (t != null) t.animate().translationY(0f).setDuration(150).setInterpolator(EASE);
        handler.removeCallbacks(hideToast);
        hideToast.run();
    }

    // ---------------------------------------------------------------- actions

    private void launch(Apps.Entry e, View from) {
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(Apps.launchIntent(e), opts);
        } catch (Exception ex) {
            Log.w(TAG, "Launch failed: " + e.key, ex);
            showToast("Couldn't open " + e.label, 2500);
        }
    }

    private void openSettings(View from) {
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts);
        } catch (Exception ex) {
            showToast("Couldn't open Settings", 2500);
        }
    }

    private void appInfo(Apps.Entry e) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", e.packageName(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            showToast("App info isn't available", 2500);
        }
    }

    private void nextPhoto() {
        backdrop.advance();
    }

    private void updateClock() {
        Date now = new Date();
        Locale l = Locale.getDefault();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", l).format(now));
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(l, "EEEEdMMMM");
        date.setText(new SimpleDateFormat(pattern, l).format(now));
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;

        if (isAppKey(k)) {
            // Remote app buttons arrive as BUTTON_3/4/6/7. Left unhandled, Android falls back to
            // DPAD_CENTER, which would open whichever tile has focus, so they are always consumed.
            if (up && !e.isCanceled() && movingKey == null) openAppKey(k);
            return true;
        }

        if (movingKey != null) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) moveBy(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (up && !e.isCanceled()) finishMove();
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }

        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            // A launcher never finishes on BACK; it only closes its own overlays.
            if (up && !e.isCanceled()) {
                if (sheetLayer.getVisibility() == View.VISIBLE) closeSheet(true);
                else if (appsLayer.getVisibility() == View.VISIBLE) closeApps(true);
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (up && sheetLayer.getVisibility() != View.VISIBLE) {
                View f = getCurrentFocus();
                if (f != null && f.isLongClickable()) f.performLongClick();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // Some remotes (and `input keyevent --longpress`) deliver a held OK as a
            // FLAG_LONG_PRESS repeat before the view's own long-press timer fires.
            View f = getCurrentFocus();
            if (down && e.getRepeatCount() == 0) {
                okDownView = f;
                okLongDone = false;
            } else if (down && e.isLongPress() && !okLongDone && f != null && f == okDownView
                    && f.isLongClickable()) {
                okLongDone = true;
                f.cancelLongPress();
                f.setPressed(false);
                f.performLongClick();
                return true;
            } else if (up && okLongDone) {
                okLongDone = false;
                okDownView = null;
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isAppKey(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_3 || k == KeyEvent.KEYCODE_BUTTON_4
                || k == KeyEvent.KEYCODE_BUTTON_6 || k == KeyEvent.KEYCODE_BUTTON_7;
    }

    /** YouTube (BUTTON_3) and Netflix (BUTTON_4) open their app if installed; otherwise nothing happens. */
    private void openAppKey(int k) {
        String pkg = k == KeyEvent.KEYCODE_BUTTON_3 ? "com.google.android.youtube.tv"
                : k == KeyEvent.KEYCODE_BUTTON_4 ? "com.netflix.ninja" : null;
        Apps.Entry target = pkg != null && apps != null ? apps.byKey.get(pkg) : null;
        if (target == null) return;
        View from = getCurrentFocus();
        launch(target, from != null ? from : getWindow().getDecorView());
    }

    // ---------------------------------------------------------------- scroll containers

    /** Horizontal scroller that keeps a margin around the focused tile without drawing a fade. */
    static final class HScroll extends HorizontalScrollView {
        HScroll(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setHorizontalFadingEdgeEnabled(true);
            setFadingEdgeLength(Ui.dp(48));
            setFocusable(false);
        }

        @Override
        protected float getLeftFadingEdgeStrength() {
            return 0f;
        }

        @Override
        protected float getRightFadingEdgeStrength() {
            return 0f;
        }
    }

    static final class Scroll extends ScrollView {
        Scroll(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setVerticalFadingEdgeEnabled(true);
            setFadingEdgeLength(Ui.dp(40));
            setFocusable(false);
        }
    }
}
