package com.cogent.lighthouse;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Set;

/**
 * Steam Frame builds: records which input devices Android reports and what the Frame's controllers
 * send (key codes, axes), to logcat (tag LighthouseInput) and input-log.txt in the game folder.
 * Lepton's input path isn't documented, so this shows how its controllers arrive.
 */
final class InputLog {
    private static final String TAG = "LighthouseInput";
    private static final long MAX_BYTES = 512 * 1024;
    private static final long MOTION_INTERVAL_MS = 250;

    private final Set<Integer> mSeenDevices = new HashSet<>();
    private Writer mOut;
    private long mWritten;
    private long mLastMotion;

    InputLog(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        try {
            mOut = new FileWriter(new File(dir, "input-log.txt"), false);
        } catch (IOException e) {
            Log.w(TAG, "Can't write input-log.txt", e);
        }
        line("Input devices at startup:");
        for (int id : InputDevice.getDeviceIds()) {
            describe(InputDevice.getDevice(id));
        }
    }

    void key(KeyEvent e) {
        seen(e.getDevice());
        line(String.format("key %s %s (%d) scan=%d source=0x%x device=%d",
                e.getAction() == KeyEvent.ACTION_DOWN ? "down" : e.getAction() == KeyEvent.ACTION_UP ? "up" : "multi",
                KeyEvent.keyCodeToString(e.getKeyCode()), e.getKeyCode(), e.getScanCode(), e.getSource(),
                e.getDeviceId()));
    }

    void motion(MotionEvent e) {
        seen(e.getDevice());
        long now = SystemClock.uptimeMillis();
        if (now - mLastMotion < MOTION_INTERVAL_MS) {
            return;
        }
        mLastMotion = now;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("motion %s source=0x%x device=%d", MotionEvent.actionToString(e.getActionMasked()),
                e.getSource(), e.getDeviceId()));
        InputDevice d = e.getDevice();
        if (d != null) {
            for (InputDevice.MotionRange r : d.getMotionRanges()) {
                float v = e.getAxisValue(r.getAxis());
                if (v != 0) {
                    sb.append(String.format(" %s=%.2f", MotionEvent.axisToString(r.getAxis()), v));
                }
            }
        }
        line(sb.toString());
    }

    private void seen(InputDevice d) {
        if (d != null && mSeenDevices.add(d.getId())) {
            describe(d);
        }
    }

    private void describe(InputDevice d) {
        if (d == null) {
            return;
        }
        mSeenDevices.add(d.getId());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("device %d \"%s\" vendor=0x%04x product=0x%04x sources=0x%x virtual=%b external=%b",
                d.getId(), d.getName(), d.getVendorId(), d.getProductId(), d.getSources(), d.isVirtual(),
                d.isExternal()));
        sb.append(" keyboardType=").append(d.getKeyboardType());
        sb.append(" axes=[");
        for (InputDevice.MotionRange r : d.getMotionRanges()) {
            sb.append(MotionEvent.axisToString(r.getAxis())).append(' ');
        }
        sb.append(']');
        line(sb.toString());
    }

    private void line(String s) {
        Log.i(TAG, s);
        if (mOut == null || mWritten > MAX_BYTES) {
            return;
        }
        try {
            mOut.write(s);
            mOut.write('\n');
            mOut.flush();
            mWritten += s.length() + 1;
        } catch (IOException e) {
            mOut = null;
        }
    }
}
