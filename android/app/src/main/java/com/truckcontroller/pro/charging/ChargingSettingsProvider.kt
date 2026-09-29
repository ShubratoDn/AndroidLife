package com.truckcontroller.pro.charging

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Hands the Charging Animation settings to PhoneDeck's hook in System UI. Only the `settings`
 * call is supported, and only system processes (System UI runs as the system user) may read it.
 */
class ChargingSettingsProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ChargingAnimation.METHOD_SETTINGS) return null
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != Process.myUid()) return null
        val context = context ?: return null
        val identity = Binder.clearCallingIdentity()
        try {
            return ChargingAnimation.toBundle(context)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
