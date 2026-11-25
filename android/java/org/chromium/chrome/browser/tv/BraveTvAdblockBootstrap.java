/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.brave_shields.mojom.FilterListAndroidHandler;
import org.chromium.brave_shields.mojom.SubscriptionInfo;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.shields.FilterListServiceFactory;
import org.chromium.url.mojom.Url;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Brave TV: makes ad blocking work without Brave's update server.
 *
 * <p>Brave normally receives its filter lists and the scriptlet "resources library" as
 * components from go-updater.brave.com, which only answers builds carrying Brave's private
 * service key (others get HTTP 403). Everything those components contain is public, so this
 * class fetches it from the sources directly:
 *
 * <ul>
 *   <li>Filter lists: Brave's public list catalog names the source URLs of every list. Each
 *       source of the lists Brave enables by default, the TV extras and the regional list for
 *       the device language becomes a Shields subscription, which Brave downloads and refreshes
 *       by itself (Shields > Content filtering > Custom filter lists).
 *   <li>Resources: a resources.json ships in the APK (seed) and is refreshed daily from Brave's
 *       public repository; native code loads it through --brave-tv-adblock-resources-dir.
 * </ul>
 */
public final class BraveTvAdblockBootstrap {
    private static final String TAG = "BraveTvAdblock";

    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/brave/adblock-resources/master/filter_lists/list_catalog.json";
    private static final String RESOURCES_URL =
            "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json";

    private static final String RESOURCES_ASSET = "brave_tv_adblock_resources.json";
    private static final String RESOURCES_DIR = "brave_tv_adblock";
    private static final String RESOURCES_FILE = "resources.json";
    private static final long RESOURCES_REFRESH_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
    private static final long STATUS_LOG_DELAY_MS = 60_000;

    private static final String PREFS_NAME = "brave_tv";
    private static final String PREF_SUBSCRIBED = "adblock_subscribed_urls";
    private static final String PREF_RESOURCES_REFRESHED = "adblock_resources_refreshed";

    /** Catalog lists that are off on regular Brave but on for Brave TV. */
    private static final Set<String> EXTRA_LISTS =
            new HashSet<>(
                    Arrays.asList(
                            "67E792D4-AE03-4D1A-9EDE-80E01C81F9B8", // Annoying distractions
                            "690FF3B4-8B6B-4709-8505-FEC6643D7BD9", // Newsletter pop-ups
                            "E2FA7D98-0BD5-493E-8AF4-950604ADE9CB", // Tracking URLs
                            "564C3B75-8731-404C-AD7C-5683258BA0B0" // Experimental
                            ));

    /** Used when the catalog cannot be downloaded: Brave's default lists plus the extras. */
    private static final String[] FALLBACK_SOURCES = {
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2024.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2025.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2026.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-general.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt",
        "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-specific.txt",
        "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-cookies.txt",
        "https://secure.fanboy.co.nz/fanboy-annoyance_ubo.txt",
        "https://secure.fanboy.co.nz/fanboy-newsletter.txt",
    };

    private BraveTvAdblockBootstrap() {}

    // ---- Resources library ---------------------------------------------------------------

    /**
     * Makes sure the resources directory holds a resources.json (copied from the APK on first
     * start) and returns it, or null if that failed. Runs before native init, so it is quick:
     * a copy only happens once.
     */
    static File prepareResourcesDir(Context context) {
        File dir = new File(context.getFilesDir(), RESOURCES_DIR);
        File file = new File(dir, RESOURCES_FILE);
        // Re-seed after every app update, since a newer APK ships a newer library. The
        // daily refresh then merges Brave's latest public resources on top.
        File stamp = new File(dir, RESOURCES_FILE + ".seed");
        String installStamp = installStamp(context);
        if (file.length() > 0 && installStamp.equals(readStamp(stamp))) return dir;
        try (InputStream in = context.getAssets().open(RESOURCES_ASSET)) {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            writeAtomically(file, readAll(in));
            writeAtomically(stamp, installStamp.getBytes(StandardCharsets.UTF_8));
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(PREF_RESOURCES_REFRESHED)
                    .apply();
            return dir;
        } catch (Exception e) {
            Log.w(TAG, "Could not seed ad-block resources", e);
            return null;
        }
    }

    /**
     * Merges Brave's latest public resources into the local file (by name, so resources the
     * seed has and the download lacks are kept). Takes effect on the next start.
     */
    private static void maybeRefreshResources(Context context, SharedPreferences prefs) {
        long last = prefs.getLong(PREF_RESOURCES_REFRESHED, 0);
        if (System.currentTimeMillis() - last < RESOURCES_REFRESH_MS) return;
        File dir = prepareResourcesDir(context);
        if (dir == null) return;
        try {
            String downloaded = download(RESOURCES_URL);
            if (downloaded == null) return;
            File file = new File(dir, RESOURCES_FILE);
            Map<String, JSONObject> byName = new LinkedHashMap<>();
            JSONArray current = new JSONArray(new String(readFile(file), StandardCharsets.UTF_8));
            for (int i = 0; i < current.length(); i++) {
                JSONObject r = current.getJSONObject(i);
                byName.put(r.getString("name"), r);
            }
            JSONArray fresh = new JSONArray(downloaded);
            for (int i = 0; i < fresh.length(); i++) {
                JSONObject r = fresh.getJSONObject(i);
                byName.put(r.getString("name"), r);
            }
            JSONArray merged = new JSONArray();
            for (JSONObject r : byName.values()) merged.put(r);
            writeAtomically(file, merged.toString().getBytes(StandardCharsets.UTF_8));
            prefs.edit().putLong(PREF_RESOURCES_REFRESHED, System.currentTimeMillis()).apply();
            Log.i(TAG, "Resources refreshed: " + merged.length() + " entries");
        } catch (Exception e) {
            Log.w(TAG, "Resources refresh failed", e);
        }
    }

    // ---- Filter lists --------------------------------------------------------------------

    /** Call on the UI thread after native init. Network work happens in the background. */
    public static void run() {
        Context context = ContextUtils.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String language = Locale.getDefault().getLanguage();
        PostTask.postTask(
                TaskTraits.BEST_EFFORT_MAY_BLOCK,
                () -> {
                    List<String> sources = null;
                    String catalog = download(CATALOG_URL);
                    if (catalog != null) {
                        try {
                            sources = selectSources(catalog, language);
                        } catch (Exception e) {
                            Log.w(TAG, "Catalog parse failed", e);
                        }
                    }
                    if (sources == null || sources.isEmpty()) {
                        sources = new ArrayList<>(Arrays.asList(FALLBACK_SOURCES));
                    }
                    final List<String> finalSources = sources;
                    PostTask.postTask(TaskTraits.UI_DEFAULT, () -> subscribe(prefs, finalSources));
                    PostTask.postDelayedTask(
                            TaskTraits.UI_DEFAULT,
                            BraveTvAdblockBootstrap::logSubscriptionStatus,
                            STATUS_LOG_DELAY_MS);
                    maybeRefreshResources(context, prefs);
                });
    }

    /** Source URLs of Brave's default lists, the TV extras and the device-language lists. */
    static List<String> selectSources(String catalogJson, String language) throws Exception {
        Set<String> urls = new LinkedHashSet<>();
        JSONArray catalog = new JSONArray(catalogJson);
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.getJSONObject(i);
            String uuid = entry.optString("uuid");
            boolean wanted =
                    (entry.optBoolean("default_enabled", false) && supportsAndroid(entry))
                            || EXTRA_LISTS.contains(uuid)
                            || hasLanguage(entry, language);
            if (!wanted) continue;
            JSONArray sources = entry.optJSONArray("sources");
            if (sources == null) continue;
            for (int j = 0; j < sources.length(); j++) {
                String url = sources.getJSONObject(j).optString("url");
                if (url.startsWith("https://")) urls.add(url);
            }
        }
        return new ArrayList<>(urls);
    }

    private static boolean supportsAndroid(JSONObject entry) {
        JSONArray platforms = entry.optJSONArray("platforms");
        if (platforms == null || platforms.length() == 0) return true;
        for (int i = 0; i < platforms.length(); i++) {
            if ("ANDROID".equalsIgnoreCase(platforms.optString(i))) return true;
        }
        return false;
    }

    private static boolean hasLanguage(JSONObject entry, String language) {
        JSONArray languages = entry.optJSONArray("languages");
        if (languages == null || language == null || language.isEmpty()) return false;
        for (int i = 0; i < languages.length(); i++) {
            if (language.equalsIgnoreCase(languages.optString(i))) return true;
        }
        return false;
    }

    /** Adds each source once ever, so lists the user removes stay removed. */
    private static void subscribe(SharedPreferences prefs, List<String> sources) {
        Set<String> done = new HashSet<>(prefs.getStringSet(PREF_SUBSCRIBED, new HashSet<>()));
        List<String> todo = new ArrayList<>();
        for (String url : sources) {
            if (!done.contains(url)) todo.add(url);
        }
        if (todo.isEmpty()) return;
        Profile profile = ProfileManager.getLastUsedRegularProfile();
        FilterListAndroidHandler handler =
                FilterListServiceFactory.getInstance().getFilterListAndroidHandler(profile, null);
        if (handler == null) return;
        for (String spec : todo) {
            Url url = new Url();
            url.url = spec;
            handler.createSubscription(url);
            done.add(spec);
        }
        // Already-sent messages are still delivered after the pipe closes.
        handler.close();
        prefs.edit().putStringSet(PREF_SUBSCRIBED, done).apply();
        Log.i(TAG, "Subscribed to " + todo.size() + " filter lists (" + done.size() + " total)");
    }

    /** Logs how many subscriptions have downloaded, and which have not (diagnostics). */
    private static void logSubscriptionStatus() {
        Profile profile = ProfileManager.getLastUsedRegularProfile();
        FilterListAndroidHandler handler =
                FilterListServiceFactory.getInstance().getFilterListAndroidHandler(profile, null);
        if (handler == null) {
            Log.w(TAG, "Status: filter list service unavailable");
            return;
        }
        handler.getSubscriptions(
                subscriptions -> {
                    int downloaded = 0;
                    for (SubscriptionInfo info : subscriptions) {
                        boolean ok =
                                info.lastSuccessfulUpdateAttempt != null
                                        && info.lastSuccessfulUpdateAttempt.internalValue != 0;
                        if (ok) {
                            downloaded++;
                        } else {
                            Log.w(
                                    TAG,
                                    "Not downloaded: "
                                            + (info.subscriptionUrl == null
                                                    ? "?"
                                                    : info.subscriptionUrl.url)
                                            + " enabled="
                                            + info.enabled
                                            + " attempt="
                                            + (info.lastUpdateAttempt == null
                                                    ? 0
                                                    : info.lastUpdateAttempt.internalValue)
                                            + " text="
                                            + info.lastUpdatedPrettyText);
                        }
                    }
                    Log.i(
                            TAG,
                            "Status: " + subscriptions.length + " subscriptions, " + downloaded
                                    + " downloaded");
                    handler.close();
                });
    }

    // ---- I/O helpers ---------------------------------------------------------------------

    /** Changes with every APK install/update. */
    private static String installStamp(Context context) {
        try {
            return String.valueOf(
                    context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0)
                            .lastUpdateTime);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String readStamp(File stamp) {
        try {
            return new String(readFile(stamp), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String download(String spec) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(spec).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + connection.getResponseCode() + " for " + spec);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "Download failed: " + spec, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_DOWNLOAD_BYTES) throw new Exception("too large");
        }
        return out.toByteArray();
    }

    private static byte[] readFile(File file) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static void writeAtomically(File file, byte[] data) throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(file)) throw new Exception("rename failed");
    }
}
