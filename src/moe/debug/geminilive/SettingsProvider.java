package moe.debug.geminilive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Read-only settings bridge for the GSA hooks. Exposes three booleans only. */
public final class SettingsProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"get".equals(method) || getContext() == null) {
            return null;
        }
        SharedPreferences prefs = getContext()
                .getSharedPreferences(Config.PREFS, 0);
        Bundle result = new Bundle();
        result.putBoolean(Config.KEY_DEFAULT_LIVE,
                prefs.getBoolean(Config.KEY_DEFAULT_LIVE, Config.DEFAULT_ENABLED));
        result.putBoolean(Config.KEY_AUTO_AUTH,
                prefs.getBoolean(Config.KEY_AUTO_AUTH, Config.DEFAULT_ENABLED));
        result.putBoolean(Config.KEY_AUTO_EXIT,
                prefs.getBoolean(Config.KEY_AUTO_EXIT, Config.DEFAULT_ENABLED));
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
