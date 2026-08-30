package com.notifrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.notifrelay.ui.RelayMainContent
import com.notifrelay.ui.RelayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (SettingsRepository.get(this).foregroundEnabled) {
            ForegroundServiceController.start(this)
        }
        setContent {
            RelayTheme {
                RelayMainContent(BleRelayManager.get(this))
            }
        }
    }
}
