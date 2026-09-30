package ir.aspar.mapper.server;

import android.os.Looper;

/**
 * Entry point of the privileged helper.
 *
 * It is started by the app through wireless debugging with shell (ADB) rights:
 *   CLASSPATH=/data/app/.../base.apk app_process / ir.aspar.mapper.server.Server <port> <token>
 *
 * Shell rights let it read the raw mouse/keyboard devices in /dev/input,
 * grab them exclusively, and inject multi-touch events into any app (the game).
 */
public final class Server {

    public static final int VERSION = 3;

    private Server() {
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 47810;
        String token = args.length > 1 ? args[1] : "";

        Log.i("Aspar Mapper server v" + VERSION + " starting, uid=" + android.os.Process.myUid());

        // Some framework singletons expect a main looper to exist.
        try {
            Looper.prepareMainLooper();
        } catch (Throwable t) {
            Log.w("prepareMainLooper: " + t);
        }

        try {
            TouchInjector injector = new TouchInjector();
            Mapper mapper = new Mapper(injector);
            DeviceManager devices = new DeviceManager(mapper);
            mapper.setDeviceManager(devices);
            ControlServer.DeviceManagerHolder.instance = devices;
            ControlServer control = new ControlServer(port, token, mapper);
            mapper.setControlServer(control);
            ForegroundWatcher fg = new ForegroundWatcher(pkg -> control.sendEvent("foreground", pkg));
            control.setForegroundWatcher(fg);
            fg.start();

            devices.start();
            control.run(); // blocks until "quit"
        } catch (Throwable t) {
            Log.e("fatal", t);
        }
        Log.i("server exiting");
        System.exit(0);
    }
}
