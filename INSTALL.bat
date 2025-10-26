@echo off
chcp 65001 > nul
echo ========================================
echo    Android TV IPTV Player 自动构建脚本
echo ========================================
echo.

:: 检查 Java 是否安装
echo 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Java 环境，请先安装 JDK 11+
    echo 下载地址: https://www.oracle.com/java/technologies/downloads/
    pause
    exit /b 1
) else (
    echo [✓] Java 环境已就绪
    java -version
    echo.
)

:: 检查 Android SDK
echo 检查 Android SDK...
if exist "C:\Users\%USERNAME%\AppData\Local\Android\Sdk" (
    echo [✓] Android SDK 已安装
    set SDK_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
) else if exist "C:\Android\Sdk" (
    echo [✓] Android SDK 已安装
    set SDK_HOME=C:\Android\Sdk
) else (
    echo [警告] 未找到 Android SDK，请手动配置 local.properties 文件
    echo.
)

:: 创建 local.properties 文件
if not exist local.properties (
    echo 创建 local.properties 文件...
    if defined SDK_HOME (
        echo sdk.dir=%SDK_HOME% > local.properties
        echo [✓] 已自动配置 SDK 路径
    ) else (
        echo 请在 local.properties 中手动设置 SDK 路径:
        echo sdk.dir=C:\Users\您的用户名\AppData\Local\Android\Sdk
        echo sdk.dir=C:\Android\Sdk
        echo.
        echo 请编辑 local.properties 文件后重新运行此脚本
        pause
        exit /b 1
    )
) else (
    echo [✓] local.properties 文件已存在
)

echo.
echo ========================================
echo            开始构建 APK
echo ========================================
echo.

:: 清理项目
echo [1/4] 清理项目...
call gradlew.bat clean
if %errorlevel% neq 0 (
    echo [错误] 清理项目失败
    pause
    exit /b 1
)

:: 同步依赖
echo [2/4] 同步依赖...
call gradlew.bat build --refresh-dependencies
if %errorlevel% neq 0 (
    echo [错误] 依赖同步失败
    pause
    exit /b 1
)

:: 构建Debug APK
echo [3/4] 构建Debug APK...
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo [错误] APK 构建失败
    pause
    exit /b 1
)

:: 检查构建结果
echo [4/4] 检查构建结果...
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo.
    echo ========================================
    echo           构建成功！
    echo ========================================
    echo APK文件位置: app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo 文件大小:
    dir "app\build\outputs\apk\debug\app-debug.apk" | find "app-debug.apk"
    echo.
    echo 如何安装:
    echo 1. 将 APK 文件复制到您的 Android TV 设备
    echo 2. 在设置中启用"安装未知应用"
    echo 3. 点击 APK 文件进行安装
    echo 4. 或使用 ADB 命令: adb install app-debug.apk
    echo.
    echo ========================================
) else (
    echo [错误] APK 文件未找到，构建可能失败
    pause
    exit /b 1
)

:: 询问是否打开APK所在目录
echo.
set /p OPEN_FOLDER="是否打开APK文件所在目录? (Y/n): "
if /i "%OPEN_FOLDER%" neq "n" if /i "%OPEN_FOLDER%" neq "no" (
    explorer "app\build\outputs\apk\debug"
)

:: 询问是否立即安装
echo.
set /p INSTALL_NOW="是否立即尝试安装? 需要连接Android设备 (y/N): "
if /i "%INSTALL_NOW%"=="y" if /i "%INSTALL_NOW%"=="yes" (
    echo.
    echo 检查Android设备连接...
    adb devices
    echo.
    set /p CONFIRM_INSTALL="确认安装到已连接的设备? (y/N): "
    if /i "%CONFIRM_INSTALL%"=="y" if /i "%CONFIRM_INSTALL%"=="yes" (
        echo 开始安装...
        adb install "app\build\outputs\apk\debug\app-debug.apk"
        echo.
        echo 安装完成!
    )
)

echo.
echo 感谢使用 Android TV IPTV Player!
pause