package com.notifrelay.ui

import android.bluetooth.BluetoothManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.notifrelay.BleRelayManager
import com.notifrelay.DeviceInfo
import com.notifrelay.DiscoveryState
import com.notifrelay.EventLog
import com.notifrelay.ForegroundServiceController
import com.notifrelay.R
import com.notifrelay.RelayState
import com.notifrelay.SavedDevice
import com.notifrelay.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class Destination(val route: String, val title: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("devices", "设备", Icons.Outlined.Devices),
    Destination("apps", "应用", Icons.Outlined.Apps),
    Destination("settings", "设置", Icons.Outlined.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayMainContent(manager: BleRelayManager) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val route = currentEntry?.destination?.route ?: "devices"
    val destination = destinations.firstOrNull { it.route == route } ?: destinations.first()
    var pairingVersion by remember { mutableIntStateOf(0) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LifecycleResumeEffect(manager) {
        val listener: (RelayState) -> Unit = { mainHandler.post { pairingVersion++ } }
        manager.observeState(listener)
        pairingVersion++
        onPauseOrDispose { manager.removeState(listener) }
    }
    @Suppress("UNUSED_EXPRESSION") pairingVersion
    BackHandler { (context as? Activity)?.finish() }

    if (manager.connected && manager.needsLocalPairConfirmation()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("确认配对设备") },
            text = {
                Text(
                    "「${manager.remoteName.ifBlank { "附近设备" }}」请求与你建立通知流转连接。\n\n" +
                        "请先核对两台设备上显示的设备名称，确认名称一致且确实是你要连接的设备。"
                )
            },
            confirmButton = { TextButton(onClick = manager::acceptPairing) { Text("确认配对") } },
            dismissButton = { TextButton(onClick = manager::rejectPairing) { Text("拒绝") } }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(destination.title) }) },
        bottomBar = {
            NavigationBar {
                destinations.forEach { item ->
                    NavigationBarItem(
                        selected = route == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                                launchSingleTop = true
                                restoreState = false
                            }
                        },
                        icon = { Icon(item.icon, null) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "devices",
            modifier = Modifier.padding(padding)
        ) {
            composable("devices") { DevicesScreen(manager) }
            composable("apps") { AppsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}

private data class DeviceUiState(
    val connected: Boolean,
    val remoteName: String,
    val remoteAndroid: String,
    val remoteBattery: Int,
    val remoteDeviceId: String,
    val pairingRequired: Boolean,
    val paired: Boolean,
    val finding: Boolean,
    val bluetoothEnabled: Boolean,
    val listenerEnabled: Boolean,
    val foregroundEnabled: Boolean,
    val discovery: DiscoveryState
)

private data class DeviceRowUi(
    val id: String,
    val address: String,
    val name: String,
    val subtitle: String,
    val connected: Boolean,
    val saved: Boolean
)

@Composable
private fun DevicesScreen(manager: BleRelayManager) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    var discovery by remember { mutableStateOf(DiscoveryState(false, emptyList())) }
    var refresh by remember { mutableIntStateOf(0) }
    var wasConnected by remember { mutableStateOf(manager.visibleConnected()) }
    var manualDisconnect by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    fun snapshot(): DeviceUiState {
        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return DeviceUiState(
            manager.visibleConnected(), manager.remoteName, manager.remoteAndroid,
            manager.remoteBattery, manager.remoteDeviceId, manager.pairingRequired,
            manager.paired, manager.findingRemote, bluetooth.adapter?.isEnabled == true,
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            repo.foregroundEnabled, discovery
        )
    }

    DisposableEffect(manager, lifecycleOwner) {
        var registered = false
        val stateListener: (RelayState) -> Unit = { state ->
            handler.post {
                val justDisconnected = wasConnected && !state.connected
                val userDisconnect = manager.consumeUserDisconnectEvent()
                wasConnected = state.connected
                if (justDisconnected) {
                    if (manualDisconnect || userDisconnect) {
                        handler.postDelayed({
                            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                manager.role == BleRelayManager.Role.NONE
                            ) manager.startDiscovery(autoConnectSaved = false)
                        }, 900L)
                    } else {
                        manager.startDiscovery()
                    }
                }
                manualDisconnect = false
                refresh++
            }
        }
        val discoveryListener: (DiscoveryState) -> Unit = { value ->
            handler.post { discovery = value; refresh++ }
        }
        fun startObserving() {
            if (registered) return
            registered = true
            wasConnected = manager.visibleConnected()
            manager.observeState(stateListener)
            manager.observeDiscovery(discoveryListener)
            if (!manager.visibleConnected() && manager.role == BleRelayManager.Role.NONE && !manager.isConnecting()) {
                manager.startDiscovery(autoConnectSaved = !manager.isAutoReconnectPaused())
            }
            refresh++
        }
        fun stopObserving() {
            if (!registered) return
            registered = false
            manager.removeState(stateListener)
            manager.removeDiscovery(discoveryListener)
            if (!repo.foregroundEnabled) manager.stopDiscovery()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startObserving()
                Lifecycle.Event.ON_PAUSE -> stopObserving()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startObserving()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            stopObserving()
            handler.removeCallbacksAndMessages(null)
        }
    }

    @Suppress("UNUSED_EXPRESSION") refresh
    val state = snapshot()
    val rows = buildDeviceRows(repo.savedDevices(), state.discovery, state.remoteDeviceId)

    deleteId?.let { id ->
        val name = repo.findByDeviceId(id)?.name ?: id
        val connected = state.connected && state.remoteDeviceId == id
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(if (connected) "取消配对设备" else "删除已配对设备") },
            text = {
                Text(
                    if (connected) "确定要取消与「$name」的配对吗？此操作会同步删除双方的配对记录并断开连接。"
                    else "确定要删除已配对设备「$name」吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = { manager.unpair(id); deleteId = null; refresh++ }) { Text("取消配对") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!state.bluetoothEnabled || !state.listenerEnabled || !state.foregroundEnabled) {
            item {
                StatusCard(state)
            }
        }
        if (state.connected) {
            item {
                ConnectedCard(
                    state = state,
                    onFind = {
                        if (manager.findingRemote) manager.cancelFindRemote()
                        else if (manager.findRemoteDevice()) toast(context, "已让远端设备响铃")
                    },
                    onUnpair = { state.remoteDeviceId.takeIf(String::isNotBlank)?.let { deleteId = it } },
                    onDisconnect = { manualDisconnect = true; manager.disconnect(); refresh++ }
                )
            }
            item {
                TestNotificationCard {
                    sendTestNotification(context, manager, repo)
                }
            }
        } else {
            item {
                Button(
                    onClick = { manager.startDiscovery(autoConnectSaved = !manager.isAutoReconnectPaused()) },
                    enabled = !state.discovery.scanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.discovery.scanning) "扫描中…" else "扫描设备")
                }
            }
            if (state.discovery.scanning) item { Text("正在搜索附近设备…", style = MaterialTheme.typography.bodySmall) }
            val savedRows = rows.filter { it.saved }
            val nearbyRows = rows.filterNot { it.saved }
            if (savedRows.isNotEmpty()) item { Text("已配对设备", style = MaterialTheme.typography.labelLarge) }
            items(savedRows, key = { "saved-${it.id}" }) { row ->
                DeviceRow(row, { connectDevice(context, manager, row) }, { deleteId = row.id })
            }
            if (nearbyRows.isNotEmpty()) item { Text("附近设备", style = MaterialTheme.typography.labelLarge) }
            items(nearbyRows, key = { "nearby-${it.id}-${it.address}" }) { row ->
                DeviceRow(row, { connectDevice(context, manager, row) }, {})
            }
            if (rows.isEmpty()) item {
                Text(
                    if (state.discovery.scanning) "正在搜索附近设备…" else "未发现设备，点上方「扫描设备」",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: DeviceUiState) {
    RelayCard(container = MaterialTheme.colorScheme.surfaceContainer) {
        Text("设备状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        StatusLine("蓝牙", state.bluetoothEnabled)
        StatusLine("通知监听", state.listenerEnabled)
        StatusLine("常驻后台", state.foregroundEnabled)
    }
}

@Composable
private fun StatusLine(label: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(if (enabled) "已开启" else "未开启", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConnectedCard(
    state: DeviceUiState,
    onFind: () -> Unit,
    onUnpair: () -> Unit,
    onDisconnect: () -> Unit
) {
    RelayCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Text("已连接设备", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(state.remoteName.ifBlank { "未知设备" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (state.pairingRequired) "等待配对确认" else listOf(
                state.remoteAndroid.ifBlank { "版本未知" },
                if (state.remoteBattery >= 0) "电量 ${state.remoteBattery}%" else "电量未知"
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        ActionButton(Icons.Outlined.Search, if (state.finding) "取消查找" else "查找设备", onFind)
        ActionButton(Icons.Outlined.DeleteOutline, "取消配对", onUnpair)
        ActionButton(Icons.Outlined.LinkOff, "断开连接", onDisconnect)
    }
}

@Composable
private fun ActionButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun TestNotificationCard(onClick: () -> Unit) {
    RelayCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
        Text("连接测试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("通过当前蓝牙连接向远端发送一条测试通知", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClick) { Text("发送测试通知") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(row: DeviceRowUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = { Text(row.name, fontWeight = FontWeight.Bold) },
        supportingContent = { if (row.subtitle.isNotBlank()) Text(row.subtitle) },
        leadingContent = {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.PhoneAndroid, null) }
        },
        trailingContent = { Text(if (row.connected) "已连接" else "连接", style = MaterialTheme.typography.labelLarge) }
    )
}

private fun buildDeviceRows(saved: List<SavedDevice>, discovery: DiscoveryState, connectedId: String): List<DeviceRowUi> {
    val discoveredById = discovery.devices.filter { it.deviceId.isNotBlank() }.associateBy { it.deviceId }
    val savedIds = saved.map { it.deviceId }.toSet()
    val rows = saved.map { item ->
        val scan = discoveredById[item.deviceId]
        DeviceRowUi(
            item.deviceId,
            scan?.address.orEmpty(),
            item.name.ifBlank { scan?.address.orEmpty() },
            if (item.deviceId == connectedId || scan != null) item.android.ifBlank { scan?.address.orEmpty() }
            else listOf(item.android, "点击自动查找并连接").filter(String::isNotBlank).joinToString(" · "),
            item.deviceId == connectedId,
            true
        )
    }.toMutableList()
    discovery.devices.filter { it.deviceId.isBlank() || it.deviceId !in savedIds }.forEach { scan ->
        rows += DeviceRowUi(scan.deviceId, scan.address, scan.name.ifBlank { scan.address }, scan.address, scan.deviceId == connectedId, false)
    }
    return rows
}

private fun sendTestNotification(context: Context, manager: BleRelayManager, repo: SettingsRepository) {
    if (!manager.visibleConnected()) return toast(context, "请先连接设备")
    if (!manager.paired) return toast(context, "正在完成设备握手，请稍后再试")
    val now = System.currentTimeMillis()
    manager.sendToRemote(JSONObject().apply {
        put("type", "notif")
        put("device", repo.resolvedDeviceName())
        put("pkg", context.packageName)
        put("app", context.getString(R.string.app_name))
        put("title", "测试通知")
        put("text", "通知流转连接正常 · $now")
        put("key", "notif-relay-test-$now")
        put("time", now)
        put("ongoing", false)
    }.toString())
    toast(context, "测试通知已发送")
}

private fun connectDevice(context: Context, manager: BleRelayManager, row: DeviceRowUi) {
    if (manager.visibleConnected() || row.connected) return
    if (row.address.isNotBlank()) {
        toast(context, "正在连接「${row.name}」…")
        manager.connectTo(row.address)
    } else if (row.saved) {
        toast(context, "正在查找「${row.name}」，找到后自动连接…")
        manager.connectToSaved(row.id)
    }
}

data class AppInfo(val pkg: String, val label: String, val icon: Drawable?)

@Composable
private fun AppsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var onlyWhitelist by remember { mutableStateOf(repo.onlyWhitelist) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context.applicationContext) }
        loading = false
    }
    @Suppress("UNUSED_EXPRESSION") version
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, true) || it.pkg.contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        RelayCard(container = MaterialTheme.colorScheme.secondaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("仅转发选中的应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (onlyWhitelist) "当前仅转发下方勾选的应用"
                        else "当前转发全部应用；开启后仅转发下方勾选的应用",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = onlyWhitelist, onCheckedChange = { onlyWhitelist = it; repo.onlyWhitelist = it })
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索应用") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { repo.setWhitelistForAll(apps.map { it.pkg }, true); version++; toast(context, "已全部开启") },
                modifier = Modifier.weight(1f)
            ) { Text("全部开启") }
            OutlinedButton(
                onClick = { repo.setWhitelistForAll(apps.map { it.pkg }, false); version++; toast(context, "已全部关闭") },
                modifier = Modifier.weight(1f)
            ) { Text("全部关闭") }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.pkg }) { app ->
                    AppRow(app, checked = repo.isAppInWhitelist(app.pkg)) {
                        repo.setAppEnabled(app.pkg, it)
                        version++
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val bitmap = remember(app.icon) { app.icon?.toBitmap(48, 48)?.asImageBitmap() }
    ListItem(
        headlineContent = { Text(app.label) },
        supportingContent = { Text(app.pkg, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            if (bitmap != null) androidx.compose.foundation.Image(bitmap, null, Modifier.size(44.dp))
            else Icon(Icons.Outlined.Apps, null, Modifier.size(44.dp))
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked) }
    )
}

private fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
        .mapNotNull { result ->
            val pkg = result.activityInfo?.packageName ?: return@mapNotNull null
            AppInfo(
                pkg,
                try { result.loadLabel(pm).toString() } catch (_: Exception) { pkg },
                try { result.loadIcon(pm) } catch (_: Exception) { null }
            )
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var deviceName by remember { mutableStateOf(repo.resolvedDeviceName()) }
    var foreground by remember { mutableStateOf(repo.foregroundEnabled) }
    var otpLive by remember { mutableStateOf(repo.otpLiveEnabled) }
    val logs = remember { mutableStateListOf<String>() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val logScroll = rememberScrollState()

    LifecycleResumeEffect(Unit) {
        val listener: (String) -> Unit = { line -> handler.post { logs += line } }
        EventLog.observe(listener)
        onPauseOrDispose { EventLog.remove(listener); handler.removeCallbacksAndMessages(null) }
    }
    LaunchedEffect(logs.size) { logScroll.scrollTo(logScroll.maxValue) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RelayCard(container = MaterialTheme.colorScheme.surfaceContainer) {
            Text("设备名称", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("显示在流转通知标题中，默认使用系统设备名", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("设备名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    repo.deviceName = null
                    deviceName = DeviceInfo.systemName(context)
                    toast(context, "已恢复为系统设备名")
                }) { Text("恢复系统名") }
                Button(onClick = {
                    val value = deviceName.trim()
                    if (value.isBlank()) toast(context, "设备名不能为空")
                    else { repo.deviceName = value; deviceName = value; toast(context, "已保存设备名：$value") }
                }) { Text("保存名称") }
            }
        }
        SettingSwitchCard(
            "常驻后台", "保持连接并在状态栏显示远端状态", foreground,
            MaterialTheme.colorScheme.secondaryContainer
        ) {
            foreground = it
            repo.foregroundEnabled = it
            if (it) ForegroundServiceController.start(context) else ForegroundServiceController.stop(context)
            toast(context, if (it) "常驻后台已开启" else "常驻后台已关闭")
        }
        SettingSwitchCard(
            "验证码实时通知", "Android 16+ 使用实时通知显示验证码，其他情况回退为普通通知", otpLive,
            MaterialTheme.colorScheme.tertiaryContainer
        ) {
            otpLive = it
            repo.otpLiveEnabled = it
            toast(context, if (it) "验证码实时通知已开启" else "验证码实时通知已关闭，将使用普通通知")
        }
        Text("诊断日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("调试信息，用于排查连接与流转问题", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Box(
                Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).verticalScroll(logScroll).padding(16.dp)
            ) {
                Text(logs.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingSwitchCard(title: String, description: String, checked: Boolean, container: androidx.compose.ui.graphics.Color, onChange: (Boolean) -> Unit) {
    RelayCard(container = container) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun RelayCard(container: androidx.compose.ui.graphics.Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.fillMaxWidth().padding(20.dp), content = content) }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
