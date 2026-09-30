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
    public static final String ACTION_SET_GAME = "ir.aspar.mapper.SET_GAME";
    public static final String ACTION_HIDE_TOGGLE = "ir.aspar.mapper.HIDE_TOGGLE";
    public static final String ACTION_RELOAD = "ir.aspar.mapper.RELOAD";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PKG = "pkg";

    private static final String CHANNEL = "mapper";
    private static final int NOTIF_ID = 21;

    /** Status for the main screen. */
    public interface StatusListener {
        void onStatus(boolean connected, boolean gameMode, String message);
    }

    private static volatile StatusListener statusListener;
    private static volatile MapperService running;
    private static volatile String lastMessage = "";
    private static volatile java.util.List<String> serverDevices = new java.util.ArrayList<>();

    /** Mouse/keyboard devices the helper found and can use (empty when it is not running). */
    public static java.util.List<String> serverDevices() {
        MapperService s = running;
        if (s == null || s.client == null || !s.client.isConnected()) return new java.util.ArrayList<>();
        return serverDevices;
    }

    /** Asks the helper for its current device list (answer arrives in serverDevices()). */
    public static void requestDevices() {
        MapperService s = running;
        if (s != null && s.client != null && s.client.isConnected()) s.client.requestDevices();
    }

    /** Remembers which game was started from Aspar Mapper; the floating menu only shows inside it. */
    public static void launchGame(Context ctx, String pkg) {
        Intent i = new Intent(ctx, MapperService.class).setAction(ACTION_SET_GAME).putExtra(EXTRA_PKG, pkg);
        ctx.startForegroundService(i);
    }

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
    private Layouts layouts;

    private FrameLayout bubbleRoot;
    private IconView bubble;
    private boolean longPressed;
    private WindowManager.LayoutParams bubbleLp;
    private KeymapView labels;
    private EditorOverlay editor;
    private boolean connected;
    private boolean gameMode;
    private volatile boolean activating;
    private boolean lootMode;          // mouse mode opened by a loot/inventory key: show only the cursor
    private Boolean cursorSent;        // last "own cursor" state told to the helper
    private boolean cursorOn;          // helper says its cursor is visible
    private CursorView cursorView;
    private WindowManager.LayoutParams cursorLp;
    private volatile String activeGame;
    private volatile String foreground; // from usage stats; null = unknown
    private volatile String serverForeground; // from the helper (most reliable); null = unknown
    private volatile boolean watching = true;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        wm = getSystemService(WindowManager.class);
        activeGame = Prefs.get(this).getString("active_game", null);
        layouts = Layouts.load(this, activeGame);
        keyMap = layouts.active();
        goForeground();
        client = new ServerClient(this, this);
        pushConfig();
        startForegroundWatcher();
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
            case ACTION_SET_GAME: {
                String pkg = intent.getStringExtra(EXTRA_PKG);
                activeGame = pkg;
                Prefs.get(this).edit().putString("active_game", pkg).apply();
                layouts = Layouts.load(this, pkg);
                keyMap = layouts.active();
                if (labels != null) labels.setKeyMap(keyMap);
                pushConfig();
                showOverlays();
                break;
            }
            case ACTION_RELOAD:
                layouts = Layouts.load(this, activeGame);
                keyMap = layouts.active();
                if (labels != null) labels.setKeyMap(keyMap);
                pushConfig();
                break;
            case ACTION_HIDE_TOGGLE:
                onHideToggle();
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
        watching = false;
        if (editor != null) editor.dismiss();
        removeView(bubbleRoot);
        removeView(labels);
        removeView(cursorView);
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
                if (client != null) main.postDelayed(client::requestDevices, 3000);
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
        if (c) {
            pushConfig();
            client.requestDevices();
        } else {
            serverDevices = new java.util.ArrayList<>();
            serverForeground = null;
        }
        refreshVisibility();
        updateBubble();
        report(c ? "سرویس " + info : "سرویس در دسترس نیست – «فعال‌سازی» را بزن\n(" + client.lastError() + ")");
    }

    @Override
    public void onModeVia(String via) {
        lootMode = "loot".equals(via);
    }

    @Override
    public void onCursor(float x, float y, boolean on) {
        cursorOn = on;
        if (cursorView == null) {
            if (!canDrawOverlays()) return;
            cursorView = new CursorView(this);
            int size = Ui.dp(this, 26);
            cursorLp = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            cursorLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            cursorLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            cursorView.setVisibility(View.GONE);
            wm.addView(cursorView, cursorLp);
        }
        // the window starts 1px right/below the tip, so the injected touch at the tip is never covered by it
        cursorLp.x = (int) x + 1;
        cursorLp.y = (int) y + 1;
        try {
            wm.updateViewLayout(cursorView, cursorLp);
        } catch (Exception ignored) {
        }
        refreshVisibility();
    }

    @Override
    public void onGameModeChanged(boolean on) {
        gameMode = on;
        if (on) lootMode = false;
        updateBubble();
        refreshVisibility();
        StatusListener l = statusListener;
        if (l != null) l.onStatus(connected, gameMode, lastMessage);
    }

    @Override
    public void onDevices(java.util.List<String> devices) {
        serverDevices = devices;
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
            labels.setVisibility(View.GONE);
            wm.addView(labels, lp);
        }
        if (bubbleRoot == null) createBubble();
        refreshVisibility();
    }

    /** The game chosen in Aspar Mapper is the app in front. Unknown = hidden. */
    private boolean inGame() {
        if (activeGame == null) return false;
        String fg = connected && serverForeground != null ? serverForeground : foreground;
        return fg != null && activeGame.equals(fg);
    }

    @Override
    public void onNextLayout() {
        if (layouts == null || editor != null) return;
        layouts.next();
        layouts.save(this, activeGame);
        keyMap = layouts.active();
        if (labels != null) labels.setKeyMap(keyMap);
        pushConfig();
        if (!Prefs.bubbleHidden(this)) {
            Toast.makeText(this, layouts.activeName(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onHideToggle() {
        boolean hidden = !Prefs.bubbleHidden(this);
        Prefs.setBubbleHidden(this, hidden);
        refreshVisibility();
        goForeground(); // update the notification button text
    }

    @Override
    public void onForeground(String pkg) {
        serverForeground = pkg;
        refreshVisibility();
    }

    /** Floating button and labels only appear inside the game that was started from Aspar Mapper. */
    private void refreshVisibility() {
        boolean in = inGame();
        boolean editing = editor != null && editor.isShowing();
        boolean stream = Prefs.bubbleHidden(this);
        // hidden while playing (game mode) and in stream mode, so viewers never see it
        // in a loot box (mouse freed by a loot key) only the mouse pointer is shown
        if (bubbleRoot != null) bubbleRoot.setVisibility(in && !editing && !gameMode && !lootMode && !stream ? View.VISIBLE : View.GONE);
        // own cursor inside the game (not while editing: the editor is used with the normal pointer)
        boolean wantCursor = in && connected && !editing;
        if (client != null && connected && (cursorSent == null || cursorSent != wantCursor)) {
            cursorSent = wantCursor;
            client.setCursor(wantCursor);
        }
        if (!connected) cursorSent = null;
        if (cursorView != null) cursorView.setVisibility(wantCursor && cursorOn && !gameMode ? View.VISIBLE : View.GONE);
        if (labels != null) labels.setVisibility(in && gameMode && !editing && !stream && Prefs.showLabels(this) ? View.VISIBLE : View.GONE);
        if (!in && gameMode && client != null) client.setGameMode(false); // give the keyboard back outside the game
    }

    private boolean hasUsageAccess() {
        android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
        int mode = ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /** Polls which app is in front, using usage events (access is granted automatically on activation). */
    private void startForegroundWatcher() {
        Thread t = new Thread(() -> {
            android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
            long since = System.currentTimeMillis() - 60_000;
            String current = null;
            while (watching) {
                try {
                    if (hasUsageAccess()) {
                        long now = System.currentTimeMillis();
                        android.app.usage.UsageEvents events = usm.queryEvents(since, now);
                        android.app.usage.UsageEvents.Event ev = new android.app.usage.UsageEvents.Event();
                        while (events.hasNextEvent()) {
                            events.getNextEvent(ev);
                            if (ev.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                                current = ev.getPackageName();
                            }
                        }
                        since = now - 1;
                        if (current != null && !current.equals(foreground)) {
                            foreground = current;
                            main.post(this::refreshVisibility);
                        }
                    } else if (foreground != null) {
                        foreground = null;
                        main.post(this::refreshVisibility);
                    }
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "foreground-watch");
        t.setDaemon(true);
        t.start();
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
        editor = new EditorOverlay(this, keyMap, layouts.names, layouts.current, bx, by, Ui.dp(this, 54), new EditorOverlay.Callback() {
            @Override
            public void onLayoutAction(KeyMap edits, String action) {
                // keep the edits made so far, then switch / add / delete and reopen the editor
                layouts.setActive(edits);
                if (action.startsWith("switch:")) {
                    layouts.current = Integer.parseInt(action.substring(7));
                } else if ("new".equals(action)) {
                    layouts.add(layouts.nextName(), new KeyMap());
                } else if ("copy".equals(action)) {
                    layouts.add(layouts.nextName(), edits.copy());
                } else if ("delete".equals(action)) {
                    layouts.removeActive();
                }
                layouts.save(MapperService.this, activeGame);
                keyMap = layouts.active();
                if (labels != null) labels.setKeyMap(keyMap);
                pushConfig();
                editor = null;
                main.post(MapperService.this::openEditor);
            }

            @Override
            public void onEditorClosed(KeyMap saved) {
                if (saved != null) {
                    layouts.setActive(saved);
                    layouts.save(MapperService.this, activeGame);
                    keyMap = layouts.active();
                    if (labels != null) labels.setKeyMap(keyMap);
                    pushConfig();
                    report("چیدمان ذخیره شد ✓");
                    Toast.makeText(MapperService.this, "ذخیره شد ✓", Toast.LENGTH_SHORT).show();
                }
                editor = null;
                refreshVisibility();
            }

            @Override
            public void onStopRequested() {
                editor = null;
                send(MapperService.this, ACTION_STOP);
            }
        });
        editor.show();
        refreshVisibility(); // normal system pointer while editing
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
                .addAction(new Notification.Action.Builder(null,
                        Prefs.bubbleHidden(this) ? "نمایش دکمه" : "مخفی کردن دکمه",
                        PendingIntent.getService(this, 4, new Intent(this, MapperService.class).setAction(ACTION_HIDE_TOGGLE),
                                PendingIntent.FLAG_IMMUTABLE)).build())
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
