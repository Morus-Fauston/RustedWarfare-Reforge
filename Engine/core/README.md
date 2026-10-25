# 共享玩法核心入口

固定内容定义、世界状态、指令与模拟规则的自有源码位置；不依赖 libGDX、Android 或具体网络传输。普通对象与适度组合，离线/测试/服务端复用，不复制三套玩法。

## 当前能力

Kotlin/JVM 工程，使用 JDK 25 工具链并生成 Java 17 字节码。已实现：

- `ContentLoader` 的有限 INI/TMX/TSX 子集：无武器变体、普通坦克战斗定义和固定工厂分别使用显式入口。未知字段、不支持能力/编码及错误引用明确拒绝；不是通用原版内容兼容器。
- `SimulationContext` 的初始化、移动／停止／选择、权限校验、定步推进、只读观察、关闭与重开；规划使用唯一 A*，实际移动碰撞继续检查真实地形。
- 每玩家不可变地形知识快照、记忆／硬核遗忘、按单位所属玩家知识规划；显式可信宿主投影发现相关阻挡后，下一次推进自动重规划，不可达则清目标停止。
- 默认关闭的宿主搜索诊断，可显式启用和 drain；不作为玩家 Action 或普通 Observation 字段输出。
- 普通单单位比例推进、转向、减速及清令后的停稳；局部路点维护与按速度/转向能力调整的前视。真实地形采用中心格通行及多格中心线保护，地图外沿仍检查完整单位圆；自主寻路和所属玩家知识隔离保留。

已实现圆格自动视野、普通记忆/硬核遗忘及可选盟友共享。自动视野模式由核心生产地形投影，拒绝同时从`acceptVisibleTerrain`注入；手工投影入口仍只接受受信任宿主输入，不自行证明某格可见。核心本身没有客户端显示功能，也没有战略AI、联网、通用动态避让或完整原版兼容。移动结论限固定代表场景与已有专项，不表示完整正式对拍或最终性能验收通过。

## 固定战斗与生产开发接口

当前三期候选通过公开 `Scenario`、`SimulationContext.submit/advance/observePlayer` 接通普通坦克与陆军工厂，客户端不自行扣款、推进队列或创建单位。`AttackAction` 使用已有权限与可见目标门；普通弹丸实际推进、伤害和死亡属于同一步候选事务。

工厂的 `ProduceAction` 带待产类型和32位小写十六进制 nonce；`CancelProductionAction` 取消该类型的最后一个待产项并全额退款。固定坦克价格350、单头 Float 速率 `.002f`，第501次生产更新才完成，余量不结转给尾项。完成项对应一个真实、满生命的新单位，出生当步不自行更新；工厂死亡只退仍未完成的项。Stop不取消生产，取消不回收本局已用 nonce，关闭重开才清理本局状态。

出厂临时计时为30次自身更新。期间直供移动目标并允许略过建筑成本，保留硬地形、多格中心线和地图完整圆外沿；归零当次接回所属玩家规划。建筑释放及规划模式依各自规则处理，不保证拥挤分离或堵口必定脱困。宿主 `Observation.production` 仅作诊断，玩家 DTO 不含出生计时、创建序、producer关联或数值对象ID；玩家只取得本人资金、当前获准工厂队列与普通授权对象。

世界生产、出厂、身份和含出生回滚的公开接缝测试分别见 `WorldProductionTest`、`FactoryExitTest`、`ProductionIdentityTest` 与 `ProductionTransactionTest`。运行依赖批准的私有夹具，测试/窗口、Android构建、真机及最终接收是不同证据层级；具体开发交付状态以单票为准。

## 构建与验证边界

在 `Engine/` 目录执行：

```powershell
./gradlew.bat :core:compileKotlin --no-daemon
./gradlew.bat :core:test --no-daemon
```

当前测试集有部分夹具依赖被忽略的 `TestLab/local/P001-移动沙盒输入` 及原图；公开克隆不包含这些材料，缺件会失败，不应跳过后称全测通过。工程 Android 插件的配置要求见 [引擎总入口](<../README.md>)，该 SDK 要求不表示核心依赖 Android API。

主要规则见内部[离线核心](<../../Docs/当前决策/离线核心与首个移动切片.md>)；公开源码入口为 [ContentLoader](<src/main/kotlin/com/rustedwarfare/reforge/core/content/ContentLoader.kt>) 和 [SimulationContext](<src/main/kotlin/com/rustedwarfare/reforge/core/sim/SimulationContext.kt>)。独立核心没有导入原版游戏运行时。
