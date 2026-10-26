package sa.urtv.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «صوري», in the home screen's Luma look: two tabs in a glass capsule.
 *  · «صوري»: the account's photos as a grid; OK on one sets it as the home wallpaper or deletes it. «تلقائي»
 *    goes back to the rotation, and the bundled AZ TV photos can be picked too.
 *  · «رفع من الجوال»: a QR code and a 4-digit code; the phone opens a page on the home Wi-Fi (PhotoServer) and
 *    sends photos for this account, or its avatar. They show up in «صوري» as they arrive.
 */
public class PhotosActivity extends Activity implements PhotoServer.Listener {
    static final String EXTRA_PROFILE = "profile";

    private Profiles.Profile profile;
    private PhotoServer server;
    private String code, url;
    private ImageView qr, avatar;
    private TextView address, codeView, count;
    private TabBar tabs;
    private FrameLayout body;
    private ScrollView galleryPage;
    private LinearLayout gallery;
    private View uploadPage;
    private final List<View> firstRow = new ArrayList<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private boolean rebuilding;   // the grid is being replaced: focus passing through a tab is not a choice

    private static final int COLS = 4, GAP = 20, RADIUS = 16;   // the screen is 960×540 dp
    private static final int QR = 188;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent().getStringExtra(EXTRA_PROFILE);
        profile = id != null ? Profiles.find(this, id) : null;
        if (profile == null) profile = Profiles.active(this);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(build());
        refresh();
        show(0);
        if (!firstRow.isEmpty()) firstRow.get(0).requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        code = newCode();
        server = new PhotoServer(this, profile.id, code, this);
        int port = server.start();
        String ip = wifiIp();
        url = port < 0 || ip == null ? null : "http://" + ip + (port == 80 ? "" : ":" + port);
        showAccess();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (server != null) server.stop();   // the page lives only while this screen is open
        server = null;
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- PhotoServer.Listener (any thread)

    @Override public void changed() {
        runOnUiThread(() -> {
            refresh();
            toast("وصلت ✓ تلقاها في «صوري»");
        });
    }

    @Override public void tooManyTries() {
        runOnUiThread(() -> {
            code = newCode();
            if (server != null) server.setCode(code);
            showAccess();
            toast("محاولات خاطئة كثيرة: تغيّر الرمز");
        });
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap back = ownBackdrop();
        if (back == null) back = Ui.backdrop(this);
        if (back != null) bg.setImageBitmap(back);
        if (Build.VERSION.SDK_INT >= 31) bg.setRenderEffect(RenderEffect.createBlurEffect(dp(28), dp(28), Shader.TileMode.CLAMP));
        bg.setAlpha(0.75f);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0xCC000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // header: who on the right, the tabs in the middle, the count on the left
        FrameLayout head = new FrameLayout(this);
        head.setClipChildren(false);
        root.addView(head, lp(-1, dp(TabBar.HEIGHT), Gravity.TOP, dp(48), dp(20), dp(48)));

        LinearLayout who = new LinearLayout(this);
        who.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new ImageView(this);
        who.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setGravity(Gravity.RIGHT);
        TextView title = Ui.text(this, 20, "display", Color.WHITE);
        title.setIncludeFontPadding(false);
        title.setText("صوري");
        TextView sub = Ui.text(this, 11, "regular", Ui.DIM);
        sub.setText("حساب " + profile.name);
        names.addView(title);
        names.addView(sub);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.rightMargin = dp(12);
        who.addView(names, np);
        Ui.rtl(who);
        head.addView(who, new FrameLayout.LayoutParams(-2, -1, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        tabs = new TabBar(this, false, "صوري", "رفع من الجوال");
        tabs.setOnTabFocus((v, has) -> { if (has && !rebuilding) show(tabs.tabs.indexOf(v)); });
        for (TextView t : tabs.tabs) t.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN || k != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (tabs.current() == 0 && !firstRow.isEmpty()) { firstRow.get(0).requestFocus(); return true; }
            return true;   // the upload page has nothing to press
        });
        head.addView(tabs, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));

        count = Ui.text(this, 13, "medium", Ui.DIM);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable chip = Ui.rounded(0x1FFFFFFF, TabBar.HEIGHT / 2f, this);
        chip.setStroke(Math.max(1, dp(0.8f)), 0x1FFFFFFF);
        count.setBackground(chip);
        head.addView(count, new FrameLayout.LayoutParams(-2, -1, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        body = new FrameLayout(this);
        body.setClipChildren(false);
        root.addView(body, lp(-1, -1, Gravity.TOP, 0, dp(84), 0));

        galleryPage = new ScrollView(this);
        galleryPage.setVerticalScrollBarEnabled(false);
        galleryPage.setClipToPadding(false);
        galleryPage.setPadding(dp(48), dp(8), dp(48), dp(40));
        gallery = new LinearLayout(this);
        gallery.setOrientation(LinearLayout.VERTICAL);
        gallery.setClipChildren(false);
        galleryPage.addView(gallery, new FrameLayout.LayoutParams(-1, -2));
        body.addView(galleryPage, new FrameLayout.LayoutParams(-1, -1));

        uploadPage = buildUpload();
        body.addView(uploadPage, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    /** One clear-glass card: the steps on the right, the QR on the left. */
    private View buildUpload() {
        FrameLayout page = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(32), dp(26), dp(32), dp(26));
        card.setBackground(glassCard(28));
        page.addView(card, new FrameLayout.LayoutParams(dp(780), -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        ((FrameLayout.LayoutParams) card.getLayoutParams()).topMargin = dp(24);

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(this, 20, "display", Color.WHITE);
        head.setText("ارفع صورك من الجوال");
        head.setGravity(Gravity.RIGHT);
        steps.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = Ui.text(this, 12, "regular", Ui.DIM);
        sub.setText("تصير خلفية لحسابك وتظهر في شاشة التوقف");
        sub.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.bottomMargin = dp(12);
        steps.addView(sub, sp);
        steps.addView(step("١", "وصّل جوالك على نفس شبكة الواي فاي"));
        steps.addView(step("٢", "امسح الرمز بالكاميرا، أو افتح الرابط:"));
        address = Ui.text(this, 17, "medium", Ui.GOLD);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setGravity(Gravity.RIGHT);
        steps.addView(address, indented());
        steps.addView(step("٣", "اكتب رمز الدخول:"));
        codeView = Ui.text(this, 32, "display", Color.WHITE);
        codeView.setIncludeFontPadding(false);
        codeView.setLetterSpacing(0.25f);
        codeView.setGravity(Gravity.RIGHT);
        steps.addView(codeView, indented());
        steps.addView(step("٤", "اختر الصور وارسلها، أو غيّر صورتك الرمزية"));
        card.addView(steps, new LinearLayout.LayoutParams(0, -2, 1f));

        // the QR: crisp square modules on white, with the quiet zone a phone camera needs
        FrameLayout qrCard = new FrameLayout(this);
        qrCard.setBackground(Ui.rounded(Color.WHITE, 20, this));
        qrCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_XY);
        qrCard.addView(qr, new FrameLayout.LayoutParams(dp(QR), dp(QR)));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-2, -2);
        qp.rightMargin = dp(36);
        card.addView(qrCard, qp);
        Ui.rtl(card);   // steps on the right, QR on the left
        return page;
    }

    private LinearLayout.LayoutParams indented() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, dp(40), dp(2));
        return p;
    }

    private View step(String n, String s) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView num = Ui.text(this, 13, "medium", Color.WHITE);
        num.setText(n);
        num.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x26FFFFFF);
        g.setStroke(Math.max(1, dp(0.8f)), 0x40FFFFFF);
        num.setBackground(g);
        l.addView(num, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = Ui.text(this, 15, "regular", Color.WHITE);
        t.setText(s);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.rightMargin = dp(14);
        l.addView(t, tp);
        Ui.rtl(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        l.setLayoutParams(lp);
        return l;
    }

    /** Luma's card on a photo: a faint veil and a hairline edge. */
    private GradientDrawable glassCard(float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x29FFFFFF, 0x14FFFFFF});
        g.setCornerRadius(dp(radius));
        g.setStroke(Math.max(1, dp(0.8f)), 0x33FFFFFF);
        return g;
    }

    private void show(int page) {
        if (tabs.current() == page && galleryPage.getVisibility() != uploadPage.getVisibility()) return;
        tabs.setCurrent(page);
        View in = page == 0 ? galleryPage : uploadPage, out = page == 0 ? uploadPage : galleryPage;
        out.setVisibility(View.GONE);
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationY(dp(14));
        in.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    // ---------------------------------------------------------------- the gallery

    private void showAccess() {
        if (url == null) {
            address.setText("الواي فاي غير متصل");
            codeView.setText("----");
            qr.setImageBitmap(null);
            return;
        }
        address.setText(url);
        codeView.setText(Ui.digits(code));
        Bitmap q = qrBitmap(url + "/#k=" + code);
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), q);
        d.setFilterBitmap(false);   // keep module edges sharp when scaled up
        qr.setImageDrawable(d);
    }

    /** Rebuilds the grid: «تلقائي» and the account's photos, newest first, then the bundled ones. */
    private void refresh() {
        profile = Profiles.find(this, profile.id);
        if (profile == null) { finish(); return; }
        avatar.setImageBitmap(Profiles.avatar(this, profile, dp(80)));
        List<File> photos = Profiles.photos(this, profile.id);
        count.setText(Ui.digits(photos.size() + " من " + Profiles.MAX_PHOTOS + " صورة"));
        String pick = Profiles.wallpaper(this, profile.id);
        View focused = getCurrentFocus();
        Object keep = focused == null ? null : focused.getTag();
        int gen = ++generation;

        rebuilding = true;
        gallery.removeAllViews();
        firstRow.clear();
        List<View> mine = new ArrayList<>();
        mine.add(autoTile(pick.isEmpty()));
        for (File f : photos) mine.add(photoTile(f.getName(), false, f.getName().equals(pick), gen, () -> load(f)));
        gallery.addView(label("صورك", photos.isEmpty() ? "ما رفعت صور بعد" : "OK على صورة: خلفية أو حذف"));
        grid(mine, true);
        if (photos.isEmpty()) gallery.addView(emptyCard());

        String[] bundled = new String[0];
        try { bundled = getAssets().list("wallpapers"); } catch (java.io.IOException ignored) { }
        if (bundled != null && bundled.length > 0) {
            List<View> ours = new ArrayList<>();
            for (String n : bundled) ours.add(photoTile("a:" + n, true, ("a:" + n).equals(pick), gen, () -> loadAsset(n)));
            gallery.addView(label("صور AZ TV", "تقدر تختار وحدة منها بعد"));
            grid(ours, false);
        }
        View again = keep == null ? null : gallery.findViewWithTag(keep);
        if (again != null) again.requestFocus();
        else if (galleryPage.getVisibility() == View.VISIBLE && !firstRow.isEmpty()) firstRow.get(0).requestFocus();
        rebuilding = false;
    }

    private TextView label(String title, String hint) {
        TextView t = Ui.text(this, 15, "medium", Color.WHITE);
        t.setText(title + "   ·   " + hint);
        t.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(10), 0, dp(12));
        t.setLayoutParams(p);
        return t;
    }

    /** Rows of COLS tiles, the first on the right; tiles keep their width in a short last row. */
    private void grid(List<View> tiles, boolean top) {
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = dp(GAP);
                gallery.addView(row, rp);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % COLS == 0 ? 0 : dp(GAP / 2f), 0, i % COLS == COLS - 1 ? 0 : dp(GAP / 2f), 0);
            row.addView(tiles.get(i), p);
            if (top && i < COLS) {
                firstRow.add(tiles.get(i));
                tiles.get(i).setNextFocusUpId(tabs.tab(0).getId());
            }
            if (i % COLS == COLS - 1 || i == tiles.size() - 1) {
                for (int k = i % COLS + 1; k < COLS; k++) {
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
                    sp.setMargins(dp(GAP / 2f), 0, k == COLS - 1 ? 0 : dp(GAP / 2f), 0);
                    row.addView(new View(this), sp);
                }
                Ui.rtl(row);
            }
        }
    }

    /** The account's chosen photo, or its newest, small: the page sits on what the home screen shows. */
    private Bitmap ownBackdrop() {
        String pick = Profiles.wallpaper(this, profile.id);
        List<File> photos = Profiles.photos(this, profile.id);
        File f = null;
        if (!pick.isEmpty() && !pick.startsWith("a:")) f = new File(Profiles.photosDir(this, profile.id), pick);
        if ((f == null || !f.isFile()) && !photos.isEmpty()) f = photos.get(0);
        if (f == null || !f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 8;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private View emptyCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(24), dp(18), dp(24), dp(18));
        c.setBackground(glassCard(20));
        TextView t = Ui.text(this, 15, "regular", Color.WHITE);
        t.setText("ارفع صورك من جوالك وتظهر هنا، وتختار منها خلفية التلفزيون");
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        TextView go = Ui.button(this, "رفع من الجوال", true);
        go.setOnClickListener(v -> tabs.tab(1).requestFocus());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, dp(42));
        gp.topMargin = dp(12);
        c.addView(go, gp);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(GAP);
        c.setLayoutParams(p);
        return c;
    }

    /** A 16:9 tile with Luma's edges: a hairline at rest, a white rim and a lift on focus. */
    private FrameLayout tile(Object tag) {
        FrameLayout card = new MainActivity.AspectFrame(this);
        card.setTag(tag);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(RADIUS)); }
        });
        card.setBackgroundColor(0x1FFFFFFF);
        GradientDrawable hair = Ui.rounded(0x00000000, RADIUS, this);
        hair.setStroke(Math.max(1, dp(0.6f)), 0x1FFFFFFF);
        GradientDrawable rim = Ui.rounded(0x00000000, RADIUS, this);
        rim.setStroke(dp(1.5f), 0xF0FFFFFF);
        card.setForeground(hair);
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationY(has ? -dp(3) : 0)
                .translationZ(has ? dp(14) : 0).setInterpolator(has ? MainActivity.SPRING : new DecelerateInterpolator())
                .setDuration(has ? 460 : 300).start();
            card.setForeground(has ? rim : hair);
        });
        return card;
    }

    /** «الخلفية» chip in the tile's corner. */
    private void badge(FrameLayout card, String s) {
        TextView b = Ui.text(this, 11, "medium", 0xFF1D1D1F);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(Ui.rounded(0xF2FFFFFF, 11, this));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, dp(22), Gravity.TOP | Gravity.RIGHT);
        p.setMargins(0, dp(8), dp(8), 0);
        card.addView(b, p);
    }

    private View autoTile(boolean on) {
        FrameLayout card = tile("auto");
        card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0x33FFFFFF, 0x0DFFFFFF}));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        TextView ic = Ui.text(this, 26, "regular", Color.WHITE);
        ic.setText("⟳");
        ic.setGravity(Gravity.CENTER);
        TextView t = Ui.text(this, 14, "medium", Color.WHITE);
        t.setText("تلقائي");
        t.setGravity(Gravity.CENTER);
        TextView s = Ui.text(this, 10, "regular", Ui.DIM);
        s.setText("تتبدل الخلفية كل ١٠ دقائق");
        s.setGravity(Gravity.CENTER);
        c.addView(ic);
        c.addView(t);
        c.addView(s);
        card.addView(c, new FrameLayout.LayoutParams(-1, -1));
        if (on) badge(card, "الخلفية ✓");
        card.setOnClickListener(v -> {
            Profiles.setWallpaper(this, profile.id, "");
            toast("رجعت الخلفية تتبدل تلقائياً");
            refresh();
        });
        return card;
    }

    private interface Decode { Bitmap get(); }

    private View photoTile(String pick, boolean bundled, boolean on, int gen, Decode decode) {
        FrameLayout card = tile(pick);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(iv, 0, new FrameLayout.LayoutParams(-1, -1));
        loader.execute(() -> {
            if (gen != generation) return;
            Bitmap bm = decode.get();
            runOnUiThread(() -> {
                if (bm == null) return;
                iv.setImageBitmap(bm);
                iv.setAlpha(0f);
                iv.animate().alpha(1f).setDuration(240).start();
            });
        });
        if (on) badge(card, "الخلفية ✓");
        card.setOnClickListener(v -> menu(card, pick, bundled, on));
        return card;
    }

    private Bitmap load(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private Bitmap loadAsset(String name) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (java.io.InputStream in = getAssets().open("wallpapers/" + name)) { return BitmapFactory.decodeStream(in, null, o); }
        catch (java.io.IOException e) { return null; }
    }

    // ---------------------------------------------------------------- OK on a photo

    private void menu(View anchor, String pick, boolean bundled, boolean on) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = Ui.rounded(0xF22A2A2E, 18, this);
        bg.setStroke(Math.max(1, dp(0.8f)), 0x33FFFFFF);
        list.setBackground(bg);
        PopupWindow pw = new PopupWindow(list, dp(220), -2, true);
        List<TextView> rows = new ArrayList<>();
        rows.add(row(on ? "هي الخلفية الحين ✓" : "اجعلها خلفية التلفزيون", () -> {
            pw.dismiss();
            if (on) return;
            Profiles.setWallpaper(this, profile.id, pick);
            toast("صارت خلفية التلفزيون ✓");
            refresh();
        }));
        if (!bundled) {
            TextView del = row("حذف الصورة", null);
            del.setTag((Runnable) () -> {
                if (!"sure".equals(del.getContentDescription())) {   // a second OK deletes
                    del.setContentDescription("sure");
                    del.setText("متأكد؟ OK للحذف");
                    return;
                }
                pw.dismiss();
                Profiles.deletePhoto(this, profile.id, pick);
                refresh();
                if (!firstRow.isEmpty()) firstRow.get(0).requestFocus();
            });
            rows.add(del);
        }
        rows.add(row("إلغاء", pw::dismiss));
        for (TextView r : rows) {
            r.setOnClickListener(v -> ((Runnable) v.getTag()).run());
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(38)));
        }
        for (int k = 0; k < rows.size(); k++) {
            TextView r = rows.get(k);
            r.setNextFocusUpId(rows.get(Math.max(0, k - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, k + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { if (anchor.isAttachedToWindow()) anchor.requestFocus(); });
        pw.showAsDropDown(anchor, (anchor.getWidth() - dp(220)) / 2, dp(6), Gravity.START);
        rows.get(0).requestFocus();
    }

    private TextView row(String label, Runnable action) {
        TextView r = Ui.text(this, 14, "regular", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = Ui.rounded(Color.TRANSPARENT, 12, this);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF5FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF1D1D1F : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- helpers

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, 0);
        return p;
    }

    private static String newCode() {
        return String.valueOf(1000 + new SecureRandom().nextInt(9000));
    }

    private String wifiIp() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress()) return a.getAddress().getHostAddress();
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** One pixel per module (plus a 2-module white border); the view scales it up without smoothing. */
    private static Bitmap qrBitmap(String text) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF111111 : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
