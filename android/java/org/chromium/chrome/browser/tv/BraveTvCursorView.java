/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Brave TV: draws the remote-controlled pointer. It never takes focus or touches, so every
 * synthetic event the controller sends passes straight through to the views underneath.
 */
class BraveTvCursorView extends View {
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mRadius;

    private float mX;
    private float mY;
    private boolean mPressed;

    BraveTvCursorView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        mRadius = 11 * density;

        mFill.setColor(Color.argb(200, 255, 255, 255));
        mRing.setColor(Color.argb(255, 251, 84, 43)); // Brave orange
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(3 * density);
        mShadow.setColor(Color.argb(90, 0, 0, 0));

        setWillNotDraw(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setPosition(float x, float y) {
        if (x == mX && y == mY) return;
        mX = x;
        mY = y;
        invalidate();
    }

    void setCursorPressed(boolean pressed) {
        mPressed = pressed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = mPressed ? mRadius * 0.75f : mRadius;
        canvas.drawCircle(mX + 2, mY + 3, r, mShadow);
        canvas.drawCircle(mX, mY, r, mFill);
        canvas.drawCircle(mX, mY, r, mRing);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        return false;
    }
}
