package moe.debug.geminilive;

import android.net.Uri;

/** Shared settings exposed by SettingsProvider and read by the GSA hooks. */
public final class Config {
    public static final String AUTHORITY = "moe.debug.geminilive.settings";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String PREFS = "settings";

    public static final String KEY_DEFAULT_LIVE = "default_live";
    public static final String KEY_AUTO_AUTH = "auto_auth";
    public static final String KEY_AUTO_EXIT = "auto_exit";
    public static final String KEY_HIDE_ICON = "hide_icon";

    public static final boolean DEFAULT_ENABLED = true;

    private Config() {
    }
}
