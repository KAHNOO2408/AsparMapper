package ir.aspar.mapper;

import android.app.Application;
import android.content.Context;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class App extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // libadb uses the system's Conscrypt (TLS for Wireless debugging), which is a hidden API.
        HiddenApiBypass.addHiddenApiExemptions("L");
    }
}
