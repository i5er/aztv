package sa.urtv.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * «التطبيقات المفتوحة», like the iPhone's app switcher: the apps opened in this account's session as big cards,
 * newest on the right. OK opens one, ▲ throws it away (closes it), long-press OK for restart / close / delete.
 */
public class RecentsActivity extends Activity {
    private LinearLayout row;
    private HorizontalScrollView scroller;
    private TextView empty, closeAll;
    private FrameLayout overlay;
    private View lastFocus;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        fill();
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) { hideOverlay(); return; }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap back = Ui.backdrop(this);
        if (back != null) bg.setImageBitmap(back);
        if (Build.VERSION.SDK_INT >= 31) bg.setRenderEffect(RenderEffect.createBlurEffect(dp(30), dp(30), Shader.TileMode.CLAMP));
        bg.setAlpha(0.5f);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xD9000000, 0x99000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(30), dp(48), dp(24));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = Ui.text(this, 28, "display", Ui.CREAM);
        title.setText("التطبيقات المفتوحة");
        title.setGravity(Gravity.RIGHT);
        col.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = Ui.text(this, 13, "regular", Ui.DIM);
        hint.setText("OK للفتح   ·   ▲ للإغلاق   ·   اضغط مطوّلاً لإعادة التشغيل أو الحذف");
        hint.setGravity(Gravity.RIGHT);
        hint.setTextDirection(View.TEXT_DIRECTION_RTL);   // starts with «OK»: keep the line right-to-left
        col.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout mid = new FrameLayout(this);
        mid.setClipChildren(false);
        col.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));

        scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setFillViewport(true);
        row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(30), dp(40), dp(30), dp(20));
        scroller.addView(row, new FrameLayout.LayoutParams(-2, -1));
        mid.addView(scroller, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        empty = Ui.text(this, 18, "regular", 0x99FFFFFF);
        empty.setGravity(Gravity.CENTER);
        mid.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        closeAll = Ui.button(this, "إغلاق الكل", false);
        closeAll.setOnClickListener(v -> closeAll());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(180), dp(42));
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(closeAll, cp);

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB3000000);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private void fill() {
        row.removeAllViews();
        boolean allowed = Apps.canSeeUsage(this);
        List<Apps.Recent> list = allowed ? Apps.recent(this) : new ArrayList<>();
        for (Apps.Recent r : list) row.addView(card(r));
        Ui.rtl(row);   // newest on the right
        empty.setText(allowed ? "ما فيه تطبيقات مفتوحة الحين" : "يحتاج إذن «الوصول لبيانات الاستخدام» (يُعطى من الكمبيوتر)");
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        closeAll.setVisibility(list.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (!list.isEmpty()) {
            View first = row.getChildAt(row.getChildCount() - 1);
            scroller.post(() -> { scroller.fullScroll(View.FOCUS_RIGHT); first.requestFocus(); });
        }
    }

    private static final float W = 236, H = 133;

    private View card(Apps.Recent r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag(r);

        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16)); }
        });
        card.setElevation(dp(8));
        Drawable art = banner(r.pkg);
        boolean isBanner = art != null;
        if (art == null) art = icon(r.pkg);
        card.setBackgroundColor(isBanner ? 0xFF1C1C1E : 0xFF2A2622);
        ImageView img = new ImageView(this);
        img.setImageDrawable(art);
        img.setScaleType(isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!isBanner) img.setPadding(dp(40), dp(26), dp(40), dp(26));
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View ring = new View(this);
        GradientDrawable rg = Ui.rounded(0x00000000, 16, this);
        rg.setStroke(dp(3), Color.WHITE);
        ring.setBackground(rg);
        ring.setAlpha(0f);
        card.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        box.addView(card, new LinearLayout.LayoutParams(dp(W), dp(H)));

        TextView name = Ui.text(this, 15, "medium", Ui.DIM);
        name.setText(r.label);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(W), -2);
        np.topMargin = dp(12);
        box.addView(name, np);
        TextView when = Ui.text(this, 12, "regular", 0x80FFFFFF);
        when.setText(ago(r.lastUsed));
        when.setGravity(Gravity.CENTER);
        box.addView(when, new LinearLayout.LayoutParams(dp(W), -2));

        box.setOnFocusChangeListener((v, has) -> {
            card.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(14) : 0)
                .setInterpolator(has ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator()).setDuration(240).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            name.setTextColor(has ? Color.WHITE : Ui.DIM);
            if (has) lastFocus = v;
        });
        box.setOnClickListener(v -> { if (!Apps.open(this, r.pkg)) toast("ما قدرت أفتح التطبيق"); });
        box.setOnLongClickListener(v -> { options(box, r); return true; });
        box.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN) { throwAway(box, r); return true; }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(14), 0, dp(14), 0);
        box.setLayoutParams(lp);
        return box;
    }

    /** ▲: the card flies up and the app closes, like swiping it away on an iPhone. */
    private void throwAway(View box, Apps.Recent r) {
        int i = row.indexOfChild(box);
        box.animate().translationY(-dp(300)).alpha(0f).setInterpolator(new AccelerateInterpolator()).setDuration(260)
            .withEndAction(() -> {
                Apps.close(this, r.pkg);
                row.removeView(box);
                if (row.getChildCount() == 0) { fill(); return; }
                row.getChildAt(Math.max(0, Math.min(i, row.getChildCount()) - 1)).requestFocus();
            }).start();
    }

    private void closeAll() {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            Apps.close(this, ((Apps.Recent) v.getTag()).pkg);
            v.animate().translationY(-dp(300)).alpha(0f).setStartDelay(i * 50L).setDuration(240).start();
        }
        row.postDelayed(this::fill, 300 + row.getChildCount() * 50L);
        toast("تم إغلاق كل التطبيقات");
    }

    // ---------------------------------------------------------------- options

    private void options(View box, Apps.Recent r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(22), dp(28), dp(22));
        card.setBackground(Ui.card(this));
        card.setElevation(dp(24));
        TextView t = Ui.text(this, 21, "display", Ui.CREAM);
        t.setText(r.label);
        t.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.bottomMargin = dp(12);
        card.addView(t, tp);

        List<TextView> bs = new ArrayList<>();
        bs.add(option("فتح", () -> Apps.open(this, r.pkg)));
        bs.add(option("إعادة تشغيل", () -> {
            Apps.close(this, r.pkg);
            row.postDelayed(() -> Apps.open(this, r.pkg), 500);
        }));
        bs.add(option("إغلاق", () -> throwAway(box, r)));
        if (Apps.removable(this, r.pkg)) bs.add(option("حذف التطبيق من التلفزيون", () -> Apps.uninstall(this, r.pkg)));
        bs.add(option("إلغاء", () -> { }));
        for (TextView b : bs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
            lp.setMargins(0, dp(4), 0, dp(4));
            card.addView(b, lp);
        }
        overlay.removeAllViews();
        overlay.addView(card, new FrameLayout.LayoutParams(dp(380), -2, Gravity.CENTER));
        overlay.setVisibility(View.VISIBLE);
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        closeAll.setFocusable(false);
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(180).start();
        bs.get(0).post(bs.get(0)::requestFocus);
    }

    private TextView option(String label, Runnable action) {
        TextView b = Ui.button(this, label, false);
        b.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> { hideOverlay(); action.run(); });
        return b;
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
        overlay.removeAllViews();
        row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        closeAll.setFocusable(true);
        if (lastFocus != null && lastFocus.getParent() == row) lastFocus.requestFocus();
    }

    // ---------------------------------------------------------------- helpers

    private Drawable banner(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            ResolveInfo ri = i == null ? null : pm.resolveActivity(i, 0);
            Drawable d = ri == null ? null : ri.activityInfo.loadBanner(pm);
            if (d == null) d = pm.getApplicationBanner(pkg);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private Drawable icon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    private static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        if (m < 1) return "الآن";
        if (m < 60) return "قبل " + Ui.digits(String.valueOf(m)) + " د";
        long h = m / 60;
        return h == 1 ? "قبل ساعة" : "قبل " + Ui.digits(String.valueOf(h)) + " س";
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
