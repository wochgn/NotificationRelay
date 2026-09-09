package com.notifrelay.ui

import android.bluetooth.BluetoothManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notifrelay.BleRelayManager
import com.notifrelay.DeviceInfo
import com.notifrelay.DiscoveryState
import com.notifrelay.EventLog
import com.notifrelay.ForegroundServiceController
import com.notifrelay.PeerState
import com.notifrelay.R
import com.notifrelay.RecentsController
import com.notifrelay.RelayState
import com.notifrelay.SavedDevice
import com.notifrelay.SettingsRepository
import com.notifrelay.setUiStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class Destination(val route: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val destinations = listOf(
    Destination("devices", "设备", Icons.Outlined.Devices, Icons.Filled.Devices),
    Destination("apps", "应用", Icons.Outlined.Apps, Icons.Filled.Apps),
    Destination("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
)

private fun routeIndex(route: String?): Int =
    destinations.indexOfFirst { it.route == route }.coerceAtLeast(0)

// M3 Expressive 减速曲线，用于底栏/侧栏点击后的 Pager 定位动画。
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayMainContent(
    manager: BleRelayManager,
    widthSizeClass: WindowWidthSizeClass,
    currentTab: String,
    onTabChange: (String) -> Unit,
    settingsScrollState: ScrollState
) {
    val context = LocalContext.current
    val route = currentTab
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

    // 每次状态刷新都重新取会话快照，多台设备时取第一台等待确认的
    @Suppress("UNUSED_EXPRESSION") pairingVersion
    val relayState = manager.currentState()
    val pendingPair = relayState.peers.firstOrNull { it.needsConfirm }
    if (pendingPair != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("确认配对设备") },
            text = {
                Text(
                    "「${pendingPair.name.ifBlank { "附近设备" }}」请求与你建立通知流转连接。\n\n" +
                        "请先核对两台设备上显示的设备名称，确认名称一致且确实是你要连接的设备。"
                )
            },
            confirmButton = {
                TextButton(onClick = { manager.acceptPairing(pendingPair.deviceId) }) { Text("确认配对") }
            },
            dismissButton = {
                TextButton(onClick = { manager.rejectPairing(pendingPair.deviceId) }) { Text("拒绝") }
            }
        )
    }

    fun navigateTo(item: Destination) {
        onTabChange(item.route)
    }

    val currentTabIndex = routeIndex(currentTab)
    val pagerState = rememberPagerState(
        initialPage = currentTabIndex,
        pageCount = { destinations.size }
    )
    LaunchedEffect(currentTabIndex) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != currentTabIndex) {
            pagerState.animateScrollToPage(
                page = currentTabIndex,
                animationSpec = tween(340, easing = EmphasizedDecelerate)
            )
        }
    }
    val onTabChangeUpdated by rememberUpdatedState(onTabChange)
    LaunchedEffect(pagerState) {
        snapshotFlow {
            if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.settledPage
        }.collect { page ->
            onTabChangeUpdated(destinations[page].route)
        }
    }

    // 标题：加粗、较默认增大（44sp 缩小 20% → 35sp），顶栏高度同步调整
    // 标题左缘与选项卡片左缘对齐（20dp 页边距；M3 默认 title 左距 16dp，补 4dp）
    val topBar: @Composable () -> Unit = {
        TopAppBar(
            title = {
                Text(
                    destination.title,
                    fontSize = 35.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            },
            expandedHeight = TopAppBarDefaults.TopAppBarExpandedHeight * 1.6f,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }
    val pagerPages: @Composable (PaddingValues) -> Unit = { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            beyondViewportPageCount = 2,
            userScrollEnabled = pendingPair == null,
            key = { index -> destinations[index].route }
        ) { page ->
            when (destinations[page].route) {
                "devices" -> DevicesScreen(manager, widthSizeClass != WindowWidthSizeClass.Compact)
                "apps" -> AppsScreen()
                "settings" -> SettingsScreen(settingsScrollState)
            }
        }
    }

    if (widthSizeClass == WindowWidthSizeClass.Compact) {
        // 手机竖屏：底部导航栏
        Scaffold(
            topBar = topBar,
            bottomBar = {
                NavigationBar {
                    destinations.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = { navigateTo(item) },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }
        ) { padding -> pagerPages(padding) }
    } else {
        // 平板/横屏/大屏：KernelSU 风格可展开侧边栏，内容区占满剩余空间
        Row(Modifier.fillMaxSize()) {
            AppNavigationRail(currentRoute = route) { navigateTo(it) }
            Scaffold(topBar = topBar, modifier = Modifier.weight(1f)) { padding -> pagerPages(padding) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNavigationRail(currentRoute: String, onSelect: (Destination) -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val railState = rememberWideNavigationRailState(
        initialValue = if (repo.navigationRailExpanded) {
            WideNavigationRailValue.Expanded
        } else {
            WideNavigationRailValue.Collapsed
        }
    )
    val scope = rememberCoroutineScope()
    val expanded = railState.targetValue == WideNavigationRailValue.Expanded
    LaunchedEffect(railState.targetValue) {
        repo.navigationRailExpanded = expanded
    }
    WideNavigationRail(
        modifier = Modifier.fillMaxHeight(),
        state = railState,
        colors = WideNavigationRailDefaults.colors().copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Start + WindowInsetsSides.Vertical
        ),
        header = {
            IconButton(modifier = Modifier.padding(start = 24.dp), onClick = {
                scope.launch {
                    if (expanded) railState.collapse() else railState.expand()
                }
            }) {
                Icon(
                    if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                    contentDescription = if (expanded) "收起侧边栏" else "展开侧边栏"
                )
            }
        }
    ) {
        destinations.forEach { item ->
            WideNavigationRailItem(
                railExpanded = expanded,
                selected = currentRoute == item.route,
                onClick = {
                    if (currentRoute != item.route) onSelect(item)
                },
                icon = {
                    Icon(
                        if (currentRoute == item.route) item.selectedIcon else item.icon,
                        contentDescription = item.title
                    )
                },
                label = { Text(item.title) }
            )
        }
    }
}

private data class DeviceUiState(
    val peers: List<PeerState>,
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
private fun DevicesScreen(manager: BleRelayManager, wideLayout: Boolean) {
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
        val state = manager.currentState()
        return DeviceUiState(
            state.peers,
            bluetooth.adapter?.isEnabled == true,
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            repo.foregroundEnabled,
            discovery
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
                                !manager.isConnecting()
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
            // 幂等启动：同时保证本机可被发现（多台设备都能连进来）与扫描发现其他设备
            manager.startDiscovery(autoConnectSaved = !manager.isAutoReconnectPaused())
            refresh++
        }
        fun stopObserving() {
            if (!registered) return
            registered = false
            manager.removeState(stateListener)
            manager.removeDiscovery(discoveryListener)
            // 离开设备页即停止扫描（保留广播，其他设备仍能发现并连入）
            manager.stopDeviceScan()
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
    val connectedKeys = state.peers
        .flatMap { listOf(it.deviceId, it.address) }
        .filter(String::isNotBlank)
        .toSet()
    val rows = buildDeviceRows(repo.savedDevices(), state.discovery, connectedKeys)

    deleteId?.let { id ->
        val name = repo.findByDeviceId(id)?.name ?: id
        val connected = state.peers.any { it.deviceId == id }
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

    val savedRows = rows.filter { it.saved }
    val nearbyRows = rows.filterNot { it.saved }
    val hasStatusNotice = !state.bluetoothEnabled || !state.listenerEnabled || !state.foregroundEnabled
    val density = LocalDensity.current
    var pairedRowsHeightPx by remember { mutableStateOf(0) }

    if (wideLayout) {
        // 大屏：已连接设备与已配对设备左右两栏展示
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasStatusNotice) StatusCard(state)
            ScanButton(state, manager)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "已连接设备（${state.peers.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    state.peers.forEach { peer ->
                        // 连接卡高度与右栏已配对卡片区块实测高度一致（含间隙）
                        val matchHeight = if (pairedRowsHeightPx > 0) {
                            Modifier.height(with(density) { pairedRowsHeightPx.toDp() })
                        } else {
                            Modifier
                        }
                        ConnectedCard(
                            peer = peer,
                            onFind = {
                                if (peer.finding) manager.cancelFindRemote(peer.deviceId)
                                else if (manager.findRemoteDevice(peer.deviceId)) toast(context, "已让远端设备响铃")
                            },
                            onUnpair = { peer.deviceId.takeIf(String::isNotBlank)?.let { deleteId = it } },
                            onDisconnect = {
                                manualDisconnect = true
                                manager.disconnect(peer.deviceId)
                                refresh++
                            },
                            modifier = matchHeight
                        )
                    }
                    if (state.peers.isEmpty()) {
                        Text(
                            "暂无已连接设备",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "已配对设备",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Column(
                        Modifier.onSizeChanged { pairedRowsHeightPx = it.height },
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        savedRows.forEach { row ->
                            DeviceRow(row, { connectDevice(context, manager, row) }, { deleteId = row.id })
                        }
                        if (nearbyRows.isNotEmpty()) {
                            Text("附近设备", style = MaterialTheme.typography.labelLarge)
                            nearbyRows.forEach { row ->
                                DeviceRow(row, { connectDevice(context, manager, row) }, {})
                            }
                        }
                    }
                    if (rows.isEmpty() && state.peers.isEmpty()) {
                        Text(
                            if (state.discovery.scanning) "正在搜索附近设备…" else "未发现设备，点上方「扫描设备」",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (hasStatusNotice) {
            item {
                StatusCard(state)
            }
        }
        item {
            ScanButton(state, manager)
        }
        if (state.peers.isNotEmpty()) {
            item {
                Text(
                    "已连接设备（${state.peers.size}）",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            items(state.peers, key = { "peer-${it.deviceId.ifBlank { it.address }}-${it.address}" }) { peer ->
                ConnectedCard(
                    peer = peer,
                    onFind = {
                        if (peer.finding) manager.cancelFindRemote(peer.deviceId)
                        else if (manager.findRemoteDevice(peer.deviceId)) toast(context, "已让远端设备响铃")
                    },
                    onUnpair = { peer.deviceId.takeIf(String::isNotBlank)?.let { deleteId = it } },
                    onDisconnect = {
                        manualDisconnect = true
                        manager.disconnect(peer.deviceId)
                        refresh++
                    }
                )
            }
        }
        if (savedRows.isNotEmpty()) {
            item {
                Text(
                    "已配对设备",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
        items(savedRows, key = { "saved-${it.id}" }) { row ->
            DeviceRow(row, { connectDevice(context, manager, row) }, { deleteId = row.id })
        }
        if (nearbyRows.isNotEmpty()) {
            item {
                Text(
                    "附近设备",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
        items(nearbyRows, key = { "nearby-${it.id}-${it.address}" }) { row ->
            DeviceRow(row, { connectDevice(context, manager, row) }, {})
        }
        if (rows.isEmpty() && state.peers.isEmpty()) item {
            Text(
                if (state.discovery.scanning) "正在搜索附近设备…" else "未发现设备，点上方「扫描设备」",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ScanButton(state: DeviceUiState, manager: BleRelayManager) {
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

// 设备状态提示卡：低饱和度红底；深色模式用暗红避免刺眼
@Composable
private fun StatusCard(state: DeviceUiState) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF4E2C2C) else Color(0xFFF2C2C2)
    // 标题与“已开启”用纯色：浅色主题纯黑、深色主题纯白
    val titleColor = if (dark) Color.White else Color.Black
    val labelColor = if (dark) Color(0xFFD8C2C2) else Color(0xFF5A5A5A)
    val okColor = if (dark) Color.White else Color.Black
    // 深色模式下的“未开启”用饱和度更高的红
    val offColor = if (dark) Color(0xFFFF5252) else Color(0xFFB3261E)
    RelayCard(container = bg) {
        Text("设备状态", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = titleColor)
        Spacer(Modifier.height(12.dp))
        StatusLine("蓝牙", state.bluetoothEnabled, labelColor, okColor, offColor)
        StatusLine("通知监听", state.listenerEnabled, labelColor, okColor, offColor)
        StatusLine("常驻后台", state.foregroundEnabled, labelColor, okColor, offColor)
    }
}

@Composable
private fun StatusLine(label: String, enabled: Boolean, labelColor: Color, okColor: Color, offColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = labelColor)
        Text(
            if (enabled) "已开启" else "未开启",
            fontWeight = FontWeight.Bold,
            color = if (enabled) okColor else offColor
        )
    }
}

@Composable
private fun ConnectedCard(
    peer: PeerState,
    onFind: () -> Unit,
    onUnpair: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    RelayCard(container = MaterialTheme.colorScheme.primaryContainer, modifier = modifier) {
        Spacer(Modifier.height(8.dp))
        Text(peer.name.ifBlank { "未知设备" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (peer.needsConfirm) "等待配对确认" else listOf(
                peer.android.ifBlank { "版本未知" },
                if (peer.battery >= 0) "电量 ${peer.battery}%" else "电量未知"
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactActionButton(Icons.Outlined.Search, if (peer.finding) "取消查找" else "查找设备", Modifier.weight(1f), onFind)
            CompactActionButton(Icons.Outlined.DeleteOutline, "取消配对", Modifier.weight(1f), onUnpair)
            CompactActionButton(Icons.Outlined.LinkOff, "断开连接", Modifier.weight(1f), onDisconnect)
        }
    }
}

@Composable
private fun CompactActionButton(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(row: DeviceRowUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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

private fun buildDeviceRows(saved: List<SavedDevice>, discovery: DiscoveryState, connectedKeys: Set<String>): List<DeviceRowUi> {
    val discoveredById = discovery.devices.filter { it.deviceId.isNotBlank() }.associateBy { it.deviceId }
    val savedIds = saved.map { it.deviceId }.toSet()
    val rows = saved.map { item ->
        val scan = discoveredById[item.deviceId]
        val connected = item.deviceId in connectedKeys
        DeviceRowUi(
            item.deviceId,
            scan?.address.orEmpty(),
            item.name.ifBlank { scan?.address.orEmpty() },
            if (connected || scan != null) item.android.ifBlank { scan?.address.orEmpty() }
            else listOf(item.android, "点击自动查找并连接").filter(String::isNotBlank).joinToString(" · "),
            connected,
            true
        )
    }.toMutableList()
    discovery.devices.filter {
        (it.deviceId.isBlank() || it.deviceId !in savedIds) &&
            it.address !in connectedKeys && it.deviceId !in connectedKeys
    }.forEach { scan ->
        rows += DeviceRowUi(scan.deviceId, scan.address, scan.name.ifBlank { scan.address }, scan.address, false, false)
    }
    return rows
}

private fun sendTestNotification(context: Context, manager: BleRelayManager, repo: SettingsRepository) {
    if (!manager.visibleConnected()) return toast(context, "请先连接设备")
    if (!manager.hasPairedPeer()) return toast(context, "正在完成设备握手，请稍后再试")
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
    if (row.connected) return
    if (row.address.isNotBlank()) {
        toast(context, "正在连接「${row.name}」…")
        manager.connectTo(row.address)
    } else if (row.saved) {
        toast(context, "正在查找「${row.name}」，找到后自动连接…")
        manager.connectToSaved(row.id)
    }
}

data class AppInfo(val pkg: String, val label: String, val icon: Drawable?)

// 应用列表与图标位图跨 tab 缓存：避免每次切页重新查询全部应用、
// 重新解码图标（切页动画期间逐行解码图标是掉帧的主因）。
@Volatile private var cachedApps: List<AppInfo>? = null
private val appIconBitmaps = LruCache<String, ImageBitmap>(256)

@Composable
private fun AppsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var onlyWhitelist by rememberSaveable { mutableStateOf(repo.onlyWhitelist) }
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf(cachedApps.orEmpty()) }
    var loading by remember { mutableStateOf(cachedApps == null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (cachedApps == null) {
            cachedApps = withContext(Dispatchers.IO) { loadApps(context.applicationContext) }
        }
        apps = cachedApps.orEmpty()
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
            shape = RoundedCornerShape(24.dp),
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
    val bitmap = remember(app.pkg) {
        appIconBitmaps.get(app.pkg)
            ?: app.icon?.toBitmap(48, 48)?.asImageBitmap()?.also { appIconBitmaps.put(app.pkg, it) }
    }
    ListItem(
        headlineContent = { Text(app.label) },
        supportingContent = { Text(app.pkg, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            if (bitmap != null) Image(bitmap, null, Modifier.size(44.dp))
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
private fun SettingsScreen(scrollState: ScrollState) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var deviceName by remember { mutableStateOf(repo.resolvedDeviceName()) }
    var foreground by remember { mutableStateOf(repo.foregroundEnabled) }
    var otpLive by remember { mutableStateOf(repo.otpLiveEnabled) }
    val logs = remember { mutableStateListOf<String>() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val logScroll = rememberScrollState()

    LifecycleResumeEffect(Unit) {
        EventLog.verboseEnabled = repo.verboseLogEnabled
        val listener: (String) -> Unit = { line -> handler.post { logs += line } }
        EventLog.observe(listener)
        onPauseOrDispose { EventLog.remove(listener); handler.removeCallbacksAndMessages(null) }
    }
    LaunchedEffect(logs.size) { logScroll.scrollTo(logScroll.maxValue) }

    val focusManager = LocalFocusManager.current
    var nameFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var settingsRootPos by remember { mutableStateOf(Offset.Zero) }
    var showResetNameDialog by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { settingsRootPos = it.positionInWindow() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val bounds = nameFieldBounds
                    if (bounds == null || (settingsRootPos + down.position) !in bounds) {
                        focusManager.clearFocus()
                    }
                }
            }
            .verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp),
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
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .onGloballyPositioned { nameFieldBounds = it.boundsInWindow() }
                    .onFocusChanged { state ->
                        if (!state.isFocused) deviceName = repo.resolvedDeviceName()
                    }
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showResetNameDialog = true }) { Text("恢复默认") }
                Button(onClick = {
                    val value = deviceName.trim()
                    if (value.isBlank()) toast(context, "设备名不能为空")
                    else { repo.deviceName = value; deviceName = value; toast(context, "已保存设备名：$value") }
                }) { Text("保存名称") }
            }
        }
        if (showResetNameDialog) {
            AlertDialog(
                onDismissRequest = { showResetNameDialog = false },
                title = { Text("恢复默认设备名") },
                text = { Text("确定要恢复为系统设备名吗？当前自定义名称将被清除。") },
                confirmButton = {
                    TextButton(onClick = {
                        showResetNameDialog = false
                        repo.deviceName = null
                        deviceName = DeviceInfo.systemName(context)
                        toast(context, "已恢复为系统设备名")
                    }) { Text("恢复默认") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetNameDialog = false }) { Text("取消") }
                }
            )
        }
        SectionLabel("后台管理")
        SettingSwitchCard(
            "常驻后台", "保持连接并在状态栏显示各设备状态", foreground
        ) {
            foreground = it
            repo.foregroundEnabled = it
            if (it) ForegroundServiceController.start(context) else ForegroundServiceController.stop(context)
            toast(context, if (it) "常驻后台已开启" else "常驻后台已关闭")
        }
        var hideRecents by remember { mutableStateOf(repo.hideFromRecents) }
        SettingSwitchCard(
            "隐藏后台卡片", "开启后不在最近任务中显示本应用，桌面图标与后台通知流转不受影响", hideRecents
        ) {
            hideRecents = it
            repo.hideFromRecents = it
            RecentsController.apply(context, it)
        }
        SectionLabel("通知增强")
        SettingSwitchCard(
            "验证码实时通知", "Android 16+ 使用实时通知显示验证码，其他情况回退为普通通知", otpLive
        ) {
            otpLive = it
            repo.otpLiveEnabled = it
            toast(context, if (it) "验证码实时通知已开启" else "验证码实时通知已关闭，将使用普通通知")
        }
        var relayOngoing by remember { mutableStateOf(repo.relayOngoingEnabled) }
        SettingSwitchCard(
            "流转常驻通知", "关闭后不再转发常驻/不可清除类通知（如音乐播放、下载进度）", relayOngoing
        ) {
            relayOngoing = it
            repo.relayOngoingEnabled = it
            toast(context, if (it) "已流转常驻通知" else "已停止流转常驻通知")
        }
        var dedupeRepeat by remember { mutableStateOf(repo.dedupeRepeatEnabled) }
        SettingSwitchCard(
            "优化流转重复通知", "接收端 1 秒内收到同一应用重复内容的通知时仅弹出第一条，超过 1 秒正常弹出", dedupeRepeat
        ) {
            dedupeRepeat = it
            repo.dedupeRepeatEnabled = it
            toast(context, if (it) "重复通知优化已开启" else "重复通知优化已关闭")
        }
        var refreshAsNew by remember { mutableStateOf(repo.refreshAsNewEnabled) }
        SettingSwitchCard(
            "通知提示增强", "开启后，当通知内容刷新时，软件会将其视为一条新通知进行转发", refreshAsNew
        ) {
            refreshAsNew = it
            repo.refreshAsNewEnabled = it
            toast(context, if (it) "内容刷新将弹出新通知" else "内容刷新将原地更新通知")
        }
        var syncRemove by remember { mutableStateOf(repo.syncRemoveEnabled) }
        SettingSwitchCard(
            "同步通知清除状态", "开启后，原机通知被清除时，已流转到本机的通知同步移除", syncRemove
        ) {
            syncRemove = it
            repo.syncRemoveEnabled = it
            toast(context, if (it) "清除状态将同步到本机" else "流转通知将保留，需手动清除")
        }
        // 主题与颜色：MD3 / MIUIX 风格切换
        SectionLabel("主题与颜色")
        var uiStyle by remember { mutableStateOf(repo.uiStyle) }
        SettingSwitchCard(
            "MIUIX 风格", "开启后使用 HyperOS（miuix）设计风格，关闭恢复 Material 3 风格", uiStyle == "miuix"
        ) {
            uiStyle = if (it) "miuix" else "md3"
            repo.uiStyle = uiStyle
            setUiStyle(uiStyle)
            toast(context, if (it) "已切换到 MIUIX 风格" else "已切换到 MD3 风格")
        }
        SectionLabel("调试与日志")
        var verboseLog by remember { mutableStateOf(repo.verboseLogEnabled) }
        SettingSwitchCard(
            "启用日志显示", "关闭后仅记录一般日志（错误、警告等），不记录连接、扫描等全部详细事件", verboseLog
        ) {
            verboseLog = it
            repo.verboseLogEnabled = it
            EventLog.verboseEnabled = it
            toast(context, if (it) "详细日志已开启" else "仅显示一般日志")
        }
        val relayManager = remember { BleRelayManager.get(context) }
        RelayCard(container = MaterialTheme.colorScheme.surfaceContainer) {
            Text("连接测试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("向所有已连接设备发送一条测试通知", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { sendTestNotification(context, relayManager, repo) }) { Text("发送测试通知") }
            }
        }
        SelectionContainer {
            Box(
                Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(logScroll).padding(16.dp)
            ) {
                Text(logs.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 设置页类别小标题：与设备页「已配对设备」等标题同字号、同左对齐方式。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 20.dp)
    )
}

@Composable
private fun SettingSwitchCard(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    RelayCard(container = MaterialTheme.colorScheme.surfaceContainer) {
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
private fun RelayCard(
    container: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) { Column(Modifier.fillMaxWidth().padding(20.dp), content = content) }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
