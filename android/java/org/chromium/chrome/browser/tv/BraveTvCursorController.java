/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.tv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import java.util.function.Supplier;

/**
 * Brave TV: a remote-controlled pointer, so every website and every Brave screen can be used
 * with just the D-pad, OK and Back.
 *
 * <ul>
 *   <li>Arrows move the pointer (it speeds up while held).
 *   <li>OK taps; holding OK is a long-press (context menu); moving while holding OK drags.
 *   <li>Pushing against a screen edge scrolls the page in that direction.
 *   <li>Channel/Page up and down scroll a page at a time.
 *   <li>Text fields (the address bar) get the keys back so suggestions can be picked normally.
 * </ul>
 *
 * When the pointer is off, keys go to Chromium's spatial navigation, which moves focus between
 * links with the arrow keys.
 */
public class BraveTvCursorController implements Choreographer.FrameCallback {
    // Pointer speed in dp per second, ramping from MIN to MAX over RAMP_SECONDS while held.
    private static final float MIN_SPEED_DP = 220f;
    private static final float MAX_SPEED_DP = 1500f;
    private static final float RAMP_SECONDS = 0.9f;
    // Edge scrolling in mouse-wheel ticks per second.
    private static final float MIN_SCROLL_TICKS = 8f;
    private static final float MAX_SCROLL_TICKS = 40f;
    private static final float PAGE_SCROLL_TICKS = 10f;
    private static final long HIDE_DELAY_MS = 5000;
    // Gamepad analog sticks.
    private static final float STICK_DEADZONE = 0.18f;
    private static final float STICK_MAX_SPEED_DP = 1300f;

    private final Activity mActivity;
    private final ViewGroup mDecor;
    private final BraveTvCursorView mView;
    private final Supplier<View> mContentViewSupplier;
    private final float mDensity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideRunnable = this::hide;

    private boolean mEnabled;
    private boolean mUp;
    private boolean mDown;
    private boolean mLeft;
    private boolean mRight;
    private boolean mFrameScheduled;
    private long mHoldStartNanos;
    private long mLastFrameNanos;
    private float mX = -1;
    private float mY = -1;
    private long mTouchDownTime = -1;
    private float mPendingH;
    private float mPendingV;
    private float mStickX;
    private float mStickY;
    private float mScrollStickX;
    private float mScrollStickY;

    /**
     * @param contentViewSupplier returns the current tab's view (web page or native page), used
     *     as the scroll target when the pointer sits over the toolbar.
     */
    public BraveTvCursorController(Activity activity, Supplier<View> contentViewSupplier) {
        mActivity = activity;
        mDecor = (ViewGroup) activity.getWindow().getDecorView();
        mContentViewSupplier = contentViewSupplier;
        mDensity = activity.getResources().getDisplayMetrics().density;
        mView = new BraveTvCursorView(activity);
        mView.setVisibility(View.INVISIBLE);
        mView.setElevation(10000f);
        mDecor.addView(
                mView,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        mEnabled = BraveTvUtils.isCursorEnabled();
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        resetInputState();
        mEnabled = enabled;
        BraveTvUtils.setCursorEnabled(enabled);
        if (enabled) {
            show();
        } else {
            hide();
        }
    }

    /** Switches between the pointer and arrow-key link navigation, and says which is active. */
    public void toggle() {
        setEnabled(!mEnabled);
        Toast.makeText(
                        mActivity,
                        mEnabled
                                ? org.chromium.chrome.R.string.brave_tv_cursor_on
                                : org.chromium.chrome.R.string.brave_tv_cursor_off,
                        Toast.LENGTH_SHORT)
                .show();
    }

    public void destroy() {
        resetInputState();
        mHandler.removeCallbacksAndMessages(null);
        mDecor.removeView(mView);
    }

    /** Releases held keys; call when the window loses focus (menus, dialogs, other apps). */
    public void resetInputState() {
        mUp = mDown = mLeft = mRight = false;
        if (mTouchDownTime >= 0) {
            injectTouch(MotionEvent.ACTION_CANCEL);
        }
        mView.setCursorPressed(false);
    }

    /** Returns true if the key was consumed by the pointer. */
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) return false;

        // Shortcut toggle on remotes that have colour buttons. (Gamepad Y is taken: Chromium
        // opens the app menu with it in KeyboardShortcuts before this runs.)
        if (code == KeyEvent.KEYCODE_PROG_RED) {
            if (!down && !event.isCanceled()) toggle();
            return true;
        }

        if (!mEnabled) return false;

        // Let text fields (address bar, find in page) handle keys: caret movement and picking
        // suggestions. Key-ups are still consumed if we own the press.
        View focus = mActivity.getCurrentFocus();
        boolean textFocused = focus instanceof EditText;

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    if (textFocused && !isAnyDirectionHeld()) return false;
                } else if (!isDirectionHeld(code)) {
                    // We never saw (or passed on) the press; let its release go the same way.
                    return false;
                }
                setDirection(code, down);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (textFocused && mTouchDownTime < 0) return false;
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        if (mView.getVisibility() != View.VISIBLE) {
                            // First press after the pointer faded out only reveals it.
                            show();
                            return true;
                        }
                        show();
                        injectTouch(MotionEvent.ACTION_DOWN);
                        mView.setCursorPressed(true);
                    }
                } else if (mTouchDownTime >= 0) {
                    injectTouch(MotionEvent.ACTION_UP);
                    mView.setCursorPressed(false);
                    scheduleHide();
                }
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) injectScroll(0, PAGE_SCROLL_TICKS, true);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) injectScroll(0, -PAGE_SCROLL_TICKS, true);
                return true;
            default:
                return false;
        }
    }

    private boolean isAnyDirectionHeld() {
        return mUp || mDown || mLeft || mRight;
    }

    private boolean isDirectionHeld(int code) {
        return switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP -> mUp;
            case KeyEvent.KEYCODE_DPAD_DOWN -> mDown;
            case KeyEvent.KEYCODE_DPAD_LEFT -> mLeft;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> mRight;
            default -> false;
        };
    }

    private void setDirection(int code, boolean down) {
        boolean wasHeld = isAnyDirectionHeld();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP -> mUp = down;
            case KeyEvent.KEYCODE_DPAD_DOWN -> mDown = down;
            case KeyEvent.KEYCODE_DPAD_LEFT -> mLeft = down;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> mRight = down;
            default -> {}
        }
        if (down) {
            show();
            if (!wasHeld) {
                mHoldStartNanos = System.nanoTime();
                mLastFrameNanos = mHoldStartNanos;
                // Nudge one step immediately so single taps give precise movement.
                moveBy(stepX() * 6 * mDensity, stepY() * 6 * mDensity, 0, 0);
            }
            ensureFrameLoop();
        } else if (!isAnyDirectionHeld() && !isStickActive()) {
            flushScroll(true);
            scheduleHide();
        }
    }

    private int stepX() {
        return (mRight ? 1 : 0) - (mLeft ? 1 : 0);
    }

    private int stepY() {
        return (mDown ? 1 : 0) - (mUp ? 1 : 0);
    }

    private boolean isStickActive() {
        return mStickX != 0 || mStickY != 0 || mScrollStickX != 0 || mScrollStickY != 0;
    }

    private void ensureFrameLoop() {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            mLastFrameNanos = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /**
     * Gamepad analog sticks (a gamepad used as a second remote): the left stick moves the
     * pointer proportionally, the right stick scrolls. Returns true if consumed. This
     * deliberately ignores whether a page uses the Gamepad API: some ad/tracking scripts touch
     * it for fingerprinting, which must not disable the pointer. D-pad "hat" input is left for
     * Android to turn into arrow keys.
     */
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!mEnabled) return false;
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        float lx = deadzone(event.getAxisValue(MotionEvent.AXIS_X));
        float ly = deadzone(event.getAxisValue(MotionEvent.AXIS_Y));
        float rx = deadzone(event.getAxisValue(MotionEvent.AXIS_Z));
        float ry = deadzone(event.getAxisValue(MotionEvent.AXIS_RZ));
        boolean wasActive = isStickActive();
        mStickX = lx;
        mStickY = ly;
        mScrollStickX = rx;
        mScrollStickY = ry;

        if (isStickActive()) {
            show();
            ensureFrameLoop();
            return true;
        }
        if (wasActive) {
            // Sticks just returned to centre.
            flushScroll(true);
            scheduleHide();
            return true;
        }
        // Only the hat (D-pad) moved: let Android synthesize arrow keys from it.
        return false;
    }

    private static float deadzone(float value) {
        if (Math.abs(value) < STICK_DEADZONE) return 0;
        // Rescale so movement starts smoothly from zero just outside the dead zone.
        float sign = Math.signum(value);
        return sign * (Math.abs(value) - STICK_DEADZONE) / (1f - STICK_DEADZONE);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        boolean keys = isAnyDirectionHeld();
        boolean stick = isStickActive();
        if (!mEnabled || (!keys && !stick)) return;

        float dt = Math.min(0.05f, Math.max(0f, (frameTimeNanos - mLastFrameNanos) / 1e9f));
        mLastFrameNanos = frameTimeNanos;

        float dx = 0;
        float dy = 0;
        float hTicks = 0;
        float vTicks = 0;
        if (keys) {
            float held = (frameTimeNanos - mHoldStartNanos) / 1e9f;
            float ramp = Math.min(1f, held / RAMP_SECONDS);
            float speed = (MIN_SPEED_DP + (MAX_SPEED_DP - MIN_SPEED_DP) * ramp * ramp) * mDensity;
            dx += stepX() * speed * dt;
            dy += stepY() * speed * dt;
            hTicks = vTicks = (MIN_SCROLL_TICKS + (MAX_SCROLL_TICKS - MIN_SCROLL_TICKS) * ramp) * dt;
        }
        if (mStickX != 0 || mStickY != 0) {
            // Squared response: fine control near the centre, full speed at the rim.
            float magnitude = Math.min(1f, (float) Math.hypot(mStickX, mStickY));
            float speed = STICK_MAX_SPEED_DP * mDensity * magnitude * magnitude;
            dx += mStickX * speed * dt;
            dy += mStickY * speed * dt;
            float edgeTicks = MAX_SCROLL_TICKS * magnitude * dt;
            hTicks = Math.max(hTicks, edgeTicks);
            vTicks = Math.max(vTicks, edgeTicks);
        }
        moveBy(dx, dy, hTicks, vTicks);

        if (mScrollStickX != 0 || mScrollStickY != 0) {
            // Stick down (positive Y) scrolls the page down, i.e. negative AXIS_VSCROLL.
            injectScroll(
                    mScrollStickX * MAX_SCROLL_TICKS * dt,
                    -mScrollStickY * MAX_SCROLL_TICKS * dt,
                    false);
        }

        mFrameScheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Moves the pointer; when it is pinned against an edge, scrolls toward that edge instead. */
    private void moveBy(float dx, float dy, float hTicks, float vTicks) {
        ensurePosition();
        float maxX = Math.max(0, mDecor.getWidth() - 1);
        float maxY = Math.max(0, mDecor.getHeight() - 1);
        float nx = clamp(mX + dx, 0, maxX);
        float ny = clamp(mY + dy, 0, maxY);

        float h = 0;
        float v = 0;
        if (dx < 0 && nx <= 0) h = -hTicks;
        if (dx > 0 && nx >= maxX) h = hTicks;
        // Positive AXIS_VSCROLL scrolls content up (towards the top of the page).
        if (dy < 0 && ny <= 0) v = vTicks;
        if (dy > 0 && ny >= maxY) v = -vTicks;

        if (nx != mX || ny != mY) {
            mX = nx;
            mY = ny;
            mView.setPosition(mX, mY);
            if (mTouchDownTime >= 0) {
                injectTouch(MotionEvent.ACTION_MOVE);
            } else {
                injectHover();
            }
        }
        if (h != 0 || v != 0) injectScroll(h, v, false);
    }

    private void ensurePosition() {
        if (mX < 0 || mY < 0) {
            mX = mDecor.getWidth() / 2f;
            mY = mDecor.getHeight() / 2f;
            mView.setPosition(mX, mY);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void show() {
        ensurePosition();
        mView.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private void hide() {
        if (isAnyDirectionHeld() || mTouchDownTime >= 0) {
            scheduleHide();
            return;
        }
        mView.setVisibility(View.INVISIBLE);
    }

    private void scheduleHide() {
        mHandler.removeCallbacks(mHideRunnable);
        if (mEnabled) mHandler.postDelayed(mHideRunnable, HIDE_DELAY_MS);
    }

    // ---- Synthetic input ----------------------------------------------------------------

    private void injectTouch(int action) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) mTouchDownTime = now;
        if (mTouchDownTime < 0) return;
        MotionEvent event =
                obtain(mTouchDownTime, now, action, mX, mY, InputDevice.SOURCE_TOUCHSCREEN,
                        MotionEvent.TOOL_TYPE_FINGER, 0, 0);
        mActivity.dispatchTouchEvent(event);
        event.recycle();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mTouchDownTime = -1;
        }
    }

    private void injectHover() {
        long now = SystemClock.uptimeMillis();
        MotionEvent event =
                obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, mX, mY, InputDevice.SOURCE_MOUSE,
                        MotionEvent.TOOL_TYPE_MOUSE, 0, 0);
        mActivity.dispatchGenericMotionEvent(event);
        event.recycle();
    }

    /**
     * Sends mouse-wheel scrolling. Small per-frame amounts are batched so the page receives
     * fewer, larger wheel events, which Chromium animates more smoothly.
     */
    private void injectScroll(float h, float v, boolean immediate) {
        mPendingH += h;
        mPendingV += v;
        flushScroll(immediate);
    }

    private void flushScroll(boolean force) {
        if (!force && Math.abs(mPendingH) < 0.5f && Math.abs(mPendingV) < 0.5f) return;
        if (mPendingH == 0 && mPendingV == 0) return;
        ensurePosition();
        float[] target = scrollTarget();
        long now = SystemClock.uptimeMillis();
        MotionEvent event =
                obtain(now, now, MotionEvent.ACTION_SCROLL, target[0], target[1],
                        InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, mPendingH,
                        mPendingV);
        mActivity.dispatchGenericMotionEvent(event);
        event.recycle();
        mPendingH = 0;
        mPendingV = 0;
    }

    /**
     * Scroll where the pointer is if it is over the page (so inner scrollable areas work),
     * otherwise (pointer over the toolbar) the nearest point inside the page.
     */
    private float[] scrollTarget() {
        View content = mContentViewSupplier.get();
        if (content == null || !content.isShown() || content.getWidth() == 0) {
            return new float[] {mX, mY};
        }
        int[] loc = new int[2];
        content.getLocationInWindow(loc);
        float x = clamp(mX, loc[0] + 1, loc[0] + content.getWidth() - 2);
        float y = clamp(mY, loc[1] + 1, loc[1] + content.getHeight() - 2);
        return new float[] {x, y};
    }

    private static MotionEvent obtain(
            long downTime,
            long eventTime,
            int action,
            float x,
            float y,
            int source,
            int toolType,
            float hScroll,
            float vScroll) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = toolType;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        if (hScroll != 0) coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        if (vScroll != 0) coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        return MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                new MotionEvent.PointerProperties[] {props},
                new MotionEvent.PointerCoords[] {coords},
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision= */ 1f,
                /* yPrecision= */ 1f,
                /* deviceId= */ 0,
                /* edgeFlags= */ 0,
                source,
                /* flags= */ 0);
    }
}
