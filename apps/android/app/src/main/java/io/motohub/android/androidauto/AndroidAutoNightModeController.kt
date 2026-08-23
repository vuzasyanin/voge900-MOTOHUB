package io.motohub.android.androidauto

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import io.motohub.android.feature.settings.AndroidAutoNightMode
import io.motohub.android.feature.settings.MotoHubSettings

/**
 * Resolves the rider's Android Auto day/night policy and pushes it into a live session.
 *
 * Auto follows the phone UI night bit, so a system "Dark theme from sunset to sunrise"
 * schedule becomes the map style without a second clock in MOTO-HUB.
 */
object AndroidAutoNightModeController {
    @Volatile
    private var lastAppliedNight: Boolean? = null

    fun isSystemNight(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    fun isNight(context: Context): Boolean =
        MotoHubSettings.androidAutoNightMode(context).isNight(isSystemNight(context))

    fun applyToRunningSession(context: Context, force: Boolean = false): Boolean {
        val night = isNight(context)
        if (!force && lastAppliedNight == night) return false
        val applied = AndroidAutoPreviewRuntime.setNightMode(night)
        if (applied || force) lastAppliedNight = night
        return applied
    }

    fun install(application: Application) {
        application.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                if (MotoHubSettings.androidAutoNightMode(application) != AndroidAutoNightMode.AUTO) {
                    return
                }
                applyToRunningSession(application)
            }

            override fun onLowMemory() = Unit
        })
    }
}
