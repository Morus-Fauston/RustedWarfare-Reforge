# 共享客户端表现入口

Android/Windows 共用的 libGDX 表现、输入组织与 Scene2D.ui 位置；客户端表现不代替玩法裁决，联网只消费授权结果。

Kotlin/JVM 模块依赖 core 与固定 libGDX 1.13.1，字节码目标 Java 17。ENG-P001-005 已接通 Windows 离线受控移动沙盒，维护者体验接收待确认。主要规则见[技术栈](../../Docs/ADR/ADR-005-技术栈与工程边界.md)，总入口见[Engine](../README.md)。

## 客户端边界

当前二期默认入口使用知识沙盒，提供NoFog、普通记忆、硬核遗忘与盟友共享选项，开局后冻结规则。绘制、命中、HUD和路径消费同一玩家授权帧，失视清除选择；己方单位圈为绿、同队盟友为黄、敌方为红，同队共享不授予控制权。以下`ControlledScenario`和一期验证记录保留其原覆盖，不代表二期整体验收。

- `ControlledScenario` 调用现有内容加载器，只读取批准的 P001 TMX/TSX、无武器单位 INI 和其图片；固定两玩家、两单位初态，不是通用场景文件解释器。
- `SandboxSession` 把单选、移动、停止提交到 `SimulationContext`，包括对无控制者单位的越权请求；移动与权限结果由 core 裁决。退出关闭核心，重开创建新世界并清空选择。
- `SandboxLoader` 在单一后台线程读取；取消使旧任务结果不可发布。绘制与核心推进仍在 libGDX 渲染线程执行，未承诺多核玩法模拟。
- `BootstrapScreen` 使用 Scene2D HUD 和 FitViewport；`MapProjection` 负责核心 y-down 与 UI y-up 转换，`SandboxGraphics` 绘制真实地图与单位图片、路径和选择环。
- `ClientEvidence` 是可选诊断接口，PC/Android 分别接入 JSONL 记录器；不是测试线正式轨迹协议或兼容性判定器。

三期受控候选另接普通坦克战斗与固定工厂生产，以上一期说明保留其历史覆盖。`ControlledScenario` 对 `combat.properties` 和 `production.properties` 分别选择固定场景，继续通过公开内容加载器读取，不解释任意原版单位或场景文件。`SandboxSession` 将右键敌方目标转为 `AttackAction`；工厂生产/尾项取消只提交真实 typed 动作，生产 nonce 使用 UUID 去连字符，余额和进度均来自核心玩家观察。

Scene2D生产控件复用现有皮肤和输入消费顺序：显示本人资金、所选获准工厂的队长/队首百分比与静态价格，按钮点击不再向地图投令。拒绝反馈保留到下一次玩家操作；产物面板使用单位类型，完整公共身份仍在诊断中。绘制、选择及证据 sink 继续消费同一 `PlayerObservation`，不向UI传宿主生产诊断。平台操作见 [PC](../pc/README.md#固定生产沙盒)，Android生产触控与实际设备操作不由共享源码或PC运行替代。

宿主以每秒 60 次的实验展示节奏推进 `delta=1 sim-tick`；每帧最多推进 8 步且保留余量，不跳模拟步。这不是原版 tick 频率结论。

## 验证与平台交接

从 Engine 目录运行 `./gradlew.bat :client:test :core:test --no-daemon`；测试依赖本地批准输入 `TestLab/local/P001-移动沙盒输入`，公开克隆不包含这些资料。Windows 启动见 [pc](../pc/README.md)。

ENG-P001-006 本轮共享适配测试 13 项、核心测试 62 项通过；真实 Windows 窗口和指定 MuMu 实例都完成单选、移动、停止、绕障、不可达、越权拒绝及退出重开。完整证据由内部 ENG-P001-006 票据引用；不代表人工收票或原版兼容。

Android 使用显式 `touchControls` 入口：点单位调用选择，点空地移动并保留选择；Windows 仍为左键单选、右键移动。两端共用核心指令、停止及重开逻辑。Android 暂停时在 GL 线程释放表现、保留模拟上下文，恢复只重建表现，不补时、不重建世界；暂停后的平台销毁调用 `destroyPausedHost` 幂等释放核心与加载器。未处于暂停或已清理状态时，不允许从平台线程释放仍活跃的 GL 资源。

共享入口已避免 `Path.of` 的 Android 26–33 API 差异；本轮实测仅 Android 15 / SDK 35 / x86_64。当前默认 BitmapFont 的 HUD 为英文，中文路径可输入但部分字符显示为方框，完整路径保存在诊断日志中。
