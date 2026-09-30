package ir.aspar.mapper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import ir.aspar.mapper.adb.Activator;

/**
 * First-time pairing with "Wireless debugging".
 * Android closes the pairing dialog if you leave Settings, so the 6-digit code is typed
 * straight into this notification's reply box while the dialog stays open.
 */
public class PairingService extends Service {

    public static final String ACTION_START = "ir.aspar.mapper.PAIR_START";
    public static final String ACTION_CODE = "ir.aspar.mapper.PAIR_CODE";
    public static final String ACTION_STOP = "ir.aspar.mapper.PAIR_STOP";
    public static final String EXTRA_MANUAL_PORT = "port";
    private static final String KEY_CODE = "code";
    private static final String CHANNEL = "pairing";
    private static final int NOTIF_ID = 11;

    private volatile int pairingPort = -1;
    private volatile boolean busy;

    public static void start(Context ctx, int manualPort) {
        Intent i = new Intent(ctx, PairingService.class).setAction(ACTION_START).putExtra(EXTRA_MANUAL_PORT, manualPort);
        ctx.startForegroundService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CODE.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = results != null ? results.getCharSequence(KEY_CODE) : null;
            if (code != null) onCode(code.toString());
            return START_NOT_STICKY;
        }

        // ACTION_START
        goForeground(buildAskNotification("منتظر پنجره جفت‌سازی…",
                "در تنظیمات: Wireless debugging ← Pair device with pairing code را بزن، سپس کد ۶ رقمی را همین‌جا وارد کن."));
        int manual = intent != null ? intent.getIntExtra(EXTRA_MANUAL_PORT, 0) : 0;
        if (manual > 0) {
            pairingPort = manual;
        } else {
            new Thread(() -> {
                try {
                    int p = Activator.discoverPairingPort(this, 180);
                    if (p > 0) {
                        pairingPort = p;
                        update(buildAskNotification("پنجره جفت‌سازی پیدا شد ✓",
                                "کد ۶ رقمی نوشته شده در پنجره را اینجا وارد کن."));
                    } else if (!busy) {
                        update(buildInfo("پنجره جفت‌سازی پیدا نشد",
                                "دوباره از داخل برنامه «جفت‌سازی» را بزن. اگر باز هم پیدا نشد، پورت را دستی وارد کن."));
                    }
                } catch (InterruptedException ignored) {
                }
            }, "pair-discovery").start();
        }
        return START_NOT_STICKY;
    }

    private void onCode(String rawCode) {
        if (busy) return;
        busy = true;
        final String code = normalizeDigits(rawCode);
        update(buildInfo("در حال جفت‌سازی…", "چند ثانیه صبر کن"));
        new Thread(() -> {
            try {
                long wait = System.currentTimeMillis() + 15000;
                while (pairingPort <= 0 && System.currentTimeMillis() < wait) Thread.sleep(200);
                if (pairingPort <= 0) throw new IllegalStateException("پورت جفت‌سازی پیدا نشد");
                Activator.pair(this, pairingPort, code);
                Prefs.setPaired(this, true);
                update(buildInfo("جفت‌سازی موفق بود ✓", "در حال فعال‌سازی سرویس…"));
                String r = Activator.launchServer(this, 0);
                update(buildInfo("آماده است ✓", "سرویس روشن شد. به برنامه برگرد. (" + r + ")"));
            } catch (Throwable t) {
                update(buildAskNotification("خطا: " + t.getMessage(),
                        "دوباره Pair device with pairing code را باز کن و کد جدید را وارد کن."));
            } finally {
                busy = false;
            }
        }, "pairing").start();
    }

    /** A Persian keyboard types ۱۲۳ instead of 123 – convert and drop anything that is not a digit. */
    static String normalizeDigits(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') sb.append(c);
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ notifications

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void update(Notification n) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }

    private Notification.Builder base(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "جفت‌سازی", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PairingService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(false)
                .addAction(new Notification.Action.Builder(null, "بستن", stop).build());
    }

    private Notification buildInfo(String title, String text) {
        return base(title, text).build();
    }

    private Notification buildAskNotification(String title, String text) {
        RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel("کد جفت‌سازی (۶ رقم)").build();
        PendingIntent pi = PendingIntent.getService(this, 1,
                new Intent(this, PairingService.class).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action action = new Notification.Action.Builder(null, "وارد کردن کد", pi)
                .addRemoteInput(input)
                .build();
        return base(title, text).addAction(action).build();
    }
}
