# Android TV IPTV Player APK 构建指南

## 快速构建步骤

### 1. 环境准备
确保您的开发环境已安装：

- **Android Studio**: 最新版本 (推荐 Arctic Fox 或更高)
- **Android SDK**: API 24-34
- **Java Development Kit (JDK)**: JDK 11 或更高版本
- **Git**: 用于版本控制

### 2. 项目配置

#### 2.1 配置 SDK 路径
编辑 `local.properties` 文件：
```properties
sdk.dir=C:/Users/YourUser/AppData/Local/Android/Sdk
```

#### 2.2 配置 Gradle
编辑 `gradle.properties` 文件：
```properties
org.gradle.jvmargs=-Xmx2048m
org.gradle.parallel=true
android.useAndroidX=true
android.enableJetifier=true
```

### 3. 构建命令

#### 3.1 打开项目
```bash
# 在 Android Studio 中打开项目
# 或者使用命令行
cd AndroidTVIptvPlayer
```

#### 3.2 同步项目
```bash
# 使用 Android Studio 同步按钮，或者
./gradlew build --refresh-dependencies
```

#### 3.3 构建调试版 APK
```bash
# 构建 debug APK
./gradlew assembleDebug

# 或者
./gradlew app:assembleDebug
```

#### 3.4 构建发布版 APK
```bash
# 构建 release APK (需要签名配置)
./gradlew assembleRelease

# 或者
./gradlew app:assembleRelease
```

### 4. APK 位置

构建完成后，APK 文件位于：
```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 详细构建指南

### Windows 环境

#### 1. 设置环境变量
```cmd
# 设置 ANDROID_HOME
set ANDROID_HOME=C:\Users\YourUser\AppData\Local\Android\Sdk
set PATH=%PATH%;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\tools
```

#### 2. 使用命令行构建
```cmd
# 进入项目目录
cd D:\Source\claude\migu\AndroidTVIptvPlayer

# 赋予 gradlew 执行权限
gradlew.bat build

# 构建 debug APK
gradlew.bat assembleDebug
```

#### 3. 使用 Android Studio 构建
1. 打开 Android Studio
2. File -> Open -> 选择项目目录
3. 等待 Gradle 同步完成
4. Build -> Build Bundle(s) / APK(s) -> Build APK(s)

### macOS/Linux 环境

#### 1. 设置环境变量
```bash
# 设置 ANDROID_HOME
export ANDROID_HOME=$HOME/Library/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools
```

#### 2. 使用命令行构建
```bash
# 进入项目目录
cd AndroidTVIptvPlayer

# 赋予 gradlew 执行权限
chmod +x gradlew

# 构建 debug APK
./gradlew assembleDebug
```

## 签名配置 (可选)

### 1. 生成签名密钥
```bash
keytool -genkey -v -keystore my-release-key.keystore \
  -alias my-alias -keyalg RSA -keysize 2048 -validity 10000
```

### 2. 配置签名信息
在 `app/build.gradle` 中添加：

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

## 常见问题解决

### 1. Gradle 同步失败
```bash
# 清理项目
./gradlew clean

# 重新构建
./gradlew build --refresh-dependencies
```

### 2. SDK 路径错误
检查 `local.properties` 文件中的 `sdk.dir` 路径是否正确。

### 3. 依赖下载失败
```bash
# 清理缓存
./gradlew --refresh-dependencies

# 或者手动指定 Maven 仓库
# 在 build.gradle 中添加阿里云镜像
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/central' }
maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
```

### 4. 编译错误
```bash
# 检查 Kotlin 版本兼容性
# 确保所有依赖版本兼容
./gradlew dependencies
```

## APK 安装

### 1. 使用 ADB 安装
```bash
# 连接设备
adb devices

# 安装 APK
adb install app-debug.apk

# 卸载应用
adb uninstall com.tvplayer.app
```

### 2. 直接安装
1. 将 APK 文件复制到 Android TV 设备
2. 在设备上找到 APK 文件并点击安装
3. 如果提示"未知来源"，请在设置中启用"安装未知应用"

## 构建优化

### 1. 启用构建缓存
在 `gradle.properties` 中：
```properties
org.gradle.caching=true
android.buildCacheDir=../.gradle/build-cache
```

### 2. 并行构建
```properties
org.gradle.parallel=true
org.gradle.workers.max=4
```

### 3. 增量编译
```properties
kapt.incremental.apt=true
kapt.use.worker.api=true
```

## 版本管理

### 1. 版本号配置
在 `app/build.gradle` 中：
```gradle
android {
    defaultConfig {
        versionCode 1
        versionName "1.0.0"
    }
}
```

### 2. 构建多个版本
```gradle
android {
    flavorDimensions "version"
    productFlavors {
        free {
            dimension "version"
            applicationId "com.tvplayer.app.free"
        }
        pro {
            dimension "version"
            applicationId "com.tvplayer.app.pro"
        }
    }
}
```

## 发布准备

### 1. APK 签名验证
```bash
# 验证 APK 签名
jarsigner -verify -verbose -certs app-release.apk

# 查看 APK 信息
aapt dump badging app-release.apk
```

### 2. APK 优化
```bash
# 使用 Zipalign 优化
zipalign -v 4 app-release.apk app-release-aligned.apk

# 验证优化结果
zipalign -c -v 4 app-release-aligned.apk
```

## 构建脚本

### 自动构建脚本 (Windows)
```batch
@echo off
echo Building Android TV IPTV Player...

:: 清理项目
call gradlew.bat clean

:: 构建 debug APK
call gradlew.bat assembleDebug

:: 检查构建结果
if exist app\build\outputs\apk\debug\app-debug.apk (
    echo Build successful!
    echo APK location: app\build\outputs\apk\debug\app-debug.apk
) else (
    echo Build failed!
)

pause
```

### 自动构建脚本 (macOS/Linux)
```bash
#!/bin/bash
echo "Building Android TV IPTV Player..."

# 清理项目
./gradlew clean

# 构建 debug APK
./gradlew assembleDebug

# 检查构建结果
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "Build successful!"
    echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
else
    echo "Build failed!"
    exit 1
fi
```

---

## 联系支持

如果在构建过程中遇到问题，请：

1. 检查错误日志
2. 确认环境配置正确
3. 查看项目 README 文档
4. 提交 Issue 到项目仓库

祝您构建顺利！