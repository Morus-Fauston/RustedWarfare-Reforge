# Android 应用与打包入口

Android 平台启动、生命周期适配与 APK 构建入口，共用 core/client，不另写玩法。离线本机执行核心，联网消费授权结果。

AGP 9.1.1、compileSdk/targetSdk 34、minSdk 26、Build Tools 36.0.0、Java 字节码目标 17。AndroidLauncher 已注册 MAIN/LAUNCHER，使用系统默认应用图标；libGDX 1.13.1 后端启动共享离线沙盒，并打包 armeabi-v7a、arm64-v8a、x86、x86_64 原生库。ENG-P001-006 已在指定 MuMu index 0（Android 15 / SDK 35 / x86_64）完成实际操作取证；真机、其他 ABI 和性能尚未验证，维护者人工验收未完成。

从 `Engine/` 执行 `./gradlew.bat :android:assembleDebug --no-daemon`，APK 输出为 `android/build/outputs/apk/debug/android-debug.apk`。SDK 通过本机 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 配置；`local.properties` 不得提交或公开。需要预先安装 SDK Platform 34 与 Build Tools 36.0.0。

使用默认 debug 签名，不提供发布签名或发布包。横屏显示受控地图与两名玩家单位：点单位单选，点地面向所选单位发移动指令，Stop 停止，Exit match 释放本局，Reopen 创建新局。另一玩家单位可检查选择表现，移动和停止仍由同一核心拒绝。系统返回键退出 Activity；再次启动创建新局。总入口见[Engine](../README.md)。

后台暂停模拟并在 GL 线程释放局内绘制、UI 与原生缓冲；恢复重建表现、保留同局与选择，不补算后台时间。销毁（包括非 finishing 的 Activity 重建）关闭核心与内容加载器，不保存对局；本轮验证 Home/恢复、返回退出/启动及 `Activity.recreate()`，不保证任意后台情形存活。

`:android:stageSandboxAssets` 从本机批准的 P001 输入读取 7 个文件，使用 AGP Variant API 生成 APK assets；原字节和相对路径不改，启动时解出到应用私有目录后交给共享加载器。原版 PNG 仅为内部测试引用，未取得公开分发结论；APK 不得作为公开包。公开克隆缺少 TestLab 与参考素材时，构建此内部样本将明确失败。

调试证据写入应用外部私有目录 `files/runs/run-<时间>-<唯一后缀>/client-diagnostic.jsonl`；这是客户端诊断流，不是正式兼容性轨迹协议。必要设备测试可构建 `:android:assembleDebugAndroidTest`，安装测试 APK 后显式选择已授权实例执行 `am instrument -w com.rustedwarfare.reforge.android.test/com.rustedwarfare.reforge.android.XmlSecurityInstrumentation`；测试自行准备输入并检查 XML 安全和 Activity 重建。

构建后的包结构检查：`python tools/verify_apk.py build/outputs/apk/debug/android-debug.apk --aapt2 <SDK>/build-tools/36.0.0/aapt2.exe`。该检查会拒绝缺启动入口、DEX、字体、原生库或许可说明的包；它不操作设备，不证明实际启动或玩法通过。
