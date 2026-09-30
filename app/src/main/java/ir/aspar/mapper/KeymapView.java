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
            float kr = radiusOf(e);
            fill.setColor(sel ? 0xDDFFC107 : 0xAA000000);
            canvas.drawCircle(x, y, kr, fill);
            stroke.setColor(accent);
            canvas.drawCircle(x, y, kr, stroke);
            String label = e.label();
            float size = (label.length() > 4 ? Ui.dp(getContext(), 9) : Ui.dp(getContext(), 12)) * Math.max(0.7f, Math.min(1.8f, e.scale));
            text.setTextSize(size);
            text.setColor(sel ? 0xFF000000 : 0xFFFFFFFF);
            canvas.drawText(label, x, y + size / 3, text);
        }
    }
}
