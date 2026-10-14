# Changelog

## v0.1.0 (2026-10-14 23:44)

### 新增

- **引擎工程基线**：建立 Gradle Wrapper 9.3.1、Kotlin 2.3.20、AGP 9.1.1 多模块工程（`core`/`client`/`pc`/`android`），JDK 25 执行构建，共享 JVM 模块输出 Java 17 字节码，Android 编译目标 Java 17。
  - `settings.gradle.kts` 采用 `FAIL_ON_PROJECT_REPOS`，仓库只在设置层声明，模块不能各自添加仓库。
  - `server` 暂无模块，仅保留说明，不承诺服务端实现。
- **核心启动边界**：`CoreRuntime` 提供不依赖图形与 Android 的最小启动入口，配套 kotlin.test 冒烟测试；`core` 运行时依赖只有 `kotlin-stdlib` 与 `org.jetbrains:annotations`。
- **共享客户端状态页**：`BootstrapScreen` 经 `CoreRuntime.start` 启动核心，用 libGDX 1.13.1 `FitViewport` 绘制工程状态页，并释放字体与绘制资源。
- **Android 启动包装**：`AndroidLauncher` 注册 MAIN/LAUNCHER，使用系统默认图标；自定义 Gradle 任务经 Variant API 生成 armeabi-v7a、arm64-v8a、x86、x86_64 四套 libGDX 原生库。
- **APK 包结构校验**：`tools/verify_apk.py` 核对启动入口、DEX 类、字体、四 ABI 原生库与许可说明，缺件即拒绝并返回非零退出码。

### 调整

- **忽略规则**：新增 `local.properties` 与 `/Engine/` 构建产物、编译器缓存忽略项，避免本机 SDK 路径和产物进入公开仓库。
- **模块说明**：Engine 及 core/client/pc/android 五个 README 从「仅目录说明」改为实际工程状态、构建命令与未覆盖边界。

### 文档

- **边界表述**：README 明确构建成功不等于设备启动、绘制或移动沙盒通过；APK 校验不操作设备；PC 仍无应用入口。

### 技术

- **版本字段**：根工程 `version` 与 Android `versionName` 为 `0.1.0`，`versionCode` 为 1。
- **依赖范围**：固定 libGDX 1.13.1；未引入原版核心、参考项目代码或未确认框架。
- **当前状态**：只有工程包装与空状态页，没有地图、输入、移动、内容解析、联网或 Linux 服务端实现；设备启动与绘制仍待人工验收。

### 文件变更表

| 文件 | 变更 |
|:-----|:------|
| `Engine/settings.gradle.kts` | **新增** — 声明四模块与集中仓库策略 |
| `Engine/build.gradle.kts` | **新增** — 固定 Kotlin 2.3.20、AGP 9.1.1 与工程坐标 |
| `Engine/gradle.properties` | **新增** — 启用 AndroidX |
| `Engine/gradle/wrapper/gradle-wrapper.properties` | **新增** — 固定 Gradle 9.3.1 分发地址 |
| `Engine/gradlew`、`Engine/gradlew.bat`、`Engine/gradle/wrapper/gradle-wrapper.jar` | **新增** — Wrapper 启动脚本与校验包 |
| `Engine/core/build.gradle.kts` | **新增** — 工具链 25、目标 Java 17、JUnit Platform 测试 |
| `Engine/core/src/main/kotlin/.../CoreRuntime.kt` | **新增** — 无图形核心启动边界 |
| `Engine/core/src/test/kotlin/.../CoreRuntimeTest.kt` | **新增** — 最小启动冒烟测试 |
| `Engine/client/build.gradle.kts` | **新增** — 依赖 core 与 libGDX 1.13.1 |
| `Engine/client/src/main/kotlin/.../BootstrapScreen.kt` | **新增** — 工程状态页与资源释放 |
| `Engine/pc/build.gradle.kts` | **新增** — 依赖 client 的空模块 |
| `Engine/android/build.gradle.kts` | **新增** — AGP 应用配置与四 ABI 原生库生成任务 |
| `Engine/android/src/main/AndroidManifest.xml` | **新增** — MAIN/LAUNCHER 入口与系统默认图标 |
| `Engine/android/src/main/kotlin/.../AndroidLauncher.kt` | **新增** — 启动共享状态页 |
| `Engine/android/src/main/assets/licenses/Apache-2.0.txt` | **新增** — Apache 2.0 许可全文 |
| `Engine/android/src/main/assets/licenses/THIRD-PARTY-NOTICES.txt` | **新增** — libGDX 等第三方声明 |
| `Engine/android/tools/verify_apk.py` | **新增** — APK 包结构校验脚本 |
| `.gitignore` | 新增 `local.properties` 与 Engine 构建产物/缓存忽略 |
| `Engine/README.md` | 更新工程基线、验证命令与未覆盖边界 |
| `Engine/android/README.md` | 更新 SDK 要求、构建命令、包校验与设备验收边界 |
| `Engine/client/README.md` | 更新 libGDX 依赖与状态页说明 |
| `Engine/core/README.md` | 更新核心工程与测试说明 |
| `Engine/pc/README.md` | 更新空模块构建说明 |

---

## v0.0.0 (2026-10-12 23:43)

### 文档

- **准备工作**：建立独立反编译、模拟测试与引擎重写三个代码主入口及职责说明。
- **平台划分**：规划共享核心与客户端表现目录，设置 Android、Windows 客户端及 Linux 服务端入口，避免重复维护玩法。
- **协作基础**：记录项目协作约束与领域术语，明确离线本地模拟、联网服务端权威及证据边界。

### 技术

- **仓库范围**：忽略本地文档、票据、测试数据、参考项目、原版 APK 与解包内容，公开自有准备材料。
- **当前状态**：本版本仅包含目录说明与配置，尚无可构建工程、可运行游戏或兼容性验证结果；部分内部资料链接不随公开仓库提供。
