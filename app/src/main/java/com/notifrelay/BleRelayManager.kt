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
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 连接状态快照，供 UI 与常驻通知观察。
 */
data class RelayState(
    val role: BleRelayManager.Role = BleRelayManager.Role.NONE,
    val connected: Boolean = false,
    val remoteName: String = "",
    val remoteBattery: Int = -1,
    val remoteAndroid: String = "",
    val pairingRequired: Boolean = false,
    val paired: Boolean = false
)

/** 扫描发现到的设备（去重后的一行）。deviceId 为稳定身份，address 仅用于当前连接。 */
data class ScanDevice(val deviceId: String, val address: String, val name: String, val rssi: Int)

/** 发现状态快照（扫描开关 + 已发现设备列表）。 */
data class DiscoveryState(val scanning: Boolean, val devices: List<ScanDevice>)

/**
 * BLE 双向传输管理。
 *
 * 一条连接承载两个方向：
 *  - 中心(GATT client) write CHAR_FROM_CENTRAL  -> 外设(GATT server) 收到
 *  - 外设 notify CHAR_FROM_PERIPHERAL           -> 中心 收到
 *
 * 应用层消息为 JSON，分三类（由 "type" 字段区分）：
 *  - notif  通知（携带 device/app/title/text/ongoing）
 *  - hello  连接建立时握手（携带 device + battery）
 *  - status 心跳（携带 battery）
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
    }

    enum class Role { NONE, AUTO, PERIPHERAL, CENTRAL }

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
                    closeConnection()
                    log("蓝牙已关闭，已清理连接状态")
                }
                BluetoothAdapter.STATE_ON -> {
                    if (SettingsRepository.get(appContext).savedDevices().isNotEmpty()) {
                        handler.postDelayed({
                            if (!connected && btAdapter?.isEnabled == true) startDiscovery()
                        }, 1_000L)
                    }
                }
            }
        }
    }

    @Volatile var role: Role = Role.NONE
    @Volatile var connected: Boolean = false
    @Volatile var remoteName: String = ""
    @Volatile var remoteBattery: Int = -1
    @Volatile var remoteAndroid: String = ""
    @Volatile var remoteDeviceId: String = ""
    @Volatile var pairingRequired: Boolean = false
    @Volatile var paired: Boolean = false
    @Volatile private var applicationReady = false
    @Volatile var findingRemote: Boolean = false
    @Volatile private var userDisconnectEvent = false
    @Volatile private var disconnecting = false
    private var localPairAccepted = false
    private var remotePairAccepted = false

    // 状态观察者（UI / 常驻通知）
    private val stateListeners = CopyOnWriteArrayList<(RelayState) -> Unit>()

    // 发现观察者（设备列表 UI）
    private val discoveryListeners = CopyOnWriteArrayList<(DiscoveryState) -> Unit>()
    // 扫描结果去重累积（address -> 设备）
    private val discoveredDevices = LinkedHashMap<String, ScanDevice>()
    private var pendingConnectDeviceId: String? = null
    private var autoConnectSaved = true
    @Volatile private var autoReconnectPaused = false
    @Volatile private var centralConnecting = false

    // 心跳：周期上报电量
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            if (connected) sendStatus()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // 外设发送的延时调度（用命名 Runnable 以便 stopAll 时精准移除）
    private val peripheralSendRunnable = object : Runnable {
        override fun run() { peripheralSendNext() }
    }

    // 部分机型在刚完成 CCCD 订阅时会丢掉第一包 notify，重复握手可避免连接停在“未知设备”。
    private val helloRetryRunnable = object : Runnable {
        override fun run() {
            if (connected && !disconnecting) sendHello()
        }
    }

    private val centralConnectTimeoutRunnable = Runnable {
        if (centralConnecting) {
            val gatt = bluetoothGatt
            if (gatt != null) {
                failCentralConnection(gatt, "中心：连接超时")
            } else {
                centralConnecting = false
                clearConnectionState()
                notifyState()
                log("中心：连接超时")
            }
        }
    }

    private val handshakeTimeoutRunnable = Runnable {
        if (connected && !applicationReady) {
            log("应用层握手超时，断开后重试")
            val gatt = bluetoothGatt
            if (role == Role.CENTRAL && gatt != null) {
                failCentralConnection(gatt, "中心：应用层握手超时")
            } else {
                closeConnection()
            }
        }
    }

    // 扫描超时：SCAN_TIMEOUT_MS 后自动停扫
    private val scanTimeoutRunnable = object : Runnable {
        override fun run() {
            if (scanning) {
                log("扫描超时，已停止扫描")
                stopScanning()
                notifyDiscovery()
            }
        }
    }

    private val disconnectTimeoutRunnable = Runnable {
        if (disconnecting) closeConnection()
    }

    // ---- 外设侧 ----
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingRequested = false
    @Volatile private var serviceReady = false
    private var centralDevice: BluetoothDevice? = null
    private var charToCentral: BluetoothGattCharacteristic? = null

    // ---- 中心侧 ----
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var charFromCentral: BluetoothGattCharacteristic? = null
    private var charFromPeripheral: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var scanning = false

    // 分片接收缓冲
    private val recvBuffer = ByteArrayOutputStream()
    private var recvTotalLen = -1

    // 中心发送队列（串行写，onCharacteristicWrite 驱动下一片）
    private val sendQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private var disconnectAfterCentralWrite = false

    // 外设发送队列：notify 无确认，按固定间隔逐片发送（流控），避免连发丢片
    private val peripheralQueue = ArrayDeque<ByteArray>()
    private var peripheralSending = false
    private var disconnectAfterPeripheralSend = false
    private val peripheralLock = Any()

    private var notifId = 1000

    init {
        appContext.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun log(msg: String) = EventLog.add(msg)

    // ================= 状态观察 =================

    fun observeState(listener: (RelayState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeState(listener: (RelayState) -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyState() {
        val s = RelayState(role, visibleConnected(), remoteName, remoteBattery, remoteAndroid, pairingRequired, paired)
        stateListeners.forEach { it(s) }
    }

    fun needsLocalPairConfirmation(): Boolean = pairingRequired && !localPairAccepted

    fun visibleConnected(): Boolean = connected && applicationReady && !disconnecting

    fun isAutoReconnectPaused(): Boolean = autoReconnectPaused

    fun isConnecting(): Boolean = centralConnecting

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

    private fun notifyDiscovery() {
        val s = DiscoveryState(scanning, discoveredDevices.values.toList())
        discoveryListeners.forEach { it(s) }
    }

    // ================= 对外接口 =================

    /**
     * 发现模式：同时广播（可被发现）+ 扫描（发现对方），但**不**自动连接。
     * 用户点按设备列表里的设备再调用 connectTo() 主动连接（我方作中心）。
     */
    fun startDiscovery(autoConnectSaved: Boolean = true) {
        if (connected || centralConnecting) return
        // 前台服务和设备页可能同时请求发现；已有发现会话不能被重置，
        // 否则会中断刚由扫描触发的 GATT 连接。
        if (role == Role.AUTO && gattServer != null && scanning) {
            this.autoConnectSaved = autoConnectSaved && !autoReconnectPaused
            return
        }
        stopAll()
        this.autoConnectSaved = autoConnectSaved && !autoReconnectPaused
        if (!hasBlePermissions()) {
            log("蓝牙权限未授予，无法发现设备")
            return
        }
        val adapter = btAdapter ?: run { log("无蓝牙适配器"); return }
        if (!adapter.isEnabled) { log("请先手动打开蓝牙"); return }
        val saved = SettingsRepository.get(appContext).savedDevices().firstOrNull()
        val myId = SettingsRepository.get(appContext).deviceId()
        // 已配对后固定角色：deviceId 较小的一方主动扫描连接，较大的一方只广播等待。
        // 这样两端不会同时发起 GATT 连接，避免把一条连接误判成两个角色。
        role = if (this.autoConnectSaved && saved != null) {
            if (myId < saved.deviceId) Role.CENTRAL else Role.PERIPHERAL
        } else {
            Role.AUTO
        }
        notifyState()

        if (role != Role.CENTRAL) startAdvertising()
        if (role != Role.PERIPHERAL) startScanning()
        log(
            when (role) {
                Role.CENTRAL -> "自动回连：我作中心，扫描已配对设备"
                Role.PERIPHERAL -> "自动回连：我作外设，广播等待中心连接"
                else -> "发现模式：广播 + 扫描，等待点按连接…"
            }
        )
    }

    private fun startAdvertising() {
        val adapter = btAdapter ?: return
        advertisingRequested = true
        serviceReady = false
        val server = btManager.openGattServer(appContext, gattServerCallback)
        gattServer = server
        if (!server.addService(buildService())) {
            log("添加 GATT 服务失败")
        } else {
            // GATT 服务注册是异步的。必须等 onServiceAdded 成功后再开始广播，
            // 否则另一端可能先连上却发现不到目标服务。
            log("GATT 服务注册中")
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

    /** 点按设备：我方作中心主动连接对方。 */
    fun connectTo(address: String) {
        val adapter = btAdapter ?: run { log("无蓝牙适配器"); return }
        if (address.isBlank()) return
        autoReconnectPaused = false
        stopDiscovery()
        closeGattServer() // 我方作中心：关闭本机 GATT server，避免其回调把 role 顶回外设
        val device = adapter.getRemoteDevice(address)
        log("连接 $address …")
        connectAsCentral(device)
    }

    fun connectToSaved(deviceId: String) {
        autoReconnectPaused = false
        val discovered = discoveredDevices.values.firstOrNull { it.deviceId == deviceId }
        if (discovered != null) {
            connectTo(discovered.address)
            return
        }
        startDiscovery()
        pendingConnectDeviceId = deviceId
        log("正在查找已配对设备…")
    }

    fun acceptPairing() {
        if (!connected || !pairingRequired) return
        localPairAccepted = true
        sendToRemote(infoJson().put("type", "pair_accept").toString())
        completePairingIfReady()
        notifyState()
        log("本机已确认配对，等待对方确认")
    }

    fun rejectPairing() {
        if (!connected || !pairingRequired) return
        log("本机已拒绝配对")
        sendControlAndDisconnect("pair_reject")
    }

    fun disconnect() {
        if (connected) {
            log("正在同步断开连接")
            autoReconnectPaused = true
            sendControlAndDisconnect("disconnect")
            return
        }
        closeConnection()
    }

    fun unpair(deviceId: String) {
        SettingsRepository.get(appContext).removeDevice(deviceId)
        if (connected && remoteDeviceId == deviceId) {
            log("正在同步取消配对")
            autoReconnectPaused = true
            sendControlAndDisconnect("unpair")
        }
    }

    fun findRemoteDevice(): Boolean {
        if (!visibleConnected() || !paired || findingRemote) return false
        sendToRemote(JSONObject().put("type", "find_device").toString())
        findingRemote = true
        notifyState()
        log("已向远端发送查找设备请求")
        return true
    }

    fun cancelFindRemote(): Boolean {
        if (!visibleConnected() || !findingRemote) return false
        sendToRemote(JSONObject().put("type", "find_stop").toString())
        findingRemote = false
        notifyState()
        log("已取消查找设备")
        return true
    }

    private fun sendControlAndDisconnect(type: String) {
        userDisconnectEvent = true
        disconnecting = true
        notifyState()
        handler.removeCallbacks(disconnectTimeoutRunnable)
        handler.postDelayed(disconnectTimeoutRunnable, DISCONNECT_TIMEOUT_MS)
        // 控制消息保持最小，默认 MTU 下只需两片，发送完成后再关闭物理链路。
        val chunks = chunk(JSONObject().put("type", type).toString().toByteArray(Charsets.UTF_8))
        when (role) {
            Role.CENTRAL -> {
                sendQueue.clear()
                sendQueue.addAll(chunks)
                disconnectAfterCentralWrite = true
                if (!writing) writeNext()
            }
            Role.PERIPHERAL -> synchronized(peripheralLock) {
                peripheralQueue.clear()
                peripheralQueue.addAll(chunks)
                disconnectAfterPeripheralSend = true
                if (!peripheralSending) {
                    peripheralSending = true
                    handler.post(peripheralSendRunnable)
                }
            }
            Role.NONE, Role.AUTO -> closeConnection()
        }
    }

    private fun closeConnection() {
        clearConnectionState()
        notifyState()
        stopAll()
    }

    private fun startScanning() {
        val adapter = btAdapter ?: return
        scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        scanning = true
        scanner?.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        notifyDiscovery()
    }

    fun sendToRemote(json: String) {
        if (disconnecting) return
        val chunks = chunk(json.toByteArray(Charsets.UTF_8))
        when (role) {
            Role.CENTRAL -> {
                val gatt = bluetoothGatt
                val char = charFromCentral
                if (!connected || gatt == null || char == null) {
                    log("中心未连接，丢弃通知")
                    return
                }
                sendQueue.addAll(chunks)
                if (!writing) writeNext()
            }
            Role.PERIPHERAL -> {
                val dev = centralDevice
                val charOut = charToCentral
                val server = gattServer
                if (!connected || dev == null || charOut == null || server == null) {
                    log("外设无中心连接，丢弃通知")
                    return
                }
                enqueuePeripheral(chunks)
            }
            Role.NONE, Role.AUTO -> log("未连接，丢弃通知")
        }
    }

    /**
     * 外设→中心发送：notify 是「无确认」的，连发会溢出 GATT server 缓冲导致丢片，
     * 中心侧永远凑不齐完整 JSON。这里像中心写队列一样串行化，并给每片留出发送间隔。
     */
    private fun enqueuePeripheral(chunks: List<ByteArray>) {
        synchronized(peripheralLock) {
            peripheralQueue.addAll(chunks)
            if (!peripheralSending) {
                peripheralSending = true
                handler.post(peripheralSendRunnable)
            }
        }
    }

    private fun peripheralSendNext() {
        val dev = centralDevice
        val char = charToCentral
        val server = gattServer
        if (!connected || dev == null || char == null || server == null) {
            synchronized(peripheralLock) {
                peripheralQueue.clear()
                peripheralSending = false
            }
            return
        }
        var chunk: ByteArray? = null
        var shouldDisconnect = false
        synchronized(peripheralLock) {
            if (peripheralQueue.isEmpty()) {
                peripheralSending = false
                if (disconnectAfterPeripheralSend) {
                    disconnectAfterPeripheralSend = false
                    shouldDisconnect = true
                }
            } else {
                chunk = peripheralQueue.removeFirst()
            }
        }
        if (shouldDisconnect) {
            closeConnection()
            return
        }
        val data = chunk ?: return
        char.value = data
        // 用 3 参数重载（返回 boolean，API 18+ 兼容）。
        // 4 参数重载 API 33 才有、返回 int 状态码，在 Android 12 上会 NoSuchMethodError。
        @Suppress("DEPRECATION")
        val ok = server.notifyCharacteristicChanged(dev, char, false)
        if (ok) {
            handler.postDelayed(peripheralSendRunnable, PERIPHERAL_NOTIFY_DELAY_MS)
        } else {
            synchronized(peripheralLock) {
                peripheralQueue.clear()
                peripheralSending = false
            }
            log("外设 notify 发送失败，清空待发队列")
        }
    }

    fun stopAll() {
        val oldGatt = bluetoothGatt
        bluetoothGatt = null
        try { oldGatt?.disconnect(); oldGatt?.close() } catch (_: Exception) {}
        charFromCentral = null
        charFromPeripheral = null
        stopAdvertising()
        stopScanning()
        try { centralDevice?.let { gattServer?.cancelConnection(it) } } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        centralDevice = null
        charToCentral = null
        sendQueue.clear()
        writing = false
        disconnectAfterCentralWrite = false
        handler.removeCallbacks(peripheralSendRunnable)
        handler.removeCallbacks(helloRetryRunnable)
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.removeCallbacks(disconnectTimeoutRunnable)
        synchronized(peripheralLock) {
            peripheralQueue.clear()
            peripheralSending = false
            disconnectAfterPeripheralSend = false
        }
        recvBuffer.reset()
        recvTotalLen = -1
        discoveredDevices.clear()
        pendingConnectDeviceId = null
        notifyDiscovery()
        clearConnectionState()
        notifyState()
        log("已停止")
    }

    private fun clearConnectionState() {
        applicationReady = false
        centralConnecting = false
        role = Role.NONE
        connected = false
        remoteName = ""
        remoteBattery = -1
        remoteAndroid = ""
        remoteDeviceId = ""
        pairingRequired = false
        paired = false
        localPairAccepted = false
        remotePairAccepted = false
        findingRemote = false
        disconnecting = false
        mtu = 23
    }

    private fun stopAdvertising() {
        advertisingRequested = false
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertiser = null
    }

    /** 关闭本机 GATT server（我方作中心时调用）：server 关闭后，其 onConnectionStateChange 不会再把 role 顶回外设。 */
    private fun closeGattServer() {
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        charToCentral = null
    }

    private fun stopScanning() {
        if (scanning) {
            scanning = false
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        }
        handler.removeCallbacks(scanTimeoutRunnable)
    }

    // ================= 应用层消息 =================

    private fun sendHello() {
        sendToRemote(infoJson().put("type", "hello").toString())
    }

    private fun sendHelloWithRetry() {
        handler.removeCallbacks(helloRetryRunnable)
        sendHello()
        handler.postDelayed(helloRetryRunnable, 500L)
        handler.postDelayed(helloRetryRunnable, 1_500L)
    }

    private fun sendStatus() {
        sendToRemote(infoJson().put("type", "status").toString())
    }

    private fun infoJson(): JSONObject = JSONObject()
        .put("device", SettingsRepository.get(appContext).resolvedDeviceName())
        .put("id", SettingsRepository.get(appContext).deviceId())
        .put("battery", DeviceInfo.batteryPercent(appContext))
        .put("android", DeviceInfo.androidVersion())

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

    // ================= 外设侧回调 =================

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
            log("广播启动失败：$msg (code=$errorCode)")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != Constants.SERVICE_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS && gattServer != null && role != Role.CENTRAL) {
                serviceReady = true
                log("GATT 服务注册成功，开始广播")
                startAdvertisingBeacon()
            } else {
                log("GATT 服务注册失败 status=$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (gattServer == null) return
                if (!serviceReady) {
                    log("外设：服务尚未就绪，拒绝过早连接")
                    try { gattServer?.cancelConnection(device) } catch (_: Exception) {}
                    return
                }
                // 防御：我方已是中心时，忽略本机 server 对同一物理链路报上来的连接事件，
                // 避免把 role 从 CENTRAL 顶回 PERIPHERAL，导致中心走错发送路径。
                if (role == Role.CENTRAL) {
                    log("外设：忽略 server 侧重复连接（我方已是中心）")
                    return
                }
                role = Role.PERIPHERAL
                centralDevice = device
                connected = true
                applicationReady = false
                handler.removeCallbacks(handshakeTimeoutRunnable)
                handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)
                mtu = 23
                stopDiscovery()
                notifyState()
                log("外设：中心已连接 ${device.name ?: device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (device != centralDevice) return
                if (!disconnecting) autoReconnectPaused = false
                centralDevice = null
                clearConnectionState()
                notifyState()
                log("外设：中心已断开 status=$status")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@BleRelayManager.mtu = mtu
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
                onChunkReceived(value)
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
            // 此时中心已订阅 notify，可靠地把本机设备名+电量推给对方
            sendHelloWithRetry()
        }
    }

    // ================= 中心侧回调 =================

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
                .ifBlank { if (savedDevices.size == 1) savedDevices[0].deviceId else "" }
            val resolvedName = name.ifBlank {
                savedDevices.firstOrNull { it.deviceId == resolvedDeviceId }?.name.orEmpty()
            }
            // 用稳定 deviceId 去重（缺失时退回 address）
            val key = if (resolvedDeviceId.isNotBlank()) resolvedDeviceId else address
            discoveredDevices[key] = ScanDevice(resolvedDeviceId, address, resolvedName, result.rssi)
            notifyDiscovery()

            if (!connected && resolvedDeviceId == pendingConnectDeviceId) {
                pendingConnectDeviceId = null
                log("已找到配对设备，正在连接 $resolvedName")
                stopDiscovery()
                closeGattServer()
                connectAsCentral(device)
                return
            }

            // 只对已保存设备自动重连。首次发现的新设备必须由用户在列表中确认连接，
            // 避免附近安装了本应用的陌生设备被自动连上。
            if (connected || resolvedDeviceId.isBlank()) return
            if (!autoConnectSaved) return
            if (SettingsRepository.get(appContext).findByDeviceId(resolvedDeviceId) == null) return

            // 自动协商中心/外设：deviceId 字典序较小的一方作中心主动连接，另一方继续广播等待。
            // 规则两侧一致 → 恰好一方连、一方等，不会双连。
            val myId = SettingsRepository.get(appContext).deviceId()
            if (resolvedDeviceId < myId) {
                log("自动协商：对方（$resolvedName）作中心，我继续广播等待")
                return
            }
            log("自动协商：我作中心，连接 $resolvedName")
            // 先占用中心角色再关闭 GATT server，避免关闭/连接之间的回调窗口
            // 把同一条连接误判为外设连接。
            centralConnecting = true
            role = Role.CENTRAL
            notifyState()
            stopDiscovery()
            closeGattServer() // 我方作中心：关闭本机 GATT server，避免其回调把 role 顶回外设
            connectAsCentral(device)
        }
        override fun onScanFailed(errorCode: Int) {
            log("扫描失败 code=$errorCode")
        }
    }

    private fun connectAsCentral(device: BluetoothDevice) {
        // autoConnect=false：直接连接。true 会把连接请求挂起等待，是「卡在连接中」的常见原因。
        centralConnecting = true
        role = Role.CENTRAL
        notifyState()
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback)
        handler.postDelayed(centralConnectTimeoutRunnable, CENTRAL_CONNECT_TIMEOUT_MS)
        if (bluetoothGatt == null) {
            handler.removeCallbacks(centralConnectTimeoutRunnable)
            centralConnecting = false
            clearConnectionState()
            notifyState()
            log("中心：发起连接失败")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTING) {
                log("中心：连接中")
            } else if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(centralConnectTimeoutRunnable)
                centralConnecting = false
                role = Role.CENTRAL
                connected = true
                applicationReady = false
                handler.removeCallbacks(handshakeTimeoutRunnable)
                handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)
                notifyState()
                log("中心：已连接，开始发现服务")
                // GATT 操作必须串行：这里只做服务发现，不要并发 requestMtu
                refreshGattCache(gatt)
                handler.postDelayed({
                    if (gatt != bluetoothGatt || !connected) return@postDelayed
                    if (!gatt.discoverServices()) {
                        failCentralConnection(gatt, "发起服务发现失败")
                    }
                }, 300L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt != bluetoothGatt) return
                handler.removeCallbacks(centralConnectTimeoutRunnable)
                centralConnecting = false
                if (!disconnecting) autoReconnectPaused = false
                try { gatt.close() } catch (_: Exception) {}
                bluetoothGatt = null
                charFromCentral = null
                charFromPeripheral = null
                clearConnectionState()
                notifyState()
                log("中心：连接断开 status=$status")
            } else {
                handler.removeCallbacks(centralConnectTimeoutRunnable)
                failCentralConnection(gatt, "中心：连接失败 status=$status state=$newState")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // 主动不协商 MTU，保持默认 23：不同机型 MTU 不对称会导致大包被对端拒收
            this@BleRelayManager.mtu = mtu
            log("中心：MTU=$mtu")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failCentralConnection(gatt, "发现服务失败 status=$status")
                return
            }
            val service = gatt.getService(Constants.SERVICE_UUID)
            if (service == null) {
                failCentralConnection(gatt, "未找到目标服务")
                return
            }
            charFromCentral = service.getCharacteristic(Constants.CHAR_FROM_CENTRAL)
            charFromPeripheral = service.getCharacteristic(Constants.CHAR_FROM_PERIPHERAL)
            if (charFromCentral == null || charFromPeripheral == null) {
                failCentralConnection(gatt, "中心：特征值不完整")
                return
            }
            subscribeToNotifications(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failCentralConnection(gatt, "中心：订阅失败($status)")
                return
            }
            log("中心：订阅成功")
            // 订阅成功后把本机设备名/版本/电量推给对方
            sendHelloWithRetry()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == Constants.CHAR_FROM_CENTRAL) {
                writeNext()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == Constants.CHAR_FROM_PERIPHERAL) {
                characteristic.value?.let { onChunkReceived(it) }
            }
        }
    }

    private fun subscribeToNotifications(gatt: BluetoothGatt) {
        val charRecv = charFromPeripheral ?: run { log("中心：接收特征值未就绪"); return }
        gatt.setCharacteristicNotification(charRecv, true)
        val cccd = charRecv.getDescriptor(Constants.CCCD_UUID)
        if (cccd == null) {
            log("中心：CCCD 为 null（外设未添加 CCCD 描述符）")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = gatt.writeDescriptor(cccd)
        log("中心：发起订阅（writeDescriptor=$ok）")
        if (!ok) failCentralConnection(gatt, "中心：发起订阅失败")
    }

    private fun failCentralConnection(gatt: BluetoothGatt, reason: String) {
        if (gatt != bluetoothGatt) return
        centralConnecting = false
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
        bluetoothGatt = null
        charFromCentral = null
        charFromPeripheral = null
        clearConnectionState()
        notifyState()
        log(reason)
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val refreshed = gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean
            log("中心：刷新 GATT 缓存 ${if (refreshed == true) "成功" else "未执行"}")
        } catch (_: Exception) {
            log("中心：刷新 GATT 缓存不可用")
        }
    }

    private fun writeNext() {
        val gatt = bluetoothGatt
        val char = charFromCentral
        if (gatt == null || char == null || !connected) {
            sendQueue.clear(); writing = false; return
        }
        if (sendQueue.isEmpty()) {
            writing = false
            if (disconnectAfterCentralWrite) {
                disconnectAfterCentralWrite = false
                closeConnection()
            }
            return
        }
        writing = true
        val chunk = sendQueue.removeFirst()
        char.value = chunk
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!gatt.writeCharacteristic(char)) {
            log("写特征值失败")
            writing = false
        }
    }

    // ================= 分片协议 =================

    private fun chunk(bytes: ByteArray): List<ByteArray> {
        val total = bytes.size
        val maxPayload = ((mtu - 3 - 5).coerceAtLeast(15))
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

    private fun onChunkReceived(data: ByteArray) {
        if (data.size < 5 || data[0] != 0x01.toByte()) return
        val total = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val offset = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val payload = data.copyOfRange(5, data.size)

        if (offset == 0) {
            recvBuffer.reset()
            recvTotalLen = total
        }
        if (recvTotalLen != total) return

        recvBuffer.write(payload)
        if (recvBuffer.size() >= total) {
            val json = String(recvBuffer.toByteArray(), Charsets.UTF_8)
            recvBuffer.reset()
            recvTotalLen = -1
            handleReceivedMessage(json)
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

    private fun handleReceivedMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type", "notif")) {
                "hello" -> {
                    remoteName = obj.optString("device", "").ifBlank { remoteName }
                    remoteDeviceId = obj.optString("id", "").ifBlank { remoteDeviceId }
                    remoteBattery = obj.optInt("battery", -1)
                    remoteAndroid = obj.optString("android", "").ifBlank { remoteAndroid }
                    val saved = SettingsRepository.get(appContext).findByDeviceId(remoteDeviceId)
                    paired = saved != null
                    pairingRequired = !paired
                    localPairAccepted = paired
                    remotePairAccepted = paired
                    applicationReady = remoteDeviceId.isNotBlank()
                    if (applicationReady) handler.removeCallbacks(handshakeTimeoutRunnable)
                    notifyState()
                    log("握手：远端 ${remoteName.ifBlank { "未知" }} $remoteAndroid 电量 $remoteBattery%")
                    if (paired) {
                        sendToRemote(infoJson().put("type", "pair_accept").toString())
                    }
                }
                "status" -> {
                    remoteName = obj.optString("device", "").ifBlank { remoteName }
                    remoteDeviceId = obj.optString("id", "").ifBlank { remoteDeviceId }
                    remoteBattery = obj.optInt("battery", -1)
                    remoteAndroid = obj.optString("android", "").ifBlank { remoteAndroid }
                    notifyState()
                }
                "pair_accept" -> {
                    remoteName = obj.optString("device", "").ifBlank { remoteName }
                    remoteDeviceId = obj.optString("id", "").ifBlank { remoteDeviceId }
                    remoteAndroid = obj.optString("android", "").ifBlank { remoteAndroid }
                    applicationReady = remoteDeviceId.isNotBlank()
                    if (applicationReady) handler.removeCallbacks(handshakeTimeoutRunnable)
                    remotePairAccepted = true
                    completePairingIfReady()
                    notifyState()
                    log("对方已确认配对")
                }
                "pair_reject" -> {
                    log("对方已拒绝配对")
                    closeConnection()
                }
                "disconnect" -> {
                    log("对方请求断开连接")
                    autoReconnectPaused = true
                    userDisconnectEvent = true
                    closeConnection()
                }
                "unpair" -> {
                    val deviceId = remoteDeviceId
                    if (deviceId.isNotBlank()) {
                        SettingsRepository.get(appContext).removeDevice(deviceId)
                    }
                    log("对方已取消配对")
                    autoReconnectPaused = true
                    userDisconnectEvent = true
                    closeConnection()
                }
                "notif_remove" -> {
                    if (!paired) return
                    val key = obj.optString("key", "")
                    if (key.isNotBlank()) {
                        appContext.getSystemService(NotificationManager::class.java)
                            .cancel(key.hashCode())
                        log("已清除远程通知")
                    }
                }
                "find_device" -> {
                    if (!paired) return
                    FindDeviceController.start(appContext, remoteName)
                }
                "find_stop" -> {
                    FindDeviceController.stop()
                }
                "find_stopped" -> {
                    findingRemote = false
                    notifyState()
                    log("远端已停止响铃")
                }
                else -> {
                    if (!paired) {
                        log("配对未完成，忽略远程通知")
                        return
                    }
                    log("收到远程通知")
                    val device = obj.optString("device", "")
                    val app = obj.optString("app", "远程")
                    val title = obj.optString("title", "")
                    val text = obj.optString("text", "")
                    val key = obj.optString("key", "")
                    val ongoing = obj.optBoolean("ongoing", false)
                    val otpCode = obj.optString("code", "")
                        .takeIf { obj.optBoolean("otp", false) && it.isNotBlank() }
                        ?: OtpDetector.detect(title, text)
                    // 若握手丢失，从通知里也能学到远端名
                    if (device.isNotBlank() && remoteName.isBlank()) {
                        remoteName = device
                        notifyState()
                    }
                    postLocalNotification(device, app, title, text, key, ongoing, otpCode)
                }
            }
        } catch (e: Exception) {
            log("解析失败：${e.message}")
        }
    }

    /** 学到远端名字/ID 后，把当前远端设备记入已配对列表（记住设备，不做系统绑定）。 */
    private fun saveRemoteIfKnown() {
        if (remoteDeviceId.isBlank() || remoteName.isBlank()) return
        SettingsRepository.get(appContext).saveDevice(SavedDevice(remoteDeviceId, remoteName, remoteAndroid))
    }

    private fun completePairingIfReady() {
        if (!localPairAccepted || !remotePairAccepted) return
        saveRemoteIfKnown()
        paired = true
        pairingRequired = false
        notifyState()
        log("双方已确认，配对完成")
    }

    private fun postLocalNotification(
        device: String,
        app: String,
        title: String,
        text: String,
        key: String,
        ongoing: Boolean,
        otpCode: String? = null
    ) {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        ensureChannels(nm)

        // 常驻/不可清除类通知进单独通道（默认静默），方便在系统设置里单独管理
        val channelId = if (ongoing) Constants.CHANNEL_ID_ONGOING else Constants.CHANNEL_ID

        // 用通知 key 的 hash 作为稳定 id：同一条通知的更新会覆盖同一条，而不是堆积新通知
        val id = if (key.isNotEmpty()) key.hashCode() else notifId++

        // 标题格式：<远端设备名> | <应用名> | <通知标题>（空段自动省略）
        val titleLine = listOf(device, app, title).filter { it.isNotBlank() }.joinToString(" | ")

        val liveNotification = buildOtpLiveNotification(app, titleLine, otpCode)
        val n = liveNotification
            ?: NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(titleLine)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        nm.notify(id, n)
        log("已弹出本地通知：$titleLine（${if (liveNotification != null) "实时验证码" else if (ongoing) "常驻" else "普通"}通道）")
    }

    /** Android 16+ 使用 ProgressStyle；API 不可用时由调用方回退到普通通知。 */
    private fun buildOtpLiveNotification(app: String, titleLine: String, otpCode: String?): Notification? {
        if (otpCode.isNullOrBlank() || android.os.Build.VERSION.SDK_INT < 36) return null
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
                .setContentTitle(otpCode)
                .setContentText(otpCode)
                .setSubText(titleLine)
                .setCategory(Notification.CATEGORY_MESSAGE)
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
        nm.createNotificationChannel(
            NotificationChannel(Constants.CHANNEL_ID, "流转的通知", NotificationManager.IMPORTANCE_HIGH)
                .also { allowChannelPromotion(it) }
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
