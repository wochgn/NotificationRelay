package com.notifrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.notifrelay.ui.RelayMainContent
import com.notifrelay.ui.RelayTheme

// 全局界面风格状态：MainActivity 与各页面共享，切风格即时生效
var uiStyleState by mutableStateOf("md3")
    private set

fun setUiStyle(style: String) {
    uiStyleState = style
}

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
        uiStyleState = SettingsRepository.get(this).uiStyle
        setContent {
            // M3 自适应：按窗口宽度决定导航形态
            val windowSizeClass = calculateWindowSizeClass(this)
            if (uiStyleState == "miuix") {
                com.notifrelay.ui.miuix.MiuixRelayApp(manager = BleRelayManager.get(this), widthSizeClass = windowSizeClass.widthSizeClass)
            } else {
                RelayTheme {
                    RelayMainContent(BleRelayManager.get(this), windowSizeClass.widthSizeClass)
                }
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
