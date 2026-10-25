# 独立引擎代码与总构建入口

本目录用于实际可运行的独立 Kotlin 引擎。目录建立不等于工程可构建或实施授权。

| 入口 | 用途 |
|---|---|
| [core](core/README.md) | 共享玩法核心与内容逻辑 |
| [client](client/README.md) | 共享客户端表现与 UI |
| [android](android/README.md) | Android 应用与打包 |
| [pc](pc/README.md) | Windows 客户端与打包 |
| [server](server/README.md) | Linux 专用服务端与打包 |

平台宿主共用核心，不复制玩法。离线本地模拟与联网权威职责见[离线核心](../Docs/当前决策/离线核心与首个移动切片.md)。目录规则唯一来源为[三线落位](../Docs/当前决策/三线代码与构建落位.md)，任务见[引擎开发线](../.scratch/开发票据/引擎重写/README.md)。

当前已建立 Gradle Wrapper 9.3.1、Kotlin 2.3.20、AGP 9.1.1 工程基线，包含 core/client/pc/android 四个模块；server 暂仅说明。JDK 25 执行构建，共享 JVM 模块和 PC 生成 Java 17 字节码，Android 采用 Java 17 编译目标。未引入原版运行时。

## 当前实现

- `core` 已提供有限 INI/TMX/TSX 加载、无画面移动模拟、指令权限、静态绕障与真实地形碰撞；另有玩家地形知识、记忆／遗忘、所属玩家知识规划、可信宿主投影触发的自动重规划及默认关闭的搜索诊断。
- `client` 共用 libGDX 1.13.1 表现与输入；Windows 和 Android 已有一期离线移动沙盒入口，支持单选、移动、停止、退出和重开。平台操作及限制分别见 [Windows](<pc/README.md>) 与 [Android](<android/README.md>)，不能用构建成功代替操作验收。
- Windows 另有已交付的[50单位寻路与迷雾Demo](<pc/DEMO.md>)，运行 `pc/run-demo.cmd`；使用自有地图和资源，圆形无遮挡视野与地形记忆仅为演示规则。该入口不依赖内部一期地图，不代表Android或整期视野规则完成。
- 二期已接入自动视野、盟友共享及三模式知识沙盒；普通单单位移动包含比例推进、转向、减速、清令与停稳，地形通行与自主寻路协调。交付结论限已记录的代表场景与平台开发验证，完整正式对拍及最终验收尚未完成。原版参考器属于 [Decompile 工具线](<../Decompile/README.md>)，不是核心运行时依赖；现有切片不代表完整原版兼容、手机真机、网络、容量或安全验收。
- 三期开发候选已接普通坦克的攻击、弹丸、伤害与死亡，以及固定资金、预置陆军工厂和真实生产坦克；正式受控入口及操作见 [PC 生产沙盒](pc/README.md#固定生产沙盒)。这些是有限内容与离线规则，不是通用模组、持续经营、完整战斗系统或三期整体完成。开发交接、独立审阅与维护者接收分别记录；后续炮塔、升级、点防御及指挥票不因本段说明视为交付。

## 构建与测试

以下命令在 `Engine/` 目录执行，要求 JDK 25；配置工程还需要 Android SDK（见 [Android 配置](<android/README.md>)）：

```powershell
./gradlew.bat :core:compileKotlin :client:compileKotlin :pc:installDist --no-daemon
./gradlew.bat :core:test :client:test --no-daemon
./gradlew.bat :pc:run --no-daemon
./gradlew.bat :android:assembleDebug --no-daemon
```

`core` 和 `client` 的部分测试、默认一期客户端运行以及 Android 的内部样本打包依赖本机 `TestLab/local/P001-移动沙盒输入` 与原图；这些文件不在公开仓库。公开克隆缺件时不能直接跑完整测试或受控样本，缺件失败不等于测试通过，也不能上传原版素材来补齐公开构建。Windows显式Demo入口使用自有资源。仅源码编译／分发目录生成不证明样本可运行。

本仓库当前以 Windows/JVM 和指定 MuMu 模拟器作为已记录开发环境，未据此验证其他桌面系统、Android 真机或全部 ABI。完整自身行为与性能工具入口见 [SimulationTest](<../SimulationTest/README.md>)。
