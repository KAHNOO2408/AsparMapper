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

        // 4. play
        LinearLayout c4 = card(col, "۴. بازی");
        c4.addView(btn("نمایش دکمه شناور", v -> MapperService.send(this, MapperService.ACTION_SHOW)));
        c4.addView(btn("ویرایش کلیدها (اول بازی را باز کن)", v -> {
            MapperService.send(this, MapperService.ACTION_EDIT);
            Toast.makeText(this, "بازی را باز کن؛ ویرایشگر روی آن باز است", Toast.LENGTH_LONG).show();
        }));
        c4.addView(text(
                "• بازی را باز کن و کلید ` (زیر Esc) را بزن تا «حالت بازی» روشن شود: نشانگر موس قفل می‌شود و حرکت موس دوربین را می‌چرخاند.\n"
                        + "• دوباره ` بزن تا به حالت موس معمولی برگردی (برای منوها و اینونتوری).\n"
                        + "• دکمه شناور: آبی = حالت موس، سبز = حالت بازی، خاکستری = سرویس وصل نیست.\n"
                        + "• یک ضربه روی دکمه شناور = ویرایش دکمه‌ها (+ افزودن، چیدمان‌ها، برچسب‌ها، تنظیمات). برای ذخیره دوباره روی دکمه سبز بزن.\n"
                        + "• نگه داشتن دکمه شناور = روشن/خاموش کردن حالت بازی (بدون کیبورد).\n"
                        + "• بار اول حتماً «ویرایش کلیدها» را روی صفحه بازی بزن و دایره‌ها را دقیقاً روی دکمه‌های بازی بکش.\n"
                        + "• قبل از رفتن به حالت بازی، نشانگر موس را به گوشه صفحه ببر.", 14, 0xFFE0E0E0));

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
    }

    @Override
    protected void onPause() {
        MapperService.setStatusListener(null);
        super.onPause();
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
