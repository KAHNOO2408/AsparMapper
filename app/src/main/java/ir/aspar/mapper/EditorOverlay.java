package ir.aspar.mapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Mantis-style editor shown on top of the game.
 * Tap the floating button (top-left replica) to save and leave.
 * The dark toolbar next to it has: add controls, layouts, key labels, settings.
 */
final class EditorOverlay {

    interface Callback {
        void onEditorClosed(KeyMap saved); // saved == null when cancelled

        void onStopRequested();
    }

    private enum Wait { NONE, KEY, TOGGLE, DIR_UP, DIR_LEFT, DIR_DOWN, DIR_RIGHT }

    private enum Panel { NONE, ADD, LAYERS, SETTINGS }

    private static final int CARD_BG = 0xFFF1F1F3;
    private static final int DARK = 0xFF0D0D12;
    private static final int LIGHT_ITEM = 0xFFCBD0DC;
    private static final int GREEN = 0xFF34C759;

    private final Context ctx;
    private final WindowManager wm;
    private final Callback callback;
    private final KeyMap keyMap;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Root root;
    private final EditCanvas canvas;
    private final IconView bubble;
    private final LinearLayout toolbar;
    private final FrameLayout panel;
    private final LinearLayout popup;
    private final TextView hint;

    private final int bubbleX;
    private final int bubbleY;
    private final int bubbleSize;

    private Wait wait = Wait.NONE;
    private Panel openPanel = Panel.NONE;
    private int addPage = 0;
    private boolean resetArmed;

    EditorOverlay(Context ctx, KeyMap original, int bubbleX, int bubbleY, int bubbleSize, Callback callback) {
        this.ctx = ctx;
        this.callback = callback;
        this.wm = ctx.getSystemService(WindowManager.class);
        this.keyMap = KeyMap.fromJsonString(original.toJsonString()); // edit a copy
        this.bubbleX = bubbleX;
        this.bubbleY = bubbleY;
        this.bubbleSize = bubbleSize;

        root = new Root(ctx);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(0x66000000);

        canvas = new EditCanvas(ctx, keyMap);
        root.addView(canvas, new FrameLayout.LayoutParams(-1, -1));

        // replica of the floating button: tap = save & exit
        bubble = new IconView(ctx, IconView.Kind.GAMEPAD, 0xFFFFFFFF, GREEN);
        bubble.setOnClickListener(v -> close(true));
        root.addView(bubble, new FrameLayout.LayoutParams(bubbleSize, bubbleSize));

        toolbar = new LinearLayout(ctx);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackground(round(0xF0101010, dp(30)));
        toolbar.setPadding(dp(14), dp(6), dp(14), dp(6));
        toolbar.addView(toolIcon(IconView.Kind.PLUS, v -> togglePanel(Panel.ADD)));
        toolbar.addView(toolIcon(IconView.Kind.LAYERS, v -> togglePanel(Panel.LAYERS)));
        toolbar.addView(toolIcon(IconView.Kind.KEYBOARD, v -> toggleLabels()));
        toolbar.addView(toolIcon(IconView.Kind.SLIDERS, v -> togglePanel(Panel.SETTINGS)));
        root.addView(toolbar, new FrameLayout.LayoutParams(-2, -2));

        panel = new FrameLayout(ctx);
        panel.setBackground(round(CARD_BG, dp(26)));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        root.addView(panel, new FrameLayout.LayoutParams(dp(300), -2));

        popup = new LinearLayout(ctx);
        popup.setOrientation(LinearLayout.VERTICAL);
        popup.setBackground(round(0xF01C1C1E, dp(18)));
        popup.setPadding(dp(12), dp(10), dp(12), dp(12));
        popup.setVisibility(View.GONE);
        popup.setClickable(true);
        root.addView(popup, new FrameLayout.LayoutParams(dp(250), -2));

        hint = new TextView(ctx);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(round(0xE6000000, dp(18)));
        hint.setPadding(dp(16), dp(8), dp(16), dp(8));
        hint.setVisibility(View.GONE);
        root.addView(hint, new FrameLayout.LayoutParams(-2, -2));

        View.OnLayoutChangeListener relayoutOnChange = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) relayout();
        };
        root.addOnLayoutChangeListener(relayoutOnChange);
        toolbar.addOnLayoutChangeListener(relayoutOnChange);
        panel.addOnLayoutChangeListener(relayoutOnChange);
        popup.addOnLayoutChangeListener(relayoutOnChange);
        hint.addOnLayoutChangeListener(relayoutOnChange);
        showHint("دکمه‌ها را بکش و جابه‌جا کن • برای ذخیره و خروج روی دکمه سبز بزن", 4000);
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

    // ------------------------------------------------------------------ layout

    /** Area not covered by status bar, navigation bar or camera cutout. */
    private Rect safeArea() {
        Rect r = new Rect(0, 0, root.getWidth(), root.getHeight());
        WindowInsets wi = root.getRootWindowInsets();
        if (wi != null) {
            Insets in = wi.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            r.left += in.left;
            r.top += in.top;
            r.right -= in.right;
            r.bottom -= in.bottom;
        }
        r.inset(dp(8), dp(8));
        return r;
    }

    private void positionUi() {
        if (root.getWidth() == 0) return;
        Rect safe = safeArea();
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);

        float bx = clamp(bubbleX - loc[0], safe.left, safe.right - bubbleSize);
        float by = clamp(bubbleY - loc[1], safe.top, safe.bottom - bubbleSize);
        bubble.setX(bx);
        bubble.setY(by);

        boolean leftSide = bx + bubbleSize / 2f < root.getWidth() / 2f;
        int gap = dp(12);
        int tw = toolbar.getWidth();
        int th = toolbar.getHeight();
        float tx = leftSide ? bx + bubbleSize + gap : bx - gap - tw;
        float ty = by + bubbleSize / 2f - th / 2f;
        tx = clamp(tx, safe.left, safe.right - tw);
        ty = clamp(ty, safe.top, safe.bottom - th);
        toolbar.setX(tx);
        toolbar.setY(ty);

        if (panel.getVisibility() == View.VISIBLE) {
            int pw = panel.getWidth();
            int ph = panel.getHeight();
            float px = leftSide ? tx : tx + tw - pw;
            float py = ty + th + gap;
            if (py + ph > safe.bottom) py = ty - gap - ph;      // no room below: open upwards
            px = clamp(px, safe.left, safe.right - pw);
            py = clamp(py, safe.top, safe.bottom - ph);
            panel.setX(px);
            panel.setY(py);
        }

        if (popup.getVisibility() == View.VISIBLE && canvas.selected != null) {
            int pw = popup.getWidth();
            int ph = popup.getHeight();
            float ex = canvas.toViewX(canvas.selected.fx);
            float ey = canvas.toViewY(canvas.selected.fy);
            float off = dp(40);
            float px = ex + off + pw < safe.right ? ex + off : ex - off - pw;
            float py = ey - ph / 2f;
            popup.setX(clamp(px, safe.left, safe.right - pw));
            popup.setY(clamp(py, safe.top, safe.bottom - ph));
        }

        hint.setX(clamp(root.getWidth() / 2f - hint.getWidth() / 2f, safe.left, safe.right - hint.getWidth()));
        hint.setY(safe.top);
    }

    private static float clamp(float v, float min, float max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, v));
    }

    private void relayout() {
        root.post(this::positionUi);
    }

    // ------------------------------------------------------------------ toolbar actions

    private void togglePanel(Panel p) {
        hidePopup();
        if (openPanel == p) {
            openPanel = Panel.NONE;
            panel.setVisibility(View.GONE);
            return;
        }
        openPanel = p;
        resetArmed = false;
        panel.removeAllViews();
        switch (p) {
            case ADD:
                panel.addView(buildAddPanel());
                break;
            case LAYERS:
                panel.addView(buildLayersPanel());
                break;
            case SETTINGS:
                panel.addView(buildSettingsPanel());
                break;
            default:
                break;
        }
        panel.setVisibility(View.VISIBLE);
        relayout();
    }

    private void closePanel() {
        openPanel = Panel.NONE;
        panel.setVisibility(View.GONE);
    }

    private void toggleLabels() {
        boolean on = !Prefs.showLabels(ctx);
        Prefs.setShowLabels(ctx, on);
        showHint(on ? "برچسب کلیدها هنگام بازی نمایش داده می‌شوند" : "برچسب کلیدها هنگام بازی مخفی می‌شوند", 2500);
    }

    private void close(boolean save) {
        dismiss();
        callback.onEditorClosed(save ? keyMap : null);
    }

    // ------------------------------------------------------------------ panels

    private View buildAddPanel() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(16), dp(18), dp(16), 0);

        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);

        if (addPage == 0) {
            LinearLayout row1 = new LinearLayout(ctx);
            row1.setGravity(Gravity.CENTER_VERTICAL);
            row1.addView(item(IconView.Kind.MOUSE, DARK, "موس", v -> addLook()));
            TextView reset = new TextView(ctx);
            reset.setText(resetArmed ? "مطمئنی؟" : "ریست");
            reset.setTextColor(0xFFFFFFFF);
            reset.setTypeface(Typeface.DEFAULT_BOLD);
            reset.setTextSize(15);
            reset.setGravity(Gravity.CENTER);
            reset.setBackground(round(resetArmed ? 0xFFE53935 : GREEN, dp(22)));
            reset.setOnClickListener(v -> {
                if (!resetArmed) {
                    resetArmed = true;
                    rebuildPanel();
                } else {
                    keyMap.elements.clear();
                    select(null);
                    resetArmed = false;
                    rebuildPanel();
                    showHint("همه دکمه‌ها پاک شدند", 2000);
                }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(100), dp(44));
            rlp.leftMargin = dp(10);
            rlp.rightMargin = dp(10);
            rlp.bottomMargin = dp(22);
            row1.addView(reset, rlp);
            row1.addView(item(IconView.Kind.KEY, DARK, "کلید", v -> addTap(KeyMap.TAP)));
            page.addView(row1);

            LinearLayout row2 = new LinearLayout(ctx);
            row2.setGravity(Gravity.CENTER);
            row2.setPadding(0, dp(10), 0, 0);
            row2.addView(item(IconView.Kind.WASD, LIGHT_ITEM, "WASD", v -> addJoystick(17, 30, 31, 32, false)));
            row2.addView(space(dp(36)));
            row2.addView(item(IconView.Kind.ARROWS, LIGHT_ITEM, "جهت‌ها", v -> addJoystick(103, 105, 108, 106, false)));
            page.addView(row2);
        } else {
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(4), 0, 0);
            row.addView(item(IconView.Kind.HOLD, DARK, "نگه‌داشتن", v -> addTap(KeyMap.TOGGLE)));
            row.addView(space(dp(36)));
            row.addView(item(IconView.Kind.WASD, LIGHT_ITEM, "جوی‌استیک دلخواه", v -> addJoystick(17, 30, 31, 32, true)));
            page.addView(row);
            TextView note = small("نگه‌داشتن: یک بار زدن = انگشت روی صفحه می‌ماند، بار دوم = برداشته می‌شود", 0xFF555555);
            note.setPadding(dp(4), dp(10), dp(4), 0);
            page.addView(note);
        }
        col.addView(page, new LinearLayout.LayoutParams(-1, -2));

        // swipe left/right to change page
        page.setOnTouchListener(new View.OnTouchListener() {
            float sx;

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    sx = e.getX();
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(e.getX() - sx) > dp(50)) {
                    addPage = addPage == 0 ? 1 : 0;
                    rebuildPanel();
                }
                return true;
            }
        });

        LinearLayout dots = new LinearLayout(ctx);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(16), 0, dp(4));
        for (int i = 0; i < 2; i++) {
            View d = new View(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (i == addPage) g.setColor(0xFF2A2A30);
            else g.setStroke(dp(3), 0xFFAAAAAA);
            d.setBackground(g);
            final int page2 = i;
            d.setOnClickListener(v -> {
                addPage = page2;
                rebuildPanel();
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(18), dp(18));
            dl.leftMargin = dp(8);
            dl.rightMargin = dp(8);
            dots.addView(d, dl);
        }
        col.addView(dots);

        IconView up = new IconView(ctx, IconView.Kind.CHEVRON_UP, 0xFF888888, 0xFFE2E2E6);
        up.setOnClickListener(v -> closePanel());
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(dp(56), dp(44));
        ul.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(up, ul);
        return col;
    }

    private View buildLayersPanel() {
        LinearLayout col = cardColumn("چیدمان‌ها");
        col.addView(row("چیدمان پیش‌فرض Oxide", "جای دکمه‌ها حدسی است؛ بعد جابه‌جا کن", v -> {
            KeyMap def = KeyMap.oxideDefault();
            keyMap.elements.clear();
            keyMap.elements.addAll(def.elements);
            select(null);
            closePanel();
            showHint("چیدمان Oxide بارگذاری شد", 2000);
        }));
        col.addView(row("پاک کردن همه دکمه‌ها", "صفحه خالی برای چیدن از اول", v -> {
            keyMap.elements.clear();
            select(null);
            closePanel();
        }));
        return col;
    }

    private View buildSettingsPanel() {
        LinearLayout col = cardColumn("تنظیمات");
        col.addView(row("کلید تغییر حالت: " + KeyNames.name(keyMap.toggleKey),
                "بین حالت بازی و موس معمولی جابه‌جا می‌کند – بزن و کلید جدید را فشار بده", v -> {
                    closePanel();
                    startWait(Wait.TOGGLE);
                }));
        col.addView(row(Prefs.showLabels(ctx) ? "برچسب کلیدها در بازی: روشن" : "برچسب کلیدها در بازی: خاموش",
                "نمایش کم‌رنگ دکمه‌ها روی صفحه بازی", v -> {
                    toggleLabels();
                    rebuildPanel();
                }));
        col.addView(row("خروج بدون ذخیره", "تغییرات این دفعه دور ریخته می‌شود", v -> close(false)));
        col.addView(row("خاموش کردن Aspar Mapper", "دکمه شناور و سرویس بسته می‌شود", v -> {
            dismiss();
            callback.onStopRequested();
        }));
        return col;
    }

    private void rebuildPanel() {
        Panel p = openPanel;
        openPanel = Panel.NONE;
        togglePanel(p);
    }

    // ------------------------------------------------------------------ adding elements

    private KeyMap.Element newElementAtCenter(String type) {
        KeyMap.Element e = new KeyMap.Element();
        e.type = type;
        float shift = (keyMap.elements.size() % 5) * 0.03f;
        e.fx = 0.5f + shift;
        e.fy = 0.45f + shift;
        return e;
    }

    private void addLook() {
        for (KeyMap.Element e : keyMap.elements) {
            if (KeyMap.LOOK.equals(e.type)) {
                closePanel();
                select(e);
                showHint("ناحیه موس (دوربین) از قبل هست – همین را جابه‌جا کن", 2500);
                return;
            }
        }
        KeyMap.Element e = newElementAtCenter(KeyMap.LOOK);
        e.fx = 0.68f;
        e.fy = 0.40f;
        e.size = 0.25f;
        keyMap.elements.add(e);
        closePanel();
        select(e);
        showHint("وسط ناحیه دوربین را روی یک جای خالی سمت راست صفحه بازی بگذار", 3500);
    }

    private void addTap(String type) {
        KeyMap.Element e = newElementAtCenter(type);
        keyMap.elements.add(e);
        closePanel();
        select(e);
    }

    private void addJoystick(int up, int left, int down, int right, boolean askKeys) {
        KeyMap.Element e = newElementAtCenter(KeyMap.JOYSTICK);
        e.fx = 0.15f;
        e.fy = 0.70f;
        e.size = 0.14f;
        e.up = up;
        e.left = left;
        e.down = down;
        e.right = right;
        keyMap.elements.add(e);
        closePanel();
        select(e);
        if (askKeys) startWait(Wait.DIR_UP);
        else showHint("مرکز دایره را دقیقاً روی مرکز جوی‌استیک بازی بگذار", 3000);
    }

    // ------------------------------------------------------------------ selection popup

    private void select(KeyMap.Element e) {
        canvas.selected = e;
        wait = Wait.NONE;
        canvas.invalidate();
        if (e == null) {
            hidePopup();
            return;
        }
        if (KeyMap.TAP.equals(e.type) || KeyMap.TOGGLE.equals(e.type)) wait = Wait.KEY;
        buildPopup();
    }

    private void hidePopup() {
        popup.setVisibility(View.GONE);
        if (wait == Wait.KEY) wait = Wait.NONE;
    }

    private void buildPopup() {
        KeyMap.Element e = canvas.selected;
        popup.removeAllViews();
        if (e == null) {
            popup.setVisibility(View.GONE);
            return;
        }
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(15);
        title.setText(typeName(e));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        IconView del = new IconView(ctx, IconView.Kind.TRASH, 0xFFFF6B6B, 0);
        del.setOnClickListener(v -> {
            keyMap.elements.remove(e);
            select(null);
        });
        head.addView(del, new LinearLayout.LayoutParams(dp(34), dp(34)));
        IconView x = new IconView(ctx, IconView.Kind.CLOSE, 0xFFBBBBBB, 0);
        x.setOnClickListener(v -> select(null));
        head.addView(x, new LinearLayout.LayoutParams(dp(34), dp(34)));
        popup.addView(head);

        if (KeyMap.TAP.equals(e.type) || KeyMap.TOGGLE.equals(e.type)) {
            popup.addView(small("کلید فعلی: " + KeyNames.name(e.key), 0xFFFFFFFF));
            popup.addView(small("برای تغییر، یک کلید روی کیبورد بزن یا دکمه موس را انتخاب کن:", 0xFFAAAAAA));
            LinearLayout chips = new LinearLayout(ctx);
            chips.addView(chip("کلیک چپ", v -> assign(KeyNames.BTN_LEFT)));
            chips.addView(chip("کلیک راست", v -> assign(KeyNames.BTN_RIGHT)));
            chips.addView(chip("وسط", v -> assign(KeyNames.BTN_MIDDLE)));
            chips.addView(chip("چرخ ↑", v -> assign(KeyNames.WHEEL_UP)));
            chips.addView(chip("چرخ ↓", v -> assign(KeyNames.WHEEL_DOWN)));
            chips.addView(chip("کناری", v -> assign(KeyNames.BTN_SIDE)));
            HorizontalScrollView hs = new HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(chips);
            popup.addView(hs);
        } else if (KeyMap.JOYSTICK.equals(e.type)) {
            popup.addView(small("کلیدها: " + KeyNames.name(e.up) + " " + KeyNames.name(e.left) + " "
                    + KeyNames.name(e.down) + " " + KeyNames.name(e.right), 0xFFFFFFFF));
            LinearLayout r = new LinearLayout(ctx);
            r.addView(chip("تغییر کلیدها", v -> startWait(Wait.DIR_UP)));
            r.addView(chip("اندازه −", v -> resize(e, -0.01f)));
            r.addView(chip("اندازه +", v -> resize(e, 0.01f)));
            popup.addView(r);
        } else if (KeyMap.LOOK.equals(e.type)) {
            popup.addView(small(String.format(Locale.US, "حساسیت: %.1f", e.sens), 0xFFFFFFFF));
            LinearLayout r1 = new LinearLayout(ctx);
            r1.addView(chip("حساسیت −", v -> sens(e, -0.1f)));
            r1.addView(chip("حساسیت +", v -> sens(e, 0.1f)));
            popup.addView(r1);
            LinearLayout r2 = new LinearLayout(ctx);
            r2.addView(chip("محدوده −", v -> resize(e, -0.02f)));
            r2.addView(chip("محدوده +", v -> resize(e, 0.02f)));
            popup.addView(r2);
        }
        popup.setVisibility(View.VISIBLE);
        relayout();
    }

    private String typeName(KeyMap.Element e) {
        switch (e.type) {
            case KeyMap.JOYSTICK:
                return "جوی‌استیک";
            case KeyMap.LOOK:
                return "موس (دوربین)";
            case KeyMap.TOGGLE:
                return "نگه‌داشتن";
            default:
                return "کلید";
        }
    }

    private void assign(int code) {
        KeyMap.Element e = canvas.selected;
        if (e == null) return;
        e.key = code;
        canvas.invalidate();
        buildPopup();
        showHint("کلید «" + KeyNames.name(code) + "» ثبت شد", 1500);
    }

    private void resize(KeyMap.Element e, float d) {
        e.size = Math.max(0.04f, Math.min(0.45f, e.size + d));
        canvas.invalidate();
        relayout();
    }

    private void sens(KeyMap.Element e, float d) {
        e.sens = Math.max(0.1f, Math.min(5f, Math.round((e.sens + d) * 10f) / 10f));
        buildPopup();
    }

    // ------------------------------------------------------------------ keyboard input

    private void startWait(Wait w) {
        wait = w;
        switch (w) {
            case TOGGLE:
                showHint("کلید جدید برای تغییر حالت را بزن", 0);
                break;
            case DIR_UP:
                showHint("کلید «جلو» را بزن", 0);
                break;
            case DIR_LEFT:
                showHint("کلید «چپ» را بزن", 0);
                break;
            case DIR_DOWN:
                showHint("کلید «عقب» را بزن", 0);
                break;
            case DIR_RIGHT:
                showHint("کلید «راست» را بزن", 0);
                break;
            default:
                break;
        }
    }

    private boolean onKey(KeyEvent ev) {
        if (wait == Wait.NONE) return false; // right-click arrives as BACK: never close on it
        if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) return true;
        int code = ev.getScanCode();
        if (code <= 0) return true; // not from a physical keyboard (e.g. mouse right-click)
        KeyMap.Element e = canvas.selected;
        switch (wait) {
            case TOGGLE:
                keyMap.toggleKey = code;
                wait = Wait.NONE;
                showHint("کلید تغییر حالت: " + KeyNames.name(code), 2000);
                return true;
            case KEY:
                if (code == keyMap.toggleKey) {
                    showHint("این کلید برای تغییر حالت است؛ یکی دیگر بزن", 2500);
                    return true;
                }
                if (e != null) assign(code);
                return true;
            case DIR_UP:
                if (e != null) e.up = code;
                startWait(Wait.DIR_LEFT);
                break;
            case DIR_LEFT:
                if (e != null) e.left = code;
                startWait(Wait.DIR_DOWN);
                break;
            case DIR_DOWN:
                if (e != null) e.down = code;
                startWait(Wait.DIR_RIGHT);
                break;
            case DIR_RIGHT:
                if (e != null) e.right = code;
                wait = Wait.NONE;
                showHint("کلیدهای جوی‌استیک ثبت شد", 1500);
                break;
            default:
                break;
        }
        canvas.invalidate();
        if (e != null) buildPopup();
        return true;
    }

    // ------------------------------------------------------------------ small view helpers

    private final Runnable hideHint = this::hideHintNow;

    private void hideHintNow() {
        hint.setVisibility(View.GONE);
    }

    private void showHint(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHint);
        if (ms > 0) main.postDelayed(hideHint, ms);
        relayout();
    }

    private int dp(float v) {
        return Ui.dp(ctx, v);
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private View toolIcon(IconView.Kind kind, View.OnClickListener l) {
        IconView v = new IconView(ctx, kind, 0xFFFFFFFF, 0);
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private View item(IconView.Kind kind, int circle, String label, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        IconView icon = new IconView(ctx, kind, 0xFFFFFFFF, circle);
        box.addView(icon, new LinearLayout.LayoutParams(dp(62), dp(62)));
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(0xFF333333);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(dp(90), -2));
        box.setOnClickListener(l);
        icon.setOnClickListener(l);
        return box;
    }

    private View space(int w) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private TextView small(String s, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setBackground(round(0xFF3A3A3F, dp(14)));
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout cardColumn(String title) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(16), dp(18), dp(14));
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(0xFF111111);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(17);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        IconView x = new IconView(ctx, IconView.Kind.CLOSE, 0xFF777777, 0);
        x.setOnClickListener(v -> closePanel());
        head.addView(x, new LinearLayout.LayoutParams(dp(32), dp(32)));
        col.addView(head);
        return col;
    }

    private View row(String title, String subtitle, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(0xFFFFFFFF, dp(14)));
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(0xFF111111);
        t.setTextSize(15);
        r.addView(t);
        TextView s = new TextView(ctx);
        s.setText(subtitle);
        s.setTextColor(0xFF777777);
        s.setTextSize(12);
        r.addView(s);
        r.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    // ------------------------------------------------------------------ views

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
                case MotionEvent.ACTION_DOWN:
                    dragging = hit(ev.getX(), ev.getY());
                    downX = ev.getX();
                    downY = ev.getY();
                    moved = false;
                    if (dragging != null) {
                        grabDx = toViewX(dragging.fx) - ev.getX();
                        grabDy = toViewY(dragging.fy) - ev.getY();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging == null) return true;
                    if (Math.hypot(ev.getX() - downX, ev.getY() - downY) > dp(6)) {
                        if (!moved) popup.setVisibility(View.GONE);
                        moved = true;
                    }
                    if (moved) {
                        dragging.fx = clamp(toFractionX(ev.getX() + grabDx), 0f, 1f);
                        dragging.fy = clamp(toFractionY(ev.getY() + grabDy), 0f, 1f);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging != null) {
                        select(dragging); // after a drag or a tap: show its settings next to it
                    } else if (!moved) {
                        select(null);
                        closePanel();
                    }
                    dragging = null;
                    return true;
                default:
                    return true;
            }
        }

        private KeyMap.Element hit(float x, float y) {
            Point s = screen();
            KeyMap.Element best = null;
            double bestD = dp(34);
            for (KeyMap.Element e : keyMap.elements) {
                double d = Math.hypot(toViewX(e.fx) - x, toViewY(e.fy) - y);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
            if (best == null) {
                for (KeyMap.Element e : keyMap.elements) {
                    if (!KeyMap.JOYSTICK.equals(e.type) && !KeyMap.LOOK.equals(e.type)) continue;
                    double d = Math.hypot(toViewX(e.fx) - x, toViewY(e.fy) - y);
                    if (d < e.size * s.y) return e;
                }
            }
            return best;
        }
    }
}
