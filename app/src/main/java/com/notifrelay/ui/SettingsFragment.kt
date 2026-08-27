package com.notifrelay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.notifrelay.DeviceInfo
import com.notifrelay.EventLog
import com.notifrelay.ForegroundServiceController
import com.notifrelay.R
import com.notifrelay.SettingsRepository
import com.notifrelay.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val repo get() = SettingsRepository.get(requireContext())

    private val logListener: (String) -> Unit = { line ->
        activity?.runOnUiThread { appendLog(line) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etDeviceName.setText(repo.resolvedDeviceName())

        binding.btnSaveName.setOnClickListener {
            val name = binding.etDeviceName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(requireContext(), "设备名不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            repo.deviceName = name
            Toast.makeText(requireContext(), "已保存设备名：$name", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetName.setOnClickListener {
            repo.deviceName = null
            binding.etDeviceName.setText(DeviceInfo.systemName(requireContext()))
            Toast.makeText(requireContext(), "已恢复为系统设备名", Toast.LENGTH_SHORT).show()
        }

        binding.switchForeground.isChecked = repo.foregroundEnabled
        binding.switchForeground.setOnCheckedChangeListener { _, checked ->
            repo.foregroundEnabled = checked
            if (checked) ForegroundServiceController.start(requireContext())
            else ForegroundServiceController.stop(requireContext())
            Toast.makeText(
                requireContext(),
                if (checked) "常驻后台已开启" else "常驻后台已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        EventLog.observe(logListener)
    }

    override fun onPause() {
        super.onPause()
        EventLog.remove(logListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun appendLog(line: String) {
        binding.tvLog.append(line + "\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
