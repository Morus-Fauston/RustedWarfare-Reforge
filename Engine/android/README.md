# Android 应用与打包入口

Android 平台启动、生命周期适配与 APK 构建入口，共用 core/client，不另写玩法。离线本机执行核心，联网消费授权结果。

AGP 9.1.1、compileSdk/targetSdk 34、minSdk 26、Build Tools 36.0.0、Java 字节码目标 17。AndroidLauncher 已注册 MAIN/LAUNCHER，使用系统默认应用图标；libGDX 1.13.1 后端启动共享离线沙盒，并打包 armeabi-v7a、arm64-v8a、x86、x86_64 原生库。ENG-P001-006 已在指定 MuMu index 0（Android 15 / SDK 35 / x86_64）完成实际操作取证；真机、其他 ABI 和性能尚未验证，维护者人工验收未完成。

从 `Engine/` 执行 `./gradlew.bat :android:assembleDebug --no-daemon`，APK 输出为 `android/build/outputs/apk/debug/android-debug.apk`。SDK 通过本机 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 配置；`local.properties` 不得提交或公开。需要预先安装 SDK Platform 34 与 Build Tools 36.0.0。

使用默认 debug 签名，不提供发布签名或发布包。横屏显示受控地图与两名玩家单位：点单位单选，点地面向所选单位发移动指令，Stop 停止，Exit match 释放本局，Reopen 创建新局。另一玩家单位可检查选择表现，移动和停止仍由同一核心拒绝。系统返回键退出 Activity；再次启动创建新局。总入口见[Engine](../README.md)。

后台暂停模拟并在 GL 线程释放局内绘制、UI 与原生缓冲；恢复重建表现、保留同局与选择，不补算后台时间。销毁（包括非 finishing 的 Activity 重建）关闭核心与内容加载器，不保存对局；本轮验证 Home/恢复、返回退出/启动及 `Activity.recreate()`，不保证任意后台情形存活。

`:android:stageSandboxAssets` 从本机批准的 P001/P002 输入读取固定12个文件（两套各4件描述、P002配置和3张共用原图），使用 AGP Variant API 生成 APK assets；原字节和相对路径不改，启动时解出到应用私有目录后交给共享加载器。此默认包输入仍是移动／知识沙盒；P005共享代码构建兼容不表示已打包或在Android操作生产／升级场景。原版 PNG 仅为内部测试引用，未取得公开分发结论；APK 不得作为公开包。公开克隆缺少 TestLab 与参考素材时，构建此内部样本将明确失败。

调试证据写入应用外部私有目录 `files/runs/run-<时间>-<唯一后缀>/client-diagnostic.jsonl`；这是客户端诊断流，不是正式兼容性轨迹协议。必要设备测试可构建 `:android:assembleDebugAndroidTest`，安装测试 APK 后显式选择已授权实例执行 `am instrument -w com.rustedwarfare.reforge.android.test/com.rustedwarfare.reforge.android.XmlSecurityInstrumentation`；测试自行准备输入并检查 XML 安全和 Activity 重建。

构建后的包结构检查：`python tools/verify_apk.py build/outputs/apk/debug/android-debug.apk --aapt2 <SDK>/build-tools/36.0.0/aapt2.exe`。该检查会拒绝缺启动入口、DEX、字体、原生库或许可说明的包；它不操作设备，不证明实际启动或玩法通过。

## 独立存储元数据诊断

测试 APK 另有 `com.rustedwarfare.reforge.android.metadata.StorageMetadataInstrumentation`，保留原 XML/Activity 测试入口。AGP 9.1.1 会覆盖首个 instrumentation 节点的名称，因此测试 Manifest 显式将旧入口放在第一项，新诊断放在第二项。

这是指定实例的开发诊断：要求 API28+、User0 和 MAIN 默认进程，仅支持已观察安装配对 `(MAIN10053, TEST10052)` 与 `(MAIN10051, TEST10052)`。MAIN 进程、Context 和 PM UID 必须精确相同，不可在两个配对间混用；其它身份不符即停止。数值允许集不证明设备身份，主机调用方仍须精确绑定当次获配实例、实时端点、实际 UID、完整 APK 与签名；#1 的接收器不得接受 #0 的10053。正常 Application/provider 绑定仍会发生，框架可生成 cache、ART 或 usage 状态；不要把“诊断代码不写业务文件”理解为系统零写。安装前保护必要旧数据，只在已批准的实例与动态 ADB 端点执行。

请求恰含七个字符串：`bindingId`、`mainApkSha256`、`mainApkBytes`、`testApkSha256`、`testApkBytes`、`certificateSha256`、`certificateBytes`。长度为正十进制，摘要为完整小写 SHA256；两包完整 DER 必须相同。它不接受数据路径。真实进程、Context/PackageManager、完整 code APK 与完整签名身份匹配后，依次 lstat `/storage`、`/storage/self`、`/storage/self/primary`；仅对后两项中已见的 symlink 各读一次链接文本，不继续读取返回的路径，不枚举或读取业务数据。

每个阶段的 Bundle 含 `phase`、`metadata`、`result`，最终通过 `finish` 返回；保留全部状态、首错、errno、partial 和独立传输错误。主机必须接收到完整最终 JSON，检查 `complete`、`success` 和仪表结果，不能仅凭 ADB exit0 判通过。`oldRunsProtected`、`externalRootVerified`、`privateTreeVerified`、`installAllowed` 及产品运行标志始终 false。

纯逻辑主机验证不需要 Android SDK 或内部素材。从仓库根使用 JDK25，输出放系统 Temp：

```powershell
$metadataClasses = Join-Path ([System.IO.Path]::GetTempPath()) ('rw-metadata-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $metadataClasses
$metadataSources = 'Engine/android/src/androidTest/java/com/rustedwarfare/reforge/android/metadata'
javac --release 8 -encoding UTF-8 -d $metadataClasses "$metadataSources/MetadataJson.java" "$metadataSources/MetadataProbe.java" 'Engine/android/src/test/java/com/rustedwarfare/reforge/android/metadata/MetadataProbeHostTest.java'
if ($LASTEXITCODE -ne 0) { throw 'Metadata host compilation failed' }
java -cp $metadataClasses com.rustedwarfare.reforge.android.metadata.MetadataProbeHostTest all
if ($LASTEXITCODE -ne 0) { throw 'Metadata host checks failed' }
```

预期输出 `PASS all: 81 checks`，另保留 `JSON_SUCCESS` 与 `JSON_FAILURE` 合成样本。含两UID配对、混配/未知/shell/test/root拒绝及新配对的完整身份/APK/DER坏控；主机验证覆盖编排与失败路径，不能证明 Android 权限、旧数据保护或正式沙盒运行。

## 自身运行数据读取与备份

测试 APK 的第三个入口为 `com.rustedwarfare.reforge.android.ownruns.OwnRunsProtectionInstrumentation`，保留前两个入口。它先校验与上述诊断相同的实例、进程、两包完整 APK 和 DER 身份，再只读自身外部私有 `files/runs`，每个合法 run 只允许 `client-diagnostic.jsonl`。不接受任意数据路径，不创建或删除设备业务文件。正常框架绑定仍可能维护 profile、cache 或 ART 状态。

请求所有值均为字符串。`inventory` 恰含十键：`schema=e9-ownruns-request-v1`、`mode=inventory`、`manifest=-` 与上述七个身份键。它保留实际 names 顺序、节点属性及完整文件 SHA256，不发送内容。`backup` 使用 `mode=backup`、刚接收 inventory 生成的完整 manifest，并增加第十一键 `manifestSha256`；只有 manifest 中 `copy=1` 的叶子传输原始内容，`copy=0` 仍重新核验。不能复用其它安装身份或旧批次的 manifest。

合法 run 名为 `run-[0-9]+-[0-9a-f]{8}`；逐级检查固定路径及普通目录，叶子以 `O_RDONLY | O_CLOEXEC | O_NOFOLLOW` 打开并前后核对象身份。最多128个 run、每叶16MiB、总64MiB、观测30秒；主机还须设置调用超时。身份、未知 names、链接、读取或对象变化、预算、传输任一失败均保首错和 partial。仅自身 files/runs 缺失可通过成功的定向父查询证明；该查询不传输其它父 names，畸形数组不得接成缺失。

状态使用 `e9-ownruns-frame-v1` 有序 ASCII 分片，内容为原 bytes 的 Base64；主机必须核 sequence/part/last、事件和 summary 摘要、terminal、内容偏移与完整 size/SHA，再以新文件保存原 bytes。ADB exit0 或终态文本不足以证明备份完成。成功也不自动放行安装、证明整个私有树安全或正式产品运行。主机接收与设备运行依赖本机私有执行材料，不随公开仓库提供。

以下主机命令只编译纯 Java seam，自建夹具和 classes 放系统 Temp，不需 Android SDK。从仓库根使用 JDK25：

```powershell
$ownRunsClasses = Join-Path ([System.IO.Path]::GetTempPath()) ('rw-ownruns-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $ownRunsClasses
$ownRunsSources = 'Engine/android/src/androidTest/java/com/rustedwarfare/reforge/android'
$ownRunsTests = 'Engine/android/src/test/java/com/rustedwarfare/reforge/android/ownruns'
javac --release 8 -encoding UTF-8 -d $ownRunsClasses "$ownRunsSources/metadata/MetadataJson.java" "$ownRunsSources/metadata/MetadataProbe.java" "$ownRunsSources/ownruns/OwnRunsJson.java" "$ownRunsSources/ownruns/OwnRunsProbe.java" "$ownRunsTests/OwnRunsProbeHostTest.java" "$ownRunsTests/OwnRunsProtocolHostTest.java" "$ownRunsTests/OwnRunsFilesystemHostTest.java" "$ownRunsTests/OwnRunsDirectedAbsenceHostTest.java"
if ($LASTEXITCODE -ne 0) { throw 'OwnRuns host compilation failed' }
java -cp $ownRunsClasses com.rustedwarfare.reforge.android.ownruns.OwnRunsProbeHostTest backup
if ($LASTEXITCODE -ne 0) { throw 'OwnRuns inventory/backup checks failed' }
java -cp $ownRunsClasses com.rustedwarfare.reforge.android.ownruns.OwnRunsDirectedAbsenceHostTest all
if ($LASTEXITCODE -ne 0) { throw 'OwnRuns directed absence checks failed' }
```

预期分别为 `PASS 8 ownruns checks` 和 `PASS 59 directed-absence checks`。完整主机套件可另用 `-Downruns.fixtureDir=<系统Temp内新夹具目录>` 执行 `OwnRunsProbeHostTest all`，其中真实 Windows symlink 创建需要本机支持；该套件也不证明 Android descriptor、SELinux 或产品玩法。指定 MuMu0 已完成实际 inventory 和七份旧 run 的原 bytes 备份及非作者接收；此前 run-as 拒绝和失败证据保留。正式 Android 沙盒、原版参考器及整期验收分别验证。

## Gradle 主机检查入口

从 `Engine/` 执行 `./gradlew.bat :android:verifySandboxHostChecks --offline --no-daemon`。该入口用JDK25、Java17目标单独编译现有纯JDK harness及其纯TEST seam，再实际执行assets、metadata、OwnRuns完整套件及定向缺失检查。夹具各在新系统Temp，输出在 `android/build/sandbox-host-checks/classes`；真实symlink夹具按旧测试约定保留，不触碰设备数据。非零退出使Gradle任务失败。

这些 `src/test` 文件是main-method harness，默认 `testDebugUnitTest` 没有为它们配置JUnit和TEST seam类路径，不能用JUnit零用例或默认任务结果代替上述入口。该入口不会将TEST probe或host harness打入MAIN；也不代表Kotlin、APK、ART、真实输入或存储权限验证。多会话共享同一checkout时须先取得构建窗口，不并发写共同build目录。#1新增UID支持需单独实测，不继承#0历史运行通过。

## 固定功能仪表入口

`com.rustedwarfare.reforge.android.SandboxFunctionalInstrumentation`仅TEST APK，显式三个字符串参数`mode`、`shared`、`exercise`；主机先绑定当次实例、包/签名和数据基线，不能直接套旧APK命令。`controls`/`explore`支持三模式×共享，`retention`/`visibility`/`recreate`限MEMORY/HARDCORE且不共享，`unreachable`限NO_FOG，`recovery`限MEMORY且不共享；`pause`用实际本应用后台/恢复。跟随本期不新增。

入口通过实际触点、公开诊断全流及有限释放链检查；`visibility`另采本应用原尺寸Surface PNG并分块传回，仍需主机完整重建与独立视觉检查。`recovery`仅暂移经APK bytes核对的派生必需引用，恢复原文件再实际Start，不覆盖仓库输入。TEST将UI回调错误回传仪表线程并保留首错；真实窗口焦点在既有预算内等待，不用固定睡眠冒输入通过。出现HELD或进程残留时保全现场，不自动重跑或读取inventory。

这些入口及主机oracle是开发验证工具；构建/主机绿不表示真实MuMu功能、其它ABI、手机真机或整期验收已通过。原始失败、完整新run和APK身份须随每次运行保留。
