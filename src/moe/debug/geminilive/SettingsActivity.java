package moe.debug.geminilive;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings UI. Values live in this app's SharedPreferences and are exposed to
 * the GSA hooks by SettingsProvider; changing a switch needs no root and takes
 * effect within about a second.
 */
public class SettingsActivity extends Activity {
    private static final String LAUNCHER_ALIAS = "moe.debug.geminilive.Launcher";
    private static final String KEY_TILE_PROMPTED = "tile_prompted";

    private SharedPreferences prefs;
    private Switch defaultLiveSwitch;
    private Switch autoAuthSwitch;
    private Switch autoExitSwitch;
    private Switch hideIconSwitch;
    private TextView status;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        setContentView(buildContent());
        loadSettings();
        applyStatusBar();
    }

    private View buildContent() {
        final int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(pad, pad + insets.getSystemWindowInsetTop(),
                    pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = text("Gemini Live Tweaks", 22, true);
        root.addView(title);

        TextView subtitle = text(
                "开关会立即生效；配置保存在本应用内，约 1 秒内同步到 Google 应用。",
                14, false);
        subtitle.setTextColor(0xFF666666);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        defaultLiveSwitch = addSwitch(root, "默认进入 Live",
                "普通 “Hey Google” 热词唤醒后直接进入 Gemini Live。");
        autoAuthSwitch = addSwitch(root, "锁屏进入 Live",
                "锁屏/熄屏时说 “Hey Google, let's talk” 时绕过解锁，直接进入 Live。");
        autoExitSwitch = addSwitch(root, "结束 Live 后自动退出",
                "说 “end session” 或点 X 结束 Live 后关闭 Gemini，回到桌面或锁屏。");
        hideIconSwitch = addSwitch(root, "隐藏桌面图标",
                "默认隐藏。关闭后图标会重新出现在桌面；隐藏后可从快捷设置的"
                        + "“Live 设置”磁贴打开本应用，首次隐藏会提示添加磁贴。");

        status = text("正在读取配置…", 13, false);
        status.setTextColor(0xFF666666);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        TextView note = text(
                "模块可在 LSPosed 中启用并勾选 Google 作用域。隐藏桌面图标后，使用"
                        + "快捷设置中的“Live 设置”磁贴打开本设置；配置保存在本应用内，"
                        + "重启后保留。",
                12, false);
        note.setTextColor(0xFF888888);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);

        return scroll;
    }

    private Switch addSwitch(LinearLayout parent, String title, String summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = text(title, 16, true);
        TextView summaryView = text(summary, 13, false);
        summaryView.setTextColor(0xFF777777);
        summaryView.setPadding(0, dp(2), dp(8), 0);
        textColumn.addView(titleView);
        textColumn.addView(summaryView);

        Switch toggle = new Switch(this);
        toggle.setChecked(true);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> saveSettings());
        row.addView(textColumn);
        row.addView(toggle);
        parent.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(0x1F000000);
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return toggle;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(0xFF1F1F1F);
        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void applyStatusBar() {
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void loadSettings() {
        boolean defaultLive = prefs.getBoolean(
                Config.KEY_DEFAULT_LIVE, Config.DEFAULT_ENABLED);
        boolean autoAuth = prefs.getBoolean(
                Config.KEY_AUTO_AUTH, Config.DEFAULT_ENABLED);
        boolean autoExit = prefs.getBoolean(
                Config.KEY_AUTO_EXIT, Config.DEFAULT_ENABLED);
        boolean hideIcon = prefs.getBoolean(Config.KEY_HIDE_ICON, true);

        loading = true;
        defaultLiveSwitch.setChecked(defaultLive);
        autoAuthSwitch.setChecked(autoAuth);
        autoExitSwitch.setChecked(autoExit);
        hideIconSwitch.setChecked(hideIcon);
        loading = false;

        applyIconVisibility(hideIcon);
        maybeRequestTile(hideIcon);
        updateStatus(hideIcon);
    }

    private void saveSettings() {
        if (loading) {
            return;
        }
        boolean hideIcon = hideIconSwitch.isChecked();
        prefs.edit()
                .putBoolean(Config.KEY_DEFAULT_LIVE, defaultLiveSwitch.isChecked())
                .putBoolean(Config.KEY_AUTO_AUTH, autoAuthSwitch.isChecked())
                .putBoolean(Config.KEY_AUTO_EXIT, autoExitSwitch.isChecked())
                .putBoolean(Config.KEY_HIDE_ICON, hideIcon)
                .apply();
        applyIconVisibility(hideIcon);
        maybeRequestTile(hideIcon);
        updateStatus(hideIcon);
    }

    private void applyIconVisibility(boolean hide) {
        ComponentName alias = new ComponentName(getPackageName(), LAUNCHER_ALIAS);
        int state = hide
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        getPackageManager().setComponentEnabledSetting(
                alias, state, PackageManager.DONT_KILL_APP);
    }

    private void maybeRequestTile(boolean hide) {
        if (!hide || Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (prefs.getBoolean(KEY_TILE_PROMPTED, false)) {
            return;
        }
        StatusBarManager manager = getSystemService(StatusBarManager.class);
        if (manager == null) {
            return;
        }
        prefs.edit().putBoolean(KEY_TILE_PROMPTED, true).apply();
        try {
            manager.requestAddTileService(
                    new ComponentName(this, SettingsTileService.class),
                    "Live 设置",
                    Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now),
                    getMainExecutor(),
                    result -> {
                    });
        } catch (Throwable ignored) {
            // The user can still add the tile manually in Quick Settings.
        }
    }

    private void updateStatus(boolean hideIcon) {
        String iconStatus = hideIcon
                ? "桌面图标已隐藏；可从快捷设置的 “Live 设置” 磁贴打开本应用。"
                : "桌面图标已显示。";
        status.setText("配置已保存，约 1 秒内同步到 Google 应用。\n" + iconStatus);
    }
}
