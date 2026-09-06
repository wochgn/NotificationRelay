package com.notifrelay.ui.miuix

import android.bluetooth.BluetoothManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
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
import com.notifrelay.BleRelayManager
import com.notifrelay.DeviceInfo
import com.notifrelay.DiscoveryState
import com.notifrelay.EventLog
import com.notifrelay.ForegroundServiceController
import com.notifrelay.PeerState
import com.notifrelay.R
import com.notifrelay.RelayState
import com.notifrelay.SavedDevice
import com.notifrelay.SettingsRepository
import com.notifrelay.setUiStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

private data class MiuixTab(val key: String, val label: String, val icon: ImageVector)

private val miuixTabs = listOf(
    MiuixTab("devices", "设备", Icons.Outlined.Devices),
    MiuixTab("apps", "应用", Icons.Outlined.Apps),
    MiuixTab("settings", "设置", Icons.Outlined.Settings)
)

/**
 * miuix（HyperOS 风格）界面：与 MD3 版并列，仅 UI 层不同，业务逻辑复用 BleRelayManager。
 */
@Composable
fun MiuixRelayApp(manager: BleRelayManager, widthSizeClass: WindowWidthSizeClass) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller) {
        MiuixAppContent(manager, widthSizeClass)
    }
}

@Composable
private fun MiuixAppContent(manager: BleRelayManager, widthSizeClass: WindowWidthSizeClass) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf("devices") }
    val tab = miuixTabs.firstOrNull { it.key == currentTab } ?: miuixTabs.first()

    BackHandler { (context as? Activity)?.finish() }

    val title = tab.label
    val pages: @Composable () -> Unit = {
        when (currentTab) {
            "devices" -> MiuixDevicesScreen(manager)
            "apps" -> MiuixAppsScreen()
            "settings" -> MiuixSettingsScreen()
        }
    }

    if (widthSizeClass == WindowWidthSizeClass.Compact) {
        Scaffold(topBar = { TopAppBar(title = title) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                pages()
                MiuixLiquidGlassBottomBar(
                    currentTab = currentTab,
                    onSelect = { currentTab = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 40.dp, end = 40.dp, bottom = 20.dp)
                        .fillMaxWidth()
                        .height(64.dp)
                )
            }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                header = { Spacer(Modifier.height(24.dp)) }
            ) {
                miuixTabs.forEach { item ->
                    NavigationRailItem(
                        selected = currentTab == item.key,
                        onClick = { currentTab = item.key },
                        icon = item.icon,
                        label = item.label
                    )
                }
            }
            Scaffold(topBar = { TopAppBar(title = title) }, modifier = Modifier.weight(1f)) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { pages() }
            }
        }
    }
}

// ================= 设备页 =================

private data class MiuixDeviceUi(
    val peers: List<PeerState>,
    val bluetoothEnabled: Boolean,
    val listenerEnabled: Boolean,
    val foregroundEnabled: Boolean,
    val discovery: DiscoveryState
)

@Composable
private fun MiuixDevicesScreen(manager: BleRelayManager) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    var discovery by remember { mutableStateOf(DiscoveryState(false, emptyList())) }
    var refresh by remember { mutableIntStateOf(0) }
    var wasConnected by remember { mutableStateOf(manager.visibleConnected()) }
    var manualDisconnect by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    fun snapshot(): MiuixDeviceUi {
        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val state = manager.currentState()
        return MiuixDeviceUi(
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
            manager.startDiscovery(autoConnectSaved = !manager.isAutoReconnectPaused())
            refresh++
        }
        fun stopObserving() {
            if (!registered) return
            registered = false
            manager.removeState(stateListener)
            manager.removeDiscovery(discoveryListener)
            if (!repo.foregroundEnabled && !manager.hasVisibleConnections()) manager.stopDiscovery()
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
    val savedDevices = repo.savedDevices()
    val savedRows = buildMiuixRows(savedDevices, state.discovery, connectedKeys, false)
    val nearbyRows = buildMiuixRows(savedDevices, state.discovery, connectedKeys, true)

    deleteId?.let { id ->
        val name = repo.findByDeviceId(id)?.name ?: id
        val connected = state.peers.any { it.deviceId == id }
        OverlayDialog(
            show = true,
            title = if (connected) "取消配对设备" else "删除已配对设备",
            onDismissRequest = { deleteId = null }
        ) {
            Column {
                Text(
                    if (connected) "确定要取消与「$name」的配对吗？此操作会同步删除双方的配对记录并断开连接。"
                    else "确定要删除已配对设备「$name」吗？"
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "取消", onClick = { deleteId = null }, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "取消配对",
                        onClick = {
                            manager.unpair(id)
                            deleteId = null
                            refresh++
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // 配对确认（多设备时取第一台待确认）
    val pendingPair = state.peers.firstOrNull { it.needsConfirm }
    if (pendingPair != null) {
        OverlayDialog(
            show = true,
            title = "确认配对设备",
            onDismissRequest = {}
        ) {
            Column {
                Text(
                    "「${pendingPair.name.ifBlank { "附近设备" }}」请求与你建立通知流转连接。\n\n" +
                        "请先核对两台设备上显示的设备名称，确认名称一致且确实是你要连接的设备。"
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "拒绝", onClick = { manager.rejectPairing(pendingPair.deviceId) }, modifier = Modifier.weight(1f))
                    TextButton(text = "确认配对", onClick = { manager.acceptPairing(pendingPair.deviceId) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MiuixCard {
                MiuixScanRow(manager, state)
            }
        }
        if (state.peers.isNotEmpty()) {
            item { SmallTitle(text = "已连接设备（${state.peers.size}）") }
            items(state.peers, key = { "peer-${it.deviceId.ifBlank { it.address }}-${it.address}" }) { peer ->
                MiuixCard {
                    MiuixPeerRow(manager, peer, { deleteId = it }, { manualDisconnect = true; refresh++ })
                }
            }
        }
        if (savedRows.isNotEmpty()) {
            item { SmallTitle(text = "已配对设备") }
            item {
                MiuixCard {
                    savedRows.forEach { row ->
                        MiuixDeviceRow(manager, row, { deleteId = row.id })
                    }
                }
            }
        }
        if (nearbyRows.isNotEmpty()) {
            item { SmallTitle(text = "附近设备") }
            item {
                MiuixCard {
                    nearbyRows.forEach { row ->
                        MiuixDeviceRow(manager, row, {})
                    }
                }
            }
        }
        if (savedRows.isEmpty() && nearbyRows.isEmpty() && state.peers.isEmpty()) {
            item {
                Text(
                    if (state.discovery.scanning) "正在搜索附近设备…" else "未发现设备，点上方「扫描设备」",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * 悬浮液态玻璃底栏：Android 13+ 使用 LiquidGlassView 实时折射后方内容，
 * 低版本回退为半透明胶囊底色。
 */
@Composable
private fun MiuixLiquidGlassBottomBar(
    currentTab: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val localView = LocalView.current
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier = modifier
            .then(
                if (android.os.Build.VERSION.SDK_INT >= 33) Modifier
                else Modifier.background(
                    MiuixTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape
                )
            )
            .clip(shape)
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { ctx ->
                    com.qmdeve.liquidglass.widget.LiquidGlassView(ctx).apply {
                        val metrics = ctx.resources.displayMetrics
                        fun px(dp: Int) = dp * metrics.density
                        setCornerRadius(px(32))
                        setRefractionHeight(px(20))
                        setRefractionOffset(px(70))
                        setBlurRadius(6f)
                        setDispersion(0.3f)
                        setTintAlpha(0.14f)
                        // 绑定窗口内容作为折射采样源
                        post {
                            val content = localView.rootView.findViewById(android.R.id.content) as? ViewGroup
                            if (content != null) bind(content)
                        }
                    }
                }
            )
        }
        Row(
            Modifier.fillMaxSize().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            miuixTabs.forEach { item ->
                val selected = currentTab == item.key
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.surfaceContainerHighest
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceContainer
                    )
                    Text(
                        text = item.label,
                        color = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixScanRow(manager: BleRelayManager, state: MiuixDeviceUi) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "扫描附近设备", fontWeight = FontWeight.Medium)
            Text(text = if (state.discovery.scanning) "扫描中…" else "同时保持本机可被发现")
        }
        top.yukonga.miuix.kmp.basic.Button(
            onClick = { manager.startDiscovery(autoConnectSaved = !manager.isAutoReconnectPaused()) },
            enabled = !state.discovery.scanning
        ) { Text("扫描") }
    }
}

@Composable
private fun MiuixPeerRow(manager: BleRelayManager, peer: PeerState, onUnpair: (String) -> Unit, onDisconnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = peer.name.ifBlank { "未知设备" }, fontWeight = FontWeight.Medium)
        Text(
            text = if (peer.needsConfirm) "等待配对确认" else listOf(
                peer.android.ifBlank { "版本未知" },
                if (peer.battery >= 0) "电量 ${peer.battery}%" else "电量未知"
            ).joinToString(" · ")
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            top.yukonga.miuix.kmp.basic.Button(
                onClick = {
                    if (peer.finding) manager.cancelFindRemote(peer.deviceId)
                    else manager.findRemoteDevice(peer.deviceId)
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (peer.finding) "取消查找" else "查找设备") }
            top.yukonga.miuix.kmp.basic.Button(
                onClick = { peer.deviceId.takeIf(String::isNotBlank)?.let(onUnpair) },
                modifier = Modifier.weight(1f)
            ) { Text("取消配对") }
            top.yukonga.miuix.kmp.basic.Button(
                onClick = { onDisconnect(); manager.disconnect(peer.deviceId) },
                modifier = Modifier.weight(1f)
            ) { Text("断开连接") }
        }
    }
}

private data class MiuixDeviceRowUi(
    val id: String,
    val address: String,
    val name: String,
    val subtitle: String,
    val connected: Boolean
)

@Composable
private fun MiuixDeviceRow(manager: BleRelayManager, row: MiuixDeviceRowUi, onLongClick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                if (row.connected) return@clickable
                if (row.address.isNotBlank()) manager.connectTo(row.address)
                else if (row.id.isNotBlank()) manager.connectToSaved(row.id)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = row.name, fontWeight = FontWeight.Medium)
            if (row.subtitle.isNotBlank()) Text(text = row.subtitle)
        }
        Text(
            text = if (row.connected) "已连接" else "连接",
            modifier = Modifier.padding(end = 16.dp),
            color = MiuixTheme.colorScheme.primary
        )
    }
}

private fun buildMiuixRows(
    saved: List<SavedDevice>,
    discovery: DiscoveryState,
    connectedKeys: Set<String>,
    nearby: Boolean
): List<MiuixDeviceRowUi> {
    val discoveredById = discovery.devices.filter { it.deviceId.isNotBlank() }.associateBy { it.deviceId }
    val savedIds = saved.map { it.deviceId }.toSet()
    return if (!nearby) {
        saved.map { item ->
            val scan = discoveredById[item.deviceId]
            val connected = item.deviceId in connectedKeys
            MiuixDeviceRowUi(
                item.deviceId,
                scan?.address.orEmpty(),
                item.name.ifBlank { scan?.address.orEmpty() },
                if (connected || scan != null) item.android.ifBlank { scan?.address.orEmpty() }
                else listOf(item.android, "点击自动查找并连接").filter(String::isNotBlank).joinToString(" · "),
                connected
            )
        }
    } else {
        discovery.devices.filter {
            (it.deviceId.isBlank() || it.deviceId !in savedIds) &&
                it.address !in connectedKeys && it.deviceId !in connectedKeys
        }.map { scan ->
            MiuixDeviceRowUi(scan.deviceId, scan.address, scan.name.ifBlank { scan.address }, scan.address, false)
        }
    }
}

// ================= 应用页 =================

data class MiuixAppInfo(val pkg: String, val label: String, val icon: Drawable?)

@Volatile private var cachedApps: List<MiuixAppInfo>? = null
private val appIconBitmaps = LruCache<String, ImageBitmap>(256)

@Composable
private fun MiuixAppsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var onlyWhitelist by rememberSaveable { mutableStateOf(repo.onlyWhitelist) }
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf(cachedApps.orEmpty()) }
    var loading by remember { mutableStateOf(cachedApps == null) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (cachedApps == null) {
            cachedApps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
                    .mapNotNull { result ->
                        val pkg = result.activityInfo?.packageName ?: return@mapNotNull null
                        MiuixAppInfo(
                            pkg,
                            try { result.loadLabel(pm).toString() } catch (_: Exception) { pkg },
                            try { result.loadIcon(pm) } catch (_: Exception) { null }
                        )
                    }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            }
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

    Column(Modifier.fillMaxSize()) {
        MiuixCard(modifier = Modifier.padding(horizontal = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = "仅转发选中的应用", fontWeight = FontWeight.Medium)
                    Text(
                        text = if (onlyWhitelist) "当前仅转发下方勾选的应用"
                        else "当前转发全部应用；开启后仅转发下方勾选的应用"
                    )
                }
                Switch(checked = onlyWhitelist, onCheckedChange = { onlyWhitelist = it; repo.onlyWhitelist = it })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            top.yukonga.miuix.kmp.basic.Button(
                onClick = { repo.setWhitelistForAll(apps.map { it.pkg }, true); version++; toast(context, "已全部开启") },
                modifier = Modifier.weight(1f)
            ) { Text("全部开启") }
            top.yukonga.miuix.kmp.basic.Button(
                onClick = { repo.setWhitelistForAll(apps.map { it.pkg }, false); version++; toast(context, "已全部关闭") },
                modifier = Modifier.weight(1f)
            ) { Text("全部关闭") }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "搜索应用", Modifier.weight(1f))
                    }
                }
                items(filtered, key = { it.pkg }) { app ->
                    val checked = repo.isAppInWhitelist(app.pkg)
                    MiuixCard(modifier = Modifier.padding(vertical = 3.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bitmap = remember(app.pkg) {
                                appIconBitmaps.get(app.pkg)
                                    ?: app.icon?.toBitmap(48, 48)?.asImageBitmap()?.also { appIconBitmaps.put(app.pkg, it) }
                            }
                            if (bitmap != null) Image(bitmap, null, Modifier.size(40.dp)) else Spacer(Modifier.width(40.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(text = app.label)
                                Text(text = app.pkg)
                            }
                            Switch(
                                checked = checked,
                                onCheckedChange = { repo.setAppEnabled(app.pkg, it); version++ }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ================= 设置页 =================

@Composable
private fun MiuixSettingsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var deviceName by remember { mutableStateOf(repo.resolvedDeviceName()) }
    var foreground by remember { mutableStateOf(repo.foregroundEnabled) }
    var otpLive by remember { mutableStateOf(repo.otpLiveEnabled) }
    var relayOngoing by remember { mutableStateOf(repo.relayOngoingEnabled) }
    var dedupeRepeat by remember { mutableStateOf(repo.dedupeRepeatEnabled) }
    var refreshAsNew by remember { mutableStateOf(repo.refreshAsNewEnabled) }
    var verboseLog by remember { mutableStateOf(repo.verboseLogEnabled) }
    var uiStyle by remember { mutableStateOf(repo.uiStyle) }
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

    val manager = remember { BleRelayManager.get(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SmallTitle(text = "设备名称")
        MiuixCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                TextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = "设备名称",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = {
                            repo.deviceName = null
                            deviceName = DeviceInfo.systemName(context)
                            toast(context, "已恢复为系统设备名")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("恢复默认") }
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = {
                            val value = deviceName.trim()
                            if (value.isBlank()) toast(context, "设备名不能为空")
                            else {
                                repo.deviceName = value
                                deviceName = value
                                toast(context, "已保存设备名：$value")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存名称") }
                }
            }
        }

        SmallTitle(text = "后台管理")
        MiuixCard {
            MiuixSwitchPref(
                "常驻后台", "保持连接并在状态栏显示各设备状态", foreground
            ) {
                foreground = it
                repo.foregroundEnabled = it
                if (it) ForegroundServiceController.start(context) else ForegroundServiceController.stop(context)
                toast(context, if (it) "常驻后台已开启" else "常驻后台已关闭")
            }
        }

        SmallTitle(text = "通知增强")
        MiuixCard {
            MiuixSwitchPref(
                "验证码实时通知", "Android 16+ 使用实时通知显示验证码，其他情况回退为普通通知", otpLive
            ) {
                otpLive = it
                repo.otpLiveEnabled = it
                toast(context, if (it) "验证码实时通知已开启" else "验证码实时通知已关闭，将使用普通通知")
            }
            MiuixSwitchPref(
                "流转常驻通知", "关闭后不再转发常驻/不可清除类通知（如音乐播放、下载进度）", relayOngoing
            ) {
                relayOngoing = it
                repo.relayOngoingEnabled = it
                toast(context, if (it) "已流转常驻通知" else "已停止流转常驻通知")
            }
            MiuixSwitchPref(
                "优化流转重复通知", "接收端 1 秒内收到同一应用重复内容的通知时仅弹出第一条，超过 1 秒正常弹出", dedupeRepeat
            ) {
                dedupeRepeat = it
                repo.dedupeRepeatEnabled = it
                toast(context, if (it) "重复通知优化已开启" else "重复通知优化已关闭")
            }
            MiuixSwitchPref(
                "通知提示增强", "开启后，当通知内容刷新时，软件会将其视为一条新通知进行转发", refreshAsNew
            ) {
                refreshAsNew = it
                repo.refreshAsNewEnabled = it
                toast(context, if (it) "内容刷新将弹出新通知" else "内容刷新将原地更新通知")
            }
        }

        SmallTitle(text = "主题与颜色", modifier = Modifier.padding(top = 6.dp))
        MiuixCard {
            top.yukonga.miuix.kmp.preference.SwitchPreference(
                title = "MIUIX 风格",
                summary = "关闭后恢复 Material 3 风格（MD3）",
                checked = uiStyle == "miuix",
                onCheckedChange = {
                    uiStyle = if (it) "miuix" else "md3"
                    repo.uiStyle = uiStyle
                    setUiStyle(uiStyle)
                    toast(context, if (it) "已切换到 MIUIX 风格" else "已切换到 MD3 风格")
                }
            )
        }
        SmallTitle(text = "调试与日志")
        MiuixCard {
            MiuixSwitchPref(
                "启用日志显示", "关闭后仅记录一般日志（错误、警告等），不记录连接、扫描等全部详细事件", verboseLog
            ) {
                verboseLog = it
                repo.verboseLogEnabled = it
                EventLog.verboseEnabled = it
                toast(context, if (it) "详细日志已开启" else "仅显示一般日志")
            }
        }
        MiuixCard {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = "连接测试", fontWeight = FontWeight.Medium)
                Text(text = "向所有已连接设备发送一条测试通知")
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    top.yukonga.miuix.kmp.basic.Button(onClick = { sendMiuixTestNotification(context, manager, repo) }) {
                        Text("发送测试通知")
                    }
                }
            }
        }
        MiuixCard {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().height(240.dp)
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .verticalScroll(logScroll)
                        .padding(12.dp)
                ) {
                    Text(
                        text = logs.joinToString("\n"),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MiuixSwitchPref(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onChange
    )
}

@Composable
private fun MiuixCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        content = content
    )
}

private fun sendMiuixTestNotification(context: Context, manager: BleRelayManager, repo: SettingsRepository) {
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

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
