# AndroidTVIptvPlayer（5xingTV）

[![Build Android APK](https://github.com/marui9819/AndroidTVIptvPlayer/actions/workflows/build-apk.yml/badge.svg)](https://github.com/marui9819/AndroidTVIptvPlayer/actions/workflows/build-apk.yml) · [下载 APK 2.9.124](https://github.com/marui9819/AndroidTVIptvPlayer/releases/download/v2.9.124/5xingTV-v2.9.124-debug.apk) · [全部版本](https://github.com/marui9819/AndroidTVIptvPlayer/releases) · [项目 Wiki](https://github.com/marui9819/AndroidTVIptvPlayer/wiki)

如果这个播放器对你有帮助，请点一下右上角的 **Star**，也欢迎通过 Issues 提交设备兼容信息、问题和改进建议。

面向 Android TV 与 Android 手机的本地 IPTV 播放器。当前版本 `2.9.124`（versionCode `414`），应用 ID `com.tvplayer.app`，最低 Android 5.0/API 21。

## 项目优势

- 面向电视遥控器设计频道浏览、分类抽屉、收藏、订阅管理和设置交互；焦点高亮针对电视观看距离做了强化。
- 针对 Android 6/7、MStar 等老电视控制缓冲、解码和后台任务负载，尽量让播放优先使用有限的 CPU、内存与硬件解码资源。适配重点包括 MStar 6A938 等低配置设备。
- 同一播放器适配电视、手机竖屏、横屏全屏及 Android 画中画；手机设置布局适合触摸操作，电视设置适合遥控器操作。
- 可导入并管理数百个频道的用户节目列表；支持频道分组、搜索、同名频道合并、备用来源、收藏、最近播放、备注、EPG/台标及播放故障诊断。
- 提供虎牙、斗鱼等直播平台的地址解析适配。低配设备、手机及不同系统版本上的播放效果会因直播源、平台接口、网络状况和设备解码器而异；不承诺所有频道在所有设备上持续可用。
- 节目源可由用户在电视端管理，也可用手机扫描电视上的二维码，在同一局域网中新增或编辑节目源，不依赖电脑服务。

播放器使用 ExoPlayer 2.18.7 / Android MediaCodec。应用本身不自建视频中转或转码服务器。

## 下载与安装

- 最新当前版本： [5xingTV 2.9.124 Debug APK](https://github.com/marui9819/AndroidTVIptvPlayer/releases/download/v2.9.124/5xingTV-v2.9.124-debug.apk)
- [浏览所有 Release](https://github.com/marui9819/AndroidTVIptvPlayer/releases)
- [构建状态与近期 APK 构建](https://github.com/marui9819/AndroidTVIptvPlayer/actions/workflows/build-apk.yml)

此 APK 为 Debug 侧载包，不是 Google Play 正式签名包。若设备提示签名冲突，需先卸载签名不同的旧包。最低系统 Android 5.0/API 21；手机画中画需要 Android 8.0/API 26 及系统支持。

## 首次使用与节目源

项目源码和 APK **不包含直播源、频道地址、节目列表、直播切片或可直接观看的节目内容**。首次安装后，请在“订阅管理”或“导入播放列表”中添加自己有权使用的来源。不要把未获授权的节目源、EPG 或台标提交到本仓库。

查找来源时，请优先联系持权电视台、运营商或内容提供方，查找其官方网站、授权应用、公开声明的免费直播或可合法使用的个人订阅。也可以在 GitHub 搜索 `M3U`、`HLS`、频道名称或地区语言，阅读仓库说明和许可证，并逐条核实来源授权与当地法规。公开可检索不等于已获转播授权；请遵守服务条款，不要抓取或传播未授权内容，测试结束后删除临时来源。

- 频道台标优先使用电视台官方媒体资料；也可在 [Wikimedia Commons](https://commons.wikimedia.org/) 查找明确允许复用的图片。逐个检查文件页的版权、署名和商标条件。
- 节目单（EPG）优先使用电视台官网或获授权的 EPG 服务。 [iptv-org/epg](https://github.com/iptv-org/epg) 可作为 EPG/XMLTV 工具与数据格式的参考；使用其中任何数据前仍需核实相应来源的许可和使用条款。
- XMLTV 格式说明见 [XMLTV Format](https://wiki.xmltv.org/index.php/XMLTVFormat)。

## 主要功能

- 导入本地或远程 M3U/M3U8、JSON、文本节目列表；按分类浏览、搜索和测速。
- 合并同名频道并切换备用来源；可设置失败后是否自动切换频道，默认关闭。
- 收藏、最近正常播放记录、频道备注（与播放 URL 绑定）、不可用频道手动重试。
- 可配置订阅自动刷新和失败重试；电视端二维码支持手机在局域网内编辑或新增节目源。
- 画面比例、音视频预检、缺音恢复、显示频道名/时间/版本、定时关闭、手机 PiP 等设置。
- 有数量和容量限制的本机播放日志；诊断上报地址默认留空。

## 遥控器操作

- 播放时：上/下切换频道；左/右在当前频道的可用来源间切换。
- 确定键打开频道分类/列表；菜单键或返回键按屏幕提示打开/关闭快捷操作。
- 频道列表中可用方向键选择频道，向右进入收藏、备注或手动重试等操作（具体菜单随频道状态变化）。
- 设置、订阅管理和列表操作均提供清晰的焦点高亮。不同电视厂商遥控器映射可能略有差异。

## 开发与构建

要求 JDK 17、Android SDK Platform 34；仓库包含 Gradle Wrapper。运行 `./gradlew testDebugUnitTest` 和 `./gradlew assembleDebug` 构建与检查。Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 隐私、许可与内容

频道、收藏、备注和播放历史保存在设备本地。除非用户自行配置诊断上报地址，应用不会把诊断记录发送到远程服务。二维码只用于同一局域网中的节目源管理。

本仓库目前未声明播放器源码的开源许可证；公开可见不等同于授予复制、修改或再分发许可。第三方媒体、站点、台标和 EPG 归各自权利人所有。

## English

AndroidTVIptvPlayer (5xingTV) is a local IPTV player for Android TV and Android phones. Current source version: `2.9.124` (versionCode `414`), application ID `com.tvplayer.app`, minimum Android 5.0/API 21.

### Why this project

- A TV-first interface for D-pad and remote control, including channel drawers, favorites, subscriptions, and settings with high-contrast focus states.
- Playback-oriented tuning for older Android 6/7 and MStar TVs: buffering and background work are adjusted to reduce competition for limited CPU, memory, and hardware decoders. MStar 6A938-class devices are an optimization target.
- One player supports TV, phone portrait, landscape full screen, and Android Picture-in-Picture. Phone and TV settings are tailored for touch and remote-control use.
- Import and organize user-provided lists with hundreds of channels; features include groups, search, duplicate-channel merging, alternate sources, favorites, history, notes, EPG/logos, and bounded playback diagnostics.
- Includes stream-resolution adapters for services such as Huya and Douyu. Real-world playback depends on the source, platform APIs, network, OS build, and device decoders; availability is not guaranteed for every channel or device.
- Add or edit playlists on the TV, or scan its QR code with a phone on the same LAN. No PC service is required.

The player uses ExoPlayer 2.18.7 / Android MediaCodec. It does not host or transcode video streams.

### APK download

- Current build: [5xingTV 2.9.124 Debug APK](https://github.com/marui9819/AndroidTVIptvPlayer/releases/download/v2.9.124/5xingTV-v2.9.124-debug.apk)
- [All releases](https://github.com/marui9819/AndroidTVIptvPlayer/releases) · [Build workflow](https://github.com/marui9819/AndroidTVIptvPlayer/actions/workflows/build-apk.yml) · [Wiki](https://github.com/marui9819/AndroidTVIptvPlayer/wiki)

This is a debug sideload package, not a Google Play production-signed build. If Android reports a signature conflict, uninstall the differently signed package first. Minimum Android version is 5.0/API 21; Android PiP requires Android 8.0/API 26 and system support.

### Playlist and metadata sources

No playlist, channel URL, stream segment, or directly playable programme content is included in the source or APK. Add only sources you are authorized to use. Prefer broadcaster, operator, or content-owner websites and licensed subscriptions. GitHub and web searches for `M3U`, `HLS`, channel names, or region/language can help locate technical examples, but public availability does not grant redistribution rights. Verify the rights and terms for every stream, EPG entry, and logo, and remove temporary test sources when finished.

- Prefer official broadcaster media kits for channel logos. [Wikimedia Commons](https://commons.wikimedia.org/) is another option when the individual file page grants reuse rights; check attribution, license, and trademark terms.
- Prefer official schedules or licensed EPG services. [iptv-org/epg](https://github.com/iptv-org/epg) is a reference for EPG/XMLTV tools and data workflows, not a blanket endorsement of any stream or listing.
- See the [XMLTV format reference](https://wiki.xmltv.org/index.php/XMLTVFormat).

### Features and remote controls

Import local or remote M3U/M3U8, JSON, and text playlists; browse groups; search; merge same-name channels; switch alternate sources; manage favorites, recent successful playback, URL-bound notes, and manual retries for unavailable channels. Configure playlist refresh, EPG/logos, picture scaling, audio/video preflight, missing-audio recovery, overlays, sleep timer, and phone PiP. Local playback logs are size-limited; remote diagnostic reporting is unset by default.

During playback, Up/Down changes channels and Left/Right switches available sources. OK opens the channel drawer; Menu or Back opens/closes the quick-action dock as indicated on screen. In channel lists, Right exposes actions such as favorites, notes, or retry when applicable.

### Build

Use JDK 17 and Android SDK Platform 34. The repository includes the Gradle Wrapper. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`; the debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Privacy and licensing

Channels, favorites, notes, and history are stored locally. Diagnostic logs are not sent to a remote server unless the user configures a reporting endpoint. The QR feature manages playlists on the local network.

The player source currently has no declared open-source license. Public visibility does not grant permission to copy, modify, or redistribute it. Third-party streams, logos, schedules, and platform services remain subject to their owners' rights and terms.

## Star the project

If 5xingTV is useful to you, please **Star this repository**. Device compatibility reports and source-neutral bug reports are welcome through [Issues](https://github.com/marui9819/AndroidTVIptvPlayer/issues). Do not post private playlists, stream URLs, tokens, or logs containing viewing information.

## 相关项目 / Related project

如果你使用乐视智能家庭中心“屏霸”SHC Air，也可查看独立工具 [LetvOfflineRemoteKey](https://github.com/marui9819/LetvOfflineRemoteKey)，用于离线选择并写入屏霸遥控器 TV 键的红外码组。它与本播放器相互独立。

For LeTV Smart Home Center (屏霸) SHC Air owners, see the separate [LetvOfflineRemoteKey](https://github.com/marui9819/LetvOfflineRemoteKey) utility for configuring the remote's TV power-key IR profile. It is independent of this player.