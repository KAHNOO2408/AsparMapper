package ir.aspar.mapper.server;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns keyboard / mouse events into virtual fingers according to the current key map.
 *
 * Element types (sent by the app as JSON, coordinates already in screen pixels):
 *  - tap:      {type:"tap", key, x, y}              finger is held while the key is held
 *  - toggle:   {type:"toggle", key, x, y}           first press holds the finger down, second press lifts it
 *  - joystick: {type:"joystick", x, y, r, up, down, left, right}
 *  - look:     {type:"look", x, y, sens, sensY, adsSens, adsKey, lim}  mouse drags a finger (camera)
 *  - macro:    {type:"macro", key, steps:[[x,y]..], delay}  one key = several taps in a row
 *  - wheel:    {type:"wheel", steps:[[x,y]..]}      mouse wheel steps through these points (hotbar)
 *  tap "tapMode":"press" = tap on press and tap again on release (hold-to-aim for toggle buttons)
 *
 * Mouse buttons arrive as normal key codes (BTN_LEFT=272, BTN_RIGHT=273, BTN_MIDDLE=274).
 * The mouse wheel is exposed as virtual keys WHEEL_UP / WHEEL_DOWN (short taps).
 */
final class Mapper {

    static final int WHEEL_UP = 0x1001;
    static final int WHEEL_DOWN = 0x1002;

    // finger keys (must not collide with tap element indexes, which start at 100)
    private static final int FINGER_LOOK = 2;
    private static final int FINGER_CURSOR = 3;
    private static final int KEY_LEFTSHIFT = 42;
    private static final int KEY_RIGHTSHIFT = 54;
    private static final int BTN_LEFT = 272;
    private static final int BTN_RIGHT = 273;

    // own mouse cursor (mouse grabbed, clicks become touches) – used in mouse mode inside the game
    private volatile boolean cursorActive;
    private float curX;
    private float curY;
    private boolean cursorPressed;
    private boolean transferring;       // Shift + left button: double-tap every slot the cursor passes
    private float lastTapX;
    private float lastTapY;
    private long lastCursorSent;
    private boolean cursorDirty;
    private float cursorSpeed = 1.5f;
    private float slotSize = 70f;
    private final Set<Integer> held = new HashSet<>(); // every key currently held, in any mode

    private static final class Element {
        String type;
        int key;
        float x;
        float y;
        float r;
        int up;
        int down;
        int left;
        int right;
        float sens;
        float lim;
        int finger;
        boolean cursor;       // key also switches game mode <-> free mouse
        boolean autoSprint;   // joystick: forward alone goes to the sprint point
        int sprintKey;        // joystick: key that sprints while held
        float sprintR;        // joystick: distance of the sprint point (straight up)
        boolean sprinting;
        boolean sprintLocked;   // double-tapped forward: finger stays on the sprint point
        boolean swallowUp;      // forward key pressed only to stop sprinting: ignore until released
        long lastUpRelease;
        String tapMode = "hold";
        float sensY = 1f;       // look: vertical speed relative to horizontal
        float adsSens;          // look: sensitivity while adsKey is held (aiming)
        int adsKey = -1;
        float[][] steps = new float[0][];
        long delay = 120;
        int cycle = -1;         // wheel: current slot
        float ex;               // swipe: end point
        float ey;
        long dur = 250;
    }

    private final java.util.concurrent.ExecutorService taps = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int layoutKey = -1; // tells the app to switch to the next layout

    private final TouchInjector touch;
    private DeviceManager devices;
    private ControlServer control;

    private volatile boolean gameMode;
    private int toggleKey = 41; // KEY_GRAVE ( ` )
    private int hideKey = -1;   // tells the app to show/hide its floating button
    private float screenW = 2340;
    private float screenH = 1080;

    private final List<Element> elements = new ArrayList<>();
    private final Set<Integer> pressed = new HashSet<>();

    // camera (mouse look) state
    private float lookX;
    private float lookY;
    private long lastLookMove;
    private int pendingDx;
    private int pendingDy;

    Mapper(TouchInjector touch) {
        this.touch = touch;
        Thread idle = new Thread(this::idleLoop, "look-idle");
        idle.setDaemon(true);
        idle.start();
    }

    void setDeviceManager(DeviceManager d) {
        devices = d;
    }

    void setControlServer(ControlServer c) {
        control = c;
    }

    boolean isGameMode() {
        return gameMode;
    }

    boolean isCursorActive() {
        return cursorActive;
    }

    void setCursorActive(boolean on) {
        synchronized (this) {
            if (cursorActive == on) return;
            cursorActive = on;
            touch.up(FINGER_CURSOR);
            cursorPressed = false;
            transferring = false;
            if (on) {
                curX = screenW / 2f;
                curY = screenH / 2f;
            }
        }
        Log.i("own cursor = " + on);
        if (devices != null) devices.applyGrabState();
        sendCursor(true);
    }

    private void sendCursor(boolean force) {
        if (control == null) return;
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastCursorSent < 8) {
            cursorDirty = true;
            return;
        }
        lastCursorSent = now;
        cursorDirty = false;
        try {
            JSONObject o = new JSONObject();
            o.put("event", "cursor");
            o.put("x", curX);
            o.put("y", curY);
            o.put("on", cursorActive && !gameMode);
            control.sendJson(o);
        } catch (Exception ignored) {
        }
    }

    private boolean ownCursor() {
        return cursorActive && !gameMode;
    }

    private void cursorMove(int dx, int dy) {
        curX = Math.max(0, Math.min(screenW - 1, curX + dx * cursorSpeed));
        curY = Math.max(0, Math.min(screenH - 1, curY + dy * cursorSpeed));
        if (!transferring && cursorPressed) {
            touch.move(FINGER_CURSOR, curX, curY);
        }
        sendCursor(false);
    }

    /** Shift + left button held: double-tap under the cursor again and again, very fast. */
    private void transferLoop() {
        final int f = FINGER_CURSOR + 22;
        while (true) {
            float x;
            float y;
            synchronized (this) {
                if (!transferring || !ownCursor()) break;
                x = curX;
                y = curY;
            }
            for (int i = 0; i < 2; i++) {
                synchronized (this) {
                    touch.down(f, x, y);
                }
                SystemClock.sleep(22);
                synchronized (this) {
                    touch.up(f);
                }
                SystemClock.sleep(i == 0 ? 35 : 45);
            }
        }
        synchronized (this) {
            touch.up(f);
        }
    }

    private void doubleTapAtCursor() {
        lastTapX = curX;
        lastTapY = curY;
        final float x = curX;
        final float y = curY;
        taps.execute(() -> {
            for (int i = 0; i < 2; i++) {
                synchronized (this) {
                    touch.down(FINGER_CURSOR + 20, x, y);
                }
                SystemClock.sleep(30);
                synchronized (this) {
                    touch.up(FINGER_CURSOR + 20);
                }
                SystemClock.sleep(50);
            }
        });
    }

    /** Mouse buttons while our own cursor is shown. Returns true when handled. */
    private boolean cursorButton(int code, boolean down) {
        if (code != BTN_LEFT && code != BTN_RIGHT) return false;
        synchronized (this) {
            if (down) {
                if (code == BTN_LEFT && (held.contains(KEY_LEFTSHIFT) || held.contains(KEY_RIGHTSHIFT))) {
                    if (!transferring) {
                        transferring = true;
                        Thread t = new Thread(this::transferLoop, "transfer");
                        t.setDaemon(true);
                        t.start();
                    }
                } else if (!cursorPressed) {
                    cursorPressed = true;
                    touch.down(FINGER_CURSOR, curX, curY);
                }
            } else {
                if (transferring && code == BTN_LEFT) {
                    transferring = false;
                } else if (cursorPressed) {
                    cursorPressed = false;
                    touch.up(FINGER_CURSOR);
                }
            }
        }
        return true;
    }

    /** Mouse wheel with our own cursor: swipe the list under the cursor. */
    private void cursorScroll(int value) {
        final float x = curX;
        final float y = curY;
        final float dist = Math.min(screenH * 0.12f, 140f) * (value > 0 ? 1 : -1); // wheel up = content down
        taps.execute(() -> {
            int f = FINGER_CURSOR + 21;
            synchronized (this) {
                touch.down(f, x, y);
            }
            for (int i = 1; i <= 4; i++) {
                SystemClock.sleep(12);
                synchronized (this) {
                    touch.move(f, x, y + dist * i / 4f);
                }
            }
            SystemClock.sleep(12);
            synchronized (this) {
                touch.up(f);
            }
        });
    }

    // ------------------------------------------------------------------ config

    synchronized void setConfig(JSONObject cfg) {
        touch.releaseAll();
        pressed.clear();
        elements.clear();
        toggleKey = cfg.optInt("toggleKey", 41);
        hideKey = cfg.optInt("hideKey", -1);
        cursorSpeed = (float) cfg.optDouble("cursorSpeed", 1.5);
        slotSize = (float) cfg.optDouble("slotSize", 70);
        layoutKey = cfg.optInt("layoutKey", -1);
        screenW = (float) cfg.optDouble("w", screenW);
        screenH = (float) cfg.optDouble("h", screenH);
        JSONArray arr = cfg.optJSONArray("elements");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Element e = new Element();
                e.type = o.optString("type", "tap");
                e.key = o.optInt("key", -1);
                e.x = (float) o.optDouble("x", 0);
                e.y = (float) o.optDouble("y", 0);
                e.r = (float) o.optDouble("r", 150);
                e.up = o.optInt("up", 17);
                e.down = o.optInt("down", 31);
                e.left = o.optInt("left", 30);
                e.right = o.optInt("right", 32);
                e.sens = (float) o.optDouble("sens", 1.0);
                e.lim = (float) o.optDouble("lim", 300);
                e.cursor = o.optBoolean("cursor", false);
                e.autoSprint = o.optBoolean("autoSprint", false);
                e.sprintKey = o.optInt("sprintKey", -1);
                e.sprintR = (float) o.optDouble("sprintR", e.r * 2.5);
                e.tapMode = o.optString("tapMode", "hold");
                e.sensY = (float) o.optDouble("sensY", 1.0);
                e.adsSens = (float) o.optDouble("adsSens", e.sens);
                e.adsKey = o.optInt("adsKey", -1);
                e.delay = o.optLong("delay", 120);
                e.ex = (float) o.optDouble("ex", e.x);
                e.ey = (float) o.optDouble("ey", e.y);
                e.dur = o.optLong("dur", 250);
                JSONArray st = o.optJSONArray("steps");
                if (st != null) {
                    e.steps = new float[st.length()][];
                    for (int k = 0; k < st.length(); k++) {
                        JSONArray pt = st.optJSONArray(k);
                        e.steps[k] = new float[]{(float) pt.optDouble(0), (float) pt.optDouble(1)};
                    }
                }
                if ("look".equals(e.type)) e.finger = FINGER_LOOK;
                else e.finger = 100 + i; // taps, toggles and every joystick get their own finger
                elements.add(e);
            }
        }
        Log.i("config: " + elements.size() + " elements, toggleKey=" + toggleKey + ", screen=" + screenW + "x" + screenH);
    }

    void setGameMode(boolean on) {
        setGameMode(on, "toggle");
    }

    void setGameMode(boolean on, String via) {
        synchronized (this) {
            if (gameMode == on) return;
            touch.up(FINGER_CURSOR);
            cursorPressed = false;
            transferring = false;
            gameMode = on;
            touch.releaseAll();
            pressed.clear();
            pendingDx = pendingDy = 0;
            for (Element e : elements) {
                e.sprintLocked = false;
                e.sprinting = false;
                e.swallowUp = false;
            }
        }
        Log.i("game mode = " + on);
        if (devices != null) devices.applyGrabState();
        if (control != null) {
            try {
                JSONObject o = new JSONObject();
                o.put("event", "mode");
                o.put("value", on);
                o.put("via", via);
                control.sendJson(o);
            } catch (Exception ignored) {
            }
        }
        sendCursor(true);
    }

    // ------------------------------------------------------------------ events

    void onEvent(String device, int type, int code, int value) {
        if (type == DeviceManager.EV_KEY) {
            onKey(code, value);
        } else if (type == DeviceManager.EV_REL) {
            if (code == DeviceManager.REL_X) {
                synchronized (this) {
                    pendingDx += value;
                }
            } else if (code == DeviceManager.REL_Y) {
                synchronized (this) {
                    pendingDy += value;
                }
            } else if (code == DeviceManager.REL_WHEEL && value != 0) {
                if (gameMode) wheel(value > 0 ? WHEEL_UP : WHEEL_DOWN);
                else if (ownCursor()) cursorScroll(value);
            }
        } else if (type == DeviceManager.EV_SYN && code == DeviceManager.SYN_REPORT) {
            flushMouse();
        }
    }

    private void onKey(int code, int value) {
        if (value == 2) return; // auto-repeat
        boolean down = value == 1;
        synchronized (this) {
            if (down) held.add(code);
            else held.remove(code);
        }
        if (ownCursor() && cursorButton(code, down)) return;

        if (layoutKey > 0 && code == layoutKey) {
            if (!down && control != null) control.sendEvent("nextLayout", true);
            return;
        }
        if (hideKey > 0 && code == hideKey) {
            if (!down && control != null) control.sendEvent("hideToggle", true);
            return;
        }
        if (code == toggleKey) {
            // switch on release, so the system always sees a complete press/release pair
            if (!down) setGameMode(!gameMode);
            return;
        }
        // keys marked "cursor": tap the button, then switch game mode <-> free mouse (e.g. loot/inventory)
        Element cursorKey = null;
        synchronized (this) {
            for (Element e : elements) {
                if (e.cursor && e.key == code && ("tap".equals(e.type) || "toggle".equals(e.type))) cursorKey = e;
            }
        }
        if (cursorKey != null) {
            if (down) {
                synchronized (this) {
                    touch.down(cursorKey.finger, cursorKey.x, cursorKey.y);
                }
            } else {
                synchronized (this) {
                    touch.up(cursorKey.finger);
                }
                setGameMode(!gameMode, "loot");
            }
            return;
        }

        if (!gameMode) {
            // swipes also work with the free mouse (e.g. build menus)
            if (down) {
                synchronized (this) {
                    for (Element e : elements) {
                        if ("swipe".equals(e.type) && e.key == code) swipe(e);
                    }
                }
            }
            return;
        }

        synchronized (this) {
            if (down) {
                if (!pressed.add(code)) return;
            } else {
                if (!pressed.remove(code)) return;
            }
            for (Element e : elements) {
                if ("tap".equals(e.type) && e.key == code) {
                    if ("press".equals(e.tapMode)) {
                        // e.g. aim button that toggles in the game: on while the key is held
                        quickTap(e.finger, e.x, e.y);
                    } else if (down) {
                        touch.down(e.finger, e.x, e.y);
                    } else {
                        touch.up(e.finger);
                    }
                } else if ("macro".equals(e.type) && e.key == code) {
                    if (down) runMacro(e);
                } else if ("swipe".equals(e.type) && e.key == code) {
                    if (down) swipe(e);
                } else if ("toggle".equals(e.type) && e.key == code) {
                    // press once = finger stays down, press again = release
                    if (down) {
                        if (touch.isDown(e.finger)) touch.up(e.finger);
                        else touch.down(e.finger, e.x, e.y);
                    }
                } else if ("joystick".equals(e.type)
                        && (code == e.up || code == e.down || code == e.left || code == e.right || code == e.sprintKey)) {
                    joystickKey(e, code, down);
                    updateJoystick(e);
                }
            }
        }
    }

    private static final long DOUBLE_TAP_MS = 350;

    /** Double-tap forward = locked fast run; forward again = stop. Other directions also stop the lock. */
    private void joystickKey(Element e, int code, boolean down) {
        long now = SystemClock.uptimeMillis();
        if (code == e.up) {
            if (down) {
                if (e.sprintLocked) {
                    e.sprintLocked = false;
                    e.swallowUp = true; // this press only stops running
                } else if (e.autoSprint && now - e.lastUpRelease < DOUBLE_TAP_MS) {
                    e.sprintLocked = true;
                }
            } else {
                if (e.swallowUp) e.swallowUp = false;
                else e.lastUpRelease = now;
            }
        } else if (down && e.sprintLocked && (code == e.down || code == e.left || code == e.right)) {
            e.sprintLocked = false;
        }
    }

    private void updateJoystick(Element e) {
        if (e.sprintLocked) {
            float sx = e.x;
            float sy = e.y - e.sprintR;
            if (!touch.isDown(e.finger)) touch.down(e.finger, e.x, e.y);
            if (!e.sprinting) {
                touch.move(e.finger, e.x, e.y - e.r);
                touch.move(e.finger, e.x, e.y - (e.r + e.sprintR) / 2f);
            }
            e.sprinting = true;
            touch.move(e.finger, sx, sy);
            return;
        }
        float dx = 0;
        float dy = 0;
        if (pressed.contains(e.up) && !e.swallowUp) dy -= 1;
        if (pressed.contains(e.down)) dy += 1;
        if (pressed.contains(e.left)) dx -= 1;
        if (pressed.contains(e.right)) dx += 1;
        if (dx == 0 && dy == 0) {
            if (touch.isDown(e.finger)) {
                // slide back to the centre before lifting, so the game does not lock sprint by itself
                touch.move(e.finger, e.x, e.y);
                touch.up(e.finger);
            }
            e.sprinting = false;
            return;
        }
        boolean wantSprint = dx == 0 && dy < 0 && e.sprintKey > 0 && pressed.contains(e.sprintKey);
        float tx;
        float ty;
        if (wantSprint) {
            tx = e.x;
            ty = e.y - e.sprintR;
        } else {
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            tx = e.x + dx / len * e.r;
            ty = e.y + dy / len * e.r;
        }
        if (!touch.isDown(e.finger)) {
            touch.down(e.finger, e.x, e.y);
            // move in steps so on-screen joysticks register the drag reliably
            touch.move(e.finger, e.x + (tx - e.x) * 0.3f, e.y + (ty - e.y) * 0.3f);
        }
        if (wantSprint && !e.sprinting) {
            // drag through the joystick edge up to the sprint point, like a finger would
            touch.move(e.finger, e.x, e.y - e.r);
            touch.move(e.finger, e.x, e.y - (e.r + e.sprintR) / 2f);
        }
        e.sprinting = wantSprint;
        touch.move(e.finger, tx, ty);
    }

    /** A short tap that does not block the input thread. */
    private void quickTap(int finger, float x, float y) {
        taps.execute(() -> {
            synchronized (this) {
                touch.down(finger, x, y);
            }
            SystemClock.sleep(45);
            synchronized (this) {
                touch.up(finger);
            }
            SystemClock.sleep(15);
        });
    }

    /** Drags a finger from the start point to the end point, like a real swipe. */
    private void swipe(Element e) {
        final int f = e.finger;
        final float x0 = e.x, y0 = e.y, x1 = e.ex, y1 = e.ey;
        final int steps = (int) Math.max(4, Math.min(60, e.dur / 12));
        final long stepMs = Math.max(4, e.dur / steps);
        taps.execute(() -> {
            synchronized (this) {
                touch.down(f, x0, y0);
            }
            for (int i = 1; i <= steps; i++) {
                SystemClock.sleep(stepMs);
                float t = i / (float) steps;
                synchronized (this) {
                    touch.move(f, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t);
                }
            }
            SystemClock.sleep(30);
            synchronized (this) {
                touch.up(f);
            }
        });
    }

    private void runMacro(Element e) {
        for (float[] st : e.steps) {
            final float x = st[0];
            final float y = st[1];
            quickTap(e.finger, x, y);
            final long d = Math.max(0, e.delay - 60);
            taps.execute(() -> SystemClock.sleep(d));
        }
    }

    private void wheel(int virtualKey) {
        synchronized (this) {
            // hotbar element: the wheel steps through its slots (down = next, up = previous)
            for (Element e : elements) {
                if ("wheel".equals(e.type) && e.steps.length > 0) {
                    int n = e.steps.length;
                    int dir = virtualKey == WHEEL_DOWN ? 1 : -1;
                    e.cycle = e.cycle < 0 ? (dir > 0 ? 0 : n - 1) : ((e.cycle + dir) % n + n) % n;
                    quickTap(e.finger, e.steps[e.cycle][0], e.steps[e.cycle][1]);
                    return;
                }
            }
            for (Element e : elements) {
                if ("tap".equals(e.type) && e.key == virtualKey) {
                    quickTap(e.finger, e.x, e.y);
                    return;
                }
            }
        }
    }

    private synchronized void flushMouse() {
        int dx = pendingDx;
        int dy = pendingDy;
        pendingDx = pendingDy = 0;
        if (dx == 0 && dy == 0) return;
        if (ownCursor()) {
            cursorMove(dx, dy);
            return;
        }
        if (!gameMode) return;
        Element look = null;
        for (Element e : elements) {
            if ("look".equals(e.type)) look = e;
        }
        if (look == null) return;

        if (!touch.isDown(look.finger)) {
            lookX = look.x;
            lookY = look.y;
            touch.down(look.finger, lookX, lookY);
        }
        boolean aiming = look.adsKey > 0 && pressed.contains(look.adsKey);
        float sx = aiming ? look.adsSens : look.sens;
        float sy = sx * look.sensY;
        float nx = lookX + dx * sx;
        float ny = lookY + dy * sy;
        boolean outOfRange = Math.abs(nx - look.x) > look.lim || Math.abs(ny - look.y) > look.lim
                || nx < 2 || ny < 2 || nx > screenW - 2 || ny > screenH - 2;
        if (outOfRange) {
            // lift the finger and put it back in the middle of the look area
            touch.up(look.finger);
            lookX = look.x;
            lookY = look.y;
            touch.down(look.finger, lookX, lookY);
            nx = lookX + dx * sx;
            ny = lookY + dy * sy;
        }
        lookX = nx;
        lookY = ny;
        touch.move(look.finger, lookX, lookY);
        lastLookMove = SystemClock.uptimeMillis();
    }

    /** Lift the camera finger when the mouse stops, so it does not block other touches. */
    private void idleLoop() {
        while (true) {
            SystemClock.sleep(16);
            synchronized (this) {
                if (touch.isDown(FINGER_LOOK) && SystemClock.uptimeMillis() - lastLookMove > 250) {
                    touch.up(FINGER_LOOK);
                }
                if (cursorDirty) sendCursor(true);
            }
        }
    }
}
