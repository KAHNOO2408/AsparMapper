package ir.aspar.mapper;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ir.aspar.mapper.adb.Activator;

/** Keeps a connection to the helper process and reconnects automatically. */
public final class ServerClient {

    public interface Listener {
        void onConnectionChanged(boolean connected, String info);

        void onGameModeChanged(boolean gameMode);

        void onDevices(java.util.List<String> devices);

        void onForeground(String pkg);

        void onHideToggle();

        void onNextLayout();
    }

    public static final int EXPECTED_VERSION = 2;

    private final android.content.Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean connected;
    private volatile boolean gameMode;
    private volatile JSONObject pendingConfig;
    private volatile String lastError = "";

    public ServerClient(android.content.Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        Thread t = new Thread(this::loop, "server-client");
        t.setDaemon(true);
        t.start();
    }

    public boolean isConnected() {
        return connected;
    }

    /** Why the last connection attempt failed (for the diagnostics text). */
    public String lastError() {
        return lastError;
    }

    public boolean isGameMode() {
        return gameMode;
    }

    public void close() {
        running = false;
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
        sender.shutdownNow();
    }

    public void sendConfig(JSONObject cfg) {
        pendingConfig = cfg;
        send(cfg);
    }

    public void setGameMode(boolean on) {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", "mode");
            o.put("game", on);
            send(o);
        } catch (Exception ignored) {
        }
    }

    public void requestDevices() {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", "devices");
            send(o);
        } catch (Exception ignored) {
        }
    }

    public void quitServer() {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", "quit");
            send(o);
        } catch (Exception ignored) {
        }
    }

    private void send(JSONObject o) {
        sender.execute(() -> {
            OutputStream os = out;
            if (os == null) return;
            try {
                os.write((o + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception e) {
                closeSocket();
            }
        });
    }

    private void closeSocket() {
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    private void loop() {
        while (running) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", Activator.SERVER_PORT), 1000);
                s.setTcpNoDelay(true);
                socket = s;
                OutputStream os = s.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                JSONObject hello = new JSONObject();
                hello.put("cmd", "hello");
                hello.put("token", Prefs.token(ctx));
                os.write((hello + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                JSONObject res = new JSONObject(in.readLine());
                if (!res.optBoolean("ok")) {
                    lastError = "server rejected token";
                    post(false, "سرویس قدیمی در حال اجراست؛ دوباره «فعال‌سازی» را بزن.");
                    sleep(3000);
                    continue;
                }
                int version = res.optInt("version");
                gameMode = res.optBoolean("game");
                String fg0 = res.optString("foreground", "");
                if (!fg0.isEmpty()) main.post(() -> listener.onForeground(fg0));
                out = os;
                connected = true;
                lastError = "";
                post(true, version == EXPECTED_VERSION ? "وصل است" : "نسخه سرویس قدیمی است؛ دوباره فعال‌سازی کن");
                main.post(() -> listener.onGameModeChanged(gameMode));
                JSONObject cfg = pendingConfig;
                if (cfg != null) send(cfg);

                String line;
                while (running && (line = in.readLine()) != null) {
                    JSONObject msg = new JSONObject(line);
                    org.json.JSONArray devs = msg.optJSONArray("devices");
                    if (devs != null) {
                        java.util.List<String> list = new java.util.ArrayList<>();
                        for (int i = 0; i < devs.length(); i++) list.add(devs.optString(i));
                        main.post(() -> listener.onDevices(list));
                    }
                    if ("nextLayout".equals(msg.optString("event"))) {
                        main.post(listener::onNextLayout);
                    }
                    if ("hideToggle".equals(msg.optString("event"))) {
                        main.post(listener::onHideToggle);
                    }
                    if ("foreground".equals(msg.optString("event"))) {
                        String pkg = msg.optString("value");
                        main.post(() -> listener.onForeground(pkg));
                    }
                    if ("mode".equals(msg.optString("event"))) {
                        gameMode = msg.optBoolean("value");
                        boolean gm = gameMode;
                        main.post(() -> listener.onGameModeChanged(gm));
                    }
                }
            } catch (Exception e) {
                // not running yet, or it went away
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                out = null;
                socket = null;
                if (connected) {
                    connected = false;
                    gameMode = false;
                    post(false, "قطع شد");
                    main.post(() -> listener.onGameModeChanged(false));
                }
            }
            sleep(1500);
        }
    }

    private void post(boolean c, String info) {
        main.post(() -> listener.onConnectionChanged(c, info));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
