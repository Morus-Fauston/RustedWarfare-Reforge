# Android 应用与打包入口

Android 平台启动、生命周期适配与 APK 构建入口，共用 core/client，不另写玩法。离线本机执行核心，联网消费授权结果。

当前已建立 AGP 9.1.1 构建配置，compileSdk/targetSdk 34、minSdk 26、Build Tools 36.0.0、Java 字节码目标 17。AndroidLauncher 已注册 MAIN/LAUNCHER，使用系统默认应用图标；libGDX 1.13.1 后端启动共享客户端工程状态页，并打包 armeabi-v7a、arm64-v8a、x86、x86_64 原生库。没有地图、触控操作或移动玩法；入口声明和构建成功不代替实际设备启动验收。

从 `Engine/` 执行 `./gradlew.bat :android:assembleDebug --no-daemon`，APK 输出为 `android/build/outputs/apk/debug/android-debug.apk`。SDK 通过本机 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 配置；`local.properties` 不得提交或公开。需要预先安装 SDK Platform 34 与 Build Tools 36.0.0。

本次包装验证只用默认 debug 签名，不提供发布签名或发布包。安装后应可在应用列表找到 RustedWarfare-Reforge；桌面自动添加快捷方式取决于设备 Launcher 设置。点击后预期显示工程状态页及 “Movement sandbox is not implemented yet.”，实际启动与绘制仍待设备确认。总入口见[Engine](../README.md)。

构建后的包结构检查：`python tools/verify_apk.py build/outputs/apk/debug/android-debug.apk --aapt2 <SDK>/build-tools/36.0.0/aapt2.exe`。该检查会拒绝缺启动入口、DEX、字体、原生库或许可说明的包；它不操作设备，不证明实际启动或玩法通过。
