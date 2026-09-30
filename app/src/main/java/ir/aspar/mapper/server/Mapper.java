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
 *  - look:     {type:"look", x, y, sens, lim}       mouse movement drags a finger (camera)
 *
 * Mouse buttons arrive as normal key codes (BTN_LEFT=272, BTN_RIGHT=273, BTN_MIDDLE=274).
 * The mouse wheel is exposed as virtual keys WHEEL_UP / WHEEL_DOWN (short taps).
 */
final class Mapper {

    static final int WHEEL_UP = 0x1001;
    static final int WHEEL_DOWN = 0x1002;

    // finger keys (must not collide with tap element indexes, which start at 100)
    private static final int FINGER_LOOK = 2;

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
    }

    private final TouchInjector touch;
    private DeviceManager devices;
    private ControlServer control;

    private volatile boolean gameMode;
    private int toggleKey = 41; // KEY_GRAVE ( ` )
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

    // ------------------------------------------------------------------ config

    synchronized void setConfig(JSONObject cfg) {
        touch.releaseAll();
        pressed.clear();
        elements.clear();
        toggleKey = cfg.optInt("toggleKey", 41);
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
                if ("look".equals(e.type)) e.finger = FINGER_LOOK;
                else e.finger = 100 + i; // taps, toggles and every joystick get their own finger
                elements.add(e);
            }
        }
        Log.i("config: " + elements.size() + " elements, toggleKey=" + toggleKey + ", screen=" + screenW + "x" + screenH);
    }

    void setGameMode(boolean on) {
        synchronized (this) {
            if (gameMode == on) return;
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
        if (control != null) control.sendEvent("mode", on);
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
            } else if (code == DeviceManager.REL_WHEEL && value != 0 && gameMode) {
                wheel(value > 0 ? WHEEL_UP : WHEEL_DOWN);
            }
        } else if (type == DeviceManager.EV_SYN && code == DeviceManager.SYN_REPORT) {
            flushMouse();
        }
    }

    private void onKey(int code, int value) {
        if (value == 2) return; // auto-repeat
        boolean down = value == 1;

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
                setGameMode(!gameMode);
            }
            return;
        }

        if (!gameMode) return;

        synchronized (this) {
            if (down) {
                if (!pressed.add(code)) return;
            } else {
                if (!pressed.remove(code)) return;
            }
            for (Element e : elements) {
                if ("tap".equals(e.type) && e.key == code) {
                    if (down) touch.down(e.finger, e.x, e.y);
                    else touch.up(e.finger);
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

    private void wheel(int virtualKey) {
        Element target = null;
        synchronized (this) {
            for (Element e : elements) {
                if ("tap".equals(e.type) && e.key == virtualKey) target = e;
            }
            if (target == null) return;
            touch.down(target.finger, target.x, target.y);
        }
        SystemClock.sleep(40);
        synchronized (this) {
            touch.up(target.finger);
        }
    }

    private synchronized void flushMouse() {
        int dx = pendingDx;
        int dy = pendingDy;
        pendingDx = pendingDy = 0;
        if (!gameMode || (dx == 0 && dy == 0)) return;
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
        float nx = lookX + dx * look.sens;
        float ny = lookY + dy * look.sens;
        boolean outOfRange = Math.abs(nx - look.x) > look.lim || Math.abs(ny - look.y) > look.lim
                || nx < 2 || ny < 2 || nx > screenW - 2 || ny > screenH - 2;
        if (outOfRange) {
            // lift the finger and put it back in the middle of the look area
            touch.up(look.finger);
            lookX = look.x;
            lookY = look.y;
            touch.down(look.finger, lookX, lookY);
            nx = lookX + dx * look.sens;
            ny = lookY + dy * look.sens;
        }
        lookX = nx;
        lookY = ny;
        touch.move(look.finger, lookX, lookY);
        lastLookMove = SystemClock.uptimeMillis();
    }

    /** Lift the camera finger when the mouse stops, so it does not block other touches. */
    private void idleLoop() {
        while (true) {
            SystemClock.sleep(50);
            synchronized (this) {
                if (touch.isDown(FINGER_LOOK) && SystemClock.uptimeMillis() - lastLookMove > 250) {
                    touch.up(FINGER_LOOK);
                }
            }
        }
    }
}
