package dev.frame.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Frosted-glass container: paints the pre-blurred backdrop aligned to its on-screen position,
 * then a tint and a hairline border. Don't scale or translate-animate these views, because the
 * blur alignment is only recomputed on redraw.
 */
class GlassView extends FrameLayout {
    private final Backdrop backdrop;
    private final float radius;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private Bitmap bitmapA, bitmapB;
    private BitmapShader shaderA, shaderB;

    GlassView(Context c, Backdrop backdrop, float radiusDp, int tintColor, int strokeColor) {
        super(c);
        this.backdrop = backdrop;
        this.radius = Ui.dp(radiusDp);
        setWillNotDraw(false);
        tint.setColor(tintColor);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, Ui.density * 0.75f));
        stroke.setColor(strokeColor);
        backdrop.attach(this);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        rect.set(0, 0, getWidth(), getHeight());
        View root = getRootView();
        getLocationInWindow(loc);
        layer(c, backdrop.curBlur, 255, root);
        if (backdrop.nextBlur != null) layer(c, backdrop.nextBlur, Math.round(backdrop.fade * 255), root);
        c.drawRoundRect(rect, radius, radius, tint);
        if (stroke.getColor() != 0) {
            float inset = stroke.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            c.drawRoundRect(rect, Math.max(0, radius - inset), Math.max(0, radius - inset), stroke);
        }
    }

    private void layer(Canvas c, Bitmap b, int alpha, View root) {
        if (b == null || alpha <= 0 || root.getWidth() == 0) return;
        BitmapShader s = shader(b);
        matrix.setScale(root.getWidth() / (float) b.getWidth(), root.getHeight() / (float) b.getHeight());
        matrix.postTranslate(-loc[0], -loc[1]);
        s.setLocalMatrix(matrix);
        image.setShader(s);
        image.setAlpha(alpha);
        c.drawRoundRect(rect, radius, radius, image);
    }

    private BitmapShader shader(Bitmap b) {
        if (b == bitmapA) return shaderA;
        if (b == bitmapB) return shaderB;
        bitmapB = bitmapA;
        shaderB = shaderA;
        bitmapA = b;
        shaderA = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        return shaderA;
    }
}
