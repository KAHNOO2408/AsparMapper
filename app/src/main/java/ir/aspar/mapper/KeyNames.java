package ir.aspar.mapper;

import android.util.SparseArray;

/** Display names for Linux input key codes (what KeyEvent.getScanCode() returns for real keyboards). */
public final class KeyNames {

    public static final int BTN_LEFT = 272;
    public static final int BTN_RIGHT = 273;
    public static final int BTN_MIDDLE = 274;
    public static final int BTN_SIDE = 275;
    public static final int BTN_EXTRA = 276;
    public static final int WHEEL_UP = 0x1001;
    public static final int WHEEL_DOWN = 0x1002;
    public static final int KEY_GRAVE = 41;

    private static final SparseArray<String> NAMES = new SparseArray<>();

    static {
        String[] row1 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="};
        for (int i = 0; i < row1.length; i++) NAMES.put(2 + i, row1[i]);
        NAMES.put(1, "Esc");
        NAMES.put(14, "Backspace");
        NAMES.put(15, "Tab");
        String[] row2 = {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]"};
        for (int i = 0; i < row2.length; i++) NAMES.put(16 + i, row2[i]);
        NAMES.put(28, "Enter");
        NAMES.put(29, "Ctrl");
        String[] row3 = {"A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "`"};
        for (int i = 0; i < row3.length; i++) NAMES.put(30 + i, row3[i]);
        NAMES.put(42, "Shift");
        NAMES.put(43, "\\");
        String[] row4 = {"Z", "X", "C", "V", "B", "N", "M", ",", ".", "/"};
        for (int i = 0; i < row4.length; i++) NAMES.put(44 + i, row4[i]);
        NAMES.put(54, "RShift");
        NAMES.put(56, "Alt");
        NAMES.put(57, "Space");
        NAMES.put(58, "Caps");
        for (int i = 0; i < 10; i++) NAMES.put(59 + i, "F" + (i + 1));
        NAMES.put(87, "F11");
        NAMES.put(88, "F12");
        NAMES.put(102, "Home");
        NAMES.put(104, "PageUp");
        NAMES.put(107, "End");
        NAMES.put(109, "PageDown");
        NAMES.put(110, "Insert");
        NAMES.put(111, "Delete");
        NAMES.put(97, "RCtrl");
        NAMES.put(100, "RAlt");
        NAMES.put(103, "↑");
        NAMES.put(105, "←");
        NAMES.put(106, "→");
        NAMES.put(108, "↓");
        NAMES.put(BTN_LEFT, "کلیک چپ");
        NAMES.put(BTN_RIGHT, "کلیک راست");
        NAMES.put(BTN_MIDDLE, "کلیک وسط");
        NAMES.put(BTN_SIDE, "دکمه کناری ۱");
        NAMES.put(BTN_EXTRA, "دکمه کناری ۲");
        NAMES.put(WHEEL_UP, "چرخ ↑");
        NAMES.put(WHEEL_DOWN, "چرخ ↓");
    }

    private KeyNames() {
    }

    public static String name(int code) {
        String n = NAMES.get(code);
        return n != null ? n : ("#" + code);
    }

    /** Very short label for the on-screen circles. */
    public static String shortName(int code) {
        switch (code) {
            case BTN_LEFT:
                return "LMB";
            case BTN_RIGHT:
                return "RMB";
            case BTN_MIDDLE:
                return "MMB";
            case BTN_SIDE:
                return "M4";
            case BTN_EXTRA:
                return "M5";
            case WHEEL_UP:
                return "W↑";
            case WHEEL_DOWN:
                return "W↓";
            default:
                return name(code);
        }
    }
}
