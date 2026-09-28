package com.cogent.lighthouse;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

public class LighthouseActivity extends SDLActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Deliver the back button/gesture to the game (it opens the menu) instead of closing the app.
        try {
            Os.setenv("SDL_ANDROID_TRAP_BACK_BUTTON", "1", true);
        } catch (ErrnoException e) {
            // Back will just close the app as before.
        }
        super.onCreate(savedInstanceState);

        if (mLayout != null) {
            mLayout.addView(new TouchControlsView(this),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
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
