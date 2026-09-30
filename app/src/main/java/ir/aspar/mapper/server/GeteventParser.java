package ir.aspar.mapper.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the output of "getevent -pl" (device list with labelled capabilities).
 * Used because /proc/bus/input/devices is not readable by the shell on newer Android versions.
 * Pure Java, no Android dependencies.
 */
final class GeteventParser {

    static final class Device {
        String path;
        String name = "";
        final Map<String, Set<String>> events = new HashMap<>();

        boolean has(String type, String code) {
            Set<String> s = events.get(type);
            return s != null && s.contains(code);
        }

        boolean isMouse() {
            return has("REL", "REL_X") && has("REL", "REL_Y") && !has("ABS", "ABS_MT_POSITION_X");
        }

        boolean isKeyboard() {
            return has("KEY", "KEY_A") && has("KEY", "KEY_Q") && has("KEY", "KEY_SPACE");
        }

        boolean isPhoneInternal() {
            String n = name.toLowerCase();
            return n.contains("sec_touch") || n.contains("e-pen") || n.contains("gpio")
                    || n.contains("uinput") || n.contains("virtual");
        }
    }

    private GeteventParser() {
    }

    static List<Device> parse(String output) {
        List<Device> list = new ArrayList<>();
        Device cur = null;
        String section = null;
        boolean inEvents = false;
        for (String raw : output.split("\n")) {
            String line = raw.replace("\r", "");
            String t = line.trim();
            if (t.startsWith("add device")) {
                cur = new Device();
                int slash = t.indexOf("/dev/input/");
                cur.path = slash >= 0 ? t.substring(slash).trim() : null;
                if (cur.path != null) list.add(cur);
                section = null;
                inEvents = false;
                continue;
            }
            if (cur == null) continue;
            if (t.startsWith("name:")) {
                String n = t.substring(5).trim();
                if (n.startsWith("\"") && n.endsWith("\"") && n.length() >= 2) n = n.substring(1, n.length() - 1);
                cur.name = n;
                continue;
            }
            if (t.startsWith("events:")) {
                inEvents = true;
                section = null;
                continue;
            }
            if (t.startsWith("input props:")) {
                inEvents = false;
                section = null;
                continue;
            }
            if (!inEvents || t.isEmpty()) continue;

            // "KEY (0001): KEY_ESC  KEY_1 ..." or a continuation line with more codes
            int paren = t.indexOf(" (");
            int colon = t.indexOf("):");
            String rest = t;
            if (paren > 0 && colon > paren && t.substring(0, paren).matches("[A-Z]+")) {
                section = t.substring(0, paren);
                rest = t.substring(colon + 2);
            }
            if (section == null) continue;
            Set<String> set = cur.events.computeIfAbsent(section, k -> new HashSet<>());
            for (String tok : rest.trim().split("\\s+")) {
                if (tok.isEmpty() || tok.startsWith(":") || tok.contains(":")) {
                    // ABS lines look like "ABS_X : value 0, min 0, ..." – keep only the code
                    int c = tok.indexOf(':');
                    if (c > 0) set.add(tok.substring(0, c));
                    continue;
                }
                if (tok.matches("[A-Z0-9_]+")) set.add(tok);
            }
        }
        return list;
    }
}
