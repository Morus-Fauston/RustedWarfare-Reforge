param(
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
    [Parameter(Mandatory = $true)][string]$ControlledSuite
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$launcher = Join-Path $repoRoot 'SimulationTest/build/install/simulation-test/bin/simulation-test.bat'
$fixture = Join-Path $repoRoot 'SimulationTest/fixtures/record-chain/scenario.json'
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
$ControlledSuite = [IO.Path]::GetFullPath($ControlledSuite)
New-Item -ItemType Directory -Path $EvidenceDirectory -ErrorAction Stop | Out-Null
$toolRuns = Join-Path $repoRoot ('SimulationTest/build/comparison-cli/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $toolRuns -ErrorAction Stop | Out-Null
$checks = [Collections.Generic.List[object]]::new()

function Invoke-Check([string]$Name, [string[]]$Arguments, [int]$Expected) {
    $stdout = @(& $launcher @Arguments 2>&1)
    $actual = $LASTEXITCODE
    $stdout | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "$Name.log") -Encoding utf8
    $entry = [ordered]@{name=$Name; command=@($launcher) + $Arguments; expected=$Expected; actual=$actual}
    $checks.Add($entry)
    $checks | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'cli-checks.json') -Encoding utf8
    if ($actual -ne $Expected) { throw "$Name expected exit $Expected, got $actual" }
    if ($Arguments[0] -in @('run', 'compare')) {
        $line = $stdout | ForEach-Object ToString | Where-Object { $_ -match '^[-a-z]+: ' } | Select-Object -Last 1
        if (-not $line) { throw "$Name missing output path" }
        $path = $line.Substring($line.IndexOf(': ') + 2)
        $entry.output = $path
        $resultPath = Join-Path $path 'comparison/result.json'
        $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        if ($result.exitCode -ne $actual -or $result.comparison -is [string]) { throw "$Name result is not a real consistent judgment" }
        if ($actual -in @(3, 4, 64) -and $null -ne $result.firstDivergence) { throw "$Name fabricated divergence on insufficient evidence" }
        $entry.resultSha256 = (Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($Arguments[0] -eq 'run') {
            $manifest = Get-Content -LiteralPath (Join-Path $path 'manifest.json') -Raw | ConvertFrom-Json
            if ($manifest.exitCode -ne $actual -or $manifest.status -ne $result.result) { throw "$Name manifest mismatch" }
        }
    }
    $checks | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'cli-checks.json') -Encoding utf8
    Write-Output "$Name exit=$actual"
}

# Literal assertions from interface v1, fixed before invoking the core.
$contract = [ordered]@{
    kind='behavior-contract'; schemaVersion=1; contractId='tool.comparison.stop.v1'; scope='self-behavior'
    scenarioIds=@('tool.record-chain.v1')
    rules=@(@{ruleId='stop.cancels'; when=@{event='action-accepted'; actionKind='stop'}; assertions=@(
        @{path='units.u1.moveTarget'; operator='absent-or-null'},
        @{path='events'; operator='contains'; value='movement-cancelled'}
    )})
    comparison=@{numberMode='declared-per-field'; tolerances=@{}}
    exclusions=@('original-compatibility'); approval=@{status='tool-fixture-only'; source='TEST-P001-003'}
}
$passContract = Join-Path $EvidenceDirectory 'fixed-stop-contract.json'
$contract | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $passContract -Encoding utf8NoBOM
$contract.contractId = 'tool.comparison.deliberately-failing.v1'
$contract.rules = @(@{ruleId='deliberate.wrong-owner'; assertions=@(@{path='units.u1.owner'; operator='equals'; value='p2'})})
$failContract = Join-Path $EvidenceDirectory 'fixed-failing-contract.json'
$contract | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $failContract -Encoding utf8NoBOM
$contract.schemaVersion = 2
$badContract = Join-Path $EvidenceDirectory 'unknown-version-contract.json'
$contract | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $badContract -Encoding utf8NoBOM
$badScenario = Get-Content -LiteralPath $fixture -Raw | ConvertFrom-Json
@($badScenario.content.map, $badScenario.content.unitVariant) + @($badScenario.content.dependencies) | ForEach-Object {
    $_.path = [IO.Path]::GetFullPath((Join-Path (Split-Path $fixture -Parent) $_.path))
}
$badScenario.units[0].position.x = -10
$badScenarioPath = Join-Path $EvidenceDirectory 'isolated-invalid-spawn.json'
$badScenario | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $badScenarioPath -Encoding utf8NoBOM

Invoke-Check 'help' @('--help') 0
$common = @('--scenario', $fixture, '--output', $toolRuns, '--mode', 'self')
Invoke-Check 'run-pass' (@('run', '--contract', $passContract) + $common) 0
Invoke-Check 'run-assertion' (@('run', '--contract', $failContract) + $common) 2
Invoke-Check 'run-empty-rules' (@('run', '--contract', (Join-Path $repoRoot 'SimulationTest/fixtures/record-chain/contract.json')) + $common) 3
Invoke-Check 'run-input-error' (@('run', '--contract', $badContract) + $common) 64
Invoke-Check 'run-core-failure' @('run', '--scenario', $badScenarioPath, '--contract', $passContract, '--output', $toolRuns, '--mode', 'self') 4
$compare = @('--scenario', (Join-Path $ControlledSuite 'scenario.json'), '--contract', (Join-Path $ControlledSuite 'contract.json'),
    '--expected', (Join-Path $ControlledSuite 'T01-reference'), '--output', (Join-Path $EvidenceDirectory 'comparisons'), '--mode', 'self')
foreach ($case in @(
    @('T01-equivalent', 0), @('T02-displacement', 2), @('T02-ownership', 2), @('T02-input-timing', 2),
    @('T03-missing-step', 3), @('T03-missing-field', 3), @('T03-missing-completion', 3), @('T03-truncated', 3), @('T03-crash', 4),
    @('T03-scenario-identity', 64)
)) {
    Invoke-Check ('compare-' + $case[0]) (@('compare', '--actual', (Join-Path $ControlledSuite $case[0])) + $compare) $case[1]
}
[ordered]@{checks=$checks.Count; toolSmokeRuns=$toolRuns; originalCompatibility=$false; allExpectedExitsObserved=$true} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'summary.json') -Encoding utf8
exit 0
