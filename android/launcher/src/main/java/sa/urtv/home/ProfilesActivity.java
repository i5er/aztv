package sa.urtv.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * «مين يشاهد اليوم؟»: big round avatars, the focused one lifts with a gold ring. Shown when the TV wakes
 * (gate mode: Back can't skip it) and from the account pill on the home screen. Long-press OK on an account
 * to edit it, lock it with a code, open its photos or delete it; the «+» adds one.
 */
public class ProfilesActivity extends Activity {
    static final String EXTRA_GATE = "gate";

    private boolean gate;
    private FrameLayout root, overlay;
    private LinearLayout picker, row;
    private TextView subtitle;
    private final List<View> overlays = new ArrayList<>();   // dialogs, top last
    private PinPad pad;                                       // the PIN pad on top, for the remote's digit keys
    private String focusId;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        gate = getIntent().getBooleanExtra(EXTRA_GATE, false);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(build());
        fill(true);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        gate = gate || i.getBooleanExtra(EXTRA_GATE, false);
        while (!overlays.isEmpty()) popOverlay();
        fill(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        subtitle.setText(gate ? "اختر حسابك للمتابعة" : "اختر الحساب أو أضف حساباً جديداً");
    }

    @Override
    public void onBackPressed() {
        if (!overlays.isEmpty()) { popOverlay(); return; }
        if (!gate) finish();   // on wake an account must be chosen
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (pad != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { pad.press((char) ('0' + k - KeyEvent.KEYCODE_0)); return true; }
            if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) { pad.press((char) ('0' + k - KeyEvent.KEYCODE_NUMPAD_0)); return true; }
            if (k == KeyEvent.KEYCODE_DEL) { pad.erase(); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) { return Ui.dp(this, v); }

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap back = Ui.backdrop(this);
        if (back != null) bg.setImageBitmap(back);
        if (Build.VERSION.SDK_INT >= 31) bg.setRenderEffect(RenderEffect.createBlurEffect(dp(24), dp(24), Shader.TileMode.CLAMP));
        bg.setAlpha(0.55f);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xE6000000, 0x99000000, 0xB3000000, 0xF2000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        // warm glow behind the avatars
        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.55f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.45f);
        g.setColors(new int[] {0x40D6AF6B, 0x10D6AF6B, Color.TRANSPARENT});
        glow.setBackground(g);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setGravity(Gravity.CENTER_HORIZONTAL);
        picker.setClipChildren(false);
        picker.setClipToPadding(false);

        TextView brand = Ui.text(this, 14, "medium", Ui.GOLD);
        brand.setText("AZ TV");
        brand.setLetterSpacing(0.08f);
        picker.addView(brand, new LinearLayout.LayoutParams(-2, -2));

        TextView title = Ui.text(this, 44, "display", Ui.CREAM);
        title.setText("مين يشاهد اليوم؟");
        title.setShadowLayer(dp(20), 0, 0, 0x99000000);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(6);
        picker.addView(title, tp);

        subtitle = Ui.text(this, 15, "regular", Ui.DIM);
        picker.addView(subtitle, new LinearLayout.LayoutParams(-2, -2));

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = dp(52);
        picker.addView(row, rp);

        root.addView(picker, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        TextView hint = Ui.text(this, 13, "regular", 0x80FFFFFF);
        hint.setText("اضغط مطوّلاً على OK لتعديل الحساب أو قفله برمز أو إضافة صوره");
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        ((FrameLayout.LayoutParams) hint.getLayoutParams()).bottomMargin = dp(36);

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB3000000);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    /** (Re)draws the avatars; animate on first show. */
    private void fill(boolean animate) {
        row.removeAllViews();
        List<Profiles.Profile> list = Profiles.all(this);
        String active = Profiles.active(this).id;
        View focus = null;
        for (Profiles.Profile p : list) {
            View v = item(p, p.id.equals(active));
            row.addView(v);
            if (p.id.equals(focusId != null ? focusId : active)) focus = v;
        }
        if (list.size() < Profiles.MAX) row.addView(addItem());
        Ui.rtl(row);   // first account on the right, like Arabic text
        if (focus == null && row.getChildCount() > 0) focus = row.getChildAt(row.getChildCount() - 1);
        if (focus != null) { View f = focus; f.post(f::requestFocus); }
        if (animate) for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(30));
            v.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                .setStartDelay(120 + i * 70L).setDuration(480).start();
        }
    }

    private static final float AVATAR = 128, RING = 146;

    /** One account: the round avatar with its ring and lock badge, and the name. */
    private View item(Profiles.Profile p, boolean isActive) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setFocusable(true);
        box.setClickable(true);
        box.setId(View.generateViewId());
        box.setPadding(dp(18), dp(10), dp(18), dp(10));

        FrameLayout circle = new FrameLayout(this);
        circle.setClipChildren(false);
        View ring = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setStroke(dp(3), Ui.GOLD);
        ring.setBackground(rg);
        ring.setAlpha(0f);
        circle.addView(ring, new FrameLayout.LayoutParams(dp(RING), dp(RING), Gravity.CENTER));

        ImageView av = new ImageView(this);
        av.setImageBitmap(Profiles.avatar(this, p, dp(AVATAR)));
        av.setElevation(dp(10));
        av.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        circle.addView(av, new FrameLayout.LayoutParams(dp(AVATAR), dp(AVATAR), Gravity.CENTER));

        if (p.locked()) {
            TextView lock = Ui.text(this, 13, "regular", Color.WHITE);
            lock.setText("🔒");
            lock.setGravity(Gravity.CENTER);
            GradientDrawable lb = new GradientDrawable();
            lb.setShape(GradientDrawable.OVAL);
            lb.setColor(0xF0151210);
            lb.setStroke(dp(2), 0x40FFFFFF);
            lock.setBackground(lb);
            lock.setElevation(dp(12));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM | Gravity.LEFT);
            lp.setMargins(dp(8), 0, 0, dp(8));
            circle.addView(lock, lp);
        }
        box.addView(circle, new LinearLayout.LayoutParams(dp(RING), dp(RING)));

        TextView name = Ui.text(this, 18, "medium", Ui.DIM);
        name.setText(p.name);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(14);
        box.addView(name, np);

        TextView now = Ui.text(this, 11, "medium", Ui.GOLD);
        now.setText(isActive ? "● الحساب الحالي" : " ");
        now.setGravity(Gravity.CENTER);
        box.addView(now, new LinearLayout.LayoutParams(-2, -2));

        box.setOnFocusChangeListener((v, has) -> {
            circle.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                .setInterpolator(has ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                .setDuration(has ? 320 : 200).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(200).start();
            name.setTextColor(has ? Color.WHITE : Ui.DIM);
            name.animate().translationY(has ? dp(6) : 0).setDuration(200).start();
            if (has) focusId = p.id;
        });
        box.setOnClickListener(v -> choose(p));
        box.setOnLongClickListener(v -> { manage(p); return true; });
        return box;
    }

    private View addItem() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setClickable(true);
        box.setClipChildren(false);
        box.setPadding(dp(18), dp(10), dp(18), dp(10));

        FrameLayout circle = new FrameLayout(this);
        TextView plus = Ui.text(this, 54, "light", 0xCCFFFFFF);
        plus.setText("+");
        plus.setGravity(Gravity.CENTER);
        plus.setIncludeFontPadding(false);
        GradientDrawable dash = new GradientDrawable();
        dash.setShape(GradientDrawable.OVAL);
        dash.setColor(0x14FFFFFF);
        dash.setStroke(dp(2), 0x66FFFFFF, dp(8), dp(6));
        plus.setBackground(dash);
        circle.addView(plus, new FrameLayout.LayoutParams(dp(AVATAR), dp(AVATAR), Gravity.CENTER));
        box.addView(circle, new LinearLayout.LayoutParams(dp(RING), dp(RING)));

        TextView name = Ui.text(this, 18, "medium", Ui.DIM);
        name.setText("إضافة حساب");
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(14);
        box.addView(name, np);
        TextView spacer = Ui.text(this, 11, "medium", Ui.GOLD);
        spacer.setText(" ");
        box.addView(spacer, new LinearLayout.LayoutParams(-2, -2));

        box.setOnFocusChangeListener((v, has) -> {
            circle.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(240).start();
            dash.setColor(has ? 0x33FFFFFF : 0x14FFFFFF);
            dash.setStroke(dp(2), has ? Ui.GOLD : 0x66FFFFFF, dp(8), dp(6));
            plus.setTextColor(has ? Ui.GOLD : 0xCCFFFFFF);
            name.setTextColor(has ? Color.WHITE : Ui.DIM);
        });
        box.setOnClickListener(v -> editor(null));
        return box;
    }

    // ---------------------------------------------------------------- actions

    private void choose(Profiles.Profile p) {
        if (p.locked()) verify(p, () -> enter(p));
        else enter(p);
    }

    private void enter(Profiles.Profile p) {
        Profiles.activate(this, p);
        picker.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(260).withEndAction(() -> {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }).start();
    }

    private void manage(Profiles.Profile p) {
        if (p.locked()) verify(p, () -> menu(p));
        else menu(p);
    }

    private void menu(Profiles.Profile p) {
        LinearLayout card = dialog(dp(420));
        card.addView(avatarHeader(p, p.name, "ماذا تريد أن تفعل؟"));
        List<TextView> buttons = new ArrayList<>();
        buttons.add(menuButton("تعديل الاسم والصورة والقفل", () -> editor(p)));
        buttons.add(menuButton("صور الحساب (من الجوال)", () -> {
            popOverlay();
            startActivity(new Intent(this, PhotosActivity.class).putExtra(PhotosActivity.EXTRA_PROFILE, p.id));
        }));
        if (Profiles.all(this).size() > 1) buttons.add(menuButton("حذف الحساب", () -> confirmDelete(p)));
        buttons.add(menuButton("إلغاء", this::popOverlay));
        for (TextView b : buttons) card.addView(b, buttonLp(-1));
        pushOverlay(card, buttons.get(0));
    }

    private void confirmDelete(Profiles.Profile p) {
        LinearLayout card = dialog(dp(440));
        card.addView(avatarHeader(p, "حذف حساب «" + p.name + "»؟", "تنحذف صوره ومفضلته من هذا التلفزيون."));
        LinearLayout btns = buttonRow();
        TextView del = Ui.button(this, "حذف", true);
        del.setOnClickListener(v -> {
            Profiles.remove(this, p.id);
            while (!overlays.isEmpty()) popOverlay();
            focusId = null;
            fill(false);
        });
        TextView cancel = Ui.button(this, "إلغاء", false);
        cancel.setOnClickListener(v -> popOverlay());
        btns.addView(cancel, buttonLp(dp(150)));
        btns.addView(del, buttonLp(dp(150)));
        card.addView(btns);
        pushOverlay(card, cancel);
    }

    // ---------------------------------------------------------------- add / edit

    /** existing null = a new account. */
    private void editor(Profiles.Profile existing) {
        final Profiles.Profile p = new Profiles.Profile();
        if (existing != null) {
            p.id = existing.id; p.name = existing.name; p.emoji = existing.emoji; p.color = existing.color; p.pin = existing.pin;
        } else {
            List<Profiles.Profile> all = Profiles.all(this);
            p.id = Profiles.newId(this);
            p.name = "";
            p.color = all.size() % Profiles.COLORS.length;
            p.emoji = Profiles.EMOJI[(all.size() * 3 + 1) % Profiles.EMOJI.length];
        }
        final boolean hadPhoto = existing != null && Profiles.avatarFile(this, p.id).isFile();
        final boolean[] usePhoto = {hadPhoto};
        final String[] newPin = {null};
        final View[] saveRef = {null};

        LinearLayout card = dialog(dp(600));   // the screen is 540 dp tall: header, name, avatars, lock and buttons must all fit

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        ImageView preview = new ImageView(this);
        Runnable updatePreview = () -> preview.setImageBitmap(usePhoto[0]
            ? Profiles.avatar(this, p, dp(56)) : Profiles.emojiAvatar(p.emoji, p.color, dp(56)));
        updatePreview.run();
        head.addView(preview, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, 21, "display", Ui.CREAM);
        title.setText(existing == null ? "حساب جديد" : "تعديل الحساب");
        ht.addView(title);
        TextView sub = Ui.text(this, 12, "regular", Ui.DIM);
        sub.setText("لكل حساب مفضلته وصوره، وتقدر تقفله برمز");
        ht.addView(sub);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(-2, -2);
        htp.rightMargin = dp(16);
        ht.setGravity(Gravity.RIGHT);
        head.addView(ht, htp);
        Ui.rtl(head);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        EditText name = new EditText(this);
        name.setText(p.name);
        name.setHint("اسم الحساب");
        name.setHintTextColor(0x66FFFFFF);
        name.setTextColor(Color.WHITE);
        name.setTypeface(Ui.font(this, "medium"));
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(16)});
        name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        name.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        name.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable nb = Ui.rounded(0x1FFFFFFF, 16, this);
        nb.setStroke(dp(2), 0x00000000);
        name.setBackground(nb);
        name.setOnFocusChangeListener((v, has) -> nb.setStroke(dp(2), has ? Ui.GOLD : 0x00000000));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, dp(44));
        nlp.topMargin = dp(12);
        card.addView(name, nlp);

        TextView avLabel = Ui.text(this, 12, "medium", Ui.GOLD);
        avLabel.setGravity(Gravity.RIGHT);
        avLabel.setText(hadPhoto ? "الصورة الرمزية · صورتك من الجوال مختارة، أو اختر رمزاً" : "الصورة الرمزية");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(10);
        alp.bottomMargin = dp(2);
        card.addView(avLabel, alp);

        List<View> chips = new ArrayList<>();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setClipChildren(false);
            for (int k = 0; k < 8; k++) {
                final int idx = r * 8 + k;
                final String emoji = Profiles.EMOJI[idx];
                final int color = idx % Profiles.COLORS.length;
                FrameLayout chip = new FrameLayout(this);
                chip.setFocusable(true);
                chip.setClickable(true);
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(Profiles.emojiAvatar(emoji, color, dp(40)));
                chip.addView(iv, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
                View sel = new View(this);
                GradientDrawable sg = new GradientDrawable();
                sg.setShape(GradientDrawable.OVAL);
                sg.setStroke(dp(3), Color.WHITE);
                sel.setBackground(sg);
                chip.addView(sel, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
                chip.setTag(new Object[] {emoji, sel, sg});
                chip.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.18f : 1f).scaleY(has ? 1.18f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(200).start());
                chip.setOnClickListener(v -> {
                    p.emoji = emoji;
                    p.color = color;
                    usePhoto[0] = false;
                    updatePreview.run();
                    refreshChips(chips, p, usePhoto[0]);
                });
                chips.add(chip);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(52), dp(52));
                cl.setMargins(dp(7), dp(1), dp(7), dp(1));
                line.addView(chip, cl);
            }
            grid.addView(line);
        }
        refreshChips(chips, p, usePhoto[0]);
        card.addView(grid);

        // the lock: a row that reads like a setting
        TextView lock = Ui.button(this, "", false);
        lock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        Runnable lockLabel = () -> lock.setText(p.locked() || newPin[0] != null
            ? "🔒  قفل الحساب برمز سري: مفعّل  ·  OK للإلغاء" : "🔓  قفل الحساب برمز سري: غير مفعّل  ·  OK للتفعيل");
        lockLabel.run();
        lock.setOnClickListener(v -> {
            if (p.locked() || newPin[0] != null) {
                p.pin = "";
                newPin[0] = null;
                lockLabel.run();
            } else {
                // code chosen: straight to «حفظ», so it's one press away
                setPin(pin -> { newPin[0] = pin; lockLabel.run(); saveRef[0].post(saveRef[0]::requestFocus); });
            }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, dp(42));
        llp.topMargin = dp(10);
        card.addView(lock, llp);

        LinearLayout btns = buttonRow();
        TextView cancel = Ui.button(this, "إلغاء", false);
        cancel.setOnClickListener(v -> popOverlay());
        TextView save = Ui.button(this, existing == null ? "إنشاء الحساب" : "حفظ", true);
        saveRef[0] = save;
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { toast("اكتب اسم الحساب"); name.requestFocus(); return; }
            p.name = n;
            if (newPin[0] != null) p.pin = Profiles.hash(p.id, newPin[0]);
            Profiles.put(this, p);
            if (hadPhoto && !usePhoto[0]) Profiles.clearAvatar(this, p.id);
            while (!overlays.isEmpty()) popOverlay();
            focusId = p.id;
            fill(false);
            toast(existing == null ? "تم إنشاء حساب " + n : "تم الحفظ");
        });
        btns.addView(cancel, buttonLp(dp(150)));
        btns.addView(save, buttonLp(dp(190)));
        card.addView(btns);

        pushOverlay(card, existing == null ? name : save);
    }

    private static void refreshChips(List<View> chips, Profiles.Profile p, boolean usePhoto) {
        for (View c : chips) {
            Object[] t = (Object[]) c.getTag();
            ((View) t[1]).setAlpha(!usePhoto && t[0].equals(p.emoji) ? 1f : 0f);
        }
    }

    // ---------------------------------------------------------------- the code

    interface PinCheck { String check(String pin); }   // null = accepted, else the message to show
    interface PinDone { void done(String pin); }

    private void verify(Profiles.Profile p, Runnable ok) {
        showPad(p, "أدخل الرمز", "حساب " + p.name + " مقفل", pin -> {
            if (!Profiles.checkPin(p, pin)) return "رمز غير صحيح، جرّب مرة ثانية";
            popOverlay();
            ok.run();
            return null;
        });
    }

    private void setPin(PinDone done) {
        showPad(null, "اختر رمزاً من ٤ أرقام", "بيُطلب كل ما فتحت هذا الحساب", first -> {
            popOverlay();
            showPad(null, "أعد كتابة الرمز", "للتأكيد", second -> {
                if (!second.equals(first)) return "الرمزان مختلفان، اكتبه مرة ثانية";
                popOverlay();
                done.done(second);
                return null;
            });
            return null;
        });
    }

    private void showPad(Profiles.Profile p, String title, String sub, PinCheck check) {
        PinPad pp = new PinPad(p, title, sub, check);
        pushOverlay(pp.card, pp.keys.get(4));   // start on «٥», the middle of the pad
    }

    /** Four dots and a 3×4 pad of round keys; the remote's digit keys work too. */
    private final class PinPad {
        final LinearLayout card;
        final List<TextView> keys = new ArrayList<>();
        final View[] dots = new View[4];
        final TextView sub;
        final String subText;
        final PinCheck check;
        final StringBuilder typed = new StringBuilder();
        boolean busy;

        PinPad(Profiles.Profile p, String title, String subText, PinCheck check) {
            this.check = check;
            this.subText = subText;
            card = dialog(dp(360));
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setTag(this);
            if (p != null) {
                ImageView av = new ImageView(ProfilesActivity.this);
                av.setImageBitmap(Profiles.avatar(ProfilesActivity.this, p, dp(52)));
                card.addView(av, new LinearLayout.LayoutParams(dp(52), dp(52)));
            } else {
                TextView ic = Ui.text(ProfilesActivity.this, 30, "regular", Color.WHITE);
                ic.setText("🔒");
                card.addView(ic);
            }
            TextView t = Ui.text(ProfilesActivity.this, 19, "display", Ui.CREAM);
            t.setText(title);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.topMargin = dp(6);
            card.addView(t, tl);
            sub = Ui.text(ProfilesActivity.this, 13, "regular", Ui.DIM);
            sub.setText(subText);
            sub.setGravity(Gravity.CENTER);
            card.addView(sub, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout dotRow = new LinearLayout(ProfilesActivity.this);
            dotRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            for (int i = 0; i < 4; i++) {
                View d = new View(ProfilesActivity.this);
                dots[i] = d;
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(16), dp(16));
                dl.setMargins(dp(9), 0, dp(9), 0);
                dotRow.addView(d, dl);
            }
            LinearLayout.LayoutParams drl = new LinearLayout.LayoutParams(-2, -2);
            drl.topMargin = dp(12);
            drl.bottomMargin = dp(10);
            card.addView(dotRow, drl);
            paintDots(0xFFFFFFFF);

            String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕"};
            LinearLayout grid = new LinearLayout(ProfilesActivity.this);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setGravity(Gravity.CENTER_HORIZONTAL);
            grid.setClipChildren(false);
            for (int r = 0; r < 4; r++) {
                LinearLayout line = new LinearLayout(ProfilesActivity.this);
                line.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                line.setClipChildren(false);
                for (int k = 0; k < 3; k++) {
                    String l = labels[r * 3 + k];
                    TextView key = Ui.text(ProfilesActivity.this, l.matches("\\d") ? 20 : 16, "medium", Color.WHITE);
                    key.setText(l.matches("\\d") ? Ui.digits(l) : l);
                    key.setGravity(Gravity.CENTER);
                    key.setFocusable(true);
                    key.setClickable(true);
                    GradientDrawable kb = new GradientDrawable();
                    kb.setShape(GradientDrawable.OVAL);
                    kb.setColor(0x1AFFFFFF);
                    key.setBackground(kb);
                    key.setOnFocusChangeListener((v, has) -> {
                        kb.setColor(has ? 0xF2FFFFFF : 0x1AFFFFFF);
                        key.setTextColor(has ? 0xFF111111 : Color.WHITE);
                        v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150).start();
                    });
                    key.setOnClickListener(v -> {
                        if (l.equals("⌫")) erase();
                        else if (l.equals("✕")) popOverlay();
                        else press(l.charAt(0));
                    });
                    LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(dp(50), dp(50));
                    kl.setMargins(dp(10), dp(4), dp(10), dp(4));
                    line.addView(key, kl);
                    keys.add(key);
                }
                grid.addView(line);
            }
            card.addView(grid);
        }

        void paintDots(int fill) {
            for (int i = 0; i < 4; i++) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                if (i < typed.length()) g.setColor(fill);
                else g.setStroke(dp(2), 0x80FFFFFF);
                dots[i].setBackground(g);
            }
        }

        void press(char d) {
            if (busy || typed.length() >= 4) return;
            typed.append(d);
            paintDots(Ui.GOLD);
            dots[typed.length() - 1].setScaleX(1.4f);
            dots[typed.length() - 1].setScaleY(1.4f);
            dots[typed.length() - 1].animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            if (typed.length() == 4) {
                busy = true;
                card.postDelayed(() -> {
                    String err = check.check(typed.toString());
                    if (err != null) fail(err);
                }, 180);
            }
        }

        void erase() {
            if (busy || typed.length() == 0) return;
            typed.setLength(typed.length() - 1);
            paintDots(Ui.GOLD);
        }

        void fail(String msg) {
            paintDots(0xFFE5484D);
            sub.setText(msg);
            sub.setTextColor(0xFFFF8A8A);
            card.animate().translationX(dp(14)).setDuration(50).withEndAction(() ->
                card.animate().translationX(-dp(12)).setDuration(70).withEndAction(() ->
                    card.animate().translationX(dp(7)).setDuration(60).withEndAction(() ->
                        card.animate().translationX(0).setDuration(50).start()).start()).start()).start();
            card.postDelayed(() -> {
                typed.setLength(0);
                busy = false;
                paintDots(Ui.GOLD);
            }, 650);
        }
    }

    // ---------------------------------------------------------------- dialogs

    private LinearLayout dialog(int width) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(30), dp(22), dp(30), dp(22));
        card.setBackground(Ui.card(this));
        card.setElevation(dp(24));
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setLayoutParams(new FrameLayout.LayoutParams(width, -2, Gravity.CENTER));
        return card;
    }

    private View avatarHeader(Profiles.Profile p, String title, String sub) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        ImageView av = new ImageView(this);
        av.setImageBitmap(Profiles.avatar(this, p, dp(64)));
        head.addView(av, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = Ui.text(this, 20, "display", Ui.CREAM);
        a.setText(title);
        t.addView(a);
        TextView b = Ui.text(this, 13, "regular", Ui.DIM);
        b.setText(sub);
        t.addView(b);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.rightMargin = dp(16);
        t.setGravity(Gravity.RIGHT);
        head.addView(t, tp);
        Ui.rtl(head);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(16);
        head.setLayoutParams(hp);
        return head;
    }

    private TextView menuButton(String label, Runnable action) {
        TextView b = Ui.button(this, label, false);
        b.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout buttonRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        r.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout.LayoutParams buttonLp(int width) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(42));
        lp.setMargins(dp(8), dp(4), dp(8), dp(4));
        return lp;
    }

    /** Shows card above the others; the picker and older dialogs stop taking focus. */
    private void pushOverlay(View card, View focus) {
        if (!overlays.isEmpty()) {
            View prev = overlays.get(overlays.size() - 1);
            View f = getCurrentFocus();
            if (!(prev.getTag() instanceof PinPad) && f != null && isInside(f, prev)) prev.setTag(f);
            overlay.removeView(prev);
        }
        overlays.add(card);
        pad = card.getTag() instanceof PinPad ? (PinPad) card.getTag() : null;
        if (card.getParent() != null) ((ViewGroup) card.getParent()).removeView(card);
        overlay.addView(card);
        overlay.setVisibility(View.VISIBLE);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(2f)).setDuration(220).start();
        if (focus != null) focus.post(focus::requestFocus);
    }

    private void popOverlay() {
        if (overlays.isEmpty()) return;
        overlay.removeView(overlays.remove(overlays.size() - 1));
        pad = null;
        if (!overlays.isEmpty()) {
            View prev = overlays.get(overlays.size() - 1);
            overlay.addView(prev);
            if (prev.getTag() instanceof PinPad) pad = (PinPad) prev.getTag();
            View f = prev.getTag() instanceof View ? (View) prev.getTag() : null;
            View target = f != null && isInside(f, prev) ? f : firstFocusable(prev);
            if (target != null) target.post(target::requestFocus);
            return;
        }
        overlay.setVisibility(View.GONE);
        picker.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v.isFocused()) return;
        }
        fill(false);
    }

    private static boolean isInside(View v, View root) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) if (x == root) return true;
        return false;
    }

    private static View firstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = firstFocusable(((ViewGroup) v).getChildAt(i));
            if (f != null) return f;
        }
        return null;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
