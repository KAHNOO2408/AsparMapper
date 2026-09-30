package ir.aspar.mapper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements MapperService.StatusListener {

    private TextView status;
    private TextView overlayState;
    private EditText pairPort;
    private EditText connectPort;
    private LinearLayout devicesBox;
    private LinearLayout gamesBox;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable devicePoll = new Runnable() {
        @Override
        public void run() {
            MapperService.requestDevices();
            refreshDevices();
            ui.postDelayed(this, 2000);
        }
    };
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int id) {
                    refreshDevices();
                }

                @Override
                public void onInputDeviceRemoved(int id) {
                    refreshDevices();
                }

                @Override
                public void onInputDeviceChanged(int id) {
                    refreshDevices();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        scroll.addView(col);
        setContentView(scroll);

        String version = "";
        try {
            version = " v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView title = text("Aspar Mapper" + version, 24, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(text("کنترل بازی با موس و کیبورد – مخصوص Oxide: Survival Island", 14, 0xFFB0BEC5));

        status = text("…", 15, 0xFFFFFFFF);
        status.setPadding(p, p, p, p);
        status.setBackground(round(0xFF263238));
        status.setTextIsSelectable(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = p;
        col.addView(status, slp);

        // devices
        LinearLayout cd = card(col, "🖱⌨️ موس و کیبورد");
        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        cd.addView(devicesBox);

        // games
        LinearLayout cg = card(col, "🎮 بازی‌های من");
        gamesBox = new LinearLayout(this);
        gamesBox.setOrientation(LinearLayout.VERTICAL);
        cg.addView(gamesBox);
        cg.addView(btn("+ افزودن بازی یا برنامه", v -> pickApp()));

        // 1. permissions
        LinearLayout c1 = card(col, "۱. دسترسی‌ها");
        overlayState = text("", 14, 0xFFFFFFFF);
        c1.addView(overlayState);
        c1.addView(btn("اجازه نمایش روی برنامه‌های دیگر", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));

        // 2. pairing
        LinearLayout c2 = card(col, "۲. جفت‌سازی (فقط بار اول)");
        c2.addView(text(
                "۱) گوشی باید به یک Wi-Fi وصل باشد (اینترنت لازم نیست؛ هر مودم یا هات‌اسپات کافی است).\n"
                        + "۲) اگر «گزینه‌های توسعه‌دهنده» را نداری: تنظیمات ← درباره تلفن ← اطلاعات نرم‌افزار ← ۷ بار روی «شماره ساخت» بزن.\n"
                        + "۳) دکمه «شروع جفت‌سازی» را بزن؛ یک اعلان ظاهر می‌شود.\n"
                        + "۴) در گزینه‌های توسعه‌دهنده، «اشکال‌زدایی بی‌سیم» (Wireless debugging) را روشن کن و واردش شو، بعد «جفت کردن دستگاه با کد» را بزن.\n"
                        + "۵) پنجره کد را باز نگه دار، نوار اعلان را پایین بکش و در اعلان Aspar Mapper کد ۶ رقمی را بنویس و بفرست.\n"
                        + "بعد از جفت‌سازی، سرویس خودکار فعال می‌شود.", 14, 0xFFE0E0E0));
        c2.addView(btn("شروع جفت‌سازی", v -> {
            requestNotificationPermission();
            PairingService.start(this, parse(pairPort));
            Toast.makeText(this, "حالا به تنظیمات برو و Pair device with pairing code را بزن", Toast.LENGTH_LONG).show();
        }));
        c2.addView(btn("باز کردن گزینه‌های توسعه‌دهنده", v -> openDevSettings()));
        pairPort = portField("پورت جفت‌سازی (اختیاری – فقط اگر خودکار پیدا نشد)");
        c2.addView(pairPort);

        // 3. activation
        LinearLayout c3 = card(col, "۳. فعال‌سازی (بعد از هر ری‌استارت گوشی)");
        c3.addView(text("«اشکال‌زدایی بی‌سیم» را روشن کن و دکمه زیر را بزن. بعد از فعال شدن می‌توانی اشکال‌زدایی بی‌سیم را خاموش کنی؛ سرویس تا ری‌استارت بعدی کار می‌کند.",
                14, 0xFFE0E0E0));
        c3.addView(btn("فعال‌سازی", v -> MapperService.send(this, MapperService.ACTION_ACTIVATE, parse(connectPort))));
        connectPort = portField("پورت اتصال (اختیاری – عدد بعد از : در «آدرس IP و درگاه»)");
        c3.addView(connectPort);
        c3.addView(btn("عیب‌یابی (گزارش کامل)", v -> MapperService.send(this, MapperService.ACTION_DIAG, parse(connectPort))));
        c3.addView(btn("کپی گزارش بالای صفحه", v -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Aspar Mapper", status.getText()));
            Toast.makeText(this, "کپی شد – برای Claude بفرست", Toast.LENGTH_SHORT).show();
        }));

        // 5. help
        LinearLayout c4 = card(col, "راهنمای بازی");
        c4.addView(text(
                "• بازی را از بخش «بازی‌های من» (بالای همین صفحه) با دکمه «اجرا» باز کن؛ دکمه شناور فقط داخل همان بازی ظاهر می‌شود.\n"
                        + "• کلید ` (زیر Esc) «حالت بازی» را روشن/خاموش می‌کند: نشانگر موس قفل می‌شود و حرکت موس دوربین را می‌چرخاند.\n"
                        + "• دکمه شناور: آبی = حالت موس، سبز = حالت بازی، خاکستری = سرویس وصل نیست.\n"
                        + "• یک ضربه روی دکمه شناور = ویرایش دکمه‌ها (+ افزودن، چیدمان‌ها، برچسب‌ها، تنظیمات). برای ذخیره دوباره روی دکمه سبز بزن.\n"
                        + "• نگه داشتن دکمه شناور = روشن/خاموش کردن حالت بازی (بدون کیبورد).\n"
                        + "• هر بازی چیدمان جداگانه خودش را دارد.\n"
                        + "• در حالت بازی دکمه شناور خودکار مخفی است. برای استریم: در تنظیمات دکمه شناور یک «کلید مخفی/نمایش» بگذار، یا از اعلان «مخفی کردن دکمه» را بزن.", 14, 0xFFE0E0E0));

        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MapperService.setStatusListener(this);
        boolean ok = Settings.canDrawOverlays(this);
        overlayState.setText(ok ? "✓ اجازه نمایش روی برنامه‌ها داده شده" : "✗ اجازه نمایش روی برنامه‌ها داده نشده");
        overlayState.setTextColor(ok ? 0xFF81C784 : 0xFFE57373);
        if (ok) MapperService.send(this, MapperService.ACTION_SHOW);
        getSystemService(android.hardware.input.InputManager.class).registerInputDeviceListener(deviceListener, ui);
        ui.post(devicePoll);
        refreshGames();
    }

    @Override
    protected void onPause() {
        MapperService.setStatusListener(null);
        getSystemService(android.hardware.input.InputManager.class).unregisterInputDeviceListener(deviceListener);
        ui.removeCallbacks(devicePoll);
        super.onPause();
    }

    // ------------------------------------------------------------------ devices

    private void refreshDevices() {
        if (devicesBox == null) return;
        java.util.List<String> mice = new java.util.ArrayList<>();
        java.util.List<String> keyboards = new java.util.ArrayList<>();
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d == null || d.isVirtual() || !d.isExternal()) continue;
            if (d.supportsSource(android.view.InputDevice.SOURCE_MOUSE) && !mice.contains(d.getName())) mice.add(d.getName());
            if (d.getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC && !keyboards.contains(d.getName())) {
                keyboards.add(d.getName());
            }
        }
        devicesBox.removeAllViews();
        devicesBox.addView(deviceLine("موس", mice));
        devicesBox.addView(deviceLine("کیبورد", keyboards));

        java.util.List<String> srv = MapperService.serverDevices();
        TextView t;
        if (srv.isEmpty()) {
            t = text("سرویس: هنوز دستگاهی در اختیار سرویس نیست (بعد از فعال‌سازی و وصل کردن دستگاه‌ها چند ثانیه صبر کن)", 12, 0xFF9E9E9E);
        } else {
            StringBuilder sb = new StringBuilder("سرویس آماده است با: ");
            for (int i = 0; i < srv.size(); i++) sb.append(i > 0 ? "، " : "").append(srv.get(i));
            t = text(sb.toString(), 12, 0xFF81C784);
        }
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        devicesBox.addView(t);
    }

    private TextView deviceLine(String kind, java.util.List<String> names) {
        boolean ok = !names.isEmpty();
        String s = ok ? "✓ " + kind + " وصل است: " + android.text.TextUtils.join("، ", names)
                : "✗ " + kind + " وصل نیست";
        return text(s, 15, ok ? 0xFF81C784 : 0xFFE57373);
    }

    // ------------------------------------------------------------------ games

    private void refreshGames() {
        gamesBox.removeAllViews();
        java.util.List<String> pkgs = Games.saved(this);
        if (pkgs.isEmpty()) {
            gamesBox.addView(text("هنوز بازی‌ای اضافه نکردی. با دکمه زیر بازی (مثلاً Oxide) را انتخاب کن.", 14, 0xFF9E9E9E));
            return;
        }
        for (String pkg : pkgs) {
            Games.App app = Games.info(this, pkg);
            if (app == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            TextView name = text(app.label, 16, 0xFFFFFFFF);
            name.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Button play = Ui.button(this, "▶ اجرا", v -> launch(pkg));
            row.addView(play);
            TextView del = text("✕", 20, 0xFF9E9E9E);
            del.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            del.setOnClickListener(v -> {
                Games.remove(this, pkg);
                refreshGames();
            });
            row.addView(del);
            gamesBox.addView(row);
        }
    }

    private void launch(String pkg) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "اول اجازه «نمایش روی برنامه‌های دیگر» را بده", Toast.LENGTH_LONG).show();
            return;
        }
        MapperService.launchGame(this, pkg);
        if (!Games.launch(this, pkg)) {
            Toast.makeText(this, "این برنامه باز نشد", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickApp() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        box.setPadding(p, p, p, 0);
        EditText search = new EditText(this);
        search.setHint("جستجو…");
        box.addView(search);
        TextView loading = text("در حال خواندن برنامه‌ها…", 14, 0xFF9E9E9E);
        box.addView(loading);
        android.widget.ListView list = new android.widget.ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(this, 420)));
        b.setTitle("انتخاب بازی یا برنامه");
        b.setView(box);
        b.setNegativeButton("بستن", null);
        android.app.AlertDialog dialog = b.show();

        new Thread(() -> {
            java.util.List<Games.App> all = Games.installed(this);
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                java.util.List<Games.App> shown = new java.util.ArrayList<>(all);
                android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
                    @Override
                    public int getCount() {
                        return shown.size();
                    }

                    @Override
                    public Object getItem(int i) {
                        return shown.get(i);
                    }

                    @Override
                    public long getItemId(int i) {
                        return i;
                    }

                    @Override
                    public View getView(int i, View convert, android.view.ViewGroup parent) {
                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 8), Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 8));
                        android.widget.ImageView icon = new android.widget.ImageView(MainActivity.this);
                        icon.setImageDrawable(shown.get(i).icon);
                        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 40), Ui.dp(MainActivity.this, 40)));
                        TextView t = new TextView(MainActivity.this);
                        t.setText(shown.get(i).label);
                        t.setTextSize(16);
                        t.setPadding(Ui.dp(MainActivity.this, 12), 0, 0, 0);
                        row.addView(t);
                        return row;
                    }
                };
                list.setAdapter(adapter);
                list.setOnItemClickListener((parent, view, pos, id) -> {
                    Games.add(this, shown.get(pos).pkg);
                    refreshGames();
                    dialog.dismiss();
                });
                search.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b2, int c) {
                        String q = s.toString().trim().toLowerCase();
                        shown.clear();
                        for (Games.App app : all) {
                            if (q.isEmpty() || app.label.toLowerCase().contains(q) || app.pkg.contains(q)) shown.add(app);
                        }
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                    }
                });
            });
        }, "load-apps").start();
    }

    @Override
    public void onStatus(boolean connected, boolean gameMode, String message) {
        String head = connected ? (gameMode ? "🟢 وصل – حالت بازی" : "🔵 وصل – حالت موس") : "⚪ سرویس وصل نیست";
        status.setText(head + (message == null || message.isEmpty() ? "" : "\n" + message));
    }

    // ------------------------------------------------------------------ helpers

    private void openDevSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private int parse(EditText e) {
        String s = PairingService.normalizeDigits(e.getText().toString());
        if (s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.25f);
        t.setGravity(Gravity.START);
        return t;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Ui.dp(this, 12));
        return d;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        c.setBackground(round(0xFF1E1E1E));
        TextView t = text(title, 17, 0xFF4FC3F7);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, Ui.dp(this, 8));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        parent.addView(c, lp);
        return c;
    }

    private Button btn(String s, View.OnClickListener l) {
        Button b = Ui.button(this, s, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText portField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF757575);
        e.setTextColor(0xFFFFFFFF);
        e.setTextSize(13);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }
}
