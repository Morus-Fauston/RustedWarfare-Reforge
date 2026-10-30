# 首条公共无头短测

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

PR运行机制及三条短测，连续长测独立于PR机制JUnit类；`--kind long`或`all`通过安装后的CLI执行同局长测。主分支长测工作流由批次收口接入。此处只证明自身行为，原版对照、真实PC输入及性能仍需各自适用证据。

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

`platform-basics`选择客户端选择、投影、手势、按钮、生命周期和演示入口机制测试，以及三个无头Session短测。操作意图在`platform/scenarios`，独立玩法判据在`platform/contracts`；PC和无头使用相同文件及比较器。短测分别使用已有工厂/坦克、工人和T1炮塔的受控初态，覆盖选择、移动、停止、攻击、生产/取消、建造以及升级/取消。它们不表示从中心起步的完整经营链，也不代替CoreAdapter的精确模拟步场景。

直接运行与只读复核（在仓库根执行，输出必须是不存在的新目录）：

```powershell
python -B SimulationTest/tools/platform_scenario.py session --scenario SimulationTest/public/platform/scenarios/production.json --contract SimulationTest/public/platform/contracts/production.json --distribution SimulationTest/build/install/simulation-test --output <new-directory>
python -B SimulationTest/tools/platform_scenario.py windows --scenario SimulationTest/public/platform/scenarios/production.json --contract SimulationTest/public/platform/contracts/production.json --distribution SimulationTest/build/pc/install/pc --size 1200x800 --output <new-directory>
python -B SimulationTest/tools/platform_scenario.py compare --run <existing-directory>
```

Windows入口需要已构建PC安装包、Python Pillow、可用交互桌面和前台控制权，仅操作它自己启动并核验身份的窗口。支持1200×800和960×640；`construction`、`upgrade`同样替换场景/契约文件名。输入快照、产物哈希、实际键鼠记录、诊断和截图随批次归档；正常退出和前台恢复缺证不能算通过。公共CI只运行无头入口。

正式动作必须属于本次投递且拥有者、对象、接受结果及调用前后观察精确关联。金额使用同一次调用即时差值，停止检查固定尾部，建设绑定唯一新建事件/对象。仅真实PC点击目标允许按固定地图与实际viewport算出的一像素每轴上限；调用后的目标仍须精确等于正式动作target。原始nonce/身份/轨迹不改写，其余玩法判据及原CoreAdapter精确标准保持。

基线身份包括共享源码/安装包、输入、场景/契约、比较器/驱动、窗口尺寸与平台环境。输入映射、投影、命中、布局、反馈或生命周期变化时补受影响操作：移动/停止/攻击用`production`，建造用`construction`，普通升级用`upgrade`；共同投影或平台接线变化运行三组双尺寸。纯玩法变更优先机制与无头回归，不机械重跑真实PC。旧平台脚本和历史证据保持原入口和身份，适用性按覆盖及源码核对。

Android纯JDK宿主入口（不运行SDK、APK或设备）：

```powershell
python -B SimulationTest/tools/android_host_check.py --distribution SimulationTest/build/install/simulation-test --fixture SimulationTest/public/inputs/content-movement-knowledge/P002-知识沙盒输入 --output <new-directory>
```

它编译现有宿主测试并运行业务/失败边界、知识编码容量、可见性轨迹与存储检查，底层仍使用实际共享客户端。OwnRuns真实文件系统检查需要主机允许创建测试符号链接，缺权限会失败。Android instrumentation/ART和设备证据仍保留原所属入口，本批没有建立Android触控操作基线；最终共享源码兼容构建由四期收尾集中执行。


## 工具机制与专属验证入口

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
