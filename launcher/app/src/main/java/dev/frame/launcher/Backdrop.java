package dev.frame.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background photos. Photos the user pushed to the app's external files dir ("backgrounds")
 * take precedence; otherwise the bundled CC0 set is used. Decoding happens off the UI thread,
 * and each photo also gets a small blurred copy used by the frosted-glass panels.
 */
final class Backdrop {
    interface Listener {
        void onCreditChanged(String credit);
    }

    private static final String TAG = "FrameBackdrop";
    private static final long ROTATE_MS = 10 * 60 * 1000L;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> views = new ArrayList<>();
    private final int width, height;

    Bitmap cur, curBlur, next, nextBlur;
    float fade;
    String credit = "";

    private Listener listener;
    private ValueAnimator anim;
    private boolean busy, started;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (!busy) load(prefs.getInt("bg_index", 0) + 1);
            main.postDelayed(this, ROTATE_MS);
        }
    };

    Backdrop(Activity a) {
        ctx = a.getApplicationContext();
        prefs = ctx.getSharedPreferences("frame", Context.MODE_PRIVATE);
        Point p = new Point();
        a.getWindowManager().getDefaultDisplay().getRealSize(p);
        width = Math.max(1, Math.max(p.x, p.y));
        height = Math.max(1, Math.min(p.x, p.y));
    }

    void attach(View v) {
        views.add(v);
    }

    void setListener(Listener l) {
        listener = l;
        if (l != null && !credit.isEmpty()) l.onCreditChanged(credit);
    }

    void start() {
        started = true;
        if (cur == null && next == null && !busy) load(prefs.getInt("bg_index", 0));
        main.removeCallbacks(rotate);
        main.postDelayed(rotate, ROTATE_MS);
    }

    void stop() {
        started = false;
        main.removeCallbacks(rotate);
    }

    /** Returns false if a photo is still being prepared. */
    boolean advance() {
        if (busy) return false;
        load(prefs.getInt("bg_index", 0) + 1);
        main.removeCallbacks(rotate);
        if (started) main.postDelayed(rotate, ROTATE_MS);
        return true;
    }

    private void invalidate() {
        for (View v : views) v.invalidate();
    }

    private void load(final int index) {
        busy = true;
        exec.execute(() -> {
            List<Photo> list = photos();
            Bitmap[] out = null;
            String text = "";
            int used = 0;
            for (int attempt = 0; attempt < list.size() && out == null; attempt++) {
                int i = Math.floorMod(index + attempt, list.size());
                try {
                    out = decode(list.get(i));
                    text = list.get(i).credit;
                    used = i;
                } catch (Throwable t) {
                    Log.w(TAG, "Skipping photo " + list.get(i), t);
                }
            }
            final Bitmap[] result = out;
            final String c = text;
            final int idx = used;
            main.post(() -> {
                busy = false;
                if (result == null) return;
                prefs.edit().putInt("bg_index", idx).apply();
                show(result[0], result[1], c);
            });
        });
    }

    private void show(Bitmap photo, Bitmap blur, String text) {
        if (anim != null) anim.end();
        boolean first = cur == null;
        next = photo;
        nextBlur = blur;
        fade = 0f;
        credit = text;
        if (listener != null) listener.onCreditChanged(text);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(first ? 450 : 1600);
        anim.setInterpolator(new DecelerateInterpolator(first ? 1f : 1.5f));
        anim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                cur = next;
                curBlur = nextBlur;
                next = null;
                nextBlur = null;
                fade = 0f;
                anim = null;
                invalidate();
            }
        });
        anim.start();
    }

    private List<Photo> photos() {
        List<Photo> out = new ArrayList<>();
        File dir = ctx.getExternalFilesDir("backgrounds");
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    out.add(new Photo(null, f, ""));
                }
            }
        }
        if (!out.isEmpty()) return out;

        Map<String, String> credits = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("backgrounds/credits.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length >= 2) credits.put(parts[0].trim(), parts[1].trim());
            }
        } catch (IOException ignored) {
        }
        try {
            String[] names = ctx.getAssets().list("backgrounds");
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) {
                    if (!n.endsWith(".jpg")) continue;
                    String artist = credits.get(n);
                    out.add(new Photo("backgrounds/" + n, null, artist == null ? "" : "Photo \u00B7 " + artist));
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private Bitmap[] decode(Photo p) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = p.open(ctx)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not an image");
        int rotation = p.rotation();
        int sw = rotation % 180 == 0 ? bounds.outWidth : bounds.outHeight;
        int sh = rotation % 180 == 0 ? bounds.outHeight : bounds.outWidth;
        int sample = 1;
        while (sw / (sample * 2) >= width && sh / (sample * 2) >= height) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap raw;
        try (InputStream in = p.open(ctx)) {
            raw = BitmapFactory.decodeStream(in, null, opts);
        }
        if (raw == null) throw new IOException("Decode failed");

        Matrix m = new Matrix();
        m.postRotate(rotation);
        RectF r = new RectF(0, 0, raw.getWidth(), raw.getHeight());
        m.mapRect(r);
        m.postTranslate(-r.left, -r.top);
        float scale = Math.max(width / r.width(), height / r.height());
        m.postScale(scale, scale);
        m.postTranslate((width - r.width() * scale) / 2f, (height - r.height() * scale) / 2f);

        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(frame).drawBitmap(raw, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        raw.recycle();

        Bitmap blur = Blur.of(frame);
        Bitmap gpu = frame.copy(Bitmap.Config.HARDWARE, false);
        if (gpu != null) {
            frame.recycle();
            frame = gpu;
        }
        return new Bitmap[] {frame, blur};
    }

    private static final class Photo {
        final String asset;
        final File file;
        final String credit;

        Photo(String asset, File file, String credit) {
            this.asset = asset;
            this.file = file;
            this.credit = credit;
        }

        InputStream open(Context c) throws IOException {
            return file != null ? new FileInputStream(file) : c.getAssets().open(asset);
        }

        int rotation() {
            if (file == null) return 0;
            try {
                int o = new ExifInterface(file.getAbsolutePath())
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
                if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
                if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
            } catch (Throwable ignored) {
            }
            return 0;
        }

        @Override
        public String toString() {
            return file != null ? file.getName() : asset;
        }
    }
}
