# Android TV IPTV Player

一个功能完整的 Android TV IPTV 播放器应用，支持多种流媒体格式和播放列表管理。

## 功能特性

### 📺 核心功能
- **多格式支持**: HLS (HTTP Live Streaming), MP4, TS, RTMP
- **播放器控制**: 播放/暂停、快进/快退、音量控制、频道切换
- **硬件解码**: 支持硬件加速和软件解码切换
- **画质选择**: 自动、1080p、720p、480p、360p 多种画质选择
- **字幕支持**: 自动字幕加载和选择
- **背景播放**: 支持后台音频播放

### 📋 播放列表管理
- **多种格式**: M3U, JSON, 纯文本 URL 列表
- **本地导入**: 从文件系统导入播放列表
- **远程加载**: 从 URL 加载远程播放列表
- **自动刷新**: 定时自动更新播放列表内容
- **分组管理**: 按频道分组整理和浏览
- **收藏功能**: 收藏喜爱的频道
- **历史记录**: 记录最近播放的频道

### 🔍 搜索功能
- **实时搜索**: 支持频道名称和分组搜索
- **智能匹配**: 模糊匹配和高亮显示
- **搜索历史**: 保存搜索历史记录
- **搜索建议**: 提供热门搜索词建议

### 🎨 TV 界面
- **遥控器优化**: 完全支持 TV 遥控器操作
- **焦点管理**: 完善的焦点导航系统
- **Material Design**: 现代化的 TV UI 设计
- **Logo 显示**: 支持 EPG 频道 Logo 显示
- **频道详情**: 显示频道信息和节目指南

### ⚙️ 高级功能
- **WorkManager 后台任务**: 自动刷新播放列表
- **Room 数据库**: 本地数据持久化
- **网络优化**: 支持代理和自定义头部
- **缓存管理**: HTTP 缓存和媒体缓存
- **多语言**: 支持中文和其他语言

## 技术架构

### 🏗️ 架构模式
- **MVVM**: Model-View-ViewModel 架构
- **Repository**: 数据仓库模式
- **Clean Architecture**: 分层架构设计

### 🛠️ 技术栈
- **Kotlin**: 主要开发语言
- **AndroidX**: 现代 Android 组件
- **ExoPlayer**: 媒体播放引擎
- **Room**: 数据库框架
- **Retrofit**: 网络请求框架
- **Coroutines**: 异步编程
- **LiveData**: 响应式数据
- **ViewModel**: UI 数据管理
- **WorkManager**: 后台任务管理
- **Glide**: 图片加载库
- **Material Components**: UI 组件库

## 项目结构

```
app/
├── src/main/java/com/tvplayer/app/
│   ├── data/
│   │   ├── model/          # 数据模型 (Channel, Playlist)
│   │   └── db/             # 数据库 (DAO, Database)
│   ├── network/            # 网络层 (ApiService, Retrofit)
│   ├── repository/         # 数据仓库 (PlaylistRepository)
│   ├── ui/                # UI 层
│   │   ├── main/           # 主界面
│   │   ├── player/         # 播放器界面
│   │   ├── search/         # 搜索界面
│   │   ├── settings/       # 设置界面
│   │   └── playlist/       # 播放列表管理
│   ├── viewmodel/         # ViewModel 层
│   ├── workmanager/       # 后台任务
│   ├── util/              # 工具类
│   └── App.kt             # 应用程序入口
├── src/main/res/          # 资源文件
│   ├── layout/            # 布局文件
│   ├── values/            # 值资源 (strings, colors, etc.)
│   ├── drawable/          # 图片资源
│   └── xml/               # XML 配置
```

## 系统要求

- **最低版本**: Android 7.0 (API 24)
- **目标版本**: Android 14 (API 34)
- **架构要求**: ARM, ARM64, x86, x86_64
- **内存要求**: 最低 1GB RAM，推荐 2GB+
- **存储要求**: 100MB 安装空间 + 媒体缓存

## 构建和部署

### 环境要求
- Android Studio Arctic Fox 或更高版本
- Android SDK API 24-34
- NDK (可选，用于原生开发)
- Kotlin 1.9.10+

### 构建步骤

1. **克隆项目**
```bash
git clone <repository-url>
cd AndroidTVIptvPlayer
```

2. **配置 SDK**
```bash
# 在 local.properties 中配置 SDK 路径
sdk.dir=C:/Users/YourUser/AppData/Local/Android/Sdk
```

3. **同步 Gradle**
```bash
./gradlew build
```

4. **构建 APK**
```bash
# 调试版本
./gradlew assembleDebug

# 发布版本
./gradlew assembleRelease
```

### 签名和发布

1. **生成签名密钥**
```bash
keytool -genkey -v -keystore my-release-key.keystore \
  -alias my-alias -keyalg RSA -keysize 2048 -validity 10000
```

2. **配置签名**
在 `app/build.gradle` 中配置签名信息：
```gradle
android {
    signingConfigs {
        release {
            storeFile file('my-release-key.keystore')
            storePassword 'your-store-password'
            keyAlias 'your-alias'
            keyPassword 'your-key-password'
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

3. **构建签名 APK**
```bash
./gradlew assembleRelease
```

## 配置说明

### 应用配置
在 `App.kt` 中配置应用程序初始化：
```kotlin
class App : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())
    val database by lazy { AppDatabase.getDatabase(this) }
    val preferences by lazy { PreferencesHelper(this) }

    override fun onCreate() {
        super.onCreate()
        // 初始化组件
        PlayerHelper.loadInitialPlaylist()
        WorkManagerInitializer.schedulePlaylistRefresh(this)
    }
}
```

### 播放器配置
在 `PlayerHelper.kt` 中配置播放器参数：
```kotlin
fun createPlayer(context: Context, useHardwareAcceleration: Boolean): ExoPlayer {
    // 硬件加速配置
    val trackSelector = DefaultTrackSelector(context, adaptiveTrackSelectionFactory)

    // 缓冲配置
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_CACHE_SIZE.toInt(),
            MAX_CACHE_SIZE.toInt(),
            MIN_CACHE_SIZE.toInt(),
            MAX_CACHE_SIZE.toInt()
        ).build()

    return ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .build()
}
```

### 网络配置
在 `RetrofitClient.kt` 中配置网络参数：
```kotlin
fun createClient(preferences: PreferencesHelper): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (preferences.enableDebugLogging)
                HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
        })
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", preferences.userAgent)
                .build()
            chain.proceed(request)
        }
        .connectTimeout(preferences.networkTimeout, TimeUnit.SECONDS)
        .readTimeout(preferences.networkTimeout, TimeUnit.SECONDS)
        .build()
}
```

## 使用说明

### 基本操作
1. **导入播放列表**:
   - 点击"导入"按钮
   - 选择从文件、URL 或二维码导入
   - 支持M3U、JSON格式

2. **浏览频道**:
   - 使用遥控器方向键导航
   - OK键选择频道
   - Menu键打开设置

3. **播放控制**:
   - 播放/暂停: OK键
   - 切换频道: 左右方向键
   - 音量控制: 上下方向键
   - 全屏切换: Menu键

### 高级功能
1. **收藏频道**: 长按频道列表项
2. **搜索频道**: 搜索按钮，输入关键词
3. **设置画质**: Menu键 -> 质量选择
4. **字幕设置**: Menu键 -> 字幕选项

## 开发指南

### 添加新功能
1. **数据模型**: 在 `data/model` 目录下添加新的实体类
2. **数据库操作**: 在 `data/db` 目录下添加对应的 DAO
3. **网络接口**: 在 `network` 目录下添加 API 接口
4. **UI 界面**: 在 `ui` 目录下创建新的 Activity 或 Fragment
5. **业务逻辑**: 在 `viewmodel` 目录下实现 ViewModel

### 调试技巧
1. **启用日志**: 在设置中开启调试日志
2. **网络监控**: 使用 Charles 或 Fiddler 抓包
3. **性能分析**: 使用 Android Profiler
4. **数据库查看**: 使用 DB Browser for SQLite

## 常见问题

### 构建问题
- **Gradle 同步失败**: 检查网络和 SDK 版本
- **依赖冲突**: 使用 `./gradlew dependencies` 查看依赖树
- **签名问题**: 确认 keystore 文件和密码正确

### 运行问题
- **播放失败**: 检查网络连接和 URL 格式
- **界面卡顿**: 检查内存使用和布局优化
- **遥控器失灵**: 检查焦点管理和按键事件处理

### 性能优化
1. **内存优化**: 使用 Glide 图片加载和内存缓存
2. **网络优化**: 启用 HTTP 缓存和 CDN 加速
3. **播放优化**: 合理配置缓冲参数和硬件加速

## 贡献指南

1. **Fork 项目**
2. **创建功能分支**: `git checkout -b feature/new-feature`
3. **提交更改**: `git commit -m 'Add new feature'`
4. **推送分支**: `git push origin feature/new-feature`
5. **创建 Pull Request**

## 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

## 更新日志

### v1.0.0
- 🎉 初始版本发布
- ✅ 完整的 IPTV 播放功能
- ✅ TV 界面优化
- ✅ 播放列表管理
- ✅ 搜索功能
- ✅ 后台自动刷新

## 联系方式

- **项目地址**: [GitHub Repository]
- **问题反馈**: [Issues]
- **邮件联系**: [Email]

---

**注意**: 本项目仅供学习和研究使用，请遵守相关法律法规。