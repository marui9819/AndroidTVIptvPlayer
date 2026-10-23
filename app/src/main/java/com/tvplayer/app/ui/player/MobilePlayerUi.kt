package com.tvplayer.app.ui.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tvplayer.app.R

/** Reflows one existing video container; never reparents the active Surface on mode changes. */
internal class MobilePlayerUi(
    private val context: Context,
    private val video: FrameLayout,
    private val pipSupported: Boolean,
    private val action: (String) -> Unit
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF0B121A.toInt())
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
    private val details = ScrollView(context).apply { isFillViewport = true }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(20)) }
    private val title = TextView(context).apply { textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, 1) }
    private val subtitle = TextView(context).apply { textSize = 14f; setTextColor(0xFF9FB5C9.toInt()); setPadding(0, dp(6), 0, dp(18)) }
    private val fullControls = LinearLayout(context).apply { setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(0xDC101D29.toInt()) }
    private var fullscreen = false
    private var pip = false
    private val hideControls = Runnable { if (fullscreen && !pip) fullControls.visibility = View.GONE }
    private val playButtons = mutableListOf<Button>()

    init {
        (video.parent as? ViewGroup)?.removeView(video)
        header.addView(button(context, "返回", "back"))
        header.addView(TextView(context).apply { text = "5xingTV · 直播"; textSize = 17f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(context, "全屏", "fullscreen"))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(video, LinearLayout.LayoutParams(-1, dp(210)))
        content.addView(title)
        content.addView(subtitle)
        row(context, listOf("上一频道" to "previous", "播放 / 暂停" to "play", "下一频道" to "next"))
        row(context, listOf("频道" to "channels", "画面" to "video", "小窗" to "pip"))
        row(context, listOf("收藏" to "favorite", "备注" to "remark", "切换来源" to "source"))
        row(context, listOf("系统设置" to "settings", "节目源管理" to "sources"))
        content.addView(TextView(context).apply {
            text = if (pipSupported) "全屏时点一下画面显示操作栏；返回键退出全屏。小窗可跨应用观看，点小窗可还原或关闭。"
                else "全屏时点一下画面显示操作栏；返回键退出全屏。当前系统不支持画中画小窗。"
            textSize = 13f; setTextColor(0xFF9FB5C9.toInt()); setPadding(0, dp(16), 0, 0)
        })
        details.addView(content)
        root.addView(details, LinearLayout.LayoutParams(-1, 0, 1f))
        listOf("退出全屏" to "normal", "频道" to "channels", "暂停" to "play", "小窗" to "pip").forEach { (label, key) ->
            fullControls.addView(button(context, label, key), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        }
        video.addView(fullControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        fullControls.visibility = View.GONE
        video.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && fullscreen && !pip) showControls()
            true
        }
        root.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (!fullscreen && !pip && r - l != oldR - oldL) updateVideoHeight()
        }
    }

    private fun button(context: Context, label: String, key: String) = Button(context).apply {
        text = label; textSize = 14f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        contentDescription = label
        backgroundTintList = null; setBackgroundResource(R.drawable.settings_section_background)
        setTextColor(Color.WHITE); setPadding(dp(8), 0, dp(8), 0)
        if (key == "pip" && !pipSupported) { isEnabled = false; alpha = .4f }
        if (key == "play") playButtons.add(this)
        setOnClickListener { action(key) }
    }

    private fun row(context: Context, actions: List<Pair<String, String>>) {
        content.addView(LinearLayout(context).apply {
            actions.forEach { (label, key) -> addView(button(context, label, key), LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) }) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    fun update(name: String, group: String, playing: Boolean) {
        title.text = name.ifBlank { "正在准备播放" }; subtitle.text = group
        playButtons.forEach { it.text = if (playing) "暂停" else "播放" }
    }

    fun setMode(full: Boolean, inPip: Boolean = false) {
        fullscreen = full; pip = inPip
        header.visibility = if (full || inPip) View.GONE else View.VISIBLE
        details.visibility = if (full || inPip) View.GONE else View.VISIBLE
        video.layoutParams = if (full || inPip) LinearLayout.LayoutParams(-1, 0, 1f)
            else LinearLayout.LayoutParams(-1, (root.width.coerceAtLeast(dp(320)) * 9 / 16))
        if (full && !inPip) showControls() else { root.removeCallbacks(hideControls); fullControls.visibility = View.GONE }
    }

    private fun updateVideoHeight() {
        if (fullscreen || pip || root.width <= 0) return
        val height = root.width * 9 / 16
        if (video.layoutParams.height != height) video.layoutParams = LinearLayout.LayoutParams(-1, height)
    }

    fun showControls() {
        if (!fullscreen || pip) return
        fullControls.visibility = View.VISIBLE
        root.removeCallbacks(hideControls); root.postDelayed(hideControls, 5_000L)
    }

    fun stop() { root.removeCallbacks(hideControls) }
}
