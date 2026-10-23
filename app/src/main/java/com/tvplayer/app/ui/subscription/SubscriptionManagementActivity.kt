package com.tvplayer.app.ui.subscription

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.room.withTransaction
import androidx.lifecycle.lifecycleScope
import com.tvplayer.app.App
import com.tvplayer.app.R
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.data.model.SubscriptionEndpoint
import com.tvplayer.app.data.repository.ChannelProbeRepository
import com.tvplayer.app.data.repository.EpgRepository
import com.tvplayer.app.data.repository.LogoRepository
import com.tvplayer.app.data.repository.PlaylistRepository
import com.tvplayer.app.data.repository.SubscriptionRepository
import com.tvplayer.app.databinding.ActivitySubscriptionManagementBinding
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.DeviceProfile
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.util.RemoteSourceManager
import com.tvplayer.app.util.TvDialogStyler
import com.tvplayer.app.util.TvFocusStyler
import kotlinx.coroutines.launch

class SubscriptionManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionManagementBinding
    private lateinit var preferences: PreferencesHelper
    private lateinit var database: AppDatabase
    private lateinit var subscriptionRepository: SubscriptionRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var epgRepository: EpgRepository
    private lateinit var logoRepository: LogoRepository
    private lateinit var channelProbeRepository: ChannelProbeRepository
    private lateinit var remoteSourceManager: RemoteSourceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = PreferencesHelper(this)
        database = AppDatabase.getDatabase(this)
        subscriptionRepository = SubscriptionRepository(this, database)
        logoRepository = LogoRepository(this, subscriptionRepository = subscriptionRepository)
        playlistRepository = PlaylistRepository(database = database, logoRepository = logoRepository)
        epgRepository = EpgRepository(this, preferences = preferences, subscriptionRepository = subscriptionRepository)
        channelProbeRepository = ChannelProbeRepository(this, database = database, preferences = preferences)
        remoteSourceManager = RemoteSourceManager(this, database, playlistRepository, preferences)

        binding.subscriptionBackButton.setOnClickListener { finish() }
        binding.subscriptionSaveButton.setOnClickListener {
            lifecycleScope.launch {
                refreshAllPlaylists()
                showToast("订阅已保存并刷新")
                finish()
            }
        }
        binding.addPlaylistSourceButton.setOnClickListener { showPlaylistDialog() }
        binding.showRemoteSourceQrButton.setOnClickListener { showRemoteSourceQrDialog() }
        binding.syncRemoteSourcesButton.setOnClickListener { syncRemoteSourceChanges() }
        binding.addEpgSourceButton.setOnClickListener { showEndpointDialog(SubscriptionEndpoint.TYPE_EPG) }
        binding.addLogoSourceButton.setOnClickListener { showEndpointDialog(SubscriptionEndpoint.TYPE_LOGO) }
        binding.editChannelProbeButton.setOnClickListener { showChannelProbeDialog() }
        binding.runChannelProbeButton.setOnClickListener {
            lifecycleScope.launch {
                channelProbeRepository.probeAllChannels(force = true)
                showToast("频道测速完成")
            }
        }
        binding.showRemoteSourceQrButton.post {
            binding.showRemoteSourceQrButton.requestFocus()
        }
        TvFocusStyler.apply(binding.root)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            renderAll()
        }
    }

    override fun onDestroy() {
        remoteSourceManager.stopLocalServer()
        super.onDestroy()
    }

    private suspend fun renderAll() {
        renderPlaylists(
            database.playlistDao().getAllPlaylistsList(),
            focusedPlaylistEntryId()
        )
        renderEndpoints(
            binding.epgSourcesContainer,
            subscriptionRepository.getEndpoints(SubscriptionEndpoint.TYPE_EPG),
            SubscriptionEndpoint.TYPE_EPG
        )
        renderEndpoints(
            binding.logoSourcesContainer,
            subscriptionRepository.getEndpoints(SubscriptionEndpoint.TYPE_LOGO),
            SubscriptionEndpoint.TYPE_LOGO
        )
        binding.channelProbeSummary.text = buildProbeSummary()
    }

    private fun renderPlaylists(playlists: List<Playlist>, focusedPlaylistId: Long? = null) {
        binding.playlistSourcesContainer.removeAllViews()
        playlists.forEach { playlist ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_subscription_entry, binding.playlistSourcesContainer, false)
            itemView.tag = playlist.id
            itemView.findViewById<TextView>(R.id.entry_title).text =
                if (playlist.isDefault) "${playlist.name} · 默认" else playlist.name
            itemView.findViewById<TextView>(R.id.entry_summary).text = playlist.url ?: "本地列表"
            itemView.findViewById<TextView>(R.id.entry_status).text = buildString {
                append(if (playlist.isActive) "已启用" else "已停用")
                append(" · ")
                append(if (playlist.autoRefreshEnabled) "自动刷新" else "手动刷新")
                append(" · ")
                append("频道数：${playlist.channelCount}")
                if (playlist.lastUpdated > 0L) append(" · 已刷新")
                if (!playlist.lastError.isNullOrBlank()) append(" · 错误：${playlist.lastError}")
            }
            val playlistError = itemView.findViewById<TextView>(R.id.entry_error_icon)
            playlistError.visibility = if (playlist.lastError.isNullOrBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
            playlistError.setOnClickListener {
                showToast("刷新失败：${playlist.lastError}")
            }
            itemView.findViewById<TextView>(R.id.entry_status).setTextColor(
                if (playlist.lastError.isNullOrBlank()) {
                    getColor(R.color.tv_focus_color)
                } else {
                    getColor(R.color.error_color)
                }
            )
            itemView.findViewById<SwitchCompat>(R.id.entry_auto_refresh_switch).apply {
                setOnCheckedChangeListener(null)
                isChecked = playlist.autoRefreshEnabled
                text = if (playlist.autoRefreshEnabled) "自动刷新" else "手动刷新"
                setOnCheckedChangeListener { _, enabled ->
                    lifecycleScope.launch {
                        database.playlistDao().update(playlist.copy(autoRefreshEnabled = enabled))
                        showToast(if (enabled) "已开启 ${playlist.name} 自动刷新" else "已关闭 ${playlist.name} 自动刷新")
                        renderAll()
                    }
                }
            }
            itemView.findViewById<SwitchCompat>(R.id.entry_enable_switch).apply {
                setOnCheckedChangeListener(null)
                isChecked = playlist.isActive
                text = if (playlist.isActive) "启用" else "停用"
                setOnCheckedChangeListener { _, enabled ->
                    lifecycleScope.launch {
                        if (enabled) {
                            database.playlistDao().update(playlist.copy(isActive = true))
                        } else {
                            // Release the parsed list too.  This makes the
                            // switch useful on a 4 GB Android TV rather than
                            // only hiding a source that still occupies RAM.
                            database.withTransaction {
                                database.channelDao().deleteChannelsByPlaylist(playlist.id)
                                database.playlistDao().update(
                                    playlist.copy(isActive = false, channelCount = 0)
                                )
                            }
                        }
                        showToast(
                            if (enabled) {
                                "已启用 ${playlist.name}，按“保存并刷新”加载频道"
                            } else {
                                "已停用 ${playlist.name}"
                            }
                        )
                        renderAll()
                    }
                }
            }
            itemView.findViewById<android.view.View>(R.id.entry_primary_button).setOnClickListener {
                showPlaylistDialog(playlist)
            }
            itemView.findViewById<android.view.View>(R.id.entry_secondary_button).setOnClickListener {
                lifecycleScope.launch {
                    testAndRefreshPlaylist(playlist)
                }
            }
            itemView.findViewById<android.view.View>(R.id.entry_delete_button).setOnClickListener {
                lifecycleScope.launch {
                    if (database.playlistDao().getAllPlaylistsList().size <= 1) {
                        showToast("至少保留一个视频源")
                    } else {
                        showDeleteConfirmation("视频源", playlist.name) {
                            lifecycleScope.launch {
                                playlistRepository.deletePlaylist(playlist.id)
                                renderAll()
                            }
                        }
                    }
                }
            }
            TvFocusStyler.apply(itemView)
            binding.playlistSourcesContainer.addView(itemView)
        }
        focusedPlaylistId?.let { id ->
            binding.playlistSourcesContainer.post {
                binding.playlistSourcesContainer.findViewWithTag<View>(id)?.requestFocus()
            }
        }
    }

    private fun focusedPlaylistEntryId(): Long? {
        var view = currentFocus ?: return null
        while (view.parent is View && view.parent !== binding.playlistSourcesContainer) {
            view = view.parent as View
        }
        return if (view.parent === binding.playlistSourcesContainer) view.tag as? Long else null
    }

    private fun showRemoteSourceQrDialog() {
        lifecycleScope.launch {
            val registration = remoteSourceManager.registerCurrentSources()
            val message = if (registration.isSuccess) {
                "电视已开启局域网编辑服务。请让手机与电视连接同一 Wi-Fi 后扫码；手机保存后，回到此页按“从手机同步修改”。"
            } else {
                "电视未能开启局域网编辑服务，请确认电视已连接 Wi-Fi。\n${registration.exceptionOrNull()?.message.orEmpty()}"
            }
            val content = LinearLayout(this@SubscriptionManagementActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(16), dp(28), dp(8))
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                runCatching { remoteSourceManager.createQrBitmap() }.getOrNull()?.let { qr ->
                    addView(ImageView(context).apply {
                        setImageBitmap(qr)
                        adjustViewBounds = true
                        contentDescription = "手机编辑节目源二维码"
                    }, LinearLayout.LayoutParams(dp(360), dp(360)))
                }
                addView(TextView(context).apply {
                    text = message
                    setTextColor(resources.getColor(android.R.color.white))
                    textSize = 15f
                    setPadding(0, dp(12), 0, dp(4))
                })
            }
            AlertDialog.Builder(this@SubscriptionManagementActivity)
                .setTitle("手机编辑节目源")
                .setView(content)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun syncRemoteSourceChanges() {
        lifecycleScope.launch {
            binding.syncRemoteSourcesButton.isEnabled = false
            remoteSourceManager.syncFromServer()
                .onSuccess { summary ->
                    showToast(summary.message)
                    renderAll()
                }
                .onFailure { error ->
                    showToast("同步失败：${error.message ?: "请先在电视上打开二维码编辑"}")
                }
            binding.syncRemoteSourcesButton.isEnabled = true
        }
    }

    private fun renderEndpoints(container: LinearLayout, endpoints: List<SubscriptionEndpoint>, type: String) {
        container.removeAllViews()
        endpoints.forEach { endpoint ->
            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_subscription_entry, container, false)
            itemView.findViewById<SwitchCompat>(R.id.entry_auto_refresh_switch).visibility = android.view.View.GONE
            itemView.findViewById<TextView>(R.id.entry_auto_refresh_label).visibility = android.view.View.GONE
            itemView.findViewById<TextView>(R.id.entry_error_icon).visibility = android.view.View.GONE
            itemView.findViewById<TextView>(R.id.entry_title).text =
                if (endpoint.isEnabled) endpoint.name else "${endpoint.name} · 已禁用"
            itemView.findViewById<TextView>(R.id.entry_summary).text = endpoint.url
            itemView.findViewById<TextView>(R.id.entry_status).text =
                buildString {
                    append(if (endpoint.isEnabled) "已启用" else "已停用")
                    append(" · ")
                    append(endpoint.lastError ?: if (endpoint.lastSuccessAt > 0L) "最近测试成功" else "尚未测试")
                }
            itemView.findViewById<SwitchCompat>(R.id.entry_enable_switch).apply {
                setOnCheckedChangeListener(null)
                isChecked = endpoint.isEnabled
                text = if (endpoint.isEnabled) "启用" else "停用"
                setOnCheckedChangeListener { _, enabled ->
                    lifecycleScope.launch {
                        subscriptionRepository.updateEndpoint(endpoint.copy(isEnabled = enabled))
                        showToast(if (enabled) "已启用 ${endpoint.name}" else "已停用 ${endpoint.name}")
                        renderAll()
                    }
                }
            }
            itemView.findViewById<android.view.View>(R.id.entry_primary_button).setOnClickListener {
                showEndpointDialog(type, endpoint)
            }
            itemView.findViewById<android.view.View>(R.id.entry_secondary_button).setOnClickListener {
                lifecycleScope.launch {
                    val success = testEndpoint(type, endpoint.url)
                    val updated = endpoint.copy(
                        lastTestAt = System.currentTimeMillis(),
                        lastSuccessAt = if (success) System.currentTimeMillis() else endpoint.lastSuccessAt,
                        lastError = if (success) null else "测试失败"
                    )
                    subscriptionRepository.updateEndpoint(updated)
                    if (type == SubscriptionEndpoint.TYPE_LOGO && success) {
                        logoRepository.loadLogoMap(forceRefresh = true)
                        logoRepository.applyLogosToDatabase(forceRefresh = false)
                    }
                    renderAll()
                }
            }
            itemView.findViewById<android.view.View>(R.id.entry_delete_button).setOnClickListener {
                showDeleteConfirmation("订阅接口", endpoint.name) {
                    lifecycleScope.launch {
                        subscriptionRepository.deleteEndpoint(endpoint)
                        renderAll()
                    }
                }
            }
            TvFocusStyler.apply(itemView)
            container.addView(itemView)
        }
    }

    private fun showPlaylistDialog(existing: Playlist? = null) {
        val nameInput = EditText(this).apply {
            setText(existing?.name ?: "")
            hint = "视频源名称（可留空自动命名）"
        }
        val urlInput = EditText(this).apply {
            setText(existing?.url ?: "")
            hint = "请输入你有权使用的 M3U / M3U8 播放列表地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增视频源" else "编辑视频源")
            .setView(buildFormLayout(nameInput, urlInput))
            .setPositiveButton("保存") { _, _ ->
                lifecycleScope.launch {
                    val url = urlInput.text.toString().trim()
                    if (!looksLikeUrl(url)) {
                        showToast("请输入有效的视频源地址")
                        return@launch
                    }
                    val name = nameInput.text.toString().trim().ifBlank {
                        existing?.name?.takeIf { it.isNotBlank() }
                            ?: inferPlaylistName(url)
                    }
                    val sortOrder = existing?.sortOrder ?: database.playlistDao().getAllPlaylistsList().size
                    val playlist = (existing ?: Playlist.createRemote(name, url)).copy(
                        name = name,
                        url = url,
                        sortOrder = sortOrder,
                        isDefault = existing?.isDefault ?: database.playlistDao().getDefaultPlaylistItem() == null
                    )
                    val id = if (existing == null) {
                        database.playlistDao().insertPlaylist(playlist)
                    } else {
                        database.playlistDao().update(playlist)
                        playlist.id
                    }
                    if (playlist.isDefault) {
                        database.playlistDao().setAsDefaultPlaylist(id)
                        preferences.defaultPlaylistId = id
                        preferences.defaultPlaylistUrl = url
                    }
                    val saved = database.playlistDao().getPlaylistById(id) ?: return@launch
                    testAndRefreshPlaylist(saved)
                    renderAll()
                }
            }
            .setNeutralButton("设为默认") { _, _ ->
                lifecycleScope.launch {
                    existing?.let {
                        database.playlistDao().setAsDefaultPlaylist(it.id)
                        preferences.defaultPlaylistId = it.id
                        preferences.defaultPlaylistUrl = it.url ?: preferences.defaultPlaylistUrl
                        renderAll()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()
        showKeyboardReadyDialog(dialog, nameInput)
    }

    private fun showEndpointDialog(type: String, existing: SubscriptionEndpoint? = null) {
        val title = when (type) {
            SubscriptionEndpoint.TYPE_EPG -> "EPG 源"
            else -> "Logo 图标库"
        }
        val nameInput = EditText(this).apply {
            setText(existing?.name ?: "")
            hint = "$title 名称"
        }
        val urlInput = EditText(this).apply {
            setText(existing?.url ?: "")
            hint = if (type == SubscriptionEndpoint.TYPE_EPG) Constants.DefaultEpgSources.SOURCE_PRIMARY else Constants.DefaultLogoSources.PRIMARY
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增$title" else "编辑$title")
            .setView(buildFormLayout(nameInput, urlInput))
            .setPositiveButton("保存") { _, _ ->
                lifecycleScope.launch {
                    val url = urlInput.text.toString().trim()
                    if (!looksLikeUrl(url)) {
                        showToast("请输入有效地址")
                        return@launch
                    }
                    val endpoint = (existing ?: SubscriptionEndpoint(type = type, name = "", url = "")).copy(
                        type = type,
                        name = nameInput.text.toString().trim().ifBlank { title },
                        url = url,
                        sortOrder = existing?.sortOrder ?: subscriptionRepository.getEndpoints(type).size
                    )
                    if (existing == null) {
                        subscriptionRepository.saveEndpoint(endpoint)
                    } else {
                        subscriptionRepository.updateEndpoint(endpoint)
                    }
                    if (type == SubscriptionEndpoint.TYPE_LOGO) {
                        logoRepository.loadLogoMap(forceRefresh = true)
                        logoRepository.applyLogosToDatabase(forceRefresh = false)
                    }
                    renderAll()
                }
            }
            .setNeutralButton("测试") { _, _ ->
                lifecycleScope.launch {
                    val success = testEndpoint(type, urlInput.text.toString().trim())
                    showToast(if (success) "测试通过" else "测试失败")
                }
            }
            .setNegativeButton("取消", null)
            .create()
        showKeyboardReadyDialog(dialog, nameInput)
    }

    private fun showChannelProbeDialog() {
        val enableInput = android.widget.CheckBox(this).apply {
            text = "启用频道测速"
            isChecked = preferences.enableChannelProbeRefresh
        }
        val intervalInput = EditText(this).apply {
            setText((preferences.channelProbeInterval / 60_000L).toString())
            hint = "分钟，默认60"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val threadInput = EditText(this).apply {
            setText(preferences.channelProbeThreadCount.toString())
            hint = "线程数"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val delayInput = EditText(this).apply {
            setText(preferences.channelProbeDelayMs.toString())
            hint = "毫秒"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val timeoutInput = EditText(this).apply {
            setText((preferences.channelProbeTimeoutMs / 1000L).toString())
            hint = "秒，默认5"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), 0)
            addView(enableInput)
            addView(labeled("刷新周期（分钟）", intervalInput))
            addView(labeled("线程数", threadInput))
            addView(labeled("每个频道完成后等待（毫秒）", delayInput))
            addView(labeled("单频道超时（秒）", timeoutInput))
        }

        AlertDialog.Builder(this)
            .setTitle("频道测速参数")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                preferences.enableChannelProbeRefresh = enableInput.isChecked
                preferences.channelProbeInterval = (intervalInput.text.toString().toLongOrNull() ?: 60L) * 60_000L
                preferences.channelProbeThreadCount = threadInput.text.toString().toIntOrNull() ?: Constants.DEFAULT_CHANNEL_PROBE_THREAD_COUNT
                preferences.channelProbeDelayMs = delayInput.text.toString().toLongOrNull() ?: Constants.DEFAULT_CHANNEL_PROBE_DELAY_MS
                preferences.channelProbeTimeoutMs = (timeoutInput.text.toString().toLongOrNull() ?: 5L) * 1000L
                binding.channelProbeSummary.text = buildProbeSummary()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirmation(type: String, name: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("确认删除$type")
            .setMessage("确定删除“$name”吗？删除后将不再自动加载该来源。")
            .setPositiveButton("删除") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .create()
        TvDialogStyler.apply(dialog)
        dialog.show()
    }

    private suspend fun testAndRefreshPlaylist(playlist: Playlist) {
        val result = playlist.url?.let { App.instance.preferences.defaultPlaylistUrl = it; playlistRepository.refreshPlaylist(playlist) }
        if (result?.isSuccess == true) {
            showToast("视频源可用，已刷新 ${result.getOrNull()} 个频道")
        } else {
            showToast("视频源测试失败")
        }
    }

    private suspend fun testEndpoint(type: String, url: String): Boolean {
        if (!looksLikeUrl(url)) return false
        return when (type) {
            SubscriptionEndpoint.TYPE_EPG -> epgRepository.testEpgSources(listOf(url)).isSuccess
            SubscriptionEndpoint.TYPE_LOGO -> {
                val result = subscriptionRepository.testEndpoint(url)
                result.isSuccess && logoRepository.parseLogoList(result.getOrNull().orEmpty()).isNotEmpty()
            }
            else -> false
        }
    }

    private suspend fun refreshAllPlaylists() {
        database.playlistDao().getActivePlaylistsList().forEach { playlist ->
            if (!playlist.url.isNullOrBlank()) {
                playlistRepository.refreshPlaylist(playlist)
            }
        }
        epgRepository.refreshEpgSources()
        logoRepository.loadLogoMap(forceRefresh = true)
        logoRepository.applyLogosToDatabase(forceRefresh = false)
    }

    private fun buildProbeSummary(): String {
        return buildString {
            append(if (preferences.enableChannelProbeRefresh) "已启用" else "已关闭")
            append(" · 周期 ${preferences.channelProbeInterval / 60_000L} 分钟")
            append(" · ${preferences.channelProbeThreadCount} 线程")
            append(" · 间隔 ${preferences.channelProbeDelayMs}ms")
            append(" · 超时 ${preferences.channelProbeTimeoutMs / 1000L} 秒")
            if (DeviceProfile.isTv(this@SubscriptionManagementActivity)) {
                append(" · 电视端按分类补测，手动可全量测速")
            }
        }
    }

    private fun buildFormLayout(vararg edits: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), 0)
            edits.forEach { edit ->
                edit.isFocusable = true
                edit.isFocusableInTouchMode = true
                edit.isSingleLine = true
                addView(
                    edit,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 16
                    }
                )
            }
        }
    }

    /**
     * Dialog EditTexts have no initial focus on several TV launchers, so both
     * the emulator hardware keyboard and a USB keyboard appear unresponsive.
     * Explicit focus and IME request make text entry deterministic without
     * affecting normal D-pad navigation.
     */
    private fun showKeyboardReadyDialog(dialog: AlertDialog, initialInput: EditText) {
        dialog.setOnShowListener {
            TvFocusStyler.apply(dialog.window?.decorView ?: initialInput)
            initialInput.isFocusableInTouchMode = true
            initialInput.requestFocus()
            initialInput.setSelection(initialInput.text.length)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            initialInput.post {
                val inputManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                inputManager?.showSoftInput(initialInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

    private suspend fun inferPlaylistName(url: String): String {
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
            .removePrefix("www.")
            .takeIf { it.isNotBlank() }
        return host ?: "视频源${database.playlistDao().getAllPlaylistsList().size + 1}"
    }

    private fun labeled(title: String, input: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SubscriptionManagementActivity).apply { text = title })
            addView(input)
        }
    }

    private fun looksLikeUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
