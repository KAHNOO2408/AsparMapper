package ir.aspar.mapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Typeface;
import android.view.View;

/**
 * Draws the key layout. Used read-only on top of the game (labels) and as the
 * canvas of the editor.
 */
class KeymapView extends View {

    protected KeyMap keyMap;
    protected KeyMap.Element selected;
    protected final int[] loc = new int[2];
    protected final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final float keyRadius;

    KeymapView(Context c, KeyMap keyMap) {
        super(c);
        this.keyMap = keyMap;
        keyRadius = Ui.dp(c, 22);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 2));
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(Ui.dp(c, 12));
    }

    void setKeyMap(KeyMap km) {
        keyMap = km;
        invalidate();
    }

    /** Screen size in the current rotation. */
    Point screen() {
        return Ui.realSize(getContext());
    }

    float toViewX(float fx) {
        getLocationOnScreen(loc);
        return fx * screen().x - loc[0];
    }

    float toViewY(float fy) {
        getLocationOnScreen(loc);
        return fy * screen().y - loc[1];
    }

    float toFractionX(float vx) {
        getLocationOnScreen(loc);
        return (vx + loc[0]) / screen().x;
    }

    float toFractionY(float vy) {
        getLocationOnScreen(loc);
        return (vy + loc[1]) / screen().y;
    }

    /** Editor only: the selected button gets a ring with settings / resize / delete badges. */
    boolean gears;

    static final int BADGE_SETTINGS = 0;
    static final int BADGE_RESIZE = 1;
    static final int BADGE_DELETE = 2;

    float badgeRadius() {
        return Ui.dp(getContext(), 17);
    }

    /** Radius of the selection ring around an element (view pixels). */
    float ringRadius(KeyMap.Element e) {
        if (KeyMap.JOYSTICK.equals(e.type) || KeyMap.LOOK.equals(e.type)) {
            return e.size * screen().y + Ui.dp(getContext(), 8);
        }
        return Math.max(radiusOf(e) * 1.6f, Ui.dp(getContext(), 44));
    }

    /** Centre of a badge on the ring: settings top-left, resize top-right, delete bottom-right. */
    float[] badgePos(KeyMap.Element e, int which) {
        Point s = screen();
        getLocationOnScreen(loc);
        float x = e.fx * s.x - loc[0];
        float y = e.fy * s.y - loc[1];
        float d = ringRadius(e) * 0.7071f;
        switch (which) {
            case BADGE_SETTINGS:
                return new float[]{x - d, y - d};
            case BADGE_RESIZE:
                return new float[]{x + d, y - d};
            default:
                return new float[]{x + d, y + d};
        }
    }

    private void drawBadge(Canvas c, float bx, float by, int which) {
        float br = badgeRadius();
        fill.setColor(0xF0000000);
        c.drawCircle(bx, by, br, fill);
        float u = br / 10f;
        stroke.setColor(0xFFFFFFFF);
        float old = stroke.getStrokeWidth();
        stroke.setStrokeWidth(u * 1.6f);
        if (which == BADGE_SETTINGS) {
            text.setTextSize(br * 1.3f);
            text.setColor(0xFFFFFFFF);
            c.drawText("⚙", bx, by + br * 0.45f, text);
        } else if (which == BADGE_RESIZE) {
            // two corner brackets, like "expand"
            c.drawLine(bx + u, by - 5 * u, bx + 5 * u, by - 5 * u, stroke);
            c.drawLine(bx + 5 * u, by - 5 * u, bx + 5 * u, by - u, stroke);
            c.drawLine(bx - 5 * u, by + u, bx - 5 * u, by + 5 * u, stroke);
            c.drawLine(bx - 5 * u, by + 5 * u, bx - u, by + 5 * u, stroke);
        } else {
            c.drawLine(bx - 4.5f * u, by - 4.5f * u, bx + 4.5f * u, by + 4.5f * u, stroke);
            c.drawLine(bx + 4.5f * u, by - 4.5f * u, bx - 4.5f * u, by + 4.5f * u, stroke);
        }
        stroke.setStrokeWidth(old);
    }

    /** Radius of the round key marker, scaled per element. */
    float radiusOf(KeyMap.Element e) {
        if (KeyMap.TAP.equals(e.type) || KeyMap.TOGGLE.equals(e.type)) return keyRadius * e.scale;
        return keyRadius;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (keyMap == null) return;
        Point s = screen();
        getLocationOnScreen(loc);
        for (KeyMap.Element e : keyMap.elements) {
            float x = e.fx * s.x - loc[0];
            float y = e.fy * s.y - loc[1];
            boolean sel = e == selected;
            int accent = sel ? 0xFFFFC107 : 0xFF4FC3F7;
            if (KeyMap.JOYSTICK.equals(e.type) || KeyMap.LOOK.equals(e.type)) {
                float r = e.size * s.y;
                fill.setColor(KeyMap.LOOK.equals(e.type) ? 0x2266BB6A : 0x224FC3F7);
                canvas.drawCircle(x, y, r, fill);
                stroke.setColor(sel ? accent : (KeyMap.LOOK.equals(e.type) ? 0xFF66BB6A : accent));
                canvas.drawCircle(x, y, r, stroke);
            }
            if (KeyMap.JOYSTICK.equals(e.type) && e.autoSprint) {
                // where the finger goes to lock sprint (should sit on the game's running icon)
                float sy = y - e.sprintDist * e.size * s.y;
                fill.setColor(0x55FF9800);
                canvas.drawCircle(x, sy, keyRadius * 0.8f, fill);
                stroke.setColor(0xFFFF9800);
                canvas.drawCircle(x, sy, keyRadius * 0.8f, stroke);
                text.setTextSize(Ui.dp(getContext(), 13));
                text.setColor(0xFFFFFFFF);
                canvas.drawText("🏃", x, sy + Ui.dp(getContext(), 5), text);
            }
            if (KeyMap.SWIPE.equals(e.type)) {
                // arrow from the start (key circle) to the end point
                float exv = e.ex * s.x - loc[0];
                float eyv = e.ey * s.y - loc[1];
                stroke.setColor(0xFFAB47BC);
                canvas.drawLine(x, y, exv, eyv, stroke);
                double ang = Math.atan2(eyv - y, exv - x);
                float ah = keyRadius * 0.7f;
                canvas.drawLine(exv, eyv, (float) (exv - ah * Math.cos(ang - 0.5)), (float) (eyv - ah * Math.sin(ang - 0.5)), stroke);
                canvas.drawLine(exv, eyv, (float) (exv - ah * Math.cos(ang + 0.5)), (float) (eyv - ah * Math.sin(ang + 0.5)), stroke);
                fill.setColor(0x88AB47BC);
                canvas.drawCircle(exv, eyv, keyRadius * 0.55f, fill);
            }
            if (KeyMap.JOYSTICK.equals(e.type)) {
                // W / A / S / D (or arrows) around the centre, like the real stick
                float r = e.size * s.y;
                float d = r * 0.55f;
                float kr2 = Math.max(Ui.dp(getContext(), 13), Math.min(r * 0.24f, Ui.dp(getContext(), 22)));
                int[] codes = {e.up, e.left, e.down, e.right};
                float[][] pos = {{0, -d}, {-d, 0}, {0, d}, {d, 0}};
                for (int k = 0; k < 4; k++) {
                    float px = x + pos[k][0];
                    float py = y + pos[k][1];
                    fill.setColor(0x99000000);
                    canvas.drawCircle(px, py, kr2, fill);
                    stroke.setColor(sel ? accent : 0xCCFFFFFF);
                    canvas.drawCircle(px, py, kr2, stroke);
                    String l = KeyNames.shortName(codes[k]);
                    text.setTextSize(l.length() > 2 ? kr2 * 0.6f : kr2 * 0.9f);
                    text.setColor(0xFFFFFFFF);
                    canvas.drawText(l, px, py + kr2 * 0.32f, text);
                }
                fill.setColor(sel ? 0xDDFFC107 : 0x99FFFFFF);
                canvas.drawCircle(x, y, Ui.dp(getContext(), 6), fill);
                continue;
            }
            float kr = radiusOf(e);
            fill.setColor(sel ? 0xDDFFC107 : 0xAA000000);
            canvas.drawCircle(x, y, kr, fill);
            stroke.setColor(accent);
            canvas.drawCircle(x, y, kr, stroke);
            if (sel && !e.steps.isEmpty()) {
                for (int k = 0; k < e.steps.size(); k++) {
                    float px = e.steps.get(k)[0] * s.x - loc[0];
                    float py = e.steps.get(k)[1] * s.y - loc[1];
                    fill.setColor(0xCCAB47BC);
                    canvas.drawCircle(px, py, keyRadius * 0.6f, fill);
                    text.setTextSize(Ui.dp(getContext(), 11));
                    text.setColor(0xFFFFFFFF);
                    canvas.drawText(String.valueOf(k + 1), px, py + Ui.dp(getContext(), 4), text);
                }
            }
            String label = e.label();
            float size = (label.length() > 4 ? Ui.dp(getContext(), 9) : Ui.dp(getContext(), 12)) * Math.max(0.7f, Math.min(1.8f, e.scale));
            text.setTextSize(size);
            text.setColor(sel ? 0xFF000000 : 0xFFFFFFFF);
            canvas.drawText(label, x, y + size / 3, text);
        }
        // selection ring + badges on top of everything (editor only)
        KeyMap.Element e = selected;
        if (gears && e != null && keyMap.elements.contains(e)) {
            float x = e.fx * s.x - loc[0];
            float y = e.fy * s.y - loc[1];
            stroke.setColor(0xDDFFFFFF);
            canvas.drawCircle(x, y, ringRadius(e), stroke);
            for (int b = 0; b < 3; b++) {
                float[] p = badgePos(e, b);
                drawBadge(canvas, p[0], p[1], b);
            }
        }
    }
}
