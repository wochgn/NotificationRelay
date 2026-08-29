package com.notifrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import com.notifrelay.databinding.ActivityOnboardingBinding

/**
 * 首次启动引导页：引导开启通知使用权 / 蓝牙与通知权限。
 * 作为启动 Activity（见 AndroidManifest），完成后进入 MainActivity。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        SystemBars.apply(this)

        // 已完成引导：直接进主界面
        if (SettingsRepository.get(this).onboarded) {
            goToMain()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnPermissions.setOnClickListener {
            permLauncher.launch(requiredPermissions)
        }
        binding.btnDone.setOnClickListener {
            if (!isListenerEnabled() || !hasAllPermissions()) return@setOnClickListener
            SettingsRepository.get(this).onboarded = true
            goToMain()
        }
    }

    override fun onResume() {
        super.onResume()
        // 已 onboarded 的早退路径不会初始化 binding，需判空
        if (::binding.isInitialized) refresh()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun refresh() {
        val listenerEnabled = isListenerEnabled()
        val permissionsGranted = hasAllPermissions()
        binding.tvListenerStatus.text = if (listenerEnabled) "已开启" else "未开启"
        binding.tvPermissionStatus.text = if (permissionsGranted) "已授予" else "未授予"
        binding.btnDone.isEnabled = listenerEnabled && permissionsGranted
    }

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
