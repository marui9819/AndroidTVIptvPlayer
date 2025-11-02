# Android TV IPTV Player 快速修复指南

## 🚨 常见问题快速解决

### 1. Java版本兼容性问题
**症状**: `Unsupported class file major version 64`

**解决方案**:
```bash
# 检查当前Java版本
java -version

# 如果是Java 20，需要更新项目配置到支持版本
```

**配置检查清单**:
- [ ] Gradle Wrapper ≥ 8.4
- [ ] Android Gradle Plugin ≥ 8.3.0
- [ ] Kotlin Plugin ≥ 1.9.0
- [ ] Java/Kotlin 编译版本 = 17
- [ ] compileSdk/targetSdk = 34

### 2. 构建失败问题
**症状**: 各种Gradle构建错误

**标准修复流程**:
```bash
# 1. 清理项目
./gradlew clean

# 2. 清理Gradle缓存 (如果需要)
rm -rf ~/.gradle/caches/
rm -rf .gradle/

# 3. 重新构建
./gradlew assembleDebug
```

### 3. GitHub Actions构建
**访问位置**: GitHub仓库 → Actions标签页

**构建产物位置**:
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- 保留时间: 30天

## 📁 关键配置文件

| 文件 | 用途 | 重要配置 |
|------|------|----------|
| `gradle/wrapper/gradle-wrapper.properties` | Gradle版本 | `distributionUrl` |
| `build.gradle` | 根级构建配置 | AGP版本、Kotlin插件 |
| `app/build.gradle` | 应用构建配置 | SDK版本、Java版本 |
| `.github/workflows/build-apk.yml` | CI/CD配置 | Java版本、Android SDK |

## 🔧 版本兼容性表

| 组件 | 要求版本 | 最低支持 | 推荐版本 |
|------|----------|----------|----------|
| Gradle | 8.4+ | 8.0 | 8.4 |
| Android Gradle Plugin | 8.3.0+ | 8.0.0 | 8.3.0 |
| Kotlin Plugin | 1.9.0+ | 1.8.0 | 1.9.0 |
| Java/Kotlin | 17 | 11 | 17 |
| Android SDK | 34 | 33 | 34 |

## 📞 快速参考命令

```bash
# 查看当前Gradle版本
./gradlew --version

# 查看项目依赖树
./gradlew app:dependencies

# 清理并重新构建
./gradlew clean assembleDebug

# 构建Release版本
./gradlew assembleRelease
```

## 🆘 紧急联系方式

- **项目仓库**: https://github.com/marui9819/AndroidTVIptvPlayer
- **技术文档**: 查看 `docs/` 目录
- **问题报告**: GitHub Issues

---

**快速修复指南 v1.0** - 2025-11-02