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

    /**
     * Connects to Wireless debugging (it must be turned on), (re)starts the helper and disconnects.
     * @param manualPort port shown under "IP address & Port" in Wireless debugging, or 0 to find it automatically
     * @return a human readable result
     */
    public static String launchServer(Context ctx, int manualPort) throws Exception {
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

        String token = Prefs.newToken(ctx);
        String apk = ctx.getApplicationInfo().sourceDir;
        String log = "/data/local/tmp/aspar_mapper.log";
        // "serve[r]" keeps pkill/pgrep from matching this very shell command line.
        String pattern = "'ir.aspar.mapper.serve[r].Server'";
        String cmd = "pkill -f " + pattern + "; sleep 0.3; "
                + "if command -v setsid >/dev/null 2>&1; then S=setsid; else S=nohup; fi; "
                + "CLASSPATH=" + apk + " $S app_process /system/bin ir.aspar.mapper.server.Server "
                + SERVER_PORT + " " + token + " > " + log + " 2>&1 < /dev/null & "
                + "sleep 1.5; P=$(pgrep -f " + pattern + "); "
                + "if [ -n \"$P\" ]; then echo \"OK pid=$P\"; else echo FAILED; tail -n 15 " + log + "; fi; echo __END__";

        String output;
        try (AdbStream stream = adb.openStream("shell:" + cmd)) {
            InputStream in = stream.openInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            long deadline = System.currentTimeMillis() + 10_000;
            int n;
            while (System.currentTimeMillis() < deadline && (n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.toString("UTF-8").contains("__END__")) break;
            }
            output = buf.toString("UTF-8").replace("__END__", "").trim();
        }
        try {
            adb.disconnect();
        } catch (Exception ignored) {
        }
        if (!output.startsWith("OK")) {
            throw new IllegalStateException("سرویس اجرا نشد:\n" + output);
        }
        return output;
    }
}
