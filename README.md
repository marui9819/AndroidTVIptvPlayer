<div align="center">

<img src="docs/screenshots/tv-playing.jpg" alt="5xingTV 播放画面" width="800"/>

# 5xingTV（AndroidTVIptvPlayer）

**面向低配置设备的高性能无广告 IPTV 播放器**

不内置任何直播源的本地播放器，为老电视、老盒子、旧手机做了深度优化；免费、无广告、Apache-2.0 开源协议分发。

`简体中文` | [English](README_EN.md)

[![Build Android APK](https://github.com/marui9819/AndroidTVIptvPlayer/actions/workflows/build-apk.yml/badge.svg)](https://github.com/marui9819/AndroidTVIptvPlayer/actions/workflows/build-apk.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)

[⬇ 下载 APK 2.9.124](https://github.com/marui9819/AndroidTVIptvPlayer/releases/download/v2.9.124/5xingTV-v2.9.124-debug.apk) · [全部版本](https://github.com/marui9819/AndroidTVIptvPlayer/releases) · [项目 Wiki](https://github.com/marui9819/AndroidTVIptvPlayer/wiki) · [产品主页](https://marui9819.github.io/AndroidTVIptvPlayer/)

如果这个播放器对你有帮助，请点一下右上角的 **Star** ⭐，也欢迎通过 [Issues](https://github.com/marui9819/AndroidTVIptvPlayer/issues) 提交设备兼容信息、问题和改进建议。

</div>

---

## 它解决什么问题

家里的老电视、吃灰的机顶盒、抽屉里的旧手机——硬件还没坏，却因为系统老旧、内存紧张，被一个塞满广告的"智能"系统拖得卡顿难用。**5xingTV 想给这些设备一次重生的机会**：装上它，用遥控器就能顺滑地看你自己添加的节目源，没有开屏广告，没有暂停广告，什么广告都没有。

给父母装一台：他们只需要按上下键换台、按确认键打开频道抽屉，剩下的你远程指导一次就够了。

> ⚠️ 本应用是一个"空"的播放器：**不内置任何直播源、频道或节目内容**。请添加你有权使用的节目源。演示截图中的频道均为公开测试流，命名格式"演示·xxx"。

## 核心特性

| 特性 | 说明 |
| --- | --- |
| 🖥 **老旧设备深度适配** | 针对 Android 6/7、MStar 6A938 等老平台做了缓冲、解码与后台负载优化；播放器运行在独立进程，单点崩溃不拖垮整个应用 |
| 🎮 **遥控器优先的完整 TV 操作** | D-pad 导航、频道抽屉、快捷操作面板、高对比焦点（针对电视观看距离强化），全程不需要触摸 |
| 📱 **手机画中画（PiP）** | Android 8+ 小窗带播放/暂停按钮，回消息时不中断直播；角标随窗口自适应缩放 |
| 🔗 **电视展示二维码，手机扫码管源** | 手机浏览器直接编辑节目源并同步回电视，**无需电脑**，父母也会用 |
| 🔀 **同名频道多源合并** | 左右键切换备用来源；播放失败自动换台（默认关闭，用户可控） |
| ⏲ **定时关闭** | 一次性倒计时，到点只停止本应用、不关电视；还有频道备注（与播放 URL 绑定）、收藏、最近播放 |
| 🔒 **本地化隐私** | 数据全存本机，诊断上报默认留空；160 个单元测试保障工程质量 |
| 🆓 **免费、无广告、开源** | Apache-2.0 协议分发，源码可查 |

<table>
<tr>
<td align="center"><img src="docs/screenshots/tv-home.jpg" alt="TV 首页功能矩阵" width="380"/></td>
<td align="center"><img src="docs/screenshots/tv-channel-drawer.jpg" alt="频道抽屉" width="380"/></td>
</tr>
<tr>
<td align="center">首页功能矩阵</td>
<td align="center">频道抽屉：分类 + 频道列表</td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/tv-quick-actions.jpg" alt="快捷操作面板" width="380"/></td>
<td align="center"><img src="docs/screenshots/tv-qr-edit.jpg" alt="手机扫码编辑节目源" width="380"/></td>
</tr>
<tr>
<td align="center">遥控器快捷操作面板</td>
<td align="center">电视展示二维码，手机扫码管理节目源</td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/phone-portrait.jpg" alt="手机竖屏播放" width="200"/></td>
<td align="center"><img src="docs/screenshots/phone-pip.jpg" alt="手机画中画" width="380"/></td>
</tr>
<tr>
<td align="center">手机竖屏播放页</td>
<td align="center">手机画中画小窗</td>
</tr>
</table>

## 老设备适配

低配置设备的卡顿，往往不是"性能不够"，而是播放、EPG 解析、台标下载在同时争抢本就不多的 CPU 和内存。5xingTV 针对 Android 6/7 与 MStar（如 6A938）平台的老电视做了专门处理：

- **播放器独立进程**：厂商 MediaCodec/OMX 的偶发异常被隔离在播放进程内，主界面与设置不受影响；
- **按机型收敛后台负载**：老设备上自动降低 EPG、台标、批量测速的优先级，播放永远第一位；
- **缓冲策略分级**：针对老电视的内存状况调整播放缓冲，减少卡顿；
- **启动即可看**：首页功能矩阵 + 继续观看，遥控器一键直达。

适配是对上述平台的重点优化，不构成对所有旧机型效果的承诺。旧设备的解码能力和稳定性仍由固件及具体节目流决定。

## 下载与安装

- 最新当前版本：[5xingTV 2.9.124 Debug APK](https://github.com/marui9819/AndroidTVIptvPlayer/releases/download/v2.9.124/5xingTV-v2.9.124-debug.apk)
- [浏览所有 Release](https://github.com/marui9819/AndroidTVIptvPlayer/releases) · [构建状态与近期 APK 构建](https://github.com/marui9819/AndroidTVIptvPlayer/actions/workflows/build-apk.yml) · [项目 Wiki](https://github.com/marui9819/AndroidTVIptvPlayer/wiki)

此 APK 为 Debug 侧载包，不是 Google Play 正式签名包。可通过 U 盘、文件管理器或 `adb install -r` 安装；安装时允许"未知来源应用"。若设备提示签名冲突，需先卸载签名不同的旧包。最低系统 Android 5.0/API 21；手机画中画需要 Android 8.0/API 26 及系统支持。

## 快速上手：遥控器操作速成

1. 打开应用，进入 **订阅管理** 或 **导入播放列表**，添加你有权使用的 M3U/M3U8 播放列表地址；
2. 记住四个键就够：

| 按键 | 作用 |
| --- | --- |
| 上 / 下 | 上一个 / 下一个频道 |
| 确认（OK） | 打开频道抽屉（分类 + 频道列表） |
| 左 / 右 | 同名频道多源合并时，切换备用来源 |
| 菜单 / 返回 | 呼出快捷操作面板（收藏、备注、切源、定时关闭等） |

3. 频道列表中可用方向键选择频道，向右进入收藏、备注或手动重试等操作（具体菜单随频道状态变化）。不同电视厂商遥控器映射可能略有差异。

## 首次使用与节目源

项目源码和 APK **不包含直播源、频道地址、节目列表、直播切片或可直接观看的节目内容**。首次安装后，请在"订阅管理"或"导入播放列表"中添加自己有权使用的来源。不要把未获授权的节目源、EPG 或台标提交到本仓库。

查找来源时，请优先联系持权电视台、运营商或内容提供方，查找其官方网站、授权应用、公开声明的免费直播或可合法使用的个人订阅。也可以在 GitHub 搜索 `M3U`、`HLS`、频道名称或地区语言，阅读仓库说明和许可证，并逐条核实来源授权与当地法规。公开可检索不等于已获转播授权；请遵守服务条款，不要抓取或传播未授权内容，测试结束后删除临时来源。

- 频道台标优先使用电视台官方媒体资料；也可在 [Wikimedia Commons](https://commons.wikimedia.org/) 查找明确允许复用的图片。逐个检查文件页的版权、署名和商标条件。
- 节目单（EPG）优先使用电视台官网或获授权的 EPG 服务。[iptv-org/epg](https://github.com/iptv-org/epg) 可作为 EPG/XMLTV 工具与数据格式的参考；使用其中任何数据前仍需核实相应来源的许可和使用条款。
- XMLTV 格式说明见 [XMLTV Format](https://wiki.xmltv.org/index.php/XMLTVFormat)。

### 社区节目源项目推荐

> **免责声明：以下为社区维护的第三方项目，仅作链接推荐，与本项目无关。使用前请自行确认授权与合规情况；本项目不对其内容、可用性或合法性承担任何责任。**

| 项目 | 说明 |
| --- | --- |
| [iptv-org/iptv](https://github.com/iptv-org/iptv) | 全球公开频道收录项目，按国家/地区/语言分类，社区规模最大 |
| [vbskycn/iptv](https://github.com/vbskycn/iptv) | 国内常用直播源每日更新，支持 IPv4/IPv6 双栈访问 |
| [YanG-1989/m3u](https://github.com/YanG-1989/m3u) | 直播源聚合仓库，持续维护更新 |
| [Guovin/iptv-api](https://github.com/Guovin/iptv-api) | 直播源自动采集/测速/更新工具，可自建专属播放列表 |

## 路线图

以下功能**规划中 / 开发中**，欢迎在 Issues 区留言讨论：

- [ ] EPG 节目单管理增强（可视化编辑、更灵活的刷新）
- [ ] 双指缩放手势（当前提供三档画面比例切换）
- [ ] 平板与大屏适配优化
- [ ] 频道分组自定义排序

## 转载与二开（许可证）

本项目播放器源码以 **[Apache-2.0](LICENSE)** 协议分发。再分发或二次开发时，请保留 [NOTICE](NOTICE) 声明并注明出处；如经修改，须标注已作修改。**转载渠道物料时，请勿声称"内置直播源"或承诺任何频道可用性。** 第三方媒体、站点、台标和 EPG 归各自权利人所有。

## 开发与构建

要求 JDK 17 与 Android SDK Platform 34；仓库包含 Gradle Wrapper。

```bash
git clone https://github.com/marui9819/AndroidTVIptvPlayer.git
cd AndroidTVIptvPlayer
./gradlew testDebugUnitTest   # 160 个单元测试
./gradlew assembleDebug       # 产物: app/build/outputs/apk/debug/app-debug.apk
```

播放器使用 ExoPlayer 2.18.7 / Android MediaCodec。应用本身不自建视频中转或转码服务器。Windows 可使用 `gradlew.bat`。

## 隐私说明

- 频道、收藏、备注和播放历史保存在设备本地；
- 诊断上报默认不配置服务地址，只有用户自行填写后才会启用；
- 二维码只用于同一局域网中的节目源管理；本地调试日志仅限同一网络内查看有限容量的运行记录，请勿公开分享日志、二维码或播放地址。

## 合法使用与内容说明

本仓库及 APK 不包含直播播放列表、频道 URL、直播切片或任何可直接观看的节目内容。请只添加你拥有、获授权或当地法律允许访问的来源，并自行确认服务条款及版权许可。不要将未获授权的节目源上传到本仓库或转发给他人；若仅作许可范围内的临时测试，请在测试结束后及时移除节目源及相关本地数据。

低配设备、手机及不同系统版本上的播放效果会因直播源、平台接口、网络状况和设备解码器而异；虎牙、斗鱼等站点适配可能随其接口变化而失效，本项目不承诺所有频道在所有设备上持续可用，也不对用户添加的第三方内容负责。

## Star the project

如果 5xingTV 对你有用，请点一下 **Star** ⭐。欢迎通过 [Issues](https://github.com/marui9819/AndroidTVIptvPlayer/issues) 提交设备兼容报告与问题描述（附设备型号、系统版本）。请勿在 Issues 中粘贴私人节目列表、流地址、令牌或含观看信息的日志。

## 相关项目 / Related project

如果你使用乐视智能家庭中心"屏霸"SHC Air，也可查看独立工具 [LetvOfflineRemoteKey](https://github.com/marui9819/LetvOfflineRemoteKey)，用于离线选择并写入屏霸遥控器 TV 键的红外码组。它与本播放器相互独立。

---

**English users:** See [README_EN.md](README_EN.md) for the full English documentation.

📱 产品主页：[https://marui9819.github.io/AndroidTVIptvPlayer/](https://marui9819.github.io/AndroidTVIptvPlayer/)
