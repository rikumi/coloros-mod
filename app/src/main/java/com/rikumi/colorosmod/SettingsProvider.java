package com.rikumi.colorosmod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 跨进程读取模块设置的 ContentProvider。
 *
 * 为什么需要它: 被 hook 的进程(launcher / systemui 等)以不同 UID 运行, 且 SELinux 禁止其读取
 * 模块 App 的 app_data_file, 导致直接读 prefs 文件 / XSharedPreferences(经 LSPosed 守护进程) 均失败,
 * 开关永远返回默认值。ContentProvider 走 Binder, 不受文件系统 SELinux 限制: hook 进程用
 * getContentResolver().query(content://com.rikumi.colorosmod.settings/<key>) 即可拿到真实值;
 * 即使模块 App 已退出, Android 也会按需拉起其进程来服务本次查询。
 */
public class SettingsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.rikumi.colorosmod.settings";
    public static final String PREF_NAME = "settings";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/setting";
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
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            String key = uri.getLastPathSegment();
            if (key == null) return null;
            Context ctx = getContext();
            if (ctx == null) return null;
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            // 支持 boolean 与 int 两种值; 键不存在时返回空游标, 由读取方使用各自的默认值
            // (boolean 读取方默认 true, 与旧行为一致)。
            java.util.Map<String, ?> all = sp.getAll();
            Object raw = all.get(key);
            int val;
            if (raw instanceof Boolean) {
                val = (Boolean) raw ? 1 : 0;
            } else if (raw instanceof Number) {
                val = ((Number) raw).intValue();
            } else {
                return new MatrixCursor(new String[]{"v"});
            }
            MatrixCursor c = new MatrixCursor(new String[]{"v"});
            c.addRow(new Object[]{val});
            return c;
        } catch (Throwable t) {
            return null;
        }
    }
}
