# 构建指南

## 环境

- JDK 17
- Android SDK Platform 34 和对应 Build Tools
- macOS/Linux：`./gradlew`；Windows：`gradlew.bat`

第一次构建前，按本机 Android SDK 安装位置配置未纳入版本控制的 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

Windows 示例：

```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

不要提交 `local.properties`、签名密钥、密码或密钥库。

## 测试与构建

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

Windows 命令：

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

安装到已授权的 Android 设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 发布构建

`assembleRelease` 不代表 APK 已用可信发布证书签名。发布者应自行配置私有签名信息，并将密钥及密码保存在本地安全配置或 CI Secrets 中；严禁将签名材料提交到 Git。

## 节目源与测试数据

新安装不包含默认直播列表或演示频道。构建验证应使用自有、获授权或明确允许测试的节目源；不要将播放地址、播放列表、设备数据库、诊断日志或测试抓包加入提交。
