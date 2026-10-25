package com.tvplayer.app.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.Button
import android.widget.TextView
import android.view.Gravity
import android.view.KeyEvent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceDataStore
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.tvplayer.app.util.ChannelUserState
import androidx.recyclerview.widget.RecyclerView
import com.tvplayer.app.R
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.DebugReporter
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.util.SystemSettingsLauncher
import com.tvplayer.app.util.AppVersion
import com.tvplayer.app.util.PlaybackControls
import com.tvplayer.app.util.TvDialogStyler
import com.tvplayer.app.util.TvSingleChoiceDialog
import com.tvplayer.app.util.DeviceProfile
import com.tvplayer.app.ui.subscription.SubscriptionManagementActivity

class SettingsActivity : FragmentActivity() {
    private val tv by lazy { DeviceProfile.isTv(this) }
    private var section: SettingsSection? = null
    private val sectionViews = linkedMapOf<SettingsSection, View>()
    private val settingsFragment get() = supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!tv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setContentView(R.layout.activity_settings)
        section = SettingsSection.fromKey(savedInstanceState?.getString("section"))
            ?: if (tv) SettingsSection.PLAYBACK else null
        findViewById<View>(R.id.back_to_player_button).setOnClickListener { onBackPressed() }
        findViewById<android.view.View>(R.id.save_settings_button).setOnClickListener {
            finish()
        }
        if (!tv) {
            findViewById<LinearLayout>(R.id.settings_body).orientation = LinearLayout.VERTICAL
            findViewById<View>(R.id.settings_navigation).layoutParams = LinearLayout.LayoutParams(-1, -1)
            findViewById<View>(R.id.settings_container).layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        val navigation = findViewById<LinearLayout>(R.id.settings_sections)
        SettingsSection.values().forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                contentDescription = "${item.title}，${item.description}"
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundResource(R.drawable.settings_section_background)
                addView(TextView(context).apply {
                    text = item.title; textSize = if (tv) 19f else 18f
                    setTextColor(0xFFF5FAFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = item.description; textSize = if (tv) 12f else 13f
                    setTextColor(0xFF9FB5C9.toInt())
                })
                setOnClickListener { selectSection(item, enter = true) }
                if (tv) {
                    setOnFocusChangeListener { _, focused -> if (focused) selectSection(item) }
                    setOnKeyListener { _, code, event ->
                        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                            selectSection(item, enter = true); true
                        } else false
                    }
                }
            }
            navigation.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            sectionViews[item] = card
        }
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commitNow()
        }
        renderSection()
        if (tv) sectionViews[section]?.requestFocus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun selectSection(value: SettingsSection, enter: Boolean = false) {
        section = value
        renderSection()
        if (tv && enter) settingsFragment?.focusFirstSetting()
    }

    private fun renderSection() {
        findViewById<TextView>(R.id.settings_title).text = section?.title ?: "设置"
        findViewById<TextView>(R.id.settings_subtitle).text = if (tv) "↑↓ 选择分类 · → 进入设置 · 返回键回到分类"
            else section?.description ?: "按分类查找，所有修改即时保存"
        findViewById<View>(R.id.settings_navigation).visibility = if (tv || section == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.settings_container).visibility = if (tv || section != null) View.VISIBLE else View.GONE
        sectionViews.forEach { (item, view) -> view.isSelected = item == section }
        settingsFragment?.showSection(section ?: SettingsSection.PLAYBACK)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!tv && section != null) { section = null; renderSection(); return }
        if (tv && findViewById<View>(R.id.settings_container).hasFocus()) {
            sectionViews[section]?.requestFocus(); return
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("section", section?.key)
        super.onSaveInstanceState(outState)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val tv get() = DeviceProfile.isTv(requireContext())
        private lateinit var preferencesHelper: PreferencesHelper
        private val timerHandler = Handler(Looper.getMainLooper())
        private val timerSummaryRefresh = object : Runnable {
            override fun run() {
                if (!isAdded) return
                updateSleepTimerSummary()
                timerHandler.postDelayed(this, 10_000L)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = Constants.PREFS_NAME
            preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE
            val context = requireContext()
            // Migrate the old string-valued EPG count before SeekBarPreference
            // reads the new integer key from SharedPreferences.
            preferencesHelper = PreferencesHelper(context)
            setPreferencesFromResource(R.xml.preferences, rootKey)

            bindUserUiSettings()
            bindRefreshSettings()
            bindSleepTimer()
            bindDiagnosticsSettings()
            bindSystemSettings()
            findPreference<Preference>("app_version")?.summary = "当前安装版本 ${AppVersion.label(requireContext())}"
            val picture = findPreference<Preference>("default_resize_mode")
            if (picture != null) {
                findPreference<PreferenceGroup>("section_playback")?.removePreference(picture)
                picture.order = -10
                findPreference<PreferenceGroup>("section_interface")?.addPreference(picture)
            }
            // The app currently has one contrast-tested dark design, not a working theme engine.
            findPreference<Preference>("ui_theme")?.isVisible = false
            findPreference<ListPreference>("video_quality")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            fun configure(group: PreferenceGroup) {
                for (index in 0 until group.preferenceCount) {
                    group.getPreference(index).apply {
                        isIconSpaceReserved = false
                        if (this is PreferenceGroup) configure(this)
                    }
                }
            }
            configure(preferenceScreen)
            val network = findPreference<PreferenceGroup>("section_network")
            network?.addPreference(Preference(context).apply {
                key = "manage_subscriptions"; title = "节目源管理"; summary = "新增、编辑、手机扫码与源检测"; order = -1
                setOnPreferenceClickListener { startActivity(Intent(context, SubscriptionManagementActivity::class.java)); true }
            })
            findPreference<PreferenceGroup>("section_about")?.addPreference(Preference(context).apply {
                key = "device_summary"; title = if (tv) "电视设备" else "手机 / 平板"
                summary = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}"
                isSelectable = false; isIconSpaceReserved = false
            })
            if (!tv) listOf("open_developer_options", "check_root_access", "boot_start_enabled").forEach { findPreference<Preference>(it)?.isVisible = false }
            showSection(SettingsSection.fromKey((activity as? SettingsActivity)?.section?.key) ?: SettingsSection.PLAYBACK)
        }

        internal fun showSection(section: SettingsSection) {
            SettingsSection.values().forEach { item -> findPreference<Preference>(item.key)?.isVisible = item == section }
        }

        internal fun focusFirstSetting() {
            if (view == null) return
            listView.post {
                for (index in 0 until listView.childCount) {
                    val row = listView.getChildAt(index)
                    if (row.isFocusable && row.isEnabled && row.requestFocus()) break
                }
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is ListPreference) {
                showListPreferenceDialog(preference)
                return
            }
            val builder = AlertDialog.Builder(requireContext()).setTitle(preference.title).setNegativeButton("取消", null)
            val dialog = when (preference) {
                is EditTextPreference -> {
                    val input = EditText(requireContext()).apply {
                        setText(preference.text); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        setTextColor(0xFFF5FAFF.toInt()); setPadding(dp(20), dp(14), dp(20), dp(14))
                    }
                    builder.setView(input).setPositiveButton("保存") { _, _ ->
                        val value = input.text.toString().trim()
                        if (preference.callChangeListener(value)) preference.text = value
                    }.create().also { input.post { prepareKeyboardInput(input) } }
                }
                else -> { super.onDisplayPreferenceDialog(preference); return }
            }
            TvDialogStyler.apply(dialog)
            dialog.setOnDismissListener { focusPreference(preference.key) }
            dialog.show()
        }

        /** A single explicit checked row plus a separate high-contrast D-pad focus state. */
        private fun showListPreferenceDialog(preference: ListPreference) {
            val entries = preference.entries
            val values = preference.entryValues
            if (entries.isEmpty() || values.isEmpty()) return

            val selectedIndex = preference.findIndexOfValue(preference.value)
                .takeIf { it in entries.indices } ?: 0
            TvSingleChoiceDialog.show(
                context = requireContext(),
                title = preference.title ?: preference.key,
                entries = entries,
                selectedIndex = selectedIndex,
                onDismiss = { focusPreference(preference.key) },
                onSelected = { index ->
                    val value = values[index].toString()
                    if (preference.callChangeListener(value)) {
                        preference.value = value
                        true
                    } else false
                }
            )
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // Preference's default focus effect is almost invisible on many TV
            // firmwares.  Give every actionable row a large, high-contrast
            // focus surface so D-pad navigation remains obvious from a sofa.
            listView.apply {
                clipToPadding = false
                setPadding(dp(if (tv) 18 else 12), dp(10), dp(if (tv) 18 else 12), dp(18))
                addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = decorateTvFocus(view)
                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
                setOnKeyListener { _, code, event ->
                    if (tv && code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN &&
                        findFocus()?.findViewById<View>(androidx.preference.R.id.seekbar) == null) {
                        (activity as? SettingsActivity)?.let { it.sectionViews[it.section]?.requestFocus() }; true
                    } else false
                }
            }
        }

        private fun decorateTvFocus(row: View) {
            // PreferenceCategory headings are not focusable; leave their visual
            // hierarchy intact and decorate actual setting rows only.
            if (!row.isFocusable || row.findViewById<View>(android.R.id.title) == null) return
            row.background = ContextCompat.getDrawable(requireContext(), if (tv) R.drawable.tv_preference_row_background else R.drawable.settings_section_background)
            row.findViewById<TextView>(android.R.id.title)?.apply { textSize = if (tv) 20f else 16f; setTextColor(0xFFF5FAFF.toInt()) }
            row.findViewById<TextView>(android.R.id.summary)?.apply { textSize = if (tv) 15f else 13f; setTextColor(0xFF9FB5C9.toInt()) }
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()

        private fun bindUserUiSettings() {
            val state = ChannelUserState.get(requireContext())
            val store = object : PreferenceDataStore() {
                override fun putBoolean(key: String, value: Boolean) { state.prefs.edit().putBoolean(key, value).apply() }
                override fun getBoolean(key: String, defValue: Boolean) = if (key == "show_current_channel_name") state.showName else state.prefs.getBoolean(key, defValue)
                override fun putString(key: String, value: String?) { state.prefs.edit().putString(key, value).apply() }
                override fun getString(key: String, defValue: String?) = state.prefs.getString(key, defValue)
                override fun putInt(key: String, value: Int) {
                    if (key == "recent_history_limit") state.setHistoryLimit(value)
                    else state.prefs.edit().putInt(key, value).apply()
                }
                override fun getInt(key: String, defValue: Int) = state.prefs.getInt(key, defValue)
            }
            listOf("show_current_channel_name", "show_current_time", "show_version_badge").forEach { key ->
                findPreference<SwitchPreferenceCompat>(key)?.apply {
                    preferenceDataStore = store
                    isChecked = store.getBoolean(key, true)
                }
            }
            findPreference<ListPreference>("default_resize_mode")?.apply {
                preferenceDataStore = store
                value = state.resizeMode.toString()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            findPreference<SeekBarPreference>("channel_name_transparency")?.apply {
                preferenceDataStore = store
                value = state.transparency
                summary = "${value}%；0% 不透明，100% 完全透明" + if (tv) "（方向左右键调整）" else ""
                setOnPreferenceChangeListener { _, next ->
                    summary = "${next}%；0% 不透明，100% 完全透明" + if (tv) "（方向左右键调整）" else ""; true
                }
            }
            findPreference<SeekBarPreference>("recent_history_limit")?.apply {
                preferenceDataStore = store
                value = state.historyLimit
                summary = "保留最近正常播放的 ${value} 个频道；减少数量会裁剪较早记录"
                setOnPreferenceChangeListener { _, next ->
                    summary = "保留最近正常播放的 ${next} 个频道；减少数量会裁剪较早记录"; true
                }
            }
            listOf(
                "channel_drawer_group_width" to (150..400),
                "channel_drawer_list_width" to (250..600)
            ).forEach { (key, range) ->
                findPreference<SeekBarPreference>(key)?.apply {
                    isVisible = tv
                    summary = "当前 ${value}dp；每步 5dp，范围 ${range.first}–${range.last}dp"
                    setOnPreferenceChangeListener { _, next ->
                        summary = "当前 ${next}dp；每步 5dp，范围 ${range.first}–${range.last}dp"
                        true
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            timerHandler.removeCallbacks(timerSummaryRefresh)
            timerSummaryRefresh.run()
        }

        override fun onPause() {
            timerHandler.removeCallbacks(timerSummaryRefresh)
            super.onPause()
        }

        private fun bindSleepTimer() {
            findPreference<Preference>("sleep_timer")?.setOnPreferenceClickListener {
                showSleepTimerDialog()
                true
            }
            findPreference<Preference>("cancel_sleep_timer")?.setOnPreferenceClickListener {
                val success = PlaybackControls.cancelTimer(requireContext())
                Toast.makeText(requireContext(), if (success) "已取消定时关闭" else "取消失败，请重试", Toast.LENGTH_SHORT).show()
                updateSleepTimerSummary()
                focusPreference("sleep_timer")
                true
            }
            updateSleepTimerSummary()
        }

        private fun updateSleepTimerSummary() {
            val state = PlaybackControls.snapshot(requireContext()) ?: return
            val active = !state.getString("token").isNullOrEmpty()
            val remaining = (state.getLong("deadline") - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val minutes = (remaining + 59_999L) / 60_000L
            findPreference<Preference>("sleep_timer")?.summary = if (active) {
                "约 ${minutes / 60} 小时 ${minutes % 60} 分钟后停止播放并退出；点击可修改"
            } else "未开启；设置几小时几分钟后停止播放并退出应用，不关闭设备"
            findPreference<Preference>("cancel_sleep_timer")?.isEnabled = active
        }

        private fun showSleepTimerDialog() {
            val state = PlaybackControls.snapshot(requireContext())
            val lastMinutes = ((state?.getLong("lastDuration") ?: 3_600_000L) / 60_000L).toInt()
            var hours = (lastMinutes / 60).coerceIn(0, 23)
            // Keep legacy timer values on the new five-minute grid.
            var minutes = ((((lastMinutes % 60) + 2) / 5) * 5).coerceIn(0, 55)
            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), dp(8))
            }
            fun addCounter(unit: String, maximum: Int, initial: Int, step: Int = 1, update: (Int) -> Unit) {
                var value = initial
                val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                val label = TextView(requireContext()).apply {
                    text = "$value $unit"; textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                fun button(textLabel: String, delta: Int) = Button(requireContext()).apply {
                    text = textLabel
                    contentDescription = (if (delta < 0) "减少" else "增加") + unit
                    setOnClickListener {
                        value = (value + delta * step).coerceIn(0, maximum)
                        label.text = "$value $unit"
                        update(value)
                    }
                }
                row.addView(button("−", -1), LinearLayout.LayoutParams(dp(68), dp(54)))
                row.addView(label, LinearLayout.LayoutParams(0, dp(62), 1f))
                row.addView(button("+", 1), LinearLayout.LayoutParams(dp(68), dp(54)))
                content.addView(row)
            }
            addCounter("小时", 23, hours) { hours = it }
            addCounter("分钟", 55, minutes, step = 5) { minutes = it }
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("定时关闭（最长 23 小时 55 分钟）")
                .setMessage("使用遥控器或点击 − / + 调整，分钟按 5 分钟递增。本次倒计时不会因换台而重置；到点退出应用，不关闭设备。")
                .setView(content)
                .setNegativeButton("返回", null)
                .setPositiveButton("开始计时", null)
                .create()
            TvDialogStyler.apply(dialog, DialogInterface.BUTTON_POSITIVE)
            dialog.setOnDismissListener { focusPreference("sleep_timer") }
            dialog.show()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (hours == 0 && minutes == 0) {
                    Toast.makeText(requireContext(), "请设置至少 1 分钟", Toast.LENGTH_SHORT).show()
                } else if (PlaybackControls.startTimer(requireContext(), hours, minutes)) {
                    updateSleepTimerSummary()
                    Toast.makeText(requireContext(), "将在 $hours 小时 $minutes 分钟后退出", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else Toast.makeText(requireContext(), "设置失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }

        private fun focusPreference(key: String) {
            if (!isAdded || view == null) return
            val preferencesList = listView
            // Enabling/disabling the cancel row triggers a RecyclerView rebind.
            // Restore the actual setting row AFTER the dialog returns window focus.
            preferencesList.postDelayed({
                if (!isAdded || !isResumed || view == null) return@postDelayed
                val position = (listView.adapter as? PreferenceGroup.PreferencePositionCallback)
                    ?.getPreferenceAdapterPosition(key) ?: return@postDelayed
                if (position < 0) return@postDelayed
                preferencesList.doOnNextLayout {
                    if (isAdded && isResumed && view != null) {
                        preferencesList.findViewHolderForAdapterPosition(position)?.itemView?.apply {
                            // A phone tap leaves touch mode enabled; a normal
                            // TV row cannot receive programmatic focus in that mode.
                            if (tv) { isFocusableInTouchMode = true; requestFocus() }
                        }
                    }
                }
                preferencesList.scrollToPosition(position)
                preferencesList.requestLayout()
            }, 300L)
        }

        private fun bindRefreshSettings() {
            findPreference<Preference>("refresh_interval")?.apply {
                summary = getPlaylistRefreshIntervalLabel(preferencesHelper.refreshInterval)
                setOnPreferenceClickListener {
                    showRefreshIntervalDialog(
                        title = "播放列表刷新间隔",
                        entries = resources.getStringArray(R.array.refresh_interval_entries),
                        values = resources.getStringArray(R.array.refresh_interval_values),
                        currentValue = preferencesHelper.refreshInterval
                    ) { interval ->
                        preferencesHelper.refreshInterval = interval
                        summary = getPlaylistRefreshIntervalLabel(interval)
                    }
                    true
                }
            }
        }

        private fun getPlaylistRefreshIntervalLabel(interval: Long): String {
            return when (interval) {
                Constants.REFRESH_INTERVAL_5_MINUTES -> "5 分钟"
                15 * 60 * 1000L -> "15 分钟"
                30 * 60 * 1000L -> "30 分钟"
                Constants.REFRESH_INTERVAL_1_HOUR -> "1 小时"
                Constants.REFRESH_INTERVAL_3_HOURS -> "3 小时"
                Constants.REFRESH_INTERVAL_6_HOURS -> "6 小时"
                Constants.REFRESH_INTERVAL_12_HOURS -> "12 小时"
                Constants.REFRESH_INTERVAL_24_HOURS -> "24 小时"
                else -> "${interval / 60_000} 分钟"
            }
        }

        private fun bindDiagnosticsSettings() {
            findPreference<EditTextPreference>("diagnostics_server_url")?.apply {
                setOnBindEditTextListener { prepareKeyboardInput(it) }
                summary = text?.takeIf { it.isNotBlank() }
                    ?: "留空时只保存在设备本地；如需上报，请手动填写你自己的服务地址"
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = newValue?.toString()?.trim().orEmpty()
                    preference.summary = value.ifBlank { "留空时只保存在设备本地；如需上报，请手动填写你自己的服务地址" }
                    true
                }
            }
            findPreference<EditTextPreference>("remote_source_server_url")?.apply {
                setOnBindEditTextListener { prepareKeyboardInput(it) }
                summary = text?.takeIf { it.isNotBlank() } ?: "节目源二维码由电视本机提供，无需电脑服务；留空即可"
                setOnPreferenceChangeListener { preference, newValue ->
                    val value = newValue?.toString()?.trim().orEmpty().trimEnd('/')
                    preference.summary = value.ifBlank { "节目源二维码由电视本机提供，无需电脑服务；留空即可" }
                    true
                }
            }
            findPreference<Preference>("export_diagnostics")?.setOnPreferenceClickListener {
                val path = runCatching { DebugReporter.diagnosticsDirectory().absolutePath }
                    .getOrDefault("诊断目录暂不可用")
                AlertDialog.Builder(requireContext())
                    .setTitle("诊断日志目录")
                    .setMessage(path)
                    .setPositiveButton("确定", null)
                    .show()
                true
            }
            findPreference<Preference>("clear_diagnostics")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("清除诊断日志")
                    .setMessage("确定删除应用内已保存的诊断文件吗？")
                    .setPositiveButton("删除") { _, _ ->
                        val success = DebugReporter.clearDiagnostics()
                        showToast(if (success) "诊断日志已清除" else "清除失败")
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        private fun bindSystemSettings() {
            findPreference<Preference>("open_developer_options")?.setOnPreferenceClickListener {
                val result = SystemSettingsLauncher.openDeveloperOptions(requireContext())
                showToast("${result.message}（${result.method}）")
                true
            }
            findPreference<Preference>("open_system_settings")?.setOnPreferenceClickListener {
                val result = SystemSettingsLauncher.openSystemSettings(requireContext())
                showToast("${result.message}（${result.method}）")
                true
            }
            findPreference<Preference>("check_root_access")?.setOnPreferenceClickListener {
                val available = SystemSettingsLauncher.isRootAvailable()
                showToast(if (available) "检测到 su，设备可能已有 Root" else "未检测到可用 Root 权限")
                true
            }
        }


        private fun showRefreshIntervalDialog(
            title: String,
            entries: Array<String>,
            values: Array<String>,
            currentValue: Long,
            onSelected: (Long) -> Unit
        ) {
            val selectedIndex = values.indexOfFirst { it.toLongOrNull() == currentValue }.coerceAtLeast(0)
            TvSingleChoiceDialog.show(
                context = requireContext(),
                title = title,
                entries = entries,
                selectedIndex = selectedIndex,
                onSelected = { which ->
                    val interval = values.getOrNull(which)?.toLongOrNull()
                    if (interval != null) { onSelected(interval); true } else false
                }
            )
        }

        private fun showToast(message: String) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }

        private fun prepareKeyboardInput(input: EditText) {
            input.isFocusable = true
            input.isFocusableInTouchMode = true
            input.isSingleLine = true
            input.post {
                input.requestFocus()
                input.setSelection(input.text.length)
                val manager = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
