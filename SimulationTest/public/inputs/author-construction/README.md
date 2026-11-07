# 自有作者施工输入

INI、JSON、Hjson 使用同一份 constructor/rw-reforge.constructor.v1 建造者定义与 factory/rw-reforge.factory.v1 普通建筑主体。三格式分别位于 ini、json、hjson；spbuilder 继承同格式目录中的 base-constructor.ini，声明 constructionTypes=workshop。主体价700、建造速率.001、生命1200，初始资金3000；工作范围85、准备30及衰减／维修语义沿用现有机制。此处 factory 尚无生产、出口或升级能力。

## 普通内容菜单

在PC客户端内容包页输入本仓库下 `SimulationTest/public/inputs/author-construction/json/package` 的绝对路径，依次 Check、Enable，选择spbuilder并开始。选己方蓝色建造者，点击workshop卡片，再选合法空地；施工实际扣费一次，资金3000降至2300。Esc／右键取消预览，Stop停止作业；重新选择己方建造者，右键未完成的己方工地可续建。INI和Hjson目录等价。

`extended` 是只改内容的INI扩展示例，列表包含workshop至workshop_7；同样Check／Enable后选择spbuilder。鼠标移入卡片区域滚轮访问后续建筑，最后一张完整露出后可以正常选择施工。它没有新增引擎或菜单ID分支。

`water` 是Core验证夹具：与ini主体相同，仅显式地基改为WATER。AuthorBuildingFoundationTest显式使用该目录的naval-shore.tmx和岸边出生点，在水域施工、在陆地拒绝；普通菜单的默认陆图不等于该Core场景。

## 有限字段与拒绝

constructor支持配置稳定小写ID、生命、半径／显示半径、LAND移动参数、价格／建造速率、视野、身体与残骸PNG、逗号分隔的唯一constructionTypes。当前既有包络要求速度不大于.8、视野至少15；移动半径还须通过实际地图的核心初始化资格。

普通factory主体支持稳定ID、正生命与半径、NONE移动、显式LAND／WATER地基、非负整数价格、正有限binary32速率、身体／可选背图／残骸PNG及视野。当前普通施工半径不大于30，实体足迹为-1,-1,1,1，放置足迹为-1,-1,1,3；其他形状按未支持输入拒绝。可建目标在最终启用内容集合中必须解析到已有施工定义。父模板和图片按实际来源路径解析，并受包路径边界及完整来源身份约束。

未知能力、非法地基、非有限数、越权路径、继承环、重名列表及悬空目标均拒绝，修复输入可重新加载。旧native builder／工厂格式与固定身份保持原入口严格规则；本输入不扩展其资格。

## 证据范围与来源

已实际运行三格式Loader／Core／Session、协作续建、缺钱／取消／越界、死亡／同帧回滚重试／隐藏观测，以及PC1200×800、960×640普通卡片和七建筑滚动下单。正式自有Core短案和Session短案在production-combat注册表中分别登记；固定Core nonce轨迹可精确比较，原生正常下单UUID按实保留，不能据此声称跨原生档案字节相等。本票仍在阶段实施流程，独立整票审阅与维护者最终接收分别登记，不以本说明自动结票。

矩形PNG及地图／地形图从本仓库既有自有示例原字节复用：builder图来自naval-production/ini/production-package/units，workshop图来自platform-basics/production/units；陆图来自three-format-tanks/ini，岸线图来自naval/ini。extended和water均从本批ini自有输入复制后只改配置。不是原版素材、原版平衡或兼容性证据。多产品与升级按后续票交付，Android设备、真实网络及整期性能矩阵另行验收。
