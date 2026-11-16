param(
  [string]$BaselineRevision = '09597ed',
  [string]$OutputDir = "reports/result-checker-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not [System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir = Join-Path $projectRoot $OutputDir }
[System.IO.Directory]::CreateDirectory($OutputDir) | Out-Null
Push-Location $projectRoot
try {
  & mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/result-checker-classpath.txt' *> (Join-Path $OutputDir 'compile.log')
  if ($LASTEXITCODE -ne 0) { throw "Compile failed; see $OutputDir/compile.log" }
  $probeClasspath = 'target/test-classes;target/classes;' + (Get-Content target/result-checker-classpath.txt -Raw).Trim()
  $baselineCommit = (& git rev-parse --verify "${BaselineRevision}^{commit}").Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Baseline revision does not exist.' }
  $oldSource = & git show "${baselineCommit}:src/main/java/com/text2sql/agent/validation/ResultChecker.java"
  if ($LASTEXITCODE -ne 0) { throw 'Baseline checker source is unavailable.' }
  # Compile only the old checker in an isolated directory; never reset the user's checkout.
  $isolatedDir = Join-Path $projectRoot 'target/result-checker-baseline'
  [System.IO.Directory]::CreateDirectory($isolatedDir) | Out-Null
  $sourcePath = Join-Path $isolatedDir 'ResultChecker.java'
  [System.IO.File]::WriteAllText($sourcePath, ($oldSource -join "`n"), [System.Text.UTF8Encoding]::new($false))
  & javac -encoding UTF-8 -cp $probeClasspath -d $isolatedDir $sourcePath *> (Join-Path $OutputDir 'baseline-compile.log')
  if ($LASTEXITCODE -ne 0) { throw "Baseline compilation failed; see $OutputDir/baseline-compile.log" }
  & java '-Dresult.checker.baseline=true' -cp "$isolatedDir;$probeClasspath" com.text2sql.agent.validation.ResultCheckerAcceptanceProbe (Join-Path $OutputDir 'before.json') *> (Join-Path $OutputDir 'before.log')
  if ($LASTEXITCODE -ne 0) { throw "Baseline replay failed; see $OutputDir/before.log" }
  & java -cp $probeClasspath com.text2sql.agent.validation.ResultCheckerAcceptanceProbe (Join-Path $OutputDir 'after.json') *> (Join-Path $OutputDir 'after.log')
  if ($LASTEXITCODE -ne 0) { throw "Current replay failed; see $OutputDir/after.log" }
  $before = Get-Content (Join-Path $OutputDir 'before.json') -Raw | ConvertFrom-Json
  $after = Get-Content (Join-Path $OutputDir 'after.json') -Raw | ConvertFrom-Json
  $inputDifferences = @()
  foreach ($field in @('datasetSha256','sampleIdsSha256','boundaryTotal','goldTotal')) {
    if ($before.$field -cne $after.$field) { $inputDifferences += $field }
  }
  foreach ($item in $before.gold) {
    $other = @($after.gold | Where-Object id -eq $item.id)
    if ($other.Count -ne 1) { throw "Missing/duplicate Gold item $($item.id)" }
    foreach ($field in @('sqlSha256','resultSha256','columns','rows','truncated')) {
      if (($item.$field | ConvertTo-Json -Depth 12 -Compress) -cne
          ($other[0].$field | ConvertTo-Json -Depth 12 -Compress)) { $inputDifferences += "$($item.id):$field" }
    }
  }
  foreach ($item in $before.boundaries) {
    $other = @($after.boundaries | Where-Object id -eq $item.id)
    if ($other.Count -ne 1) { throw "Missing/duplicate boundary item $($item.id)" }
    foreach ($field in @('question','sql','columns','rows','expectedRule')) {
      if (($item.$field | ConvertTo-Json -Depth 12 -Compress) -cne
          ($other[0].$field | ConvertTo-Json -Depth 12 -Compress)) { $inputDifferences += "$($item.id):$field" }
    }
  }
  $passed = $inputDifferences.Count -eq 0 -and $after.boundaryContractsMatched -eq $after.boundaryTotal -and
      $after.goldTotal -eq 200 -and $after.goldSuspicious -eq 0
  $summary = [ordered]@{
    format='result-checker-acceptance-v1'; timestamp=(Get-Date -Format o)
    gitCommit=((& git rev-parse HEAD).Trim()); baselineCommit=$baselineCommit
    worktreeState=$(if ((& git status --porcelain).Count -eq 0) {'CLEAN'} else {'DIRTY'})
    passed=$passed; inputDifferences=$inputDifferences
    datasetSha256=$after.datasetSha256; sampleIdsSha256=$after.sampleIdsSha256
    baselineCheckerClassSha256=$before.checkerClassSha256; currentCheckerClassSha256=$after.checkerClassSha256
    boundaryTotal=$after.boundaryTotal; beforeContractsMatched=$before.boundaryContractsMatched; afterContractsMatched=$after.boundaryContractsMatched
    goldTotal=$after.goldTotal; beforeGoldAlerts=$before.goldSuspicious; afterGoldAlerts=$after.goldSuspicious
    beforeGoldAlertIds=@($before.gold | Where-Object suspicious | ForEach-Object id)
    afterGoldAlertIds=@($after.gold | Where-Object suspicious | ForEach-Object id)
    checkerAvgMs=$after.checkerAvgMs; checkerP95Ms=$after.checkerP95Ms
    modelCallCount=0; modelTokens=0; estimatedModelCostYuan=0
  }
  $summary | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $OutputDir 'summary.json') -Encoding utf8
  Write-Output "Result checker: passed=$passed, contracts=$($after.boundaryContractsMatched)/$($after.boundaryTotal), Gold alerts=$($before.goldSuspicious)->$($after.goldSuspicious)/200, report=$OutputDir/summary.json"
  if (-not $passed) { throw 'Result-checker acceptance failed; inspect summary.json.' }
} finally {
  Pop-Location
}
