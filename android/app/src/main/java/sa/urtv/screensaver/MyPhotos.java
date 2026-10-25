package sa.urtv.screensaver;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

/**
 * The active account's own photos, kept by the launcher (sa.urtv.home, PhotoProvider). When the account has
 * any, the page's hosted photo list is answered with them instead, so the screen shows the family's photos.
 */
final class MyPhotos {
    static final String PREFIX = "my_";
    private static final Uri BASE = Uri.parse("content://sa.urtv.home.photos");

    private MyPhotos() {}

    /** {id, name} of the account watching now, or null without the launcher's accounts. */
    static String[] activeProfile(Context c) {
        try (Cursor cur = c.getContentResolver().query(BASE.buildUpon().appendPath("active").build(), null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return new String[] {cur.getString(0), cur.getString(1)};
        } catch (Exception ignored) { }
        return null;
    }

    /** photos/index.json in the page's format, or null when the account has no photos of its own. */
    static String index(Context c) {
        JSONArray a = new JSONArray();
        try (Cursor cur = c.getContentResolver().query(BASE.buildUpon().appendPath("list").build(), null, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("f", PREFIX + cur.getString(0));
                o.put("c", "");
                o.put("d", 0.9);
                a.put(o);
            }
        } catch (Exception e) {
            return null;   // no launcher, or an older one without accounts
        }
        return a.length() == 0 ? null : a.toString();
    }

    /** One of the active account's photos, by the name index() gave; null if it is not theirs. */
    static InputStream open(Context c, String name) {
        try {
            ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(
                BASE.buildUpon().appendPath("file").appendPath(name.substring(PREFIX.length())).build(), "r");
            return fd == null ? null : new ParcelFileDescriptor.AutoCloseInputStream(fd);
        } catch (Exception e) {
            return null;
        }
    }
}
