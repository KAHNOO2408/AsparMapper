package ir.aspar.mapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Small vector icons drawn in code (no image resources needed). */
final class IconView extends View {

    enum Kind { MACRO, WHEEL, PLUS, LAYERS, KEYBOARD, SLIDERS, MOUSE, KEY, WASD, ARROWS, HOLD, CHEVRON_UP, CLOSE, CHECK, TRASH, GAMEPAD }

    private final Kind kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;
    private int background;

    IconView(Context c, Kind kind, int color, int background) {
        super(c);
        this.kind = kind;
        this.color = color;
        this.background = background;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setColors(int color, int background) {
        this.color = color;
        this.background = background;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        if (background != 0) {
            fill.setColor(background);
            c.drawCircle(cx, cy, s / 2f, fill);
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(s * 0.07f);
        fill.setColor(color);
        text.setColor(color);
        float u = s / 24f; // 24-unit grid like Material icons
        switch (kind) {
            case PLUS:
                stroke.setStrokeWidth(s * 0.1f);
                c.drawLine(cx - 7 * u, cy, cx + 7 * u, cy, stroke);
                c.drawLine(cx, cy - 7 * u, cx, cy + 7 * u, stroke);
                break;
            case LAYERS:
                fill.setColor(color);
                for (int i = 0; i < 3; i++) {
                    float y = cy - 5 * u + i * 4.5f * u;
                    Path p = new Path();
                    p.moveTo(cx, y - 3.5f * u);
                    p.lineTo(cx + 7 * u, y);
                    p.lineTo(cx, y + 3.5f * u);
                    p.lineTo(cx - 7 * u, y);
                    p.close();
                    if (i == 0) {
                        c.drawPath(p, fill);
                    } else {
                        stroke.setStrokeWidth(s * 0.06f);
                        c.drawPath(p, stroke);
                    }
                }
                break;
            case KEYBOARD: {
                RectF r = new RectF(cx - 9 * u, cy - 6 * u, cx + 9 * u, cy + 6 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, fill);
                fill.setColor(background != 0 ? background : 0xFF000000);
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 5; col++) {
                        float x = cx - 6 * u + col * 3 * u;
                        float y = cy - 3 * u + row * 3 * u;
                        c.drawRect(x - 0.9f * u, y - 0.9f * u, x + 0.9f * u, y + 0.9f * u, fill);
                    }
                }
                c.drawRect(cx - 4.5f * u, cy + 2.6f * u, cx + 4.5f * u, cy + 4f * u, fill);
                break;
            }
            case SLIDERS: {
                stroke.setStrokeWidth(s * 0.07f);
                float[] knobs = {-3f, 3f, -1f};
                for (int i = 0; i < 3; i++) {
                    float y = cy - 6 * u + i * 6 * u;
                    c.drawLine(cx - 8 * u, y, cx + 8 * u, y, stroke);
                    c.drawCircle(cx + knobs[i] * u, y, 2.2f * u, fill);
                }
                break;
            }
            case MOUSE: {
                RectF r = new RectF(cx - 5.5f * u, cy - 8.5f * u, cx + 5.5f * u, cy + 8.5f * u);
                fill.setColor(color);
                c.drawRoundRect(r, 5.5f * u, 5.5f * u, fill);
                stroke.setColor(background != 0 ? background : 0xFF000000);
                stroke.setStrokeWidth(s * 0.05f);
                c.drawLine(cx, cy - 8 * u, cx, cy - 3 * u, stroke);
                break;
            }
            case KEY:
                text.setTextSize(s * 0.36f);
                c.drawText("A", cx, cy + s * 0.13f, text);
                break;
            case MACRO:
                text.setTextSize(s * 0.34f);
                c.drawText("⚡", cx, cy + s * 0.12f, text);
                break;
            case WHEEL:
                text.setTextSize(s * 0.36f);
                c.drawText("⇅", cx, cy + s * 0.13f, text);
                break;
            case WASD:
            case ARROWS: {
                String[] labels = kind == Kind.WASD ? new String[]{"W", "A", "S", "D"} : new String[]{"↑", "←", "↓", "→"};
                float r = s * 0.12f;
                float d = s * 0.26f;
                float[][] pos = {{0, -d}, {-d, 0}, {0, d}, {d, 0}};
                text.setTextSize(r * 1.1f);
                for (int i = 0; i < 4; i++) {
                    fill.setColor(0xFFFFFFFF);
                    c.drawCircle(cx + pos[i][0], cy + pos[i][1], r, fill);
                    text.setColor(0xFF111111);
                    c.drawText(labels[i], cx + pos[i][0], cy + pos[i][1] + r * 0.38f, text);
                }
                break;
            }
            case HOLD: {
                stroke.setStrokeWidth(s * 0.06f);
                c.drawCircle(cx, cy - 2 * u, 5 * u, stroke);
                fill.setColor(color);
                c.drawCircle(cx, cy - 2 * u, 2 * u, fill);
                c.drawLine(cx, cy + 3 * u, cx, cy + 8 * u, stroke);
                break;
            }
            case CHEVRON_UP: {
                stroke.setStrokeWidth(s * 0.08f);
                Path p = new Path();
                p.moveTo(cx - 5 * u, cy + 2.5f * u);
                p.lineTo(cx, cy - 2.5f * u);
                p.lineTo(cx + 5 * u, cy + 2.5f * u);
                c.drawPath(p, stroke);
                break;
            }
            case CLOSE:
                stroke.setStrokeWidth(s * 0.09f);
                c.drawLine(cx - 5 * u, cy - 5 * u, cx + 5 * u, cy + 5 * u, stroke);
                c.drawLine(cx + 5 * u, cy - 5 * u, cx - 5 * u, cy + 5 * u, stroke);
                break;
            case CHECK: {
                stroke.setStrokeWidth(s * 0.09f);
                Path p = new Path();
                p.moveTo(cx - 6 * u, cy);
                p.lineTo(cx - 1.5f * u, cy + 4.5f * u);
                p.lineTo(cx + 6.5f * u, cy - 4.5f * u);
                c.drawPath(p, stroke);
                break;
            }
            case TRASH: {
                stroke.setStrokeWidth(s * 0.06f);
                c.drawLine(cx - 6 * u, cy - 5 * u, cx + 6 * u, cy - 5 * u, stroke);
                c.drawLine(cx - 2 * u, cy - 7 * u, cx + 2 * u, cy - 7 * u, stroke);
                Path p = new Path();
                p.moveTo(cx - 4.5f * u, cy - 4 * u);
                p.lineTo(cx - 3.5f * u, cy + 7 * u);
                p.lineTo(cx + 3.5f * u, cy + 7 * u);
                p.lineTo(cx + 4.5f * u, cy - 4 * u);
                c.drawPath(p, stroke);
                break;
            }
            case GAMEPAD: {
                RectF r = new RectF(cx - 9 * u, cy - 5 * u, cx + 9 * u, cy + 6 * u);
                c.drawRoundRect(r, 5 * u, 5 * u, fill);
                int bg = background != 0 ? background : 0xFF000000;
                fill.setColor(bg);
                c.drawRect(cx - 6.5f * u, cy - 0.7f * u, cx - 2.5f * u, cy + 0.7f * u, fill);
                c.drawRect(cx - 5.2f * u, cy - 2f * u, cx - 3.8f * u, cy + 2f * u, fill);
                c.drawCircle(cx + 4 * u, cy - 1.2f * u, 1.1f * u, fill);
                c.drawCircle(cx + 6 * u, cy + 1 * u, 1.1f * u, fill);
                break;
            }
            default:
                break;
        }
    }
}
