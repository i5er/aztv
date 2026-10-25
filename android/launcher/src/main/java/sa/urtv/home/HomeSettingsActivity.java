package sa.urtv.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * «الإعدادات», after github.com/TurkiHassan/custom-tv-arabic: one glass sheet over the blurred photo. On the right
 * the home screen (which cards show, which apps this account sees), on the left preferences and shortcuts
 * (clock, digits, city and teams, favourites, photos, the TV's own settings). Everything is per account.
 */
public class HomeSettingsActivity extends Activity {
    private static final int CHECK = 0xFF7BE0A4;
    private static final int ASK_HOME = 7;
    private LinearLayout right, left;
    private View firstRow;

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

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        View back = new View(this);
        back.setBackground(new Glass(back, 0, 0x4D000000, 0x00FFFFFF, false));   // the home photo, blurred
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(28), dp(20), dp(28), dp(16));
        sheet.setBackground(new Glass(sheet, dp(28), 0x1FFFFFFF, 0x66FFFFFF, true));
        root.addView(sheet, new FrameLayout.LayoutParams(dp(840), dp(480), Gravity.CENTER));

        FrameLayout head = new FrameLayout(this);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.RIGHT);
        TextView crumb = Ui.text(this, 11, "regular", Ui.DIM);
        crumb.setText("الرئيسية");
        TextView title = Ui.text(this, 28, "display", Color.WHITE);
        title.setText("الإعدادات");
        title.setIncludeFontPadding(false);
        titles.addView(crumb);
        titles.addView(title);
        head.addView(titles, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT));
        TextView close = Ui.button(this, "إغلاق", false);
        close.setOnClickListener(v -> finish());
        head.addView(close, new FrameLayout.LayoutParams(dp(96), dp(36), Gravity.LEFT | Gravity.CENTER_VERTICAL));
        sheet.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout cols = new LinearLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.topMargin = dp(12);
        sheet.addView(cols, cp);
        left = column();
        right = column();
        cols.addView((View) left.getParent().getParent(), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1f);
        rp.leftMargin = dp(24);
        cols.addView((View) right.getParent().getParent(), rp);
        return root;
    }

    /** A column: its header row on top, the rows below in a scroller. Returns the rows' container. */
    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(dp(4), dp(4), dp(4), dp(10));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        sv.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        col.addView(sv, new LinearLayout.LayoutParams(-1, -1));
        return rows;
    }

    private void fill() {
        View focused = getCurrentFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        right.removeAllViews();
        left.removeAllViews();
        firstRow = null;

        // ---- right: the home screen
        section(right, "تطبيقات الرئيسية", "اضغط OK للإظهار أو الإخفاء");
        sub(right, "لوحات الشاشة الرئيسية");
        toggle(right, "لوحة المباراة القادمة", "show_match");
        toggle(right, "لوحة مواقيت الصلاة", "show_prayers");
        toggle(right, "لوحة الطقس", "show_weather");
        toggle(right, "الترحيب والتاريخ", "show_greeting");
        sub(right, "تطبيقاتك");
        Set<String> hidden = Profiles.hidden(this);
        for (Map.Entry<String, String> app : allApps().entrySet()) {
            boolean shown = !hidden.contains(app.getKey());
            TextView[] parts = row(right, app.getValue(), shown ? "✓" : "مخفي", "app:" + app.getKey(), v -> {
                Profiles.hide(this, app.getKey(), !Profiles.hidden(this).contains(app.getKey()) ? true : false);
                fill();
            });
            parts[1].setTextColor(shown ? CHECK : Ui.DIM);
        }

        // ---- left: preferences
        section(left, "التفضيلات", "اضغط OK للتغيير");
        keepRow();
        boolean h24 = Profiles.flag(this, "clock24", false), latin = Profiles.flag(this, "latin", false);
        row(left, "الساعة", h24 ? "٢٤ ساعة" : "١٢ ساعة", "clock", v -> { Profiles.setFlag(this, "clock24", !h24); fill(); });
        row(left, "الأرقام", latin ? "إنجليزية 123" : "عربية ١٢٣", "digits", v -> { Profiles.setFlag(this, "latin", !latin); fill(); });
        row(left, "المدينة والفرق المتابعة", "AZ TV", "city", v -> open("sa.urtv.screensaver"));
        row(left, "ترتيب المفضلة", "", "dock", v -> startActivity(new Intent(this, DockActivity.class)));
        row(left, "صور الحساب من الجوال", "", "photos", v -> startActivity(new Intent(this, PhotosActivity.class)
            .putExtra(PhotosActivity.EXTRA_PROFILE, Profiles.active(this).id)));
        row(left, "الحسابات", "مين يشاهد اليوم؟", "accounts", v -> startActivity(new Intent(this, ProfilesActivity.class)));
        row(left, "الواي فاي", "", "wifi", v -> system(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        row(left, "إعدادات التلفزيون", "النظام", "system", v -> system(new Intent(Settings.ACTION_SETTINGS)));
        TextView note = Ui.text(this, 11, "regular", 0x99FFFFFF);
        note.setText("هذه الإعدادات خاصة بحساب «" + Profiles.active(this).name + "». تتغير الخلفية حسب صورك.");
        note.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(10);
        left.addView(note, np);

        View again = focusTag == null ? null : findTag(focusTag);
        View target = again != null ? again : firstRow;
        if (target != null) target.post(target::requestFocus);
    }

    /**
     * «الواجهة الرئيسية الثابتة»: OK turns it on and walks through what is missing (make it the home app, then the
     * keeper in Accessibility); once all is set OK only says so, and a long press turns it off, so it isn't by accident.
     */
    private void keepRow() {
        boolean want = HomeKeeper.wanted(this), home = HomeKeeper.isDefault(this), svc = HomeKeeper.serviceOn(this);
        boolean done = want && svc;
        String value = !want ? "متوقفة" : done ? "مفعّلة ✓" : "ناقصها خطوة";
        TextView[] parts = row(left, "الواجهة الرئيسية الثابتة", value, "keep", v -> {
            if (done && home) { toast("مفعّلة: AZ TV يبقى الواجهة دائماً. للإيقاف اضغط مطوّلاً"); return; }
            HomeKeeper.setWanted(this, true);
            if (!home && HomeKeeper.askDefault(this, ASK_HOME)) return;   // the rest after Android's own question
            nextKeepStep();
        });
        parts[1].setTextColor(done ? CHECK : want ? 0xFFFFD27A : Ui.DIM);
        ((View) parts[0].getParent()).setOnLongClickListener(v -> {
            if (!HomeKeeper.wanted(this)) return false;
            HomeKeeper.setWanted(this, false);
            toast("أوقفت الواجهة الثابتة");
            fill();
            return true;
        });
    }

    /** After the home-app question: turn the keeper on by itself if allowed, else open Accessibility for it. */
    private void nextKeepStep() {
        if (HomeKeeper.enableService(this)) {
            toast("تم ✓ AZ TV الواجهة الرئيسية ولن تتغير");
        } else {
            toast("فعّل «AZ TV: الواجهة الثابتة» من إمكانية الوصول، ثم ارجع");
            system(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
        fill();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ASK_HOME) nextKeepStep();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private View findTag(Object tag) {
        for (LinearLayout col : new LinearLayout[] {right, left})
            for (int i = 0; i < col.getChildCount(); i++) if (tag.equals(col.getChildAt(i).getTag())) return col.getChildAt(i);
        return null;
    }

    private void section(LinearLayout col, String title, String hint) {
        FrameLayout f = new FrameLayout(this);
        TextView t = Ui.text(this, 16, "medium", Color.WHITE);
        t.setText(title);
        f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT));
        TextView h = Ui.text(this, 10, "regular", 0x99FFFFFF);
        h.setText(hint);
        f.addView(h, new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        col.addView(f, lp);
    }

    private void sub(LinearLayout col, String text) {
        TextView t = Ui.text(this, 11, "regular", Ui.DIM);
        t.setText(text);
        t.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), dp(4), dp(4));
        col.addView(t, lp);
    }

    private void toggle(LinearLayout col, String label, String key) {
        boolean on = Profiles.flag(this, key, true);
        TextView[] parts = row(col, label, on ? "ظاهر" : "مخفي", key, v -> { Profiles.setFlag(this, key, !on); fill(); });
        parts[1].setTextColor(on ? Color.WHITE : Ui.DIM);
    }

    /** A glass row: label on the right, value on the left; white when focused. Returns {label, value}. */
    private TextView[] row(LinearLayout col, String label, String value, Object tag, View.OnClickListener click) {
        FrameLayout r = new FrameLayout(this);
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag(tag);
        r.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = Ui.rounded(0x17FFFFFF, 12, this);
        r.setBackground(bg);
        TextView l = Ui.text(this, 15, "regular", Color.WHITE);
        l.setText(label);
        l.setSingleLine(true);
        r.addView(l, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        TextView v = Ui.text(this, 14, "regular", 0xE6FFFFFF);
        v.setText(value);
        r.addView(v, new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        int[] keep = new int[] {0};
        r.setOnFocusChangeListener((view, has) -> {
            bg.setColor(has ? 0xF7FFFFFF : 0x17FFFFFF);
            l.setTextColor(has ? 0xFF111111 : Color.WHITE);
            if (has) keep[0] = v.getCurrentTextColor();
            v.setTextColor(has ? 0xFF111111 : keep[0] != 0 ? keep[0] : 0xE6FFFFFF);
            view.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(150).start();
        });
        r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.setMargins(0, dp(3), 0, dp(3));
        col.addView(r, lp);
        if (firstRow == null && col == right) firstRow = r;
        return new TextView[] {l, v};
    }

    /** Every TV app, hidden ones included, by name. */
    private Map<String, String> allApps() {
        PackageManager pm = getPackageManager();
        List<String[]> list = new ArrayList<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<String> seen = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || seen.contains(p)) continue;
            seen.add(p);
            list.add(new String[] {p, String.valueOf(ri.loadLabel(pm))});
        }
        Collections.sort(list, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] s : list) out.put(s[0], s[1]);
        return out;
    }

    private void open(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i);
    }

    private void system(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) { }
    }
}
