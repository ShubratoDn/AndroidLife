package com.truckcontroller.pro.dimmer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.truckcontroller.pro.HomeActivity
import com.truckcontroller.pro.R

/** Quick Settings tile: tap to switch Night Screen on or off. */
class NightScreenTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        when {
            NightScreen.isRunning -> NightScreen.stop(this)
            !NightScreen.canDrawOverlays(this) -> openApp()
            else -> {
                val started = runCatching { NightScreen.start(this) }.isSuccess
                if (!started) openApp()
            }
        }
        // The service updates the tile once it has started or stopped
        qsTile?.let {
            it.state = if (NightScreen.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val running = NightScreen.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Night Screen"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_moon)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "${NightScreen.level(this)} %" else "Off"
        }
        tile.updateTile()
    }

    /** Opens the app so the user can grant the overlay permission or start Night Screen there. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(HomeActivity.EXTRA_OPEN_NIGHT_SCREEN, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
