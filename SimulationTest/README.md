# 模拟测试代码与构建入口

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

本票仅实现**采集链**。采集完整时 `collectionStatus=collected`、`complete=true`，但 `status=evidence-insufficient`、退出码 **3**，`comparison/result.json` 明确 `not-evaluated`；不会因空规则、自身重复一致或采集成功返回契约通过。比较器由 TEST-P001-003 实现。输入/版本/身份错误退出 64；核心/内容加载失败和超时退出 4；取消或采集数据不足退出 3。参数语法错误直接输出诊断，不创建虚假的游戏批次。

## 场景读取与核心边界

场景/轨迹基线见[接口规格](../Docs/规划/首期场景轨迹与判定接口规格.md)。目前可执行的工具夹具位于 `fixtures/record-chain/`，仅验证工具，不是 TEST-P001-004 的正式场景/契约，也不是批准基准。

为绑定实际文件和核心的必填参数，夹具显式提供以下执行信息；它们不替代接口规格或自动批准后续正式场景：

- 内容身份 `{id, sha256}` 同时提供 `path`（相对于场景原文件目录）；`content.dependencies` 列出所有 TSX、地块图像和单位图像的身份。运行前校验哈希和引用闭包，不复制原版素材；输入文件只读，运行结束复核内容哈希。
- `movementContract` 显式传入已接收的 `pathPointRadius` 和 `targetRadius`；`angleUnit` 显式为 `degree-clockwise-zero-up`。坐标/时间模型完整声明，工具不提供玩法参数默认值。
- `termination.kind=explicit-steps`、`required=true`、`steps=[{stepIndex, delta, deltaUnit}, ...]` 提供完整采样计划；首期为从 0 连续编号、每步 `delta=1.0`。当前拒绝不能直接执行的 `contract-defined` 占位终止条件。
- 动作按步号和文件顺序提交核心公开 API，再推进并采样；权限判定和玩法均由核心负责。`CoreAdapter` 同时适配 `observe/close/reopen`，CLI 当前单批执行一次场景生命周期。

原始层完整保留核心只读 DTO 的位置、角度、速度、路径、目标、枚举、事件及动作结果；标准化层保留身份和值，只添加模型引用、字段别名、枚举映射和原始行号，不舍入或合并样本。事件既在对应观测中保留，也以带采样引用的事件行导出，消费者不可将两种表示重复计数。批次包含场景/契约副本、内容固定引用、实际运行工具/core/JSON库产物哈希、环境、模拟步数、独立墙上耗时、命令和退出状态。

## 验证与交接

```powershell
./tools/verify-cli.ps1 -EvidenceDirectory <新的票据证据批次绝对路径>
```

该脚本运行打包入口的帮助、版本、坏参数、两次采集、坏哈希、未知版本、缺依赖、初始化失败、取消及超时，玩法批次仍归 TestLab。重复运行只比较固定输入的核心观测，不产生原版预期。

本机测试需要已经制备的 `TestLab/local/P001-移动沙盒输入/` 和只读原版图像引用；它们受根 `.gitignore` 排除，公开克隆不包含这些资产。缺输入时测试明确失败，不空跑或跳过后报通过。源码与工具夹具为本票自写，未复制原版/参考项目玩法代码；本票不授权原版改包、安装、运行或素材分发。Windows/JVM 的证据不证明 Android、Linux、真实网络、性能或原版兼容。
