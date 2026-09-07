package com.notifrelay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.notifrelay.ui.RelayMainContent
import com.notifrelay.ui.RelayTheme

// 全局界面状态：MainActivity 与各页面共享，切换风格/主题即时生效且保持页面位置
var uiStyleState by mutableStateOf("md3")
    private set

var appTabState by mutableStateOf("devices")
    private set

fun setUiStyle(style: String) {
    uiStyleState = style
}

fun setAppTab(tab: String) {
    appTabState = tab
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
        val settings = SettingsRepository.get(this)
        uiStyleState = settings.uiStyle
        setContent {
            // 沉浸式状态下栏/小白条：按主题自动反色（浅色主题深色图标，深色主题浅色图标）
            val systemDark = isSystemInDarkTheme()
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = !systemDark
            insetsController.isAppearanceLightNavigationBars = !systemDark

            // M3 自适应：按窗口宽度决定导航形态
            val windowSizeClass = calculateWindowSizeClass(this)
            // 设置页滚动状态提升到根级：切换风格/底栏形态时保持页面位置
            val settingsScrollState = rememberScrollState()
            if (uiStyleState == "miuix") {
                com.notifrelay.ui.miuix.MiuixRelayApp(
                    manager = BleRelayManager.get(this),
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    currentTab = appTabState,
                    onTabChange = { setAppTab(it) },
                    settingsScrollState = settingsScrollState
                )
            } else {
                RelayTheme {
                    RelayMainContent(
                        manager = BleRelayManager.get(this),
                        widthSizeClass = windowSizeClass.widthSizeClass,
                        currentTab = appTabState,
                        onTabChange = { setAppTab(it) },
                        settingsScrollState = settingsScrollState
                    )
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
