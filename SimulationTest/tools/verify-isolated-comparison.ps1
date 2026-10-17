param([Parameter(Mandatory = $true)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
New-Item -ItemType Directory -Path $EvidenceDirectory -ErrorAction Stop | Out-Null
$isolation = Join-Path $repoRoot ('SimulationTest/build/isolated-comparison-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $isolation -ErrorAction Stop | Out-Null
$base = (& git -C $repoRoot rev-parse HEAD).Trim()
$archivePath = Join-Path $isolation 'baseline-core.zip'
& git -C $repoRoot archive --format=zip "--output=$archivePath" $base Engine/core
if ($LASTEXITCODE -ne 0) { throw 'Cannot export fixed core baseline' }
Expand-Archive -LiteralPath (Join-Path $isolation 'baseline-core.zip') -DestinationPath $isolation
$project = Join-Path $isolation 'SimulationTest'
New-Item -ItemType Directory -Path $project | Out-Null
foreach ($name in @('src', 'fixtures', 'gradle', 'tools', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat')) {
    Copy-Item -LiteralPath (Join-Path $repoRoot "SimulationTest/$name") -Destination $project -Recurse
}
# Private, local-only test inputs; these copies are not included in installDist.
$inputDirectory = Join-Path $repoRoot 'TestLab/local/P001-移动沙盒输入'
$inputCopy = Join-Path $isolation 'TestLab/local/P001-移动沙盒输入'
New-Item -ItemType Directory -Path (Split-Path $inputCopy -Parent) -Force | Out-Null
Copy-Item -LiteralPath $inputDirectory -Destination $inputCopy -Recurse
$scenarioPath = Join-Path $repoRoot 'SimulationTest/fixtures/record-chain/scenario.json'
$scenario = Get-Content -LiteralPath $scenarioPath -Raw | ConvertFrom-Json
foreach ($reference in $scenario.content.dependencies) {
    $source = [IO.Path]::GetFullPath((Join-Path (Split-Path $scenarioPath -Parent) $reference.path))
    $relative = [IO.Path]::GetRelativePath($repoRoot, $source)
    if ($relative.StartsWith('..') -or [IO.Path]::IsPathRooted($relative)) { throw 'Input outside repository is not included automatically' }
    $destination = [IO.Path]::GetFullPath((Join-Path $isolation $relative))
    if (-not $destination.StartsWith($isolation + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Copy escaped isolation root' }
    if (-not (Test-Path -LiteralPath $destination)) {
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
}
$sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'src') -Recurse -File | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($project, $_.FullName); sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}
})
[ordered]@{baseCommit=$base; isolation=$isolation; core='git archive HEAD Engine/core, excludes other session WIP'; sources=$sources} |
    ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'isolation.json') -Encoding utf8
$previousEvidence = $env:SIMULATION_COMPARISON_EVIDENCE
$env:SIMULATION_COMPARISON_EVIDENCE = Join-Path $EvidenceDirectory 'controlled'
Push-Location $project
try {
    & ./gradlew.bat test installDist --rerun-tasks --no-daemon --console=plain 2>&1 |
        Tee-Object -FilePath (Join-Path $EvidenceDirectory 'build-test.log')
    $buildExit = $LASTEXITCODE
    if ($buildExit -ne 0) { throw "Isolated build/test exit $buildExit" }
    Copy-Item -LiteralPath 'build/test-results/test' -Destination (Join-Path $EvidenceDirectory 'tool-test-results') -Recurse
    Copy-Item -LiteralPath 'build/core/test-results/test' -Destination (Join-Path $EvidenceDirectory 'core-test-results') -Recurse
    & ./tools/verify-comparison.ps1 -EvidenceDirectory (Join-Path $EvidenceDirectory 'cli') -ControlledSuite $env:SIMULATION_COMPARISON_EVIDENCE
    if ($LASTEXITCODE -ne 0) { throw "Isolated CLI exit $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:SIMULATION_COMPARISON_EVIDENCE = $previousEvidence
}
exit 0
