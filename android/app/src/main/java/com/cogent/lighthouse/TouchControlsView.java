package com.cogent.lighthouse;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen N64 controller drawn over the game. It feeds an SDL virtual gamepad (see
 * src/port/Android/TouchControls.cpp), so the game's normal controller mappings apply.
 *
 * The small button in the bottom-right corner: tap to hide/show the controls, hold to edit the
 * layout (drag to move, pinch to resize, opacity slider, tap the check mark to save, hold it to
 * reset).
 * While a menu or popup is open the controls step aside and touches go to the UI.
 */
public class TouchControlsView extends View {
    // SDL_GameControllerButton / SDL_GameControllerAxis values
    private static final int SDL_BUTTON_A = 0;
    private static final int SDL_BUTTON_B = 1;
    private static final int SDL_BUTTON_START = 6;
    private static final int SDL_BUTTON_LEFTSHOULDER = 9;
    private static final int SDL_AXIS_LEFTX = 0;
    private static final int SDL_AXIS_LEFTY = 1;
    private static final int SDL_AXIS_RIGHTX = 2;
    private static final int SDL_AXIS_RIGHTY = 3;
    private static final int SDL_AXIS_TRIGGERLEFT = 4;
    private static final int SDL_AXIS_TRIGGERRIGHT = 5;
    private static final int AXIS_MAX = 32767;

    private static final String PREFS = "touch_controls";
    private static final String PREF_HIDDEN = "hidden";
    private static final String PREF_POS = "pos_";
    private static final long LONG_PRESS_MS = 500;
    private static final float MIN_SCALE = 0.5f, MAX_SCALE = 2.5f;
    private static final String PREF_OPACITY = "opacity";
    private static final float MIN_OPACITY = 0.2f, MAX_OPACITY = 2.5f;

    // Opacity (0-255): controls are faint at rest and brighten while held.
    private static final int FILL_IDLE = 40, FILL_PRESSED = 130;
    private static final int LINE_IDLE = 70, LINE_PRESSED = 170;
    private static final int TEXT_IDLE = 110, TEXT_PRESSED = 220;

    private static native void nativeSetButton(int button, boolean pressed);
    private static native void nativeSetAxis(int axis, int value);
    private static native boolean nativeIsUiActive();

    private enum Kind { STICK, A, B, START, L, R, Z, C_UP, C_DOWN, C_LEFT, C_RIGHT }

    private static final class Control {
        final Kind kind;
        final String label;
        final int color;
        final boolean pill;
        float cx, cy, r;
        float defaultR;
        boolean pressed;

        Control(Kind kind, String label, int color, boolean pill) {
            this.kind = kind;
            this.label = label;
            this.color = color;
            this.pill = pill;
        }

        boolean hit(float x, float y, float slopScale) {
            float dx = x - cx, dy = y - cy;
            float slop = r * slopScale;
            return pill ? Math.abs(dx) < slop * 1.7f && Math.abs(dy) < slop : dx * dx + dy * dy < slop * slop;
        }
    }

    private final List<Control> mControls = new ArrayList<>();
    private Control mStick; // cx/cy = resting position, r = travel radius
    // pointer id -> control it is holding (buttons only)
    private final SparseArray<Control> mPointerControls = new SparseArray<>();
    private int mStickPointer = -1;
    private float mStickBaseX, mStickBaseY, mStickKnobX, mStickKnobY;
    private float mToggleX, mToggleY, mToggleR;

    private boolean mHidden;
    private float mOpacity; // multiplier on the default alphas
    private boolean mSliding;
    private float mSliderX0, mSliderX1, mSliderY;
    private boolean mEditing;
    private boolean mUiActive = true;
    private boolean mToggleDown, mToggleLongPressed;
    private Control mDragging;
    private int mDragPointer = -1, mPinchPointer = -1;
    private float mDragOffX, mDragOffY, mPinchStartDist, mPinchStartR;
    private int mLastStickX, mLastStickY, mLastCx, mLastCy, mLastZ, mLastR;

    private final float mDp;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mPollUi = new Runnable() {
        @Override
        public void run() {
            boolean active;
            try {
                active = nativeIsUiActive();
            } catch (UnsatisfiedLinkError e) {
                active = true;
            }
            if (active != mUiActive) {
                mUiActive = active;
                if (active) {
                    mEditing = false;
                    releaseAll();
                }
                invalidate();
            }
            mHandler.postDelayed(this, 150);
        }
    };

    private final Runnable mToggleLongPress = new Runnable() {
        @Override
        public void run() {
            if (!mToggleDown) {
                return;
            }
            mToggleLongPressed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (mEditing) {
                resetLayout();
            } else {
                mEditing = true;
                setHidden(false);
                releaseAll();
            }
            invalidate();
        }
    };

    public TouchControlsView(Context context) {
        super(context);
        mDp = context.getResources().getDisplayMetrics().density;
        mHidden = prefs().getBoolean(PREF_HIDDEN, false);
        mOpacity = prefs().getFloat(PREF_OPACITY, 1f);

        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(2 * mDp);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setFakeBoldText(true);

        mStick = new Control(Kind.STICK, "", Color.WHITE, false);
        mControls.add(new Control(Kind.A, "A", Color.rgb(40, 90, 230), false));
        mControls.add(new Control(Kind.B, "B", Color.rgb(30, 160, 60), false));
        mControls.add(new Control(Kind.C_UP, "▲", Color.rgb(240, 200, 20), false));
        mControls.add(new Control(Kind.C_DOWN, "▼", Color.rgb(240, 200, 20), false));
        mControls.add(new Control(Kind.C_LEFT, "◀", Color.rgb(240, 200, 20), false));
        mControls.add(new Control(Kind.C_RIGHT, "▶", Color.rgb(240, 200, 20), false));
        mControls.add(new Control(Kind.Z, "Z", Color.rgb(150, 150, 150), false));
        mControls.add(new Control(Kind.L, "L", Color.rgb(150, 150, 150), true));
        mControls.add(new Control(Kind.R, "R", Color.rgb(150, 150, 150), true));
        mControls.add(new Control(Kind.START, "START", Color.rgb(210, 40, 40), true));
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.post(mPollUi);
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacks(mPollUi);
        mHandler.removeCallbacks(mToggleLongPress);
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutDefaults(w, h);
        loadLayout(w, h);
        resetStick();

        mSliderX0 = w / 2f - 130 * mDp;
        mSliderX1 = w / 2f + 130 * mDp;
        mSliderY = h / 2f + 50 * mDp;

        mToggleR = 18 * mDp;
        mToggleX = w - 30 * mDp;
        mToggleY = h - 30 * mDp;
    }

    private void layoutDefaults(int w, int h) {
        float m = 28 * mDp;
        mStick.r = mStick.defaultR = 62 * mDp;
        mStick.cx = m + mStick.r + 20 * mDp;
        mStick.cy = h - m - mStick.r - 10 * mDp;

        // Right cluster: A bottom, B up-left of A, C buttons in a diamond above-right.
        place(Kind.A, w - m - 105 * mDp, h - m - 40 * mDp, 38);
        place(Kind.B, w - m - 185 * mDp, h - m - 95 * mDp, 32);
        float cX = w - m - 55 * mDp, cY = h - m - 175 * mDp, cOff = 44 * mDp;
        place(Kind.C_UP, cX, cY - cOff, 24);
        place(Kind.C_DOWN, cX, cY + cOff, 24);
        place(Kind.C_LEFT, cX - cOff, cY, 24);
        place(Kind.C_RIGHT, cX + cOff, cY, 24);

        place(Kind.Z, m + 34 * mDp, h - m - 205 * mDp, 30);
        place(Kind.L, m + 40 * mDp, m + 26 * mDp, 22);
        place(Kind.R, w - m - 40 * mDp, m + 26 * mDp, 22);
        place(Kind.START, w / 2f, h - m - 12 * mDp, 20);
    }

    private void place(Kind kind, float x, float y, float radiusDp) {
        for (Control c : mControls) {
            if (c.kind == kind) {
                c.cx = x;
                c.cy = y;
                c.r = c.defaultR = radiusDp * mDp;
            }
        }
    }

    // Positions are stored as fractions of the view size so they survive resolution changes.
    private void loadLayout(int w, int h) {
        SharedPreferences p = prefs();
        for (Control c : allControls()) {
            String key = PREF_POS + c.kind.name();
            if (p.contains(key + "_s")) {
                c.r = c.defaultR * clamp(p.getFloat(key + "_s", 1f), MIN_SCALE, MAX_SCALE);
            }
            if (p.contains(key + "_x")) {
                c.cx = clamp(p.getFloat(key + "_x", 0) * w, c.r, w - c.r);
                c.cy = clamp(p.getFloat(key + "_y", 0) * h, c.r, h - c.r);
            }
        }
    }

    private void saveLayout() {
        SharedPreferences.Editor e = prefs().edit();
        e.putFloat(PREF_OPACITY, mOpacity);
        for (Control c : allControls()) {
            String key = PREF_POS + c.kind.name();
            e.putFloat(key + "_x", c.cx / getWidth());
            e.putFloat(key + "_y", c.cy / getHeight());
            e.putFloat(key + "_s", c.r / c.defaultR);
        }
        e.apply();
    }

    private void resetLayout() {
        mOpacity = 1f;
        SharedPreferences.Editor e = prefs().edit();
        e.remove(PREF_OPACITY);
        for (Control c : allControls()) {
            e.remove(PREF_POS + c.kind.name() + "_x");
            e.remove(PREF_POS + c.kind.name() + "_y");
            e.remove(PREF_POS + c.kind.name() + "_s");
        }
        e.apply();
        layoutDefaults(getWidth(), getHeight());
        resetStick();
    }

    private List<Control> allControls() {
        List<Control> all = new ArrayList<>(mControls);
        all.add(mStick);
        return all;
    }

    private void resetStick() {
        mStickBaseX = mStickKnobX = mStick.cx;
        mStickBaseY = mStickKnobY = mStick.cy;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        if (mUiActive) {
            return;
        }
        drawToggle(canvas);
        if (mHidden) {
            return;
        }

        if (mEditing) {
            mText.setTextSize(16 * mDp);
            mText.setColor(Color.argb(230, 255, 255, 255));
            float y = getHeight() / 2f - 30 * mDp;
            canvas.drawText("Drag to move  \u00B7  Hold a control and pinch to resize", getWidth() / 2f, y, mText);
            canvas.drawText("Tap \u2713 to save  \u00B7  Hold \u2713 to reset", getWidth() / 2f, y + 26 * mDp, mText);
            drawOpacitySlider(canvas);
        }

        // Stick
        boolean held = mStickPointer >= 0 || mDragging == mStick;
        mFill.setColor(Color.argb(alpha(held ? 60 : 30), 255, 255, 255));
        canvas.drawCircle(mStickBaseX, mStickBaseY, mStick.r, mFill);
        mStroke.setColor(Color.argb(alpha(held ? LINE_PRESSED : LINE_IDLE - 10), 255, 255, 255));
        canvas.drawCircle(mStickBaseX, mStickBaseY, mStick.r, mStroke);
        mFill.setColor(Color.argb(alpha(held ? FILL_PRESSED : FILL_IDLE + 20), 220, 220, 220));
        canvas.drawCircle(mStickKnobX, mStickKnobY, mStick.r * 0.45f, mFill);
        if (mEditing) {
            drawEditOutline(canvas, mStick);
        }

        for (Control c : mControls) {
            boolean lit = c.pressed || c == mDragging;
            mFill.setColor(Color.argb(alpha(lit ? FILL_PRESSED : FILL_IDLE), Color.red(c.color), Color.green(c.color),
                    Color.blue(c.color)));
            mStroke.setColor(Color.argb(alpha(lit ? LINE_PRESSED : LINE_IDLE), 255, 255, 255));
            if (c.pill) {
                RectF rect = pillRect(c);
                canvas.drawRoundRect(rect, c.r, c.r, mFill);
                canvas.drawRoundRect(rect, c.r, c.r, mStroke);
            } else {
                canvas.drawCircle(c.cx, c.cy, c.r, mFill);
                canvas.drawCircle(c.cx, c.cy, c.r, mStroke);
            }
            mText.setColor(Color.argb(alpha(lit ? TEXT_PRESSED : TEXT_IDLE), 255, 255, 255));
            mText.setTextSize(c.kind == Kind.START ? c.r * 0.7f : c.r * 0.9f);
            canvas.drawText(c.label, c.cx, c.cy - (mText.descent() + mText.ascent()) / 2, mText);
            if (mEditing) {
                drawEditOutline(canvas, c);
            }
        }
    }

    private int alpha(int base) {
        return Math.max(0, Math.min(255, Math.round(base * mOpacity)));
    }

    private static RectF pillRect(Control c) {
        return new RectF(c.cx - c.r * 1.7f, c.cy - c.r, c.cx + c.r * 1.7f, c.cy + c.r);
    }

    // Layout mode: a dashed outline marks each control without hiding its real opacity.
    private void drawEditOutline(Canvas canvas, Control c) {
        mStroke.setPathEffect(new DashPathEffect(new float[] { 8 * mDp, 6 * mDp }, 0));
        mStroke.setColor(Color.argb(200, 255, 255, 255));
        float pad = 3 * mDp;
        if (c.pill) {
            RectF rect = pillRect(c);
            rect.inset(-pad, -pad);
            canvas.drawRoundRect(rect, c.r + pad, c.r + pad, mStroke);
        } else {
            canvas.drawCircle(c.cx, c.cy, c.r + pad, mStroke);
        }
        mStroke.setPathEffect(null);
    }

    private void drawOpacitySlider(Canvas canvas) {
        float t = (mOpacity - MIN_OPACITY) / (MAX_OPACITY - MIN_OPACITY);
        float knobX = mSliderX0 + t * (mSliderX1 - mSliderX0);
        mStroke.setColor(Color.argb(120, 255, 255, 255));
        mStroke.setStrokeWidth(4 * mDp);
        canvas.drawLine(mSliderX0, mSliderY, mSliderX1, mSliderY, mStroke);
        mStroke.setColor(Color.argb(230, 255, 255, 255));
        canvas.drawLine(mSliderX0, mSliderY, knobX, mSliderY, mStroke);
        mStroke.setStrokeWidth(2 * mDp);
        mFill.setColor(Color.argb(mSliding ? 255 : 220, 255, 255, 255));
        canvas.drawCircle(knobX, mSliderY, (mSliding ? 13 : 11) * mDp, mFill);
        mText.setTextSize(14 * mDp);
        mText.setColor(Color.argb(230, 255, 255, 255));
        canvas.drawText("Opacity " + Math.round(mOpacity * 100) + "%", getWidth() / 2f, mSliderY - 16 * mDp, mText);
    }

    private boolean inSlider(float x, float y) {
        return Math.abs(y - mSliderY) < 26 * mDp && x > mSliderX0 - 20 * mDp && x < mSliderX1 + 20 * mDp;
    }

    private void setOpacityFromX(float x) {
        float t = clamp((x - mSliderX0) / (mSliderX1 - mSliderX0), 0f, 1f);
        mOpacity = MIN_OPACITY + t * (MAX_OPACITY - MIN_OPACITY);
    }

    private void drawToggle(Canvas canvas) {
        mFill.setColor(Color.argb(mEditing ? 160 : (mHidden ? 20 : 50), 0, 0, 0));
        canvas.drawCircle(mToggleX, mToggleY, mToggleR, mFill);
        mStroke.setColor(Color.argb(mEditing ? 230 : (mHidden ? 40 : 110), 255, 255, 255));
        canvas.drawCircle(mToggleX, mToggleY, mToggleR, mStroke);
        float s = mToggleR * 0.5f;
        mStroke.setStrokeWidth(1.5f * mDp);
        if (mEditing) {
            // Check mark
            canvas.drawLine(mToggleX - s, mToggleY, mToggleX - s * 0.2f, mToggleY + s * 0.8f, mStroke);
            canvas.drawLine(mToggleX - s * 0.2f, mToggleY + s * 0.8f, mToggleX + s * 1.1f, mToggleY - s * 0.8f, mStroke);
        } else {
            // Little gamepad glyph; crossed out while the controls are hidden.
            RectF body = new RectF(mToggleX - s * 1.3f, mToggleY - s * 0.6f, mToggleX + s * 1.3f, mToggleY + s * 0.6f);
            canvas.drawRoundRect(body, s * 0.5f, s * 0.5f, mStroke);
            if (mHidden) {
                canvas.drawLine(mToggleX - s * 1.3f, mToggleY + s * 1.1f, mToggleX + s * 1.3f, mToggleY - s * 1.1f,
                        mStroke);
            }
        }
        mStroke.setStrokeWidth(2 * mDp);
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mUiActive) {
            return false;
        }
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int id = event.getPointerId(index);
        float x = event.getX(index), y = event.getY(index);

        // The toggle button: tap = hide/show (or finish editing), hold = edit layout (or reset it).
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && inToggle(x, y)) {
            mToggleDown = true;
            mToggleLongPressed = false;
            mHandler.postDelayed(mToggleLongPress, LONG_PRESS_MS);
            return true;
        }
        if (mToggleDown && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            mToggleDown = false;
            mHandler.removeCallbacks(mToggleLongPress);
            if (!mToggleLongPressed && action == MotionEvent.ACTION_UP) {
                if (mEditing) {
                    mEditing = false;
                    saveLayout();
                } else {
                    setHidden(!mHidden);
                }
                invalidate();
            }
            return true;
        }
        if (mToggleDown) {
            return true;
        }

        if (mEditing) {
            return handleEditTouch(event, action, id, x, y);
        }
        if (mHidden) {
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Control c = hitControl(x, y);
                if (c != null) {
                    mPointerControls.put(id, c);
                } else if (mStickPointer < 0 && inStickZone(x, y)) {
                    // Floating stick: it recentres under the thumb.
                    mStickPointer = id;
                    mStickBaseX = clamp(x, mStick.r, getWidth() - mStick.r);
                    mStickBaseY = clamp(y, mStick.r, getHeight() - mStick.r);
                    updateStick(x, y);
                } else if (action == MotionEvent.ACTION_DOWN) {
                    // Not on a control: let the game/UI underneath have this touch.
                    return false;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pid = event.getPointerId(i);
                    float px = event.getX(i), py = event.getY(i);
                    if (pid == mStickPointer) {
                        updateStick(px, py);
                    } else if (mPointerControls.indexOfKey(pid) >= 0) {
                        // Allow sliding between buttons (e.g. across the C buttons).
                        Control c = hitControl(px, py);
                        if (c != null) {
                            mPointerControls.put(pid, c);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (action == MotionEvent.ACTION_CANCEL) {
                    mPointerControls.clear();
                    mStickPointer = -1;
                } else {
                    mPointerControls.remove(id);
                    if (id == mStickPointer) {
                        mStickPointer = -1;
                    }
                }
                if (mStickPointer < 0) {
                    resetStick();
                }
                break;
            default:
                break;
        }
        sendState();
        invalidate();
        return true;
    }

    private boolean handleEditTouch(MotionEvent event, int action, int id, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN && inSlider(x, y)) {
            mSliding = true;
        }
        if (mSliding) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                setOpacityFromX(event.getX(0));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mSliding = false;
            }
            invalidate();
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDragging = hitControl(x, y);
                if (mDragging == null && mStick.hit(x, y, 1.0f)) {
                    mDragging = mStick;
                }
                if (mDragging != null) {
                    mDragPointer = id;
                    mDragOffX = mDragging.cx - x;
                    mDragOffY = mDragging.cy - y;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Second finger while holding a control: pinch to resize it.
                if (mDragging != null && mPinchPointer < 0) {
                    int dragIndex = event.findPointerIndex(mDragPointer);
                    if (dragIndex >= 0) {
                        mPinchPointer = id;
                        mPinchStartDist = Math.max(1f, distance(event.getX(dragIndex), event.getY(dragIndex), x, y));
                        mPinchStartR = mDragging.r;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                if (mDragging == null) {
                    break;
                }
                int dragIndex = event.findPointerIndex(mDragPointer);
                int pinchIndex = mPinchPointer >= 0 ? event.findPointerIndex(mPinchPointer) : -1;
                if (dragIndex >= 0 && pinchIndex >= 0) {
                    float dist = distance(event.getX(dragIndex), event.getY(dragIndex), event.getX(pinchIndex),
                            event.getY(pinchIndex));
                    mDragging.r = clamp(mPinchStartR * dist / mPinchStartDist, mDragging.defaultR * MIN_SCALE,
                            mDragging.defaultR * MAX_SCALE);
                } else if (dragIndex >= 0) {
                    mDragging.cx = event.getX(dragIndex) + mDragOffX;
                    mDragging.cy = event.getY(dragIndex) + mDragOffY;
                }
                keepOnScreen(mDragging);
                if (mDragging == mStick) {
                    resetStick();
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (id == mPinchPointer) {
                    mPinchPointer = -1;
                    // Carry on dragging from where the control is now.
                    int dragIndex = event.findPointerIndex(mDragPointer);
                    if (mDragging != null && dragIndex >= 0) {
                        mDragOffX = mDragging.cx - event.getX(dragIndex);
                        mDragOffY = mDragging.cy - event.getY(dragIndex);
                    }
                } else if (id == mDragPointer) {
                    mDragging = null;
                    mDragPointer = mPinchPointer = -1;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = null;
                mDragPointer = mPinchPointer = -1;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void keepOnScreen(Control c) {
        float halfW = c.pill ? c.r * 1.7f : c.r;
        c.cx = clamp(c.cx, halfW, getWidth() - halfW);
        c.cy = clamp(c.cy, c.r, getHeight() - c.r);
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private boolean inToggle(float x, float y) {
        float dx = x - mToggleX, dy = y - mToggleY, r = mToggleR * 1.5f;
        return dx * dx + dy * dy < r * r;
    }

    private boolean inStickZone(float x, float y) {
        float dx = x - mStick.cx, dy = y - mStick.cy, r = mStick.r * 2.3f;
        return dx * dx + dy * dy < r * r;
    }

    private Control hitControl(float x, float y) {
        for (Control c : mControls) {
            if (c.hit(x, y, 1.3f)) {
                return c;
            }
        }
        return null;
    }

    private void updateStick(float x, float y) {
        float dx = x - mStickBaseX, dy = y - mStickBaseY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > mStick.r) {
            dx = dx / len * mStick.r;
            dy = dy / len * mStick.r;
        }
        mStickKnobX = mStickBaseX + dx;
        mStickKnobY = mStickBaseY + dy;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void setHidden(boolean hidden) {
        mHidden = hidden;
        prefs().edit().putBoolean(PREF_HIDDEN, hidden).apply();
        if (hidden) {
            releaseAll();
        }
        invalidate();
    }

    private void releaseAll() {
        mPointerControls.clear();
        mStickPointer = -1;
        mDragging = null;
        mDragPointer = mPinchPointer = -1;
        resetStick();
        sendState();
    }

    /** Pushes the current control state to the virtual gamepad, sending only what changed. */
    private void sendState() {
        for (Control c : mControls) {
            boolean pressed = false;
            for (int i = 0; i < mPointerControls.size(); i++) {
                if (mPointerControls.valueAt(i) == c) {
                    pressed = true;
                    break;
                }
            }
            if (pressed != c.pressed) {
                c.pressed = pressed;
                switch (c.kind) {
                    case A: setButton(SDL_BUTTON_A, pressed); break;
                    case B: setButton(SDL_BUTTON_B, pressed); break;
                    case START: setButton(SDL_BUTTON_START, pressed); break;
                    case L: setButton(SDL_BUTTON_LEFTSHOULDER, pressed); break;
                    default: break; // axis-backed, handled below
                }
            }
        }

        int stickX = Math.round((mStickKnobX - mStickBaseX) / mStick.r * AXIS_MAX);
        int stickY = Math.round((mStickKnobY - mStickBaseY) / mStick.r * AXIS_MAX);
        int cx = (isPressed(Kind.C_RIGHT) ? AXIS_MAX : 0) - (isPressed(Kind.C_LEFT) ? AXIS_MAX : 0);
        int cy = (isPressed(Kind.C_DOWN) ? AXIS_MAX : 0) - (isPressed(Kind.C_UP) ? AXIS_MAX : 0);
        int z = isPressed(Kind.Z) ? AXIS_MAX : 0;
        int r = isPressed(Kind.R) ? AXIS_MAX : 0;

        if (stickX != mLastStickX) setAxis(SDL_AXIS_LEFTX, mLastStickX = stickX);
        if (stickY != mLastStickY) setAxis(SDL_AXIS_LEFTY, mLastStickY = stickY);
        if (cx != mLastCx) setAxis(SDL_AXIS_RIGHTX, mLastCx = cx);
        if (cy != mLastCy) setAxis(SDL_AXIS_RIGHTY, mLastCy = cy);
        if (z != mLastZ) setAxis(SDL_AXIS_TRIGGERLEFT, mLastZ = z);
        if (r != mLastR) setAxis(SDL_AXIS_TRIGGERRIGHT, mLastR = r);
    }

    private boolean isPressed(Kind kind) {
        for (Control c : mControls) {
            if (c.kind == kind) {
                return c.pressed;
            }
        }
        return false;
    }

    private static void setButton(int button, boolean pressed) {
        try {
            nativeSetButton(button, pressed);
        } catch (UnsatisfiedLinkError e) {
            // game library not loaded
        }
    }

    private static void setAxis(int axis, int value) {
        try {
            nativeSetAxis(axis, value);
        } catch (UnsatisfiedLinkError e) {
            // game library not loaded
        }
    }
}
