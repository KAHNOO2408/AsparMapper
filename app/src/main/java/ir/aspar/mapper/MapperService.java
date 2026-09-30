package ir.aspar.mapper;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import ir.aspar.mapper.adb.Activator;

/**
 * Runs while the mapper is in use: keeps the link to the helper, shows the floating button,
 * the key labels on top of the game and the editor.
 */
public class MapperService extends Service implements ServerClient.Listener {

    public static final String ACTION_SHOW = "ir.aspar.mapper.SHOW";
    public static final String ACTION_ACTIVATE = "ir.aspar.mapper.ACTIVATE";
    public static final String ACTION_EDIT = "ir.aspar.mapper.EDIT";
    public static final String ACTION_TOGGLE = "ir.aspar.mapper.TOGGLE";
    public static final String ACTION_STOP = "ir.aspar.mapper.STOP";
    public static final String ACTION_DIAG = "ir.aspar.mapper.DIAG";
    public static final String EXTRA_PORT = "port";

    private static final String CHANNEL = "mapper";
    private static final int NOTIF_ID = 21;

    /** Status for the main screen. */
    public interface StatusListener {
        void onStatus(boolean connected, boolean gameMode, String message);
    }

    private static volatile StatusListener statusListener;
    private static volatile MapperService running;
    private static volatile String lastMessage = "";

    public static void setStatusListener(StatusListener l) {
        statusListener = l;
        MapperService s = running;
        if (l != null) {
            l.onStatus(s != null && s.client != null && s.client.isConnected(),
                    s != null && s.client != null && s.client.isGameMode(), lastMessage);
        }
    }

    public static void send(Context ctx, String action) {
        send(ctx, action, 0);
    }

    public static void send(Context ctx, String action, int port) {
        Intent i = new Intent(ctx, MapperService.class).setAction(action).putExtra(EXTRA_PORT, port);
        ctx.startForegroundService(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private ServerClient client;
    private KeyMap keyMap;

    private FrameLayout bubbleRoot;
    private IconView bubble;
    private boolean longPressed;
    private WindowManager.LayoutParams bubbleLp;
    private KeymapView labels;
    private EditorOverlay editor;
    private boolean connected;
    private boolean gameMode;
    private volatile boolean activating;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        wm = getSystemService(WindowManager.class);
        keyMap = KeyMap.load(this);
        goForeground();
        client = new ServerClient(this, this);
        pushConfig();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent != null ? intent.getAction() : ACTION_SHOW;
        if (action == null) action = ACTION_SHOW;
        switch (action) {
            case ACTION_ACTIVATE:
                activate(intent.getIntExtra(EXTRA_PORT, 0));
                break;
            case ACTION_DIAG:
                diagnose(intent.getIntExtra(EXTRA_PORT, 0));
                break;
            case ACTION_EDIT:
                showOverlays();
                openEditor();
                break;
            case ACTION_TOGGLE:
                if (client != null) client.setGameMode(!gameMode);
                break;
            case ACTION_STOP:
                if (client != null) client.setGameMode(false);
                main.postDelayed(this::stopSelf, 200);
                break;
            default:
                showOverlays();
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        if (editor != null) editor.dismiss();
        removeView(bubbleRoot);
        removeView(labels);
        if (client != null) client.close();
        report("متوقف شد");
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // rotation changed: pixel positions must be recomputed
        main.postDelayed(() -> {
            pushConfig();
            if (labels != null) labels.invalidate();
        }, 300);
    }

    // ------------------------------------------------------------------ activation

    private void activate(int port) {
        if (activating) return;
        activating = true;
        report("در حال فعال‌سازی…");
        new Thread(() -> {
            try {
                if (client != null) client.quitServer();
                String r = Activator.launchServer(this, port);
                Prefs.get(this).edit().putString("last_activation", "OK: " + r).apply();
                report("سرویس اجرا شد ✓ " + r);
                main.post(this::showOverlays);
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                if (t instanceof io.github.muntashirakon.adb.AdbPairingRequiredException) {
                    msg = "اول باید «جفت‌سازی» را انجام بدی.";
                    Prefs.setPaired(this, false);
                }
                Prefs.get(this).edit().putString("last_activation", "FAILED: " + msg).apply();
                report("فعال‌سازی ناموفق: " + msg);
            } finally {
                activating = false;
            }
        }, "activate").start();
    }

    private void diagnose(int port) {
        report("در حال جمع‌آوری گزارش… (اشکال‌زدایی بی‌سیم باید روشن باشد)");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("== app: connected=").append(client != null && client.isConnected())
                    .append(" lastError=").append(client != null ? client.lastError() : "-")
                    .append(" token=").append(Prefs.token(this).length())
                    .append(" version=").append(ServerClient.EXPECTED_VERSION).append('\n');
            sb.append("== apk: ").append(getApplicationInfo().sourceDir).append('\n');
            sb.append("== last activation: ").append(Prefs.get(this).getString("last_activation", "never")).append('\n');
            try {
                sb.append(Activator.diagnose(this, port));
            } catch (Throwable t) {
                sb.append("== adb error: ").append(t);
            }
            report(sb.toString());
        }, "diagnose").start();
    }

    private void report(String message) {
        lastMessage = message;
        main.post(() -> {
            StatusListener l = statusListener;
            if (l != null) l.onStatus(connected, gameMode, message);
        });
    }

    // ------------------------------------------------------------------ server events

    @Override
    public void onConnectionChanged(boolean c, String info) {
        connected = c;
        if (c) pushConfig();
        updateBubble();
        report(c ? "سرویس " + info : "سرویس در دسترس نیست – «فعال‌سازی» را بزن\n(" + client.lastError() + ")");
    }

    @Override
    public void onGameModeChanged(boolean on) {
        gameMode = on;
        updateBubble();
        if (labels != null) labels.setVisibility(on && Prefs.showLabels(this) ? View.VISIBLE : View.GONE);
        StatusListener l = statusListener;
        if (l != null) l.onStatus(connected, gameMode, lastMessage);
    }

    private void pushConfig() {
        if (client == null) return;
        try {
            Point s = Ui.realSize(this);
            client.sendConfig(keyMap.toServerConfig(s.x, s.y));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ overlays

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(this);
    }

    private void showOverlays() {
        if (!canDrawOverlays()) {
            report("اجازه «نمایش روی برنامه‌های دیگر» داده نشده");
            return;
        }
        if (labels == null) {
            labels = new KeymapView(this, keyMap);
            WindowManager.LayoutParams lp = Ui.fullScreen(false, false);
            // Android blocks touches passing through overlays that are more than 80% opaque.
            lp.alpha = 0.6f;
            labels.setVisibility(gameMode && Prefs.showLabels(this) ? View.VISIBLE : View.GONE);
            wm.addView(labels, lp);
        }
        if (bubbleRoot == null) createBubble();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createBubble() {
        bubbleRoot = new FrameLayout(this);
        int size = Ui.dp(this, 54);
        bubble = new IconView(this, IconView.Kind.GAMEPAD, 0xFFFFFFFF, 0xFF757575);
        bubbleRoot.addView(bubble, new FrameLayout.LayoutParams(size, size));

        bubbleLp = Ui.wrap(Ui.dp(this, 8), Ui.dp(this, 90));
        wm.addView(bubbleRoot, bubbleLp);

        final Runnable longPress = () -> {
            longPressed = true;
            if (client != null && connected) {
                client.setGameMode(!gameMode);
            } else {
                Toast.makeText(this, "سرویس وصل نیست – از داخل برنامه «فعال‌سازی» را بزن", Toast.LENGTH_SHORT).show();
            }
        };

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = bubbleLp.x;
                        oy = bubbleLp.y;
                        moved = false;
                        longPressed = false;
                        main.postDelayed(longPress, 600);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx;
                        float dy = e.getRawY() - sy;
                        if (!moved && Math.abs(dx) + Math.abs(dy) > Ui.dp(MapperService.this, 8)) {
                            moved = true;
                            main.removeCallbacks(longPress);
                        }
                        if (moved) {
                            bubbleLp.x = (int) (ox + dx);
                            bubbleLp.y = (int) (oy + dy);
                            wm.updateViewLayout(bubbleRoot, bubbleLp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (!moved && !longPressed) openEditor();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        return true;
                    default:
                        return false;
                }
            }
        });
        updateBubble();
    }

    private void updateBubble() {
        if (bubble == null) return;
        // gray = helper not running, blue = normal mouse, green = game mode
        int color = !connected ? 0xFF757575 : (gameMode ? 0xFF34C759 : 0xFF1E88E5);
        bubble.setColors(0xFFFFFFFF, color);
        bubble.setAlpha(gameMode ? 0.55f : 1f);
    }

    private void openEditor() {
        if (!canDrawOverlays()) {
            report("اجازه «نمایش روی برنامه‌های دیگر» داده نشده");
            return;
        }
        if (editor != null && editor.isShowing()) return;
        if (client != null && gameMode) client.setGameMode(false);
        int bx = bubbleLp != null ? bubbleLp.x : Ui.dp(this, 8);
        int by = bubbleLp != null ? bubbleLp.y : Ui.dp(this, 90);
        if (bubbleRoot != null) bubbleRoot.setVisibility(View.GONE);
        editor = new EditorOverlay(this, keyMap, bx, by, Ui.dp(this, 54), new EditorOverlay.Callback() {
            @Override
            public void onEditorClosed(KeyMap saved) {
                if (saved != null) {
                    keyMap = saved;
                    keyMap.save(MapperService.this);
                    if (labels != null) labels.setKeyMap(keyMap);
                    pushConfig();
                    report("چیدمان ذخیره شد ✓");
                    Toast.makeText(MapperService.this, "ذخیره شد ✓", Toast.LENGTH_SHORT).show();
                }
                if (bubbleRoot != null) bubbleRoot.setVisibility(View.VISIBLE);
                onGameModeChanged(gameMode); // refresh labels visibility
                editor = null;
            }

            @Override
            public void onStopRequested() {
                editor = null;
                send(MapperService.this, ACTION_STOP);
            }
        });
        editor.show();
    }

    private void removeView(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ notification

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "کنترل بازی", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, MapperService.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent edit = PendingIntent.getService(this, 2,
                new Intent(this, MapperService.class).setAction(ACTION_EDIT), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, MapperService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Aspar Mapper فعال است")
                .setContentText("کلید ` بین حالت بازی و موس جابه‌جا می‌کند")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "حالت بازی", toggle).build())
                .addAction(new Notification.Action.Builder(null, "ویرایش", edit).build())
                .addAction(new Notification.Action.Builder(null, "خاموش", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
