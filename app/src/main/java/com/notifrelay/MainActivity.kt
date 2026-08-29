package com.notifrelay

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import com.notifrelay.databinding.ActivityMainBinding
import com.notifrelay.ui.AppsFragment
import com.notifrelay.ui.DevicesFragment
import com.notifrelay.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pairingDialog: androidx.appcompat.app.AlertDialog? = null
    private val relayManager get() = BleRelayManager.get(this)
    private val stateListener: (RelayState) -> Unit = {
        runOnUiThread { updatePairingDialog() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Material 3 动态取色（Android 12+ 跟随壁纸换色，ColorOS/HyperOS 均支持）
        DynamicColors.applyToActivityIfAvailable(this)
        SystemBars.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // edge-to-edge：内容避开系统栏，避免被状态栏/导航栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 若用户开启了常驻后台，启动前台服务保活
        if (SettingsRepository.get(this).foregroundEnabled) {
            ForegroundServiceController.start(this)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_devices -> switchTo("设备", DevicesFragment())
                R.id.nav_apps -> switchTo("应用", AppsFragment())
                R.id.nav_settings -> switchTo("设置", SettingsFragment())
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_devices
        }
    }

    override fun onStart() {
        super.onStart()
        relayManager.observeState(stateListener)
        updatePairingDialog()
    }

    override fun onStop() {
        pairingDialog?.dismiss()
        pairingDialog = null
        relayManager.removeState(stateListener)
        super.onStop()
    }

    private fun updatePairingDialog() {
        if (!relayManager.connected || !relayManager.needsLocalPairConfirmation()) {
            pairingDialog?.dismiss()
            pairingDialog = null
            return
        }
        if (pairingDialog?.isShowing == true) return

        val name = relayManager.remoteName.ifBlank { "附近设备" }
        pairingDialog = MaterialAlertDialogBuilder(this)
            .setTitle("配对请求")
            .setMessage("「$name」希望建立通知流转连接。请确认两台设备上显示的名称一致。")
            .setPositiveButton("确认配对") { _, _ -> relayManager.acceptPairing() }
            .setNegativeButton("拒绝") { _, _ -> relayManager.rejectPairing() }
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun switchTo(title: String, fragment: Fragment) {
        binding.topAppBar.title = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
