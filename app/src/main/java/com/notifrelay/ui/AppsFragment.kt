package com.notifrelay.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.notifrelay.R
import com.notifrelay.SettingsRepository
import com.notifrelay.databinding.FragmentAppsBinding

data class AppInfo(val pkg: String, val label: String, val icon: Drawable?)

class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    private val repo get() = SettingsRepository.get(requireContext())

    private val allApps = mutableListOf<AppInfo>()
    private var adapter: AppAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchOnlyWhitelist.isChecked = repo.onlyWhitelist
        binding.switchOnlyWhitelist.setOnCheckedChangeListener { _, checked ->
            repo.onlyWhitelist = checked
            updateHint()
        }
        updateHint()

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                adapter?.applyFilter(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnAllOn.setOnClickListener {
            repo.setWhitelistForAll(allApps.map { it.pkg }, true)
            adapter?.notifyDataSetChanged()
            Toast.makeText(requireContext(), "已全部开启", Toast.LENGTH_SHORT).show()
        }
        binding.btnAllOff.setOnClickListener {
            repo.setWhitelistForAll(allApps.map { it.pkg }, false)
            adapter?.notifyDataSetChanged()
            Toast.makeText(requireContext(), "已全部关闭", Toast.LENGTH_SHORT).show()
        }

        loadAppsAsync()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateHint() {
        binding.tvHint.text = if (repo.onlyWhitelist)
            "当前仅转发下方勾选的应用"
        else
            "当前转发全部应用；开启上方开关后，仅转发下方勾选的应用"
    }

    private fun loadAppsAsync() {
        val ctx = requireContext().applicationContext
        Thread {
            val apps = loadApps(ctx)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                allApps.clear()
                allApps.addAll(apps)
                adapter = AppAdapter(requireContext(), repo, allApps)
                binding.listApps.adapter = adapter
                adapter?.applyFilter(binding.etSearch.text?.toString().orEmpty())
            }
        }.start()
    }

    private fun loadApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        return ris.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            val icon = try {
                ri.loadIcon(pm)
            } catch (e: Exception) {
                null
            }
            AppInfo(pkg, label, icon)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }
}

/**
 * 应用列表适配器：图标 + 名称 + 转发开关。
 * 开关状态绑定白名单（不依赖「仅转发」模式开关，模式开关只改变过滤行为）。
 */
class AppAdapter(
    private val context: Context,
    private val repo: SettingsRepository,
    private val apps: List<AppInfo>
) : BaseAdapter() {

    private val filtered = mutableListOf<AppInfo>()

    init {
        filtered.addAll(apps)
    }

    fun applyFilter(query: String) {
        filtered.clear()
        if (query.isBlank()) {
            filtered.addAll(apps)
        } else {
            val q = query.lowercase()
            filtered.addAll(apps.filter {
                it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
            })
        }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = filtered.size

    override fun getItem(position: Int): AppInfo = filtered[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val img = view.findViewById<ImageView>(R.id.img_icon)
        val label = view.findViewById<TextView>(R.id.tv_label)
        val switch = view.findViewById<SwitchMaterial>(R.id.switch_app)

        val app = filtered[position]
        img.setImageDrawable(app.icon)
        label.text = app.label

        // ListView 复用会串状态：先解绑监听再设置，避免 setChecked 触发旧回调
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = repo.isAppInWhitelist(app.pkg)
        switch.setOnCheckedChangeListener { _, checked ->
            repo.setAppEnabled(app.pkg, checked)
        }
        return view
    }
}
