package sa.urtv.home;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * «التطبيقات المفتوحة»: recently used apps from usage stats (granted over ADB: appops GET_USAGE_STATS), closing one
 * (killBackgroundProcesses: everything behind the home screen), and removing an app the user installed.
 */
final class Apps {
    static final class Recent {
        String pkg, label;
        long lastUsed;
    }

    private Apps() {}

    static boolean canSeeUsage(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
            == AppOpsManager.MODE_ALLOWED;
    }

    /** Apps opened in this account's session and not closed since, newest first. */
    static List<Recent> recent(Context c) {
        List<Recent> out = new ArrayList<>();
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return out;
        long now = System.currentTimeMillis();
        long since = Math.max(Profiles.activeSince(c), now - 24 * 3600 * 1000L);
        Map<String, String> apps = Dock.launchable(c);
        // the end runs far ahead: this box's daily bucket can carry a future start date (its clock was once wrong),
        // and a range ending "now" skips it; each app's own lastTimeUsed is real, so that is what's filtered on
        Map<String, UsageStats> stats = um.queryAndAggregateUsageStats(since - 24 * 3600 * 1000L, now + 3650L * 24 * 3600 * 1000);
        for (Map.Entry<String, UsageStats> e : stats.entrySet()) {
            String pkg = e.getKey();
            long used = e.getValue().getLastTimeUsed();
            if (!apps.containsKey(pkg) || used < since || used > now + 60_000 || used <= closedAt(c, pkg)) continue;
            Recent r = new Recent();
            r.pkg = pkg;
            r.label = apps.get(pkg);
            r.lastUsed = used;
            out.add(r);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    private static long closedAt(Context c, String pkg) { return Profiles.prefs(c).getLong("closed_" + pkg, 0); }

    /** Stops the app's processes (it is behind us, so in the background) and drops it from the list. */
    static void close(Context c, String pkg) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am != null) am.killBackgroundProcesses(pkg);
        Profiles.prefs(c).edit().putLong("closed_" + pkg, System.currentTimeMillis()).apply();
    }

    static boolean open(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Only apps the user installed (or updated system apps' own installs are kept): never the TV's own. */
    static boolean removable(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !pkg.equals(c.getPackageName())
                && !pkg.equals("sa.urtv.screensaver");
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The system's own «Uninstall?» dialog: the user confirms there. */
    static void uninstall(Context c, String pkg) {
        try {
            c.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }
}
