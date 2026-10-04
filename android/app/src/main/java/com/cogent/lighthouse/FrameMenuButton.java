package com.cogent.lighthouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

/**
 * Steam Frame builds: a small button in the top-left corner that opens and closes the Lighthouse
 * menu. The Frame's pointer arrives as touch, so it can always reach the settings even when no
 * controller button is mapped to the menu. Touches outside the button pass through to the game.
 */
public class FrameMenuButton extends View {
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private boolean mPressed;

    public FrameMenuButton(Context context) {
        super(context);
        mFill.setColor(Color.argb(90, 0, 0, 0));
        mLine.setColor(Color.argb(200, 255, 255, 255));
        mLine.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float size = Math.min(w, h) * 0.09f;
        float margin = size * 0.3f;
        mRect.set(margin, margin, margin + size, margin + size);
        mLine.setStrokeWidth(size * 0.08f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mFill.setAlpha(mPressed ? 170 : 90);
        float r = mRect.width() * 0.2f;
        canvas.drawRoundRect(mRect, r, r, mFill);
        float x0 = mRect.left + mRect.width() * 0.28f, x1 = mRect.right - mRect.width() * 0.28f;
        for (int i = -1; i <= 1; i++) {
            float y = mRect.centerY() + i * mRect.height() * 0.18f;
            canvas.drawLine(x0, y, x1, y, mLine);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!mRect.contains(event.getX(), event.getY())) {
                    return false; // not ours: the game gets this touch
                }
                mPressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (mPressed && mRect.contains(event.getX(), event.getY())) {
                    // Escape toggles the menu in libultraship.
                    SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                mPressed = false;
                invalidate();
                return true;
            default:
                return mPressed;
        }
    }
}
