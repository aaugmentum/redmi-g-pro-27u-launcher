package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Inputs on Xiaomi/Redmi sets with the China HyperOS ROM, which ship no app that opens TvContract passthrough URIs:
 * their own player switches sources by a numeric id (from its ConstTranslate.getInputSourceName: HDMI 1-3 = 23-25,
 * DP = 29, second DP = 30, which is USB-C on the Redmi G Pro 27U). Used by the Inputs menu and by InputsOverlay.
 */
final class XiaomiInputs {

    static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    /** Global setting where the player keeps the source on screen (or last on screen). */
    private static final String CURRENT = "LastCurrentSource";

    static final class Source {
        final String label;
        final int id;
        /** TvInputManager.INPUT_STATE_*, as reported for the port. */
        final int state;
        final boolean hdmi;

        Source(String label, int id, int state, boolean hdmi) {
            this.label = label;
            this.id = id;
            this.state = state;
            this.hdmi = hdmi;
        }
    }

    private XiaomiInputs() {
    }

    /** False when Xiaomi's player is absent, or when something does handle passthrough URIs (then the generic rows work). */
    static boolean usable(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.resolveActivity(intent(0), PackageManager.MATCH_DEFAULT_ONLY) != null
                && pm.resolveActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelUriForPassthroughInput("probe"), "vnd.android.cursor.item/channel"),
                PackageManager.MATCH_DEFAULT_ONLY) == null;
    }

    /** The ports in menu order: DisplayPort, USB-C, then HDMI 1-3. */
    static List<Source> list(TvInputManager tim) {
        List<TvInputInfo> dp = new ArrayList<>(), hdmi = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            // Ports only: HDMI-CEC devices behind a port are inputs of type HDMI too, with the port as parent.
            if (!info.isPassthroughInput() || info.getParentId() != null) continue;
            if (info.getType() == TvInputInfo.TYPE_DISPLAY_PORT) dp.add(info);
            else if (info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
        }
        // Input ids end in the hardware number (…/HW9, …/HW10), which follows port order.
        Comparator<TvInputInfo> byHw = (a, b) -> Integer.compare(hwNumber(a.getId()), hwNumber(b.getId()));
        Collections.sort(dp, byHw);
        Collections.sort(hdmi, byHw);
        List<Source> out = new ArrayList<>();
        for (int i = 0; i < dp.size() && i < 2; i++) {
            out.add(new Source(i == 0 ? "DisplayPort" : "USB-C", 29 + i, tim.getInputState(dp.get(i).getId()), false));
        }
        for (int i = 0; i < hdmi.size() && i < 3; i++) {
            out.add(new Source("HDMI " + (i + 1), 23 + i, tim.getInputState(hdmi.get(i).getId()), true));
        }
        return out;
    }

    /** Opens a source in Xiaomi's player. */
    static Intent intent(int source) {
        return new Intent(PLAYER + ".EXTSRC_PLAY")
                .setClassName(PLAYER, PLAYER + ".ExternalSourceActivity")
                .putExtra("input", source);
    }

    /** The source on screen now, or the last one shown; -1 when the player hasn't said. */
    static int current(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), CURRENT, -1);
    }

    private static int hwNumber(String id) {
        int i = id.lastIndexOf("HW");
        try {
            return i < 0 ? Integer.MAX_VALUE : Integer.parseInt(id.substring(i + 2));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
