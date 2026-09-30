package ir.aspar.mapper.server;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Minimal logger: the server's stdout/stderr is redirected to a file by the launcher. */
final class Log {
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Log() {
    }

    private static synchronized void out(String level, String msg) {
        System.out.println(FMT.format(new Date()) + " " + level + " " + msg);
        System.out.flush();
    }

    static void i(String msg) {
        out("I", msg);
    }

    static void w(String msg) {
        out("W", msg);
    }

    static void e(String msg, Throwable t) {
        out("E", msg + ": " + t);
        t.printStackTrace(System.out);
        System.out.flush();
    }
}
