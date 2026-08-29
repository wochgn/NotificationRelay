package com.notifrelay.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import com.notifrelay.BleRelayManager
import com.notifrelay.DiscoveryState
import com.notifrelay.R
import com.notifrelay.RelayState
import com.notifrelay.SettingsRepository
import com.notifrelay.databinding.FragmentDevicesBinding
import org.json.JSONObject

/**
 * 「设备」页：扫描 & 配对。状态卡展示蓝牙/通知监听/常驻后台状态，
 * 连接后展示远端设备名 / Android 版本 / 电量和连接测试。
 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private val manager get() = BleRelayManager.get(requireContext())
    private val repo get() = SettingsRepository.get(requireContext())

    private var lastDiscovery = DiscoveryState(false, emptyList())
    private var adapter: DeviceAdapter? = null
    private var wasConnected = false
    private var manualDisconnect = false

    private val stateListener: (RelayState) -> Unit = { state ->
        activity?.runOnUiThread {
            val nowConnected = state.connected
            val justDisconnected = wasConnected && !nowConnected
            val userDisconnect = manager.consumeUserDisconnectEvent()
            wasConnected = nowConnected
            if (justDisconnected) {
                if (manualDisconnect || userDisconnect) {
                    binding.root.postDelayed({
                        if (_binding != null && manager.role == BleRelayManager.Role.NONE) {
                            manager.startDiscovery(autoConnectSaved = false)
                        }
                    }, 900L)
                } else {
                    manager.startDiscovery()
                }
            }
            manualDisconnect = false
            refresh()
        }
    }
    private val discoveryListener: (DiscoveryState) -> Unit = { s ->
        lastDiscovery = s
        activity?.runOnUiThread { refresh() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnScan.setOnClickListener {
            manager.startDiscovery(autoConnectSaved = !manager.isAutoReconnectPaused())
        }
        binding.btnDisconnect.setOnClickListener {
            manualDisconnect = true
            manager.disconnect()
            refresh()
        }
        binding.btnUnpair.setOnClickListener {
            manager.remoteDeviceId.takeIf { it.isNotBlank() }?.let(::confirmDelete)
        }
        binding.btnFindDevice.setOnClickListener {
            if (manager.findingRemote) {
                manager.cancelFindRemote()
            } else if (manager.findRemoteDevice()) {
                Toast.makeText(requireContext(), "已让远端设备响铃", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnTestNotification.setOnClickListener { sendTestNotification() }
    }

    override fun onResume() {
        super.onResume()
        wasConnected = manager.visibleConnected()
        manager.observeState(stateListener)
        manager.observeDiscovery(discoveryListener)
        if (!manager.visibleConnected()) {
            manager.startDiscovery(autoConnectSaved = !manager.isAutoReconnectPaused())
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        manager.removeState(stateListener)
        manager.removeDiscovery(discoveryListener)
        // 常驻后台开启时由前台服务继续扫描并负责已配对设备自动回连。
        if (!repo.foregroundEnabled) manager.stopDiscovery()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        // 状态卡
        val bluetoothEnabled = isBluetoothEnabled()
        val listenerEnabled = isListenerEnabled()
        val serviceEnabled = repo.foregroundEnabled
        binding.tvBtStatus.text = if (bluetoothEnabled) "已开启" else "未开启"
        binding.tvListenerStatus.text = if (listenerEnabled) "已开启" else "未开启"
        binding.tvServiceStatus.text = if (serviceEnabled) "已开启" else "未开启"
        binding.cardStatus.visibility =
            if (bluetoothEnabled && listenerEnabled && serviceEnabled) View.GONE else View.VISIBLE

        // 已连接设备卡
        val connected = manager.visibleConnected()
        binding.cardConnected.visibility = if (connected) View.VISIBLE else View.GONE
        binding.cardTestNotification.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) {
            binding.tvRemoteName.text = manager.remoteName.ifBlank { "未知设备" }
            val android = manager.remoteAndroid.ifBlank { "版本未知" }
            val battery = if (manager.remoteBattery >= 0) "电量 ${manager.remoteBattery}%" else "电量未知"
            val state = if (manager.pairingRequired) "等待配对确认" else "$android · $battery"
            binding.tvRemoteMeta.text = state
            binding.btnFindDevice.text = if (manager.findingRemote) "取消查找" else "查找设备"
        }

        // 已连接后不再展示扫描入口和设备列表，避免与当前连接状态产生歧义。
        val scanning = lastDiscovery.scanning
        val discoveryVisibility = if (connected) View.GONE else View.VISIBLE
        binding.btnScan.visibility = discoveryVisibility
        binding.tvScanHint.visibility = discoveryVisibility
        binding.listDevices.visibility = discoveryVisibility
        if (!connected) {
            binding.btnScan.isEnabled = !scanning
            binding.btnScan.text = if (scanning) "扫描中…" else "扫描设备"
            binding.tvScanHint.text = if (scanning) "正在搜索附近设备…" else ""
        }

        rebuildList()
    }

    private fun rebuildList() {
        val rows = mutableListOf<Any>()
        val saved = repo.savedDevices()
        val discovered = lastDiscovery.devices
        val discoveredByDeviceId = discovered
            .filter { it.deviceId.isNotBlank() }
            .associateBy { it.deviceId }
        val savedDeviceIds = saved.map { it.deviceId }.toSet()
        val connectedDeviceId = manager.remoteDeviceId

        if (saved.isNotEmpty()) {
            rows.add(HeaderRow("已配对设备"))
            saved.forEach { d ->
                val scan = discoveredByDeviceId[d.deviceId]
                val address = scan?.address.orEmpty()
                val isConnected = d.deviceId == connectedDeviceId
                rows.add(
                    DeviceRow(
                        d.deviceId,
                        address,
                        d.name.ifBlank { address },
                        if (isConnected || scan != null) d.android.ifBlank { address }
                        else listOf(d.android, "点击自动查找并连接").filter { it.isNotBlank() }.joinToString(" · "),
                        isConnected,
                        deletable = true
                    )
                )
            }
        }

        val nearby = discovered.filter { it.deviceId.isBlank() || it.deviceId !in savedDeviceIds }
        if (nearby.isNotEmpty()) {
            rows.add(HeaderRow("附近设备"))
            nearby.forEach { s ->
                val name = s.name.ifBlank { s.address }
                rows.add(
                    DeviceRow(
                        s.deviceId,
                        s.address,
                        name,
                        s.address,
                        s.deviceId == connectedDeviceId,
                        deletable = false
                    )
                )
            }
        }

        if (rows.isEmpty()) {
            val hint = if (lastDiscovery.scanning) "正在搜索附近设备…" else "未发现设备，点上方「扫描设备」"
            rows.add(HeaderRow(hint))
        }

        if (adapter == null) {
            adapter = DeviceAdapter(
                requireContext(),
                rows,
                { row -> requestConnection(row) },
                { deviceId -> confirmDelete(deviceId) }
            )
            binding.listDevices.adapter = adapter
        } else {
            adapter?.setRows(rows)
        }
    }

    private fun requestConnection(row: DeviceRow) {
        if (manager.visibleConnected()) return
        if (row.address.isNotBlank()) manager.connectTo(row.address)
        else if (row.deletable) manager.connectToSaved(row.deviceId)
    }

    private fun confirmDelete(deviceId: String) {
        val name = repo.findByDeviceId(deviceId)?.name ?: deviceId
        val connected = manager.visibleConnected() && manager.remoteDeviceId == deviceId
        AlertDialog.Builder(requireContext())
            .setTitle(if (connected) "取消配对" else "删除设备")
            .setMessage(
                if (connected)
                    "确定取消与「$name」的配对吗？双方将同步删除配对记录并断开连接。"
                else
                    "确定删除已配对设备「$name」吗？"
            )
            .setPositiveButton("取消配对") { _, _ ->
                manager.unpair(deviceId)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendTestNotification() {
        if (!manager.connected) {
            Toast.makeText(requireContext(), "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        val message = JSONObject().apply {
            put("type", "notif")
            put("device", repo.resolvedDeviceName())
            put("pkg", requireContext().packageName)
            put("app", getString(R.string.app_name))
            put("title", "测试通知")
            put("text", "通知流转连接正常 · $now")
            put("key", "notif-relay-test-$now")
            put("time", now)
            put("ongoing", false)
        }.toString()

        manager.sendToRemote(message)
        Toast.makeText(requireContext(), "测试通知已发送", Toast.LENGTH_SHORT).show()
    }

    private fun isBluetoothEnabled(): Boolean {
        val bm = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bm.adapter?.isEnabled == true
    }

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)
}

private data class HeaderRow(val title: String)
private data class DeviceRow(
    val deviceId: String,
    val address: String,
    val name: String,
    val subtitle: String,
    val isConnected: Boolean,
    val deletable: Boolean = false
)

private class DeviceAdapter(
    private val context: Context,
    rows: List<Any>,
    private val onConnect: (DeviceRow) -> Unit,
    private val onDelete: (String) -> Unit
) : BaseAdapter() {

    private var rows: List<Any> = rows

    fun setRows(r: List<Any>) {
        rows = r
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is HeaderRow) 0 else 1

    override fun getViewTypeCount(): Int = 2

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return if (getItemViewType(position) == 0) {
            val v = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_device_header, parent, false)
            v.findViewById<TextView>(R.id.tv_header).text = (rows[position] as HeaderRow).title
            v
        } else {
            val row = rows[position] as DeviceRow
            val v = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_device, parent, false)
            v.findViewById<TextView>(R.id.tv_name).text = row.name
            v.findViewById<TextView>(R.id.tv_subtitle).text = row.subtitle
            val state = v.findViewById<TextView>(R.id.tv_state)
            state.text = if (row.isConnected) "已连接" else "连接"
            v.setOnClickListener {
                if (row.isConnected) return@setOnClickListener
                onConnect(row)
            }
            if (row.deletable) {
                v.setOnLongClickListener { onDelete(row.deviceId); true }
            } else {
                v.setOnLongClickListener(null)
            }
            v
        }
    }
}
