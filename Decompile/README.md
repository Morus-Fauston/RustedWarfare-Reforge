# 独立反编译代码与构建入口

本目录用于自有定点反编译辅助、原始符号定位与语义核验工具的源码和构建配置。不是原版输入目录，也不承诺重建完整可编译原版。

## 公开入口与内部历史入口

公开克隆的操作入口在本页及 [reference-bridge 构建与主机测试](<reference-bridge/README.md>)，无需内部文档即可执行下方公测。下列既有路径保留用于内部维护追溯；Docs、.scratch、TestLab 按[根忽略规则](<../.gitignore>)不公开，公开克隆中缺失是预期，不应当作唯一操作说明：

唯一落位规则见[三线代码与构建落位](../Docs/当前决策/三线代码与构建落位.md)。任务见[独立反编译开发线](../.scratch/开发票据/独立反编译/README.md)，研究仍在[Research](../Docs/Research/README.md)。

既有归档入口： [DEC 只读归档复核工具](<tools/verify_dec_evidence.py>)及其[隔离测试](<tools/test_verify_dec_evidence.py>)。尚无完整反编译工程或原版运行工具。Python 3.11+ 从项目根执行 `python -B Decompile/tools/verify_dec_evidence.py`；覆盖归档身份、迁移/维护记录与导航，不执行采集器、不写回旧结果、不证明机制语义。**该默认命令依赖内部迁移清单与旧证据，公开克隆缺件时不可运行完整归档复核**；不是下方公开公测的前置条件。其隔离测试会清理自建夹具，禁止删除的验证轮次不要执行。原始证据按所属票据/研究的既有路径归档，不放入源码或自动分发。反编译线最终成果汇报单独放在 [Research-DEC](<../Docs/Research-DEC/README.md>)，不搬入底稿、脚本或日志。

## 当前自有工具索引

| 入口 | 公开可做什么 | 不证明什么 |
|---|---|---|
| [reference-bridge](<reference-bridge/README.md>) | JDK 25 `javac --release 8` 分组编译 io/protocol/probe；用 Android SDK platform 34 android.jar 编译 Gboot；运行明确的主机场景 | 完整参考器、Android runtime、原版 backend、真实单步或安装许可 |
| [gboot_transform](<tools/p003/gboot_transform.py>) / [测试](<tools/p003/test_gboot_transform.py>) | 纯函数整方法定点替换，字节身份/结构校验；自编写 smali 夹具公测 | 原版合片或加载可用 |
| [bf_observer_transform](<tools/p003/bf_observer_transform.py>) / [测试](<tools/p003/test_bf_observer_transform.py>) | 带身份和锚点约束的两点观测插桩；自有合成夹具公测 | 原版运行时回调已经发生 |
| [original_input_transform](<tools/p003/original_input_transform.py>) / [测试](<tools/p003/test_original_input_transform.py>) | 受限派生输入变换及 `--identity` 校验 CLI；只写新输出目录 | 原版加载、基准等价或完整布局可用 |

TestLab 场景、真实原版输入、采集/合片脚本及旧证据不随公开仓库提供。下方只用自有 toy 夹具，不要求找内部文档补件，不提供原素材示例。原版 runtime 不发布、不进入独立 core；没有解禁修改原版包的签名、安装或设备运行。

## 工作区目录检查

目录约束见[AGENTS](<../AGENTS.md>)。维护者本机使用 `Decompile/tools/workspace_layout.py`、配套测试和 pre-commit 模板维护私有票据目录与历史证据；这些工具和迁移清单均保留本地、被Git忽略，不是公开构建或下方公测的依赖。

公开克隆按AGENTS文字核查目录，无需复制或安装上述本机钩子。新测试输出使用下方系统临时目录，不再平铺在仓库或私有工作台根下；内部历史证据复核须另行取得私有材料，不能把缺件当作验证通过。

## Python 公开公测与派生 CLI

**从仓库根**用 Python 3.11+ 执行；本次实际为 Windows / Python 3.13.14，其他环境未验证。三个模块均仅使用标准库；`-B` 避免源码目录新增字节码缓存。公测会保留自有文件夹具；符号链接负例可能受 Windows 权限影响并显示 skipped，不能算该分支通过。

```powershell
# unittest 的进度写到 stderr；Windows PowerShell 不应将它当成终止错误。
$ErrorActionPreference = 'Continue'
$pythonRun = Join-Path ([System.IO.Path]::GetTempPath()) ('rw-reforge-docs-python-' + [guid]::NewGuid().ToString())
$null = New-Item -ItemType Directory -Path $pythonRun
$env:DERIVED_TEST_ROOT = Join-Path $pythonRun 'fixtures'
# 三个独立 discover；不要用默认根 discover 冒充已找到测试。
python -B -m unittest discover -s Decompile/tools/p003 -p 'test_gboot_transform.py' -v
if ($LASTEXITCODE -ne 0) { throw 'gboot_transform tests failed' }
python -B -m unittest discover -s Decompile/tools/p003 -p 'test_bf_observer_transform.py' -v
if ($LASTEXITCODE -ne 0) { throw 'bf_observer_transform tests failed' }
python -B -m unittest discover -s Decompile/tools/p003 -p 'test_original_input_transform.py' -v
if ($LASTEXITCODE -ne 0) { throw 'original_input_transform tests failed' }
python -B Decompile/tools/p003/original_input_transform.py --help
if ($LASTEXITCODE -ne 0) { throw 'derive CLI help failed' }
```

`--help` 列出全部公开 CLI 参数：`--source-root`、`--output`、`--identity`、`--allowed-output-root` 必填，`--forbid-root` 可重复。source / allowed / output.parent 必须已存在；output 必须是 allowed 下**尚不存在**的新目录，不能与 source 或禁止根相交，拒绝符号链接/重解析路径。所有输入先比对 bytes/SHA-256 并完成变换计划，再创建输出；不是恶意并发文件系统事务，不保证磁盘故障回滚。成功退出 0，拒绝退出 2；成功仍有 `layoutReady=false`、`runtimeVerified=false`、`installAllowed=false`。

### identity JSON 字段契约与可执行 toy 示例

CLI 没有 JSON Schema 导出参数，也不新增 schema 文件；以下是源码实际读取的 JSON 字段契约，不要臆造 `--schema`：

- `schemaVersion: 1`；`files` 为非空数组，每项 `path`、正整数 `bytes`、64 位十六进制 `sha256` 必填。path 使用 `/` 分隔的安全相对名，不含空段、`.`、`..`、冒号或反斜线；文件名唯一。
- `map` 位于 `maps/`，`unit` 位于 `units/`；`copy` 恰好两个不同的 `tilesets/*.tsx`；四个输出名各不相同，且全部在 files 中。
- `allowedGids` 为非空 uint32 整数集合；`mapExpected` 包含 `width`、`height` 和 `gids`（十进制 gid 字符串到出现次数的对象）。必须与输入地图完全一致。
- files 可包含仅校验不复制的依赖；外部依赖才使用 `role: "dependency"` 和绝对 `sourcePath`，不可用于四个输出文件。`dependencyMappings` 为可选追溯元数据，默认 `[]`，不自动复制或验证映射语义。
- 地图仅支持一个 Ground 层、无压缩 CSV、已批准 gids，无既有 Items/Objects；输出 Ground 为小端 uint32 base64，并加全零 Items。单位 INI 不得已有 attack 段，仅追加 `[attack] / canAttack: false`。这些变换不代表原版输入等价获批。

下面在新的隔离目录生成四个自有 toy 文件、完整 identity JSON（含真实长度和哈希），再调用真实 CLI。不读原版素材、不覆写已有输出、不删除夹具。续用上面 `$pythonRun`：

```powershell
$toy = Join-Path $pythonRun ('toy-' + [guid]::NewGuid().ToString())
@'
import hashlib, json, sys
from pathlib import Path
base = Path(sys.argv[1]).absolute()
source = base / "source"
allowed = base / "runs"
forbidden = base / "keep"
source.mkdir(parents=True)
allowed.mkdir()
forbidden.mkdir()
content = {
    "maps/toy.tmx": b'<map width="2" height="1"><layer name="Ground" width="2" height="1"><data encoding="csv">1,62</data></layer></map>\n',
    "units/toy.ini": b'[core]\nname: owned-toy\n',
    "tilesets/a.tsx": b'<tileset name="owned-a"/>\n',
    "tilesets/b.tsx": b'<tileset name="owned-b"/>\n',
}
files = []
for name, raw in content.items():
    path = source / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    files.append({"path": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "role": "content"})
identity = {
    "schemaVersion": 1,
    "files": files,
    "map": "maps/toy.tmx",
    "unit": "units/toy.ini",
    "copy": ["tilesets/a.tsx", "tilesets/b.tsx"],
    "allowedGids": [1, 62],
    "mapExpected": {"width": 2, "height": 1, "gids": {"1": 1, "62": 1}},
    "dependencyMappings": [],
}
(base / "identity.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
print(json.dumps(identity, indent=2))
'@ | python -B - $toy
if ($LASTEXITCODE -ne 0) { throw 'toy fixture generation failed' }
python -B Decompile/tools/p003/original_input_transform.py `
    --source-root (Join-Path $toy 'source') `
    --output (Join-Path $toy 'runs/run-1') `
    --identity (Join-Path $toy 'identity.json') `
    --allowed-output-root (Join-Path $toy 'runs') `
    --forbid-root (Join-Path $toy 'keep')
if ($LASTEXITCODE -ne 0) { throw 'toy derive failed' }
```

上面生成的完整 JSON 示例（哈希针对示例中的精确 toy 字节，不用于原素材）：

```json
{
  "schemaVersion": 1,
  "files": [
    {"path": "maps/toy.tmx", "bytes": 115, "sha256": "13164d187b4230a965b45d40e1a90aae15c8e80f1b2c17e98b2cb08ca833ccd4", "role": "content"},
    {"path": "units/toy.ini", "bytes": 23, "sha256": "b61fd7c66041e6948ff755e964eb8c41425a88d771922cf58383f711290d4ee6", "role": "content"},
    {"path": "tilesets/a.tsx", "bytes": 26, "sha256": "18e89e8f8a01d198f1bd057f360fd8b5482196e5b0bf0058fd309bc8b06200d9", "role": "content"},
    {"path": "tilesets/b.tsx", "bytes": 26, "sha256": "b301ff92a158267ba15243a3688c3f4b2e0be6b80a5355c73757931aa331547d", "role": "content"}
  ],
  "map": "maps/toy.tmx",
  "unit": "units/toy.ini",
  "copy": ["tilesets/a.tsx", "tilesets/b.tsx"],
  "allowedGids": [1, 62],
  "mapExpected": {"width": 2, "height": 1, "gids": {"1": 1, "62": 1}},
  "dependencyMappings": []
}
```

成功在新的 `runs/run-1` 中生成 manifest.json 和 derived 下的四个文件，依赖不会自动复制；重复使用同一 output 会以 `output-exists` / 退出 2 拒绝。重新运行请生成新的 UUID，不删除旧输出。本次 Windows 主机已实跑三个 discover、CLI help 与该 toy CLI；这不替代真实原版输入制备、加载或设备验收。
