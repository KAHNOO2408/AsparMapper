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

    public static final class Element {
        public String type = TAP;
        public int key = -1;
        public float fx;
        public float fy;
        public float size = 0.16f;  // joystick radius or camera range, as fraction of height
        public float sens = 1.0f;   // camera sensitivity
        public int up = 17, down = 31, left = 30, right = 32; // W S A D
        public String note = "";

        public String label() {
            switch (type) {
                case JOYSTICK:
                    if (up == 103 && left == 105 && down == 108 && right == 106) return "↑←↓→";
                    return KeyNames.shortName(up) + KeyNames.shortName(left) + KeyNames.shortName(down) + KeyNames.shortName(right);
                case LOOK:
                    return "🖱";
                case TOGGLE:
                    return KeyNames.shortName(key) + "⏺";
                default:
                    return KeyNames.shortName(key);
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
            return e;
        }
    }

    public int toggleKey = KeyNames.KEY_GRAVE;
    public final List<Element> elements = new ArrayList<>();

    public String toJsonString() {
        try {
            JSONObject root = new JSONObject();
            root.put("toggleKey", toggleKey);
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
            } else if (JOYSTICK.equals(e.type)) {
                o.put("r", e.size * height);
                o.put("up", e.up);
                o.put("down", e.down);
                o.put("left", e.left);
                o.put("right", e.right);
            } else if (LOOK.equals(e.type)) {
                o.put("sens", e.sens);
                o.put("lim", e.size * height);
            }
            arr.put(o);
        }
        cfg.put("elements", arr);
        return cfg;
    }

    public static KeyMap load(android.content.Context ctx) {
        String s = Prefs.keymapJson(ctx);
        return s == null ? oxideDefault() : fromJsonString(s);
    }

    public void save(android.content.Context ctx) {
        Prefs.saveKeymap(ctx, toJsonString());
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
