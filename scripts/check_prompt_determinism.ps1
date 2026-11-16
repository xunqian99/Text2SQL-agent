param(
  [ValidateRange(3, 20)][int]$Runs = 5,
  [string]$OutputDir = "reports/prompt-audit-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
  $OutputDir = Join-Path $projectRoot $OutputDir
}
[System.IO.Directory]::CreateDirectory($OutputDir) | Out-Null
Push-Location $projectRoot
try {
  # No application startup, LLM client, API key, or SQL generation in this path.
  & mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/prompt-audit-classpath.txt' *> (Join-Path $OutputDir 'compile.log')
  if ($LASTEXITCODE -ne 0) { throw "Compile failed; see $OutputDir/compile.log" }
  $auditClasspath = 'target/test-classes;target/classes;' + (Get-Content target/prompt-audit-classpath.txt -Raw).Trim()
  $baseline = $null
  $differences = @()
  $withinComparisons = 0
  $withinDifferences = 0
  $timings = @()
  $inputDifferences = @()
  $componentFields = @('promptSha256', 'ddlSha256', 'metricsSha256', 'valueHintsSha256',
    'examplesSha256', 'tables', 'retrievalTables', 'scores', 'expandedTables', 'columns', 'evidenceOrder')
  foreach ($run in 1..$Runs) {
    $snapshotPath = Join-Path $OutputDir "run-$run.json"
    & java -cp $auditClasspath com.text2sql.agent.retrieval.PromptDeterminismProbe $snapshotPath *> (Join-Path $OutputDir "run-$run.log")
    if ($LASTEXITCODE -ne 0) { throw "Probe $run failed; see $OutputDir/run-$run.log" }
    $snapshot = Get-Content $snapshotPath -Raw | ConvertFrom-Json
    $withinComparisons += $snapshot.sameProcessComparisons
    $withinDifferences += $snapshot.sameProcessMismatches.Count
    $timings += [ordered]@{run=$run; avgMs=$snapshot.captureAvgMs; p95Ms=$snapshot.captureP95Ms}
    if ($null -eq $baseline) {
      $baseline = $snapshot
      continue
    }
    foreach ($field in @('datasetSha256','sampleIdsSha256','schemaSha256','systemPromptSha256',
        'promptHashVersion','retrievalConfig','semanticEnabled','fewshotMaxExamples')) {
      if (($baseline.$field | ConvertTo-Json -Depth 12 -Compress) -cne
          ($snapshot.$field | ConvertTo-Json -Depth 12 -Compress)) {
        $inputDifferences += "run-${run}:$field"
      }
    }
    foreach ($item in $baseline.items) {
      $other = @($snapshot.items | Where-Object id -eq $item.id)
      if ($other.Count -ne 1) { throw "Missing or duplicate sample $($item.id) in run $run" }
      $changed = @()
      foreach ($field in $componentFields) {
        if (($item.$field | ConvertTo-Json -Depth 12 -Compress) -cne
            ($other[0].$field | ConvertTo-Json -Depth 12 -Compress)) { $changed += $field }
      }
      if ($changed.Count -gt 0) {
        $differences += [ordered]@{run=$run; id=$item.id; fields=$changed}
      }
    }
  }
  $revision = (& git rev-parse HEAD).Trim()
  $state = if ((& git status --porcelain).Count -eq 0) {'CLEAN'} else {'DIRTY'}
  $passed = $inputDifferences.Count -eq 0 -and $differences.Count -eq 0 -and $withinDifferences -eq 0
  $summary = [ordered]@{
    format='prompt-determinism-acceptance-v1'; timestamp=(Get-Date -Format o)
    gitCommit=$revision; worktreeState=$state; passed=$passed; runs=$Runs; items=$baseline.itemCount
    datasetSha256=$baseline.datasetSha256; sampleIdsSha256=$baseline.sampleIdsSha256
    schemaSha256=$baseline.schemaSha256; promptHashVersion=$baseline.promptHashVersion
    sameProcessComparisons=$withinComparisons; sameProcessDifferences=$withinDifferences
    crossProcessComparisons=(($Runs - 1) * $baseline.itemCount)
    inputDifferences=$inputDifferences; crossProcessDifferences=$differences
    captureTimings=$timings; modelCallCount=0; estimatedModelCostYuan=0
  }
  $summary | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $OutputDir 'summary.json') -Encoding utf8
  Write-Output "Prompt audit: passed=$passed, runs=$Runs, items=$($baseline.itemCount), within=$withinComparisons, cross=$(($Runs - 1) * $baseline.itemCount), report=$OutputDir/summary.json"
  if (-not $passed) { throw 'Prompt audit failed; inspect summary.json before comparing model accuracy.' }
} finally {
  Pop-Location
}
