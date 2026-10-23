package com.tvplayer.app.ui.settings

internal enum class SettingsSection(val key: String, val title: String, val description: String) {
    PLAYBACK("section_playback", "播放与声音", "起播、解码、音视频预检与缺音恢复"),
    INTERFACE("section_interface", "画面与界面", "频道名称、透明度、时钟与最近播放"),
    TIMER("section_timer", "定时关闭", "几小时几分钟后停止播放并退出"),
    NETWORK("section_network", "订阅与网络", "视频源自动刷新及刷新间隔"),
    ADVANCED("section_advanced", "诊断与工具", "错误日志、服务地址与系统工具"),
    ABOUT("section_about", "关于应用", "当前版本与设备信息");

    companion object {
        fun fromKey(key: String?) = values().firstOrNull { it.key == key }
    }
}
