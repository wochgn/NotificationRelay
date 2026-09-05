package com.notifrelay

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 单个远端设备的会话快照，供 UI 与常驻通知观察。
 */
data class PeerState(
    val deviceId: String,
    val address: String,
    val name: String,
    val android: String,
    val battery: Int,
    val paired: Boolean,
    val needsConfirm: Boolean,
    val finding: Boolean
)

/**
 * 全局连接状态快照：peers 为当前所有已连接设备（支持多台）。
 */
data class RelayState(
    val connected: Boolean = false,
    val peers: List<PeerState> = emptyList(),
    val pairingRequired: Boolean = false
)

/** 扫描发现到的设备（去重后的一行）。deviceId 为稳定身份，address 仅用于当前连接。 */
data class ScanDevice(val deviceId: String, val address: String, val name: String, val rssi: Int)

/** 发现状态快照（扫描开关 + 已发现设备列表）。 */
data class DiscoveryState(val scanning: Boolean, val devices: List<ScanDevice>)

private data class RemoteNotificationData(
    val id: Int,
    val device: String,
    val senderId: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val otpCode: String?
)

/**
 * BLE 双向传输管理（多设备版）。
 *
 * 本机同时扮演 GATT server（可被多台中心连接）与 GATT client（可同时连接多台外设），
 * 每条物理链路对应一个 [Session]，互不干扰：
 *  - 中心(GATT client) write CHAR_FROM_CENTRAL  -> 外设(GATT server) 收到
 *  - 外设 notify CHAR_FROM_PERIPHERAL           -> 中心 收到
 *
 * 应用层消息为 JSON，分多类（由 "type" 字段区分）：
 *  - notif  通知（携带 device/app/title/text/ongoing）
 *  - hello  连接建立时握手（携带 device + battery）
 *  - status 心跳（携带 battery）
 *  - pair_accept / pair_reject / disconnect / unpair / find_device 等控制消息
 *
 * 内置分片协议，帧格式（每片 5 字节头 + payload）：
 *   [0]      type = 0x01（数据片）
 *   [1..2]   totalLen 大端 16bit
 *   [3..4]   offset   大端 16bit
 *   [5..]    payload
 */
@SuppressLint("MissingPermission")
class BleRelayManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: BleRelayManager? = null
        fun get(context: Context): BleRelayManager =
            instance ?: synchronized(this) {
                instance ?: BleRelayManager(context.applicationContext).also { instance = it }
            }
        private const val HEARTBEAT_MS = 60_000L
        // 外设 notify 无确认，靠发送间隔做流控，避免连发导致丢片
        private const val PERIPHERAL_NOTIFY_DELAY_MS = 25L
        private const val DISCONNECT_TIMEOUT_MS = 750L
        private const val CENTRAL_CONNECT_TIMEOUT_MS = 12_000L
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L
        // 单次扫描时长，超时自动停扫（不停广播）
        private const val SCAN_TIMEOUT_MS = 15_000L
        // MTU 协商目标：默认 23 时每片仅 15 字节，一条通知要几十次往返；
        // 协商成功后每片可达 239 字节，显著降低流转延迟。协商失败自动回退 23。
        private const val MTU_REQUEST = 247
    }

    enum class Role { NONE, AUTO, CENTRAL, PERIPHERAL }

    /**
     * 一条 BLE 链路的全部状态：发送/接收队列、握手、配对、远端信息。
     * CENTRAL：我方为 GATT client；PERIPHERAL：我方为 GATT server。
     */
    private inner class Session(val address: String, val role: Role) {
        // ---- 中心侧 ----
        var gatt: BluetoothGatt? = null
        var charFromCentral: BluetoothGattCharacteristic? = null
        var charFromPeripheral: BluetoothGattCharacteristic? = null
        val sendQueue = ArrayDeque<ByteArray>()
        var writing = false
        var disconnectAfterCentralWrite = false

        // ---- 外设侧 ----
        var remoteDevice: BluetoothDevice? = null
        val peripheralQueue = ArrayDeque<ByteArray>()
        var peripheralSending = false
        var disconnectAfterPeripheralSend = false

        // ---- 链路公共 ----
        @Volatile var connected = false
        @Volatile var applicationReady = false
        @Volatile var disconnecting = false
        @Volatile var mtu = 23
        val recvBuffer = ByteArrayOutputStream()
        var recvTotalLen = -1

        // ---- 远端信息与配对 ----
        @Volatile var remoteName = ""
        @Volatile var remoteBattery = -1
        @Volatile var remoteAndroid = ""
        @Volatile var remoteDeviceId = ""
        @Volatile var pairingRequired = false
        @Volatile var paired = false
        @Volatile var localPairAccepted = false
        @Volatile var remotePairAccepted = false
        @Volatile var findingRemote = false
        // 对端声明的 MTU：服务端 onMtuChanged 在部分机型会虚报链路 MTU，
        // 外设方向的分片长度必须取 min(本机 MTU, 对端 MTU) 防止 notify 超长
        @Volatile var peerMtu = 23
        val sentIconPackages = HashSet<String>()

        fun visibleReady(): Boolean = connected && applicationReady && !disconnecting

        val centralConnectTimeoutRunnable = Runnable {
            if (!connected) failCentralConnection(this, "中心：连接超时")
        }
        val handshakeTimeoutRunnable = Runnable {
            if (connected && !applicationReady) {
                logGeneral("应用层握手超时，断开后重试")
                if (role == Role.CENTRAL) failCentralConnection(this, "中心：应用层握手超时")
                else closeSession(this, "外设：应用层握手超时")
            }
        }
        val helloRetryRunnable = Runnable {
            if (connected && !disconnecting) sendHello(this)
        }
        val disconnectTimeoutRunnable = Runnable {
            if (disconnecting) closeSession(this, "已断开连接")
        }
    }

    private val appContext = context.applicationContext
    private val btManager: BluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager.adapter
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    autoReconnectPaused = false
                    stopAll()
                    log("蓝牙已关闭，已清理连接状态")
                }
                BluetoothAdapter.STATE_ON -> {
                    if (SettingsRepository.get(appContext).savedDevices().isNotEmpty()) {
                        handler.postDelayed({
                            if (btAdapter?.isEnabled == true) startDiscovery()
                        }, 1_000L)
                    }
                }
            }
        }
    }

    @Volatile private var autoReconnectPaused = false
    @Volatile private var autoConnectSaved = true
    @Volatile private var pendingConnectDeviceId: String? = null
    @Volatile private var userDisconnectEvent = false
    @Volatile private var shuttingDown = false
    // 主界面是否在前台。后台时降低扫描频率、且不为「添加新设备」持续扫描。
    @Volatile private var uiVisible = false

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    fun isUiVisible(): Boolean = uiVisible

    // 会话表：key = 远端 MAC 地址（同一地址同一时刻只允许一条链路/一个会话）
    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    // 正在作为中心发起连接的地址，防止重复发起
    private val centralPending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 状态观察者（UI / 常驻通知）
    private val stateListeners = CopyOnWriteArrayList<(RelayState) -> Unit>()

    // 发现观察者（设备列表 UI）
    private val discoveryListeners = CopyOnWriteArrayList<(DiscoveryState) -> Unit>()
    // 扫描结果去重累积（deviceId 优先，退回 address）
    private val discoveredDevices = LinkedHashMap<String, ScanDevice>()

    // 心跳：周期向所有已连接设备上报电量
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            sessionsSnapshot().forEach { if (it.visibleReady()) sendStatus(it) }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // ---- 服务端（可服务多台中心） ----
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advertisingRequested = false
    @Volatile private var serviceReady = false
    private var charToCentral: BluetoothGattCharacteristic? = null

    // ---- 扫描 ----
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    @Volatile private var scanning = false

    private var notifId = 1000
    private val remoteAppIcons = LruCache<String, Bitmap>(64)
    // 接收端重复通知优化：1 秒窗口内同设备同 key 同内容的通知仅弹出第一条
    private val receivedFingerprints = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    // 通知内容代数：同 key 通知内容刷新后视为一条新通知，代数递增使 id 变化、再次弹出
    private val notifGenerations = HashMap<String, Pair<Int, Int>>()
    private val remoteNotifications = object : LinkedHashMap<Int, RemoteNotificationData>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RemoteNotificationData>?): Boolean = size > 64
    }

    init {
        appContext.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun log(msg: String) = EventLog.add(msg)

    // 一般日志（错误/警告类），不受「启用日志显示」开关影响，始终记录
    private fun logGeneral(msg: String) = EventLog.addGeneral(msg)

    // ================= 会话工具 =================

    private fun sessionsSnapshot(): List<Session> =
        synchronized(sessionLock) { sessions.values.toList() }

    private fun sessionByAddress(address: String): Session? =
        synchronized(sessionLock) { sessions[address] }

    private fun sessionByDeviceId(deviceId: String?): Session? {
        if (deviceId.isNullOrBlank()) return null
        return synchronized(sessionLock) { sessions.values.firstOrNull { it.remoteDeviceId == deviceId } }
    }

    private fun sessionByGatt(gatt: BluetoothGatt): Session? =
        synchronized(sessionLock) { sessions.values.firstOrNull { it.gatt === gatt } }

    private fun hasSessionFor(address: String, deviceId: String): Boolean =
        synchronized(sessionLock) {
            sessions.containsKey(address) ||
                (deviceId.isNotBlank() && sessions.values.any { it.remoteDeviceId == deviceId })
        }

    // ================= 状态观察 =================

    fun observeState(listener: (RelayState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeState(listener: (RelayState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun currentState(): RelayState {
        val snapshot = sessionsSnapshot()
        val peers = snapshot.map { s ->
            PeerState(
                s.remoteDeviceId, s.address, s.remoteName, s.remoteAndroid, s.remoteBattery,
                s.paired, s.pairingRequired && !s.localPairAccepted, s.findingRemote
            )
        }
        return RelayState(
            connected = snapshot.any { it.visibleReady() },
            peers = peers,
            pairingRequired = peers.any { it.needsConfirm }
        )
    }

    private fun notifyState() {
        val s = currentState()
        stateListeners.forEach { it(s) }
    }

    fun visibleConnected(): Boolean = currentState().connected

    fun hasVisibleConnections(): Boolean = visibleConnected()

    fun hasPairedPeer(): Boolean = sessionsSnapshot().any { it.visibleReady() && it.paired }

    fun isFindingRemote(): Boolean = sessionsSnapshot().any { it.findingRemote }

    fun hasMissingSavedPeer(): Boolean {
        val known = sessionsSnapshot().map { it.remoteDeviceId }.toSet()
        return SettingsRepository.get(appContext).savedDevices().any { it.deviceId !in known }
    }

    fun needsLocalPairConfirmation(): Boolean = currentState().pairingRequired

    fun pendingPairPeerName(): String =
        currentState().peers.firstOrNull { it.needsConfirm }?.name.orEmpty()

    fun isAutoReconnectPaused(): Boolean = autoReconnectPaused

    fun isConnecting(): Boolean = centralPending.isNotEmpty()

    fun isDiscoveryScanning(): Boolean = scanning

    fun isDiscoveryActive(): Boolean = scanning || gattServer != null || advertiser != null

    fun consumeUserDisconnectEvent(): Boolean {
        val occurred = userDisconnectEvent
        userDisconnectEvent = false
        return occurred
    }

    fun observeDiscovery(listener: (DiscoveryState) -> Unit) {
        discoveryListeners.add(listener)
    }

    fun removeDiscovery(listener: (DiscoveryState) -> Unit) {
        discoveryListeners.remove(listener)
    }

    // 扫描结果每次回调都触发会引发 UI 高频重组（切换页签动画期间尤其掉帧），
    // 这里把通知合并到 250ms 一次再发给观察者。
    private val discoveryNotifyRunnable = Runnable {
        val list = synchronized(discoveredDevices) { discoveredDevices.values.toList() }
        val s = DiscoveryState(scanning, list)
        discoveryListeners.forEach { it(s) }
    }

    private fun notifyDiscovery() {
        handler.removeCallbacks(discoveryNotifyRunnable)
        handler.postDelayed(discoveryNotifyRunnable, 250L)
    }

    // ================= 对外接口 =================

    /**
     * 发现模式：同时广播（可被多台设备发现/连接）+ 扫描（发现多台设备）。
     * 幂等：已建立会话不受影响，可随时调用以补充扫描/广播。
     */
    fun startDiscovery(autoConnectSaved: Boolean = true) {
        shuttingDown = false
        this.autoConnectSaved = autoConnectSaved && !autoReconnectPaused
        if (!hasBlePermissions()) {
            logGeneral("蓝牙权限未授予，无法发现设备")
            return
        }
        val adapter = btAdapter ?: run { log("无蓝牙适配器"); return }
        if (!adapter.isEnabled) { log("请先手动打开蓝牙"); return }
        ensureAdvertising()
        ensureScanning()
    }

    private fun ensureAdvertising() {
        advertisingRequested = true
        if (gattServer == null) {
            serviceReady = false
            val server = try { btManager.openGattServer(appContext, gattServerCallback) } catch (_: Exception) { null }
            gattServer = server
            if (server == null || !server.addService(buildService())) {
                logGeneral("添加 GATT 服务失败")
            } else {
                // GATT 服务注册是异步的。必须等 onServiceAdded 成功后再开始广播，
                // 否则另一端可能先连上却发现不到目标服务。
                log("GATT 服务注册中")
            }
        } else if (serviceReady && advertiser == null) {
            startAdvertisingBeacon()
        }
    }

    private fun startAdvertisingBeacon() {
        if (!advertisingRequested || advertiser != null) return
        val adapter = btAdapter ?: return

        advertiser = adapter.bluetoothLeAdvertiser
        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        val scanResponseBuilder = AdvertiseData.Builder()
        val nameBytes = (adapter.name ?: "").toByteArray(Charsets.UTF_8)
        if (nameBytes.size <= 17) scanResponseBuilder.setIncludeDeviceName(true)
        scanResponseBuilder.addManufacturerData(
            Constants.MANUFACTURER_ID,
            hexToBytes(SettingsRepository.get(appContext).deviceId())
        )
        advertiser?.startAdvertising(advSettings, advData, scanResponseBuilder.build(), advertiseCallback)
    }

    private fun hasBlePermissions(): Boolean = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    ).all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 只停广播与扫描，不影响已建立的连接。 */
    fun stopDiscovery() {
        stopAdvertising()
        stopScanning()
        notifyDiscovery()
    }

    /** 只停扫描、保留广播：离开界面时停止搜索新设备，但本机仍可被其他设备发现。 */
    fun stopDeviceScan() {
        stopScanning()
        notifyDiscovery()
    }

    /** 点按设备：我方作中心主动连接对方（可与已有连接并存）。 */
    fun connectTo(address: String) {
        val adapter = btAdapter ?: run { log("无蓝牙适配器"); return }
        if (address.isBlank()) return
        if (sessionByAddress(address) != null) {
            logGeneral("该设备已在连接中，忽略重复连接")
            return
        }
        autoReconnectPaused = false
        val device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) { logGeneral("无效设备地址 $address"); return }
        log("连接 $address …")
        connectAsCentral(device)
    }

    fun connectToSaved(deviceId: String) {
        if (sessionByDeviceId(deviceId) != null) return
        autoReconnectPaused = false
        val discovered = synchronized(discoveredDevices) {
            discoveredDevices.values.firstOrNull { it.deviceId == deviceId }
        }
        if (discovered != null) {
            connectTo(discovered.address)
            return
        }
        startDiscovery()
        pendingConnectDeviceId = deviceId
        log("正在查找已配对设备…")
    }

    fun acceptPairing(deviceId: String) {
        val session = sessionByDeviceId(deviceId) ?: return
        if (!session.connected || !session.pairingRequired) return
        session.localPairAccepted = true
        sendToSession(session, infoJson(session).put("type", "pair_accept").toString())
        completePairingIfReady(session)
        notifyState()
        log("本机已确认配对，等待对方确认")
    }

    fun rejectPairing(deviceId: String) {
        val session = sessionByDeviceId(deviceId) ?: return
        if (!session.connected || !session.pairingRequired) return
        logGeneral("本机已拒绝配对")
        sendControlAndDisconnect(session, "pair_reject")
    }

    /** 断开指定设备；deviceId 为空时断开全部。 */
    fun disconnect(deviceId: String? = null) {
        val targets = sessionsSnapshot().filter { deviceId == null || it.remoteDeviceId == deviceId || it.address == deviceId }
        if (targets.isEmpty()) return
        logGeneral("正在同步断开连接")
        targets.forEach {
            autoReconnectPaused = true
            sendControlAndDisconnect(it, "disconnect")
        }
    }

    fun unpair(deviceId: String) {
        SettingsRepository.get(appContext).removeDevice(deviceId)
        val targets = sessionsSnapshot().filter { it.remoteDeviceId == deviceId }
        targets.forEach {
            log("正在同步取消配对")
            autoReconnectPaused = true
            sendControlAndDisconnect(it, "unpair")
        }
    }

    /** 让远端响铃；deviceId 为空时选择第一台已连接设备。 */
    fun findRemoteDevice(deviceId: String? = null): Boolean {
        val session = sessionByDeviceId(deviceId)
            ?: sessionsSnapshot().firstOrNull { it.visibleReady() && it.paired }
            ?: return false
        if (!session.visibleReady() || !session.paired || session.findingRemote) return false
        sendToSession(session, JSONObject().put("type", "find_device").toString())
        session.findingRemote = true
        notifyState()
        log("已向「${session.remoteName.ifBlank { session.address }}」发送查找设备请求")
        return true
    }

    fun cancelFindRemote(deviceId: String? = null): Boolean {
        val session = sessionByDeviceId(deviceId)
            ?: sessionsSnapshot().firstOrNull { it.findingRemote }
            ?: return false
        if (!session.visibleReady() || !session.findingRemote) return false
        sendToSession(session, JSONObject().put("type", "find_stop").toString())
        session.findingRemote = false
        notifyState()
        log("已取消查找设备")
        return true
    }

    private fun sendControlAndDisconnect(session: Session, type: String) {
        userDisconnectEvent = true
        session.disconnecting = true
        notifyState()
        handler.removeCallbacks(session.disconnectTimeoutRunnable)
        handler.postDelayed(session.disconnectTimeoutRunnable, DISCONNECT_TIMEOUT_MS)
        // 控制消息保持最小，默认 MTU 下只需两片，发送完成后再关闭物理链路。
        val chunks = chunk(session, JSONObject().put("type", type).toString().toByteArray(Charsets.UTF_8))
        when (session.role) {
            Role.CENTRAL -> synchronized(session) {
                session.sendQueue.clear()
                session.sendQueue.addAll(chunks)
                session.disconnectAfterCentralWrite = true
                if (!session.writing) writeNext(session)
            }
            Role.PERIPHERAL -> enqueuePeripheral(session, chunks, disconnectAfter = true)
            Role.NONE, Role.AUTO -> closeSession(session, "本地链路已清理")
        }
    }

    private fun maybeResumeDiscovery() {
        if (shuttingDown || autoReconnectPaused) return
        startDiscovery()
    }

    private fun closeSession(session: Session, reason: String) {
        val removed = synchronized(sessionLock) { sessions.remove(session.address) != null }
        handler.removeCallbacks(session.centralConnectTimeoutRunnable)
        handler.removeCallbacks(session.handshakeTimeoutRunnable)
        handler.removeCallbacks(session.helloRetryRunnable)
        handler.removeCallbacks(session.disconnectTimeoutRunnable)
        centralPending.remove(session.address)
        val gatt = session.gatt
        session.gatt = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        if (session.role == Role.PERIPHERAL) {
            try { session.remoteDevice?.let { gattServer?.cancelConnection(it) } } catch (_: Exception) {}
        }
        session.connected = false
        session.disconnecting = false
        if (removed) {
            val label = session.remoteName.ifBlank { session.address }
            log("$reason（$label）")
            notifyState()
        }
        maybeResumeDiscovery()
    }

    private fun failCentralConnection(session: Session, reason: String) {
        if (synchronized(sessionLock) { sessions[session.address] } !== session) return
        closeSession(session, reason)
    }

    /** 停止一切：关闭所有会话、广播与扫描。 */
    fun stopAll() {
        shuttingDown = true
        sessionsSnapshot().forEach { closeSession(it, "已停止") }
        stopAdvertising()
        stopScanning()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        charToCentral = null
        synchronized(discoveredDevices) { discoveredDevices.clear() }
        pendingConnectDeviceId = null
        centralPending.clear()
        notifyDiscovery()
        notifyState()
        log("已停止")
    }

    private fun stopAdvertising() {
        advertisingRequested = false
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertiser = null
    }

    private fun stopScanning() {
        if (scanning) {
            scanning = false
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        }
        handler.removeCallbacks(scanTimeoutRunnable)
    }

    private fun ensureScanning() {
        if (scanning) return
        val adapter = btAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        this.scanner = scanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        scanning = true
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            handler.removeCallbacks(scanTimeoutRunnable)
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
            notifyDiscovery()
            log("开始扫描附近设备…")
        } catch (_: Exception) {
            scanning = false
        }
    }

    // 扫描超时：SCAN_TIMEOUT_MS 后自动停扫（不停广播；常驻后台下会周期性重新扫描）
    private val scanTimeoutRunnable = object : Runnable {
        override fun run() {
            if (scanning) {
                logGeneral("扫描超时，已停止扫描")
                stopScanning()
                notifyDiscovery()
            }
        }
    }

    // ================= 发送 =================

    /** 向所有已就绪的连接广播（通知监听服务等多目标场景使用）。 */
    fun sendToRemote(json: String) {
        sessionsSnapshot().forEach { session ->
            if (session.visibleReady()) sendToSession(session, json)
        }
    }

    private fun sendToSession(session: Session, json: String) {
        if (session.disconnecting) return
        val chunks = chunk(session, json.toByteArray(Charsets.UTF_8))
        when (session.role) {
            Role.CENTRAL -> {
                val gatt = session.gatt
                val char = session.charFromCentral
                if (!session.connected || gatt == null || char == null) {
                    logGeneral("中心未连接，丢弃消息")
                    return
                }
                synchronized(session) {
                    session.sendQueue.addAll(chunks)
                    if (!session.writing) writeNext(session)
                }
            }
            Role.PERIPHERAL -> {
                val dev = session.remoteDevice
                val server = gattServer
                if (!session.connected || dev == null || charToCentral == null || server == null) {
                    logGeneral("外设无中心连接，丢弃消息")
                    return
                }
                enqueuePeripheral(session, chunks)
            }
            Role.NONE, Role.AUTO -> logGeneral("未连接，丢弃消息")
        }
    }

    fun sendAppIconIfNeeded(packageName: String) {
        if (packageName.isBlank()) return
        val targets = sessionsSnapshot().filter { session ->
            session.visibleReady() && session.paired && synchronized(session.sentIconPackages) {
                session.sentIconPackages.add(packageName)
            }
        }
        if (targets.isEmpty()) return
        Thread {
            val encoded = AppIconCodec.encode(appContext, packageName)
            handler.post {
                if (encoded == null) {
                    targets.forEach { session ->
                        synchronized(session.sentIconPackages) { session.sentIconPackages.remove(packageName) }
                    }
                    return@post
                }
                val json = JSONObject()
                    .put("type", "app_icon")
                    .put("pkg", packageName)
                    .put("data", encoded)
                    .toString()
                targets.forEach { session ->
                    if (session.visibleReady() && session.paired) sendToSession(session, json)
                    else synchronized(session.sentIconPackages) { session.sentIconPackages.remove(packageName) }
                }
            }
        }.start()
    }

    /**
     * 外设→中心发送：notify 是「无确认」的，连发会溢出 GATT server 缓冲导致丢片，
     * 中心侧永远凑不齐完整 JSON。这里像中心写队列一样串行化，并给每片留出发送间隔。
     */
    private fun enqueuePeripheral(session: Session, chunks: List<ByteArray>, disconnectAfter: Boolean = false) {
        synchronized(session) {
            session.peripheralQueue.addAll(chunks)
            if (disconnectAfter) session.disconnectAfterPeripheralSend = true
            if (!session.peripheralSending) {
                session.peripheralSending = true
                handler.post { peripheralSendNext(session) }
            }
        }
    }

    private fun peripheralSendNext(session: Session) {
        val dev = session.remoteDevice
        val char = charToCentral
        val server = gattServer
        if (!session.connected || dev == null || char == null || server == null) {
            synchronized(session) {
                session.peripheralQueue.clear()
                session.peripheralSending = false
            }
            return
        }
        var chunk: ByteArray? = null
        var shouldDisconnect = false
        synchronized(session) {
            if (session.peripheralQueue.isEmpty()) {
                session.peripheralSending = false
                if (session.disconnectAfterPeripheralSend) {
                    session.disconnectAfterPeripheralSend = false
                    shouldDisconnect = true
                }
            } else {
                chunk = session.peripheralQueue.removeFirst()
            }
        }
        if (shouldDisconnect) {
            closeSession(session, "已断开连接")
            return
        }
        val data = chunk ?: return
        char.value = data
        // 用 3 参数重载（返回 boolean，API 18+ 兼容）。
        // 4 参数重载 API 33 才有、返回 int 状态码，在 Android 12 上会 NoSuchMethodError。
        val ok = try {
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(dev, char, false)
        } catch (e: IllegalArgumentException) {
            // 部分机型链路 MTU 与回调值不对称导致 notify 超长：回退保守 MTU 并清空重发队列
            logGeneral("外设 notify 数据超长(${data.size}B)，回退保守 MTU")
            session.mtu = 23
            session.peerMtu = 23
            false
        }
        if (ok) {
            handler.postDelayed({ peripheralSendNext(session) }, PERIPHERAL_NOTIFY_DELAY_MS)
        } else {
            synchronized(session) {
                session.peripheralQueue.clear()
                session.peripheralSending = false
            }
            logGeneral("外设 notify 发送失败，清空待发队列")
        }
    }

    private fun writeNext(session: Session) {
        synchronized(session) {
            val gatt = session.gatt
            val char = session.charFromCentral
            if (gatt == null || char == null || !session.connected) {
                session.sendQueue.clear(); session.writing = false; return
            }
            if (session.sendQueue.isEmpty()) {
                session.writing = false
                if (session.disconnectAfterCentralWrite) {
                    session.disconnectAfterCentralWrite = false
                    closeSession(session, "已断开连接")
                }
                return
            }
            session.writing = true
            val chunk = session.sendQueue.removeFirst()
            char.value = chunk
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!gatt.writeCharacteristic(char)) {
                logGeneral("写特征值失败")
                session.writing = false
            }
        }
    }

    // ================= 应用层消息 =================

    private fun sendHello(session: Session) {
        sendToSession(session, infoJson(session).put("type", "hello").toString())
    }

    private fun sendHelloWithRetry(session: Session) {
        handler.removeCallbacks(session.helloRetryRunnable)
        sendHello(session)
        handler.postDelayed(session.helloRetryRunnable, 500L)
        handler.postDelayed(session.helloRetryRunnable, 1_500L)
    }

    private fun sendStatus(session: Session) {
        sendToSession(session, infoJson(session).put("type", "status").toString())
    }

    private fun infoJson(session: Session): JSONObject = JSONObject()
        .put("device", SettingsRepository.get(appContext).resolvedDeviceName())
        .put("id", SettingsRepository.get(appContext).deviceId())
        .put("battery", DeviceInfo.batteryPercent(appContext))
        .put("android", DeviceInfo.androidVersion())
        .put("mtu", session.mtu)

    // ================= 服务构建 =================

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(Constants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val charIn = BluetoothGattCharacteristic(
            Constants.CHAR_FROM_CENTRAL,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val charOut = BluetoothGattCharacteristic(
            Constants.CHAR_FROM_PERIPHERAL,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // 关键：Android 的 GATT server 不会自动为 notify 特征值添加 CCCD 描述符，
        // 必须手动添加，否则客户端 getDescriptor(CCCD) 返回 null，订阅永远卡住。
        charOut.addDescriptor(
            BluetoothGattDescriptor(
                Constants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(charIn)
        service.addCharacteristic(charOut)
        charToCentral = charOut
        return service
    }

    // ================= 广播/外设侧回调 =================

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("广播启动成功")
        }
        override fun onStartFailure(errorCode: Int) {
            val msg = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "数据过大(超过31字节)"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广播实例过多"
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已在广播"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "内部错误"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "该机型不支持外设广播"
                else -> "未知错误"
            }
            logGeneral("广播启动失败：$msg (code=$errorCode)")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != Constants.SERVICE_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS && gattServer != null) {
                serviceReady = true
                log("GATT 服务注册成功，开始广播")
                startAdvertisingBeacon()
            } else {
                logGeneral("GATT 服务注册失败 status=$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address ?: return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (gattServer == null) return
                if (!serviceReady) {
                    logGeneral("外设：服务尚未就绪，拒绝过早连接")
                    try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
                    return
                }
                var created: Session? = null
                synchronized(sessionLock) {
                    val existing = sessions[address]
                    if (existing != null) {
                        if (existing.role == Role.CENTRAL) {
                            logGeneral("外设：忽略 server 侧重复连接（我方已是中心）")
                        } else {
                            logGeneral("外设：该设备已有连接，忽略新连接")
                        }
                        return
                    }
                    val session = Session(address, Role.PERIPHERAL)
                    session.remoteDevice = device
                    session.connected = true
                    sessions[address] = session
                    created = session
                }
                val session = created ?: return
                handler.removeCallbacks(session.handshakeTimeoutRunnable)
                handler.postDelayed(session.handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)
                notifyState()
                log("外设：中心已连接 ${device.name ?: address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val session = sessionByAddress(address) ?: return
                if (session.role != Role.PERIPHERAL) return
                // 非主动断开时允许后续自动重连
                if (!session.disconnecting) autoReconnectPaused = false
                closeSession(session, "外设：中心已断开 status=$status")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            sessionByAddress(device.address ?: return)?.mtu = mtu
            log("外设：MTU=$mtu")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == Constants.CHAR_FROM_CENTRAL) {
                sessionByAddress(device.address ?: return)?.let { onChunkReceived(it, value) }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // 客户端写 CCCD 订阅 notify 时会走到这里，应答即可
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            // 此时该中心已订阅 notify，可靠地把本机设备名+电量推给对方
            sessionByAddress(device.address ?: return)?.let { sendHelloWithRetry(it) }
        }
    }

    // ================= 扫描/中心侧回调 =================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val device = result.device
            val address = device.address ?: return
            val record = result.scanRecord
            val name = device.name ?: record?.deviceName.orEmpty()
            val deviceId = record
                ?.getManufacturerSpecificData(Constants.MANUFACTURER_ID)
                ?.let { bytesToHex(it) }
                .orEmpty()
            val savedDevices = SettingsRepository.get(appContext).savedDevices()
            val savedByName = savedDevices.firstOrNull { it.name == name }
            val pendingSaved = pendingConnectDeviceId?.let { id ->
                savedDevices.firstOrNull { it.deviceId == id }
            }
            // 部分系统只返回带服务 UUID 的主广播，不返回 scan response 中的厂商数据。
            // 只有用户明确点击的设备，或名称明确匹配已配对设备时，才允许补齐身份；
            // 不能把附近任意 BLE 设备当成唯一已配对设备。
            val resolvedDeviceId = deviceId
                .ifBlank { savedByName?.deviceId.orEmpty() }
                .ifBlank { pendingSaved?.takeIf { name.isNotBlank() && name == it.name }?.deviceId.orEmpty() }
                // 扫描已经按本应用 Service UUID 过滤；只有一条已配对记录时，
                // 即使 ROM 隐藏了厂商数据，也可以安全确认这是目标设备。
                .ifBlank { if (savedDevices.size == 1 && !hasVisibleConnections()) savedDevices[0].deviceId else "" }
            val resolvedName = name.ifBlank {
                savedDevices.firstOrNull { it.deviceId == resolvedDeviceId }?.name.orEmpty()
            }
            // 用稳定 deviceId 去重（缺失时退回 address）
            val key = if (resolvedDeviceId.isNotBlank()) resolvedDeviceId else address
            synchronized(discoveredDevices) { discoveredDevices[key] = ScanDevice(resolvedDeviceId, address, resolvedName, result.rssi) }
            notifyDiscovery()

            val sessionExists = hasSessionFor(address, resolvedDeviceId)
            if (!sessionExists && resolvedDeviceId == pendingConnectDeviceId && !centralPending.contains(address)) {
                pendingConnectDeviceId = null
                log("已找到配对设备，正在连接 $resolvedName")
                connectAsCentral(device)
                return
            }

            // 已有连接/正在连接的设备不再重复发起。首次发现的新设备必须由用户确认，
            // 已保存设备按规则自动连接，从而支持同时连接多台设备。
            if (sessionExists || resolvedDeviceId.isBlank()) return
            if (centralPending.contains(address)) return
            if (!autoConnectSaved) return
            if (SettingsRepository.get(appContext).findByDeviceId(resolvedDeviceId) == null) return

            // 自动协商中心/外设：deviceId 字典序较小的一方作中心主动连接，另一方继续广播等待。
            // 规则两侧一致 → 每对设备恰好一方连、一方等，多台设备两两成立。
            val myId = SettingsRepository.get(appContext).deviceId()
            if (resolvedDeviceId < myId) {
                log("自动协商：对方（$resolvedName）作中心，我继续广播等待")
                return
            }
            log("自动协商：我作中心，连接 $resolvedName")
            connectAsCentral(device)
        }
        override fun onScanFailed(errorCode: Int) {
            logGeneral("扫描失败 code=$errorCode")
        }
    }

    private fun connectAsCentral(device: BluetoothDevice) {
        val address = device.address ?: return
        if (!centralPending.add(address)) return
        if (sessionByAddress(address) != null) {
            centralPending.remove(address)
            return
        }
        // autoConnect=false：直接连接。true 会把连接请求挂起等待，是「卡在连接中」的常见原因。
        val session = Session(address, Role.CENTRAL)
        synchronized(sessionLock) { sessions[address] = session }
        notifyState()
        val gatt = device.connectGatt(appContext, false, gattCallback)
        session.gatt = gatt
        handler.postDelayed(session.centralConnectTimeoutRunnable, CENTRAL_CONNECT_TIMEOUT_MS)
        if (gatt == null) {
            failCentralConnection(session, "中心：发起连接失败")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val session = sessionByGatt(gatt)
            if (session == null) {
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTING) {
                log("中心：连接中")
            } else if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(session.centralConnectTimeoutRunnable)
                centralPending.remove(session.address)
                session.connected = true
                session.applicationReady = false
                session.mtu = 23
                handler.removeCallbacks(session.handshakeTimeoutRunnable)
                handler.postDelayed(session.handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)
                notifyState()
                log("中心：已连接，开始发现服务")
                // GATT 操作必须串行：这里只做服务发现，不要并发 requestMtu
                refreshGattCache(gatt)
                handler.postDelayed({
                    if (sessionByGatt(gatt) !== session || !session.connected) return@postDelayed
                    if (!gatt.discoverServices()) {
                        failCentralConnection(session, "发起服务发现失败")
                    }
                }, 300L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(session.centralConnectTimeoutRunnable)
                centralPending.remove(session.address)
                if (!session.disconnecting) autoReconnectPaused = false
                closeSession(session, "中心：连接断开 status=$status")
            } else {
                handler.removeCallbacks(session.centralConnectTimeoutRunnable)
                failCentralConnection(session, "中心：连接失败 status=$status state=$newState")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val session = sessionByGatt(gatt) ?: return
            // 主动不协商 MTU，保持默认 23：不同机型 MTU 不对称会导致大包被对端拒收
            session.mtu = mtu
            log("中心：MTU=$mtu")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val session = sessionByGatt(gatt) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failCentralConnection(session, "发现服务失败 status=$status")
                return
            }
            val service = gatt.getService(Constants.SERVICE_UUID)
            if (service == null) {
                failCentralConnection(session, "未找到目标服务")
                return
            }
            session.charFromCentral = service.getCharacteristic(Constants.CHAR_FROM_CENTRAL)
            session.charFromPeripheral = service.getCharacteristic(Constants.CHAR_FROM_PERIPHERAL)
            if (session.charFromCentral == null || session.charFromPeripheral == null) {
                failCentralConnection(session, "中心：特征值不完整")
                return
            }
            subscribeToNotifications(session)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val session = sessionByGatt(gatt) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failCentralConnection(session, "中心：订阅失败($status)")
                return
            }
            log("中心：订阅成功")
            // 订阅成功后把本机设备名/版本/电量推给对方
            sendHelloWithRetry(session)
            // 订阅完成后串行发起 MTU 协商，成功后 onMtuChanged 会更新 session.mtu
            try {
                if (!gatt.requestMtu(MTU_REQUEST)) {
                    logGeneral("中心：发起 MTU 协商失败，保持默认 MTU")
                }
            } catch (_: Exception) {
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val session = sessionByGatt(gatt) ?: return
            if (characteristic.uuid == Constants.CHAR_FROM_CENTRAL) {
                writeNext(session)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val session = sessionByGatt(gatt) ?: return
            if (characteristic.uuid == Constants.CHAR_FROM_PERIPHERAL) {
                characteristic.value?.let { onChunkReceived(session, it) }
            }
        }
    }

    private fun subscribeToNotifications(session: Session) {
        val gatt = session.gatt ?: return
        val charRecv = session.charFromPeripheral ?: run { log("中心：接收特征值未就绪"); return }
        gatt.setCharacteristicNotification(charRecv, true)
        val cccd = charRecv.getDescriptor(Constants.CCCD_UUID)
        if (cccd == null) {
            log("中心：CCCD 为 null（外设未添加 CCCD 描述符）")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = gatt.writeDescriptor(cccd)
        log("中心：发起订阅（writeDescriptor=$ok）")
        if (!ok) failCentralConnection(session, "中心：发起订阅失败")
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val refreshed = gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean
            log("中心：刷新 GATT 缓存 ${if (refreshed == true) "成功" else "未执行"}")
        } catch (_: Exception) {
            log("中心：刷新 GATT 缓存不可用")
        }
    }

    // ================= 分片协议 =================

    private fun chunk(session: Session, bytes: ByteArray): List<ByteArray> {
        val total = bytes.size
        // 外设方向：部分机型 server 端 onMtuChanged 会虚报链路 MTU，与对端声明取小者
        val effectiveMtu = if (session.role == Role.PERIPHERAL) {
            minOf(session.mtu, session.peerMtu)
        } else {
            session.mtu
        }
        val maxPayload = ((effectiveMtu - 3 - 5).coerceAtLeast(15))
        val result = ArrayList<ByteArray>()
        var offset = 0
        while (offset < total) {
            val len = minOf(maxPayload, total - offset)
            val frame = ByteArray(5 + len)
            frame[0] = 0x01
            frame[1] = ((total shr 8) and 0xFF).toByte()
            frame[2] = (total and 0xFF).toByte()
            frame[3] = ((offset shr 8) and 0xFF).toByte()
            frame[4] = (offset and 0xFF).toByte()
            System.arraycopy(bytes, offset, frame, 5, len)
            result.add(frame)
            offset += len
        }
        return result
    }

    private fun onChunkReceived(session: Session, data: ByteArray) {
        if (data.size < 5 || data[0] != 0x01.toByte()) return
        val total = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val offset = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val payload = data.copyOfRange(5, data.size)

        if (offset == 0) {
            session.recvBuffer.reset()
            session.recvTotalLen = total
        }
        if (session.recvTotalLen != total) return

        session.recvBuffer.write(payload)
        if (session.recvBuffer.size() >= total) {
            val json = String(session.recvBuffer.toByteArray(), Charsets.UTF_8)
            session.recvBuffer.reset()
            session.recvTotalLen = -1
            handleReceivedMessage(session, json)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef".toCharArray()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }

    // ================= 消息分发 =================

    private fun handleReceivedMessage(session: Session, json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type", "notif")) {
                "hello" -> {
                    session.remoteName = obj.optString("device", "").ifBlank { session.remoteName }
                    session.remoteDeviceId = obj.optString("id", "").ifBlank { session.remoteDeviceId }
                    session.remoteBattery = obj.optInt("battery", -1)
                    session.remoteAndroid = obj.optString("android", "").ifBlank { session.remoteAndroid }
                    obj.optInt("mtu", 23).takeIf { it >= 23 }?.let { session.peerMtu = it }
                    // 同一设备出现两条链路（双连竞态）会产生重复会话：保留旧链路，关闭新链路
                    val duplicate = session.remoteDeviceId.takeIf(String::isNotBlank)?.let { id ->
                        synchronized(sessionLock) {
                            sessions.values.firstOrNull { it !== session && it.remoteDeviceId == id }
                        }
                    }
                    if (duplicate != null) {
                        logGeneral("检测到「${session.remoteName.ifBlank { session.address }}」重复链路，关闭新链路")
                        closeSession(session, "重复链路已关闭")
                        return
                    }
                    val saved = SettingsRepository.get(appContext).findByDeviceId(session.remoteDeviceId)
                    session.paired = saved != null
                    session.pairingRequired = !session.paired
                    session.localPairAccepted = session.paired
                    session.remotePairAccepted = session.paired
                    session.applicationReady = session.remoteDeviceId.isNotBlank()
                    if (session.applicationReady) handler.removeCallbacks(session.handshakeTimeoutRunnable)
                    notifyState()
                    log("握手：远端 ${session.remoteName.ifBlank { "未知" }} ${session.remoteAndroid} 电量 ${session.remoteBattery}%")
                    if (session.paired) {
                        sendToSession(session, infoJson(session).put("type", "pair_accept").toString())
                    }
                }
                "status" -> {
                    session.remoteName = obj.optString("device", "").ifBlank { session.remoteName }
                    session.remoteDeviceId = obj.optString("id", "").ifBlank { session.remoteDeviceId }
                    session.remoteBattery = obj.optInt("battery", -1)
                    session.remoteAndroid = obj.optString("android", "").ifBlank { session.remoteAndroid }
                    obj.optInt("mtu", 23).takeIf { it >= 23 }?.let { session.peerMtu = it }
                    notifyState()
                }
                "pair_accept" -> {
                    session.remoteName = obj.optString("device", "").ifBlank { session.remoteName }
                    session.remoteDeviceId = obj.optString("id", "").ifBlank { session.remoteDeviceId }
                    session.remoteAndroid = obj.optString("android", "").ifBlank { session.remoteAndroid }
                    obj.optInt("mtu", 23).takeIf { it >= 23 }?.let { session.peerMtu = it }
                    session.applicationReady = session.remoteDeviceId.isNotBlank()
                    if (session.applicationReady) handler.removeCallbacks(session.handshakeTimeoutRunnable)
                    session.remotePairAccepted = true
                    completePairingIfReady(session)
                    notifyState()
                    log("对方已确认配对")
                }
                "pair_reject" -> {
                    logGeneral("对方已拒绝配对")
                    closeSession(session, "对方已拒绝配对")
                }
                "disconnect" -> {
                    logGeneral("对方请求断开连接")
                    autoReconnectPaused = true
                    userDisconnectEvent = true
                    closeSession(session, "对方请求断开连接")
                }
                "unpair" -> {
                    val deviceId = session.remoteDeviceId
                    if (deviceId.isNotBlank()) {
                        SettingsRepository.get(appContext).removeDevice(deviceId)
                    }
                    log("对方已取消配对")
                    autoReconnectPaused = true
                    userDisconnectEvent = true
                    closeSession(session, "对方已取消配对")
                }
                "notif_remove" -> {
                    // 流转通知不随原机通知消失而消失：忽略原机的移除事件，
                    // 远端通知保留，由用户在本机自行清除。
                    log("原机通知已清除，远端保留流转通知")
                }
                "app_icon" -> {
                    if (!session.paired) return
                    val pkg = obj.optString("pkg", "")
                    val bitmap = AppIconCodec.decode(obj.optString("data", ""))
                    if (pkg.isBlank() || bitmap == null) return
                    remoteAppIcons.put(remoteIconKey(session.remoteDeviceId, pkg), bitmap)
                    val notifications = synchronized(remoteNotifications) {
                        remoteNotifications.values.filter { it.pkg == pkg }.toList()
                    }
                    notifications.forEach { renderLocalNotification(it, logPosted = false) }
                    log("已缓存远端应用图标：$pkg")
                }
                "find_device" -> {
                    if (!session.paired) return
                    FindDeviceController.start(appContext, session.remoteName)
                }
                "find_stop" -> {
                    FindDeviceController.stop()
                }
                "find_stopped" -> {
                    session.findingRemote = false
                    notifyState()
                    log("远端已停止响铃")
                }
                else -> {
                    if (!session.paired) {
                        logGeneral("配对未完成，忽略远程通知")
                        return
                    }
                    val key = obj.optString("key", "")
                    // 「优化流转重复通知」在接收端执行：1 秒内同一应用重复发布相同内容仅弹第一条。
                    // 去重维度是「设备+应用+内容」，不含通知 key —— 应用重复发相同内容时
                    // 往往生成新 key（新 post 而非原地更新），按 key 去重会完全失效。
                    if (SettingsRepository.get(appContext).dedupeRepeatEnabled) {
                        val fingerprint = listOf(
                            obj.optString("device", ""),
                            obj.optString("pkg", ""),
                            obj.optString("app", ""),
                            obj.optString("title", ""),
                            obj.optString("text", "")
                        ).joinToString("|").hashCode()
                        val now = android.os.SystemClock.elapsedRealtime()
                        val prev = receivedFingerprints[session.remoteDeviceId]
                        if (prev != null && prev.first == fingerprint && now - prev.second < 1_000L) {
                            log("1 秒内重复通知，已拦截：${obj.optString("app", "")}")
                            return
                        }
                        receivedFingerprints[session.remoteDeviceId] = fingerprint to now
                    }
                    log("收到远程通知")
                    val device = obj.optString("device", "")
                    val pkg = obj.optString("pkg", "")
                    val app = obj.optString("app", "远程")
                    val title = obj.optString("title", "")
                    val text = obj.optString("text", "")
                    val ongoing = obj.optBoolean("ongoing", false)
                    val otpCode = obj.optString("code", "")
                        .takeIf { obj.optBoolean("otp", false) && it.isNotBlank() }
                        ?: OtpDetector.detect(title, text)
                    // 若握手丢失，从通知里也能学到远端名
                    if (device.isNotBlank() && session.remoteName.isBlank()) {
                        session.remoteName = device
                        notifyState()
                    }
                    postLocalNotification(session.remoteDeviceId, device, pkg, app, title, text, key, ongoing, otpCode)
                }
            }
        } catch (e: Exception) {
            logGeneral("解析失败：${e.message}")
        }
    }

    /** 学到远端名字/ID 后，把该远端设备记入已配对列表（记住设备，不做系统绑定）。 */
    private fun completePairingIfReady(session: Session) {
        if (!session.localPairAccepted || !session.remotePairAccepted) return
        if (session.remoteDeviceId.isNotBlank() && session.remoteName.isNotBlank()) {
            SettingsRepository.get(appContext).saveDevice(
                SavedDevice(session.remoteDeviceId, session.remoteName, session.remoteAndroid)
            )
        }
        session.paired = true
        session.pairingRequired = false
        notifyState()
        log("双方已确认，配对完成")
    }

    // ================= 本地通知渲染 =================

    private fun postLocalNotification(
        senderId: String,
        device: String,
        pkg: String,
        app: String,
        title: String,
        text: String,
        key: String,
        ongoing: Boolean,
        otpCode: String? = null
    ) {
        // 内容刷新处理：「内容刷新视为新通知」开启时，同 key 但内容变化 → 代数 +1、
        // id 变化，以新通知形式再次弹出；关闭时同 id 原地覆盖（直接刷新已弹出的通知内容）
        val contentFp = listOf(device, pkg, app, title, text).joinToString("|").hashCode()
        val asNew = key.isNotEmpty() && SettingsRepository.get(appContext).refreshAsNewEnabled
        val generation = if (!asNew) 0 else {
            val genKey = "$senderId|$key"
            synchronized(notifGenerations) {
                val prev = notifGenerations[genKey]
                val newGen = when {
                    prev == null -> 0
                    prev.first != contentFp -> prev.second + 1
                    else -> prev.second
                }
                notifGenerations[genKey] = contentFp to newGen
                newGen
            }
        }
        val id = if (key.isNotEmpty()) "$key#$generation".hashCode() else notifId++
        val data = RemoteNotificationData(id, device, senderId, pkg, app, title, text, ongoing, otpCode)
        synchronized(remoteNotifications) { remoteNotifications[id] = data }
        renderLocalNotification(data, logPosted = true)
    }

    private fun renderLocalNotification(data: RemoteNotificationData, logPosted: Boolean) {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        ensureChannels(nm)
        val channelId = if (data.ongoing) Constants.CHANNEL_ID_ONGOING else Constants.CHANNEL_ID
        val appIcon = remoteAppIcons.get(remoteIconKey(data.senderId, data.pkg))

        // 标题格式：<应用名> | <来源设备型号/名称>
        val titleLine = listOf(data.app, data.device).filter { it.isNotBlank() }.joinToString(" | ")

        val liveNotification = buildOtpLiveNotification(data.app, data.title, data.text, data.otpCode, appIcon)
        val n = liveNotification
            ?: NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(buildBadgedIcon(appIcon))
                .setContentTitle(titleLine)
                .setContentText(data.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(data.text))
                // 不设置 onlyAlertOnce：原机通知状态刷新重发时，远端重新提醒一次
                .setAutoCancel(true)
                .build()
        nm.notify(data.id, n)
        if (logPosted) {
            log("已弹出本地通知：$titleLine（${if (liveNotification != null) "实时验证码" else if (data.ongoing) "常驻" else "普通"}通道）")
        }
    }

    // 本机应用图标（作通知角标），懒加载缓存
    @Volatile private var ownBadgeBitmap: Bitmap? = null

    private fun ownAppIconBitmap(): Bitmap? {
        ownBadgeBitmap?.let { return it }
        return try {
            val drawable = appContext.packageManager.getApplicationIcon(appContext.packageName)
            val size = 72
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            ownBadgeBitmap = bmp
            bmp
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 大图标 = 远端应用图标，右下角叠加本机应用图标（白色圆底衬出），用于区分普通通知。
     */
    private fun buildBadgedIcon(appIcon: Bitmap?): Bitmap? {
        appIcon ?: return null
        return try {
            val size = 192
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val src = Bitmap.createScaledBitmap(appIcon, size, size, true)
            canvas.drawBitmap(src, 0f, 0f, paint)
            val badge = ownAppIconBitmap()
            if (badge != null) {
                val badgeSize = (size * 0.42f).toInt()
                val inset = (size * 0.02f).toInt()
                val cx = size - badgeSize / 2 - inset
                val cy = size - badgeSize / 2 - inset
                paint.color = Color.WHITE
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), badgeSize / 2 + size * 0.03f, paint)
                val scaled = Bitmap.createScaledBitmap(badge, badgeSize, badgeSize, true)
                canvas.drawBitmap(scaled, (cx - badgeSize / 2).toFloat(), (cy - badgeSize / 2).toFloat(), paint)
            }
            out
        } catch (_: Exception) {
            appIcon
        }
    }

    private fun remoteIconKey(deviceId: String, packageName: String): String = "$deviceId|$packageName"

    /** Android 16+ 使用 ProgressStyle；API 不可用时由调用方回退到普通通知。 */
    private fun buildOtpLiveNotification(
        app: String,
        title: String,
        text: String,
        otpCode: String?,
        appIcon: Bitmap?
    ): Notification? {
        if (!SettingsRepository.get(appContext).otpLiveEnabled ||
            otpCode.isNullOrBlank() ||
            android.os.Build.VERSION.SDK_INT < 36
        ) return null
        if (appContext.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission("android.permission.POST_PROMOTED_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val progressStyleClass = Class.forName("android.app.Notification\$ProgressStyle")
            val progressStyle = progressStyleClass.getConstructor().newInstance()
            progressStyleClass
                .getMethod("setProgress", Int::class.javaPrimitiveType)
                .invoke(progressStyle, 100)
            progressStyleClass.methods
                .firstOrNull { it.name == "setStyledByProgress" && it.parameterTypes.size == 1 }
                ?.invoke(progressStyle, true)

            val builder = Notification.Builder(appContext, Constants.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sms)
                .setLargeIcon(appIcon)
                .setContentTitle(otpCode)
                .setContentText(text)
                .setSubText(listOf(app, title).filter { it.isNotBlank() }.joinToString(" · "))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setTimeoutAfter(5 * 60 * 1000L)
            Notification.Builder::class.java.methods
                .firstOrNull {
                    it.name == "setRequestPromotedOngoing" &&
                        it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                }
                ?.invoke(builder, true)
            val setStyle = Notification.Builder::class.java.methods.first {
                it.name == "setStyle" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(progressStyleClass)
            }
            setStyle.invoke(builder, progressStyle)
            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureChannels(nm: NotificationManager) {
        // v1 通道的铃声/震动设置创建后不可修改，迁移到 v2 通道并删除旧通道
        Constants.LEGACY_CHANNEL_IDS.forEach { id ->
            try { nm.deleteNotificationChannel(id) } catch (_: Exception) {}
        }
        val defaultSound = try {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } catch (_: Exception) {
            null
        }
        val soundAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(Constants.CHANNEL_ID, "流转的通知", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    // 默认请求铃声 + 震动 + 横幅（悬浮）展示
                    if (defaultSound != null) setSound(defaultSound, soundAttrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200, 120, 200)
                    enableLights(true)
                    setShowBadge(true)
                    allowChannelPromotion(this)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_ID_ONGOING,
                "流转的常驻通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台/常驻类通知（如场景调度、状态栏常驻），默认静默，可在系统设置中单独管理" }
        )
    }

    private fun allowChannelPromotion(channel: NotificationChannel) {
        if (android.os.Build.VERSION.SDK_INT < 36) return
        try {
            NotificationChannel::class.java
                .getMethod("setAllowPromoted", Boolean::class.javaPrimitiveType)
                .invoke(channel, true)
        } catch (_: Exception) {
            // 旧系统或不支持该属性时由普通通知路径继续工作。
        }
    }
}
