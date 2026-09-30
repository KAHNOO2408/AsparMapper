package ir.aspar.mapper.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Finds the package of the app currently in front. Runs in the helper (shell rights), so it
 * works without any extra permission for the app. Tries the framework API first and falls back
 * to "dumpsys".
 */
final class ForegroundWatcher {

    interface Listener {
        void onForeground(String pkg);
    }

    private final Listener listener;
    private Object atm;
    private Method getFocused;
    private boolean apiFailed;
    private String last;

    ForegroundWatcher(Listener listener) {
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String pkg = current();
                    if (pkg != null && !pkg.equals(last)) {
                        last = pkg;
                        listener.onForeground(pkg);
                    }
                    Thread.sleep(apiFailed ? 1500 : 500);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "foreground");
        t.setDaemon(true);
        t.start();
    }

    String last() {
        return last;
    }

    private String current() {
        if (!apiFailed) {
            try {
                if (getFocused == null) {
                    Class<?> cls = Class.forName("android.app.ActivityTaskManager");
                    atm = cls.getMethod("getService").invoke(null);
                    getFocused = atm.getClass().getMethod("getFocusedRootTaskInfo");
                }
                Object info = getFocused.invoke(atm);
                if (info == null) return null;
                Object top = info.getClass().getField("topActivity").get(info);
                if (top instanceof android.content.ComponentName) {
                    return ((android.content.ComponentName) top).getPackageName();
                }
                return null;
            } catch (Throwable t) {
                Log.w("foreground API unavailable, using dumpsys: " + t);
                apiFailed = true;
            }
        }
        return fromDumpsys();
    }

    /** Parses e.g. "topResumedActivity=ActivityRecord{abc u0 com.game/.Main t12}". */
    private static String fromDumpsys() {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")
                    .redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                if (line == null) return null;
                for (String tok : line.trim().split("\\s+")) {
                    int slash = tok.indexOf('/');
                    if (slash > 0 && tok.indexOf('.') > 0 && tok.indexOf('.') < slash) return tok.substring(0, slash);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return null;
    }
}
