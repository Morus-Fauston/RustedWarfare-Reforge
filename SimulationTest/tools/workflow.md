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
