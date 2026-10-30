# 首条公共无头短测

本目录全部为TEST-P004-001新建的自有合成输入，不复制原版地图、INI或图片。`generate_inputs.py`用标准库生成12×12空地、一个单位声明和20×20纯色PNG，并固定文件SHA256；它不生成预期。断言独立写在`contracts/select-move-stop.json`，运行时不改输入或预期。

`scenarios/select-move-stop.json`固定初态、两玩家、动作ID、完整模拟步和结束条件。`suites/pr-first-loop.json`声明本批机制/工具测试及短测入口。地图和单位仅用于自身行为，不能证明原版兼容或真实平台输入。

固定检查点：初态(70,70)；第0步接受选择；第1步接受向右移动，速度1、加速度1、初始朝向90°，位置(71,70)；第2步拒绝外方停止，仍移动到(72,70)；第3步接受己方停止并取消移动；第4、5步保持(72,70)且速度为0。预期来自声明参数下两个完整移动步及已有停止规则，未从运行轨迹反推。

## 复跑

仓库根目录，Python 3.13、JDK 25（Gradle Wrapper自动获取固定Gradle/Kotlin依赖）：

```powershell
python -B SimulationTest/ci/public_check.py
python -B SimulationTest/ci/public_check.py --isolated
```

默认结果进入`SimulationTest/build/public-check/<唯一批次>/`。`--output <全新目录>`可指定证据落点；已有目录拒绝覆盖。`--isolated`仅复制明确公开源码闭包到系统临时目录，没有Docs、TestLab、票据、原版资源或local.properties。用户级Gradle缓存允许复用，不表示离线可构建；网络依赖与本机安装的JDK仍必需。临时源码及失败现场保留，不批量清理。

入口串行构建/运行两组明确JUnit类及安装后的真实CLI，强制本次执行，拒绝空测试、跳过、失败或缺失类；输出源文件身份、命令/退出码、耗时、JUnit与完整轨迹。只覆盖本票列出的首条机制/短测，完整历史迁移和同局长测在后票交付。

GitHub Actions入口为仓库根目录的`.github/workflows/public-pr.yml`，沿用`ci/public-pr.yml`模板和相同的本地命令。PR更新运行机制和首条短测，任务有20分钟上限，结果与公开测试产物归档到该次Actions运行；配置存在不代表云端通过。主分支长测在后续测试收口接入。

## 检查点格式（兼容原schemaVersion=1规则）

`when`仍支持原`event=action-accepted/action-rejected`和`actionKind`。新增`actionId`、`unitId`精确绑定同一个动作结果；可加`stepIndex`和`phase`限定采样。事件只匹配本次观察之前、上次观察之后的动作，不跨观察复用旧事件。

纯模拟时刻检查点同时声明`phase=initial/after-advance`与`stepIndex`，区分初态和第0步推进结果。动作过滤须有event；未知动作/对象、范围外步号、空条件或未知字段均拒绝。未触发必需检查点返回3；错预期返回2并指向ruleId与前后轨迹；执行失败返回4；声明错误返回64。没有新增数值容差。
