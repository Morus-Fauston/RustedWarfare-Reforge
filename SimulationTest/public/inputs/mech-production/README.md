# 双足机甲与一级机甲工厂自有输入

本目录只含自有配置、程序绘制的占位 PNG 和自有 30×20 地图；不包含原版游戏代码、像素或音效。INI、JSON、带注释的 HJSON 使用同一内容语义和固定数值。

每种格式的 `package` 是单一 `mechGun` 战斗内容；`production-package` 组合 `builder`、`mechFactory` 和 `mechGun`。生产代表场景从本地建造者及3000开发资金开始，玩家先建厂，再支付600生产机甲。布局与资金属于开发样例，不能作为原版开局依据。

自有基础资源：机甲身体18×31、横轴腿47×7、脚6×18；工厂主体和底图74×106、残骸72×88。身体随主炮方向，腿脚跟随移动姿态；机甲没有独立炮塔图或残骸图。图片不定义物理半径或动画时钟。

正式场景和合同在公共 `scenarios`、`contracts`、`platform` 中，通过既有 `production-combat` 集登记：

- `mech-<format>-short`：真实 Core Runner 加载、Move/Stop、姿态归档和全档案比较。
- `mech-<format>-session-short`：真实客户端 Session 选择、步行、Stop、攻击、发弹和掉血。
- `mech-production-<format>-chain`：同局从空地建厂到实际生产、离厂和战斗死亡。固定命令nonce；Float32生产率`.0012`在834次更新后完成，从1200开始的队列于2033出生。双方都是有武器的机甲，`control=none`不关闭自动还击，场景包括双方死亡。

从仓库根执行（输出须为全新目录）：

```powershell
python -B SimulationTest/ci/public_check.py --suite production-combat --kind pr --isolated --output <new-directory>
python -B SimulationTest/ci/public_check.py --suite production-combat --kind long --isolated --output <new-directory>
```

机制集还覆盖有限工厂组合、建造和生产资金失败、厂外硬地形及恢复、腿脚图片身份、坏输入、来源改变与修复、三格式完整采样相等、旧工厂和资源准备回归。公共无头验证不证明真实PC画面、原版逐帧兼容、Android设备、网络或性能；真实PC和私有原版资格证据另行保存。
