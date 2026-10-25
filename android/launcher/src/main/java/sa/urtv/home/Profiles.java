package sa.urtv.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * «مين يشاهد اليوم؟»: the household's accounts. Each has a name, an avatar (an emoji on a colour, or a photo
 * sent from a phone), an optional 4-digit code, its own favourites row and its own photos for the home screen
 * and the screensaver. Stored in the launcher's settings file; photos under files/profiles/<id>/.
 *
 * The favourites row stays in the "dock" key the rest of the launcher reads: switching accounts parks the row
 * under "dock_<old id>" and brings back "dock_<new id>".
 */
final class Profiles {
    static final String CHANGED = "sa.urtv.home.PROFILE_CHANGED";
    static final int MAX = 6, MAX_PHOTOS = 60;

    static final String[] EMOJI = {"🦅", "🐪", "🌴", "⚽", "🎮", "🌙", "⭐", "🐎",
                                   "🦁", "🐱", "🌸", "🚀", "👑", "🎧", "🍿", "☕"};
    /** Avatar gradients, top-left to bottom-right. */
    static final int[][] COLORS = {
        {0xFFE9B44C, 0xFF8A4B12}, {0xFF2BB673, 0xFF0B5E3B}, {0xFF4F8EF7, 0xFF1E3A8A}, {0xFFEC4899, 0xFF831843},
        {0xFF9B6CF6, 0xFF3B0764}, {0xFFF97316, 0xFF7C2D12}, {0xFF14B8A6, 0xFF134E4A}, {0xFFEF4444, 0xFF7F1D1D}};

    static final class Profile {
        String id, name, emoji, pin = "";
        int color;
        boolean locked() { return !pin.isEmpty(); }
    }

    private Profiles() {}

    static SharedPreferences prefs(Context c) { return Dock.prefs(c); }

    // ---------------------------------------------------------------- the list

    static synchronized List<Profile> all(Context c) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Profile p = new Profile();
                p.id = o.getString("id");
                p.name = o.optString("name", "");
                p.emoji = o.optString("emoji", EMOJI[0]);
                p.color = Math.floorMod(o.optInt("color", 0), COLORS.length);
                p.pin = o.optString("pin", "");
                out.add(p);
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            // first run: the person who set the TV up; the favourites row as it is becomes theirs
            Profile me = new Profile();
            me.id = "p1";
            me.name = "أنا";
            me.emoji = EMOJI[0];
            me.color = 0;
            out.add(me);
            write(c, out);
            prefs(c).edit().putString("active", me.id).apply();
        }
        return out;
    }

    static synchronized void write(Context c, List<Profile> list) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.id).put("name", p.name).put("emoji", p.emoji).put("color", p.color).put("pin", p.pin);
                a.put(o);
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString("profiles", a.toString()).apply();
    }

    static Profile find(Context c, String id) {
        for (Profile p : all(c)) if (p.id.equals(id)) return p;
        return null;
    }

    static Profile active(Context c) {
        List<Profile> list = all(c);
        String id = prefs(c).getString("active", null);
        for (Profile p : list) if (p.id.equals(id)) return p;
        return list.get(0);
    }

    /** Adds or replaces by id. A new account starts with a copy of the current favourites. */
    static void put(Context c, Profile p) {
        List<Profile> list = all(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(p.id)) { list.set(i, p); found = true; }
        if (!found) {
            list.add(p);
            String dock = prefs(c).getString("dock", null);
            if (dock != null) prefs(c).edit().putString("dock_" + p.id, dock).apply();
        }
        write(c, list);
        notifyChanged(c);
    }

    static void remove(Context c, String id) {
        List<Profile> list = all(c);
        if (list.size() <= 1) return;
        if (active(c).id.equals(id)) for (Profile p : list) if (!p.id.equals(id)) { activate(c, p); break; }
        List<Profile> keep = new ArrayList<>();
        for (Profile p : list) if (!p.id.equals(id)) keep.add(p);
        write(c, keep);
        prefs(c).edit().remove("dock_" + id).remove("hidden_" + id).apply();
        deleteTree(dir(c, id));
        notifyChanged(c);
    }

    static String newId(Context c) {
        int n = 1;
        for (Profile p : all(c)) {
            try { n = Math.max(n, Integer.parseInt(p.id.substring(1)) + 1); } catch (RuntimeException ignored) { }
        }
        return "p" + n;
    }

    /** Makes p the account on screen: its favourites come back, the picker is satisfied. */
    static synchronized void activate(Context c, Profile p) {
        SharedPreferences sp = prefs(c);
        String old = sp.getString("active", null);
        SharedPreferences.Editor e = sp.edit();
        if (old != null && !old.equals(p.id)) {
            String dock = sp.getString("dock", null);
            if (dock != null) e.putString("dock_" + old, dock); else e.remove("dock_" + old);
            String mine = sp.getString("dock_" + p.id, null);
            if (mine != null) e.putString("dock", mine); else e.remove("dock");
        }
        // «التطبيقات المفتوحة» lists only what was opened since this account took over
        e.putString("active", p.id).putBoolean("needPick", false).putLong("activeSince", System.currentTimeMillis()).apply();
        c.sendBroadcast(new Intent(Dock.CHANGED).setPackage(c.getPackageName()));
        notifyChanged(c);
    }

    static void notifyChanged(Context c) {
        prefs(c).edit().putLong("profilesVersion", System.nanoTime()).apply();
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }

    static long version(Context c) { return prefs(c).getLong("profilesVersion", 0); }

    static long activeSince(Context c) { return prefs(c).getLong("activeSince", 0); }

    // ---------------------------------------------------------------- the account's own home-screen choices

    /** show_match, show_prayers, show_weather, show_greeting, clock24, latin: per account, from «الإعدادات». */
    static boolean flag(Context c, String key, boolean def) {
        return prefs(c).getBoolean(key + "_" + active(c).id, def);
    }

    static void setFlag(Context c, String key, boolean v) {
        prefs(c).edit().putBoolean(key + "_" + active(c).id, v).apply();
        notifyChanged(c);
    }

    // ---------------------------------------------------------------- apps each account hides from itself

    static java.util.Set<String> hidden(Context c) {
        return new java.util.HashSet<>(prefs(c).getStringSet("hidden_" + active(c).id, new java.util.HashSet<>()));
    }

    static void hide(Context c, String pkg, boolean hide) {
        java.util.Set<String> h = hidden(c);
        if (hide) h.add(pkg); else h.remove(pkg);
        prefs(c).edit().putStringSet("hidden_" + active(c).id, h).apply();
        c.sendBroadcast(new Intent(Dock.CHANGED).setPackage(c.getPackageName()));
    }

    static void unhideAll(Context c) {
        prefs(c).edit().remove("hidden_" + active(c).id).apply();
        c.sendBroadcast(new Intent(Dock.CHANGED).setPackage(c.getPackageName()));
    }

    // ---------------------------------------------------------------- the picker on wake

    /** Worth asking only with more than one account, or a locked one. */
    static boolean pickerUseful(Context c) {
        List<Profile> list = all(c);
        return list.size() > 1 || list.get(0).locked();
    }

    static boolean needPick(Context c) { return pickerUseful(c) && prefs(c).getBoolean("needPick", false); }

    static void requirePick(Context c) { prefs(c).edit().putBoolean("needPick", true).apply(); }

    /** True once per boot: the clock's boot time moved since we last looked. */
    static boolean freshBoot(Context c) {
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        long seen = prefs(c).getLong("bootAt", 0);
        prefs(c).edit().putLong("bootAt", bootAt).apply();
        return Math.abs(bootAt - seen) > 120_000;
    }

    // ---------------------------------------------------------------- the code

    static String hash(String id, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("urtv:" + id + ":" + pin).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean checkPin(Profile p, String pin) { return !p.locked() || p.pin.equals(hash(p.id, pin)); }

    // ---------------------------------------------------------------- photos

    static File dir(Context c, String id) { return new File(c.getFilesDir(), "profiles/" + id); }
    static File photosDir(Context c, String id) { return new File(dir(c, id), "photos"); }
    static File avatarFile(Context c, String id) { return new File(dir(c, id), "avatar.jpg"); }

    /** Newest first. */
    static List<File> photos(Context c, String id) {
        File[] f = photosDir(c, id).listFiles((d, n) -> n.matches("[A-Za-z0-9_-]+\\.jpg"));
        if (f == null) return new ArrayList<>();
        Arrays.sort(f, (a, b) -> b.getName().compareTo(a.getName()));
        return new ArrayList<>(Arrays.asList(f));
    }

    /** Any phone photo (JPEG, PNG, HEIC…) → a JPEG at most 1920 px on its long side. Returns the name, or null. */
    static synchronized String addPhoto(Context c, String id, byte[] data) {
        if (photos(c, id).size() >= MAX_PHOTOS) return null;
        Bitmap bm = decode(data, 1920);
        if (bm == null) return null;
        File dir = photosDir(c, id);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String name = "p" + System.currentTimeMillis() + ".jpg";
        return saveJpeg(bm, new File(dir, name)) ? name : null;
    }

    static boolean deletePhoto(Context c, String id, String name) {
        return name.matches("[A-Za-z0-9_-]+\\.jpg") && new File(photosDir(c, id), name).delete();
    }

    /** A photo avatar: the middle square, 400 px. */
    static synchronized boolean setAvatar(Context c, String id, byte[] data) {
        Bitmap bm = decode(data, 1200);
        if (bm == null) return false;
        int s = Math.min(bm.getWidth(), bm.getHeight());
        Bitmap sq = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
        new Canvas(sq).drawBitmap(bm, new Rect((bm.getWidth() - s) / 2, (bm.getHeight() - s) / 2,
            (bm.getWidth() + s) / 2, (bm.getHeight() + s) / 2), new Rect(0, 0, 400, 400), new Paint(Paint.FILTER_BITMAP_FLAG));
        File f = avatarFile(c, id);
        if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) return false;
        boolean ok = saveJpeg(sq, f);
        if (ok) notifyChanged(c);
        return ok;
    }

    static void clearAvatar(Context c, String id) {
        if (avatarFile(c, id).delete()) notifyChanged(c);
    }

    private static Bitmap decode(byte[] data, int maxSide) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder also reads HEIC and turns the photo upright from its EXIF
                return android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(ByteBuffer.wrap(data)), (dec, info, src) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        float k = Math.min(1f, maxSide / (float) Math.max(w, h));
                        dec.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                        dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= maxSide) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static boolean saveJpeg(Bitmap bm, File f) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bm.compress(Bitmap.CompressFormat.JPEG, 88, out)) return false;
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ---------------------------------------------------------------- avatar picture

    /** A round avatar, size px: the account's photo, or its emoji on its colour. */
    static Bitmap avatar(Context c, Profile p, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap photo = null;
        File f = avatarFile(c, p.id);
        if (f.isFile()) photo = BitmapFactory.decodeFile(f.getPath());
        if (photo != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(photo, size, size, true);
            paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            cv.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            return out;
        }
        drawEmoji(cv, size, p.emoji, p.color);
        return out;
    }

    static Bitmap emojiAvatar(String emoji, int color, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawEmoji(new Canvas(out), size, emoji, color);
        return out;
    }

    private static void drawEmoji(Canvas cv, int size, String emoji, int color) {
        int[] g = COLORS[Math.floorMod(color, COLORS.length)];
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, size, size, g[0], g[1], Shader.TileMode.CLAMP));
        cv.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        // soft light from the top so the circle reads as a disc
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0, 0, 0, size, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawCircle(size / 2f, size / 2f, size / 2f, sheen);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(size * 0.48f);
        t.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = t.getFontMetrics();
        cv.drawText(emoji, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, t);
    }
}
