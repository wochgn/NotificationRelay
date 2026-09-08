package com.notifrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.notifrelay.ui.RelayTheme

class OnboardingActivity : ComponentActivity() {
    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private var refreshVersion by mutableIntStateOf(0)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshVersion++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SettingsRepository.get(this).onboarded) {
            goToMain()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            RelayTheme {
                @Suppress("UNUSED_EXPRESSION") refreshVersion
                OnboardingContent(
                    listenerEnabled = isListenerEnabled(),
                    permissionsGranted = hasAllPermissions(),
                    onOpenListener = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    onDone = {
                        if (isListenerEnabled() && hasAllPermissions()) {
                            SettingsRepository.get(this).onboarded = true
                            goToMain()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVersion++
        RecentsController.applyFromSettings(this)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun OnboardingContent(
    listenerEnabled: Boolean,
    permissionsGranted: Boolean,
    onOpenListener: () -> Unit,
    onRequestPermissions: () -> Unit,
    onDone: () -> Unit
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.size(88.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(28.dp)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Outlined.SyncAlt, null, Modifier.size(42.dp)) }
                        Spacer(Modifier.height(16.dp))
                        Text("通知流转", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "在多台设备间通过蓝牙流转通知，开始前请完成以下授权",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                item {
                    PermissionCard(
                        Icons.Outlined.Notifications, "通知使用权",
                        granted = listenerEnabled,
                        status = if (listenerEnabled) "已开启" else "未开启",
                        action = "去开启", doneText = "已开启", onClick = onOpenListener
                    )
                }
                item {
                    PermissionCard(
                        Icons.Outlined.Bluetooth, "蓝牙与通知权限",
                        granted = permissionsGranted,
                        status = if (permissionsGranted) "已授予" else "未授予",
                        action = "去授权", doneText = "已授权", onClick = onRequestPermissions
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("保持后台运行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "建议在系统设置中允许本应用后台运行 / 自启动，并在电池优化里设为「不限制」，否则切到后台后可能被系统清理、导致通知无法流转。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onDone,
                enabled = listenerEnabled && permissionsGranted,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            ) { Text("开始使用") }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    granted: Boolean,
    status: String,
    action: String,
    doneText: String,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(30.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                // 授权完成：实心、白字、不可点击的完成态按钮
                Button(
                    onClick = {},
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = Color.White
                    )
                ) { Text(doneText) }
            } else {
                OutlinedButton(onClick = onClick) { Text(action) }
            }
        }
    }
}
