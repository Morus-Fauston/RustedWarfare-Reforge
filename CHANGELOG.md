# Changelog

## v0.1.1 (2026-10-15 21:19)

### 新增

- **有限子集加载**：新增 `ContentLoader.loadUnitVariant(Path)` 与 `loadMap(Path)`，实际读取 INI、TMX 和 TSX 文件链，为后续离线移动切片提供固定内容定义；不实现完整模组解析。
- **单位定义**：按白名单读取独立无武器单位的标识、图像引用、尺寸和 LAND 移动参数，区分已消费字段与保留未消费字段；拒绝武器段、原版身份覆盖和不支持的必要特性。
- **地图与阻挡网格**：支持正交地图、20×20 地块、CSV Ground 层、外部及内联 tileset，校验 GID 范围和引用，并按本项目实验规则生成阻挡网格；GID 0 视为不可通行。
- **图像声明区域**：按 TSX 声明区域计算图集，允许源 PNG 大于声明区域，源图不足时拒绝加载；仅读取 PNG 文件头尺寸，不证明整张图像可渲染。
- **失败诊断与只读定义**：以 `Success` / `Failure` 返回结果，错误携带文件、段、字段、引用及原因；使用防御性复制和不可修改集合保护定义，拒绝非法输入而不返回部分定义。
- **加载回归测试**：新增单位、地图及失败路径测试，覆盖参数和布局变化、缺字段、坏引用、不支持特性、失败后重载、定义保护，以及 DOCTYPE 和损坏 XML 拒绝。

### 技术

- **依赖边界**：解析使用 JDK XML API，不新增第三方运行时依赖；XML 配置禁用 DOCTYPE、外部实体及外部 DTD/schema，不将配置或单元测试等同于完整安全验收。
- **验证记录**：既有 Windows / JDK 25.0.3 / Gradle 9.3.1 批次执行 `:core:test --rerun-tasks --no-daemon`，记录 23 个测试、0 失败、0 错误、0 跳过；本次日志整理核对源码哈希，未重新运行测试。
- **公开复现限制**：新增测试依赖本机 TestLab 样本及原版图像，相关资料继续排除在公开仓库之外；仅公开克隆不能直接跑通新增测试。
- **未覆盖范围**：不证明原版内容兼容、移动/寻路/碰撞、客户端绘制、Android XML 实现兼容性、真实网络、性能或安全效果。

### 文档

- **执行授权规则**：明确普通票获得执行授权且必要条件齐备后，不再逐票确认工作时长或工具次数；异常停止、高成本专项授权及其他人工确认关口保持有效。

### 文件变更表

| 文件 | 变更 |
|:---|:---|
| [AGENTS.md](<AGENTS.md>) | 调整普通票执行预算规则 |
| [core/build.gradle.kts](<Engine/core/build.gradle.kts>) | 配置本机测试输入目录属性 |
| [ContentLoader.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/ContentLoader.kt>) | **新增** — 统一单位和地图加载入口 |
| [ContentResult.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/ContentResult.kt>) | **新增** — 成功与失败结果类型 |
| [ContentError.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/ContentError.kt>) | **新增** — 错误码与定位诊断 |
| [UnitDefinition.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/UnitDefinition.kt>) | **新增** — 单位定义与移动参数 |
| [MapDefinition.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/MapDefinition.kt>) | **新增** — 地图、tileset 和只读阻挡网格 |
| [IniReader.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/internal/IniReader.kt>) | **新增** — 单位 INI 白名单解析 |
| [TmxReader.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/internal/TmxReader.kt>) | **新增** — TMX 层、CSV 与 GID 校验 |
| [TsxReader.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/internal/TsxReader.kt>) | **新增** — 图集、图像声明区域及阻挡属性解析 |
| [XmlReaderSupport.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/internal/XmlReaderSupport.kt>) | **新增** — XML、属性及引用校验工具 |
| [UnitVariantLoadTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/content/UnitVariantLoadTest.kt>) | **新增** — 单位加载与参数变化测试 |
| [MapLoadTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/content/MapLoadTest.kt>) | **新增** — 地图布局与定义保护测试 |
| [LoadFailureTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/content/LoadFailureTest.kt>) | **新增** — 失败定位、拒绝与重载回归 |

---

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
