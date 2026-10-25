package com.rikumi.colorosmod;

import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.List;

public final class HomeControlsLaunchIntents {
    private HomeControlsLaunchIntents() {}

    public static List<Intent> apps(Context context) {
        ArrayList<Intent> intents = new ArrayList<>();
        for (String pkg : new String[]{"com.google.android.apps.chromecast.app", "com.xiaomi.smarthome"}) {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) intents.add(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return intents;
    }
}
