package ir.aspar.mapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The user's key layout. Positions are stored as fractions of the landscape screen
 * (fx = x / width, fy = y / height) so they survive resolution changes.
 * Sizes (joystick radius, camera range) are fractions of the screen height.
 */
public final class KeyMap {

    public static final String TAP = "tap";
    public static final String JOYSTICK = "joystick";
    public static final String LOOK = "look";
    public static final String TOGGLE = "toggle";
    public static final String MACRO = "macro";
    public static final String WHEEL = "wheel";
    public static final String SWIPE = "swipe";

    public static final class Element {
        public String type = TAP;
        public int key = -1;
        public float fx;
        public float fy;
        public float size = 0.16f;  // joystick radius or camera range, as fraction of height
        public float sens = 1.0f;   // camera sensitivity
        public int up = 17, down = 31, left = 30, right = 32; // W S A D
        public String note = "";
        public float scale = 1.0f;       // on-screen size of a key circle (1 = normal)
        public boolean autoSprint = true; // joystick: forward alone pushes the finger up to the sprint-lock point
        public int sprintKey = 15;       // joystick: holding this key (Tab) + forward also sprints
        public float sprintDist = 2.5f;  // joystick: sprint point distance, in joystick radiuses, straight up
        public boolean cursor = false;   // key: after pressing it, switch between game mode and free mouse
        public boolean mapMode = false;  // cursor key opens a map: mouse wheel zooms instead of scrolling
        public boolean menuMode = false; // cursor key opens a menu (craft): plain mouse, wheel scrolls, no quick transfer
        public boolean pressRelease = false; // key: tap on press + tap on release (hold-to-aim with toggle buttons)
        public float sensY = 1.0f;       // look: vertical speed relative to horizontal
        public float adsSens = 0.5f;     // look: sensitivity while the aim key is held
        public int adsKey = KeyNames.BTN_RIGHT;
        public final List<float[]> steps = new ArrayList<>(); // macro/wheel: points as screen fractions
        public int delay = 120;          // macro: ms between steps
        public float ex = 0.6f, ey = 0.5f; // swipe: end point (fractions)
        public int duration = 250;       // swipe: ms from start to end

        public String label() {
            switch (type) {
                case JOYSTICK:
                    if (up == 103 && left == 105 && down == 108 && right == 106) return "↑←↓→";
                    return KeyNames.shortName(up) + KeyNames.shortName(left) + KeyNames.shortName(down) + KeyNames.shortName(right);
                case LOOK:
                    return "🖱";
                case TOGGLE:
                    return KeyNames.shortName(key) + "⏺" + (cursor ? "🖱" : "");
                case MACRO:
                    return KeyNames.shortName(key) + "⚡";
                case WHEEL:
                    return "⇅";
                case SWIPE:
                    return KeyNames.shortName(key) + "↔";
                default:
                    return KeyNames.shortName(key) + (cursor ? "🖱" : "");
            }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("key", key);
            o.put("fx", fx);
            o.put("fy", fy);
            o.put("size", size);
            o.put("sens", sens);
            o.put("up", up);
            o.put("down", down);
            o.put("left", left);
            o.put("right", right);
            o.put("note", note);
            o.put("scale", scale);
            o.put("autoSprint", autoSprint);
            o.put("sprintKey", sprintKey);
            o.put("sprintKeyV2", true);
            o.put("sprintDist", sprintDist);
            o.put("cursor", cursor);
            o.put("pressRelease", pressRelease);
            o.put("mapMode", mapMode);
            o.put("menuMode", menuMode);
            o.put("sensY", sensY);
            o.put("adsSens", adsSens);
            o.put("adsKey", adsKey);
            o.put("delay", delay);
            o.put("ex", ex);
            o.put("ey", ey);
            o.put("duration", duration);
            JSONArray st = new JSONArray();
            for (float[] p : steps) {
                JSONArray pt = new JSONArray();
                pt.put((double) p[0]);
                pt.put((double) p[1]);
                st.put(pt);
            }
            o.put("steps", st);
            return o;
        }

        static Element fromJson(JSONObject o) {
            Element e = new Element();
            e.type = o.optString("type", TAP);
            e.key = o.optInt("key", -1);
            e.fx = (float) o.optDouble("fx", 0.5);
            e.fy = (float) o.optDouble("fy", 0.5);
            e.size = (float) o.optDouble("size", 0.16);
            e.sens = (float) o.optDouble("sens", 1.0);
            e.up = o.optInt("up", 17);
            e.down = o.optInt("down", 31);
            e.left = o.optInt("left", 30);
            e.right = o.optInt("right", 32);
            e.note = o.optString("note", "");
            e.scale = (float) o.optDouble("scale", 1.0);
            e.autoSprint = o.optBoolean("autoSprint", true);
            // older layouts stored Shift (42) as the default sprint key; the default is now Tab (15)
            e.sprintKey = o.optBoolean("sprintKeyV2", false) ? o.optInt("sprintKey", 15) : 15;
            e.sprintDist = (float) o.optDouble("sprintDist", 2.5);
            e.cursor = o.optBoolean("cursor", false);
            e.pressRelease = o.optBoolean("pressRelease", false);
            e.mapMode = o.optBoolean("mapMode", false);
            e.menuMode = o.optBoolean("menuMode", false);
            e.sensY = (float) o.optDouble("sensY", 1.0);
            e.adsSens = (float) o.optDouble("adsSens", e.sens * 0.5);
            e.adsKey = o.optInt("adsKey", KeyNames.BTN_RIGHT);
            e.delay = o.optInt("delay", 120);
            e.ex = (float) o.optDouble("ex", e.fx + 0.1);
            e.ey = (float) o.optDouble("ey", e.fy);
            e.duration = o.optInt("duration", 250);
            JSONArray st = o.optJSONArray("steps");
            if (st != null) {
                for (int i = 0; i < st.length(); i++) {
                    JSONArray pt = st.optJSONArray(i);
                    if (pt != null) e.steps.add(new float[]{(float) pt.optDouble(0), (float) pt.optDouble(1)});
                }
            }
            return e;
        }
    }

    public int toggleKey = KeyNames.KEY_GRAVE;
    public int hideKey = -1; // shows/hides the floating button (for streaming)
    public int layoutKey = -1; // switches to the next layout of this game
    public float cursorSpeed = 1.5f; // own mouse cursor speed (loot boxes / menus)
    public float slotSize = 0.065f;  // Shift+drag transfer: distance between double-taps, fraction of height
    public final List<Element> elements = new ArrayList<>();

    public KeyMap copy() {
        return fromJsonString(toJsonString());
    }

    public String toJsonString() {
        try {
            JSONObject root = new JSONObject();
            root.put("toggleKey", toggleKey);
            root.put("hideKey", hideKey);
            root.put("layoutKey", layoutKey);
            root.put("cursorSpeed", cursorSpeed);
            root.put("slotSize", slotSize);
            JSONArray arr = new JSONArray();
            for (Element e : elements) arr.put(e.toJson());
            root.put("elements", arr);
            return root.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static KeyMap fromJsonString(String s) {
        KeyMap km = new KeyMap();
        try {
            JSONObject root = new JSONObject(s);
            km.toggleKey = root.optInt("toggleKey", KeyNames.KEY_GRAVE);
            km.hideKey = root.optInt("hideKey", -1);
            km.layoutKey = root.optInt("layoutKey", -1);
            km.cursorSpeed = (float) root.optDouble("cursorSpeed", 1.5);
            km.slotSize = (float) root.optDouble("slotSize", 0.065);
            JSONArray arr = root.optJSONArray("elements");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) km.elements.add(Element.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            return oxideDefault();
        }
        return km;
    }

    /** Builds the command for the helper, converting fractions to pixels for the current screen. */
    public JSONObject toServerConfig(int width, int height) throws Exception {
        JSONObject cfg = new JSONObject();
        cfg.put("cmd", "config");
        cfg.put("toggleKey", toggleKey);
        cfg.put("hideKey", hideKey);
        cfg.put("layoutKey", layoutKey);
        cfg.put("cursorSpeed", cursorSpeed);
        cfg.put("slotSize", slotSize * height);
        cfg.put("w", width);
        cfg.put("h", height);
        JSONArray arr = new JSONArray();
        for (Element e : elements) {
            JSONObject o = new JSONObject();
            o.put("type", e.type);
            o.put("x", e.fx * width);
            o.put("y", e.fy * height);
            if (TAP.equals(e.type) || TOGGLE.equals(e.type)) {
                o.put("key", e.key);
                o.put("cursor", e.cursor);
                o.put("mapMode", e.mapMode);
                o.put("menuMode", e.menuMode);
                o.put("tapMode", e.pressRelease ? "press" : "hold");
            } else if (JOYSTICK.equals(e.type)) {
                o.put("r", e.size * height);
                o.put("up", e.up);
                o.put("down", e.down);
                o.put("left", e.left);
                o.put("right", e.right);
                o.put("autoSprint", e.autoSprint);
                o.put("sprintKey", e.sprintKey);
                o.put("sprintR", e.sprintDist * e.size * height);
            } else if (LOOK.equals(e.type)) {
                o.put("sens", e.sens);
                o.put("sensY", e.sensY);
                o.put("adsSens", e.adsSens);
                o.put("adsKey", e.adsKey);
                o.put("lim", e.size * height);
            } else if (SWIPE.equals(e.type)) {
                o.put("key", e.key);
                o.put("ex", e.ex * width);
                o.put("ey", e.ey * height);
                o.put("dur", e.duration);
            } else if (MACRO.equals(e.type) || WHEEL.equals(e.type)) {
                o.put("key", e.key);
                o.put("delay", e.delay);
                JSONArray st = new JSONArray();
                for (float[] p : e.steps) {
                    JSONArray pt = new JSONArray();
                    pt.put((double) (p[0] * width));
                    pt.put((double) (p[1] * height));
                    st.put(pt);
                }
                o.put("steps", st);
            }
            arr.put(o);
        }
        cfg.put("elements", arr);
        return cfg;
    }

    private static String prefKey(String pkg) {
        return pkg == null ? "keymap" : "keymap_" + pkg;
    }

    /** Each game has its own layout. A new game starts with an empty screen. */
    public static KeyMap load(android.content.Context ctx, String pkg) {
        android.content.SharedPreferences p = Prefs.get(ctx);
        // one-time cleanup: older versions pre-filled the Oxide guess layout; start empty instead
        if (!p.getBoolean("empty_layout_migrated", false)) {
            p.edit().remove("keymap").putBoolean("empty_layout_migrated", true).commit();
        }
        String s = p.getString(prefKey(pkg), null);
        return s == null ? new KeyMap() : fromJsonString(s);
    }

    public void save(android.content.Context ctx, String pkg) {
        Prefs.get(ctx).edit().putString(prefKey(pkg), toJsonString()).apply();
    }

    private static Element tap(int key, float fx, float fy, String note) {
        Element e = new Element();
        e.type = TAP;
        e.key = key;
        e.fx = fx;
        e.fy = fy;
        e.note = note;
        return e;
    }

    /**
     * A starting layout for Oxide: Survival Island. The positions are only a first guess –
     * open the editor on top of the game and drag each circle onto the real button.
     */
    public static KeyMap oxideDefault() {
        KeyMap km = new KeyMap();
        Element joy = new Element();
        joy.type = JOYSTICK;
        joy.fx = 0.15f;
        joy.fy = 0.70f;
        joy.size = 0.14f;
        joy.note = "حرکت";
        km.elements.add(joy);

        Element look = new Element();
        look.type = LOOK;
        look.fx = 0.68f;
        look.fy = 0.40f;
        look.size = 0.25f;
        look.sens = 1.0f;
        look.note = "چرخش دوربین با موس";
        km.elements.add(look);

        km.elements.add(tap(KeyNames.BTN_LEFT, 0.88f, 0.60f, "شلیک / ضربه"));
        km.elements.add(tap(KeyNames.BTN_RIGHT, 0.78f, 0.48f, "نشانه‌گیری"));
        km.elements.add(tap(57, 0.93f, 0.80f, "پرش"));          // Space
        km.elements.add(tap(46, 0.84f, 0.88f, "خم شدن"));       // C
        km.elements.add(tap(19, 0.93f, 0.45f, "خشاب"));         // R
        km.elements.add(tap(18, 0.62f, 0.62f, "برداشتن / استفاده")); // E
        km.elements.add(tap(15, 0.95f, 0.10f, "کوله / اینونتوری")); // Tab
        float[] slots = {0.33f, 0.40f, 0.47f, 0.54f, 0.61f, 0.68f};
        for (int i = 0; i < slots.length; i++) {
            km.elements.add(tap(2 + i, slots[i], 0.93f, "خانه " + (i + 1)));
        }
        return km;
    }
}
