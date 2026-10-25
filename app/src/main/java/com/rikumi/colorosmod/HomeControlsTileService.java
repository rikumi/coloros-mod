package com.rikumi.colorosmod;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public final class HomeControlsTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(getString(R.string.ui_home_controls_tile));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_home_controls_tile));
        tile.setState(HomeControlsTileSettings.enabled(this) ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        if (!HomeControlsTileSettings.enabled(this)) return;

        for (Intent intent : HomeControlsLaunchIntents.apps(this)) {
            try {
                if (Build.VERSION.SDK_INT >= 34)
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                else startActivityAndCollapse(intent);
                return;
            } catch (RuntimeException error) {
                XposedInit.log("home controls app fallback failed: " + error);
            }
        }
        Toast.makeText(this, R.string.ui_home_controls_no_app, Toast.LENGTH_SHORT).show();
    }
}
