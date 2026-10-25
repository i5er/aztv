package sa.urtv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Liquid glass: a view's background shows the wallpaper behind it, blurred, with a light tint, a sheen across
 * its top and a bright rim. Views can't blur what's under them on this Android, so every glass view draws the
 * same small blurred copy of the wallpaper, shifted to where the view sits on screen.
 */
final class Glass extends Drawable {
    private static Bitmap blurred, soft;   // frosted, and a light blur for clear liquid glass
    private static int screenW, screenH;
    private static final List<java.lang.ref.WeakReference<View>> HOSTS = new ArrayList<>();

    private final View host;
    private final float radius;
    private final int tint, rim;
    private final boolean sheen, clear;
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final int[] loc = new int[2];

    /** radius in px; tint is laid over the blur (white for light glass, black for dark). */
    Glass(View host, float radius, int tint, int rim, boolean sheen) { this(host, radius, tint, rim, sheen, false); }

    /** clear: liquid glass you can see through — barely blurred, a bright rim and a sheen do the work. */
    Glass(View host, float radius, int tint, int rim, boolean sheen, boolean clear) {
        this.clear = clear;
        this.host = host;
        this.radius = radius;
        this.tint = tint;
        this.rim = rim;
        this.sheen = sheen;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, host.getResources().getDisplayMetrics().density * 1.1f));
        synchronized (HOSTS) { HOSTS.add(new java.lang.ref.WeakReference<>(host)); }
    }

    /** A new wallpaper: blur a small copy once, and redraw every glass view. */
    static void setWallpaper(Bitmap src, int w, int h) {
        if (src == null) return;
        screenW = w;
        screenH = h;
        int sw = Math.max(1, w / 14), sh = Math.max(1, h / 14);
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true).copy(Bitmap.Config.ARGB_8888, true);
        boxBlur(small, 2);
        boxBlur(small, 2);
        blurred = small;
        Bitmap light = Bitmap.createScaledBitmap(src, Math.max(1, w / 5), Math.max(1, h / 5), true).copy(Bitmap.Config.ARGB_8888, true);
        boxBlur(light, 1);
        soft = light;
        invalidateAll();
    }

    /** After a scroll or a move: glass shows what is behind its new place. */
    static void invalidateAll() {
        synchronized (HOSTS) {
            for (Iterator<java.lang.ref.WeakReference<View>> it = HOSTS.iterator(); it.hasNext(); ) {
                View v = it.next().get();
                if (v == null) it.remove(); else v.invalidate();
            }
        }
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        r.set(b);
        path.reset();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(path);
        Bitmap under = clear && soft != null ? soft : blurred;
        if (under != null && screenW > 0) {
            host.getLocationOnScreen(loc);
            // undo the view's own scale so the blur stays put while it grows on focus
            float sx = host.getScaleX(), sy = host.getScaleY();
            float left = -loc[0] / sx, top = -loc[1] / sy;
            c.drawBitmap(under, null, new RectF(left, top, left + screenW / sx, top + screenH / sy), bmp);
        }
        fill.setColor(tint);
        c.drawRect(r, fill);
        if (sheen) {
            shine.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                clear ? 0x24FFFFFF : 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(r, shine);
        }
        c.restore();
        // rim: brighter on top, like light catching the edge of glass
        stroke.setShader(new LinearGradient(0, b.top, 0, b.bottom, rim, (rim & 0x00FFFFFF) | ((rim >>> 26) << 24), Shader.TileMode.CLAMP));
        if (clear) {   // light caught on both edges, like the iOS liquid glass
            stroke.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[] {rim, (rim & 0x00FFFFFF) | 0x14000000, (rim & 0x00FFFFFF) | ((rim >>> 25) << 24)},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        float inset = stroke.getStrokeWidth() / 2f;
        r.inset(inset, inset);
        c.drawRoundRect(r, radius, radius, stroke);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /** Horizontal then vertical running-average blur, in place. */
    private static void boxBlur(Bitmap bm, int rad) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h], out = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        pass(px, out, w, h, rad, 1, w);
        pass(out, px, h, w, rad, w, 1);
        bm.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** One direction: len pixels per line, lines lines; step between pixels, stride between lines. */
    private static void pass(int[] in, int[] out, int len, int lines, int rad, int step, int stride) {
        int n = rad * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * stride;
            int ra = 0, rr = 0, rg = 0, rb = 0;
            for (int i = -rad; i <= rad; i++) {
                int p = in[base + Math.min(len - 1, Math.max(0, i)) * step];
                ra += p >>> 24; rr += (p >> 16) & 255; rg += (p >> 8) & 255; rb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = (ra / n) << 24 | (rr / n) << 16 | (rg / n) << 8 | (rb / n);
                int pOut = in[base + Math.max(0, i - rad) * step];
                int pIn = in[base + Math.min(len - 1, i + rad + 1) * step];
                ra += (pIn >>> 24) - (pOut >>> 24);
                rr += ((pIn >> 16) & 255) - ((pOut >> 16) & 255);
                rg += ((pIn >> 8) & 255) - ((pOut >> 8) & 255);
                rb += (pIn & 255) - (pOut & 255);
            }
        }
    }
}
