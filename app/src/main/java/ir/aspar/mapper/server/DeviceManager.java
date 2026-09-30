package ir.aspar.mapper.server;


import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds physical mice and keyboards in /proc/bus/input/devices, reads their raw evdev events
 * and grabs them exclusively while "game mode" is on (so the system cursor and the game do not
 * also receive the clicks and key presses).
 */
final class DeviceManager {

    static final int EV_SYN = 0x00;
    static final int EV_KEY = 0x01;
    static final int EV_REL = 0x02;
    static final int SYN_REPORT = 0;
    static final int REL_X = 0x00;
    static final int REL_Y = 0x01;
    static final int REL_WHEEL = 0x08;

    private static final int EVIOCGRAB = 0x40044590;
    private static final int EVENT_SIZE = android.os.Process.is64Bit() ? 24 : 16;

    private final Mapper mapper;
    private final Map<String, Reader> readers = new HashMap<>();
    private volatile boolean running = true;

    DeviceManager(Mapper mapper) {
        this.mapper = mapper;
    }

    void start() {
        Thread scanner = new Thread(() -> {
            while (running) {
                try {
                    scan();
                } catch (Throwable t) {
                    Log.e("scan", t);
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "device-scan");
        scanner.setDaemon(true);
        scanner.start();
    }

    /** Re-open every device so the grab state follows the current mode. */
    synchronized void applyGrabState() {
        for (Reader r : readers.values()) r.reopen();
    }

    synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Reader r : readers.values()) out.add(r.name + (r.isMouse ? " [mouse]" : "") + (r.isKeyboard ? " [keyboard]" : ""));
        return out;
    }

    private String lastListing = null;
    private long lastScan = 0;

    private synchronized void scan() throws IOException {
        // getevent is relatively expensive, so only rescan when /dev/input changes
        String[] names = new java.io.File("/dev/input").list();
        String listing = names == null ? "?" : String.join(",", new java.util.TreeSet<>(java.util.Arrays.asList(names)));
        long now = System.currentTimeMillis();
        if (listing.equals(lastListing) && (!"?".equals(listing) || now - lastScan < 10_000)) return;
        lastListing = listing;
        lastScan = now;

        List<Info> found = parseDevices();
        Map<String, Info> wanted = new HashMap<>();
        for (Info info : found) {
            if (info.isMouse || info.isKeyboard) wanted.put(info.path, info);
        }
        Log.i("scan: " + found.size() + " input devices, " + wanted.size() + " mouse/keyboard");
        // remove readers of devices that disappeared
        List<String> gone = new ArrayList<>();
        for (String path : readers.keySet()) if (!wanted.containsKey(path)) gone.add(path);
        for (String path : gone) {
            Reader r = readers.remove(path);
            if (r != null) {
                Log.i("device removed: " + r.name);
                r.stop();
            }
        }
        // start readers for new devices
        for (Info info : wanted.values()) {
            if (readers.containsKey(info.path)) continue;
            Log.i("device added: " + info.name + " " + info.path + " mouse=" + info.isMouse + " kbd=" + info.isKeyboard);
            Reader r = new Reader(info);
            readers.put(info.path, r);
            r.start();
        }
    }

    // ---------------------------------------------------------------- parsing

    private static final class Info {
        String name = "";
        String path;
        boolean isMouse;
        boolean isKeyboard;
    }

    private static List<Info> parseDevices() {
        try {
            return parseProc();
        } catch (IOException e) {
            // Android 15/16: /proc/bus/input/devices is not readable by the shell any more
            return parseGetevent();
        }
    }

    private static List<Info> parseGetevent() {
        List<Info> list = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("getevent", "-pl").redirectErrorStream(true).start();
            final java.io.InputStream in = p.getInputStream();
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(in))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "getevent-read");
            t.setDaemon(true);
            t.start();
            // getevent -p prints the device list, then keeps waiting for events: stop it
            t.join(1500);
        } catch (Exception e) {
            Log.e("getevent", e);
        } finally {
            if (p != null) p.destroy();
        }
        String text;
        synchronized (out) {
            text = out.toString();
        }
        for (GeteventParser.Device d : GeteventParser.parse(text)) {
            if (d.isPhoneInternal()) continue;
            Info info = new Info();
            info.name = d.name;
            info.path = d.path;
            info.isMouse = d.isMouse();
            info.isKeyboard = d.isKeyboard();
            list.add(info);
        }
        if (list.isEmpty()) Log.w("getevent found no devices; output was:\n" + text);
        return list;
    }

    private static List<Info> parseProc() throws IOException {
        List<Info> list = new ArrayList<>();
        String name = "";
        String handlers = "";
        String key = "";
        String rel = "";
        String abs = "";
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            String line;
            while (true) {
                line = br.readLine();
                if (line == null || line.trim().isEmpty()) {
                    if (!handlers.isEmpty()) {
                        Info info = classify(name, handlers, key, rel, abs);
                        if (info != null) list.add(info);
                    }
                    name = handlers = key = rel = abs = "";
                    if (line == null) break;
                    continue;
                }
                if (line.startsWith("N: Name=")) {
                    name = line.substring(8).replace("\"", "");
                } else if (line.startsWith("H: Handlers=")) {
                    handlers = line.substring(12);
                } else if (line.startsWith("B: KEY=")) {
                    key = line.substring(7);
                } else if (line.startsWith("B: REL=")) {
                    rel = line.substring(7);
                } else if (line.startsWith("B: ABS=")) {
                    abs = line.substring(7);
                }
            }
        }
        return list;
    }

    private static Info classify(String name, String handlers, String key, String rel, String abs) {
        String event = null;
        for (String h : handlers.trim().split("\\s+")) {
            if (h.startsWith("event")) event = h;
        }
        if (event == null) return null;
        String lower = name.toLowerCase();
        // never touch the phone's own touchscreen, pen or buttons
        if (lower.contains("sec_touch") || lower.contains("e-pen") || lower.contains("gpio")
                || lower.contains("uinput") || lower.contains("virtual")) {
            return null;
        }
        Info info = new Info();
        info.name = name;
        info.path = "/dev/input/" + event;
        info.isMouse = hasBit(rel, REL_X) && hasBit(rel, REL_Y) && !hasBit(abs, 0x35 /* ABS_MT_POSITION_X */);
        // a real keyboard has letter keys and the space bar
        info.isKeyboard = hasBit(key, 30 /* KEY_A */) && hasBit(key, 16 /* KEY_Q */) && hasBit(key, 57 /* KEY_SPACE */);
        return info;
    }

    /** The kernel prints bitmaps as space separated hex words, most significant word first. */
    private static boolean hasBit(String bitmap, int bit) {
        if (bitmap == null || bitmap.trim().isEmpty()) return false;
        String[] words = bitmap.trim().split("\\s+");
        int bitsPerWord = android.os.Process.is64Bit() ? 64 : 32;
        int wordIndex = bit / bitsPerWord;
        if (wordIndex >= words.length) return false;
        String word = words[words.length - 1 - wordIndex];
        try {
            long value = Long.parseUnsignedLong(word, 16);
            return ((value >>> (bit % bitsPerWord)) & 1L) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- grab

    private static boolean grab(FileDescriptor fd) {
        // EVIOCGRAB grabs when its argument is non-null. All variants below pass a pointer.
        // These are hidden framework APIs; hidden-API checks do not apply to app_process.
        Throwable last = null;
        try {
            Method m = Class.forName("android.system.Os").getDeclaredMethod("ioctlInt", FileDescriptor.class, int.class);
            m.setAccessible(true);
            m.invoke(null, fd, EVIOCGRAB);
            return true;
        } catch (Throwable t) {
            last = t;
        }
        try {
            Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
            try {
                Method m = os.getClass().getMethod("ioctlInt", FileDescriptor.class, int.class);
                m.setAccessible(true);
                m.invoke(os, fd, EVIOCGRAB);
                return true;
            } catch (NoSuchMethodException e) {
                Class<?> refCls = Class.forName("android.system.Int32Ref");
                Object ref = refCls.getConstructor(int.class).newInstance(1);
                Method m = os.getClass().getMethod("ioctlInt", FileDescriptor.class, int.class, refCls);
                m.setAccessible(true);
                m.invoke(os, fd, EVIOCGRAB, ref);
                return true;
            }
        } catch (Throwable t) {
            Log.e("grab failed (first error: " + last + ")", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- reader

    private final class Reader {
        final String name;
        final String path;
        final boolean isMouse;
        final boolean isKeyboard;
        private volatile boolean alive = true;
        private volatile FileInputStream current;
        private Thread thread;

        Reader(Info info) {
            name = info.name;
            path = info.path;
            isMouse = info.isMouse;
            isKeyboard = info.isKeyboard;
        }

        void start() {
            thread = new Thread(this::loop, "read-" + path);
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            alive = false;
            closeCurrent();
        }

        void reopen() {
            closeCurrent(); // the loop notices and opens the device again
        }

        private void closeCurrent() {
            FileInputStream in = current;
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void loop() {
            byte[] buf = new byte[EVENT_SIZE];
            int failures = 0;
            while (alive && running) {
                boolean wantGrab = mapper.isGameMode();
                FileInputStream in;
                try {
                    in = new FileInputStream(path);
                } catch (IOException e) {
                    Log.w("cannot open " + path + ": " + e);
                    if (++failures > 5) {
                        alive = false;
                        break;
                    }
                    sleep(500);
                    continue;
                }
                current = in;
                boolean grabbed = false;
                try {
                    if (wantGrab) grabbed = grab(in.getFD());
                    Log.i((grabbed ? "grabbed " : "reading ") + name);
                    while (alive) {
                        readFully(in, buf);
                        int type = (buf[EVENT_SIZE - 8] & 0xff) | ((buf[EVENT_SIZE - 7] & 0xff) << 8);
                        int code = (buf[EVENT_SIZE - 6] & 0xff) | ((buf[EVENT_SIZE - 5] & 0xff) << 8);
                        int value = (buf[EVENT_SIZE - 4] & 0xff) | ((buf[EVENT_SIZE - 3] & 0xff) << 8)
                                | ((buf[EVENT_SIZE - 2] & 0xff) << 16) | ((buf[EVENT_SIZE - 1] & 0xff) << 24);
                        mapper.onEvent(path, type, code, value);
                        failures = 0;
                    }
                } catch (Throwable t) {
                    // closed on purpose (mode change) or device unplugged
                } finally {
                    current = null;
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                if (!new java.io.File(path).exists()) break;
            }
            Log.i("reader finished: " + name);
        }

        private void readFully(FileInputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
