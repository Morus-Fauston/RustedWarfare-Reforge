# 公共机制、短测与连续长测

完整层级入口（从仓库根运行，输出必须为新目录）：

```powershell
python -B SimulationTest/ci/public_batch.py --kind mechanism --list
python -B SimulationTest/ci/public_batch.py --kind mechanism --output <new-directory>
python -B SimulationTest/ci/public_batch.py --kind short --output <new-directory>
python -B SimulationTest/ci/public_batch.py --kind pr --isolated --output <new-directory>
python -B SimulationTest/ci/public_batch.py --kind long --isolated --output <new-directory>
```

`mechanism`选择七个公共机制集；`short`选择六个具有短场景的集；`pr`执行这六集的机制/短测和工具机制集；`long`执行内容移动知识、生产战斗、建造经济、维护防御四个同局连续场景。各长场景各自只初始化一次，四个领域场景并不冒称共同一局。选择固定且非空，不读取私有参考集；`--list`只列计划，不写输出或构建。`--isolated`仅复制明确公开源码闭包到系统临时目录。JDK25、Python3.13与Gradle包装器所需网络/缓存适用于本地和CI。

各子集仍调用下文同一`public_check.py`，保留原先单集入口及结果结构。批次根`result.json`记录固定选择、源码身份、实际命令、退出码及耗时，子目录保留完整JUnit、逐Python收据、输入快照和场景档案。任何子集失败立即停止后续集，保存失败日志与已有收据；旧输出拒绝覆盖。具体失败定位沿子目录日志及场景比较结果的阶段/操作/检查点，不以批次外层成功代替断言。

PR更新通过`public-pr.yml`执行完整`pr`入口，保留七份领域产物及批次收据产物；`main`推送通过`public-long.yml`执行同一`long`入口，阶段收尾可手动触发该工作流或本地运行。CI只执行公开无头验证；真实PC、Android宿主/构建/设备、原版参考与正式性能边界见后文，不随批次绿扩大。配置存在不代表已有真实云端结果，实际源码SHA及产物以对应Actions运行记录为准。

旧`pr-first-loop`继续用于首闭环/历史复核；完整层级已由各领域集覆盖相同适用断言，因此不会额外重复选择旧集。`verify-comparison.ps1`和`verify-isolated-comparison.ps1`仍保留原私有比较链及输入要求，不改名成公共干净环境验证。历史冻结脚本与收据保持原版本和失败状态。

## 日常机制回归与冷隔离

开发时可用同一固定公共清单复用兼容Gradle daemon及编译产物：

```powershell
$devRun = [guid]::NewGuid().ToString('N')
python -B SimulationTest/ci/development_check.py --suite development-air-production --output "SimulationTest/build/development-$devRun"
python -B SimulationTest/ci/development_check.py --suite development-air-production --cold --isolated --output "SimulationTest/build/development-cold-$devRun"
python -B SimulationTest/ci/development_check.py --suite platform-basics --suite content-three-formats --list
```

`development-air-production`固定四个客户端类，覆盖直升机/机甲生产、建造热键和动作菜单。可重复`--suite`组合现有命名清单的机制部分：相同模块、类及输入上下文才合并，不同fixture分别执行；Python机制仍真实运行。此入口只测机制，不运行短/长场景或打安装包；阶段及CI完整层级继续使用`public_batch.py`。

默认仅复用编译任务，Test任务禁用缓存及up-to-date，每次实际执行并写入全新输出目录。`--cold --isolated`从公开源码新副本全任务重编，使用single-use Gradle daemon；用户级依赖和OS文件缓存仍可能已热。`--isolated`要求`--cold`。输出不能覆盖旧批次，工具自动取得本工作区Engine/SimulationTest构建锁；Gradle缓存沿用户级配置。

每组保留精确类过滤、生效输入/JDK/daemon身份、逐任务状态、当前开始/结束标记和每方法JUnit XML。默认位置的旧XML列入`ignoredPreviousXml`，不计本次执行；跳过、空类、缺类、失败或只有up-to-date的测试均不能通过。返回码与`result.json`共同判断结果，失败原件保留。

日常入口、单组公共入口和公共批次均可指定`--test-forks 1|2|4`。默认1路；2/4路仅用于独立测试JVM之间的类并行，PC模块始终1路，JUnit方法不并行。Gradle `--max-workers`等于请求值，构建与测试共用该全局上限；Python工具测试仍逐项串行。`--list`显示请求及各模块有效并发，不创建输出。显式`--test-forks 1`可串行复跑同一选择。

公共与日常检查使用同一全新Test输出：XML/binary/html、当前方法开始/完成/结果、任务实际执行和生效fork配置须相互一致。显式测试归档环境变量是外层根；目录继续按运行、模块、worker、类和case隔离，重复case拒绝覆盖，异步工作沿已取得的路径落盘。没有显式根时归档放本次Test输出下；历史归档不会被作为当前执行结果。

方法覆盖同时核对执行前从编译类独立发现的预期集合，不能由XML和监听器共同漏测后相互证明。非空Gradle排除、额外命令行或文件过滤、JUnit标签/引擎过滤一律拒绝并留有效配置；当前发现合同支持本项目现有的普通JUnit Jupiter无参Test，继承/接口、嵌套、动态/参数化/重复测试及自定义方法显示名等尚不支持的形式明确拒绝资格，须先扩展发现合同，不能静默少算。

## 首条公共无头短测

本目录全部为TEST-P004-001新建的自有合成输入，不复制原版地图、INI或图片。`generate_inputs.py`用标准库生成12×12空地、一个单位声明和20×20纯色PNG，并固定文件SHA256；它不生成预期。断言独立写在`contracts/select-move-stop.json`，运行时不改输入或预期。

`scenarios/select-move-stop.json`固定初态、两玩家、动作ID、完整模拟步和结束条件。`suites/pr-first-loop.json`声明本批机制/工具测试及短测入口。地图和单位仅用于自身行为，不能证明原版兼容或真实平台输入。

固定检查点：初态(70,70)；第0步接受选择；第1步接受向右移动，速度1、加速度1、初始朝向90°，位置(71,70)；第2步拒绝外方停止，仍移动到(72,70)；第3步接受己方停止并取消移动；第4、5步保持(72,70)且速度为0。预期来自声明参数下两个完整移动步及已有停止规则，未从运行轨迹反推。

## 复跑

仓库根目录，Python 3.13、JDK 25（Gradle Wrapper自动获取固定Gradle/Kotlin依赖）：

```powershell
python -B SimulationTest/ci/public_check.py
python -B SimulationTest/ci/public_check.py --isolated
```

默认结果进入`SimulationTest/build/public-check/<唯一批次>/`。`--output <全新目录>`可指定证据落点；已有目录拒绝覆盖。`--isolated`仅复制明确公开源码闭包到系统临时目录，没有Docs、TestLab、票据、原版资源或local.properties。用户级Gradle缓存允许复用，不表示离线可构建；网络依赖与本机安装的JDK仍必需。临时源码及失败现场保留，不批量清理。

入口串行构建/运行两组明确JUnit类及安装后的真实CLI，强制本次执行，拒绝空测试、跳过、失败或缺失类；输出源文件身份、命令/退出码、耗时、JUnit与完整轨迹。只覆盖本票列出的首条机制/短测，完整历史迁移和同局长测在后票交付。

GitHub Actions入口为仓库根目录的`.github/workflows/public-pr.yml`，沿用`ci/public-pr.yml`模板和相同的本地命令。PR更新运行机制和首条短测，任务有20分钟上限，结果与公开测试产物归档到该次Actions运行；配置存在不代表云端通过。主分支长测在后续测试收口接入。

## 检查点格式（兼容原schemaVersion=1规则）

`when`仍支持原`event=action-accepted/action-rejected`和`actionKind`。新增`actionId`、`unitId`精确绑定同一个动作结果；可加`stepIndex`和`phase`限定采样。事件只匹配本次观察之前、上次观察之后的动作，不跨观察复用旧事件。

纯模拟时刻检查点同时声明`phase=initial/after-advance`与`stepIndex`，区分初态和第0步推进结果。动作过滤须有event；未知动作/对象、范围外步号、空条件或未知字段均拒绝。未触发必需检查点返回3；错预期返回2并指向ruleId与前后轨迹；执行失败返回4；声明错误返回64。没有新增数值容差。

## 内容、移动与知识迁移（TEST-P004-002）

`content-movement-knowledge` 显式选择48个core类、5个client类与5个tools类。公共机制使用 `generate_content_inputs.py` 独立生成的P001形状输入：30×20、20格、33阻挡、两个外部TSX及自有纯色图片。运动参数和尺寸来自原测试公开断言，未消费的价格等字段采用合成值；它不证明原版素材或兼容性。默认Engine私有夹具入口保持，只有该测试集显式传入公开根。

```powershell
python -B SimulationTest/ci/public_check.py --suite content-movement-knowledge --kind all --list
python -B SimulationTest/ci/public_check.py --suite content-movement-knowledge --kind mechanism
python -B SimulationTest/ci/public_check.py --suite content-movement-knowledge --kind short
python -B SimulationTest/ci/public_check.py --suite content-movement-knowledge --kind long
python -B SimulationTest/ci/public_check.py --suite content-movement-knowledge --kind all --isolated
```

`--list`只打印固定计划。PR使用`--kind pr`，运行机制与两条短测；本机或收口用`all`包含连续长测。机制和场景各自选取，空集、越界路径、缺输入或未知模块/类格式拒绝。旧默认首环入口仍可用。

`movement-knowledge-short`与`movement-knowledge-chain`采用独立自有1×7走廊，70位置的障碍、视距1和速度20。初态己方在110、敌方在10，互不见；第1步上移至90并发现障碍，第2步重规划判不可达。外方停止被拒，己方停止生效。长测从同一局继续返回110，视野退出但记忆保留，直到第100步检查。长测指连续行为链，无现实耐久/性能含义。输入和检查点先固定，未由轨迹生成预期，未更改既有容差。

可选`automaticVision`仅映射既有知识规则，`observers`声明采集玩家，`grid`声明地图格尺寸；加载时与已核哈希地图的真实尺寸核对，轨迹每步按该独立声明查尺寸和全格，不接受采样自报缩小网格。`observation.players.<playerId>`来自核心`observePlayer`，与宿主诊断`units`分开：包含玩家身份/边界、可见单位ID、授权事件/结果及知识格`c<列>r<行>`。每步必须完整且与宿主同局同边界；不启用该声明的旧场景不增加字段。

8个原版输入相关历史测试类保留在`private-content-reference`机制集，仅可在具备原私有TestLab的本机运行：

```powershell
python -B SimulationTest/ci/public_check.py --suite private-content-reference --kind mechanism --list
python -B SimulationTest/ci/public_check.py --suite private-content-reference --kind mechanism
```

私有集拒绝公共隔离或Actions运行；缺件/失败按执行失败处理，不跳过。P001/P002历史场景、契约、支持及CLI仍由原入口消费，原件不迁移到公共目录。专属环境和原版对照资格由TEST-P004-007承接，002不宣称已重跑这些断言。完整575版本逐项映射及六项领域归属修正在私有票据证据内，历史快照不冒作本次运行源码。

## 生产、战斗与指挥

```powershell
python -B SimulationTest/ci/public_check.py --suite production-combat --kind all --list
python -B SimulationTest/ci/public_check.py --suite production-combat --kind pr
python -B SimulationTest/ci/public_check.py --suite production-combat --kind all --isolated
```

`generate_combat_inputs.py`从公开加载语法及既有公开测试条件独立编写合成内容，图片为新建纯色RGBA；`inputs/production-combat/OWNERSHIP.json`记录来源边界和逐文件哈希。未复制原版文件、图片或旧私有场景。默认私有夹具入口保持，显式公开运行才切换测试根。机制集包含生产、攻击、弹道、伤害、观察隔离、巡逻、炮塔、升级及点防御；部分跨票机制作为共享支持，不代表其原所属票已交付。

场景与独立契约分开，`generate_production_scenarios.py`只生成初态和操作。`production-combat-short`从1750资金生产两辆350的坦克、取消队尾退款，经过501个Float更新实际出生；新对象的ID、nonce、生产者、所有者和类型绑定声明的正式生产。随后选择、停止、拒绝外方操作并攻击。`production-combat-chain`从同一局继续到目标死亡；初态、出生、攻击和结束的固定检查点保留同一余额、单位和队列，未修改运行中资金或生命。

`upgrade-cancel-short`调用正式升级与取消接口，核对支付、退款、队列和外方观察隔离；`point-defense-short`由敌方坦克正式开火，固定第7步尚未拦截、第8步扣除一次能量、第9步仅恢复能量，并保持目标生命。精确Float值来自既有机制条件。所有场景逐步采集完整观察，缺产物、健康、玩家资金或宿主经济采样均按证据不足拒绝。公共短测使用10×5自有空地图控制无关全格知识采样体量；其余原机制夹具保持30×20。

PR运行登记的机制及短测，连续长测独立于PR机制JUnit类；`--kind long`或`all`通过安装后的CLI执行同局长测。主分支长测工作流由批次收口接入。此处只证明自身行为，原版对照、真实PC输入及性能仍需各自适用证据。

具名多武器机制与宿主归档/实际Session Runner测试登记在`production-combat`；该集还包含`named-weapons-{ini,json,hjson}-short`三条真实会话。自有输入见`inputs/named-weapons/README.md`，三格式使用相同开发参数与既有受控出生布局，验证near对b、far对c、真实发弹/伤害及整体Stop。合同按稳定武器名查授权观察并核出生事件，不以主炮标量代替第二炮。`platform-basics`包含客户端日志、多炮塔准备、死亡/资源重绑定与比较器反例；Python文件同时登记公共工具闭包。这些无头结果不证明GPU渲染、原版全调度或设备体验。

## 建造、经济与常规升级

```powershell
python -B SimulationTest/ci/public_check.py --suite build-economy --kind all --list
python -B SimulationTest/ci/public_check.py --suite build-economy --kind pr
python -B SimulationTest/ci/public_check.py --suite build-economy --kind all --isolated
```

`generate_economy_inputs.py`独立生成建造、经济、工厂及炮塔升级夹具，复用003自有生产内容；`inputs/build-economy/OWNERSHIP.json`记录每个文件的哈希和来源。默认私有根保留，显式公共命名集才切换输入。机制覆盖合法/非法选址、施工生命周期、排队替换与重试、缺钱等待、收入、升级扣费/退款/完成及失败回滚；既有断言不改变。PR运行这些机制和两条短测，并单独归档本领域产物。

`build-economy-short`核施工扣费和收入、升级支付与取消退款；`build-failure-short`核非资源点拒绝、699资金无法支付700且不创建建筑，以及外方操作初始建造者的`not-owner`拒绝。`build-economy-chain`仅以中心和1750初始资金起步，正式生产建造者、建造抽取器和工厂、获得收入，再把抽取器升至T2；5100个模拟步连续保留同局对象与账本。固定边界包括建造者第500步出生、第1599步核抽取器已完工（首次完工为第1533步）、第2800步核工厂已完工及抽取器第5066步完成升级。预期单独保存在contracts，生成器只写场景输入。

动态建筑由`constructedUnits`绑定正式build动作、nonce、拥有者、类型、建造者和出生步；`convertedUnits`绑定已支持的普通升级动作及完成步，不能任意声明变形。逐步采样施工、收入周期和精确余额；宿主与玩家授权观察的施工字段须完整。缺采样返回证据不足，错预期返回行为失败。连续长测通过安装后的CLI执行，公共短测与机制不能替代真实PC按钮、Android操作、原版对照或性能证据。

## 维护、协同与防御建设

```powershell
python -B SimulationTest/ci/public_check.py --suite maintenance-defense --kind pr
python -B SimulationTest/ci/public_check.py --suite maintenance-defense --kind all --isolated
```

`generate_maintenance_inputs.py`只复用已有自有公开输入并生成新元数据和纯色RGBA图片；逐文件来源与哈希在`inputs/maintenance-defense/OWNERSHIP.json`。旧维护/队列地图保留330,170的障碍格，围攻地图保留第二炮塔位置的空地，两者有独立入口。原默认私有输入和既有断言保留。机制集全量承接维护拒绝、死亡、事务回滚、重开、协同、队列上限、切换、施工及防御战斗顺序；真实平台操作另有验收入口。

`maintenance-repair-short`用两个建造者真实回收造成伤害，再停止并免费修理至满生命；`maintenance-queue-short`核零进度工程回收的100步门槛、Float退款、下一更新才执行队尾重建及正式接续施工；`maintenance-privacy-short`核外方工人、隐藏和缺失目标拒绝，读取正式玩家观察。PR只运行机制和三条短测，单独归档维护防御产物。

`maintenance-defense-chain`从单中心、1750资金起步，16000步内正式生产工人、建设抽取器和工厂、生产工人与坦克。12敌坦克6500步接到正式攻击命令，中心真实毁灭后，由原工厂继续生产、幸存工人继续建炮塔和点防御，最终检查12敌死亡及经营对象存活。检查点与输入预先独立固定；同一世界中没有重置或注入对象。为控制无关知识格归档体积，长测仅采主机观察，玩家隐私由机制/短测覆盖。

`repair`、`reclaim`、`resume-construction`仅映射既有正式动作，`maintenanceContract.basePrices`映射核心既有维护合同。`turret-construction`和`point-defense-construction`显式启用已有防御施工加载能力。动态生产者和建造者共同按独立出生声明解析，父对象必须在命令前出生；不递归读取运行轨迹。`reclaimedUnits`把移除绑定正式回收动作、工人、拥有者、目标和固定移除步。战死对象仍在主机单位集合，回收对象单独登记并从单位集合移除；施工/生产诊断及nonce、已注册队列遵循核心实际保留规则，不能借对象消失删去历史登记。维护场景每步必须具有完整任务、施工和回收字段，玩家任务也保持正式隐藏规则。

每次Runner归档的`manifest.json.costs`记录互不嵌套的纳秒区间：加载/初始化、核心提交/推进/观察（包括玩家DTO投影）、采样/归档及比较；`otherNanos`覆盖身份核验、守卫和未分配开销。分项加剩余严格等于`measuredNanos`，计时止于最终manifest准备，最终manifest写入和archive关闭不计入。构建成本使用外层`result.json.commands[].elapsedSeconds`的真实命令耗时，构建/测试/打包组合命令明确标注，不与Runner分项相加冒充性能基准。这些成本不证明原版、真实PC或Android性能。

## 基本操作基线与共享输入

```powershell
python -B SimulationTest/ci/public_check.py --suite platform-basics --kind pr --isolated
python -B -m unittest discover -s SimulationTest/tools -p test_platform_scenario.py
```

`platform-basics`选择客户端选择、投影、手势、按钮、生命周期和演示入口机制测试，以及五个无头Session短测。操作意图在`platform/scenarios`，独立玩法判据在`platform/contracts`；PC和无头使用相同文件及比较器。短测分别使用已有工厂/坦克、工人、T1炮塔及抽取器/工人的受控初态，覆盖选择、移动、停止、攻击、生产/取消、建造、防御或经济升级/取消以及回收任务。它们不表示从中心起步的完整经营链，也不代替CoreAdapter的精确模拟步场景。

直接运行与只读复核（在仓库根执行，输出必须是不存在的新目录）：

```powershell
python -B SimulationTest/tools/platform_scenario.py session --scenario SimulationTest/public/platform/scenarios/production.json --contract SimulationTest/public/platform/contracts/production.json --distribution SimulationTest/build/install/simulation-test --output <new-directory>
python -B SimulationTest/tools/platform_scenario.py windows --scenario SimulationTest/public/platform/scenarios/production.json --contract SimulationTest/public/platform/contracts/production.json --distribution SimulationTest/build/pc/install/pc --size 1200x800 --output <new-directory>
python -B SimulationTest/tools/platform_scenario.py compare --run <existing-directory>
```

Windows入口需要已构建PC安装包、Python Pillow、可用交互桌面和前台控制权，仅操作它自己启动并核验身份的窗口。支持1200×800和960×640；`construction`、`upgrade`、`maintenance`、`native-production`同样替换场景/契约文件名。输入快照、产物哈希、实际键鼠记录、诊断和截图随批次归档；正常退出和前台恢复缺证不能算通过。公共CI只运行无头入口。

正式动作必须属于本次投递且拥有者、对象、接受结果及调用前后观察精确关联。金额使用同一次调用即时差值，停止检查固定尾部，建设绑定唯一新建事件/对象。仅真实PC点击目标允许按固定地图与实际viewport算出的一像素每轴上限；调用后的目标仍须精确等于正式动作target。原始nonce/身份/轨迹不改写，其余玩法判据及原CoreAdapter精确标准保持。

完整历史账本的`history.spend.at`可声明`construction-created`：施工费在同一正式建造意图内，唯一新工地的创建事件出现时计入。工地必须匹配动作的类型、玩家和目标位置，不能复用旧对象或重复创建；每次观察仍精确核余额。原`action`和`first-step`扣费时点保持，旧封存契约不会被自动改写成新时点。

基线身份包括共享源码/安装包、输入、场景/契约、比较器/驱动、窗口尺寸与平台环境。输入映射、投影、命中、布局、反馈或生命周期变化时补受影响操作：移动/停止/攻击用`production`，建造用`construction`，防御升级用`upgrade`，经济升级/取消及回收用`maintenance`，原生生产卡左键/同卡右键用`native-production`（旧`production`走旧面板，不覆盖ProductionButtonInput）；共同投影或平台接线变化运行受影响组的双尺寸。纯玩法变更优先机制与无头回归，不机械重跑真实PC。旧平台脚本和历史证据保持原入口和身份，适用性按覆盖及源码核对。

Android纯JDK宿主入口（不运行SDK、APK或设备）：

```powershell
python -B SimulationTest/tools/android_host_check.py --distribution SimulationTest/build/install/simulation-test --fixture SimulationTest/public/inputs/content-movement-knowledge/P002-知识沙盒输入 --output <new-directory>
```

它编译现有宿主测试并运行业务/失败边界、知识编码容量、可见性轨迹与存储检查，底层仍使用实际共享客户端。OwnRuns真实文件系统检查需要主机允许创建测试符号链接，缺权限会失败。Android instrumentation/ART和设备证据仍保留原所属入口，本批没有建立Android触控操作基线；最终共享源码兼容构建由四期收尾集中执行。

`result.json`的`toolCosts`分别记录编译命令、宿主命令和命令外成本；宿主命令包含JVM启动、场景及断言，未独立打点的启动、场景、观察时间为`null`。命令的`timeoutSeconds`是超时上限，`elapsedSeconds`才是实际耗时。日志IO失败、进程调用失败和超时分别保留命令、实际耗时及失败类别。`processAttempted`标明是否已调用进程接口；该接口抛普通IO异常而无法确认启动状态时，`processStarted`为`null`。没有实际退出码时`exitCode`为`null`。这些记录不代表Android构建、模拟器或设备验证，也不据此删减必要的画面稳定和参考采样窗口。


## 工具机制与专属验证入口

五期有限海军输入位于`inputs/naval-production`，同样进入`production-combat`集。三格式正常岸边建造者以12000完成海厂T1→同厂T2→6000付费重舰；8200步正式Core/Session计划覆盖离厂、实际水面移动/停止及五炮对地面、空中、固定水下靶的交战。核心归档使用未改normalizer逐行比较完整JSON玩法值，保持原身份与全部中间状态。水面身体/沉没、有限弹道、失败事务、岸线建造/退款/出生和新资源接线的完整测试类一并登记；真实PC双尺寸、合格原版全资源及声音输出仍分开验证。

五期重型机甲的有限输入位于`inputs/heavy-mech-production`，纳入既有`production-combat`集：三格式Core/Session短场景检查四足、普通LAND移动与小炮射击；`HeavyMechProductionSessionTest`通过三份8060q正式Session计划完成builder→T1→同一工厂T2→付费heavy→实际地空战斗，并核完整诊断。重型保留LAND开发变体，不含盾或越崖；新具名小炮图、工厂升级及资源失败回退机制也随该集选择。`public_check.py --suite production-combat --kind all --isolated`执行公开闭包，真实PC双尺寸及私有完整原图资源验证另记，不能由这些无头结果推定。

```powershell
python -B SimulationTest/ci/public_check.py --suite tooling --kind mechanism --list
python -B SimulationTest/ci/public_check.py --suite tooling --kind mechanism --isolated --output <new-directory>
python -B SimulationTest/tools/specialist_check.py --entry reference-host --list
python -B SimulationTest/tools/specialist_check.py --entry reference-host --bindings <fixed-bindings.json> --output <new-directory>
```

`tooling`固定选择比较器、采集与诊断、失败保全、性能工具自身的合成机制测试，以及工作流、共享操作比较器、变换、DEC证据核验和选择器的Python测试。Gradle完成机制和安装包后逐文件运行Python例，避免实际Session CLI缺包。每类JUnit及每份Python收据必须非空、无失败且无跳过；保留命令、耗时及源码哈希。PR沿相同入口执行，并单独上传`public-tooling-evidence`。本集不启动正式测量、原版运行时或设备。

五个p003公共合成夹具文件为`test_bf_observer_transform.py`、`test_gboot_transform.py`、`test_original_approved_layout.py`、`test_original_dependency_layout.py`与`test_original_input_transform.py`。其余九份smali相关控制及一份APK ZIP元数据控制读取固定私有输入，保留既有全部正反断言，实际输入身份另见批次记录，沿`transform-original`逐文件运行；不能用整个p003目录的glob冒公共测试。独立文件执行器`ci/python_check.py --test <file> --output <new-file>`只运行指定文件，拒绝零测试、跳过及覆盖旧收据。

`MotionV2CoreInputsTest`的十二项可移植输入/真实加载断言进入公共集；原Windows junction断言完整移至`MotionV2CoreInputsWindowsTest`，本地以`:test --tests com.rustedwarfare.reforge.testing.cross.MotionV2CoreInputsWindowsTest`运行。原`:test`仍发现两个类，Linux公共集不以跳过Windows例求绿。`OriginalMotionComparisonTest`、`MotionV2StandardTest`、`MotionV2SourceAdmissionTest`、`ReplanPerfTest`和`PrefixDiagnosticSerializationTest`继续使用TestLab、原固定helpers/JAR或私有suite，以完整类为专属入口；没有删除断言或以公共样本替代原版资格。

注册表`specialist-entries.json`说明参考桥纯JDK/SDK stub、原版比较与采集、设备、PC输入、正式性能、资格/开发测量、私有工具控制和本机目录工具的原入口、资源边界、必需文件角色及平台。`reference-host`与`reference-sdk`的完整编译/逐JVM场景见`Decompile/reference-bridge/README.md`；读取这些文档需要本地完整工作区。`original-compare`用CrossMain的`original-motion-v1`契约分派到既有O.v1比较器，须固定批准标准及双方合格采集；tool-fixture结果不等于运行时兼容通过。

正式性能沿既有workflow性能计划的`mode=measure`和`maintainerApproved=true`执行WholeChainPerfMain，资格检查为`mode=qualify`；两者都需要固定协议、包清单与成功构建收据，数值最终结论仍单独判定。PathPerfBatchMain当前必须显式传`--geometry-version p002.dev.center-motion.v2`，只属于开发几何测试，历史省略该参数的120批命令不能当成当前正式入口。

预检bindings格式为`{"released":true,"files":{"<role>":{"path":"<canonical-absolute-file>","sha256":"<fixed-sha256>"}}}`，角色必须与所选注册项完全相同，Manifest角色绑定已有清单文件而非目录。预检只读这些文件与身份，不递归证明清单成员完整、不检测运行时可用性，也不执行命令模板。`released`只声明资源/范围已释放，不能授予新的设备、原版或正式性能权限。结果始终`executed:false`；`ready`/退出0仅表示声明文件可读且哈希吻合，缺件/漂移为`evidence-insufficient`/退出3，未释放资源或平台不符为`blocked`/退出64。未知入口、无效参数和旧输出直接拒绝；任何这些状态都不能作为专属验证通过。历史结果保留原环境、命令和失败状态。


## 首次长链归档与独立重判

`python -B SimulationTest/tools/workflow.py scenario-run --plan <固定计划.json> --output <新批次>` 为所选公开 Core／Session 场景冻结源码、输入、JDK 身份，构建一次分发包；先用固定真实好档和语义坏档检验消费者，再执行场景。可选 `archive-production` 的 long 层包含三格式各一条 Core 与 Session 正常建造生产战斗链，不加入日常七组默认选择。

计划严格包含 `schemaVersion:1`、当前绝对 `root`、`suite`、`kind:short|long`、唯一 `cases` ID 列表和 `qualifications:[{path,sha256}]`。每种选定 backend 都须有资格索引；索引路径相对计划文件，SHA 固定索引原字节。`scenario-qualify --index <索引> --distribution <固定分发包> --output <新目录>` 也可单独检查。索引包含 schemaVersion、backend、fixtureKind（Core 为 core-archive，Session 为 legacy-raw）、scenario、contract 和 cases；每例有唯一 case、相对 path、expectedExitCode。至少一个好例 0 和一个语义／证据坏例 2 或 3；坏例必须声明 expectedCategory（旧 Core 索引兼容 category），Session raw 另有 sha256。进程失败或输入错误不能充当消费者资格。

批次保留 candidate/source、candidate/package、资格原件、每场景完整 raw、Core normalized、命令与标准输出错误；execution、consumption、summary、payload／seal、terminal／result 分别记录执行、独立判定、封存和打印结果。complete 仅代表所选采集完整，不代表契约或整期通过。进程失败保留部分原件并停止后续场景，不偷偷重跑。封存清单和哈希用于本工具归档完整性核验，不构成外部可信签名。

`scenario-restore --run <原批次> --output <新目录>` 原字节复制候选和已封存文件，以 restore.json 记录显式位置映射；不改写旧清单或哈希。`scenario-compare --run <原批次或恢复副本> --output <另一个新目录>` 使用档内固定 Core 包和 Python 消费者独立重判，不构建或推进模拟。恢复成功只说明完整归档复制通过，仍需单独重判；不完整原件的恢复不会变为通过。重判、恢复目录必须在原批次之外。历史 Windows 相对定位符可显式解析反斜杠，原件保持不变。消费者升级后的新规则资格须另行验证，此入口默认固定档内版本。

输出只可进入项目 build、对应票 evidence 批次或系统 Temp。生产 run 仍要求私有工作区布局守卫；公开克隆缺守卫不能冒称完整工作区验收。恢复副本可使用档内 `candidate/source/SimulationTest/tools/workflow.py scenario-compare`，限该档捕获的公开输入与同平台运行条件；不代表 Android、PC 操作、网络或原版对拍。Session 启动通过单个 lib 通配符保持命令短，Windows 用 Unicode 扩展路径和短 Temp 工作目录，全部实际 JAR 身份仍逐一记录。

Session 的可选 `history` 契约用于显式声明长链检查义务，rules 仍须逐一对应全部 intentId。initialBalance 为独立初始金额；spend 声明 intentId、正 amount、at（action 或 first-step）。noActionIntents 显式列无动作意图；其余意图检查固定动作种类及接受状态、完整步数／时序与逐观测余额。mechLegs 声明类型对应腿数，检查姿态字段、有限数值和腿序；始终拒绝死亡／不存在单位残留炮态。可选 ownerPlayerId、initialOwnedTypes、finalUnits、minimumBirths 检查声明的初态、终态和射击义务；projectileBirthsRequired 只适用于可见出生记录完整的固定场景，不能推广到任意战争迷雾记录。弹体路径可用 canonical 非负索引或唯一 token；不猜测不存在的 id 字段。新 Session 记录含 generation；混入旧代次或缺失代次拒绝，旧版整档无观测代次仍可按原读法追溯。这些是独立声明的证据义务，不替代核心玩法或新增通用玩法规则。


长链动作还须与紧邻的 before/action/after、固定 actor 和实际存活对象绑定；非选择指令核对象归属，已有建造类型、升级、目的地和目标身份逐项对应意图。旧生产动作没有产品字段时，通过同厂 factoryQueues 的 FIFO 前缀不变、恰好新增一个指定产品核对，不由终局反推请求。Session 合同声明的攻击精确点按该固定场景核对，不增加通用鼠标命中容差。独立资格 CLI 同样取得构建资源锁并限制输出目录；候选 worker 先写新 Temp，完整字节复制回父批次并保存位置映射，失败也保留。终端须实际 flush 成功才记录打印通过。
