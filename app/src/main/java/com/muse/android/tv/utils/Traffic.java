package com.muse.android.tv.utils;

import android.net.TrafficStats;
import android.view.View;
import android.widget.TextView;

import com.muse.android.tv.App;

import java.text.DecimalFormat;

public class Traffic {

    private static final DecimalFormat format = new DecimalFormat("#.0");
    private static final int UID = App.get().getApplicationInfo().uid;
    private static final String UNIT_KB = " KB/s";
    private static final String UNIT_MB = " MB/s";

    private static long lastTotalTxBytes;
    private static long lastTotalRxBytes;
    private static long lastTimeStamp;

    public static void setSpeed(TextView view) {
        if (TrafficStats.getUidRxBytes(UID) == TrafficStats.UNSUPPORTED) return;
        view.setVisibility(View.VISIBLE);
        view.setText(getSpeed());
    }

    private static String getSpeed() {
        long nowTimeStamp = System.currentTimeMillis();
        long nowTxBytes = TrafficStats.getUidTxBytes(UID);
        long nowTotalTxBytes = nowTxBytes == TrafficStats.UNSUPPORTED ? lastTotalTxBytes : nowTxBytes / 1024;
        long nowTotalRxBytes = TrafficStats.getUidRxBytes(UID) / 1024;
        long elapsed = Math.max(nowTimeStamp - lastTimeStamp, 1);
        long txSpeed = (nowTotalTxBytes - lastTotalTxBytes) * 1000 / elapsed;
        long rxSpeed = (nowTotalRxBytes - lastTotalRxBytes) * 1000 / elapsed;
        lastTimeStamp = nowTimeStamp;
        lastTotalTxBytes = nowTotalTxBytes;
        lastTotalRxBytes = nowTotalRxBytes;
        String tx = nowTxBytes == TrafficStats.UNSUPPORTED ? "--" + UNIT_KB : formatSpeed(txSpeed);
        return "[↑" + tx + " ↓" + formatSpeed(rxSpeed) + "]";
    }

    private static String formatSpeed(long speed) {
        return speed < 1000 ? speed + UNIT_KB : format.format(speed / 1024f) + UNIT_MB;
    }

    public static void reset() {
        lastTotalTxBytes = 0;
        lastTotalRxBytes = 0;
        lastTimeStamp = 0;
    }
}
