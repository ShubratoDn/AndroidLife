package com.truckcontroller.pro.charging.hook

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.truckcontroller.pro.charging.ChargeAnimationView
import com.truckcontroller.pro.charging.ChargeStyle
import com.truckcontroller.pro.charging.ChargingAnimation
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Member

/**
 * LSPosed entry point (listed in assets/xposed_init).
 * - System UI (HyperOS): covers the lock screen charging screen with PhoneDeck's animation.
 * - PhoneDeck itself: reports that the module is active.
 */
class SystemUiHook : IXposedHookLoadPackage {

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val PHONEDECK = "com.truckcontroller.pro"
        /** Base class of every HyperOS charging animation (ring, video); it is a FrameLayout. */
        private const val CHARGE_VIEW = "com.android.keyguard.charge.container.IChargeView"
        /**
         * Root of the charging screen. The animation sits in a container below the level text,
         * logo and icon views, so PhoneDeck's view goes here, above all of them.
         */
        private const val CHARGE_SCREEN = "com.android.keyguard.charge.container.MiuiChargeAnimationView"
        private const val VIEW_TAG = "phonedeck_charge_animation"
        private const val FADE_MS = 300L
        /** HyperOS closes the charging screen by itself with these after about 2 seconds. */
        private val AUTO_DISMISS = setOf("dealWithAnimationShow", "dismiss_for_timeout")
    }

    /** PhoneDeck's view on the charging screen. */
    private var current: WeakReference<View>? = null
    /** The charging screen's own `startDismiss(String)`, used to close it when PhoneDeck's timer ends. */
    private var startDismiss: Member? = null
    private var timeout: Runnable? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            PHONEDECK -> XposedHelpers.findAndHookMethod(ChargingAnimation::class.java.name, lpparam.classLoader,
                "isModuleActive", XC_MethodReplacement.returnConstant(true))
            SYSTEM_UI -> hookSystemUi(lpparam.classLoader)
        }
    }

    private fun hookSystemUi(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(CHARGE_VIEW, classLoader, "startAnimation",
                Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { attach(param.thisObject as FrameLayout) }
                            .onFailure { XposedBridge.log("PhoneDeck: charge view failed: $it") }
                    }
                })
            // HyperOS makes its animation transparent while dismissing; fade PhoneDeck's view with it
            XposedHelpers.findAndHookMethod(CHARGE_VIEW, classLoader, "setComponentTransparent",
                Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == true) current?.get()?.animate()?.alpha(0f)?.setDuration(FADE_MS)
                    }
                })
            // While PhoneDeck's animation shows and the charger is in, HyperOS's own quick close is
            // ignored; PhoneDeck's timer (or tap, unlock, a button, screen off, unplug) closes it
            startDismiss = XposedHelpers.findAndHookMethod(CHARGE_SCREEN, classLoader, "startDismiss",
                String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val screen = param.thisObject as View
                        if (current?.get() == null) return
                        if (param.args[0] in AUTO_DISMISS && isPlugged(screen.context)) {
                            param.result = null
                            return
                        }
                        cancelTimeout(screen)
                    }
                }).hookedMethod
            XposedBridge.log("PhoneDeck: charging animation hook installed")
        }.onFailure { XposedBridge.log("PhoneDeck: charging animation hook not installed: $it") }
    }

    /** Runs on every plug-in: a fresh view with the current settings, on top of the charging screen. */
    private fun attach(chargeView: FrameLayout) {
        current?.get()?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
        current = null
        val settings = readSettings(chargeView.context)
        if (!settings.getBoolean(ChargingAnimation.KEY_ENABLED, true)) return
        val style = ChargeStyle.entries.firstOrNull { it.name == settings.getString(ChargingAnimation.KEY_STYLE) }
            ?: ChargeStyle.NEON_RING

        val host = chargeScreen(chargeView)
        val view = ChargeAnimationView(chargeView.context, style, settings.getBoolean(ChargingAnimation.KEY_DETAILS, true))
            .apply { tag = VIEW_TAG }
        (host ?: chargeView).addView(view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        current = WeakReference(view)

        if (host != null) {
            cancelTimeout(host)
            if (!settings.getBoolean(ChargingAnimation.KEY_STAY_ON, false)) {
                val close = Runnable {
                    timeout = null
                    startDismiss?.let { runCatching { XposedBridge.invokeOriginalMethod(it, host, arrayOf("dismiss_for_timeout")) } }
                }
                timeout = close
                host.postDelayed(close, ChargingAnimation.SHOW_SECONDS * 1000L)
            }
        }
    }

    private fun cancelTimeout(screen: View) {
        timeout?.let { screen.removeCallbacks(it) }
        timeout = null
    }

    private fun isPlugged(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun chargeScreen(chargeView: View): ViewGroup? {
        var parent = chargeView.parent
        while (parent is ViewGroup) {
            if (parent.javaClass.name == CHARGE_SCREEN) return parent
            parent = parent.parent
        }
        return null
    }

    /** Settings from PhoneDeck (defaults if the app can't be reached). */
    private fun readSettings(context: Context): Bundle =
        runCatching {
            context.contentResolver.call(ChargingAnimation.URI, ChargingAnimation.METHOD_SETTINGS, null, null)
        }.getOrNull() ?: Bundle()
}
