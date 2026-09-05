package com.notifrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.view.WindowCompat
import com.notifrelay.ui.RelayMainContent
import com.notifrelay.ui.RelayTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (SettingsRepository.get(this).foregroundEnabled) {
            ForegroundServiceController.start(this)
        }
        // 应用更新后系统可能保留通知使用权授权但不再绑定监听服务，主动请求重绑
        RelayListenerService.requestListenerRebind(this)
        setContent {
            RelayTheme {
                // M3 自适应：按窗口宽度决定导航形态
                val windowSizeClass = calculateWindowSizeClass(this)
                RelayMainContent(BleRelayManager.get(this), windowSizeClass.widthSizeClass)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BleRelayManager.get(this).setUiVisible(true)
    }

    override fun onPause() {
        super.onPause()
        BleRelayManager.get(this).setUiVisible(false)
    }
}
