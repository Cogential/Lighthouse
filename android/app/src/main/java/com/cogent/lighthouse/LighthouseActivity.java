package com.cogent.lighthouse;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

public class LighthouseActivity extends SDLActivity {
    private InputLog mInputLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final boolean frame = getResources().getBoolean(R.bool.frame_build);
        // Deliver the back button/gesture to the game (it opens the menu) instead of closing the app.
        try {
            Os.setenv("SDL_ANDROID_TRAP_BACK_BUTTON", "1", true);
            if (frame) {
                // Read by the native side (menu scale). The headset's accelerometer is no game controller.
                Os.setenv("LIGHTHOUSE_FRAME", "1", true);
                Os.setenv("SDL_ACCELEROMETER_AS_JOYSTICK", "0", true);
            }
        } catch (ErrnoException e) {
            // Back will just close the app as before.
        }
        super.onCreate(savedInstanceState);

        if (frame) {
            mInputLog = new InputLog(this);
        }
        if (mLayout != null) {
            View overlay = frame ? new FrameMenuButton(this) : new TouchControlsView(this);
            mLayout.addView(overlay,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mInputLog != null) {
            mInputLog.key(event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mInputLog != null) {
            mInputLog.motion(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "c++_shared", "SDL2", "main" };
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        // SDL picks "any orientation" for resizable windows; the game is landscape-only.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }
}
