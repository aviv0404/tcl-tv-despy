package dev.frame.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/** Full-screen photo with soft scrims so the clock and dock stay legible on any picture. */
final class BackdropView extends View {
    private final Backdrop backdrop;
    private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint top = new Paint(Paint.DITHER_FLAG);
    private final Paint glow = new Paint(Paint.DITHER_FLAG);
    private final Paint bottom = new Paint(Paint.DITHER_FLAG);
    private final Rect dst = new Rect();

    BackdropView(Context c, Backdrop backdrop) {
        super(c);
        this.backdrop = backdrop;
        backdrop.attach(this);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        top.setShader(new LinearGradient(0, 0, 0, h * 0.42f, 0x66000000, 0, Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(Ui.dp(170), Ui.dp(96), Ui.dp(380),
                0x59000000, 0, Shader.TileMode.CLAMP));
        bottom.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0, 0x80000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        dst.set(0, 0, w, h);
        if (backdrop.cur != null) {
            photo.setAlpha(255);
            c.drawBitmap(backdrop.cur, null, dst, photo);
        }
        if (backdrop.next != null) {
            photo.setAlpha(Math.round(backdrop.fade * 255));
            c.drawBitmap(backdrop.next, null, dst, photo);
        }
        c.drawRect(0, 0, w, h * 0.42f, top);
        c.drawRect(0, 0, w, h, glow);
        c.drawRect(0, h * 0.5f, w, h, bottom);
    }
}
