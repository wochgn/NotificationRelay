package com.notifrelay.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import com.notifrelay.BleRelayManager
import com.notifrelay.DiscoveryState
import com.notifrelay.R
import com.notifrelay.RelayState
import com.notifrelay.SettingsRepository
import com.notifrelay.databinding.FragmentDevicesBinding

/**
 * 「设备」页：扫描 & 配对。状态卡展示蓝牙/通知监听/常驻后台状态，
 * 连接后展示远端设备名 / Android 版本 / 电量；下方是已配对 + 附近设备列表。
 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private val manager get() = BleRelayManager.get(requireContext())
    private val repo get() = SettingsRepository.get(requireContext())

    private var lastDiscovery = DiscoveryState(false, emptyList())
    private var adapter: DeviceAdapter? = null
    private var wasConnected = false

    private val stateListener: (RelayState) -> Unit = { _ ->
        activity?.runOnUiThread {
            val nowConnected = manager.connected
            val justDisconnected = wasConnected && !nowConnected
            wasConnected = nowConnected
            if (justDisconnected) manager.startDiscovery()
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
        binding.btnScan.setOnClickListener { manager.startDiscovery() }
        binding.btnDisconnect.setOnClickListener { manager.stopAll() }
    }

    override fun onResume() {
        super.onResume()
        wasConnected = manager.connected
        manager.observeState(stateListener)
        manager.observeDiscovery(discoveryListener)
        if (!manager.connected) manager.startDiscovery()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        manager.removeState(stateListener)
        manager.removeDiscovery(discoveryListener)
        manager.stopDiscovery()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        // 状态卡
        binding.tvBtStatus.text = if (isBluetoothEnabled()) "已开启" else "未开启"
        binding.tvListenerStatus.text = if (isListenerEnabled()) "已开启" else "未开启"
        binding.tvServiceStatus.text = if (repo.foregroundEnabled) "已开启" else "未开启"

        // 已连接设备卡
        val connected = manager.connected
        binding.cardConnected.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) {
            binding.tvRemoteName.text = manager.remoteName.ifBlank { "未知设备" }
            val android = manager.remoteAndroid.ifBlank { "版本未知" }
            val battery = if (manager.remoteBattery >= 0) "电量 ${manager.remoteBattery}%" else "电量未知"
            binding.tvRemoteMeta.text = "$android · $battery"
        }

        // 扫描按钮
        val scanning = lastDiscovery.scanning
        binding.btnScan.isEnabled = !scanning && !connected
        binding.btnScan.text = if (scanning) "扫描中…" else "扫描设备"
        binding.tvScanHint.text = if (scanning) "正在搜索附近设备…" else ""

        rebuildList()
    }

    private fun rebuildList() {
        val rows = mutableListOf<Any>()
        val saved = repo.savedDevices()
        val remoteAddr = manager.remoteAddress()

        if (saved.isNotEmpty()) {
            rows.add(HeaderRow("已配对设备"))
            saved.forEach { d ->
                rows.add(
                    DeviceRow(
                        d.address,
                        d.name.ifBlank { d.address },
                        d.android.ifBlank { d.address },
                        remoteAddr == d.address,
                        deletable = true
                    )
                )
            }
        }

        val savedAddrs = saved.map { it.address }.toSet()
        val nearby = lastDiscovery.devices.filter { it.address !in savedAddrs }
        if (nearby.isNotEmpty()) {
            rows.add(HeaderRow("附近设备"))
            nearby.forEach { s ->
                val name = s.name.ifBlank { s.address }
                rows.add(DeviceRow(s.address, name, s.address, remoteAddr == s.address))
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
                { address -> if (!manager.connected) manager.connectTo(address) },
                { address -> confirmDelete(address) }
            )
            binding.listDevices.adapter = adapter
        } else {
            adapter?.setRows(rows)
        }
    }

    private fun confirmDelete(address: String) {
        val name = repo.findByAddress(address)?.name ?: address
        AlertDialog.Builder(requireContext())
            .setTitle("删除设备")
            .setMessage("确定删除已配对设备「$name」吗？")
            .setPositiveButton("删除") { _, _ ->
                repo.removeDevice(address)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
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
    val address: String,
    val name: String,
    val subtitle: String,
    val isConnected: Boolean,
    val deletable: Boolean = false
)

private class DeviceAdapter(
    private val context: Context,
    rows: List<Any>,
    private val onConnect: (String) -> Unit,
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
            v.setOnClickListener { if (!row.isConnected) onConnect(row.address) }
            if (row.deletable) {
                v.setOnLongClickListener { onDelete(row.address); true }
            } else {
                v.setOnLongClickListener(null)
            }
            v
        }
    }
}
