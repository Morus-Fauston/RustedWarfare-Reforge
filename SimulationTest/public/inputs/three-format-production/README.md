# 三格式生产、防御与资金输入

ENG-P008-002 的有限兼容输入，逐字节复用现有公共自有地图/PNG/场景标记。来源为 production-combat 的 production/upgrade、build-economy 的 start、maintenance-defense 的 turret/point-defense/construction；未加入原版或派生资源。

每个家族的 ini/json/hjson 仅翻译本票已有单位：tank、landFactory、commandCenter、laserDefence、三个炮塔及其固定模板。INI 原生元数据保留 field=value 原字节，扩展名用 .ini；旧 .properties 入口继续兼容。结构化 native 保留既有显式 native-format 与顶层字段；分节单位采用 rw-reforge.unit.v1，具名武器/弹体/动作。同一 JSON/Hjson schema，数字按原十进制文本规范化，没有经浮点转换费用或反推轨迹。builder、extractor/fabricator 和场景 marker 留原格式，由后续票承担其三格式迁移。

生成脚本与逐文件源/目标 SHA-256 在002私有证据域 public-input-generation-01.json；公共运行不依赖该私有目录。注册、命令与结果由票据报告记录，目录存在不代表已通过。图片/地图保留原自有来源许可及公共README边界。

另有headless-defense与economy，分别逐字节复用production-combat/defense与build-economy/upgrades的自有输入，确保新增公共短测只更换作者格式/身份，完整沿用原场景规则；第二份生成收据保留其独立来源。
