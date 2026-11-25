/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.tv;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import org.chromium.chrome.browser.app.BraveActivity;

import java.util.List;
import java.util.WeakHashMap;

/**
 * Brave TV: gives every Brave screen (welcome pages, settings, dialogs hosted in activities)
 * the remote pointer, not only the main browser. Many of these screens were built for touch
 * and cannot be reached with arrow-key focus at all.
 *
 * <p>The main browser ({@link BraveActivity}) wires its own controller, with the current tab
 * as scroll target, so it is skipped here.
 */
public final class BraveTvActivityHooks {
    private static final WeakHashMap<Activity, BraveTvCursorController> sControllers =
            new WeakHashMap<>();

    private BraveTvActivityHooks() {}

    public static void install(Application application) {
        if (!BraveTvUtils.isTv(application)) return;
        application.registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityPostCreated(Activity activity, Bundle state) {
                        if (activity instanceof BraveActivity) return;
                        if (!activity.getPackageName().equals(application.getPackageName())) {
                            return;
                        }
                        Window window = activity.getWindow();
                        if (window == null) return;
                        BraveTvCursorController controller =
                                new BraveTvCursorController(activity, () -> null);
                        sControllers.put(activity, controller);
                        window.setCallback(new Wrapper(window.getCallback(), controller));
                    }

                    @Override
                    public void onActivityCreated(Activity activity, Bundle state) {}

                    @Override
                    public void onActivityStarted(Activity activity) {}

                    @Override
                    public void onActivityResumed(Activity activity) {}

                    @Override
                    public void onActivityPaused(Activity activity) {
                        BraveTvCursorController controller = sControllers.get(activity);
                        if (controller != null) controller.resetInputState();
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {}

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle out) {}

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                        BraveTvCursorController controller = sControllers.remove(activity);
                        if (controller != null) controller.destroy();
                    }
                });
    }

    /** Sends keys and gamepad sticks to the pointer first; forwards everything else as-is. */
    private static final class Wrapper implements Window.Callback {
        private final Window.Callback mBase;
        private final BraveTvCursorController mController;

        Wrapper(Window.Callback base, BraveTvCursorController controller) {
            mBase = base;
            mController = controller;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return mController.dispatchKeyEvent(event) || mBase.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return mController.dispatchGenericMotionEvent(event)
                    || mBase.dispatchGenericMotionEvent(event);
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus) mController.resetInputState();
            mBase.onWindowFocusChanged(hasFocus);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return mBase.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return mBase.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return mBase.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return mBase.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return mBase.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return mBase.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return mBase.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return mBase.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return mBase.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            mBase.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            mBase.onContentChanged();
        }

        @Override
        public void onAttachedToWindow() {
            mBase.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            mBase.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            mBase.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return mBase.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return mBase.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return mBase.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return mBase.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            mBase.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            mBase.onActionModeFinished(mode);
        }

        @Override
        public void onProvideKeyboardShortcuts(
                List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
            mBase.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            mBase.onPointerCaptureChanged(hasCapture);
        }
    }
}
