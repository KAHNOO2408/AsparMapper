package ir.aspar.mapper.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Line-based JSON control channel on 127.0.0.1. Only a client that knows the random token
 * (given to the server on its command line by the app) may control it.
 */
final class ControlServer {

    private final int port;
    private final String token;
    private final Mapper mapper;
    private volatile OutputStream client;
    private volatile boolean running = true;

    ControlServer(int port, String token, Mapper mapper) {
        this.port = port;
        this.token = token;
        this.mapper = mapper;
    }

    void run() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
            Log.i("control listening on " + port);
            while (running) {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "client");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    void sendEvent(String name, boolean value) {
        OutputStream out = client;
        if (out == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("event", name);
            o.put("value", value);
            synchronized (this) {
                out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            client = null;
        }
    }

    private void handle(Socket s) {
        boolean authed = false;
        OutputStream out = null;
        try {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject req = new JSONObject(line);
                JSONObject res = new JSONObject();
                String cmd = req.optString("cmd");
                if (!authed) {
                    if ("hello".equals(cmd) && token.equals(req.optString("token"))) {
                        authed = true;
                        client = out;
                        res.put("ok", true);
                        res.put("version", Server.VERSION);
                        res.put("game", mapper.isGameMode());
                    } else {
                        res.put("ok", false);
                        res.put("error", "unauthorized");
                        reply(out, res);
                        break;
                    }
                } else if ("config".equals(cmd)) {
                    mapper.setConfig(req);
                    res.put("ok", true);
                } else if ("mode".equals(cmd)) {
                    mapper.setGameMode(req.optBoolean("game"));
                    res.put("ok", true);
                } else if ("devices".equals(cmd)) {
                    DeviceManagerHolder.describeInto(res);
                    res.put("ok", true);
                } else if ("ping".equals(cmd)) {
                    res.put("ok", true);
                } else if ("quit".equals(cmd)) {
                    res.put("ok", true);
                    reply(out, res);
                    mapper.setGameMode(false);
                    Log.i("quit requested");
                    System.exit(0);
                } else {
                    res.put("ok", false);
                    res.put("error", "unknown command");
                }
                reply(out, res);
            }
        } catch (Exception e) {
            Log.w("client disconnected: " + e);
        } finally {
            if (client == out) client = null;
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private synchronized void reply(OutputStream out, JSONObject res) throws IOException {
        out.write((res + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Small indirection so the device list can be reported without a hard reference cycle. */
    static final class DeviceManagerHolder {
        static DeviceManager instance;

        static void describeInto(JSONObject res) throws Exception {
            JSONArray arr = new JSONArray();
            if (instance != null) for (String d : instance.describe()) arr.put(d);
            res.put("devices", arr);
        }
    }
}
