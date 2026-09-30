package ir.aspar.mapper.adb;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

import ir.aspar.mapper.Prefs;

/**
 * Pairs with the phone's own "Wireless debugging" (first time only) and launches the
 * privileged helper process with shell rights. Everything here blocks: call from a worker thread.
 */
public final class Activator {

    public static final int SERVER_PORT = 47810;
    private static final String HOST = "127.0.0.1";

    private Activator() {
    }

    /** Waits (up to timeoutSec) until the "Pair device with pairing code" dialog is open, returns its port. */
    public static int discoverPairingPort(Context ctx, int timeoutSec) throws InterruptedException {
        return discover(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutSec);
    }

    private static int discover(Context ctx, String type, int timeoutSec) throws InterruptedException {
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(ctx, type, (InetAddress host, int p) -> {
            if (p > 0) {
                port.set(p);
                latch.countDown();
            }
        });
        mdns.start();
        try {
            latch.await(timeoutSec, TimeUnit.SECONDS);
        } finally {
            mdns.stop();
        }
        return port.get();
    }

    public static boolean pair(Context ctx, int port, String code) throws Exception {
        return AdbManager.get(ctx).pair(HOST, port, code.trim());
    }

    public static final String LOG = "/data/local/tmp/aspar_mapper.log";
    /** "serve[r]" keeps pkill/pgrep from matching the shell command line that contains the pattern. */
    private static final String PATTERN = "'ir.aspar.mapper.serve[r].Server'";

    private static AdbManager connect(Context ctx, int manualPort) throws Exception {
        AdbManager adb = AdbManager.get(ctx);
        boolean connected = adb.isConnected();
        if (!connected) {
            if (manualPort > 0) {
                connected = adb.connect(HOST, manualPort);
            } else {
                connected = adb.connectTls(ctx, 15_000);
            }
        }
        if (!connected) {
            throw new IllegalStateException("اتصال به Wireless debugging برقرار نشد. مطمئن شو روشن است و گوشی به Wi-Fi وصل است.");
        }
        return adb;
    }

    private static void disconnect(AdbManager adb) {
        try {
            adb.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** Runs a shell command and returns everything it printed (stops at the __END__ marker or timeout). */
    private static String runShell(AdbManager adb, String cmd, long timeoutMs) throws Exception {
        final String full = cmd + "; echo __END__";
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final AdbStream stream = adb.openStream("shell:" + full);
        Thread reader = new Thread(() -> {
            try {
                InputStream in = stream.openInputStream();
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) > 0) {
                    synchronized (buf) {
                        buf.write(b, 0, n);
                        if (buf.toString("UTF-8").contains("__END__")) break;
                    }
                }
            } catch (Exception ignored) {
            }
        }, "adb-shell-read");
        reader.start();
        reader.join(timeoutMs);
        try {
            stream.close();
        } catch (Exception ignored) {
        }
        synchronized (buf) {
            return buf.toString("UTF-8").replace("__END__", "").replace("\r", "").trim();
        }
    }

    /**
     * Connects to Wireless debugging (it must be turned on), (re)starts the helper and disconnects.
     * @param manualPort port shown under "IP address & Port" in Wireless debugging, or 0 to find it automatically
     * @return a human readable result
     */
    public static String launchServer(Context ctx, int manualPort) throws Exception {
        AdbManager adb = connect(ctx, manualPort);
        try {
            String token = Prefs.newToken(ctx);
            String apk = ctx.getApplicationInfo().sourceDir;
            String cmd = "pkill -f " + PATTERN + "; sleep 0.3; "
                    + "if command -v setsid >/dev/null 2>&1; then S=setsid; else S=nohup; fi; "
                    + "CLASSPATH=" + apk + " $S app_process /system/bin ir.aspar.mapper.server.Server "
                    + SERVER_PORT + " " + token + " > " + LOG + " 2>&1 < /dev/null & "
                    + "sleep 2; P=$(pgrep -f " + PATTERN + "); "
                    + "if [ -n \"$P\" ]; then echo \"OK pid=$P\"; else echo FAILED; fi; "
                    + "tail -n 12 " + LOG;
            String output = runShell(adb, cmd, 12_000);
            if (!output.contains("OK pid=")) {
                throw new IllegalStateException("سرویس اجرا نشد:\n" + output);
            }
            return output;
        } finally {
            disconnect(adb);
        }
    }

    /** Collects everything useful for finding out why the helper does not answer. */
    public static String diagnose(Context ctx, int manualPort) throws Exception {
        AdbManager adb = connect(ctx, manualPort);
        try {
            String cmd = "echo \"== android $(getprop ro.build.version.release) sdk $(getprop ro.build.version.sdk) $(getprop ro.product.model)\"; "
                    + "echo \"== setsid: $(command -v setsid) nohup: $(command -v nohup)\"; "
                    + "echo \"== process:\"; pgrep -fl " + PATTERN + " || echo 'not running'; "
                    + "echo \"== port " + SERVER_PORT + " (BAC2) listening:\"; grep -i ':BAC2 ' /proc/net/tcp /proc/net/tcp6 || echo 'no'; "
                    + "echo \"== input devices:\"; grep -E '^(N|H):' /proc/bus/input/devices | tail -n 24; "
                    + "echo \"== log:\"; tail -n 40 " + LOG + " 2>&1";
            return runShell(adb, cmd, 12_000);
        } finally {
            disconnect(adb);
        }
    }
}
