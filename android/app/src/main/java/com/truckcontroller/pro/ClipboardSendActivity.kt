package com.truckcontroller.pro

import android.app.Activity
import android.content.ClipboardManager
import android.widget.Toast
import com.truckcontroller.pro.transfer.FileTransfer

/**
 * Invisible screen that sends the phone's clipboard to connected PCs. Android only lets the app
 * on screen read the clipboard (and only once its window has focus), so the notification button
 * and the Quick Settings tile open this for a moment instead of reading it in the background.
 */
class ClipboardSendActivity : Activity() {

    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        Toast.makeText(this, send(), Toast.LENGTH_SHORT).show()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun send(): String {
        val hub = FileTransfer.hub
        if (hub == null || !FileTransfer.isRunning) return "Turn on File Transfer first"
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) return "The clipboard is empty"
        val pcs = hub.onlineDevices().size
        if (pcs == 0) return "No PC is connected"
        return runCatching { hub.clipFromPhone(text) }.fold(
            onSuccess = { item -> if (item == null) "The PC already has this clipboard" else "Clipboard sent to ${if (pcs == 1) "the PC" else "$pcs PCs"}" },
            onFailure = { it.message ?: "Couldn't send the clipboard" },
        )
    }
}
