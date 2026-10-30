# 模拟测试代码与构建入口

2026-10-30新增：[首条公共机制/短测入口](public/README.md)。`python -B SimulationTest/ci/public_check.py --isolated`（从仓库根执行）只消费公开自有输入闭包；覆盖及公共CI入口见该说明。以下历史入口及原始证据保持不变。

本目录用于 Kotlin/JVM 测试控制器、原版测试桥及适配器、轨迹标准化和比较器的自有源码与构建配置，不在工具中重写另一套玩法规则。

唯一落位规则见[三线代码与构建落位](../Docs/当前决策/三线代码与构建落位.md)，执行原则见[自动行为校验](../Docs/当前决策/自动行为校验与Agent闭环.md)，任务见[测试开发线](../.scratch/开发票据/测试系统/README.md)。场景、契约、批次及本机隔离材料使用[TestLab](../TestLab/README.md)。

## 已实现入口（TEST-P001-002）

独立 Gradle 9.3.1 / Kotlin 2.3.20 / JDK 25 工程，生成 Java 17 字节码；将 `../Engine/core` 作为只读源码项目直接依赖，不加载 Android/libGDX，也不复制玩法。核心构建产物隔离到本工程的 `build/core/`。JSON 使用固定的 kotlinx-serialization-json 1.9.0；Wrapper 沿用引擎已存在的 Wrapper 文件。

在本目录执行：

```powershell
./gradlew.bat test installDist --no-daemon --console=plain
./build/install/simulation-test/bin/simulation-test.bat --help
./build/install/simulation-test/bin/simulation-test.bat run --scenario fixtures/record-chain/scenario.json --contract fixtures/record-chain/contract.json --output ../TestLab/runs/P001-自身行为验证/01-执行记录链 --mode self
```

`--output` 指定主题目录，每次新建 `run-<UTC毫秒时间>-<随机后缀>/`，不覆盖旧批次。CLI 标准输出/错误和归档文件均为 UTF-8。默认在显式边界检查 120000 毫秒协作式超时，可用 `--timeout-ms` 指定正整数；`--cancel-file` 存在时在下一边界取消。超时不改变模拟步长，也不能抢占正在执行的单次核心调用或磁盘 IO。操作系统强杀、断电和磁盘不可写可能留下无结束记录的批次，必须按不完整证据处理，不能据此通过。

TEST-P001-003 已接入规则求值。采集完整后，`comparison/result.json` 输出真实判定：0 自身契约通过、2 断言失败、3 证据不足、4 执行失败、64 输入/版本/身份错误。`complete=true` 只代表采集完成；空规则和未触发规则仍为 3，现有 `record-chain` 的空规则夹具也仍为 3。参数语法错误直接输出诊断，不创建虚假的游戏批次。所有结果均标记 `originalCompatibility=false`。

## 规则与轨迹比较（TEST-P001-003）

规则支持 `equals`、`absent-or-null`、`contains`（数组元素或事件 kind）；无 `when` 时逐观测检查，可用接口 v1 的 `action-accepted` / `action-rejected` 加可选 `actionKind` 条件定位动作后的观测。`units.<完整单位ID>.<字段>` 可使用标准化 `owner/type/movementState`，含点 ID 按最长完整身份绑定。无法绑定单位/中间路径是证据不足；只有已绑定对象的叶字段适用“缺失或 null”。条件一次也未触发不会通过。

比较仅支持 `scope=self-behavior`，工具夹具的 `approval.status=tool-fixture-only` 或已接收契约的 `accepted`，且必须排除 `original-compatibility`。这些值是本工具支持的实例格式，不自动确认任何契约。`comparison.numberMode=declared-per-field`、`tolerances={}`，可显式声明 `eventOrder=strict-record-and-array-order`；其他规则/策略/非零容差拒绝为 64。数值按十进制精确值比较：`1` 和 `1.0` 等价，微小真实差值不舍入；没有全局默认误差，也未实现任何尚未批准的容差算法。

```powershell
./build/install/simulation-test/bin/simulation-test.bat compare --scenario <固定场景> --contract <固定契约> --actual <归档目录> --expected <独立参考归档> --output <新比较主题目录> --mode self
```

`compare` 只读两个归档，输出新的 `run-*` 比较目录（判定和实际命令），不会运行核心或改参考。省略 `--expected` 时只检查自身规则，不声称轨迹等价。库入口为 `TraceFiles.compare(...)`；受控内存夹具入口为 `TraceComparator.evaluate(...)`。未连接原版参考器。

文件比较先检查 manifest 必需信息、内容/场景/契约身份、输入副本、两层轨迹 SHA-256、JSONL 完整性及逐行标准化对应，再检查初态、逐步采样、动作生效边界/文件顺序、只读状态必要字段与事件导出。崩溃/超时为 4；缺步、对象、字段、完成信息、重复采样、截断、不完整或采集端 `evidence-insufficient` 为 3；版本/身份/不支持的输入为 64。后续数据缺失优先于前面的玩法差异，证据不足不伪造首处分歧。

轨迹比较严格保留单位/玩家/世界/采样身份、动作和事件顺序及所有玩法字段，不猜测跨世界映射、不重采样、不排序或舍入。仅 `run-start` 的批次/墙钟信息和单独校验的 `run-end` 完成信息不参与玩法等价比较。首差异给出 `ruleId/path/expected/actual`、原始行号、原始和标准化记录，以及前后观测间的输入/状态/事件；规则期望来自固定契约，轨迹期望来自显式传入的独立归档。

专项验证：`./tools/verify-comparison.ps1 -EvidenceDirectory <全新目录> -ControlledSuite <ComparisonEvidenceTest生成目录>`。纯字面量 T01-T03 归档可由 `ComparisonEvidenceTest` 生成；设置 `SIMULATION_COMPARISON_EVIDENCE=<不存在的目录>` 可留档，否则使用临时目录。它们仅为比较工具夹具，不是正式玩法场景或批准的原版基准。`verify-isolated-comparison.ps1` 将当前测试工具复制到私有 build 子目录并导出固定 HEAD 的 core，执行全量测试及打包 CLI，排除其他会话 Engine 改动；私有样本复制仅供本机测试，不进入分发包。

## 场景读取与核心边界

场景/轨迹基线见[接口规格](../Docs/规划/首期场景轨迹与判定接口规格.md)。目前可执行的工具夹具位于 `fixtures/record-chain/`，仅验证工具，不是 TEST-P001-004 的正式场景/契约，也不是批准基准。

为绑定实际文件和核心的必填参数，夹具显式提供以下执行信息；它们不替代接口规格或自动批准后续正式场景：

- 内容身份 `{id, sha256}` 同时提供 `path`（相对于场景原文件目录）；`content.dependencies` 列出所有 TSX、地块图像和单位图像的身份。运行前校验哈希和引用闭包，不复制原版素材；输入文件只读，运行结束复核内容哈希。
- `movementContract` 显式传入已接收的 `pathPointRadius` 和 `targetRadius`；`angleUnit` 显式为 `degree-clockwise-zero-up`。坐标/时间模型完整声明，工具不提供玩法参数默认值。
- `termination.kind=explicit-steps`、`required=true`、`steps=[{stepIndex, delta, deltaUnit}, ...]` 提供完整采样计划；首期为从 0 连续编号、每步 `delta=1.0`。当前拒绝不能直接执行的 `contract-defined` 占位终止条件。
- 动作按步号和文件顺序提交核心公开 API，再推进并采样；权限判定和玩法均由核心负责。`CoreAdapter` 同时适配 `observe/close/reopen`，CLI 当前单批执行一次场景生命周期。

原始层完整保留核心只读 DTO 的位置、角度、速度、路径、目标、枚举、事件及动作结果；标准化层保留身份和值，只添加模型引用、字段别名、枚举映射和原始行号，不舍入或合并样本。事件既在对应观测中保留，也以带采样引用的事件行导出，消费者不可将两种表示重复计数。批次包含场景/契约副本、内容固定引用、实际运行工具/core/JSON库产物哈希、环境、模拟步数、独立墙上耗时、命令和退出状态。

## 寻路性能执行器（有限测量工具）

新增入口为 `com.rustedwarfare.reforge.testing.perf.PathPerfBatchMain`，与原 `run/compare` CLI 分开；没有注册新的 Gradle 运行任务。它调用实际核心和搜索诊断，不加载原版运行时；“原版兼容寻路”标签仅是现有 no-fog 规划适配，不表示已与原版对拍。

在 `SimulationTest/` 执行 `./gradlew.bat installDist --no-daemon` 后，可用安装目录的全部依赖启动（Windows/JVM）：

```powershell
java -cp "build/install/simulation-test/lib/*" com.rustedwarfare.reforge.testing.perf.PathPerfBatchMain --repo-root "<仓库绝对路径>" --unit "<已批准单位INI绝对路径>" --unit-sha "<该INI的SHA256>" --output "<允许新建测量批次的绝对目录>"
```

上述占位符必须换成实际绝对路径和事先固定的身份；单位图片等依赖也须原位可读。`--output` 必须是已存在且可写的普通目录，不能位于本仓库的原版输入、TestLab、Docs 或 `.git` 下；执行器在其内 exclusive-create 新批次，不覆盖旧结果。该命令会真正执行 **1/10/50 实体、5 场景、8 轮共120批**，不是帮助或构建命令。运行前确认单独资源窗口、输入／源码身份和输出目录，不能在另一性能任务、Gradle 或模拟器运行时混测；此文档修复没有重新执行该测量。

执行器输出每批原始样本、正确性控制、环境和分位汇总。round0是同一JVM内的首批，不是每批新JVM冷启动；轮1–2预热、3–7纳入实测统计。公开仓库不包含内部测量输入和历史结果，也没有为当前新源码提供已验证性能排名、毫秒/FPS阈值、动态避让或自动重规划成本结论。

无需原图的性能工具专项单元测试可单独过滤运行：

```powershell
./gradlew.bat :test --tests "com.rustedwarfare.reforge.testing.perf.PathPerfBatchTest" --no-daemon
```

这条命令只测试工具，不替代实际120批运行。参考器的纯Java／Python检查入口另见 [Decompile](<../Decompile/README.md>)，原版初始化、单步采集与首次真实对拍仍未接通。

## 验证与交接

```powershell
./tools/verify-cli.ps1 -EvidenceDirectory <新的票据证据批次绝对路径>
```

该脚本运行打包入口的帮助、版本、坏参数、两次采集、坏哈希、未知版本、缺依赖、初始化失败、取消及超时，玩法批次仍归 TestLab。重复运行只比较固定输入的核心观测，不产生原版预期。

本机测试需要已经制备的 `TestLab/local/P001-移动沙盒输入/` 和只读原版图像引用；它们受根 `.gitignore` 排除，公开克隆不包含这些资产。缺输入时测试明确失败，不空跑或跳过后报通过。源码与工具夹具为本票自写，未复制原版/参考项目玩法代码；本票不授权原版改包、安装、运行或素材分发。Windows/JVM 的证据不证明 Android、Linux、真实网络、性能或原版兼容。
