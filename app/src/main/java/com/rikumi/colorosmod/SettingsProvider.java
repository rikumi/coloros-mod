package com.rikumi.colorosmod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

// 跨进程读取模块设置的 ContentProvider。
// 被 hook 的进程 UID 不同且 SELinux 禁止读取模块 app_data_file, 直接读 prefs / XSharedPreferences
// 均失败; ContentProvider 走 Binder 不受此限制, 模块 App 已退出时也会被按需拉起来服务查询。
//
// 设置存于设备加密(DE)存储: 开机到首次解锁前(Direct Boot)CE 存储尚未挂载, 读 CE 只会拿到空设置,
// 而 SystemUI 正是在锁定态启动的 —— 默认值一旦被初始化期的 hook 固化, 用完密钥解锁也不会纠正,
// 表现为"重启后模块失效, 重启作用域才恢复"。故 provider 声明为 directBootAware 并统一走 DE 存储。
public class SettingsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.rikumi.colorosmod.settings";
    public static final String PREF_NAME = "settings";
    // 一次性取回全部设置的特殊键, 供被 hook 进程后台预热(见 XposedInit#startSettingsLoader)。
    public static final String KEY_ALL = "__all__";

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences prefs() {
        Context ctx = getContext();
        if (ctx == null) return null;
        Context de = ctx.createDeviceProtectedStorageContext();
        return (de != null ? de : ctx).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
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

    // .../__all__ -> 两列(k 键名 / v 整数值)的全部设置; .../<key> -> 单列 v 的一行,
    // 键不存在时返回空游标, 由读取方用自己的默认值。
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            String key = uri.getLastPathSegment();
            if (key == null) return null;
            SharedPreferences sp = prefs();
            if (sp == null) return null;
            java.util.Map<String, ?> all = sp.getAll();
            if (KEY_ALL.equals(key)) {
                MatrixCursor all2 = new MatrixCursor(new String[]{"k", "v"});
                for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                    Integer v = intValue(e.getValue());
                    if (v != null) all2.addRow(new Object[]{e.getKey(), v});
                }
                return all2;
            }
            Integer val = intValue(all.get(key));
            if (val == null) return new MatrixCursor(new String[]{"v"});
            MatrixCursor c = new MatrixCursor(new String[]{"v"});
            c.addRow(new Object[]{val});
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer intValue(Object raw) {
        if (raw instanceof Boolean) return ((Boolean) raw) ? 1 : 0;
        if (raw instanceof Number) return ((Number) raw).intValue();
        return null;
    }
}
