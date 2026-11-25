/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.brave_shields.mojom.FilterListAndroidHandler;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.shields.FilterListServiceFactory;
import org.chromium.url.mojom.Url;

/**
 * Brave TV: stronger ad blocking out of the box, since TVs have no easy way to tweak settings
 * and streaming sites are among the worst for ads, pop-ups and anti-adblock walls.
 *
 * <p>Applied once per {@link #DEFAULTS_VERSION}; anything the user changes afterwards in Shields
 * settings is kept. Bump the version to roll out new defaults to existing installs.
 */
public final class BraveTvShieldsDefaults {
    private static final String TAG = "BraveTvShields";
    private static final String PREFS_NAME = "brave_tv";
    private static final String PREF_DEFAULTS_VERSION = "shields_defaults_version";
    private static final int DEFAULTS_VERSION = 1;

    /**
     * Extra lists from Brave's catalog (brave/adblock-resources filter_lists/list_catalog.json)
     * that are off by default on regular Brave. Brave's default lists (EasyList, EasyPrivacy,
     * uBlock Origin filters, Brave's own lists, cookie notices, and the regional list for the
     * device language) stay on as usual.
     */
    private static final String[] EXTRA_FILTER_LISTS = {
        "67E792D4-AE03-4D1A-9EDE-80E01C81F9B8", // Annoying distractions blocker
        "690FF3B4-8B6B-4709-8505-FEC6643D7BD9", // Newsletter popup blocker
        "E2FA7D98-0BD5-493E-8AF4-950604ADE9CB", // Tracking URL blocker
        "564C3B75-8731-404C-AD7C-5683258BA0B0", // Experimental ad blocker
        "AC023D22-AE88-4060-A978-4FEEEC4221693", // Cookie notice blocker (on by default; ensure)
    };

    /**
     * Third-party lists added as subscriptions (they show up, and can be removed, under
     * Shields > Content filtering > Custom filter lists). Aimed at full-screen "scan this QR
     * code" scam overlays and the phishing/malware pages they lead to. Both use uBlock Origin
     * syntax, which Brave's engine reads natively, and update automatically.
     *
     * <p>The 12 MB "Malicious URL Blocklist" from the same project is left out on purpose: TVs
     * like the Sony BRAVIA X75WL have ~2 GB of RAM shared with the system.
     */
    private static final String[] EXTRA_SUBSCRIPTIONS = {
        "https://ublockorigin.github.io/uAssets/filters/badware.txt",
        "https://malware-filter.gitlab.io/malware-filter/phishing-filter.txt",
    };

    private BraveTvShieldsDefaults() {}

    /** Call after native init on TV. One-time defaults apply once; the bootstrap every start. */
    public static void maybeApply() {
        // Filter lists and scriptlet resources from their public sources (Brave's update
        // server does not serve this build). Cheap after the first start.
        BraveTvAdblockBootstrap.run();

        SharedPreferences prefs =
                ContextUtils.getApplicationContext()
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getInt(PREF_DEFAULTS_VERSION, 0) >= DEFAULTS_VERSION) return;

        Profile profile = ProfileManager.getLastUsedRegularProfile();

        // "Aggressive" trackers & ads blocking: also blocks first-party ads and hides more ad
        // elements. On Android, BLOCK_RESOURCE is the value the settings UI uses for Aggressive.
        BraveShieldsContentSettings.setTrackersPref(BraveShieldsContentSettings.BLOCK_RESOURCE);

        FilterListAndroidHandler handler =
                FilterListServiceFactory.getInstance().getFilterListAndroidHandler(profile, null);
        if (handler == null) {
            // Try again on next start rather than recording a half-applied state.
            Log.w(TAG, "Filter list service unavailable; will retry next start.");
            return;
        }
        for (String uuid : EXTRA_FILTER_LISTS) {
            handler.enableFilter(uuid, true);
        }
        for (String spec : EXTRA_SUBSCRIPTIONS) {
            Url url = new Url();
            url.url = spec;
            handler.createSubscription(url);
        }
        // Messages already sent are still delivered after the pipe is closed.
        handler.close();

        prefs.edit().putInt(PREF_DEFAULTS_VERSION, DEFAULTS_VERSION).apply();
    }
}
