package com.cogent.lighthouse;

import android.content.pm.ActivityInfo;

import org.libsdl.app.SDLActivity;

public class LighthouseActivity extends SDLActivity {
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
