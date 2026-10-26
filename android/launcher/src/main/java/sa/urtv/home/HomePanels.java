package sa.urtv.home;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The home screen's glass cards, after github.com/TurkiHassan/custom-tv-arabic: the next match (top left),
 * prayer times with a countdown (top right), weather with the next hours (left), and a big greeting with
 * the date, the sky and sunset (right). City, coordinates and teams are the active account's screensaver
 * settings; prayers are computed here (Umm al-Qura), weather is Open-Meteo, matches TheSportsDB.
 */
final class HomePanels {
    private static final Uri CONFIG = Uri.parse("content://sa.urtv.screensaver.config");
    private static final TimeZone RIYADH = TimeZone.getTimeZone("Asia/Riyadh");
    private static final long REFRESH_MS = 20 * 60 * 1000L;

    private final Activity a;
    private String city = "الرياض", teamId = "";
    private double lat = 24.6877, lon = 46.7219;
    private long loadedAt;
    private String loadedFor = "";

    // prayers
    private TextView prTitle, prNext, prFoot;
    private final TextView[] prName = new TextView[5], prTime = new TextView[5];
    private final LinearLayout[] prCell = new LinearLayout[5];
    private int[] prayerMin = null;   // minutes after midnight: Fajr, Dhuhr, Asr, Maghrib, Isha, Sunrise

    // weather
    private TextView wxIcon, wxTemp, wxCond, wxRange, wxMore;
    private final TextView[] hrLabel = new TextView[6], hrIcon = new TextView[6], hrTemp = new TextView[6];
    private String sky = "";
    private String sunset = "";

    // match
    private TextView mTitle, mWhen, mLeague, mHomeName, mAwayName, mFoot;
    private ImageView mHomeLogo, mAwayLogo;
    private final TextView[] mMore = new TextView[2];

    // greeting
    private TextView gDate, gHello, gSky, gSun;
    private String name = "";

    HomePanels(Activity a) { this.a = a; }

    private int dp(float v) { return Ui.dp(a, v); }

    // ---------------------------------------------------------------- views

    /** A Luma widget card: frosted photo, a faint dark tint, a thin light rim, round corners. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(22), dp(14), dp(22), dp(12));
        c.setBackground(new Glass(c, dp(24), 0x2E000000, 0x33FFFFFF, false));   // Luma's hairline edge
        return c;
    }

    private TextView t(float sp, String fam, int color) {
        TextView v = Ui.text(a, sp, fam, color);
        v.setIncludeFontPadding(false);
        v.setShadowLayer(dp(4), 0, dp(1), 0xA6000000);   // readable over clear glass
        v.setSingleLine(true);
        return v;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    View matchView, prayerView, weatherView, greetView;

    /** The account's «الإعدادات»: which cards show, clock and digits. */
    void applyChoices() {
        Ui.latinDigits = Profiles.flag(a, "latin", false);
        clock24 = Profiles.flag(a, "clock24", false);
        if (matchView != null) matchView.setVisibility(Profiles.flag(a, "show_match", true) ? View.VISIBLE : View.GONE);
        if (prayerView != null) prayerView.setVisibility(Profiles.flag(a, "show_prayers", true) ? View.VISIBLE : View.GONE);
        if (weatherView != null) weatherView.setVisibility(Profiles.flag(a, "show_weather", true) ? View.VISIBLE : View.GONE);
        if (greetView != null) greetView.setVisibility(Profiles.flag(a, "show_greeting", true) ? View.VISIBLE : View.GONE);
        loadedAt = 0;   // redraw times in the new style
    }

    View matchCard() {
        LinearLayout c = card();
        matchView = c;
        mTitle = t(11, "regular", Ui.DIM);
        mTitle.setGravity(Gravity.RIGHT);
        c.addView(mTitle, lp(-1, -2, 0));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout home = team(true), mid = new LinearLayout(a), away = team(false);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        mWhen = t(15, "medium", Color.WHITE);
        mWhen.setGravity(Gravity.CENTER);
        mLeague = t(11, "regular", Ui.DIM);
        mLeague.setGravity(Gravity.CENTER);
        mid.addView(mWhen);
        mid.addView(mLeague);
        row.addView(home, lp(dp(96), -2, 0));
        row.addView(mid, lp(0, -2, 1));
        row.addView(away, lp(dp(96), -2, 0));
        Ui.rtl(row);   // home team on the right
        LinearLayout.LayoutParams rp = lp(-1, -2, 0);
        rp.topMargin = dp(4);
        c.addView(row, rp);

        for (int i = 0; i < 2; i++) {
            mMore[i] = t(10, "regular", 0xCCFFFFFF);
            mMore[i].setGravity(Gravity.RIGHT);
            c.addView(mMore[i], lp(-1, -2, 0));
        }
        mFoot = t(9, "regular", 0x80FFFFFF);
        mFoot.setGravity(Gravity.LEFT);
        c.addView(mFoot, lp(-1, -2, 0));
        mTitle.setText("المباراة القادمة");
        mWhen.setText("…");
        return c;
    }

    private LinearLayout team(boolean home) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF2FFFFFF);
        logo.setBackground(bg);
        logo.setPadding(dp(5), dp(5), dp(5), dp(5));
        l.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView n = t(12, "medium", Color.WHITE);
        n.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(4);
        l.addView(n, np);
        if (home) { mHomeLogo = logo; mHomeName = n; } else { mAwayLogo = logo; mAwayName = n; }
        return l;
    }

    View prayerCard() {
        LinearLayout c = card();
        prayerView = c;
        prTitle = t(11, "regular", Ui.DIM);
        prTitle.setGravity(Gravity.RIGHT);
        c.addView(prTitle, lp(-1, -2, 0));
        prNext = t(20, "display", Color.WHITE);
        prNext.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams np = lp(-1, -2, 0);
        np.topMargin = dp(4);
        c.addView(prNext, np);

        LinearLayout row = new LinearLayout(a);
        String[] names = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
        for (int i = 0; i < 5; i++) {
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(7), dp(4), dp(8));
            prName[i] = t(11, "regular", Ui.DIM);
            prName[i].setText(names[i]);
            prName[i].setGravity(Gravity.CENTER);
            prTime[i] = t(18, "medium", Color.WHITE);
            prTime[i].setText("--:--");
            prTime[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            cell.addView(prName[i], tl);
            LinearLayout.LayoutParams tt = new LinearLayout.LayoutParams(-1, -2);
            tt.topMargin = dp(4);
            cell.addView(prTime[i], tt);
            prCell[i] = cell;
            LinearLayout.LayoutParams cl = lp(0, -2, 1);
            cl.setMargins(dp(3), 0, dp(3), 0);
            row.addView(cell, cl);
        }
        Ui.rtl(row);   // Fajr on the right
        LinearLayout.LayoutParams rp = lp(-1, -2, 0);
        rp.topMargin = dp(6);
        c.addView(row, rp);
        prFoot = t(9, "regular", 0x80FFFFFF);
        prFoot.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams fp = lp(-1, -2, 0);
        fp.topMargin = dp(4);
        c.addView(prFoot, fp);
        prFoot.setText("حساب أم القرى");
        return c;
    }

    View weatherCard() {
        LinearLayout c = card();
        weatherView = c;
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = t(30, "regular", 0xFFFFD45A);
        wxTemp = t(40, "display", Color.WHITE);
        LinearLayout desc = new LinearLayout(a);
        desc.setOrientation(LinearLayout.VERTICAL);
        desc.setGravity(Gravity.RIGHT);
        wxCond = t(16, "medium", Color.WHITE);
        wxRange = t(12, "regular", 0xE6FFFFFF);
        desc.addView(wxCond);
        desc.addView(wxRange);
        top.addView(wxIcon);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(10);
        top.addView(wxTemp, tp);
        top.addView(desc, lp(0, -2, 1));
        c.addView(top, lp(-1, -2, 0));
        wxMore = t(10, "regular", Ui.DIM);
        wxMore.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams mp = lp(-1, -2, 0);
        mp.topMargin = dp(4);
        c.addView(wxMore, mp);

        View line = new View(a);
        line.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams lp1 = lp(-1, dp(1), 0);
        lp1.setMargins(0, dp(8), 0, dp(6));
        c.addView(line, lp1);

        LinearLayout hours = new LinearLayout(a);
        for (int i = 0; i < 6; i++) {
            LinearLayout h = new LinearLayout(a);
            h.setOrientation(LinearLayout.VERTICAL);
            h.setGravity(Gravity.CENTER);
            hrLabel[i] = t(10, "regular", Ui.DIM);
            hrIcon[i] = t(15, "regular", Color.WHITE);
            hrTemp[i] = t(13, "medium", Color.WHITE);
            for (TextView v : new TextView[] {hrLabel[i], hrIcon[i], hrTemp[i]}) {
                v.setGravity(Gravity.CENTER);
                h.addView(v, new LinearLayout.LayoutParams(-1, -2));
            }
            hours.addView(h, lp(0, -2, 1));
        }
        c.addView(hours, lp(-1, -2, 0));
        wxCond.setText("الطقس");
        return c;
    }

    View greeting() {
        LinearLayout c = new LinearLayout(a);
        greetView = c;
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.RIGHT);
        // Luma's hero: the big hello first, then the day and the sky in a quieter line
        gHello = t(48, "display", Color.WHITE);
        gHello.setShadowLayer(dp(16), 0, dp(2), 0x80000000);
        gDate = t(17, "regular", 0xE6FFFFFF);
        gSky = t(15, "regular", 0xCCFFFFFF);
        gSun = t(13, "regular", 0xB3FFFFFF);
        for (TextView v : new TextView[] {gHello, gDate, gSky, gSun}) {
            v.setGravity(Gravity.RIGHT);
            if (v != gHello) v.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
            c.addView(v, lp(-1, -2, 0));
        }
        ((LinearLayout.LayoutParams) gHello.getLayoutParams()).setMargins(0, 0, 0, dp(6));
        ((LinearLayout.LayoutParams) gSky.getLayoutParams()).setMargins(0, dp(4), 0, dp(2));
        return c;
    }

    // ---------------------------------------------------------------- every minute

    void setName(String n) { name = n; tick(); }

    String city() { return city; }

    void tick() {
        readConfig();   // the account watching may have another city
        Calendar now = Calendar.getInstance(RIYADH);
        int h = now.get(Calendar.HOUR_OF_DAY);
        if (!(city + lat + lon).equals(prayersFor)) prayerMin = null;
        String hello = h < 4 ? "تصبح على خير" : h < 12 ? "صباح الخير" : "مساء الخير";
        if (gHello != null) gHello.setText(hello + (name.isEmpty() ? "." : " يا " + name + "."));
        if (gDate != null) gDate.setText(Ui.digits(new SimpleDateFormat("EEEE، d MMMM", new Locale("ar")).format(now.getTime())));
        if (prayerMin == null || now.get(Calendar.DAY_OF_YEAR) != computedDay) computePrayers();
        int mins = h * 60 + now.get(Calendar.MINUTE);
        int next = -1;
        for (int i = 0; i < 5; i++) if (prayerMin[i] > mins) { next = i; break; }
        String[] names = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
        int idx = next < 0 ? 0 : next;
        int left = next < 0 ? prayerMin[0] + 1440 - mins : prayerMin[next] - mins;
        prNext.setText(names[idx] + " بعد " + duration(left));
        for (int i = 0; i < 5; i++) {
            prTime[i].setText(hm(prayerMin[i]));
            boolean on = i == idx;
            if (on) {
                GradientDrawable cap = Ui.rounded(0x26FFFFFF, 14, a);
                cap.setStroke(dp(1), 0x40FFFFFF);
                prCell[i].setBackground(cap);
            } else prCell[i].setBackground(null);
            prName[i].setTextColor(on ? Color.WHITE : Ui.DIM);
        }
        prTitle.setText("مواقيت الصلاة · " + city);
        gSun.setText("الغروب " + hm(prayerMin[3]) + "   ·   الشروق " + hm(prayerMin[5]));
        refreshIfStale();
    }

    private static String duration(int m) {
        int h = m / 60, mi = m % 60;
        if (h == 0) return Ui.digits(mi + " د");
        return Ui.digits(h + " س" + (mi > 0 ? " و" + mi + " د" : ""));
    }

    static volatile boolean clock24;

    private static String hm(int m) {
        m = ((m % 1440) + 1440) % 1440;
        int h = m / 60, mi = m % 60;
        int h12 = clock24 ? h : h % 12 == 0 ? 12 : h % 12;
        return Ui.digits(h12 + ":" + (mi < 10 ? "0" : "") + mi);
    }

    // ---------------------------------------------------------------- settings of the account watching

    private boolean readConfig() {
        try (Cursor c = a.getContentResolver().query(CONFIG, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return false;
            Map<String, String> m = new HashMap<>();
            for (String kv : c.getString(0).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) m.put(kv.substring(0, e), Uri.decode(kv.substring(e + 1)));
            }
            if (m.containsKey("city")) city = m.get("city");
            try { lat = Double.parseDouble(m.get("lat")); lon = Double.parseDouble(m.get("lon")); } catch (Exception ignored) { }
            String teams = m.get("teams");
            teamId = teams == null || teams.isEmpty() ? "" : teams.split(",")[0];
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Reload when the account (its city or team) changed, or every 20 minutes. */
    void refreshIfStale() {
        readConfig();
        String key = city + lat + lon + teamId;
        if (key.equals(loadedFor) && System.currentTimeMillis() - loadedAt < REFRESH_MS) return;
        loadedFor = key;
        loadedAt = System.currentTimeMillis();
        final double la = lat, lo = lon;
        final String team = teamId, where = city;
        new Thread(() -> {
            loadWeather(la, lo, where);
            loadMatch(team);
        }, "home-panels").start();
    }

    // ---------------------------------------------------------------- prayers (same method as the screensaver page)

    private int computedDay = -1;
    private String prayersFor = "";

    private void computePrayers() {
        Calendar p = Calendar.getInstance(RIYADH);
        computedDay = p.get(Calendar.DAY_OF_YEAR);
        prayersFor = city + lat + lon;
        double tz = 3, lng = lon, la = lat;
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(p.get(Calendar.YEAR), p.get(Calendar.MONTH), p.get(Calendar.DAY_OF_MONTH));
        double jd0 = utc.getTimeInMillis() / 86400000.0 + 2440587.5 - lng / (15 * 24);
        boolean ramadan = false;
        try {
            android.icu.util.Calendar ic = android.icu.util.Calendar.getInstance(new android.icu.util.ULocale("ar_SA@calendar=islamic-umalqura"));
            ramadan = ic.get(android.icu.util.Calendar.MONTH) == 8;
        } catch (Exception ignored) { }
        double fajr = adj(angleTime(jd0, la, 18.5, 5, true), tz, lng);
        double dhuhr = adj(noon(jd0, 12), tz, lng);
        double asr = adj(asrTime(jd0, la, 13), tz, lng);
        double sunrise = adj(angleTime(jd0, la, .833, 6, true), tz, lng);
        double maghrib = adj(angleTime(jd0, la, .833, 18, false), tz, lng);
        double isha = maghrib + (ramadan ? 120 : 90) / 60.0;
        prayerMin = new int[] {mins(fajr), mins(dhuhr), mins(asr), mins(maghrib), mins(isha), mins(sunrise)};
    }

    private static int mins(double h) { return (int) Math.round(fix(h, 24) * 60) % 1440; }
    private static double fix(double a, double b) { a = a - b * Math.floor(a / b); return a < 0 ? a + b : a; }
    private static double rad(double d) { return d * Math.PI / 180; }
    private static double deg(double r) { return r * 180 / Math.PI; }
    private static double adj(double h, double tz, double lng) { return h + tz - lng / 15; }

    /** {declination, equation of time} at day fraction t. */
    private static double[] sun(double jd0, double t) {
        double d = jd0 + t - 2451545;
        double g = fix(357.529 + .98560028 * d, 360), q = fix(280.459 + .98564736 * d, 360);
        double l = fix(q + 1.915 * Math.sin(rad(g)) + .020 * Math.sin(rad(2 * g)), 360);
        double e = 23.439 - .00000036 * d;
        double ra = fix(deg(Math.atan2(Math.cos(rad(e)) * Math.sin(rad(l)), Math.cos(rad(l)))) / 15, 24);
        return new double[] {deg(Math.asin(Math.sin(rad(e)) * Math.sin(rad(l)))), q / 15 - ra};
    }

    private static double noon(double jd0, double t) { return fix(12 - sun(jd0, t / 24)[1], 24); }

    private static double angleTime(double jd0, double lat, double angle, double t, boolean ccw) {
        double d = sun(jd0, t / 24)[0], n = noon(jd0, t);
        double tt = deg(Math.acos((-Math.sin(rad(angle)) - Math.sin(rad(d)) * Math.sin(rad(lat)))
            / (Math.cos(rad(d)) * Math.cos(rad(lat))))) / 15;
        return n + (ccw ? -tt : tt);
    }

    private static double asrTime(double jd0, double lat, double t) {
        double d = sun(jd0, t / 24)[0];
        double ang = -deg(Math.atan(1 / (1 + Math.tan(rad(Math.abs(lat - d))))));
        return angleTime(jd0, lat, ang, t, false);
    }

    // ---------------------------------------------------------------- weather

    private static String code(int c) {
        if (c == 0) return "صافٍ";
        if (c <= 2) return "غائم جزئياً";
        if (c == 3) return "غائم";
        if (c == 45 || c == 48) return "ضباب";
        if (c <= 57) return "رذاذ";
        if (c <= 67) return "مطر";
        if (c <= 77) return "ثلج";
        if (c <= 82) return "زخات مطر";
        if (c <= 86) return "زخات ثلج";
        return "عواصف رعدية";
    }

    private static String icon(int c, boolean day) {
        if (c == 0) return day ? "☀" : "☾";
        if (c <= 2) return day ? "⛅" : "☾";
        if (c == 3 || c == 45 || c == 48) return "☁";
        if (c <= 67 || (c >= 80 && c <= 82)) return "☂";
        if (c <= 86) return "❄";
        return "⚡";
    }

    private static String skyLine(int c, String city) {
        if (c == 0) return "سماء صافية في " + city + ".";
        if (c <= 3) return "سماء غائمة في " + city + ".";
        if (c == 45 || c == 48) return "أجواء ضبابية في " + city + ".";
        if (c <= 67 || (c >= 80 && c <= 82)) return "أمطار في " + city + ".";
        return "طقس متقلّب في " + city + ".";
    }

    private void loadWeather(double la, double lo, String where) {
        try {
            String q = "latitude=" + la + "&longitude=" + lo + "&timezone=Asia%2FRiyadh";
            JSONObject j = new JSONObject(get("https://api.open-meteo.com/v1/forecast?" + q
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=2"));
            JSONObject cur = j.getJSONObject("current");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            JSONObject daily = j.getJSONObject("daily");
            int max = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            int min = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
            JSONObject hourly = j.getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time"), temps = hourly.getJSONArray("temperature_2m"),
                codes = hourly.getJSONArray("weather_code"), days = hourly.getJSONArray("is_day");
            String nowHour = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.ROOT) {{ setTimeZone(RIYADH); }}.format(new Date());
            int start = 0;
            for (int i = 0; i < times.length(); i++) if (times.getString(i).startsWith(nowHour)) { start = i + 1; break; }
            final String[] hl = new String[6], hi = new String[6], ht = new String[6];
            for (int k = 0; k < 6 && start + k < times.length(); k++) {
                int i = start + k;
                int hour = Integer.parseInt(times.getString(i).substring(11, 13));
                hl[k] = Ui.digits((hour % 12 == 0 ? 12 : hour % 12) + (hour < 12 ? " ص" : " م"));
                hi[k] = icon(codes.getInt(i), days.getInt(i) == 1);
                ht[k] = Ui.digits(Math.round(temps.getDouble(i)) + "°");
            }
            final String temp = Ui.digits(Math.round(cur.getDouble("temperature_2m")) + "°");
            final String ic = icon(code, day), cond = code(code);
            final String range = Ui.digits("العظمى " + max + "°   الصغرى " + min + "°");
            final String more = Ui.digits("المحسوسة " + Math.round(cur.getDouble("apparent_temperature")) + "°  ·  الرياح "
                + Math.round(cur.getDouble("wind_speed_10m")) + " كم/س  ·  الرطوبة " + cur.getInt("relative_humidity_2m") + "٪");
            final String line = skyLine(code, where);
            a.runOnUiThread(() -> {
                wxIcon.setText(ic);
                wxIcon.setTextColor(ic.equals("☀") || ic.equals("⛅") ? 0xFFFFD45A : Color.WHITE);
                wxTemp.setText(temp);
                wxCond.setText(cond);
                wxRange.setText(range);
                wxMore.setText(more);
                for (int k = 0; k < 6; k++) {
                    hrLabel[k].setText(hl[k] == null ? "" : hl[k]);
                    hrIcon[k].setText(hi[k] == null ? "" : hi[k]);
                    hrIcon[k].setTextColor("☀".equals(hi[k]) ? 0xFFFFD45A : Color.WHITE);
                    hrTemp[k].setText(ht[k] == null ? "" : ht[k]);
                }
                gSky.setText(line);
            });
        } catch (Exception e) {
            a.runOnUiThread(() -> { if (gSky.getText().length() == 0) gSky.setText(where); });
        }
    }

    // ---------------------------------------------------------------- the next match

    private Map<String, String> arabicTeams;

    private String ar(String en) {
        if (arabicTeams == null) {
            arabicTeams = new HashMap<>();
            try (InputStream in = a.getAssets().open("teams-ar.json")) {
                JSONObject o = new JSONObject(new String(read(in), "UTF-8"));
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); arabicTeams.put(k, o.getString(k)); }
            } catch (Exception ignored) { }
        }
        String v = arabicTeams.get(en);
        return v != null ? v : en;
    }

    private void loadMatch(String team) {
        if (team.isEmpty()) {
            a.runOnUiThread(() -> { mTitle.setText("المباريات"); mWhen.setText("ما فيه فريق متابَع"); mLeague.setText("أضفه من إعدادات AZ TV"); });
            return;
        }
        try {
            JSONObject j = new JSONObject(get("https://www.thesportsdb.com/api/v1/json/123/eventsnext.php?id=" + team));
            JSONArray ev = j.optJSONArray("events");
            if (ev == null || ev.length() == 0) throw new Exception("none");
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < ev.length(); i++) list.add(ev.getJSONObject(i));
            JSONObject e = list.get(0);
            final String home = ar(e.optString("strHomeTeam")), away = ar(e.optString("strAwayTeam"));
            final String when = whenText(e), league = league(e.optString("strLeague"));
            final String mine = ar(e.optString("idHomeTeam").equals(team) ? e.optString("strHomeTeam") : e.optString("strAwayTeam"));
            final String[] more = new String[2];
            for (int k = 0; k < 2; k++) {
                if (k + 1 >= list.size()) { more[k] = ""; continue; }
                JSONObject x = list.get(k + 1);
                more[k] = ar(x.optString("strHomeTeam")) + " × " + ar(x.optString("strAwayTeam")) + "   ·   " + whenText(x);
            }
            final Bitmap hb = badge(e.optString("strHomeTeamBadge")), ab = badge(e.optString("strAwayTeamBadge"));
            a.runOnUiThread(() -> {
                mTitle.setText(mine + " · المباراة القادمة");
                mHomeName.setText(home);
                mAwayName.setText(away);
                mWhen.setText(when);
                mLeague.setText(league);
                mMore[0].setText(more[0]);
                mMore[1].setText(more[1]);
                mHomeLogo.setImageBitmap(hb);
                mAwayLogo.setImageBitmap(ab);
                mFoot.setText(Ui.digits("TheSportsDB · تحديث " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())));
            });
        } catch (Exception ex) {
            a.runOnUiThread(() -> { if (mWhen.getText().toString().equals("…")) { mWhen.setText("ما فيه مباريات قريبة"); mLeague.setText(""); } });
        }
    }

    private static final String[][] LEAGUES = {
        {"Saudi-Arabian Pro League", "دوري روشن السعودي"}, {"Saudi First Division League", "دوري يلو"},
        {"Saudi King Cup", "كأس الملك"}, {"Saudi Super Cup", "كأس السوبر السعودي"},
        {"AFC Champions League Elite", "دوري أبطال آسيا للنخبة"}, {"AFC Champions League", "دوري أبطال آسيا"},
        {"FIFA Club World Cup", "كأس العالم للأندية"}, {"English Premier League", "الدوري الإنجليزي"},
        {"Spanish La Liga", "الدوري الإسباني"}, {"Italian Serie A", "الدوري الإيطالي"},
        {"German Bundesliga", "الدوري الألماني"}, {"French Ligue 1", "الدوري الفرنسي"},
        {"UEFA Champions League", "دوري أبطال أوروبا"}, {"FIFA World Cup", "كأس العالم"},
        {"FIFA World Cup Qualifying AFC", "تصفيات كأس العالم"}, {"International Friendlies", "مباراة ودية"},
    };

    private static String league(String en) {
        for (String[] l : LEAGUES) if (l[0].equalsIgnoreCase(en)) return l[1];
        return en;
    }

    private static String whenText(JSONObject e) {
        try {
            String ts = e.optString("strTimestamp", "");
            Date d;
            if (!ts.isEmpty()) {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                d = f.parse(ts.length() > 19 ? ts.substring(0, 19) : ts);
            } else {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                d = f.parse(e.optString("dateEvent") + " " + e.optString("strTime", "00:00:00"));
            }
            SimpleDateFormat out = new SimpleDateFormat(clock24 ? "EEEE d MMMM  ·  HH:mm" : "EEEE d MMMM  ·  h:mm a", new Locale("ar"));
            out.setTimeZone(RIYADH);
            return Ui.digits(out.format(d));
        } catch (Exception x) {
            return e.optString("dateEvent");
        }
    }

    private Bitmap badge(String url) {
        if (url == null || url.isEmpty() || url.equals("null")) return null;
        try {
            byte[] b = getBytes(url + "/tiny");
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bm == null) { b = getBytes(url); bm = BitmapFactory.decodeByteArray(b, 0, b.length); }
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- network

    private static String get(String url) throws Exception { return new String(getBytes(url), "UTF-8"); }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }
}
