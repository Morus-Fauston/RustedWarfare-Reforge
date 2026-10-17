# 共享客户端表现入口

Android/Windows 共用的 libGDX 表现、输入组织与 Scene2D.ui 位置；客户端表现不代替玩法裁决，联网只消费授权结果。

Kotlin/JVM 模块依赖 core 与固定 libGDX 1.13.1，字节码目标 Java 17。ENG-P001-005 已接通 Windows 离线受控移动沙盒，维护者体验接收待确认。主要规则见[技术栈](../../Docs/ADR/ADR-005-技术栈与工程边界.md)，总入口见[Engine](../README.md)。

## 客户端边界

- `ControlledScenario` 调用现有内容加载器，只读取批准的 P001 TMX/TSX、无武器单位 INI 和其图片；固定两玩家、两单位初态，不是通用场景文件解释器。
- `SandboxSession` 把单选、移动、停止提交到 `SimulationContext`，包括对无控制者单位的越权请求；移动与权限结果由 core 裁决。退出关闭核心，重开创建新世界并清空选择。
- `SandboxLoader` 在单一后台线程读取；取消使旧任务结果不可发布。绘制与核心推进仍在 libGDX 渲染线程执行，未承诺多核玩法模拟。
- `BootstrapScreen` 使用 Scene2D HUD 和 FitViewport；`MapProjection` 负责核心 y-down 与 UI y-up 转换，`SandboxGraphics` 绘制真实地图与单位图片、路径和选择环。
- `ClientEvidence` 是可选诊断接口，PC 接入独立 JSONL 记录器；不是测试线正式轨迹协议或兼容性判定器。

宿主以每秒 60 次的实验展示节奏推进 `delta=1 sim-tick`；每帧最多推进 8 步且保留余量，不跳模拟步。这不是原版 tick 频率结论。

## 验证与平台交接

从 Engine 目录运行 `./gradlew.bat :client:test :core:test --no-daemon`；测试依赖本地批准输入 `TestLab/local/P001-移动沙盒输入`，公开克隆不包含这些资料。Windows 启动见 [pc](../pc/README.md)。

本票共享适配测试 10 项、原有核心测试 57 项通过；真实 Windows 窗口完成单选、移动、停止、绕障、不可达、越权拒绝及退出重开。完整证据由内部 ENG-P001-005 票据引用。

Android 后续消费本模块，需独立完成路径接入、触控命令、平台生命周期、构建与指定环境操作验收；Windows 鼠标按钮不能直接作为触控规则。共享入口已避免 `Path.of` 的 Android 26–33 API 差异，但本票未构建或运行 Android。当前默认 BitmapFont 的 HUD 为英文，中文路径可输入但部分字符显示为方框，完整路径保存在诊断日志中。
