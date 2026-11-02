package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import static com.rikumi.colorosmod.FolderGridSettings.*;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.*;

/** Center the folder name and expanded grid as one measured group. */
final class FolderGridCenteringHooks {
    private static boolean active = true;
    private static final WeakHashMap<View, ViewTreeObserver.OnPreDrawListener> pending = new WeakHashMap<>();
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        XposedHelpers.findAndHookMethod("com.android.launcher3.folder.OplusFolder", lp.classLoader,
                "centerAboutIcon", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!active || p.hasThrowable() || (!readBoolCached(ROWS_ENABLED, false)
                                && !readBoolCached(COLUMNS_ENABLED, false))) return;
                        try {
                            View folder = (View) p.thisObject;
                            center(folder);
                            afterNativeLayout(folder);
                        }
                        catch (Throwable error) { log("folder grid centering failed: " + error); }
                    }
                });
    }

    private static boolean center(View folder) {
        if (!(folder.getParent() instanceof ViewGroup parent)) return false;
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(folder, "isEditingName"))) return false;
        WindowInsets windowInsets = folder.getRootWindowInsets();
        if (windowInsets != null && windowInsets.isVisible(WindowInsets.Type.ime())) return false;
        Object value = XposedHelpers.getObjectField(folder, "mContent");
        if (!(value instanceof View content)) return false;
        int contentHeight = content.getMeasuredHeight() - content.getPaddingTop() - content.getPaddingBottom();
        if (contentHeight <= 0 || parent.getHeight() <= 0) return false;
        ViewGroup.LayoutParams params = folder.getLayoutParams();
        int folderHeight = params.height > 0 ? params.height : folder.getMeasuredHeight();
        if (folderHeight <= 0) return false;

        // Include the title and its spacing above the grid in the centered group.
        Integer contentOffset = offsetInFolder(content, folder);
        Object title = XposedHelpers.getObjectField(folder, "mFolderName");
        if (contentOffset == null || !(title instanceof View name) || name.getMeasuredHeight() <= 0) return false;
        Integer nameOffset = offsetInFolder(name, folder);
        if (nameOffset == null) return false;
        int contentTop = contentOffset + content.getPaddingTop();
        int nameTop = nameOffset + name.getPaddingTop();
        int nameHeight = name.getMeasuredHeight() - name.getPaddingTop() - name.getPaddingBottom();
        Rect insets = (Rect) XposedHelpers.callMethod(parent, "getInsets");
        int safeTop = Math.max(0, insets.top);
        int safeBottom = Math.max(safeTop, parent.getHeight() - Math.max(0, insets.bottom));
        int y = centeredGroupTop(safeTop, safeBottom, folderHeight, nameTop, nameHeight, contentTop, contentHeight);
        if (XposedHelpers.getIntField(params, "y") != y) {
            XposedHelpers.setIntField(params, "y", y);
            folder.requestLayout();
            return true;
        }
        return false;
    }

    private static Integer offsetInFolder(View child, View folder) {
        int top = 0;
        while (child != folder) {
            top += child.getTop();
            if (!(child.getParent() instanceof View owner)) return null;
            child = owner;
        }
        return top;
    }

    private static void afterNativeLayout(View folder) {
        if (pending.containsKey(folder) || !folder.getViewTreeObserver().isAlive()) return;
        WeakReference<View> reference = new WeakReference<>(folder);
        ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                View host = reference.get();
                if (host == null) return true;
                pending.remove(host);
                if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!active || (!readBoolCached(ROWS_ENABLED, false) && !readBoolCached(COLUMNS_ENABLED, false))) return true;
                // Native centerAboutIcon can change wrapper margins and content
                // padding. Recheck after that layout, before its first visible frame.
                try { return !center(host); }
                catch (Throwable error) { log("folder grid centering layout failed: " + error); return true; }
            }
        };
        pending.put(folder, listener);
        folder.getViewTreeObserver().addOnPreDrawListener(listener);
    }

    static void cleanup() {
        active = false;
        for (var entry : pending.entrySet()) {
            View host = entry.getKey();
            if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(entry.getValue());
        }
        pending.clear();
    }

    static int centeredGroupTop(int safeTop, int safeBottom, int folderHeight,
            int nameTop, int nameHeight, int contentTop, int contentHeight) {
        int groupTop = Math.min(nameTop, contentTop);
        int groupBottom = Math.max(nameTop + nameHeight, contentTop + contentHeight);
        return centeredTop(safeTop, safeBottom, folderHeight, groupTop, groupBottom - groupTop);
    }

    static int centeredTop(int safeTop, int safeBottom, int folderHeight, int contentTop, int contentHeight) {
        int target = Math.round((safeTop + safeBottom - contentHeight) / 2f - contentTop);
        // Keep the title and native footer inside the available screen as well.
        return Math.max(safeTop, Math.min(Math.max(safeTop, safeBottom - folderHeight), target));
    }

    private FolderGridCenteringHooks() {}
}
