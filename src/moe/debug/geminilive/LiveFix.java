package moe.debug.geminilive;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Gemini Live tweaks for the Google app (GSA), scoped to
 * com.google.android.googlequicksearchbox only.
 *
 * - Locked Live: fake the keyguard-dismiss callback for GSA's lockscreen
 *   AuthenticationActivity and briefly treat the keyguard as dismissed.
 * - Default Live: launch the Live MainActivity directly when a hotword wake
 *   creates the FloatyActivity.
 * - Auto exit: close the Gemini task when LiveService is destroyed (voice
 *   "end session" and the X/close button both stop it).
 *
 * All lock-screen handling is gated by the "锁屏进入 Live" switch. With it
 * off, a locked hotword wake stays on the native, unlock-requiring path even
 * when "默认进入 Live" is on.
 *
 * Settings are stored in the module app and exposed by SettingsProvider.
 * Features default to enabled when the setting is missing.
 *
 * Tested on: OnePlus 15R (CPH2769), OxygenOS 16.0.5.701, Android 16,
 * Google app 17.68.28.ve.arm64.
 */
public final class LiveFix implements IXposedHookLoadPackage {
    private static final String TAG = "[GeminiLiveTweaks] ";
    private static final String TARGET_PKG = "com.google.android.googlequicksearchbox";

    private static final String MAIN_ACTIVITY =
            "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity";
    private static final String FLOATY_ACTIVITY =
            "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity";
    private static final String AUTH_ACTIVITY =
            "com.google.android.apps.search.assistant.surfaces.voice.lockscreen.keyguard"
                    + ".authenticationactivity.AuthenticationActivity";
    private static final String LIVE_SERVICE =
            "com.google.android.apps.search.assistant.surfaces.voice.robin.ui"
                    + ".conversationmode.service.LiveService";
    private static final String ROUTER_ARGS_CONVERSATION_MODE =
            "com.google.android.apps.search.assistant.surfaces.voice.robin.routing"
                    + ".RobinRouterArgs$FullScreen$ConversationMode";

    private static final long SPOOF_MS = 10000L;
    private static final long CONFIG_REFRESH_MS = 1000L;

    private static volatile long spoofUntil = 0L;
    private static volatile long liveStartedAt = 0L;
    private static volatile boolean liveActive = false;
    private static volatile boolean exitScheduled = false;
    private static volatile WeakReference<Activity> liveActivity = new WeakReference<>(null);
    private static volatile ClassLoader gsaLoader;

    private static volatile long configCheckedAt = 0L;
    private static volatile boolean cfgDefaultLive = true;
    private static volatile boolean cfgAutoAuth = true;
    private static volatile boolean cfgAutoExit = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) {
            return;
        }
        gsaLoader = lp.classLoader;
        hookActivities(lp.classLoader);
        hookKeyguard(lp.classLoader);
        hookLiveService(lp.classLoader);
        XposedBridge.log(TAG + "active in " + lp.processName);
    }

    private static void hookActivities(ClassLoader cl) {
        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", cl);

            XposedBridge.hookAllMethods(activityClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (!activity.getClass().getName().contains(MAIN_ACTIVITY)) {
                            return;
                        }
                        if (!isLiveIntent(activity.getIntent())) {
                            return;
                        }
                        if (!autoAuthEnabled()) {
                            return;
                        }
                        spoofUntil = SystemClock.uptimeMillis() + SPOOF_MS;
                        setShowWhenLocked(activity);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "early showWhenLocked failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(activityClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        String name = activity.getClass().getName();
                        if (name.contains(MAIN_ACTIVITY)) {
                            liveActivity = new WeakReference<>(activity);
                            exitScheduled = false;
                            maybeShowOverKeyguard(activity, activity.getIntent());
                        } else if (name.contains(FLOATY_ACTIVITY)) {
                            maybeAutoLive(activity, activity.getIntent());
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "onCreate handling failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(activityClass, "onNewIntent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (!activity.getClass().getName().contains(MAIN_ACTIVITY)) {
                            return;
                        }
                        Intent intent = param.args.length > 0 && param.args[0] instanceof Intent
                                ? (Intent) param.args[0] : activity.getIntent();
                        if (isLiveIntent(intent)) {
                            if (!liveActive) {
                                liveStartedAt = SystemClock.uptimeMillis();
                            }
                            liveActive = true;
                            liveActivity = new WeakReference<>(activity);
                            exitScheduled = false;
                        }
                        maybeShowOverKeyguard(activity, intent);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "onNewIntent handling failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (!activity.getClass().getName().contains(MAIN_ACTIVITY)) {
                            return;
                        }
                        Intent intent = activity.getIntent();
                        if (isLiveIntent(intent)) {
                            if (!liveActive) {
                                liveStartedAt = SystemClock.uptimeMillis();
                            }
                            liveActive = true;
                            liveActivity = new WeakReference<>(activity);
                        }
                        maybeShowOverKeyguard(activity, intent);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "onResume handling failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(activityClass, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (activity.getClass().getName().contains(MAIN_ACTIVITY)) {
                        liveActive = false;
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "activity hooks failed: " + t);
        }
    }

    private static void maybeAutoLive(final Activity activity, final Intent floatyIntent) {
        try {
            if (!defaultLiveEnabled()) {
                return;
            }
            int trigger = floatyIntent == null ? -1
                    : floatyIntent.getIntExtra("triggerType", -1);
            if (trigger != 3 || liveActive) {
                return;
            }
            if (!autoAuthEnabled() && isLocked(activity)) {
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (!autoAuthEnabled() && isLocked(activity)) {
                        return;
                    }
                    try {
                        Intent live = buildLiveIntent(activity, floatyIntent);
                        if (autoAuthEnabled()) {
                            spoofUntil = SystemClock.uptimeMillis() + SPOOF_MS;
                        }
                        activity.startActivity(live);
                        activity.finish();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "auto-Live failed: " + t);
                    }
                }
            }, 250L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "auto-Live setup failed: " + t);
        }
    }

    private static Intent buildLiveIntent(Activity activity, Intent src) {
        Intent intent = new Intent();
        intent.setClassName(TARGET_PKG, MAIN_ACTIVITY);

        String locale = src == null ? null : src.getStringExtra("primary_locale");
        if (locale == null) {
            locale = "en-US";
        }
        int account = src == null ? 2 : src.getIntExtra("account_id", 2);

        ClassLoader loader = gsaLoader != null ? gsaLoader : activity.getClassLoader();
        Class<?> conversationMode = XposedHelpers.findClass(
                ROUTER_ARGS_CONVERSATION_MODE, loader);
        Object routerArgs = XposedHelpers.newInstance(conversationMode);

        intent.putExtra("primary_locale", locale);
        intent.putExtra("lumi_t", true);
        intent.putExtra("account_id", account);
        intent.putExtra("robin_router_args", (Parcelable) routerArgs);
        intent.putExtra("ROBIN_DEEPLINK_CONVERSATION", false);
        intent.putExtra("showFullScreen", true);
        intent.putExtra("tracing_intent_id", System.nanoTime());
        intent.putExtra("$tiktok$account_id_owned", true);
        intent.addFlags(0x14008000);
        return intent;
    }

    private static void maybeShowOverKeyguard(Activity activity, Intent intent) {
        try {
            if (!autoAuthEnabled() || !isLiveIntent(intent)) {
                return;
            }
            // Keep treating the device as unlocked while Live is on screen.
            spoofUntil = SystemClock.uptimeMillis() + SPOOF_MS;
            setShowWhenLocked(activity);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "showWhenLocked failed: " + t);
        }
    }

    private static boolean isLocked(Activity activity) {
        try {
            KeyguardManager manager = activity.getSystemService(KeyguardManager.class);
            return manager != null
                    && (manager.isKeyguardLocked() || manager.isDeviceLocked());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setShowWhenLocked(Activity activity) {
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
        }
        activity.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }

    private static void hookKeyguard(ClassLoader cl) {
        try {
            Class<?> keyguard = XposedHelpers.findClass("android.app.KeyguardManager", cl);

            for (final String method : new String[]{"isKeyguardLocked", "isDeviceLocked"}) {
                XposedBridge.hookAllMethods(keyguard, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (Boolean.TRUE.equals(param.getResult())
                                    && SystemClock.uptimeMillis() < spoofUntil
                                    && autoAuthEnabled()) {
                                param.setResult(false);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "keyguard spoof failed: " + t);
                        }
                    }
                });
            }

            XposedBridge.hookAllMethods(keyguard, "requestDismissKeyguard", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!autoAuthEnabled()) {
                            return;
                        }
                        if (param.args.length < 2 || !(param.args[0] instanceof Activity)) {
                            return;
                        }
                        Activity activity = (Activity) param.args[0];
                        if (!activity.getClass().getName().contains(AUTH_ACTIVITY)) {
                            return;
                        }
                        final Object callback = param.args[1];
                        if (callback == null) {
                            return;
                        }
                        spoofUntil = SystemClock.uptimeMillis() + SPOOF_MS;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    XposedHelpers.callMethod(callback, "onDismissSucceeded");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "keyguard callback failed: " + t);
                                }
                            }
                        });
                        param.setResult(null);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "keyguard handling failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "keyguard hooks failed: " + t);
        }
    }

    private static void hookLiveService(ClassLoader cl) {
        try {
            Class<?> liveService = XposedHelpers.findClass(LIVE_SERVICE, cl);
            XposedBridge.hookAllMethods(liveService, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handleLiveEnded();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "LiveService hook failed: " + t);
        }
    }

    private static void handleLiveEnded() {
        try {
            if (!autoExitEnabled() || !liveActive) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (liveStartedAt != 0L && now - liveStartedAt < 1000L) {
                return;
            }
            if (exitScheduled) {
                return;
            }
            final Activity activity = liveActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            exitScheduled = true;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            activity.finishAndRemoveTask();
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "auto-exit failed: " + t);
                    }
                }
            }, 350L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "auto-exit handling failed: " + t);
        }
    }

    private static boolean isLiveIntent(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        return extras != null && extras.containsKey("robin_router_args");
    }

    private static void refreshConfig() {
        long now = SystemClock.uptimeMillis();
        if (now - configCheckedAt < CONFIG_REFRESH_MS) {
            return;
        }
        configCheckedAt = now;
        boolean defaultLive = Config.DEFAULT_ENABLED;
        boolean autoAuth = Config.DEFAULT_ENABLED;
        boolean autoExit = Config.DEFAULT_ENABLED;
        try {
            android.app.Application app = AndroidAppHelper.currentApplication();
            if (app != null) {
                Bundle values = app.getContentResolver().call(
                        Config.CONTENT_URI, "get", null, null);
                if (values != null) {
                    defaultLive = values.getBoolean(
                            Config.KEY_DEFAULT_LIVE, Config.DEFAULT_ENABLED);
                    autoAuth = values.getBoolean(
                            Config.KEY_AUTO_AUTH, Config.DEFAULT_ENABLED);
                    autoExit = values.getBoolean(
                            Config.KEY_AUTO_EXIT, Config.DEFAULT_ENABLED);
                }
            }
        } catch (Throwable ignored) {
            // Provider unavailable: keep the defaults (all enabled).
        }
        cfgDefaultLive = defaultLive;
        cfgAutoAuth = autoAuth;
        cfgAutoExit = autoExit;
    }

    private static boolean defaultLiveEnabled() {
        refreshConfig();
        return cfgDefaultLive;
    }

    private static boolean autoAuthEnabled() {
        refreshConfig();
        return cfgAutoAuth;
    }

    private static boolean autoExitEnabled() {
        refreshConfig();
        return cfgAutoExit;
    }
}
