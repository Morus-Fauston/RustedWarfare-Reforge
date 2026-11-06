# 自有作者水面内容

INI/JSON/Hjson三份等义water-combat包使用spcruiser新ID，通过现役INI基模板和template_gun继承port/starboard两门炮。基模板来自named-weapons自有样例（去掉模板独立加载标记），三张矩形PNG逐字节复用naval-production自有图片；没有原版代码、像素或运行依赖。

4000生命、40半径、0.8速度及70/100发射间隔为受控开发输入，非原版基准或平衡承诺。正常作者WATER受控场景复用既有NavalProjectionTest同图(500,300)/(650,300)几何，旧完整海军包六选项继续建造者起手。这里展示普通包检查/选择/Start入口的航行、交战和两炮姿态；正常施工/升级/排队获取链在对应后续样例票验证，沙盒布置另行标记。

每个格式目录带有逐字复用的自有 `maps/naval-shore.tmx` 和 `assets/naval-ground.png`，可作为独立 `--inputs` 根。PC 用现有 `WindowsLauncherKt --mode sandbox --inputs <本格式目录> --visuals placeholder` 启动；在 Content 菜单输入本格式的 `package` 目录，Check、Enable，选择 `spcruiser`，再 Start。无需开启沙盒布置。己方起点 (500,300)，敌方 (650,300)；选中己方，右击 (500,400) 航行，再右击敌方攻击。显示名省略时使用内容ID。

包类型 `water-combat` 和严格的 `battleship` 是不同入口。作者入口使用相同 INI/JSON/Hjson 语法、`copyFrom` 文件继承和节模板，保留源闭包；归一化后复用现役 WATER 移动及具名武器装配。允许稳定小写ID和有限的已有数值/攻击掩码；未支持字段、坏引用、继承环、重名、路径逃逸及非有限数拒绝。该有限水面能力初高为0，拒绝潜艇/飞行/工厂/建筑等未授权组合；反向速度仍只接受现役0或1规则。旧固定重舰资格不因作者入口放宽。

两门炮来自 `[template_gun]`，`port`/`starboard` 分别覆盖横向炮位与70/100间隔，共享已有 `round` 弹体及炮声。`4000/40/0.8/70/100` 只是自有开发输入；没有新增武器机制或原版平衡结论。作者舰尸体使用现役普通水体行为，不新增原固定重舰专属沉没契约。

正式 Core 样例为 `SimulationTest/public/scenarios/author-water-{ini,json,hjson}.json` 及同名 contracts；其 `water-combat` loader 调用同一源协调器，并核对声明的继承源身份。正常 Session 样例在 `public/platform/scenarios/author-water-*.json`，对应 `public/platform/contracts/`。默认 `production-combat` 套件登记六个短案及随票机制测试。Core/Session/PC操作证据分别记录，任何一种都不代表 Android、真实网络、原版兼容或维护者最终接收。
