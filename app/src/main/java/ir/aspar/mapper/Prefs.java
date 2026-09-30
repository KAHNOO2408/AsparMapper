package ir.aspar.mapper;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class Prefs {
    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences("aspar_mapper", Context.MODE_PRIVATE);
    }

    /** A fresh random secret the server will require from us (stops other apps from controlling it). */
    public static String newToken(Context ctx) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        String token = sb.toString();
        get(ctx).edit().putString("token", token).commit();
        return token;
    }

    public static String token(Context ctx) {
        return get(ctx).getString("token", "");
    }

    public static String keymapJson(Context ctx) {
        return get(ctx).getString("keymap", null);
    }

    public static void saveKeymap(Context ctx, String json) {
        get(ctx).edit().putString("keymap", json).apply();
    }

    public static boolean showLabels(Context ctx) {
        return get(ctx).getBoolean("labels", true);
    }

    public static void setShowLabels(Context ctx, boolean on) {
        get(ctx).edit().putBoolean("labels", on).apply();
    }

    /** Stream mode: the floating button stays hidden inside the game. */
    public static boolean bubbleHidden(Context ctx) {
        return get(ctx).getBoolean("bubble_hidden", false);
    }

    public static void setBubbleHidden(Context ctx, boolean on) {
        get(ctx).edit().putBoolean("bubble_hidden", on).apply();
    }

    public static boolean paired(Context ctx) {
        return get(ctx).getBoolean("paired", false);
    }

    public static void setPaired(Context ctx, boolean on) {
        get(ctx).edit().putBoolean("paired", on).apply();
    }
}
