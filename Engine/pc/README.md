# PC 客户端与打包入口

现行目标为 Windows；平台启动与随包运行时打包共用 core/client。离线本机执行核心，联网消费授权结果；目录名不自动新增其他桌面平台承诺。

ENG-P001-005 已提供 LWJGL3 Windows 离线启动入口，维护者体验接收待确认。使用现有 libGDX 1.13.1，字节码目标 Java 17；工程使用 JDK 25 工具链。总入口见[Engine](../README.md)。

## 构建与启动

在 Engine 目录运行：

```powershell
./gradlew.bat :client:test :core:test :pc:installDist --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false'
./gradlew.bat :pc:run --no-daemon
```

`:pc:run` 自动使用仓库根目录为工作目录。默认输入为根目录下 `TestLab/local/P001-移动沙盒输入`；这些经批准的本地输入和原图不包含在公开克隆或发行目录中。

也可在仓库根目录运行安装目录中的启动脚本：

```powershell
./Engine/pc/build/install/pc/bin/pc.bat
```

`installDist` 包含程序和依赖库，不包含 JRE，需可用的 `JAVA_HOME` 或 PATH 中的 Java。本票实际运行环境为 Windows 11 amd64、Microsoft JDK 25.0.3。LWJGL 3.3.3 的 JNI/Unsafe 警告保留在验证日志；不据此声称其他运行时已验证。

支持成对参数 `--inputs ABSOLUTE_CONTENT_DIRECTORY`、`--evidence NEW_ABSOLUTE_RUN_DIRECTORY`。默认每次新建 `TestLab/runs/P001-自身行为验证/03-windows-client/run-<时间>-<随机值>/`；已存在的批次不会覆盖。可用 `:pc:run --args='--inputs ... --evidence ...'` 传参，含空格的路径需在 args 内加引号。

## 操作与证据

左键点击单位单选，点击地图空白清空选择；右键点击地图向所选单位提交移动。`Stop` 提交停止；`Exit match` 关闭当前世界；`Reopen` 从内容目录重新加载。无控制者单位可查看，移动和停止会由核心返回 `not-owner`。

每批 `manifest.json` 记录运行环境与依赖哈希，`reforge/client-diagnostic.jsonl` 记录实际指令、每步核心观测、坐标转换、输入哈希及正常完成标记。原生操作元数据与截图由本票独立探针另存 `logs/`、`review/`，应用本身不伪造窗口截图。此格式是专用诊断证据，不是原版兼容判定。

本票已实际执行 `:pc:run` 和生成的 `pc.bat`，并保留 Windows 窗口操作与轨迹。未验证 Android、联网、原版行为兼容、性能或安全，也未完成随包运行时和正式发布流程。
