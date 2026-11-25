/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.tv;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

import org.chromium.base.CommandLine;
import org.chromium.base.ContextUtils;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.components.browser_ui.site_settings.WebsitePreferenceBridge;
import org.chromium.components.content_settings.ContentSetting;
import org.chromium.components.content_settings.ContentSettingsType;

/** Brave TV: detects TV devices and stores TV-only preferences. */
public final class BraveTvUtils {
    /** Forces TV mode on non-TV devices (handy for testing on a phone or emulator). */
    public static final String FORCE_TV_MODE_SWITCH = "force-brave-tv-mode";

    /** Chromium switch that lets arrow keys move focus between links on web pages. */
    public static final String SPATIAL_NAVIGATION_SWITCH = "enable-spatial-navigation";

    private static final String PREFS_NAME = "brave_tv";
    private static final String PREF_CURSOR_ENABLED = "cursor_enabled";
    private static final String PREF_DESKTOP_DEFAULT_APPLIED = "desktop_default_applied";

    private static Boolean sIsTv;

    private BraveTvUtils() {}

    /** Returns true when running on Android TV / Google TV / Fire TV (or when forced). */
    public static boolean isTv(Context context) {
        if (sIsTv == null) {
            sIsTv = detectTv(context);
        }
        return sIsTv;
    }

    public static boolean isTv() {
        return isTv(ContextUtils.getApplicationContext());
    }

    private static boolean detectTv(Context context) {
        if (CommandLine.isInitialized()
                && CommandLine.getInstance().hasSwitch(FORCE_TV_MODE_SWITCH)) {
            return true;
        }
        UiModeManager uiModeManager =
                (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature("android.software.leanback_only")
                || pm.hasSystemFeature("amazon.hardware.fire_tv");
    }

    /**
     * Adds TV-only Chromium switches. Must run in the browser process before native
     * initialization so that the switches reach both the browser and renderer processes.
     */
    public static void maybeAppendTvSwitches(Context context) {
        if (!CommandLine.isInitialized() || !isTv(context)) return;
        CommandLine commandLine = CommandLine.getInstance();
        if (!commandLine.hasSwitch(SPATIAL_NAVIGATION_SWITCH)) {
            commandLine.appendSwitch(SPATIAL_NAVIGATION_SWITCH);
        }
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The on-screen cursor is on by default: it works on every site with just a D-pad. */
    public static boolean isCursorEnabled() {
        return prefs().getBoolean(PREF_CURSOR_ENABLED, true);
    }

    public static void setCursorEnabled(boolean enabled) {
        prefs().edit().putBoolean(PREF_CURSOR_ENABLED, enabled).apply();
    }

    /**
     * Makes "Desktop site" the default once, on first start on a TV. Mobile layouts look
     * oversized and sparse on a TV. The user can still change it in Settings afterwards,
     * and we never override that choice again.
     */
    public static void maybeApplyDesktopSiteDefault() {
        if (prefs().getBoolean(PREF_DESKTOP_DEFAULT_APPLIED, false)) return;
        Profile profile = ProfileManager.getLastUsedRegularProfile();
        WebsitePreferenceBridge.setDefaultContentSetting(
                profile, ContentSettingsType.REQUEST_DESKTOP_SITE, ContentSetting.ALLOW);
        prefs().edit().putBoolean(PREF_DESKTOP_DEFAULT_APPLIED, true).apply();
    }
}
