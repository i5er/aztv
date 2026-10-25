package sa.urtv.home;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * «الواجهة الرئيسية الثابتة»: keeps AZ TV as the home screen. Google TV and some TCL updates bring their own home
 * back (an update re-enables it, Home goes to it again); this accessibility service sees the stock home come to the
 * front and puts ours back at once, and Boot opens ours after a restart. On by default; from «الإعدادات», or over
 * ADB (optimize/install.sh grants WRITE_SECURE_SETTINGS, so the app turns the service on by itself).
 */
public class HomeKeeper extends AccessibilityService {
    /** Stock homes we know of, also when they no longer answer the HOME query (disabled, then re-enabled). */
    private static final Set<String> STOCK = new HashSet<>(Arrays.asList(
        "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher", "com.tcl.tvlauncher", "com.tcl.launcher"));
    /** Never bounced: the system's own screens that can also answer HOME (FallbackHome at boot, the chooser). */
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
        "android", "com.android.settings", "com.android.tv.settings", "com.android.systemui"));

    private long lastBounce;
    private Set<String> homes;

    // ---------------------------------------------------------------- the setting (device-wide, not per account)

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("keeper", Context.MODE_PRIVATE); }

    static boolean wanted(Context c) { return prefs(c).getBoolean("keep_home", true); }

    static void setWanted(Context c, boolean on) { prefs(c).edit().putBoolean("keep_home", on).apply(); }

    /** Is AZ TV what Home opens right now? */
    static boolean isDefault(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    static boolean serviceOn(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        ComponentName me = new ComponentName(c, HomeKeeper.class);
        for (String s : list.split(":")) if (me.equals(ComponentName.unflattenFromString(s))) return true;
        return false;
    }

    /** Turns the service on without the user when WRITE_SECURE_SETTINGS was granted over ADB; false otherwise. */
    static boolean enableService(Context c) {
        if (serviceOn(c)) return true;
        try {
            ContentResolver r = c.getContentResolver();
            String list = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String me = new ComponentName(c, HomeKeeper.class).flattenToString();
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                TextUtils.isEmpty(list) ? me : list + ":" + me);
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Called by the home screen on every return: if the user wants it, quietly make sure the keeper runs. */
    static void ensure(Context c) {
        if (wanted(c)) enableService(c);
    }

    /** Asks Android to make AZ TV the home app (Android 10+ shows its own question); false if it can't ask. */
    static boolean askDefault(Activity a, int requestCode) {
        if (isDefault(a)) return true;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = a.getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode);
                    return true;
                }
            }
            a.startActivityForResult(new Intent(Settings.ACTION_HOME_SETTINGS), requestCode);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void goHome(Context c) {
        c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(c, MainActivity.class.getName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    // ---------------------------------------------------------------- the service

    @Override
    protected void onServiceConnected() {
        homes = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        if (!wanted(this)) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName()) || NEVER.contains(pkg) || !stockHomes().contains(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastBounce < 1500) return;   // one bounce per Home press, never a loop
        lastBounce = now;
        try { goHome(this); } catch (RuntimeException ignored) { }
    }

    /** Every other app that answers HOME, plus the known stock homes. Cached; apps rarely change while it runs. */
    private Set<String> stockHomes() {
        if (homes != null) return homes;
        Set<String> s = new HashSet<>(STOCK);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0))
            s.add(ri.activityInfo.packageName);
        s.remove(getPackageName());
        s.removeAll(NEVER);
        return homes = s;
    }

    @Override
    public void onInterrupt() { }

    /** After a restart: open AZ TV even if the TV put its own home back meanwhile. */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!wanted(c)) return;
            ensure(c);
            if (!isDefault(c)) try { goHome(c); } catch (RuntimeException ignored) { }
        }
    }
}
