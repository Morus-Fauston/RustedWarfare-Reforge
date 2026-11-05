# 本项目工作流入口

`workflow.py` 消费一份有类型的 JSON 计划，执行准备、构建、所选回归、固定包检查和结果保全。默认快速；`full` 是声明范围内全量功能；`performance` 独立调用既有性能执行器。计划不接受任意 shell 命令。

本机 TEST-P003-001 的冻结计划位于：

```text
D:/Git/RustedWarfare-Reforge/.scratch/开发票据/测试系统/P003-工作流整合改进/evidence/TEST-P003-001/delivery/default-plan.json
```

从任意目录调用下面入口即可；省略 `--output` 时自动在登记的 evidence 根建立唯一新批，不需手填 UUID、SHA、ADB 端口或 PID。

```powershell
python -B C:/Users/46348/.codex/worktrees/test-p003-workflow/RustedWarfare-Reforge/SimulationTest/tools/workflow.py run --plan D:/Git/RustedWarfare-Reforge/.scratch/开发票据/测试系统/P003-工作流整合改进/evidence/TEST-P003-001/delivery/default-plan.json
```

同一命令的选项：

| 操作/参数 | 覆盖 |
|---|---|
| `prepare` | 冻结输入准备、工具身份与实际 Gradle 测试输入/缓存路径 dry-run；不执行测试或产品 |
| `run`，默认 `--profile quick` | 31 项内容/移动专项、5 项知识工具专项、打包、新包两套 P002 CLI 行为检查 |
| `run --profile full` | Engine 核心/客户端/PC、SimulationTest 全部功能测试，新包 P001 和两套 P002 CLI；不运行性能或设备 |
| `run --clean` | 新建各项目 `build/workflow-clean/<批次>`，保留旧 build/XML/失败记录 |
| `launch --target core-pc-ui` | 从明确固定 PC 包实际加载、选择、Move、运动中的 Stop、动作后九步停稳、原生截图、正常关闭 |
| `run --target core-mumu-art` | 现成 MAIN/TEST 的 ART12 与 7 个 Planner 检查 |
| `collect --target core-mumu-ui` | MEMORY/共享关 visibility 实际触控、原生图片、库存和完整增量备份 |
| `collect --target original-mumu` | 既有 OM08 原版诊断采集和固定归一化器；不授正式 O.v2 通过 |
| `device-inspect` | 获配 #1 的新现场、四包、精确旧库存与 profile 检查，无业务 Activity/安装 |
| `run --profile performance` | 默认计划仅 `qualify` 37 批及独立原始统计复算；不计正式性能达标 |
| `reuse` | 读取计划中的 `reuseFrom`，核来源/输入/包/原始索引和覆盖，解释受影响层；不启动产品或设备 |

`--target` 可重复，但同一实例/交互窗口串行持有；独立主机子任务初始最多四个，Gradle worker/fork 限一。性能窗口使用独立 gate，与受管理功能任务互斥，遇占用即退出，不自动排队。正式 `measure` 另需维护者选定空闲窗口及计划中的显式批准；本票未执行正式 576 批。

每批保留 `intent.json`、逐命令 `start.json/stdout.log/stderr.log/result.json`、生效配置、本次任务/XML、源码和工具前后身份、固定包与 `artifactIndex`、JSON 总结果及 Markdown 索引。命令成功、规则结果、准备就绪、历史复用和人工接收分别记录。构建失败、0 测试、跳过和旧 XML 均不能成为本次通过。

设备计划必须来自明确归还的资源。只支持原批准 #1/creation1792546680141873 及固定工具/包；主机 epoch、专用 ADB owner、四包、八属性/内容/集合和旧备份均先验，差异停止受影响链。无自动安装、清数据、kill、重启或旧端点重试。成功设备批自动生成 `next-plan.json`，下一批消费它以取得新库存与最新 profile，不手改旧基线求绿。

已有固定包可继续使用，不因无关工作区 WIP 自动失效；关联输入/实际依赖变化必须核对。`reuse` 对旧收据缺原始索引、未验证的平台聚合复用保守拒绝，只显示可证明的层。比较器变化先重判合格冻结记录；导航变化不要求产品重跑。资格 37 与正式 576 不共享覆盖资格。

公开克隆没有 Docs/.scratch/TestLab、原版素材/APK、私有目录守卫或用户缓存。缺件会明确拒绝，不能据此声称公开克隆可完整复现。本票输入快照含 98 个文件，另 104 项固定历史引用仍依赖本机原位置，并非异机完整备份。不要将这些材料 force-add、提交或公开。

后续范围：新 PC 原版采集后端、场景快对拍扩展/图形增强、性能工具提速、完整 O.v2/36 批和手机适配。当前有限 Windows/MuMu 证据不代表全面兼容、物理手机、真实性能达标或整期接收。


## 首次长链归档与独立重判

`python -B SimulationTest/tools/workflow.py scenario-run --plan <固定计划.json> --output <新批次>` 为所选公开 Core／Session 场景冻结源码、输入、JDK 身份，构建一次分发包；先用固定真实好档和语义坏档检验消费者，再执行场景。可选 `archive-production` 的 long 层包含三格式各一条 Core 与 Session 正常建造生产战斗链，不加入日常七组默认选择。

计划严格包含 `schemaVersion:1`、当前绝对 `root`、`suite`、`kind:short|long`、唯一 `cases` ID 列表和 `qualifications:[{path,sha256}]`。每种选定 backend 都须有资格索引；索引路径相对计划文件，SHA 固定索引原字节。`scenario-qualify --index <索引> --distribution <固定分发包> --output <新目录>` 也可单独检查。索引包含 schemaVersion、backend、fixtureKind（Core 为 core-archive，Session 为 legacy-raw）、scenario、contract 和 cases；每例有唯一 case、相对 path、expectedExitCode。至少一个好例 0 和一个语义／证据坏例 2 或 3；坏例必须声明 expectedCategory（旧 Core 索引兼容 category），Session raw 另有 sha256。进程失败或输入错误不能充当消费者资格。

批次保留 candidate/source、candidate/package、资格原件、每场景完整 raw、Core normalized、命令与标准输出错误；execution、consumption、summary、payload／seal、terminal／result 分别记录执行、独立判定、封存和打印结果。complete 仅代表所选采集完整，不代表契约或整期通过。进程失败保留部分原件并停止后续场景，不偷偷重跑。封存清单和哈希用于本工具归档完整性核验，不构成外部可信签名。

`scenario-restore --run <原批次> --output <新目录>` 原字节复制候选和已封存文件，以 restore.json 记录显式位置映射；不改写旧清单或哈希。`scenario-compare --run <原批次或恢复副本> --output <另一个新目录>` 使用档内固定 Core 包和 Python 消费者独立重判，不构建或推进模拟。恢复成功只说明完整归档复制通过，仍需单独重判；不完整原件的恢复不会变为通过。重判、恢复目录必须在原批次之外。历史 Windows 相对定位符可显式解析反斜杠，原件保持不变。消费者升级后的新规则资格须另行验证，此入口默认固定档内版本。

输出只可进入项目 build、对应票 evidence 批次或系统 Temp。生产 run 仍要求私有工作区布局守卫；公开克隆缺守卫不能冒称完整工作区验收。恢复副本可使用档内 `candidate/source/SimulationTest/tools/workflow.py scenario-compare`，限该档捕获的公开输入与同平台运行条件；不代表 Android、PC 操作、网络或原版对拍。Session 启动通过单个 lib 通配符保持命令短，Windows 用 Unicode 扩展路径和短 Temp 工作目录，全部实际 JAR 身份仍逐一记录。

Session 的可选 `history` 契约用于显式声明长链检查义务，rules 仍须逐一对应全部 intentId。initialBalance 为独立初始金额；spend 声明 intentId、正 amount、at（action 或 first-step）。noActionIntents 显式列无动作意图；其余意图检查固定动作种类及接受状态、完整步数／时序与逐观测余额。mechLegs 声明类型对应腿数，检查姿态字段、有限数值和腿序；始终拒绝死亡／不存在单位残留炮态。可选 ownerPlayerId、initialOwnedTypes、finalUnits、minimumBirths 检查声明的初态、终态和射击义务；projectileBirthsRequired 只适用于可见出生记录完整的固定场景，不能推广到任意战争迷雾记录。弹体路径可用 canonical 非负索引或唯一 token；不猜测不存在的 id 字段。新 Session 记录含 generation；混入旧代次或缺失代次拒绝，旧版整档无观测代次仍可按原读法追溯。这些是独立声明的证据义务，不替代核心玩法或新增通用玩法规则。


长链动作还须与紧邻的 before/action/after、固定 actor 和实际存活对象绑定；非选择指令核对象归属，已有建造类型、升级、目的地和目标身份逐项对应意图。旧生产动作没有产品字段时，通过同厂 factoryQueues 的 FIFO 前缀不变、恰好新增一个指定产品核对，不由终局反推请求。Session 合同声明的攻击精确点按该固定场景核对，不增加通用鼠标命中容差。独立资格 CLI 同样取得构建资源锁并限制输出目录；候选 worker 先写新 Temp，完整字节复制回父批次并保存位置映射，失败也保留。终端须实际 flush 成功才记录打印通过。
