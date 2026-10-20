package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

/**
 * Brings the Home redirect, and the keyboard you chose ({@link Keyboards}), back after the firmware force-stops
 * this app. HyperOS force-stops every third-party
 * app when the monitor goes to standby (MitvActivityManagerService.killAllThirdApp; its whitelist is cloud
 * controlled), and Android then turns off a force-stopped app's accessibility services, so Home would open the
 * stock home until HomeKeyService was enabled again over adb. Nothing wakes a force-stopped app on its own, but
 * the system rebinds an enabled notification listener whose process died, which starts the app again.
 * Notifications are filtered out in the manifest. Enable over adb, with WRITE_SECURE_SETTINGS granted (see
 * HomeKeyService):
 *   cmd notification allow_listener com.gathin.tvhome/.HomeKeyRestorer
 */
public class HomeKeyRestorer extends NotificationListenerService {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onListenerConnected() {
        restore();
        // At standby the firmware is still force-stopping other apps while this one restarts. If one of them had
        // an accessibility service, Android rewrites the list from its own copy and can undo the write above; and
        // a keyboard stopped after this point gets swapped for the system one. So once more, and again later.
        handler.postDelayed(this::restore, 5000);
        handler.postDelayed(this::restore, 20000);
    }

    private void restore() {
        HomeKeyService.ensureEnabled(this);
        Keyboards.restore(this);
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacksAndMessages(null);
    }
}
