# Android TV IPTV Player 技术升级记录

## 📅 升级时间
2025-11-02

## 🎯 升级目标
解决Android Gradle插件版本冲突问题，支持Java 20环境，成功构建APK

## 🔍 问题分析

### 原始问题
- **错误信息**: "Unsupported class file major version 64"
- **根本原因**: Gradle 7.6.3 + Android Gradle Plugin 7.3.0 不支持 Java 20
- **环境冲突**: 本地开发环境使用Java 20，但项目配置为Java 11/8

### 技术栈兼容性问题
| 组件 | 原版本 | 问题 | 新版本 |
|------|--------|------|--------|
| Gradle | 7.4 | 不支持Java 17+ | 8.4 |
| Android Gradle Plugin | 7.3.0 | 不支持新Java版本 | 8.3.0 |
| Kotlin Plugin | 1.7.10 | 版本不匹配 | 1.9.0 |
| Java/Kotlin | 1.8 | 与Java 20环境冲突 | 17 |
| Android SDK | 33 | 需要升级 | 34 |

## 🛠️ 解决方案

### 1. Gradle Wrapper 升级
**文件**: `gradle/wrapper/gradle-wrapper.properties`
```properties
# 修改前
distributionUrl=https\://services.gradle.org/distributions/gradle-7.4-bin.zip

# 修改后
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
```

### 2. 根级构建脚本更新
**文件**: `build.gradle`
```gradle
// 修改前
classpath 'com.android.tools.build:gradle:7.3.0'
classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.10"

// 修改后
classpath 'com.android.tools.build:gradle:8.3.0'
classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0"
```

### 3. 应用级构建配置更新
**文件**: `app/build.gradle`

#### SDK 版本升级
```gradle
// 修改前
compileSdk 33
targetSdk 33

// 修改后
compileSdk 34
targetSdk 34
```

#### Java/Kotlin 编译版本升级
```gradle
// 修改前
compileOptions {
    sourceCompatibility JavaVersion.VERSION_1_8
    targetCompatibility JavaVersion.VERSION_1_8
}

kotlinOptions {
    jvmTarget = '1.8'
}

// 修改后
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
}

kotlinOptions {
    jvmTarget = '17'
}
```

### 4. GitHub Actions CI/CD 更新
**文件**: `.github/workflows/build-apk.yml`

#### Java 环境升级
```yaml
# 修改前
- name: Set up JDK 11
  uses: actions/setup-java@v4
  with:
    java-version: '11'
    distribution: 'temurin'
    cache: 'gradle'

# 修改后
- name: Set up JDK 17
  uses: actions/setup-java@v4
  with:
    java-version: '17'
    distribution: 'temurin'
    cache: 'gradle'
```

#### Android SDK 版本升级
```yaml
# 修改前
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --install "platform-tools" "platforms;android-33" "build-tools;33.0.1"

# 修改后
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## 📋 版本兼容性矩阵

### Android Gradle Plugin 8.3.0 兼容性要求
- **最低 Gradle 版本**: 8.4
- **推荐 Java 版本**: 17 (要求 11-20)
- **支持的 SDK 版本**: 34 (推荐)
- **Kotlin 插件版本**: 1.9.0+

### Java 17 兼容性
- **Android Gradle Plugin**: 7.3.0+
- **Gradle**: 7.5+
- **Android Studio**: 2021.1.1+

## 🔍 故障排除指南

### 常见错误及解决方案

#### 1. "Unsupported class file major version"
**错误**: Java版本不兼容
```bash
# 解决方案
# 1. 检查当前Java版本
java -version

# 2. 更新项目配置到Java 17
# 3. 更新Gradle和Android Gradle Plugin版本
```

#### 2. "Could not determine the dependencies"
**错误**: SDK配置问题
```bash
# 解决方案
# 1. 检查ANDROID_HOME环境变量
# 2. 在local.properties中指定sdk.dir
# 3. 使用GitHub Actions自动构建
```

#### 3. "Plugin is already on the classpath"
**错误**: Gradle插件版本冲突
```bash
# 解决方案
# 1. 清理Gradle缓存
./gradlew clean
# 2. 删除.gradle缓存目录
# 3. 更新所有相关插件版本
```

## 🚀 构建和部署流程

### 本地构建
```bash
# 1. 清理项目
./gradlew clean

# 2. 构建Debug APK
./gradlew assembleDebug

# 3. 构建Release APK
./gradlew assembleRelease
```

### GitHub Actions 自动构建
1. **触发条件**: 推送到main/develop分支
2. **构建环境**: Ubuntu + JDK 17 + Android SDK 34
3. **输出产物**: Debug APK (保留30天)
4. **产物位置**: `app/build/outputs/apk/debug/app-debug.apk`

## 📊 依赖项清单

### 核心依赖
```gradle
// AndroidX Core
implementation 'androidx.core:core-ktx:1.9.0'
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.9.0'
implementation 'androidx.constraintlayout:constraintlayout:2.1.4'

// 网络请求
implementation 'com.squareup.retrofit2:retrofit:2.9.0'
implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
implementation 'com.google.code.gson:gson:2.10.1'

// 图片加载
implementation 'com.github.bumptech.glide:glide:4.14.2'
annotationProcessor 'com.github.bumptech.glide:compiler:4.14.2'

// 视频播放
implementation 'com.google.android.exoplayer:exoplayer:2.18.7'

// Android TV 支持
implementation 'androidx.leanback:leanback:1.0.0'

// 设置页面
implementation 'androidx.preference:preference-ktx:1.2.1'

// 数据库
implementation "androidx.room:room-runtime:2.6.1"
kapt "androidx.room:room-compiler:2.6.1"
```

## 🔄 后续升级建议

### 定期维护检查项
1. **依赖项更新**: 每季度检查一次依赖项更新
2. **SDK版本**: 跟随Android新版本发布及时更新
3. **构建工具**: 保持Gradle和AGP版本兼容性
4. **安全补丁**: 及时应用安全相关更新

### 升级前检查清单
- [ ] 备份当前工作代码
- [ ] 检查新版本的兼容性矩阵
- [ ] 在测试环境验证构建
- [ ] 更新CI/CD配置
- [ ] 测试关键功能

## 📞 技术支持

### 相关文档链接
- [Android Gradle Plugin 发布说明](https://developer.android.com/studio/releases/gradle-plugin)
- [Gradle 兼容性文档](https://docs.gradle.org/current/userguide/compatibility.html)
- [Android SDK 版本分布](https://developer.android.com/about/dashboards)

### 联系信息
- **技术负责人**: [your contact info]
- **项目仓库**: https://github.com/marui9819/AndroidTVIptvPlayer
- **问题反馈**: GitHub Issues

---

**文档创建时间**: 2025-11-02
**最后更新**: 2025-11-02
**文档版本**: 1.0