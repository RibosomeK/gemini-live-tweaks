package moe.debug.geminilive;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Gemini Live tweaks for the Google app (GSA), scoped to
 * com.google.android.googlequicksearchbox only.
 *
 * 1. Locked Live: GSA opens a lockscreen AuthenticationActivity before starting
 *    Live. When that activity calls KeyguardManager.requestDismissKeyguard, we
 *    fake the success callback and keep a short "keyguard already dismissed"
 *    window so the Live launch continues. This does not unlock the device.
 * 2. Default Live: when a hotword wake creates the FloatyActivity, instead of
 *    waiting for the user to say "let's talk", we start the Gemini Live
 *    MainActivity directly with a ConversationMode router argument.
 *
 * Both features are enabled by default. Set a system property to "0" to turn
 * one off (temporary; properties reset after reboot):
 *   debug.hotword.live.default=0
 *   debug.hotword.live.autoauth=0
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
    private static final String ROUTER_ARGS_CONVERSATION_MODE =
            "com.google.android.apps.search.assistant.surfaces.voice.robin.routing"
                    + ".RobinRouterArgs$FullScreen$ConversationMode";

    private static final String PROP_DEFAULT_LIVE = "debug.hotword.live.default";
    private static final String PROP_AUTO_AUTH = "debug.hotword.live.autoauth";
    private static final long SPOOF_MS = 10000L;

    private static volatile long spoofUntil = 0L;
    private static volatile boolean liveActive = false;
    private static volatile ClassLoader gsaLoader;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) {
            return;
        }
        gsaLoader = lp.classLoader;
        hookActivities(lp.classLoader);
        hookKeyguard(lp.classLoader);
        XposedBridge.log(TAG + "hooks installed in " + lp.processName);
    }

    private static void hookActivities(ClassLoader cl) {
        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", cl);

            // Set the window flag before the app's onCreate so the keyguard does
            // not occlude the Live window.
            XposedBridge.hookAllMethods(activityClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity a = (Activity) param.thisObject;
                        if (!a.getClass().getName().contains(MAIN_ACTIVITY)) {
                            return;
                        }
                        if (!isLiveIntent(a.getIntent())) {
                            return;
                        }
                        if (!defaultLiveEnabled() && !autoAuthEnabled()) {
                            return;
                        }
                        spoofUntil = SystemClock.uptimeMillis() + SPOOF_MS;
                        setShowWhenLocked(a);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "early showWhenLocked failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(activityClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity a = (Activity) param.thisObject;
                        String name = a.getClass().getName();
                        if (name.contains(MAIN_ACTIVITY)) {
                            maybeShowOverKeyguard(a, a.getIntent());
                        } else if (name.contains(FLOATY_ACTIVITY)) {
                            maybeAutoLive(a, a.getIntent());
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
                        Activity a = (Activity) param.thisObject;
                        if (!a.getClass().getName().contains(MAIN_ACTIVITY)) {
                            return;
                        }
                        Intent it = param.args.length > 0 && param.args[0] instanceof Intent
                                ? (Intent) param.args[0] : a.getIntent();
                        if (isLiveIntent(it)) {
                            liveActive = true;
                        }
                        maybeShowOverKeyguard(a, it);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "onNewIntent handling failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity a = (Activity) param.thisObject;
                        if (!a.getClass().getName().contains(MAIN_ACTIVITY)) {
                            return;
                        }
                        Intent it = a.getIntent();
                        if (isLiveIntent(it)) {
                            liveActive = true;
                        }
                        maybeShowOverKeyguard(a, it);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "onResume handling failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(activityClass, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    if (a.getClass().getName().contains(MAIN_ACTIVITY)) {
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
            if (trigger != 3) {
                return;
            }
            if (liveActive) {
                return;
            }
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        XposedBridge.log(TAG + "auto-Live skipped: Floaty is finishing");
                        return;
                    }
                    try {
                        Intent live = buildLiveIntent(activity, floatyIntent);
                        spoofUntil = SystemClock.uptimeMillis() + SPOOF_MS;
                        activity.startActivity(live);
                        activity.finish();
                        XposedBridge.log(TAG + "auto-Live launched");
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
            if (!defaultLiveEnabled() && !autoAuthEnabled()) {
                return;
            }
            if (!isLiveIntent(intent)) {
                return;
            }
            // Keep treating the device as unlocked while Live is on screen.
            spoofUntil = SystemClock.uptimeMillis() + SPOOF_MS;
            setShowWhenLocked(activity);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "showWhenLocked failed: " + t);
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
                                    && SystemClock.uptimeMillis() < spoofUntil) {
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
                                    XposedBridge.log(TAG + "faked keyguard dismissal for Live");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "keyguard callback failed: " + t);
                                }
                            }
                        });
                        param.setResult(null);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "keyguard requestDismiss handling failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "keyguard hooks failed: " + t);
        }
    }

    private static boolean isLiveIntent(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        return extras != null && extras.containsKey("robin_router_args");
    }

    private static boolean defaultLiveEnabled() {
        return !"0".equals(readProp(PROP_DEFAULT_LIVE));
    }

    private static boolean autoAuthEnabled() {
        return !"0".equals(readProp(PROP_AUTO_AUTH));
    }

    private static String readProp(String name) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", null);
            Object value = XposedHelpers.callStaticMethod(sp, "get", name, "");
            return value == null ? "" : value.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
