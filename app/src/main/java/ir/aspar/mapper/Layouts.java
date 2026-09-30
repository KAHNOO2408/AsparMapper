package ir.aspar.mapper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Several key layouts for one game (e.g. "fight" and "build"). One of them is active.
 * Global keys (mode, hide, layout switch) are kept in sync across all layouts of the game.
 */
final class Layouts {

    final List<String> names = new ArrayList<>();
    final List<KeyMap> maps = new ArrayList<>();
    int current = 0;

    KeyMap active() {
        if (maps.isEmpty()) {
            names.add("چیدمان ۱");
            maps.add(new KeyMap());
            current = 0;
        }
        if (current < 0 || current >= maps.size()) current = 0;
        return maps.get(current);
    }

    String activeName() {
        active();
        return names.get(current);
    }

    /** Replaces the active layout and copies its global keys to the others. */
    void setActive(KeyMap km) {
        active();
        maps.set(current, km);
        for (KeyMap m : maps) {
            m.toggleKey = km.toggleKey;
            m.hideKey = km.hideKey;
            m.layoutKey = km.layoutKey;
            m.cursorSpeed = km.cursorSpeed;
            m.slotSize = km.slotSize;
        }
    }

    void add(String name, KeyMap km) {
        KeyMap base = active();
        km.toggleKey = base.toggleKey;
        km.hideKey = base.hideKey;
        km.layoutKey = base.layoutKey;
        km.cursorSpeed = base.cursorSpeed;
        km.slotSize = base.slotSize;
        names.add(name);
        maps.add(km);
        current = maps.size() - 1;
    }

    void removeActive() {
        if (maps.size() <= 1) {
            KeyMap base = active();
            KeyMap empty = new KeyMap();
            empty.toggleKey = base.toggleKey;
            empty.hideKey = base.hideKey;
            empty.layoutKey = base.layoutKey;
            maps.set(0, empty);
            return;
        }
        names.remove(current);
        maps.remove(current);
        if (current >= maps.size()) current = maps.size() - 1;
    }

    void next() {
        active();
        current = (current + 1) % maps.size();
    }

    String nextName() {
        return "چیدمان " + toPersianDigits(maps.size() + 1);
    }

    static String toPersianDigits(int n) {
        String s = String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append((char) ('۰' + (c - '0')));
        return sb.toString();
    }

    // ------------------------------------------------------------------ storage

    private static String key(String pkg) {
        return "layouts_" + (pkg == null ? "_" : pkg);
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("current", current);
        JSONArray n = new JSONArray();
        JSONArray m = new JSONArray();
        for (int i = 0; i < maps.size(); i++) {
            n.put(names.get(i));
            m.put(new JSONObject(maps.get(i).toJsonString()));
        }
        o.put("names", n);
        o.put("maps", m);
        return o;
    }

    static Layouts fromJson(JSONObject o) {
        Layouts l = new Layouts();
        JSONArray n = o.optJSONArray("names");
        JSONArray m = o.optJSONArray("maps");
        if (n != null && m != null) {
            for (int i = 0; i < Math.min(n.length(), m.length()); i++) {
                l.names.add(n.optString(i, "چیدمان " + toPersianDigits(i + 1)));
                l.maps.add(KeyMap.fromJsonString(m.optJSONObject(i).toString()));
            }
        }
        l.current = o.optInt("current", 0);
        l.active();
        return l;
    }

    static Layouts load(Context ctx, String pkg) {
        String s = Prefs.get(ctx).getString(key(pkg), null);
        if (s != null) {
            try {
                return fromJson(new JSONObject(s));
            } catch (Exception ignored) {
            }
        }
        // older versions stored one layout per game
        Layouts l = new Layouts();
        l.names.add("چیدمان ۱");
        l.maps.add(KeyMap.load(ctx, pkg));
        return l;
    }

    void save(Context ctx, String pkg) {
        try {
            Prefs.get(ctx).edit().putString(key(pkg), toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ backup of everything

    /** All games with all their layouts, as one JSON text. */
    static String exportAll(Context ctx) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "AsparMapper");
        root.put("version", 1);
        JSONArray games = new JSONArray();
        for (String pkg : Games.saved(ctx)) {
            JSONObject g = new JSONObject();
            g.put("pkg", pkg);
            g.put("layouts", load(ctx, pkg).toJson());
            games.put(g);
        }
        root.put("games", games);
        return root.toString(2);
    }

    /** Restores a backup; returns how many games were imported. */
    static int importAll(Context ctx, String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (!"AsparMapper".equals(root.optString("app"))) throw new IllegalArgumentException("این فایل پشتیبان Aspar Mapper نیست");
        JSONArray games = root.optJSONArray("games");
        int count = 0;
        if (games == null) return 0;
        for (int i = 0; i < games.length(); i++) {
            JSONObject g = games.getJSONObject(i);
            String pkg = g.getString("pkg");
            Layouts l = fromJson(g.getJSONObject("layouts"));
            l.save(ctx, pkg);
            Games.add(ctx, pkg);
            count++;
        }
        return count;
    }
}
