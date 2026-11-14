# 三格式既有建造与经济输入

ENG-P008-003。六家族start/factory/economy/queue/maintenance/construction各有INI/JSON/Hjson，逐字节复用既有公共自有地图、PNG、场景marker；不含原版/派生资源。全既有单位走相同作者格式，包括builder四版本以及采集器/制造仪六形态；场景marker保持原格式。JSON/Hjson采用已审native扁平元数据或rw-reforge.unit.v2分节结构，具名动作/武器/弹体、animation_idle对象、core.dont_load及根copyFrom、skipInheritance保留有限语义。费用按十进制原词形输出，不从轨迹倒推预期。

私有生成器、翻译清单与所有源/目标SHA见003对应证据域；公共运行不依赖私有目录。目录存在/登记不表示测试通过或原版完整对拍。INI native保留field=value原字节且以.ini载体明确命名，旧.properties仍兼容。只迁移本期已支持单位，不开放全字段或新玩法。
