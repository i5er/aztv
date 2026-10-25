package sa.urtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Look shared by the accounts and photos screens: the home screen's fonts, gold, pills and dark glass. */
final class Ui {
    static final int GOLD = 0xFFD6AF6B, CREAM = 0xFFFFFDF6, DIM = 0xB3FFFFFF, GLASS = 0xE61A1714;
    private static final Map<String, Typeface> FONTS = new HashMap<>();

    private Ui() {}

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** Thmanyah: display, light, medium or regular. */
    static Typeface font(Context c, String family) {
        String file = family.contains("display") ? "ThmanyahDisplay-Bold"
                : family.contains("light") ? "Thmanyah-Light"
                : family.contains("medium") ? "Thmanyah-Medium" : "Thmanyah-Regular";
        Typeface tf = FONTS.get(file);
        if (tf == null) {
            try { tf = Typeface.createFromAsset(c.getAssets(), "fonts/" + file + ".otf"); }
            catch (RuntimeException e) { tf = Typeface.DEFAULT; }
            FONTS.put(file, tf);
        }
        return tf;
    }

    static TextView text(Context c, float sp, String family, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(c, family));
        t.setTextColor(color);
        return t;
    }

    /**
     * Right-to-left order for a row: the launcher doesn't declare supportsRtl (its home screen places views
     * itself), so LAYOUT_DIRECTION_RTL is ignored; call after the children are added: the first ends up on the right.
     */
    static void rtl(android.widget.LinearLayout row) {
        int n = row.getChildCount();
        View[] kids = new View[n];
        android.view.ViewGroup.LayoutParams[] lps = new android.view.ViewGroup.LayoutParams[n];
        for (int i = 0; i < n; i++) { kids[i] = row.getChildAt(i); lps[i] = kids[i].getLayoutParams(); }
        row.removeAllViews();
        for (int i = n - 1; i >= 0; i--) row.addView(kids[i], lps[i]);
    }

    /** The account's choice in «الإعدادات»: 123 instead of ١٢٣. */
    static volatile boolean latinDigits;

    static String digits(String s) {
        if (latinDigits) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return b.toString();
    }

    static GradientDrawable rounded(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(color);
        return g;
    }

    /** The dark rounded card the dialogs sit on. */
    static GradientDrawable card(Context c) {
        GradientDrawable g = rounded(GLASS, 28, c);
        g.setStroke(dp(c, 1), 0x26FFFFFF);
        return g;
    }

    /** A pill button: white when focused, gold when primary. */
    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, 15, "medium", primary ? 0xFF1A1206 : Color.WHITE);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setId(View.generateViewId());
        b.setPadding(dp(c, 26), 0, dp(c, 26), 0);
        int idle = primary ? GOLD : 0x26FFFFFF;
        GradientDrawable bg = rounded(idle, 22, c);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? (primary ? 0xFFF1CC86 : 0xF2FFFFFF) : idle);
            b.setTextColor(has || primary ? 0xFF1A1206 : Color.WHITE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                .setInterpolator(new OvershootInterpolator(2f)).setDuration(200).start();
        });
        return b;
    }

    /** The first bundled Saudi photo, small: enough for a blurred backdrop. */
    static Bitmap backdrop(Context c) {
        try {
            String[] w = c.getAssets().list("wallpapers");
            if (w == null || w.length == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(c.getAssets().open("wallpapers/" + w[(int) (System.currentTimeMillis() / 86400000L % w.length)]), null, o);
        } catch (Exception e) {
            return null;
        }
    }
}
