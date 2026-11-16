param(
  [ValidateRange(3, 10)][int]$Rounds = 3,
  [string]$OutputDir = ('reports/ablation-' + (Get-Date -Format 'yyyyMMdd-HHmmss')),
  [ValidateRange(1, 65535)][int]$Port = 18080
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  # Do not silently overwrite or repeat paid work. Keep the live process handle until completion.
  if (Test-Path -LiteralPath $OutputDir) { throw "Output directory already exists: $OutputDir" }
  $guardedPaths = @('src/main','src/test','pom.xml','data/eval','scripts/run_eval.ps1','scripts/run_ablation.ps1','scripts/summarize_ablation.py')
  $sourceDiff = git status --porcelain -- @guardedPaths
  if ($LASTEXITCODE -ne 0 -or $sourceDiff) { throw 'Commit evaluation code before running the matrix.' }
  New-Item -ItemType Directory -Path $OutputDir | Out-Null
  $commit = (git rev-parse HEAD).Trim()
  $groups = @('baseline', 'correction', 'checker', 'no-clarification')
  $manifest = [ordered]@{
    format='production-ablation-v1'; gitCommit=$commit
    startedAt=(Get-Date -Format o); rounds=$Rounds; split='eval'; sampleCount=100
    orderPolicy='rotate-one-position-per-round'; runs=@(); state='RUNNING'
  }
  $manifestFile = Join-Path $OutputDir 'manifest.json'
  $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
  for ($round = 1; $round -le $Rounds; $round++) {
    for ($position = 0; $position -lt $groups.Count; $position++) {
      $group = $groups[($position + $round - 1) % $groups.Count]
      if ((git rev-parse HEAD).Trim() -ne $commit -or (git status --porcelain -- @guardedPaths)) {
        throw 'Evaluation source changed during the matrix; preserve existing runs and investigate.'
      }
      $runDir = (Join-Path $OutputDir "round-$round-$group").Replace('\', '/')
      New-Item -ItemType Directory -Path $runDir | Out-Null
      $options = @{Split='eval'; OutputDir=$runDir; Port=$Port}
      if ($group -in @('correction','checker')) { $options.SelfCorrection = $true }
      if ($group -eq 'checker') { $options.ResultChecker = $true }
      if ($group -eq 'no-clarification') { $options.DisableClarification = $true }
      $run = [ordered]@{round=$round; group=$group; directory=$runDir; startedAt=(Get-Date -Format o); state='RUNNING'}
      $manifest.runs += $run
      $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
      Write-Host "Starting round $round/$Rounds, group=$group"
      & "$PSScriptRoot/run_eval.ps1" @options *> (Join-Path $runDir 'run.log')
      $reports = @(Get-ChildItem -LiteralPath $runDir -Filter 'eval-*.json')
      if ($reports.Count -ne 1) { throw "Expected exactly one report in $runDir" }
      $report = Get-Content -LiteralPath $reports[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
      if ($report.meta.gitCommit -ne $commit -or $report.overall.total -ne 100 -or $report.itemEvidence.Count -ne 100) {
        throw "Incomplete or inconsistent evaluation: $runDir"
      }
      $run.state = 'COMPLETE'
      $run['completedAt'] = Get-Date -Format o
      $run['report'] = $reports[0].Name
      $run['correct'] = $report.overall.correct
      $run['p95LatencyMs'] = $report.overall.p95LatencyMs
      $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
      Write-Host "Completed round=$round group=$group correct=$($run.correct)/100 P95=$($run.p95LatencyMs)ms"
    }
  }
  $manifest.state = 'COMPLETE'
  $manifest['completedAt'] = Get-Date -Format o
  $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
} catch {
  if ($manifest -and $manifestFile) {
    $manifest.state = 'FAILED'
    $manifest['error'] = $_.Exception.Message
    $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
  }
  throw
} finally {
  Pop-Location
}
