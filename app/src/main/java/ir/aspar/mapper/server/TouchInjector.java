package ir.aspar.mapper.server;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Injects virtual multi-touch "fingers" into the system, as if the screen was touched.
 * Every finger is identified by a caller-chosen key; the injector maps it to a free
 * MotionEvent pointer id and keeps all fingers in a single consistent gesture stream.
 */
final class TouchInjector {

    private static final int INJECT_MODE_ASYNC = 0;
    private static final int MAX_FINGERS = 10;

    private final Object inputManager;
    private final Method injectMethod;

    private static final class Finger {
        final int key;
        final int pointerId;
        float x;
        float y;

        Finger(int key, int pointerId, float x, float y) {
            this.key = key;
            this.pointerId = pointerId;
            this.x = x;
            this.y = y;
        }
    }

    private final List<Finger> fingers = new ArrayList<>();
    private long downTime;

    TouchInjector() throws Exception {
        Object im;
        Method m;
        try {
            // Android 14+
            Class<?> cls = Class.forName("android.hardware.input.InputManagerGlobal");
            im = cls.getMethod("getInstance").invoke(null);
            m = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Throwable t) {
            // Android 11 - 13
            Class<?> cls = Class.forName("android.hardware.input.InputManager");
            Method getInstance = cls.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            im = getInstance.invoke(null);
            m = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        inputManager = im;
        injectMethod = m;
        Log.i("touch injector ready (" + inputManager.getClass().getName() + ")");
    }

    synchronized boolean isDown(int key) {
        return indexOf(key) >= 0;
    }

    synchronized void down(int key, float x, float y) {
        if (indexOf(key) >= 0) {
            move(key, x, y);
            return;
        }
        if (fingers.size() >= MAX_FINGERS) {
            Log.w("too many fingers, ignoring down for " + key);
            return;
        }
        fingers.add(new Finger(key, freePointerId(), x, y));
        int index = fingers.size() - 1;
        long now = SystemClock.uptimeMillis();
        int action;
        if (fingers.size() == 1) {
            downTime = now;
            action = MotionEvent.ACTION_DOWN;
        } else {
            action = MotionEvent.ACTION_POINTER_DOWN | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        send(action, now);
    }

    synchronized void move(int key, float x, float y) {
        int index = indexOf(key);
        if (index < 0) return;
        Finger f = fingers.get(index);
        if (f.x == x && f.y == y) return;
        f.x = x;
        f.y = y;
        send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis());
    }

    synchronized void up(int key) {
        int index = indexOf(key);
        if (index < 0) return;
        int action;
        if (fingers.size() == 1) {
            action = MotionEvent.ACTION_UP;
        } else {
            action = MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        send(action, SystemClock.uptimeMillis());
        fingers.remove(index);
    }

    synchronized void releaseAll() {
        while (!fingers.isEmpty()) {
            up(fingers.get(fingers.size() - 1).key);
        }
    }

    private int indexOf(int key) {
        for (int i = 0; i < fingers.size(); i++) {
            if (fingers.get(i).key == key) return i;
        }
        return -1;
    }

    private int freePointerId() {
        for (int id = 0; id < 32; id++) {
            boolean used = false;
            for (Finger f : fingers) {
                if (f.pointerId == id) {
                    used = true;
                    break;
                }
            }
            if (!used) return id;
        }
        return 31;
    }

    private void send(int action, long now) {
        int n = fingers.size();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            Finger f = fingers.get(i);
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = f.pointerId;
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = p;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.x = f.x;
            c.y = f.y;
            c.pressure = 1f;
            c.size = 0.05f;
            c.touchMajor = 12f;
            c.touchMinor = 12f;
            coords[i] = c;
        }
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, n, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            injectMethod.invoke(inputManager, ev, INJECT_MODE_ASYNC);
        } catch (Throwable t) {
            Log.e("inject failed", t);
        } finally {
            ev.recycle();
        }
    }
}
