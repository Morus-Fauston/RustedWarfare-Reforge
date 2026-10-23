# 共享玩法核心入口

固定内容定义、世界状态、指令与模拟规则的自有源码位置；不依赖 libGDX、Android 或具体网络传输。普通对象与适度组合，离线/测试/服务端复用，不复制三套玩法。

## 当前能力

Kotlin/JVM 工程，使用 JDK 25 工具链并生成 Java 17 字节码。已实现：

- `ContentLoader` 的有限 INI/TMX/TSX 子集：只接受当前受控地图与无武器变体范围，未知字段、武器段、不支持编码及错误引用明确拒绝；不是通用原版内容兼容器。
- `SimulationContext` 的初始化、移动／停止／选择、权限校验、定步推进、只读观察、关闭与重开；规划使用唯一 A*，实际移动碰撞继续检查真实地形。
- 每玩家不可变地形知识快照、记忆／硬核遗忘、按单位所属玩家知识规划；显式可信宿主投影发现相关阻挡后，下一次推进自动重规划，不可达则清目标停止。
- 默认关闭的宿主搜索诊断，可显式启用和 drain；不作为玩家 Action 或普通 Observation 字段输出。
- 普通单单位比例推进、转向、减速及清令后的停稳；局部路点维护与按速度/转向能力调整的前视。真实地形采用中心格通行及多格中心线保护，地图外沿仍检查完整单位圆；自主寻路和所属玩家知识隔离保留。

已实现圆格自动视野、普通记忆/硬核遗忘及可选盟友共享。自动视野模式由核心生产地形投影，拒绝同时从`acceptVisibleTerrain`注入；手工投影入口仍只接受受信任宿主输入，不自行证明某格可见。核心本身没有客户端显示功能，也没有战斗、生产、AI、联网、动态避让或完整原版兼容。移动结论限固定代表场景与已有专项，不表示完整正式对拍或最终性能验收通过。

## 构建与验证边界

在 `Engine/` 目录执行：

```powershell
./gradlew.bat :core:compileKotlin --no-daemon
./gradlew.bat :core:test --no-daemon
```

当前测试集有部分夹具依赖被忽略的 `TestLab/local/P001-移动沙盒输入` 及原图；公开克隆不包含这些材料，缺件会失败，不应跳过后称全测通过。工程 Android 插件的配置要求见 [引擎总入口](<../README.md>)，该 SDK 要求不表示核心依赖 Android API。

主要规则见内部[离线核心](<../../Docs/当前决策/离线核心与首个移动切片.md>)；公开源码入口为 [ContentLoader](<src/main/kotlin/com/rustedwarfare/reforge/core/content/ContentLoader.kt>) 和 [SimulationContext](<src/main/kotlin/com/rustedwarfare/reforge/core/sim/SimulationContext.kt>)。独立核心没有导入原版游戏运行时。
