param(
  [string]$BaselineRevision = '5f11651',
  [string]$MatrixDir = 'reports/ablation-20261116',
  [string]$OutputDir = "reports/sql-extraction-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not [System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir = Join-Path $projectRoot $OutputDir }
if (Test-Path -LiteralPath $OutputDir) { throw 'Output directory already exists; choose a new directory.' }
[System.IO.Directory]::CreateDirectory($OutputDir) | Out-Null
Push-Location $projectRoot
try {
  & mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/sql-extraction-classpath.txt' *> (Join-Path $OutputDir 'compile.log')
  if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; inspect compile.log.' }
  $probeClasspath = 'target/test-classes;target/classes;' + (Get-Content target/sql-extraction-classpath.txt -Raw).Trim()
  $baselineCommit = (& git rev-parse --verify "${BaselineRevision}^{commit}").Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Baseline revision does not exist.' }
  $oldSource = & git show "${baselineCommit}:src/main/java/com/text2sql/agent/generation/SqlExtractor.java"
  if ($LASTEXITCODE -ne 0) { throw 'Baseline source unavailable.' }
  # Only the old extractor is compiled in isolation; never reset the active checkout.
  $isolatedDir = Join-Path $projectRoot 'target/sql-extraction-baseline'
  [System.IO.Directory]::CreateDirectory($isolatedDir) | Out-Null
  $sourcePath = Join-Path $isolatedDir 'SqlExtractor.java'
  [System.IO.File]::WriteAllText($sourcePath, ($oldSource -join "`n"), [System.Text.UTF8Encoding]::new($false))
  & javac -encoding UTF-8 -cp $probeClasspath -d $isolatedDir $sourcePath *> (Join-Path $OutputDir 'baseline-compile.log')
  if ($LASTEXITCODE -ne 0) { throw 'Baseline compilation failed.' }
  & java -cp "$isolatedDir;$probeClasspath" com.text2sql.agent.generation.SqlExtractorAcceptanceProbe $MatrixDir (Join-Path $OutputDir 'before.json') *> (Join-Path $OutputDir 'before.log')
  if ($LASTEXITCODE -ne 0) { throw 'Baseline replay failed.' }
  & java -cp $probeClasspath com.text2sql.agent.generation.SqlExtractorAcceptanceProbe $MatrixDir (Join-Path $OutputDir 'after.json') *> (Join-Path $OutputDir 'after.log')
  if ($LASTEXITCODE -ne 0) { throw 'Current replay failed.' }
  $before = Get-Content (Join-Path $OutputDir 'before.json') -Raw | ConvertFrom-Json
  $after = Get-Content (Join-Path $OutputDir 'after.json') -Raw | ConvertFrom-Json
  foreach ($field in @('corpusSha256', 'boundaryTotal', 'storedSqlCount', 'knownResidualClosingLines', 'replayTotal')) {
    if ($before.$field -cne $after.$field) { throw "Replay inputs changed: $field" }
  }
  foreach ($collection in @('boundaryChecks', 'replayChecks')) {
    if ($before.$collection.Count -ne $after.$collection.Count) { throw 'Replay case count changed.' }
    for ($i = 0; $i -lt $before.$collection.Count; $i++) {
      foreach ($field in @('id', 'inputSha256', 'expectedSqlSha256', 'expectedRejection')) {
        if ($before.$collection[$i].$field -cne $after.$collection[$i].$field) { throw "Replay case changed: $collection/$i/$field" }
      }
    }
  }
  $passed = $after.boundaryMatched -eq $after.boundaryTotal -and $after.replayMatched -eq $after.replayTotal
  $summary = [ordered]@{
    format='sql-extraction-acceptance-v1'; timestamp=(Get-Date -Format o)
    baselineCommit=$baselineCommit; headCommit=((& git rev-parse HEAD).Trim())
    sourceSha256=(Get-FileHash src/main/java/com/text2sql/agent/generation/SqlExtractor.java -Algorithm SHA256).Hash.ToLowerInvariant()
    worktreeState=$(if ((& git status --porcelain).Count -eq 0) {'CLEAN'} else {'DIRTY'})
    passed=$passed; corpusSha256=$after.corpusSha256
    baselineExtractorClassSha256=$before.extractorClassSha256; currentExtractorClassSha256=$after.extractorClassSha256
    boundaryTotal=$after.boundaryTotal; beforeBoundaryMatched=$before.boundaryMatched; afterBoundaryMatched=$after.boundaryMatched
    storedSqlCount=$after.storedSqlCount; knownResidualClosingLines=$after.knownResidualClosingLines
    replayTotal=$after.replayTotal; beforeReplayMatched=$before.replayMatched; afterReplayMatched=$after.replayMatched
    modelCalls=0; modelTokens=0; estimatedModelCostYuan=0
    limits=$after.limits
  }
  $summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $OutputDir 'summary.json') -Encoding utf8
  Write-Output "SQL extraction: passed=$passed, boundaries=$($after.boundaryMatched)/$($after.boundaryTotal), replay=$($after.replayMatched)/$($after.replayTotal)"
  if (-not $passed) { throw 'SQL extraction acceptance failed; inspect summary.json.' }
} finally {
  Pop-Location
}
