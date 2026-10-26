package sa.urtv.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Luma 2.4's tab bar (github.com/0xProbabillity/luma-tv, MIT): a capsule 44 dp tall with one 36 dp pill inside,
 * so the two curves are concentric. The pill slides under the focused tab (white, dark text) and settles,
 * as clear glass, on the current tab when focus leaves the bar. Labels are given in reading order; the first
 * sits on the right.
 */
final class TabBar extends FrameLayout {
    static final int HEIGHT = 44, PILL = 36;

    final List<TextView> tabs = new ArrayList<>();
    private final LinearLayout row;
    private final View pill;
    private final GradientDrawable pillShape;
    private final PathInterpolator ease = new PathInterpolator(.22f, 1f, .36f, 1f);
    private ValueAnimator move;
    private int current, pillColor = REST;
    private View.OnFocusChangeListener onFocus;

    private static final int REST = 0x2EFFFFFF, FOCUS = 0xF5FFFFFF;
    private static final int INK_FOCUS = 0xFF1D1D1F, INK_CURRENT = 0xFFFFFFFF, INK_OTHER = 0xB3FFFFFF;

    /** glass: frost the wallpaper behind it (the home screen); otherwise a plain veil. */
    TabBar(Context c, boolean glass, String... labels) {
        super(c);
        setClipChildren(false);
        int pad = (Ui.dp(c, HEIGHT) - Ui.dp(c, PILL)) / 2;
        setPadding(pad, pad, pad, pad);
        float r = Ui.dp(c, HEIGHT) / 2f;
        if (glass) setBackground(new Glass(this, r, 0x1FFFFFFF, 0x33FFFFFF, false, true));
        else {
            GradientDrawable g = Ui.rounded(0x1FFFFFFF, HEIGHT / 2f, c);
            g.setStroke(Math.max(1, Ui.dp(c, 0.8f)), 0x1FFFFFFF);
            setBackground(g);
        }

        pillShape = Ui.rounded(REST, PILL / 2f, c);
        pillShape.setStroke(Math.max(1, Ui.dp(c, 0.8f)), 0x33FFFFFF);
        pill = new View(c);
        pill.setBackground(pillShape);
        addView(pill, new LayoutParams(0, Ui.dp(c, PILL), Gravity.CENTER_VERTICAL | Gravity.LEFT));

        row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (String label : labels) {
            TextView t = Ui.text(c, 14, "medium", INK_OTHER);
            t.setText(label);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
            t.setFocusable(true);
            t.setClickable(true);
            t.setId(View.generateViewId());
            t.setOnFocusChangeListener((v, has) -> {
                if (has) slide(true);
                else v.post(() -> { if (focused() < 0) slide(true); });
                if (onFocus != null) onFocus.onFocusChange(v, has);
            });
            tabs.add(t);
            row.addView(t, new LinearLayout.LayoutParams(-2, Ui.dp(c, PILL)));
        }
        Ui.rtl(row);
        addView(row, new LayoutParams(-2, -1));
        // first layout, or the labels changed width: place the pill without a slide (not while it moves)
        addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, orr, ob) -> {
            if (rr - l != orr - ol || pill.getLayoutParams().width == 0) post(() -> slide(false));
        });
    }

    TextView tab(int i) { return tabs.get(i); }

    /** Left to right on screen, for D-pad wiring. */
    List<TextView> onScreen() {
        List<TextView> l = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) l.add((TextView) row.getChildAt(i));
        return l;
    }

    void setOnTabFocus(View.OnFocusChangeListener l) { onFocus = l; }

    void setCurrent(int i) { current = i; slide(true); }

    int current() { return current; }

    private int focused() {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).hasFocus()) return i;
        return -1;
    }

    private void slide(boolean animate) {
        int f = focused(), idx = f >= 0 ? f : current;
        TextView target = tabs.get(idx);
        if (target.getWidth() == 0) return;
        float fromX = pill.getTranslationX(), toX = row.getLeft() + target.getLeft() - getPaddingLeft();
        int fromW = pill.getLayoutParams().width, toW = target.getWidth();
        int fromC = pillColor, toC = f >= 0 ? FOCUS : REST;
        int[] fromT = new int[tabs.size()], toT = new int[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            fromT[i] = tabs.get(i).getCurrentTextColor();
            toT[i] = i == f ? INK_FOCUS : i == current ? INK_CURRENT : INK_OTHER;
        }
        if (move != null) move.cancel();
        ArgbEvaluator argb = new ArgbEvaluator();
        boolean first = fromW == 0;
        move = ValueAnimator.ofFloat(0, 1);
        move.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            pill.setTranslationX(first ? toX : fromX + (toX - fromX) * p);
            ViewGroup.LayoutParams lp = pill.getLayoutParams();
            lp.width = Math.round(first ? toW : fromW + (toW - fromW) * p);
            pill.setLayoutParams(lp);
            pillColor = (int) argb.evaluate(p, fromC, toC);
            pillShape.setColor(pillColor);
            pillShape.setStroke(Math.max(1, Ui.dp(getContext(), 0.8f)), toC == FOCUS ? 0x00FFFFFF : 0x33FFFFFF);
            float t = Math.max(0, Math.min(1, (p - .12f) / .3f));   // text flips quickly, never half-matching the pill
            for (int i = 0; i < tabs.size(); i++) {
                TextView tv = tabs.get(i);
                tv.setTextColor((int) argb.evaluate(t, fromT[i], toT[i]));
                tv.setShadowLayer(toT[i] == INK_FOCUS ? 0 : Ui.dp(getContext(), 3), 0, Ui.dp(getContext(), 1), 0x66000000);
            }
        });
        move.setDuration(animate && !first ? 280 : 0);
        move.setInterpolator(ease);
        move.start();
    }
}
