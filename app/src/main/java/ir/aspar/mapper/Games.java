package ir.aspar.mapper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The user's list of games/apps that Aspar Mapper is used with. */
final class Games {

    static final class App {
        final String pkg;
        final String label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private Games() {
    }

    static List<String> saved(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(ctx).getString("games", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void store(Context ctx, List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        Prefs.get(ctx).edit().putString("games", a.toString()).apply();
    }

    static void add(Context ctx, String pkg) {
        List<String> l = saved(ctx);
        if (!l.contains(pkg)) {
            l.add(pkg);
            store(ctx, l);
        }
    }

    static void remove(Context ctx, String pkg) {
        List<String> l = saved(ctx);
        l.remove(pkg);
        store(ctx, l);
    }

    /** Info for one package, or null if it is no longer installed. */
    static App info(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return new App(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** All apps that can be opened from the launcher (games first, then by name). Slow: call off the UI thread. */
    static List<App> installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<App> games = new ArrayList<>();
        List<App> others = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || seen.contains(pkg)) continue;
            seen.add(pkg);
            App app = new App(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm));
            boolean isGame = ri.activityInfo.applicationInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME;
            (isGame ? games : others).add(app);
        }
        java.util.Comparator<App> byName = (a, b) -> a.label.compareToIgnoreCase(b.label);
        Collections.sort(games, byName);
        Collections.sort(others, byName);
        games.addAll(others);
        return games;
    }

    static boolean launch(Context ctx, String pkg) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }
}
