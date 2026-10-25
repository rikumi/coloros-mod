package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.log;
import static com.rikumi.colorosmod.XposedInit.readBool;

import android.database.sqlite.SQLiteDatabase;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class MediaProviderHooks {
    private static final String MEDIA_PROVIDER = "com.android.providers.media.MediaProvider";
    private static final String MEDIA_VOLUME = "com.android.providers.media.MediaVolume";

    private static final String[][] BLOCKABLE_FOLDERS = {
            {"Podcasts", "media_folder_block_podcasts"},
            {"Ringtones", "media_folder_block_ringtones"},
            {"Alarms", "media_folder_block_alarms"},
            {"Notifications", "media_folder_block_notifications"},
            {"Movies", "media_folder_block_movies"},
            {"Audiobooks", "media_folder_block_audiobooks"},
            {"Recordings", "media_folder_block_recordings"},
    };

    private MediaProviderHooks() {}

    public static void hookMediaProvider(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> provider = XposedHelpers.findClass(MEDIA_PROVIDER, lpparam.classLoader);
            Class<?> volume = XposedHelpers.findClass(MEDIA_VOLUME, lpparam.classLoader);

            XposedHelpers.findAndHookMethod(provider, "getDefaultFolderNames",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof String[])) return;
                            String[] original = (String[]) result;
                            List<String> filtered = new ArrayList<String>(original.length);
                            for (String folder : original) {
                                if (!isBlocked(folder)) filtered.add(folder);
                            }
                            param.setResult(filtered.toArray(new String[0]));
                        }
                    });

            XposedHelpers.findAndHookMethod(provider, "ensureDefaultFolders",
                    volume, SQLiteDatabase.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object mediaVolume = param.args[0];
                            Object name = XposedHelpers.callMethod(mediaVolume, "getName");
                            if (!"external_primary".equals(name)) return;
                            Object path = XposedHelpers.callMethod(mediaVolume, "getPath");
                            if (!(path instanceof File)) return;
                            deleteBlockedEmptyFolders((File) path);
                        }
                    });
            log("HOOK OK MediaProvider media folders");
        } catch (Throwable t) {
            log("HOOK FAIL MediaProvider media folders: " + t);
        }
    }

    private static boolean isBlocked(String folder) {
        for (String[] item : BLOCKABLE_FOLDERS) {
            if (item[0].equals(folder) && readBool(item[1], false)) return true;
        }
        return false;
    }

    private static void deleteBlockedEmptyFolders(File root) {
        for (String[] item : BLOCKABLE_FOLDERS) {
            if (!readBool(item[1], false)) continue;
            File folder = new File("/data/media/0", item[0]);
            boolean deleted = folder.isDirectory() && folder.delete();
            if (!deleted) {
                folder = new File(root, item[0]);
                deleted = folder.isDirectory() && folder.delete();
            }
            if (deleted) {
                log("deleted empty blocked media folder: " + item[0]);
            }
        }
    }

}
