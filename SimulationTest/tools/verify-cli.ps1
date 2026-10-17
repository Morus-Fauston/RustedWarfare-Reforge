param(
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
    [string]$TopicDirectory
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$launcher = Join-Path $repoRoot 'SimulationTest/build/install/simulation-test/bin/simulation-test.bat'
$fixture = Join-Path $repoRoot 'SimulationTest/fixtures/record-chain/scenario.json'
$contract = Join-Path $repoRoot 'SimulationTest/fixtures/record-chain/contract.json'
if (-not $TopicDirectory) {
    $TopicDirectory = Join-Path $repoRoot 'TestLab/runs/P001-自身行为验证/01-执行记录链'
}
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
$TopicDirectory = [IO.Path]::GetFullPath($TopicDirectory)
New-Item -ItemType Directory -Path $EvidenceDirectory -ErrorAction Stop | Out-Null
$checks = [Collections.Generic.List[object]]::new()

function Invoke-Check([string]$Name, [string[]]$Arguments, [int]$Expected) {
    $before = @(Get-ChildItem -LiteralPath $TopicDirectory -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $stdout = @(& $launcher @Arguments 2>&1)
    $actual = $LASTEXITCODE
    $stdout | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "$Name.log") -Encoding utf8
    $after = @(Get-ChildItem -LiteralPath $TopicDirectory -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $created = @($after | Where-Object { $_ -notin $before })
    $checks.Add([ordered]@{name=$Name; command=@($launcher) + $Arguments; expected=$Expected; actual=$actual; batches=$created})
    $checks | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'cli-checks.json') -Encoding utf8
    if ($actual -ne $Expected) { throw "$Name expected exit $Expected, got $actual" }
    if ($Arguments[0] -eq 'run' -and $Arguments -contains '--scenario') {
        if ($created.Count -ne 1) { throw "$Name did not produce exactly one fresh batch" }
        $manifest = Get-Content -LiteralPath (Join-Path $created[0] 'manifest.json') -Raw | ConvertFrom-Json
        if ($manifest.exitCode -ne $actual) { throw "$Name manifest and process exits differ" }
        if ($manifest.status -eq 'completed') { throw 'A collector without a comparator cannot pass a contract' }
        if (-not ($stdout | Where-Object { $_.ToString().Contains($created[0]) })) {
            throw "$Name console output cannot round-trip the batch path"
        }
    }
    Write-Output "$Name exit=$actual batches=$($created.Count)"
}

function New-Scenario([string]$Name, [scriptblock]$Mutate) {
    $inputObject = Get-Content -LiteralPath $fixture -Raw | ConvertFrom-Json
    @($inputObject.content.map, $inputObject.content.unitVariant) + @($inputObject.content.dependencies) | ForEach-Object {
        $_.path = [IO.Path]::GetFullPath((Join-Path (Split-Path $fixture -Parent) $_.path))
    }
    & $Mutate $inputObject
    $path = Join-Path $EvidenceDirectory "$Name.json"
    $inputObject | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $path -Encoding utf8NoBOM
    return $path
}

Push-Location $repoRoot
try {
    Invoke-Check 'help' @('--help') 0
    Invoke-Check 'version' @('--version') 0
    Invoke-Check 'bad-mode' @('run', '--mode', 'original') 64
    Invoke-Check 'unknown-option' @('run', '--unknown', 'value') 64
    $common = @('--contract', $contract, '--output', $TopicDirectory, '--mode', 'self')
    Invoke-Check 'collect-1' (@('run', '--scenario', $fixture) + $common) 3
    Invoke-Check 'collect-2' (@('run', '--scenario', $fixture) + $common) 3
    $badHash = New-Scenario 'bad-hash' { param($value) $value.content.map.sha256 = '0' * 64 }
    Invoke-Check 'bad-hash' (@('run', '--scenario', $badHash) + $common) 64
    $badVersion = New-Scenario 'bad-version' { param($value) $value.schemaVersion = 2 }
    Invoke-Check 'bad-version' (@('run', '--scenario', $badVersion) + $common) 64
    $missingDependency = New-Scenario 'missing-dependency' { param($value) $value.content.dependencies = @() }
    Invoke-Check 'missing-dependency' (@('run', '--scenario', $missingDependency) + $common) 64
    $badSpawn = New-Scenario 'bad-spawn' { param($value) $value.units[0].position.x = -10 }
    Invoke-Check 'bad-spawn' (@('run', '--scenario', $badSpawn) + $common) 4
    $badType = New-Scenario 'bad-type' { param($value) $value.units = @('u1') }
    Invoke-Check 'bad-type' (@('run', '--scenario', $badType) + $common) 64
    $cancel = Join-Path $EvidenceDirectory 'cancel.request'
    New-Item -ItemType File -Path $cancel | Out-Null
    Invoke-Check 'cancelled' (@('run', '--scenario', $fixture) + $common + @('--cancel-file', $cancel)) 3
    Invoke-Check 'timeout' (@('run', '--scenario', $fixture) + $common + @('--timeout-ms', '1')) 4
    $runs = @($checks | Where-Object { $_.name -in @('collect-1', 'collect-2') } | ForEach-Object { $_.batches[0] })
    function Read-Observations([string]$RunPath) {
        @(Get-Content -LiteralPath (Join-Path $RunPath 'reforge/raw.jsonl') | ForEach-Object {
            $line = $_ | ConvertFrom-Json
            if ($line.kind -eq 'observation') { $line.observation | ConvertTo-Json -Depth 20 -Compress }
        })
    }
    $first = Read-Observations $runs[0]
    $second = Read-Observations $runs[1]
    if ($first.Count -ne 7 -or $second.Count -ne 7 -or ($first -join "`n") -cne ($second -join "`n")) {
        throw 'Repeated fixed-input core observations differ or are incomplete'
    }
    [ordered]@{count=$checks.Count; observationsPerRun=$first.Count; repeatObservationsEqual=$true; originalCompatibility=$false} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
} finally { Pop-Location }
exit 0
