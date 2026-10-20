# PC 客户端与打包入口

现行目标为 Windows；平台启动与随包运行时打包共用 core/client。离线本机执行核心，联网消费授权结果；目录名不自动新增其他桌面平台承诺。

ENG-P001-005 已提供 LWJGL3 Windows 离线启动入口；当前二期入口默认使用私有 P002 知识沙盒输入，正式 Windows 全规则体验仍待实际运行和维护者接收。使用现有 libGDX 1.13.1，字节码目标 Java 17；工程使用 JDK 25 工具链。总入口见[Engine](../README.md)。

## 构建与启动

在 Engine 目录运行：

```powershell
./gradlew.bat :client:test :core:test :pc:installDist --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false'
./gradlew.bat :pc:run --no-daemon
```

`:pc:run` 自动使用仓库根目录为工作目录。默认输入为根目录下 `TestLab/local/P002-知识沙盒输入`；这些经批准的本地输入和原图不包含在公开克隆或发行目录中。输入缺失会报告加载失败，不回退到一期输入。使用 `--inputs TestLab/local/P001-移动沙盒输入` 可显式选择一期原输入进行回归。

也可在仓库根目录运行安装目录中的启动脚本：

```powershell
./Engine/pc/build/install/pc/bin/pc.bat
```

`installDist` 包含程序和依赖库，不包含 JRE，需可用的 `JAVA_HOME` 或 PATH 中的 Java。本票实际运行环境为 Windows 11 amd64、Microsoft JDK 25.0.3。LWJGL 3.3.3 的 JNI/Unsafe 警告保留在验证日志；不据此声称其他运行时已验证。

支持成对参数 `--mode sandbox|demo`、`--inputs CONTENT_DIRECTORY`、`--evidence NEW_RUN_DIRECTORY`，默认模式为 sandbox；相对路径从工作目录解析。默认每次新建 `Engine/pc/build/sandbox-runs/run-<时间>-<随机值>/`，这是可丢弃开发输出；长期验收证据必须用 `--evidence` 指向对应票据 evidence 下的新批次。已存在的批次不会覆盖。可用 `:pc:run --args='--inputs ... --evidence ...'` 传参，含空格的路径需在 args 内加引号。Demo 使用既有随包自有输入及 `Engine/pc/build/demo-runs/`，拒绝 `--inputs`，其专项规则不代表正式沙盒规则。

## 操作与证据

开局区选择 NoFog、普通记忆或硬核遗忘及盟友共享视野，再点击 `Start` 加载并冻结本局参数。`Reopen` 复用本局输入和选项；`Exit match` 关闭当前世界后才可修改。左键点击当前可见单位单选，点击地图空白清空选择；右键点击地图向所选单位提交移动，`Stop` 提交停止。可见异主单位可查看，移动和停止会由核心返回 `not-owner`；失视清除选择。

每批 `manifest.json` 记录运行环境与依赖哈希，新 sandbox 批次标记阶段 P002、票号 ENG-P002-009。`reforge/client-diagnostic.jsonl` 记录实际指令、每步玩家观测、坐标转换、输入哈希及正常完成标记。`format=p001.windows-diagnostic.v1` 继续作为诊断 schema 的历史名称，当前阶段以 manifest.phase 表示。每代 `input-identities` 的 root/scenarioId 表示实际加载内容；P002 配置 `sandbox.properties` 的原字节身份追加到既有 files 列表末尾，P001 缺此配置在独立 sandboxManifest 字段记 exists=false。manifest.inputs 只是启动路径，不能替代后续用户选择的加载根。输入须在验证期间固定并核对前后身份，此记录不提供对外部并发改写的强一致快照保证。

原生操作元数据与截图由独立探针另存 `logs/`、`review/`，应用本身不伪造窗口截图。此格式是专用诊断证据，不是原版轨迹、兼容判定或性能验收。

一期历史批次已实际执行 `:pc:run` 和生成的 `pc.bat`，并保留 Windows 窗口操作与轨迹；这些证据不表示当前正式 P002 已完成实窗验收。入口与诊断单元测试不证明正式 Windows/MuMu 操作、联网、原版行为兼容、最终性能或安全，也不代表随包运行时和正式发布流程完成。
