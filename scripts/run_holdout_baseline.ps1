param(
  [string]$SuiteDir = 'data/holdout/v1',
  [string]$OutputDir = "reports/holdout-$(Get-Date -Format 'yyyyMMdd-HHmmss')",
  [switch]$DryRunOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  if (Test-Path -LiteralPath $OutputDir) { throw 'Output directory already exists; never overwrite a baseline.' }
  $sealPath = Join-Path $SuiteDir 'manifest.json'
  if (-not (Test-Path -LiteralPath $sealPath)) { throw 'Freeze the suite manifest before running.' }
  $seal = Get-Content -LiteralPath $sealPath -Raw | ConvertFrom-Json
  $codeCommit = (& git rev-parse HEAD).Trim()
  $protected = @('src', 'pom.xml', 'data/eval', 'data/holdout', 'scripts/run_eval.ps1', 'scripts/run_holdout_baseline.ps1')
  if ((& git status --porcelain -- $protected).Count -gt 0) { throw 'Commit evaluation-related source and suites before running.' }
  # A clean text diff can hide CRLF conversion. Frozen YAML/JSON must match committed bytes exactly.
  $frozenFiles = @(& git ls-files -- data/eval data/holdout | Where-Object { $_ -match '(?:/T\d+_.*\.ya?ml$|/manifest\.json$)' })
  foreach ($frozenFile in $frozenFiles) {
    $workingObject = (& git hash-object --no-filters -- $frozenFile).Trim()
    $committedObject = (& git rev-parse "${codeCommit}:$frozenFile").Trim()
    if ($workingObject -cne $committedObject) { throw "Frozen file bytes differ from checkout: $frozenFile (check line endings)." }
  }
  if (-not $DryRunOnly -and [string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
    $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY', 'User')
  }
  if (-not $DryRunOnly -and [string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) { throw 'Model key is unavailable.' }
  [System.IO.Directory]::CreateDirectory((Join-Path $projectRoot $OutputDir)) | Out-Null
  $runs = @(
    @{ name='reference-gold'; split='all'; dataset='data/eval'; seal=''; dry=$true; count=200 },
    @{ name='holdout-gold'; split='all'; dataset=$SuiteDir; seal=$sealPath; dry=$true; count=$seal.itemCount }
  )
  if (-not $DryRunOnly) {
    $runs += @(
      @{ name='development'; split='dev'; dataset='data/eval'; seal=''; dry=$false; count=100 },
      @{ name='holdout'; split='all'; dataset=$SuiteDir; seal=$sealPath; dry=$false; count=$seal.itemCount }
    )
  }
  $manifest = [ordered]@{
    format='holdout-baseline-run-v1'; state='RUNNING'; startedAt=(Get-Date -Format o)
    codeCommit=$codeCommit; suiteId=$seal.suiteId; suiteManifestSha256=(Get-FileHash -LiteralPath $sealPath -Algorithm SHA256).Hash.ToLowerInvariant()
    declaredIndependentReview=$seal.independentReview; order='reference-gold,holdout-gold,development,holdout'
    primaryConfiguration='clarification+production-correction+result-checker; cache/routing/tools/conversation/evaluator-correction off'
    runs=@()
  }
  $manifestPath = Join-Path $OutputDir 'manifest.json'
  $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
  foreach ($run in $runs) {
    if ((& git rev-parse HEAD).Trim() -cne $codeCommit -or (& git status --porcelain -- $protected).Count -gt 0) {
      throw 'Code/suite changed during the baseline; do not mix runs.'
    }
    $directory = Join-Path $OutputDir $run.name
    [System.IO.Directory]::CreateDirectory((Join-Path $projectRoot $directory)) | Out-Null
    $entry = [ordered]@{ name=$run.name; state='RUNNING'; startedAt=(Get-Date -Format o); directory=$directory }
    $manifest.runs += $entry
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    $parameters = @{ DatasetDir=$run.dataset; Split=$run.split; SuiteManifest=$run.seal; OutputDir=$directory
                     SelfCorrection=$true; ResultChecker=$true }
    if ($run.dry) { $parameters.DryRun = $true }
    Write-Output "Starting $($run.name) at fixed code $codeCommit; dryRun=$($run.dry)"
    & "$PSScriptRoot/run_eval.ps1" @parameters *> (Join-Path $directory 'run.log')
    $reports = @(Get-ChildItem -LiteralPath $directory -Filter 'eval-*.json' -File)
    if ($reports.Count -ne 1) { throw "Missing/duplicate report: $($run.name)" }
    $evaluation = Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
    if ($evaluation.overall.total -ne $run.count -or $evaluation.meta.gitCommit -cne $codeCommit) { throw 'Report denominator or code changed.' }
    if ($run.dry -and $evaluation.overall.correct -ne $run.count) { throw "Gold preflight failed: $($run.name)" }
    $entry.state='COMPLETE'; $entry.completedAt=(Get-Date -Format o)
    $entry.report=$reports[0].Name; $entry.correct=$evaluation.overall.correct; $entry.total=$evaluation.overall.total
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output "Completed $($run.name): $($entry.correct)/$($entry.total)"
  }
  $manifest.state='COMPLETE'; $manifest.completedAt=(Get-Date -Format o)
  $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
} catch {
  if ($null -ne $manifest -and $null -ne $manifestPath) {
    $manifest.state='FAILED'; $manifest.failure=$_.Exception.Message
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
  }
  throw
} finally {
  Pop-Location
}
