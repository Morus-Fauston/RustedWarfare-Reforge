# reference-bridge：公开源码与主机测试

本目录是自有的隔离参考桥接辅助代码，不是完整原版参考器、原版源码重建工程或 Engine 的运行时依赖。公开克隆可执行下列主机测试；不需要内部 TestLab 数据，不需要原版 APK。不新增构建框架或依赖，直接调用已有 JDK / Python / Android SDK。

## 代码与能力边界

| 入口 | 作用与依赖 |
|---|---|
| [io](<src/main/java/com/rustedwarfare/reforge/reference/io/>) / [测试](<src/test/java/com/rustedwarfare/reforge/reference/io/>) | 私有路径策略、首次失败锁存、受限 IO 路由；纯 JDK |
| [protocol](<src/main/java/com/rustedwarfare/reforge/reference/protocol/>) / [测试](<src/test/java/com/rustedwarfare/reforge/reference/protocol/>) | 请求顺序、重放、发布重试状态；纯 JDK |
| [probe](<src/main/java/com/rustedwarfare/reforge/reference/probe/>) / [测试](<src/test/java/com/rustedwarfare/reforge/reference/probe/>) | b(F) 边界回调观测；纯 JDK；主机合成调用不证明真实原版单步 |
| [GbootBootstrap](<src/main/java/com/rustedwarfare/reforge/reference/boot/GbootBootstrap.java>) / [测试](<src/test/java/com/rustedwarfare/reforge/reference/boot/>) | Android 引导与 IO 门禁；编译需要 platform 34 的 android.jar；下述主机场景只触及无已初始化 Context 的公开 seam |
| [GbootPrivateBackendFactory.smali](<src/main/smali/com/rustedwarfare/reforge/reference/boot/GbootPrivateBackendFactory.smali>) | 13 行自编写 smali；返回原版 gameFramework/e/c、构造原版 e/d，只能在另行授权的隔离原版环境合片；下述 javac 不编译、不包含它 |

原版 runtime 不发布、不进入独立 core；不得为编译本目录复制原版类进项目。android.jar 只有 SDK stub，不是 Android runtime；编译或主机测试通过不证明 Context、Uri、AssetManager、PFD、SAF 或原版 backend 实际运行。当前未提供完整参考器加载/采集流程，未解除修改原版包的签名、安装或设备运行限制。

## Windows PowerShell：纯 JDK 最小入口

从仓库根运行以下各节，沿用同一 PowerShell 会话变量。准备 **JDK 25**（java、javac 在 PATH；或设置 JAVA_HOME）、Python 3.11+。命令使用 `javac --release 8` 生成 Java 8 目标字节码，**不表示已在 JDK 8 验证**。JDK 25 可能输出 release 8 过时警告，以退出码判断成败。实际验证平台为 Windows；其他系统未验证，不能直接沿用 Windows 的 classpath 分隔符 `;`。

```powershell
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path  # 当前目录必须为仓库根
if (-not (Test-Path 'Decompile/reference-bridge/src')) { throw 'Run from repository root' }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$run = Join-Path ([System.IO.Path]::GetTempPath()) ('rw-reforge-docs-java-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'jdk-classes'
$fixtures = Join-Path $run 'fixtures'
$null = New-Item -ItemType Directory -Path $classes,$fixtures
$main = Join-Path $root 'Decompile/reference-bridge/src/main/java/com/rustedwarfare/reforge/reference'
$test = Join-Path $root 'Decompile/reference-bridge/src/test/java/com/rustedwarfare/reforge/reference'

# 保留每次 argv、输出和退出码；兼容 Windows PowerShell 的原生 stderr 警告处理。
function Invoke-Checked([string]$Exe, [string[]]$Argv) {
    $log = Join-Path $run ([guid]::NewGuid().ToString() + '.log')
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $Exe @Argv 2>&1 | Out-File -LiteralPath $log -Encoding utf8
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    [ordered]@{exe=$Exe; argv=$Argv; exit=$code; log=$log} |
        ConvertTo-Json -Compress -Depth 8 |
        Add-Content -LiteralPath (Join-Path $run 'argv-exit.jsonl') -Encoding utf8
    Write-Host "exit=$code log=$log"
    if ($code -ne 0) { throw "Command failed; see $log" }
}
Invoke-Checked $javac @('-version')
Invoke-Checked $java @('-version')

# 三组分别编译，无 Android SDK / 原版类依赖。
foreach ($group in 'io','protocol','probe') {
    $sources = @((Get-ChildItem -LiteralPath (Join-Path $main $group),(Join-Path $test $group) -Filter '*.java').FullName)
    Invoke-Checked $javac (@('--release','8','-encoding','UTF-8','-d',$classes) + $sources)
}
Invoke-Checked $java @('-cp',$classes,'com.rustedwarfare.reforge.reference.io.FatalLatchTest')
Invoke-Checked $java @('-cp',$classes,'com.rustedwarfare.reforge.reference.protocol.RequestLedgerTest')
Invoke-Checked $java @('-cp',$classes,'com.rustedwarfare.reforge.reference.probe.BoundaryStepProbeTest')
```

上述最小入口不移动或删除文件。预期分别输出 `PASS FatalLatch`、`PASS RequestLedger`、`BoundaryStepProbeTest PASS J01-J09`。Probe 覆盖正常采集、浮点原始位、未进入/不配对、重入/多次调用、真实其他线程、嵌套请求、快照不变与非法参数，不证明原版实际接线。

另有 [PrivatePathPolicyTest](<src/test/java/com/rustedwarfare/reforge/reference/io/PrivatePathPolicyTest.java>) 和 [ReferenceIoTest](<src/test/java/com/rustedwarfare/reforge/reference/io/ReferenceIoTest.java>)，已包含在 io 编译中，但**不在上述最小运行集**：它们会移动自建夹具，并尝试符号链接（受权限影响）；禁止 move 的验证轮次不要运行。允许这种自建夹具操作时，运行参数均为已创建的 `$fixtures`：

```powershell
# 可选；本次公开入口修复未运行这两个测试。
Invoke-Checked $java @('-cp',$classes,'com.rustedwarfare.reforge.reference.io.PrivatePathPolicyTest',$fixtures)
Invoke-Checked $java @('-cp',$classes,'com.rustedwarfare.reforge.reference.io.ReferenceIoTest',$fixtures)
```

## Gboot：SDK stub 编译与独立 JVM 场景

已有 Android SDK platform 34 即可；本页不安装 SDK。优先读取 ANDROID_SDK_ROOT / ANDROID_HOME，未设置时在 Windows 默认 SDK 位置查找；非默认位置请先设置其中一个环境变量。这里仅将 android.jar 放入编译/主机加载 classpath，**不调用 SDK 中的真实 Android 实现**。

```powershell
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$androidJar = Join-Path $sdk 'platforms/android-34/android.jar'
if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) { throw 'Set SDK path with installed platform 34' }
$bootClasses = Join-Path $run 'boot-classes'
$null = New-Item -ItemType Directory -Path $bootClasses
$compileCp = $classes + ';' + $androidJar
$bootSources = @((Get-ChildItem -LiteralPath (Join-Path $main 'boot'),(Join-Path $test 'boot') -Filter '*.java').FullName)
Invoke-Checked $javac (@('--release','8','-encoding','UTF-8','-cp',$compileCp,'-d',$bootClasses) + $bootSources)
$bootCp = $bootClasses + ';' + $compileCp
$bootPackage = 'com.rustedwarfare.reforge.reference.boot.'
```

Gboot 使用静态首次失败锁存；**每个参数场景都要一个新 JVM**，不可在单 JVM 中循环调用 main。下面每次 Invoke-Checked 都启动一个 java 进程。

### State IO：15 个场景

完整场景来自 [GbootStateIoTest](<src/test/java/com/rustedwarfare/reforge/reference/boot/GbootStateIoTest.java>)，第二个参数是保留夹具的已有目录。覆盖真实自有文件流关闭、codec 布尔门禁、primary/suppressed 与首次失败身份；不调用真实原版 codec、不初始化 Context。

```powershell
$stateCases = @(
    'close-input-normal','close-output-normal','codec-accepted',
    'decode-failed','encode-failed','close-input-failed','close-output-both-failed',
    'primary-input','primary-output','prior-fatal-cleanup','prior-fatal-normal-close-failed',
    'input-roots-missing','output-roots-missing','temp-roots-missing','wrapper-absent'
)
foreach ($case in $stateCases) {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootStateIoTest'),$case,$fixtures)
}
```

### Provider Route：29 个参数场景

完整场景来自 [GbootProviderRouteTest](<src/test/java/com/rustedwarfare/reforge/reference/boot/GbootProviderRouteTest.java>)：10 个命名场景 + 10 个 marker 索引 + 4 个非法路径索引 + 5 个根门禁操作。`delete` 这里只验证 roots-not-ready 的拒绝，不删除文件。此组不创建真实 Uri、不打开 Provider。

```powershell
$providerCases = @(
    'metadata-before-roots','metadata-invalid','root-map-pure','root-map-invalid',
    'root-map-extra-key','metadata-null','metadata-multiple','ordinary-routes',
    'unsupported-return','prior-fatal-route'
)
foreach ($case in $providerCases) {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootProviderRouteTest'),$case)
}
# 索引 0..9：a.rwmod/x、a.rwmod\x、a.rwmod、x.[saflink]/a、
# x.[saflink]\a、x.[saflink]、content://foreign/a、/saf-virtual/x、MOD|x/a、NEW_PATH|maps2/x
foreach ($i in 0..9) {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootProviderRouteTest'),'reject-marker',"$i")
}
# 索引 0..3：null、空串、has\ambiguous、含 NUL 的路径
foreach ($i in 0..3) {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootProviderRouteTest'),'invalid-route',"$i")
}
foreach ($operation in 'file','uri','open','length','delete') {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootProviderRouteTest'),'root-gates',$operation)
}
```

### Preferences IO：14 个场景（Writer）

完整场景来自 [GbootPreferencesIoTest](<src/test/java/com/rustedwarfare/reforge/reference/boot/GbootPreferencesIoTest.java>)。使用真实 PrintWriter 与自有 Writer 故障夹具；只验证写入/flush/close 错误、primary/suppressed、首次失败和缺根门禁，不证明 Android preferences 路径实际可用。

```powershell
$preferencesCases = @(
    'healthy','write','flush','close','runtime','primary-close','primary-runtime',
    'prior-close','prior-healthy','null-writer','root-path','root-exists','root-read','root-open'
)
foreach ($case in $preferencesCases) {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootPreferencesIoTest'),$case)
}
Write-Host "Retained logs/classes/fixtures: $run"
```

预期每个场景输出 `PASS <scenario>`，15 + 29 + 14 次退出码均为 0；漏跑、跳过或只编译不算场景通过。当前 Windows / JDK 25.0.3 实跑覆盖三组纯 JDK 分组编译、FatalLatch / RequestLedger / Probe，以及上述 58 个 Gboot 主机场景；不包含可选的两个文件路由测试、Android 真机、原版初始化、合片、签装或完整参考器。Python 工具及公测见[上级公开入口](<../README.md#python-公开公测与派生-cli>)；内部 TestLab 数据缺失不影响本页主机测试，也不能据此补造真实采集证据。

### Packaged Asset：6 个早期失败场景

沿用上述编译结果，每个场景仍使用新JVM：

```powershell
foreach ($case in 'bind-before-roots','list-before-bind','prefailed') {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootPackagedAssetBindingTest'),$case)
}
foreach ($case in 'not-ready-root','not-ready-null','prefailed') {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootPackagedAssetTest'),$case)
}
```

这6个场景只验证未初始化Context时的失败顺序和首失败对象保持，不证明真实AssetManager绑定或枚举成功。`bindPackagedAssetIndex` 仅接受已核可信启动Context所给的首次精确manager，同Application不意味着不同manager可互换；`listPackagedAssets` 检查绑定身份、字面相对路径并将平台IO错误锁存。

**设备验证尚未完成**：本机指定MuMu的最近资产探针首例在第3次检查失败（根目录夹具成员断言），其余35例未运行、无完成标志；失败前未记录实际目录名，尚不能定位为helper错误或夹具预期错误。绑定和helper/native顺序比对已走到，但不据此宣布整组通过。原始失败保留在私有票据证据域，未改基准、未放宽断言；该接线前置检查点不证明原版初始化、推进、轨迹采集或首次真实对拍。

2026-10-19后续诊断补充：上段保留早期失败时点状态。实际枚举names和包身份现已另存并独审，根目录同时包含应用资产和平台的 `geoid_map`、`images`、`webkit`，因此原精确根集合断言仍失败。原oracle、原失败和完整诊断证据保留；后续自有APP资产试验不能证明原版参考器运行成功。

### Private Cache：主机叶子生命周期与3个早期失败场景

[ReferenceCache](<src/main/java/com/rustedwarfare/reforge/reference/io/ReferenceCache.java>) 仅操作已准备的三个固定缓存子目录（`mods-info.cachedata`、`mods-dir-search.cachedata`、`mods-dir-music.cachedata`）。允许单叶子的临时写入后替换、读取时更新时间、失效删除、计数及预检后的非递归清理；拒绝其他域、目录叶子、链接及跨type别名。每次操作重新核对完整canonical身份，共用首次失败锁存；写入不关闭借入的InputStream。

沿用上面的编译结果和自建 `$fixtures`，可运行下面的 **Windows** 主机测试。`all` 包含19个真实文件系统场景，其中有自建夹具的移动、替换、删除、符号链接和Windows junction；保留夹具便于复核，不对原版或私人目录运行。符号链接创建权限不足会使该场景失败，不会跳过或冒充通过。

```powershell
Invoke-Checked $java @('-cp',$classes,'com.rustedwarfare.reforge.reference.io.ReferenceCacheTest',$fixtures,'all')
foreach ($case in 'not-ready','prefailed','all-not-ready') {
    Invoke-Checked $java @('-cp',$bootCp,($bootPackage+'GbootCacheGateTest'),$case)
}
```

预期文件系统组输出 `PASS ReferenceCache all scenarios=19`，三个冷JVM各退出0。Windows/JDK25主机修复已保留实际红绿并经非作者复审，包括原同根跨type junction 漏检反例和原40次并发写入断言。三个Gboot场景只证明缺根/已有失败时的顺序与同一首失败对象；没有初始化Android Context，不证明Android文件系统、原版缓存调用、DEX组合、安装或原版正常推进。输出关闭双故障、touch系统故障等动态路径仍未覆盖。
