package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import com.rikumi.colorosmod.R;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.rikumi.colorosmod.XposedInit.MODULE_PACKAGE;

final class SystemNotificationIconMasks {
    private static final Map<String, Context> contexts = new java.util.HashMap<>();
    private static final Map<String, Icon> cache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Icon> entry) { return size() > 16; }
    };

    static Icon source(StatusBarNotification sbn) {
        var notification = sbn.getNotification();
        return notification.extras != null && notification.extras.getBoolean("oplus_smallicon_use_app_icon", false)
                ? notification.extras.getParcelable("oplus_small_icon", Icon.class) : notification.getSmallIcon();
    }

    static synchronized Icon resolve(Context context, StatusBarNotification sbn, Icon icon) {
        if (icon == null || icon.getType() != Icon.TYPE_RESOURCE) return null;
        String owner = sbn.getPackageName(), pkg = icon.getResPackage();
        if (pkg == null || pkg.isEmpty()) return null;
        if (!owner.equals("android") && !owner.equals("com.android.systemui")) return null;
        try {
            Context target = contexts.get(pkg);
            if (target == null) { target = context.createPackageContext(pkg, 0); contexts.put(pkg, target); }
            String name = target.getResources().getResourceEntryName(icon.getResId());
            if (owner.equals("android") && pkg.equals("android") && name.equals("stat_sys_adb"))
                return Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_android_debug_monochrome);

            if (owner.equals("android") && pkg.equals("com.android.wifi.resources") && name.equals("ic_wifi_settings"))
                return Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_hotspot_monochrome);
            if (!owner.equals("com.android.systemui") || !pkg.equals(owner)
                    || !(name.equals("zen_mode_icon") || name.equals("battery_full_notification"))) return null;
            if (name.equals("zen_mode_icon"))
                return Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_dnd_monochrome);
            String key = pkg + ':' + icon.getResId() + ':' + sbn.getNotification().iconLevel + ':'
                    + target.getResources().getConfiguration().hashCode();
            if (cache.containsKey(key)) return cache.get(key);
            Icon mask = extract(icon.loadDrawable(target), sbn.getNotification().iconLevel);
            cache.put(key, mask);
            return mask;
        } catch (android.content.pm.PackageManager.NameNotFoundException | android.content.res.Resources.NotFoundException error) {
            return null;
        }
    }

    private static Icon extract(Drawable drawable, int level) {
        if (drawable == null) return null;
        final int size = 96, inset = 8;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            drawable = drawable.mutate(); drawable.setLevel(level); drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            int[] source = new int[size * size]; bitmap.getPixels(source, 0, size, 0, 0, size, size);
            int[] pixels = WhiteNotificationIconMask.extract(source);
            if (pixels == null) return null;
            int left = size, top = size, right = -1, bottom = -1;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                if ((pixels[y * size + x] >>> 24) < 24) continue;
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
            if (right < left || bottom < top || left < 1 || top < 1 || right >= size - 1 || bottom >= size - 1) return null;
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            int width = right - left + 1, height = bottom - top + 1;
            float scale = (size - 2f * inset) / Math.max(width, height);
            int w = Math.round(width * scale), h = Math.round(height * scale);
            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawBitmap(bitmap, new Rect(left, top, right + 1, bottom + 1),
                    new Rect((size - w) / 2, (size - h) / 2, (size - w) / 2 + w, (size - h) / 2 + h),
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            return Icon.createWithBitmap(output);
        } finally { bitmap.recycle(); }
    }

    static synchronized void cleanup() { cache.clear(); contexts.clear(); }
}
