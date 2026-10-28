package com.hyper.podsrx.hook;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.lang.reflect.Method;

/**
 * The security center battery widget only counts a headset the system reports as connected.
 * While none is, its headset slot shows the bound AirPods from the readings the app keeps live
 * from the case advertisements, including with the lid closed.
 */
final class WidgetBatteryHost {
    private static final String PROVIDER = "com.miui.securitycenter.WidgetProvider";
    private static final String QUERY = "getWearableAndHeadSetData";
    private static final String COLUMN = "headsetBattery";

    static void install(HyperPodsModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        Class<?> provider = Class.forName(PROVIDER, false, loader);
        Method query = provider.getDeclaredMethod("query", Uri.class, String[].class, String.class, String[].class, String.class);
        module.hook(query).intercept(chain -> {
            Object result = chain.proceed();
            if (!ReloadLifecycle.isActive() || !(result instanceof Cursor)) return result;
            try {
                Uri uri = (Uri) chain.getArg(0);
                if (uri != null && QUERY.equals(uri.getLastPathSegment())) return withHeadset((Cursor) result, preferences);
            } catch (Exception | LinkageError error) {
                DiagnosticLog.w("HyperPods-X", "Widget headset battery skipped", error);
            }
            return result;
        });
        DiagnosticLog.i("HyperPods-X", "Widget headset battery ready");
    }

    private static Cursor withHeadset(Cursor cursor, SharedPreferences preferences) {
        int level = preferences.getBoolean("enabled", false) ? preferences.getInt("widget_headset", -1) : -1;
        int column = cursor.getColumnIndex(COLUMN);
        boolean replace = level >= 0 && level <= 100 && column >= 0 && cursor.moveToFirst() && cursor.getInt(column) < 0;
        if (!replace) {
            cursor.moveToPosition(-1);
            return cursor;
        }
        String[] names = cursor.getColumnNames();
        Object[] row = new Object[names.length];
        for (int i = 0; i < names.length; i++) row[i] = i == column ? Integer.valueOf(level) : value(cursor, i);
        MatrixCursor copy = new MatrixCursor(names, 1);
        copy.addRow(row);
        cursor.close();
        return copy;
    }

    private static Object value(Cursor cursor, int index) {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER: return cursor.getLong(index);
            case Cursor.FIELD_TYPE_FLOAT: return cursor.getDouble(index);
            case Cursor.FIELD_TYPE_STRING: return cursor.getString(index);
            case Cursor.FIELD_TYPE_BLOB: return cursor.getBlob(index);
            default: return null;
        }
    }
}
