package com.notifrelay

import android.annotation.SuppressLint
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
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 连接状态快照，供 UI 与常驻通知观察。
 */
data class RelayState(
    val role: BleRelayManager.Role = BleRelayManager.Role.NONE,
    val connected: Boolean = false,
    val remoteName: String = "",
    val remoteBattery: Int = -1
)

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
    }

    enum class Role { NONE, AUTO, PERIPHERAL, CENTRAL }

    private val appContext = context.applicationContext
    private val btManager: BluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager.adapter

    @Volatile var role: Role = Role.NONE
    @Volatile var connected: Boolean = false
    @Volatile var remoteName: String = ""
    @Volatile var remoteBattery: Int = -1

    // 状态观察者（UI / 常驻通知）
    private val stateListeners = CopyOnWriteArrayList<(RelayState) -> Unit>()

    // 心跳：周期上报电量
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            if (connected) sendStatus()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    // ---- 外设侧 ----
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var centralDevice: BluetoothDevice? = null
    private var charToCentral: BluetoothGattCharacteristic? = null

    // ---- 中心侧 ----
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var charFromCentral: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var scanning = false

    // 分片接收缓冲
    private val recvBuffer = ByteArrayOutputStream()
    private var recvTotalLen = -1

    // 中心发送队列（串行写，onCharacteristicWrite 驱动下一片）
    private val sendQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private var notifId = 1000

    init {
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
        val s = RelayState(role, connected, remoteName, remoteBattery)
        stateListeners.forEach { it(s) }
    }

    // ================= 对外接口 =================

    fun startPeripheral() {
        stopAll()
        val adapter = btAdapter ?: run { log("无蓝牙适配器"); return }
        if (!adapter.isEnabled) { log("请先手动打开蓝牙"); return }
        role = Role.PERIPHERAL
        notifyState()

        val server = btManager.openGattServer(appContext, gattServerCallback)
        gattServer = server
        if (!server.addService(buildService())) {
            log("外设：添加 GATT 服务失败")
        }

        advertiser = adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // 注意：BLE 广播包有 31 字节硬限制。setIncludeDeviceName(true) 会把设备名塞进广播，
        // 设备名 + 16 字节 128-bit UUID 极易超过 31 字节，触发 ADVERTISE_FAILED_DATA_TOO_LARGE(code=1)。
        // 因此只广播 service UUID（中心按 UUID 过滤即可发现），不广播设备名。
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        log("外设模式：开始广播，等待中心连接…")
    }

    fun startCentral() {
        stopAll()
        val adapter = btAdapter ?: run { log("无蓝牙适配器"); return }
        if (!adapter.isEnabled) { log("请先手动打开蓝牙"); return }
        role = Role.CENTRAL
        notifyState()

        scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner?.startScan(listOf(filter), settings, scanCallback)
        log("中心模式：开始扫描…")
    }

    fun startAuto() {
        stopAll()
        val adapter = btAdapter ?: run { log("无蓝牙适配器"); return }
        if (!adapter.isEnabled) { log("请先手动打开蓝牙"); return }
        role = Role.AUTO
        notifyState()

        // 同时开启 GATT 服务 + 广播（可作外设）与扫描（可作中心）
        val server = btManager.openGattServer(appContext, gattServerCallback)
        gattServer = server
        if (!server.addService(buildService())) {
            log("外设：添加 GATT 服务失败")
        }

        advertiser = adapter.bluetoothLeAdvertiser
        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(advSettings, advData, advertiseCallback)

        scanner = adapter.bluetoothLeScanner
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        log("自动模式：同时广播与扫描，等待发现对方…")
    }

    fun sendToRemote(json: String) {
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
                var sent = 0
                for (c in chunks) {
                    charOut.value = c
                    // 用 3 参数重载（返回 boolean，API 18+ 兼容）。
                    // 4 参数重载 API 33 才有、返回 int 状态码，在 Android 12 上会 NoSuchMethodError。
                    @Suppress("DEPRECATION")
                    val ok = server.notifyCharacteristicChanged(dev, charOut, false)
                    if (ok) sent++
                }
                if (sent < chunks.size) {
                    log("外设 notify 未送达：$sent/${chunks.size} 片（中心可能未订阅）")
                }
            }
            Role.NONE, Role.AUTO -> log("未连接，丢弃通知")
        }
    }

    fun stopAll() {
        try { if (scanning) { scanning = false; scanner?.stopScan(scanCallback) } } catch (_: Exception) {}
        try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
        charFromCentral = null
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertiser = null
        try { centralDevice?.let { gattServer?.cancelConnection(it) } } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        centralDevice = null
        charToCentral = null
        sendQueue.clear()
        writing = false
        recvBuffer.reset()
        recvTotalLen = -1
        role = Role.NONE
        connected = false
        remoteName = ""
        remoteBattery = -1
        mtu = 23
        notifyState()
        log("已停止")
    }

    private fun stopAdvertising() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertiser = null
    }

    private fun stopScanning() {
        if (scanning) {
            scanning = false
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        }
    }

    // ================= 应用层消息 =================

    private fun sendHello() {
        val name = SettingsRepository.get(appContext).resolvedDeviceName()
        val obj = JSONObject()
            .put("type", "hello")
            .put("device", name)
            .put("battery", DeviceInfo.batteryPercent(appContext))
        sendToRemote(obj.toString())
    }

    private fun sendStatus() {
        val obj = JSONObject()
            .put("type", "status")
            .put("battery", DeviceInfo.batteryPercent(appContext))
        sendToRemote(obj.toString())
    }

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
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                role = Role.PERIPHERAL
                centralDevice = device
                connected = true
                mtu = 23
                stopScanning()
                notifyState()
                log("外设：中心已连接 ${device.name ?: device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                centralDevice = null
                connected = false
                remoteName = ""
                remoteBattery = -1
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
            sendHello()
        }
    }

    // ================= 中心侧回调 =================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            try { scanner?.stopScan(this) } catch (_: Exception) {}
            val device = result.device

            // 自动协商：地址字典序较小的一方作中心（发起连接）。
            // 我方与对方各自用自己的地址比较，规则一致 → 恰好一方连、另一方等。
            val local = btAdapter?.address.orEmpty()
            val remote = device.address.orEmpty()
            if (local.isNotEmpty() && remote.isNotEmpty() && remote < local) {
                log("自动协商：对方作中心，我继续广播等待连接")
                return
            }

            // 我作中心：停止广播，连接对方
            stopAdvertising()
            log("自动协商：我作中心，连接 ${device.name ?: device.address}")
            connectAsCentral(device)
        }
        override fun onScanFailed(errorCode: Int) {
            log("扫描失败 code=$errorCode")
        }
    }

    private fun connectAsCentral(device: BluetoothDevice) {
        // autoConnect=false：直接连接。true 会把连接请求挂起等待，是「卡在连接中」的常见原因。
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                role = Role.CENTRAL
                connected = true
                notifyState()
                log("中心：已连接，开始发现服务")
                // GATT 操作必须串行：这里只做服务发现，不要并发 requestMtu
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                remoteName = ""
                remoteBattery = -1
                notifyState()
                log("中心：连接断开 status=$status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@BleRelayManager.mtu = mtu
            log("中心：MTU=$mtu")
            // 订阅完成后的首个安全写入点：把本机设备名+电量推给对方
            sendHello()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("发现服务失败 status=$status")
                return
            }
            val service = gatt.getService(Constants.SERVICE_UUID)
            if (service == null) {
                log("未找到目标服务")
                return
            }
            charFromCentral = service.getCharacteristic(Constants.CHAR_FROM_CENTRAL)
            val charRecv = service.getCharacteristic(Constants.CHAR_FROM_PERIPHERAL)
            if (charRecv == null) {
                log("中心：未找到接收特征值")
                return
            }
            gatt.setCharacteristicNotification(charRecv, true)
            val cccd = charRecv.getDescriptor(Constants.CCCD_UUID)
            if (cccd == null) {
                log("中心：CCCD 为 null（外设未添加 CCCD 描述符）")
                return
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = gatt.writeDescriptor(cccd)
            log("中心：服务已发现，发起订阅（writeDescriptor=$ok）")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("中心：订阅${if (status == BluetoothGatt.GATT_SUCCESS) "成功" else "失败($status)"}")
            // 订阅完成后才协商 MTU（串行；即使失败也不影响基本收发）
            gatt.requestMtu(517)
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

    private fun writeNext() {
        val gatt = bluetoothGatt
        val char = charFromCentral
        if (gatt == null || char == null || !connected) {
            sendQueue.clear(); writing = false; return
        }
        if (sendQueue.isEmpty()) { writing = false; return }
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

    private fun handleReceivedMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type", "notif")) {
                "hello" -> {
                    remoteName = obj.optString("device", "").ifBlank { remoteName }
                    remoteBattery = obj.optInt("battery", -1)
                    notifyState()
                    log("握手：远端 ${remoteName.ifBlank { "未知" }} 电量 $remoteBattery%")
                }
                "status" -> {
                    remoteBattery = obj.optInt("battery", -1)
                    notifyState()
                }
                else -> {
                    log("收到远程通知")
                    val device = obj.optString("device", "")
                    val app = obj.optString("app", "远程")
                    val title = obj.optString("title", "")
                    val text = obj.optString("text", "")
                    val key = obj.optString("key", "")
                    val ongoing = obj.optBoolean("ongoing", false)
                    // 若握手丢失，从通知里也能学到远端名
                    if (device.isNotBlank() && remoteName.isBlank()) {
                        remoteName = device
                        notifyState()
                    }
                    postLocalNotification(device, app, title, text, key, ongoing)
                }
            }
        } catch (e: Exception) {
            log("解析失败：${e.message}")
        }
    }

    private fun postLocalNotification(device: String, app: String, title: String, text: String, key: String, ongoing: Boolean) {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        ensureChannels(nm)

        // 常驻/不可清除类通知进单独通道（默认静默），方便在系统设置里单独管理
        val channelId = if (ongoing) Constants.CHANNEL_ID_ONGOING else Constants.CHANNEL_ID

        // 用通知 key 的 hash 作为稳定 id：同一条通知的更新会覆盖同一条，而不是堆积新通知
        val id = if (key.isNotEmpty()) key.hashCode() else notifId++

        // 标题格式：<远端设备名> | <应用名> | <通知标题>（空段自动省略）
        val titleLine = listOf(device, app, title).filter { it.isNotBlank() }.joinToString(" | ")

        val n = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleLine)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
        log("已弹出本地通知：$titleLine（${if (ongoing) "常驻" else "普通"}通道）")
    }

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(Constants.CHANNEL_ID, "流转的通知", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_ID_ONGOING,
                "流转的常驻通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台/常驻类通知（如场景调度、状态栏常驻），默认静默，可在系统设置中单独管理" }
        )
    }
}
