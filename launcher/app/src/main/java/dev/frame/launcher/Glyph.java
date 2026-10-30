package dev.frame.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small outline icons drawn on a 24x24 grid, so no icon assets are needed. */
final class Glyph extends Drawable {
    static final int GRID = 0, GEAR = 1, INPUT = 2, IMAGE = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Glyph(int type, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.6f);
        switch (type) {
            case GEAR: {
                int teeth = 8;
                float outer = 10f, inner = 7.6f;
                double tip = Math.toRadians(10), base = Math.toRadians(15);
                for (int i = 0; i < teeth; i++) {
                    double a = Math.PI * 2 * i / teeth;
                    point(i == 0, inner, a - base);
                    point(false, outer, a - tip);
                    point(false, outer, a + tip);
                    point(false, inner, a + base);
                }
                path.close();
                path.addCircle(12f, 12f, 3.2f, Path.Direction.CW);
                break;
            }
            case IMAGE:
                path.addRoundRect(new RectF(3f, 4.5f, 21f, 19.5f), 2.5f, 2.5f, Path.Direction.CW);
                path.addCircle(8.5f, 9.5f, 1.6f, Path.Direction.CW);
                path.moveTo(3.6f, 17.4f);
                path.lineTo(9f, 12.6f);
                path.lineTo(12.6f, 15.8f);
                path.lineTo(15.6f, 13.2f);
                path.lineTo(20.4f, 17.4f);
                break;
            case INPUT:
                path.moveTo(9f, 7.5f);
                path.lineTo(9f, 5.5f);
                path.quadTo(9f, 4f, 10.5f, 4f);
                path.lineTo(18.5f, 4f);
                path.quadTo(20f, 4f, 20f, 5.5f);
                path.lineTo(20f, 18.5f);
                path.quadTo(20f, 20f, 18.5f, 20f);
                path.lineTo(10.5f, 20f);
                path.quadTo(9f, 20f, 9f, 18.5f);
                path.lineTo(9f, 16.5f);
                path.moveTo(3f, 12f);
                path.lineTo(14.5f, 12f);
                path.moveTo(11f, 8.5f);
                path.lineTo(14.5f, 12f);
                path.lineTo(11f, 15.5f);
                break;
            case GRID:
            default:
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        float x = 4f + c * 9.5f, y = 4f + r * 9.5f;
                        path.addRoundRect(new RectF(x, y, x + 6.5f, y + 6.5f), 1.8f, 1.8f, Path.Direction.CW);
                    }
                }
                break;
        }
    }

    private void point(boolean first, float radius, double angle) {
        float x = 12f + (float) (radius * Math.cos(angle));
        float y = 12f + (float) (radius * Math.sin(angle));
        if (first) path.moveTo(x, y);
        else path.lineTo(x, y);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        int save = c.save();
        c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f);
        c.scale(s, s);
        c.drawPath(path, paint);
        c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
