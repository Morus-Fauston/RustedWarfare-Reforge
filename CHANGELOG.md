# Changelog

## v0.1.6 (2026-10-18 10:41)

### 新增

- **玩家地形知识**：增加独立玩家知识状态、不可变快照、记忆/遗忘及可信宿主生命周期接线；关闭与重开不保留旧知识。
- **规划输入隔离**：A* 规划显式消费不可变地形值，真实移动碰撞继续使用真实地图；当前移动仍走无迷雾适配。
- **参考器主机底座**：增加私有路径防护、首次失败锁存、单在途请求账本及严格 UTF-8/canonical JSON 封套。

### 技术

- **验证记录**：当前核心 91 项、工具 42 项通过；此前 P001 16 项、11 份玩法轨迹精确等价及负控通过，finally 小修后未另跑 P001/隔离。
- **路径验证**：PP 主机与 MuMu 自有应用各完成 73 项检查，含真实符号链接负控；不代表原版 IO 已接入。
- **协作约束**：记录二期范围内自主实施及保留的人工确认关口。
- **未完成边界**：真实视野、知识规划与重规划、原版运行及对拍、双平台二期沙盒、50 实体性能检测仍未完成。

---

## v0.1.5 (2026-10-17 19:52)

### 整体回顾

首期（P001）以离线双平台移动沙盒为目标，从仅有目录说明推进到可构建、可操作、可自动验收的闭环：

| 版本 | 主要交付 |
|---|---|
| v0.0.0 | 三线目录说明、协作约束与仓库范围 |
| v0.1.0 | Gradle 多模块工程、核心启动边界、共享状态页、Android 包装 |
| v0.1.1 | INI/TMX/TSX 有限子集加载与失败诊断 |
| v0.1.2 | 无画面移动核心、指令权限、静态绕障与回归 |
| v0.1.3 | Windows 可操作沙盒、PC 诊断、测试执行链、转向修复 |
| v0.1.4 | Android 触控沙盒、规则比较器、XML 安全加固 |
| v0.1.5 | 首期验收套件与一期收口 |

一期结论限于**离线、双平台（Windows + MuMu 模拟器）、实验规则**；不包含原版兼容、真机、联网、性能与发布就绪。

### 新增

- **P001 验收套件**：新增独立 `p001Suite` 任务，按冻结清单执行 16 个案例（M01–M07、M04a/b/c、L01–L03c、L04），逐案输出结果、首处分歧与证据引用。
  - 运行前校验套件审批状态、`pathBase=repo-root`、`originalCompatibility=false`、聚合优先级与必需案例顺序；身份不符即退出 64。
  - 全部输入（14 个内容身份、场景/契约/关系元数据）在任何核心执行前完成 SHA-256 校验；执行结束后再次回读，防止中途漂移。
  - 聚合规则：64 输入错误 > 4 执行失败 > 3 证据不足 > 2 断言失败 > 0 通过；案例数不符按证据不足处理。
- **独立关系断言**：新增 `P001Relations`，按物理量与事件关系独立检查位移、转向限幅、加减速、无目标转向、绕障几何、事件序列与到达语义，不依赖被测实现自身的公式。
- **生命周期检查**：新增 `P001Lifecycle`，覆盖 M06 越权前后完整 DTO 不变、M07 关闭重开的新世界能力、旧句柄拒绝与干净初态；M05 与连续驱动交叉核对。
- **加载拒绝与恢复**：L01–L03c 逐项核对错误码、文件、段、字段与引用定位；L04 在同一 JVM 内验证失败后合法重载与最终初始化，不复用失败状态。
- **归档只读核验**：`--verify-archive` 复核 manifest 完整性、文件清单与 SHA-256，任何缺失、重复或篡改返回 3。

### 技术

- **验证记录**：最终套件 16/16 通过、首差为 null；全量重跑工具 36 + 核心 62 项，0 失败/错误/跳过；39 个交付文件冻结后复算无漂移。
- **历史纠错保留**：早期 M05 通过批次因漏检前置指令被撤回，最终批次改为真实 `movement-started → movement-cancelled → path-unreachable` 序列；失败批次与撤回记录均未删除。
- **公开复现限制**：套件清单、场景与内容身份位于被忽略的 `.scratch` 与 `TestLab`，且依赖原版图像；公开克隆只能编译，不能直接运行 `p001Suite`。
- **未覆盖边界**：原版参考器与行为对拍、全内容/单位兼容、真机与其他 ABI、战斗/视野/AI、真实网络、安全与性能、发布就绪均未验证。

### 文件变更表

| 文件 | 变更 |
|---|---|
| [SimulationTest/build.gradle.kts](<SimulationTest/build.gradle.kts>) | 注册 `p001Suite` 任务 |
| [P001Suite.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/p001/P001Suite.kt>) | **新增** — 16 案例编排、身份校验与聚合 |
| [P001SuiteMain.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/p001/P001SuiteMain.kt>) | **新增** — CLI 入口与归档核验 |
| [P001Evidence.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/p001/P001Evidence.kt>) | **新增** — 批次目录、清单与身份校验 |
| [P001Relations.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/p001/P001Relations.kt>) | **新增** — 独立关系断言 |
| [P001Lifecycle.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/p001/P001Lifecycle.kt>) | **新增** — 越权与重开生命周期检查 |
| [P001SuiteTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/p001/P001SuiteTest.kt>) | **新增** — 3 项套件策略测试 |
| [P001RelationsTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/p001/P001RelationsTest.kt>) | **新增** — 3 项关系断言测试 |
| [P001LifecycleTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/p001/P001LifecycleTest.kt>) | **新增** — 2 项生命周期测试 |
| [P001BranchTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/p001/P001BranchTest.kt>) | **新增** — 3 项分支与异常测试 |

---

## v0.1.4 (2026-10-17 18:50)

### 新增

- **Android 离线触控沙盒**：共享客户端新增 `touchControls` 入口，点单位单选、点地面向所选单位移动并保留选择、`Stop` 停止、`Exit match` 退出、`Reopen` 重开；移动与权限仍由核心裁决，无控制者单位返回 `not-owner`。
  - 与 Windows 共用同一套核心指令、停止与重开逻辑，不新写平台玩法。
  - 实测环境为 MuMu index 0（Android 15 / SDK 35 / x86_64），**不代表真机、其他 ABI 或性能结论**。
- **Android 生命周期处理**：后台暂停时在 GL 线程释放局内绘制、UI 与原生缓冲并保留模拟上下文；恢复重建表现、不补算后台时间；销毁（含非 finishing 的 Activity 重建）关闭核心与内容加载器，不保存对局。平台线程不允许释放仍活跃的 GL 资源。
- **设备侧测试与诊断**：新增 `AndroidEvidence` 写入应用外部私有目录的 JSONL 诊断流（非正式轨迹协议）；新增 `XmlSecurityInstrumentation` 在设备上验证合法 TMX/TSX/INI、UTF-8/UTF-16 DTD 拒绝及 `Activity.recreate()` 关闭旧宿主并建新局。
- **规则比较器**：`SimulationTest` 新增 `compare` 子命令与 `TraceComparator`/`TraceFiles`，按固定契约求值自身规则并比较两份归档，输出首处分歧（`ruleId`/`path`/`expected`/`actual`）、原始行号与前后状态。
  - 退出码区分：0 契约通过、2 断言失败、3 证据不足、4 执行失败、64 输入/身份错误；所有结果标记 `originalCompatibility=false`。
  - 空规则、条件未触发、缺步/缺字段/截断/崩溃均不通过；数值按十进制精确比较，不启用未批准容差。
  - 支持 `equals`、`absent-or-null`、`contains` 三种断言与 `action-accepted`/`action-rejected` 条件定位。

### 修复

- **Android XML 解析安全加固**：现象为 Android 的 `DocumentBuilderFactory` 缺少 JDK 的 DOCTYPE/外部实体安全特性开关；根因是桌面与 Android 平台 XML 工厂能力不一致。
  - 改为先用 fail-closed SAX 守卫校验同一字节流（`startDTD`、`resolveEntity` 直接拒绝），再用 DOM 构建；启用 UTF-8/UTF-16 编码一致处理。
  - 新增桌面 `XmlSecurityTest` 与设备侧检查，覆盖内部实体、外部 DTD 与外部实体三种拒绝路径，并验证合法输入仍可加载。

### 调整

- **客户端边界**：`SandboxSession` 增加 `tap` 与 `unitAt`，触控与鼠标共用选择/移动判定；`BootstrapScreen` 增加表现资源幂等释放、暂停/恢复重建与 `destroyPausedHost` 守卫。
- **Android 资产**：新增 `StageSandboxAssets` 任务，经 Variant API 把 7 个本机批准文件按原字节与相对路径放入 APK assets；启动时解出到应用私有目录再交给共享加载器。

### 技术

- **验证记录**：Android 票记录 62 核心 + 13 客户端测试、APK 实机操作与 Windows 共用回归；比较器票记录 86 项测试、18 项文件级案例与 16 项 CLI 复验；均 0 失败/错误/跳过。
- **公开复现限制**：APK 构建依赖本机 `TestLab` 样本与原版图像，公开克隆会明确失败；`SimulationTest` 比较器与夹具同样依赖未公开资产。**APK 含原版素材，不得作为公开包或分发物**。
- **未覆盖边界**：真机、其他 ABI/Android 版本、长期后台存活、原版兼容、原版参考器、联网、性能与安全效果均未验证；Android 票人工验收仍未完成。

### 文件变更表

| 文件 | 变更 |
|---|---|
| [AndroidManifest.xml](<Engine/android/src/main/AndroidManifest.xml>) | 锁定横屏 |
| [build.gradle.kts](<Engine/android/build.gradle.kts>) | 新增测试素材暂存任务与 instrumentation runner |
| [AndroidLauncher.kt](<Engine/android/src/main/kotlin/com/rustedwarfare/reforge/android/AndroidLauncher.kt>) | 接入触控沙盒、资产解出与生命周期清理 |
| [AndroidEvidence.kt](<Engine/android/src/main/kotlin/com/rustedwarfare/reforge/android/AndroidEvidence.kt>) | **新增** — 设备诊断 JSONL |
| [SandboxAssets.kt](<Engine/android/src/main/kotlin/com/rustedwarfare/reforge/android/SandboxAssets.kt>) | **新增** — assets 解出到私有目录 |
| [XmlSecurityInstrumentation.kt](<Engine/android/src/androidTest/kotlin/com/rustedwarfare/reforge/android/XmlSecurityInstrumentation.kt>) | **新增** — 设备侧 XML 安全与重建测试 |
| [android/README.md](<Engine/android/README.md>) | 更新操作、生命周期、资产与证据边界 |
| [BootstrapScreen.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/BootstrapScreen.kt>) | 触控入口、表现释放与暂停恢复 |
| [SandboxSession.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/SandboxSession.kt>) | 新增 `tap`/`unitAt` |
| [TouchSessionTest.kt](<Engine/client/src/test/kotlin/com/rustedwarfare/reforge/client/TouchSessionTest.kt>) | **新增** — 3 项触控测试 |
| [client/README.md](<Engine/client/README.md>) | 更新触控与生命周期说明 |
| [XmlReaderSupport.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/content/internal/XmlReaderSupport.kt>) | 改为 SAX 守卫加 DOM 构建 |
| [XmlSecurityTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/content/XmlSecurityTest.kt>) | **新增** — DTD/实体拒绝测试 |
| [Main.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/Main.kt>) | 新增 `compare` 子命令 |
| [Runner.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/Runner.kt>) | 采集后接入真实求值 |
| [RunArchive.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/RunArchive.kt>) | 记录内存副本与标准化抽取 |
| [ScenarioReader.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/ScenarioReader.kt>) | 保留文档副本与声明校验 |
| [ScenarioDeclaration.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/ScenarioDeclaration.kt>) | **新增** — 场景声明校验 |
| [TraceComparator.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/TraceComparator.kt>) | **新增** — 规则求值与首差异 |
| [TraceFiles.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/TraceFiles.kt>) | **新增** — 归档比较入口 |
| [TraceNormalization.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/TraceNormalization.kt>) | **新增** — 轨迹标准化 |
| [ComparisonTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/ComparisonTest.kt>) | **新增** — 13 项比较测试 |
| [ComparisonEvidenceTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/ComparisonEvidenceTest.kt>) | **新增** — T01–T03 夹具生成 |
| [RunnerTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/RunnerTest.kt>) | 更新空规则退出码断言 |
| [tools/verify-comparison.ps1](<SimulationTest/tools/verify-comparison.ps1>) | **新增** — 比较端到端核验 |
| [tools/verify-isolated-comparison.ps1](<SimulationTest/tools/verify-isolated-comparison.ps1>) | **新增** — 隔离构建与 CLI 核验 |
| [SimulationTest/README.md](<SimulationTest/README.md>) | 更新比较器、退出码与边界 |

---

## v0.1.3 (2026-10-17 17:41)

### 新增

- **Windows 离线移动沙盒**：共享客户端从工程状态页升级为可实际操作沙盒，真实绘制受控地图与单位，支持左键单选、右键移动、`Stop` 停止、`Exit match` 退出与 `Reopen` 重开；权限与移动结果仍由核心裁决。
  - 单选、越权、不可达、退出重开均通过公开核心指令路径；无控制者单位可查看，移动与停止返回 `not-owner`。
  - 展示节奏为实验设定（每秒 60 次、每步 `delta=1 sim-tick`、每帧最多 8 步且保留余量），**不是原版 tick 频率结论**。
- **客户端边界**：内容加载固定为 P001 受控夹具，不是通用场景解释器；后台单线程加载、取消后旧结果不发布；`MapProjection` 处理核心 y-down 与 UI y-up 转换，GL 资源在渲染线程创建与释放。
- **PC 启动与诊断**：新增 LWJGL3 Windows 启动入口与 `application` 打包，支持 `--inputs`、`--evidence` 参数；每批输出 `manifest.json` 与 `client-diagnostic.jsonl`，记录依赖/输入哈希、逐步核心观测与完成标记。该格式是专用诊断证据，不是测试线正式轨迹协议。
- **测试执行记录链**：新增独立 `SimulationTest` 工程，直接依赖 `Engine/core`，按场景读取内容身份、核对初态、提交指令、显式推进并采集两层轨迹与事件，按 TestLab 规则新建批次。
  - 采集完整时明确返回 `status=evidence-insufficient`、退出码 3，`comparison/result.json` 标注 `not-evaluated`；不因采集成功或空规则冒充契约通过。
  - 输入/版本错误退出 64，核心执行失败与超时退出 4，取消或采集不足退出 3；支持协作式超时与取消文件。
- **DEC 归档复核工具**：新增只读脚本核对 DEC 报告迁移身份、底稿哈希、维护块与导航，拒绝改写历史正文；配套合成夹具单元测试，越界路径与正文篡改必须失败。

### 修复

- **核心转向过冲与摆头**：现象为固定方向持续左右修正、接近目标后越过再反向追赶；根因为旧实现只在单步最大转角内降低目标角速，接近目标时仍保留较大角速度。
  - 修复为提前按角差减速，单步转角越过角差时限定并清零角速，保留普通分支的 0.01 早退，并补零转向加速度的定速分支。
  - 新增固定目标不越过、镜像与跨 0° 收敛、转向中换目标、零加速度四项回归；用户原始轨迹逐项重放复现旧行为，修复后同序重放通过。

### 调整

- **文档治理**：明确 ENG/TEST 输出报告与 issue 同目录命名、DEC 报告归 `Research-DEC`；补充 DEC 输出完整性要求（输入/工具身份、符号定位、平台差异、失败与未知、只读复核入口）。规则变更自 2026-10-17 生效，不追改历史清单或验收。

### 技术

- **依赖**：客户端新增 JUnit 平台测试入口；`pc` 引入 libGDX LWJGL3 后端与 desktop natives（1.13.1）；`SimulationTest` 固定 kotlinx-serialization-json 1.9.0，沿用既有 Wrapper 与 JDK 25 / Kotlin 2.3.20 / Gradle 9.3.1 工具链，Java 17 字节码。
- **验证记录**：core 61 项、client 10 项、测试执行器 11 项工具测试与 57 项核心回归，均 0 失败/错误/跳过；36 个玩法批次、真实窗口操作与截图、失败与重跑批次均保留在内部证据目录。
- **公开复现限制**：客户端、测试执行器与 DEC 复核脚本分别依赖被排除的 `TestLab` 样本、原版图像和 `Docs/` 归档；公开克隆只能编译，不能直接跑通上述验证，也不包含这些资产。
- **未覆盖边界**：不证明 Android/真机、其他 DPI/JDK、原版运行或行为兼容、动态单位避让、联网、性能与安全效果；`installDist` 不含 JRE 或受控素材。

### 文件变更表

| 文件 | 变更 |
|---|---|
| [AGENTS.md](<AGENTS.md>) | 补充输出报告落位与 DEC 完整性规则 |
| [Decompile/README.md](<Decompile/README.md>) | 登记 DEC 复核工具与运行边界 |
| [Decompile/tools/verify_dec_evidence.py](<Decompile/tools/verify_dec_evidence.py>) | **新增** — 只读归档身份核验 |
| [Decompile/tools/test_verify_dec_evidence.py](<Decompile/tools/test_verify_dec_evidence.py>) | **新增** — 合成夹具单元测试 |
| [BootstrapScreen.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/BootstrapScreen.kt>) | 改写为可操作沙盒画面与 HUD |
| [SandboxSession.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/SandboxSession.kt>) | **新增** — 选择/移动/停止与核心适配 |
| [SandboxGraphics.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/SandboxGraphics.kt>) | **新增** — 地图、单位、路径与选择绘制 |
| [SandboxLoader.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/SandboxLoader.kt>) | **新增** — 后台加载与取消 |
| [ControlledScenario.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/ControlledScenario.kt>) | **新增** — P001 受控场景装载 |
| [MapProjection.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/MapProjection.kt>) | **新增** — 世界/UI 坐标换算 |
| [ClientEvidence.kt](<Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client/ClientEvidence.kt>) | **新增** — 可选诊断回调与观测序列化 |
| [SandboxSessionTest.kt](<Engine/client/src/test/kotlin/com/rustedwarfare/reforge/client/SandboxSessionTest.kt>) | **新增** — 6 项交互与清理测试 |
| [LoadingAndProjectionTest.kt](<Engine/client/src/test/kotlin/com/rustedwarfare/reforge/client/LoadingAndProjectionTest.kt>) | **新增** — 4 项加载与投影测试 |
| [client/build.gradle.kts](<Engine/client/build.gradle.kts>) | 增加测试入口与输入目录属性 |
| [client/README.md](<Engine/client/README.md>) | 更新客户端边界、验证与平台交接 |
| [ExperimentalPolicies.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim/ExperimentalPolicies.kt>) | 修复普通机身转向过冲 |
| [SteeringRegressionTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/SteeringRegressionTest.kt>) | **新增** — 4 项转向回归 |
| [WindowsLauncher.kt](<Engine/pc/src/main/kotlin/com/rustedwarfare/reforge/pc/WindowsLauncher.kt>) | **新增** — LWJGL3 启动与参数 |
| [JsonlEvidence.kt](<Engine/pc/src/main/kotlin/com/rustedwarfare/reforge/pc/JsonlEvidence.kt>) | **新增** — 诊断清单与 JSONL 记录 |
| [pc/build.gradle.kts](<Engine/pc/build.gradle.kts>) | 增加 application、LWJGL3 与 natives |
| [pc/README.md](<Engine/pc/README.md>) | 更新构建、启动、操作与边界 |
| [SimulationTest/build.gradle.kts](<SimulationTest/build.gradle.kts>) | **新增** — 独立工程与 core 源码依赖 |
| [SimulationTest/settings.gradle.kts](<SimulationTest/settings.gradle.kts>) | **新增** — 工程声明 |
| [SimulationTest/gradle.properties](<SimulationTest/gradle.properties>) | **新增** — 工程属性 |
| [SimulationTest/gradlew](<SimulationTest/gradlew>)、[gradlew.bat](<SimulationTest/gradlew.bat>)、[gradle/wrapper/](<SimulationTest/gradle/wrapper/gradle-wrapper.properties>) | **新增** — Wrapper 入口 |
| [SimulationTest/.gitignore](<SimulationTest/.gitignore>) | **新增** — 构建缓存忽略 |
| [Runner.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/Runner.kt>) | **新增** — 采集、批次与结束状态 |
| [CoreAdapter.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/CoreAdapter.kt>) | **新增** — 实际核心适配 |
| [ScenarioReader.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/ScenarioReader.kt>) | **新增** — 场景/依赖身份校验 |
| [RunArchive.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/RunArchive.kt>) | **新增** — 批次归档 |
| [JsonIO.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/JsonIO.kt>) | **新增** — JSON 构建与摘要 |
| [Main.kt](<SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/Main.kt>) | **新增** — CLI 与退出码 |
| [fixtures/record-chain/](<SimulationTest/fixtures/record-chain/scenario.json>) | **新增** — 工具夹具场景与空规则契约 |
| [RunnerTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/RunnerTest.kt>) | **新增** — CLI 与采集批次测试 |
| [CoreAdapterLifecycleTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/CoreAdapterLifecycleTest.kt>) | **新增** — 核心适配生命周期测试 |
| [TraceCaptureTest.kt](<SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/TraceCaptureTest.kt>) | **新增** — 轨迹捕获与写回测试 |
| [tools/verify-cli.ps1](<SimulationTest/tools/verify-cli.ps1>) | **新增** — 端到端 CLI 核验 |
| [SimulationTest/README.md](<SimulationTest/README.md>) | 更新工程入口、退出码与边界 |

---

## v0.1.2 (2026-10-17 14:29)

### 新增

- **无画面模拟核心**：新增 `SimulationContext`，提供场景初始化、指令提交、显式推进、只读观测、关闭与重开接口；模拟不依赖图形后端或墙上时钟。
- **指令与归属校验**：支持选择、移动、停止及按步调度；校验单位归属与本地控制权，拒绝重复动作、过期步号以及旧世界或其他上下文的句柄，同一步动作按提交顺序处理。
- **实验移动与静态绕障**：实现转向、速度逼近、推进门限、路径点消费及四邻域 A*；按单位半径检查格心净空和圆形单位与静态障碍的相交。
  - 新不可达指令取消旧目标与路径，随后减速，不替换为邻近可达点。
  - 到达事件表示目标指令完成，不等同于立即静止；坐标、时间、转向、捕获与寻路策略保留内部替换边界。
- **状态隔离与失败保护**：观测集合提供只读快照，关闭后清理指令队列与旧世界状态；移动候选坐标发生非有限溢出时回滚整步，避免发布部分单位已更新的状态。
- **核心回归与轨迹辅助**：新增 34 项测试，覆盖 M01–M07、调度顺序、权限、重开、几何与数值边界，并提供测试用观测导出；不是正式 TestLab 执行器或协议实现。

### 调整

- **地图测试基线**：按已确认的三格墙缺口与净空规则，将加载回归中的阻挡格断言从 35 改为 33，缺口对应 row 9–11；原单格宽度无法容纳直径 22 的单位，内部地图及相关证据另行维护，不随本次源码提交。
- **缓存忽略**：新增 Engine Kotlin 缓存目录忽略规则，避免本机构建缓存进入仓库。

### 技术

- **验证记录**：既有 Windows / JDK 25.0.3 / Gradle 9.3.1 环境记录全量 57 项测试通过，包含原有 23 项及新增 34 项；本轮核对源码哈希和既有测试 XML，未重新运行测试。
- **复现与交付边界**：测试仍依赖未公开的本机样本和原版图像；仅公开克隆不能直接跑通完整测试。本次未接通客户端画面、输入或发布包。
- **实验规则边界**：通过仅表示核心与已确认实验契约自洽，不证明原版行为或路径等价、跨设备数值一致、动态单位避让、并发调用、真实网络、性能或安全效果。

### 文件变更表

| 文件 | 变更 |
|---|---|
| [.gitignore](<.gitignore>) | 忽略 Engine Kotlin 缓存 |
| [LoadFailureTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/content/LoadFailureTest.kt>) | 更新为 33 阻挡格基线 |
| [MapLoadTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/content/MapLoadTest.kt>) | 更新阻挡格计数及三格缺口断言 |
| [Commands.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim/Commands.kt>) | **新增** — 选择、移动、停止指令 |
| [ExperimentalPolicies.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim/ExperimentalPolicies.kt>) | **新增** — 实验策略接口与接线 |
| [Model.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim/Model.kt>) | **新增** — 场景、世界句柄、状态与观测模型 |
| [Movement.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim/Movement.kt>) | **新增** — 推进门限及目标速度策略 |
| [Pathfinding.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim/Pathfinding.kt>) | **新增** — 四邻域 A*、格心净空与静态相交判定 |
| [SimulationContext.kt](<Engine/core/src/main/kotlin/com/rustedwarfare/reforge/core/sim/SimulationContext.kt>) | **新增** — 模拟生命周期、权限、调度、推进与观测 |
| [CommandTimelineTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/CommandTimelineTest.kt>) | **新增** — 调度顺序、动作身份及世界隔离测试 |
| [GeometryAndFailureTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/GeometryAndFailureTest.kt>) | **新增** — 几何边界、溢出回滚和失败恢复测试 |
| [MovementContractTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/MovementContractTest.kt>) | **新增** — 直线、转向、停止、绕障及不可达集成测试 |
| [OwnershipTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/OwnershipTest.kt>) | **新增** — 越权指令不改变单位状态测试 |
| [PropulsionPolicyTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/PropulsionPolicyTest.kt>) | **新增** — 推进门限与分支顺序测试 |
| [ReopenTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/ReopenTest.kt>) | **新增** — 重开清理与观测只读测试 |
| [RevisedMovementTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/RevisedMovementTest.kt>) | **新增** — 捕获边界、单点消费与不可达减速测试 |
| [SimulationBoundaryTest.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/SimulationBoundaryTest.kt>) | **新增** — 初始化、步进与只读边界测试 |
| [TestContentFactory.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/TestContentFactory.kt>) | **新增** — 测试场景辅助构造 |
| [TraceEvidence.kt](<Engine/core/src/test/kotlin/com/rustedwarfare/reforge/core/sim/TraceEvidence.kt>) | **新增** — 测试用观测导出 |

---

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
