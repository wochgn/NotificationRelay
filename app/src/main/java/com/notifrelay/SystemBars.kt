package com.notifrelay

import android.app.Activity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors

object SystemBars {
    fun apply(activity: Activity) {
        val statusColor = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorSurface,
            0
        )
        val navigationColor = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorSurfaceContainer,
            statusColor
        )
        activity.window.statusBarColor = statusColor
        activity.window.navigationBarColor = navigationColor

        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(statusColor) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(navigationColor) > 0.5
        }
    }
}
