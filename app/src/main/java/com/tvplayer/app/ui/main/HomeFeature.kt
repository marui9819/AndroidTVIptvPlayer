package com.tvplayer.app.ui.main

/**
 * 首页功能单元。
 * 使用稳定的 id 处理遥控器点击，避免把页面逻辑耦合到卡片文案。
 */
data class HomeFeature(
    val id: Id,
    val title: String,
    val subtitle: String,
    val iconResId: Int
) {
    enum class Id {
        CONTINUE,
        ALL_CHANNELS,
        FAVORITES,
        RECENT,
        SEARCH,
        SUBSCRIPTIONS,
        IMPORT,
        SETTINGS
    }
}
