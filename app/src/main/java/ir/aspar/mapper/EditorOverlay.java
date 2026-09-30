package ir.aspar.mapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen editor shown on top of the game: drag the circles onto the game's buttons,
 * tap a circle and press a key on the keyboard to bind it.
 */
final class EditorOverlay {

    interface Callback {
        void onEditorClosed(KeyMap saved); // saved == null when cancelled
    }

    private enum Wait { NONE, KEY, TOGGLE, DIR_UP, DIR_LEFT, DIR_DOWN, DIR_RIGHT }

    private final Context ctx;
    private final WindowManager wm;
    private final Callback callback;
    private final KeyMap keyMap;
    private final Root root;
    private final EditCanvas canvas;
    private final TextView hint;
    private final LinearLayout selectionBar;
    private Wait wait = Wait.NONE;

    EditorOverlay(Context ctx, KeyMap original, Callback callback) {
        this.ctx = ctx;
        this.callback = callback;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.keyMap = KeyMap.fromJsonString(original.toJsonString()); // edit a copy

        root = new Root(ctx);
        root.setBackgroundColor(0x77000000);
        canvas = new EditCanvas(ctx, keyMap);
        root.addView(canvas, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout topBar = row();
        topBar.addView(Ui.button(ctx, "ذخیره ✓", v -> close(true)));
        topBar.addView(Ui.button(ctx, "لغو ✕", v -> close(false)));
        topBar.addView(Ui.button(ctx, "+ دکمه", v -> addTap()));
        topBar.addView(Ui.button(ctx, "+ جوی‌استیک", v -> addSpecial(KeyMap.JOYSTICK)));
        topBar.addView(Ui.button(ctx, "+ دوربین", v -> addSpecial(KeyMap.LOOK)));
        topBar.addView(Ui.button(ctx, "کلید تغییر حالت", v -> startWait(Wait.TOGGLE)));
        topBar.addView(Ui.button(ctx, "چیدمان پیش‌فرض", v -> resetDefault()));
        top.addView(scroll(topBar));

        hint = new TextView(ctx);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4));
        hint.setBackgroundColor(0xAA000000);
        top.addView(hint, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(top, topLp);

        selectionBar = row();
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(scroll(selectionBar), botLp);

        refreshBars();
    }

    void show() {
        WindowManager.LayoutParams lp = Ui.fullScreen(true, true);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        wm.addView(root, lp);
        root.requestFocus();
    }

    void dismiss() {
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
    }

    boolean isShowing() {
        return root.isAttachedToWindow();
    }

    // ------------------------------------------------------------------ actions

    private void close(boolean save) {
        dismiss();
        callback.onEditorClosed(save ? keyMap : null);
    }

    private void addTap() {
        KeyMap.Element e = new KeyMap.Element();
        e.type = KeyMap.TAP;
        e.fx = 0.5f;
        e.fy = 0.5f;
        keyMap.elements.add(e);
        select(e);
        startWait(Wait.KEY);
    }

    private void addSpecial(String type) {
        for (KeyMap.Element e : keyMap.elements) {
            if (type.equals(e.type)) {
                select(e);
                hint.setText(KeyMap.JOYSTICK.equals(type) ? "فقط یک جوی‌استیک مجاز است – همین را جابجا کن." : "فقط یک ناحیه دوربین مجاز است – همین را جابجا کن.");
                return;
            }
        }
        KeyMap.Element e = new KeyMap.Element();
        e.type = type;
        e.fx = KeyMap.JOYSTICK.equals(type) ? 0.15f : 0.68f;
        e.fy = KeyMap.JOYSTICK.equals(type) ? 0.7f : 0.4f;
        e.size = KeyMap.JOYSTICK.equals(type) ? 0.14f : 0.25f;
        keyMap.elements.add(e);
        select(e);
    }

    private void resetDefault() {
        KeyMap def = KeyMap.oxideDefault();
        keyMap.elements.clear();
        keyMap.elements.addAll(def.elements);
        keyMap.toggleKey = def.toggleKey;
        select(null);
    }

    private void select(KeyMap.Element e) {
        canvas.selected = e;
        wait = Wait.NONE;
        refreshBars();
        canvas.invalidate();
    }

    private void startWait(Wait w) {
        wait = w;
        refreshBars();
    }

    private void refreshBars() {
        KeyMap.Element e = canvas.selected;
        switch (wait) {
            case KEY:
                hint.setText("حالا یک کلید روی کیبورد بزن (یا از دکمه‌های پایین، دکمه موس را انتخاب کن)");
                break;
            case TOGGLE:
                hint.setText("کلیدی را بزن که بین «حالت بازی» و «حالت موس معمولی» جابه‌جا کند");
                break;
            case DIR_UP:
                hint.setText("کلید «جلو» را بزن");
                break;
            case DIR_LEFT:
                hint.setText("کلید «چپ» را بزن");
                break;
            case DIR_DOWN:
                hint.setText("کلید «عقب» را بزن");
                break;
            case DIR_RIGHT:
                hint.setText("کلید «راست» را بزن");
                break;
            default:
                if (e == null) {
                    hint.setText("دایره‌ها را روی دکمه‌های بازی بکش. روی هر دایره بزن تا تنظیمش کنی.   کلید تغییر حالت: "
                            + KeyNames.name(keyMap.toggleKey));
                } else if (KeyMap.LOOK.equals(e.type)) {
                    hint.setText(String.format(java.util.Locale.US,
                            "ناحیه دوربین – وسطش را روی جای خالی سمت راست صفحه بگذار.  حساسیت: %.1f", e.sens));
                } else if (KeyMap.JOYSTICK.equals(e.type)) {
                    hint.setText("جوی‌استیک – وسطش را دقیقاً روی مرکز جوی‌استیک بازی بگذار. دایره بزرگ = اندازه حرکت");
                } else {
                    hint.setText("کلید: " + KeyNames.name(e.key) + (e.note.isEmpty() ? "" : "  (" + e.note + ")"));
                }
        }

        selectionBar.removeAllViews();
        if (e == null) return;
        if (KeyMap.TAP.equals(e.type)) {
            selectionBar.addView(Ui.button(ctx, "تغییر کلید", v -> startWait(Wait.KEY)));
            selectionBar.addView(Ui.button(ctx, "کلیک چپ", v -> assign(KeyNames.BTN_LEFT)));
            selectionBar.addView(Ui.button(ctx, "کلیک راست", v -> assign(KeyNames.BTN_RIGHT)));
            selectionBar.addView(Ui.button(ctx, "کلیک وسط", v -> assign(KeyNames.BTN_MIDDLE)));
            selectionBar.addView(Ui.button(ctx, "چرخ ↑", v -> assign(KeyNames.WHEEL_UP)));
            selectionBar.addView(Ui.button(ctx, "چرخ ↓", v -> assign(KeyNames.WHEEL_DOWN)));
            selectionBar.addView(Ui.button(ctx, "دکمه کناری", v -> assign(KeyNames.BTN_SIDE)));
        } else if (KeyMap.JOYSTICK.equals(e.type)) {
            selectionBar.addView(Ui.button(ctx, "کلیدهای جهت", v -> startWait(Wait.DIR_UP)));
            selectionBar.addView(Ui.button(ctx, "بزرگ‌تر", v -> resize(e, 0.01f)));
            selectionBar.addView(Ui.button(ctx, "کوچک‌تر", v -> resize(e, -0.01f)));
        } else {
            selectionBar.addView(Ui.button(ctx, "حساسیت +", v -> sens(e, 0.1f)));
            selectionBar.addView(Ui.button(ctx, "حساسیت −", v -> sens(e, -0.1f)));
            selectionBar.addView(Ui.button(ctx, "محدوده بزرگ‌تر", v -> resize(e, 0.02f)));
            selectionBar.addView(Ui.button(ctx, "محدوده کوچک‌تر", v -> resize(e, -0.02f)));
        }
        selectionBar.addView(Ui.button(ctx, "حذف 🗑", v -> {
            keyMap.elements.remove(e);
            select(null);
        }));
        selectionBar.addView(Ui.button(ctx, "بستن انتخاب", v -> select(null)));
    }

    private void assign(int code) {
        KeyMap.Element e = canvas.selected;
        if (e == null) return;
        e.key = code;
        wait = Wait.NONE;
        refreshBars();
        canvas.invalidate();
    }

    private void resize(KeyMap.Element e, float d) {
        e.size = Math.max(0.04f, Math.min(0.45f, e.size + d));
        canvas.invalidate();
        refreshBars();
    }

    private void sens(KeyMap.Element e, float d) {
        e.sens = Math.max(0.1f, Math.min(5f, Math.round((e.sens + d) * 10f) / 10f));
        refreshBars();
    }

    /** Physical keyboard input while the editor is open. */
    private boolean onKey(KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) return wait != Wait.NONE;
        int code = ev.getScanCode();
        if (wait == Wait.NONE) {
            // Note: a mouse right-click arrives as BACK on Android, so BACK must not close the editor.
            return false;
        }
        if (code <= 0) {
            hint.setText("این کلید از کیبورد واقعی نیست؛ یک کلید دیگر بزن");
            return true;
        }
        KeyMap.Element e = canvas.selected;
        switch (wait) {
            case TOGGLE:
                keyMap.toggleKey = code;
                wait = Wait.NONE;
                break;
            case KEY:
                if (code == keyMap.toggleKey) {
                    hint.setText("این کلید برای تغییر حالت استفاده می‌شود؛ یکی دیگر بزن");
                    return true;
                }
                if (e != null) e.key = code;
                wait = Wait.NONE;
                break;
            case DIR_UP:
                if (e != null) e.up = code;
                wait = Wait.DIR_LEFT;
                break;
            case DIR_LEFT:
                if (e != null) e.left = code;
                wait = Wait.DIR_DOWN;
                break;
            case DIR_DOWN:
                if (e != null) e.down = code;
                wait = Wait.DIR_RIGHT;
                break;
            case DIR_RIGHT:
                if (e != null) e.right = code;
                wait = Wait.NONE;
                break;
            default:
                break;
        }
        refreshBars();
        canvas.invalidate();
        return true;
    }

    // ------------------------------------------------------------------ views

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2));
        return l;
    }

    private HorizontalScrollView scroll(View child) {
        HorizontalScrollView s = new HorizontalScrollView(ctx);
        s.setBackgroundColor(0xCC202124);
        s.addView(child);
        return s;
    }

    private final class Root extends FrameLayout {
        Root(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (onKey(event)) return true;
            return super.dispatchKeyEvent(event);
        }
    }

    private final class EditCanvas extends KeymapView {
        private KeyMap.Element dragging;
        private float downX, downY, grabDx, grabDy;
        private boolean moved;

        EditCanvas(Context c, KeyMap km) {
            super(c, km);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    dragging = hit(ev.getX(), ev.getY());
                    downX = ev.getX();
                    downY = ev.getY();
                    moved = false;
                    if (dragging != null) {
                        grabDx = toViewX(dragging.fx) - ev.getX();
                        grabDy = toViewY(dragging.fy) - ev.getY();
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (dragging == null) return true;
                    if (Math.hypot(ev.getX() - downX, ev.getY() - downY) > Ui.dp(getContext(), 6)) moved = true;
                    if (moved) {
                        dragging.fx = clamp(toFractionX(ev.getX() + grabDx));
                        dragging.fy = clamp(toFractionY(ev.getY() + grabDy));
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (dragging != null) {
                        if (selected != dragging || !moved) select(dragging);
                    } else if (!moved) {
                        select(null);
                    }
                    dragging = null;
                    return true;
                }
                default:
                    return true;
            }
        }

        private float clamp(float f) {
            return Math.max(0f, Math.min(1f, f));
        }

        private KeyMap.Element hit(float x, float y) {
            Point s = screen();
            KeyMap.Element best = null;
            double bestD = Ui.dp(getContext(), 34);
            for (KeyMap.Element e : keyMap.elements) {
                double d = Math.hypot(toViewX(e.fx) - x, toViewY(e.fy) - y);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
            if (best == null) {
                // allow grabbing the big joystick / camera circles by their ring area
                for (KeyMap.Element e : keyMap.elements) {
                    if (KeyMap.TAP.equals(e.type)) continue;
                    double d = Math.hypot(toViewX(e.fx) - x, toViewY(e.fy) - y);
                    if (d < e.size * s.y) return e;
                }
            }
            return best;
        }
    }
}
