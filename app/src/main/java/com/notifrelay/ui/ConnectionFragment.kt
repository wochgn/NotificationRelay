package com.notifrelay.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.notifrelay.BleRelayManager
import com.notifrelay.EventLog
import com.notifrelay.R
import com.notifrelay.databinding.FragmentConnectionBinding

class ConnectionFragment : Fragment() {

    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!

    private val manager get() = BleRelayManager.get(requireContext())

    private val logListener: (String) -> Unit = { line ->
        activity?.runOnUiThread { appendLog(line) }
    }

    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        EventLog.add(if (granted) "权限已授予" else "部分权限被拒绝")
        updateStatus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPermissions.setOnClickListener { requestPermissions() }
        binding.btnListener.setOnClickListener { openListenerSettings() }
        binding.btnPeripheral.setOnClickListener {
            if (hasAllPermissions()) manager.startPeripheral() else requestPermissions()
        }
        binding.btnCentral.setOnClickListener {
            if (hasAllPermissions()) manager.startCentral() else requestPermissions()
        }
        binding.btnStop.setOnClickListener { manager.stopAll() }
    }

    override fun onResume() {
        super.onResume()
        EventLog.observe(logListener)
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        EventLog.remove(logListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun requestPermissions() {
        permLauncher.launch(requiredPermissions)
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

    private fun openListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().packageName)

    private fun updateStatus() {
        val listener = if (isListenerEnabled()) "已开启" else "未开启"
        val perms = if (hasAllPermissions()) "已授予" else "未授予"
        val role = when (manager.role) {
            BleRelayManager.Role.PERIPHERAL -> "外设(广播)"
            BleRelayManager.Role.CENTRAL -> "中心(扫描)"
            else -> "未启动"
        }
        val conn = if (manager.connected) "已连接" else "未连接"
        binding.tvStatus.text = "通知使用权：$listener\n权限：$perms\n角色：$role（$conn）"
    }

    private fun appendLog(line: String) {
        binding.tvLog.append(line + "\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
