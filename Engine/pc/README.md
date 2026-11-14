# PC 客户端与打包入口

现行目标为 Windows；平台启动与随包运行时打包共用 core/client。离线本机执行核心，联网消费授权结果；目录名不自动新增其他桌面平台承诺。

Windows 使用 LWJGL3 离线启动入口。使用现有 libGDX 1.13.1，字节码目标 Java 17，工程使用 JDK 25 工具链。开发验证与维护者最终体验分别记录，总入口见[Engine](../README.md)。

## 构建与启动

在 Engine 目录运行：

```powershell
./gradlew.bat :client:test :core:test :pc:installDist --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false'
./gradlew.bat :pc:run --no-daemon
```

`:pc:run` 自动使用仓库根目录为工作目录。默认进入主菜单。从 Skirmish 打开开局设置，指定可用的内容目录，等待检查通过后按 Start。内容包和资源包各有管理页。加载期间可取消，返回设置后可更改内容，局内 Reopen 复用已冻结的设置。

`--development true` 打开离线开发设置，允许选择测试场景或启用沙盒布置。随包的六期 A 自有开发场景提供指挥中心或模块蜘蛛开局，开局资源和单位以所选场景为准。

显式 `--inputs CONTENT_DIRECTORY` 仍从工作目录解析相对路径，使用原P002窗口标题；可用 `--inputs TestLab/local/P002-知识沙盒输入` 或 `--inputs TestLab/local/P001-移动沙盒输入` 回归旧私有输入。这些本地资料不随公开克隆或发行目录提供，缺件会加载失败。

也可在仓库根目录运行安装目录中的启动脚本：

```powershell
./Engine/pc/build/install/pc/bin/pc.bat
```

`installDist` 包含程序和依赖库，不包含 JRE，需可用的 `JAVA_HOME` 或 PATH 中的 Java。本票实际运行环境为 Windows 11 amd64、Microsoft JDK 25.0.3。LWJGL 3.3.3 的 JNI/Unsafe 警告保留在验证日志；不据此声称其他运行时已验证。

支持成对参数 `--mode sandbox|demo`、`--inputs CONTENT_DIRECTORY`、`--development true`、`--evidence NEW_RUN_DIRECTORY`，默认模式为 sandbox，相对路径从工作目录解析。沙盒诊断通过显式开发模式或 `--evidence` 启用。开发模式默认写入 `Engine/pc/build/sandbox-runs/run-<时间>-<随机值>/`。长期验收证据用 `--evidence` 指向对应票据下的新批次。可用 `:pc:run --args='--inputs ... --evidence ...'` 传参，含空格的路径需在 args 内加引号。Demo 使用随包的自有输入及 `Engine/pc/build/demo-runs/`。

## 选择画面素材

Windows沙盒和demo默认使用随包自有占位画面。仅把解包目录放在程序旁边不会启用原版；首次启动也不要求准备原版素材。

需要原版画面时，在开局页的 `Visual assets` 中选择 `Original visuals (Android 1.15 / 176)`，填写解包目录，点击 `Save and apply`，准备成功后再开局。目录应直接包含 `AndroidManifest.xml`、`assets/` 和 `res/`；安装包运行时的目录提示为安装根目录下的 `Rusted Warfare`。可以填写其他位置以及中文、空格路径。切回 `Public placeholder visuals` 后保存会保留原目录，重启仍使用上次保存的来源。退出对局后才能修改来源；沙盒 `Reopen` 和demo `Restart` 每次都会重新校验所选素材。

仅支持固定的Android `com.corrodinggames.rts`、版本`1.15`、版本号`176`，按内置清单校验manifest及30张PNG的精确身份。Steam版、其他版本、经过替换的图片或任意模组资源不能作为这套固定来源。原目录只读，程序仅整理当前已有画面需要的静态图层和地形；动画、声音、原版游戏核心均不在此入口内。原版素材由使用者自行准备，原图及本地派生图不随公开包提供。

也可用参数临时覆盖当前进程：

```powershell
./Engine/pc/build/install/pc/bin/pc.bat --visuals original --original-assets "C:\Games\Rusted Warfare"
./Engine/pc/build/install/pc/bin/pc.bat --mode demo --visuals placeholder
```

`--visuals` 只接受 `placeholder` 或 `original`。单独指定 `--original-assets DIRECTORY` 等同本次选择原版，相对路径按启动时工作目录解析；单独指定 `--visuals original` 使用保存的目录或安装根目录提示。`--visuals placeholder` 与 `--original-assets` 冲突，同一新参数重复也会拒绝。临时覆盖时页面显示 `Only this run`，`Apply (this run)` 同样不会覆盖保存设置。

设置和私有缓存保存在 `%LOCALAPPDATA%\RustedWarfare-Reforge\visual-assets\v1\`；缺少该环境变量时使用用户目录下的`AppData\Local`。设置文件为`settings.properties`，缓存按固定映射身份分代放在`cache/`，不写回解包目录。准备时遇到缺件、损坏、错误版本、缓存锁忙或写入失败会明确报错并阻止加载，保留开局页供修正；不会自动换成占位或混用两套图片。损坏的保存设置也会报错，可在页面明确选择、应用后修正。运行中的一局固定使用已经校验的缓存代，恢复时再次核验；旧代不会被逐张覆盖，程序不自动清理旧代。

## 操作与证据

开局区选择 NoFog、普通记忆或硬核遗忘及盟友共享视野，再点击 `Start` 加载并冻结本局参数。`Reopen` 复用本局输入和选项；`Exit match` 关闭当前世界后才可修改。左键点击当前可见单位单选，点击地图空白清空选择；右键点击地图向所选单位提交移动，`Stop` 提交停止。可见异主单位可查看，移动和停止会由核心返回 `not-owner`；失视清除选择。

当前可建造工人的菜单支持普通 `X` 选择基础炮塔预览、`J` 选择点防御预览；当前单经济升级卡支持普通 `U` 提交该升级。Ctrl/Shift/Alt组合不触发这些快捷动作，按住不重复。预览本身不扣费，确认位置才提交；Esc或地图右键取消预览。普通建造确认后清当前选择，保存的控制编组继续保留；关闭对局才清理整局编组。Ctrl加数字保存编组，数字召回，Shift加数字追加召回。

每批 `manifest.json` 记录运行环境与依赖哈希，阶段和主题由实际加载的场景决定；默认四期内容沿用 P006、`defense-construction-windows-client`、ENG-P006-007 的既有诊断分类，不能把历史票号当作本次审阅或接收状态。`reforge/client-diagnostic.jsonl` 记录实际指令、每步玩家观测、坐标转换、输入哈希及正常完成标记。`format=p001.windows-diagnostic.v1` 继续作为诊断 schema 的历史名称。每代 `input-identities` 的 root/scenarioId 表示实际加载内容；配置原字节身份及缺件状态沿既有记录。manifest.inputs 只是启动路径，不能替代后续用户选择的加载根。输入须在验证期间固定并核对前后身份，此记录不提供对外部并发改写的强一致快照保证。

原生操作元数据与截图由独立探针另存 `logs/`、`review/`，应用本身不伪造窗口截图。此格式是专用诊断证据，不是原版轨迹、兼容判定或性能验收。

## 固定生产沙盒

三期开发候选可用同一正式 `sandbox` 入口加载批准的生产输入目录。该目录须含以下固定 `production.properties`，以及受控地图、坦克/工厂描述和批准的图片；这些私有输入与原版素材不随公开克隆或分发包提供，缺件不得静默换场景或冒运行通过。

```properties
format=rw-reforge.production-sandbox.v1
variant=production
```

用既有 `--inputs CONTENT_DIRECTORY` 传入目录，或在退出对局后修改 Content folder 再 Start。固定开局资金1750，无持续收入；场景预置己方工厂、普通坦克和敌方普通坦克，敌方可自动射击工厂。

选择己方工厂后，`Produce 350` 实际支付并入队；`Cancel last` 取消最后待产项并全额退费。Funds、Queue和百分比来自核心当前观察，未完成前不会用UI计数替代单位。余额不足会显示 `insufficient-funds`，直到下一次玩家操作；Stop不停止工厂队列。每次出厂都是真实可选坦克，左键选择、右键空地移动/可见敌人攻击，Stop清运动和攻击；堵硬墙不会回队列、退已完成款或传送到安全点。

Reopen和Exit后重新Start均创建新世界，恢复初始资金、生命与空队列。固定PC分发包只证明对应源码/输入；实际窗口流程、独立审阅、Android集中构建和维护者接收分别留证。当前候选未据此承诺联网、Android真机、完整经营或全部原版内容兼容。

## 固定两分支升级沙盒

同一入口接受带 `upgrade.properties` 的私有固定输入，内容为 `format=rw-reforge.upgrade-sandbox.v1`、`variant=two-branches`。该标记优先于点防御、炮塔和生产标记；标记损坏或任一完成态缺失会加载失败。必须同时提供基础、二级机枪与初级火炮定义、common和对应图片，不从磁盘临时补读升级目标。

选择己方基础炮塔后，`Gun 1000`／`Art 1600` 实际支付并进入队列，旁边的 `Cancel` 取消该分支最后一项并全额退款。Funds和进度来自玩家授权观察；余额不足或重复动作显示核心拒绝。完成后保留同一炮塔ID，按完成时生命比例更换形态；机枪继续普通单体射击，火炮发射固定落点范围弹。黄色弹点和短暂橙色命中圈只画授权事件，命中圈本身不证明伤害或击杀。

只提供这两个首段分支，不显示更高升级；生产、Stop、选择与编组入口保持原规则。Reopen恢复基础炮塔、700生命、1750资金和空队列。诊断将此输入标为 `ENG-P005-004`，记录全部25件固定输入身份和owner可见队列；不输出敌方队列、内部成本／速率或预测坐标。本入口不新增Android设备运行、原版弹道逐帧等价或任意模组兼容承诺。

一期历史批次已实际执行 `:pc:run` 和生成的 `pc.bat`，并保留 Windows 窗口操作与轨迹；这些证据不表示当前正式 P002 已完成实窗验收。入口与诊断单元测试不证明正式 Windows/MuMu 操作、联网、原版行为兼容、最终性能或安全，也不代表随包运行时和正式发布流程完成。
