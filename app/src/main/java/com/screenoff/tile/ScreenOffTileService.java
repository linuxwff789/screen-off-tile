package com.screenoff.tile;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：点击一键“关屏”/“恢复”。
 * 关屏 = 背光灭 + 触摸禁用 + 保持唤醒（不锁屏、不杀应用）
 */
public class ScreenOffTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        ScreenController sc = new ScreenController(this);
        if (sc.isScreenOff()) {
            sc.turnScreenOn();
        } else {
            sc.turnScreenOff();
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        ScreenController sc = new ScreenController(this);
        boolean off = sc.isScreenOff();
        if (off) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(getString(R.string.tile_off_label));
            tile.setSubtitle(getString(R.string.tile_off_subtitle));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_on_label));
            tile.setSubtitle(getString(R.string.tile_on_subtitle));
        }
        tile.updateTile();
    }

    /** 请求把磁贴加入快捷设置（需要用户确认） */
    public static void requestAdd(Context context) {
        TileService.requestListeningState(context, new ComponentName(context, ScreenOffTileService.class));
    }
}
