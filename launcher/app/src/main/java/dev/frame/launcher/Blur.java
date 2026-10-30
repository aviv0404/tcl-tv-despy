package dev.frame.launcher;

import android.graphics.Bitmap;

/** Cheap, good-looking blur: downscale, then three passes of a separable box blur. */
final class Blur {
    private Blur() {}

    static Bitmap of(Bitmap src) {
        Bitmap mid = Bitmap.createScaledBitmap(src, 576, 324, true);
        Bitmap small = Bitmap.createScaledBitmap(mid, 192, 108, true);
        if (mid != src && mid != small) mid.recycle();
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
        for (int pass = 0; pass < 3; pass++) {
            horizontal(px, tmp, w, h, 4);
            vertical(tmp, px, w, h, 4);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void horizontal(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + Math.min(Math.max(i, 0), w - 1)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[row + Math.min(x + r + 1, w - 1)];
                int sub = in[row + Math.max(x - r, 0)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void vertical(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[Math.min(Math.max(i, 0), h - 1) * w + x];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[Math.min(y + r + 1, h - 1) * w + x];
                int sub = in[Math.max(y - r, 0) * w + x];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }
}
