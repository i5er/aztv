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
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «صور الحساب»: a QR code and a 4-digit code; the phone opens a page on the home Wi-Fi (PhotoServer) and sends
 * photos for this account's home screen and screensaver, or its avatar. Photos arrive here live; OK on one
 * deletes it.
 */
public class PhotosActivity extends Activity implements PhotoServer.Listener {
    static final String EXTRA_PROFILE = "profile";

    private Profiles.Profile profile;
    private PhotoServer server;
    private String code, url;
    private ImageView qr, avatar;
    private TextView address, codeView, count, empty;
    private LinearLayout strip;

    private static final int QR = 196, STRIP = 104;   // the screen is 960×540 dp: everything must fit at once

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
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (server != null) server.stop();   // the page lives only while this screen is open
        server = null;
    }

    // ---------------------------------------------------------------- PhotoServer.Listener (any thread)

    @Override public void changed() {
        runOnUiThread(() -> {
            refresh();
            toast("وصلت ✓");
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
        Bitmap back = Ui.backdrop(this);
        if (back != null) bg.setImageBitmap(back);
        if (Build.VERSION.SDK_INT >= 31) bg.setRenderEffect(RenderEffect.createBlurEffect(dp(28), dp(28), Shader.TileMode.CLAMP));
        bg.setAlpha(0.45f);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xCC000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(24), dp(48), dp(16));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // top: account and title, right-aligned
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        avatar = new ImageView(this);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, 24, "display", Ui.CREAM);
        title.setText("صور " + profile.name);
        ht.addView(title);
        TextView sub = Ui.text(this, 12, "regular", Ui.DIM);
        sub.setText("تظهر في الشاشة الرئيسية وشاشة التوقف لما يكون هذا الحساب هو اللي يشاهد");
        ht.addView(sub);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(-2, -2);
        htp.rightMargin = dp(16);
        ht.setGravity(Gravity.RIGHT);
        head.addView(ht, htp);
        Ui.rtl(head);
        col.addView(head);

        // middle: steps on the right, the QR card on the left
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, 0, 1f);
        mp.topMargin = dp(10);
        col.addView(mid, mp);

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.addView(step("١", "وصّل جوالك على نفس شبكة الواي فاي"));
        steps.addView(step("٢", "امسح الرمز بكاميرا الجوال، أو افتح الرابط:"));
        address = Ui.text(this, 18, "medium", Ui.GOLD);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.setMargins(0, 0, dp(44), dp(2));
        steps.addView(address, ap);
        steps.addView(step("٣", "اكتب رمز الدخول:"));
        codeView = Ui.text(this, 34, "display", Color.WHITE);
        codeView.setIncludeFontPadding(false);
        codeView.setLetterSpacing(0.25f);
        codeView.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, dp(44), dp(2));
        steps.addView(codeView, cp);
        steps.addView(step("٤", "اختر الصور وارسلها، أو غيّر صورتك الرمزية"));
        mid.addView(steps, new LinearLayout.LayoutParams(0, -2, 1f));

        // the QR: crisp square modules on white, with a quiet zone the phone camera needs
        FrameLayout qrCard = new FrameLayout(this);
        qrCard.setBackground(Ui.rounded(Color.WHITE, 18, this));
        qrCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        qrCard.setElevation(dp(16));
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_XY);
        qrCard.addView(qr, new FrameLayout.LayoutParams(dp(QR), dp(QR)));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-2, -2);
        qp.leftMargin = dp(8);
        qp.rightMargin = dp(40);
        mid.addView(qrCard, qp);
        Ui.rtl(mid);   // steps on the right, QR on the left

        // bottom: the photos so far
        count = Ui.text(this, 12, "medium", Ui.GOLD);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(8);
        clp.bottomMargin = dp(2);
        count.setGravity(Gravity.RIGHT);
        col.addView(count, clp);

        FrameLayout bottom = new FrameLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        strip = new LinearLayout(this);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(dp(8), dp(10), dp(8), dp(10));
        hs.addView(strip);
        bottom.addView(hs, new FrameLayout.LayoutParams(-1, dp(STRIP)));
        empty = Ui.text(this, 15, "regular", 0x99FFFFFF);
        empty.setText("ما فيه صور لهذا الحساب بعد: تظهر صور AZ TV المعتادة إلى أن ترسل صورك");
        empty.setGravity(Gravity.CENTER);
        bottom.addView(empty, new FrameLayout.LayoutParams(-1, dp(STRIP)));
        col.addView(bottom, new LinearLayout.LayoutParams(-1, dp(STRIP)));
        return root;
    }

    private View step(String n, String s) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView num = Ui.text(this, 13, "medium", 0xFF1A1206);
        num.setText(n);
        num.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.GOLD);
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

    /** Thumbnails of the account's photos, newest first; OK on one offers to delete it. */
    private void refresh() {
        profile = Profiles.find(this, profile.id);
        if (profile == null) { finish(); return; }
        avatar.setImageBitmap(Profiles.avatar(this, profile, dp(60)));
        List<File> photos = Profiles.photos(this, profile.id);
        count.setText("الصور: " + Ui.digits(String.valueOf(photos.size())) + " من " + Ui.digits(String.valueOf(Profiles.MAX_PHOTOS)));
        empty.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
        strip.removeAllViews();
        for (File f : photos) strip.addView(thumb(f));
    }

    private View thumb(File f) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
        });
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 8;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        iv.setImageBitmap(BitmapFactory.decodeFile(f.getPath(), o));
        card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        View ring = new View(this);
        GradientDrawable rg = Ui.rounded(0x00000000, 12, this);
        rg.setStroke(dp(3), Color.WHITE);
        ring.setBackground(rg);
        ring.setAlpha(0f);
        card.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        TextView del = Ui.text(this, 12, "medium", Color.WHITE);
        del.setText("OK للحذف");
        del.setTextDirection(View.TEXT_DIRECTION_RTL);
        del.setGravity(Gravity.CENTER);
        del.setBackgroundColor(0xB3000000);
        del.setAlpha(0f);
        card.addView(del, new FrameLayout.LayoutParams(-1, dp(26), Gravity.BOTTOM));
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(10) : 0)
                .setInterpolator(new OvershootInterpolator(1.6f)).setDuration(220).start();
            ring.setAlpha(has ? 1f : 0f);
            del.setAlpha(has ? 1f : 0f);
        });
        card.setOnClickListener(v -> {
            if ("confirm".equals(card.getTag())) {
                Profiles.deletePhoto(this, profile.id, f.getName());
                refresh();
                if (strip.getChildCount() > 0) strip.getChildAt(0).requestFocus();
            } else {
                card.setTag("confirm");
                del.setText("OK مرة ثانية للتأكيد");
                del.setBackgroundColor(0xE6C0392B);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(84));
        lp.setMargins(dp(8), 0, dp(8), 0);
        card.setLayoutParams(lp);
        return card;
    }

    // ---------------------------------------------------------------- helpers

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
