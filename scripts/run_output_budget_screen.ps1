param([string]$OutputDir = "reports/output-budget-$(Get-Date -Format 'yyyyMMdd-HHmmss')", [switch]$DryRunOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  if (Test-Path -LiteralPath $OutputDir) { throw 'Output directory exists; never overwrite evidence.' }
  $codeCommit = (& git rev-parse HEAD).Trim()
  $protected = @('src', 'pom.xml', 'data/eval', 'scripts/run_eval.ps1',
    'scripts/run_output_budget_screen.ps1', 'docs/OUTPUT_BUDGET_PROTOCOL_20261116.md')
  $hashes = [ordered]@{}
  foreach ($file in @(& git ls-files -- $protected)) {
    $hashes[$file] = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  $localConfig = 'src/main/resources/application-local.yml'
  $hashes[$localConfig] = (Get-FileHash -LiteralPath $localConfig -Algorithm SHA256).Hash.ToLowerInvariant()
  function Assert-Frozen {
    if ((& git rev-parse HEAD).Trim() -cne $codeCommit -or @(& git status --porcelain -- $protected).Count -gt 0) {
      throw 'Commit protected code/inputs/protocol and keep them frozen during the experiment.'
    }
    foreach ($file in $hashes.Keys) {
      if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hashes[$file]) {
        throw "Frozen bytes changed: $file"
      }
    }
  }
  Assert-Frozen
  foreach ($file in @(& git ls-files -- data/eval | Where-Object { $_ -match '\.ya?ml$' })) {
    if ((& git hash-object --no-filters -- $file).Trim() -cne (& git rev-parse "${codeCommit}:$file").Trim()) {
      throw "Input bytes differ from commit: $file"
    }
  }
  $runs = @(@{name='gold'; split='all'; tokens=8192; count=200; dry=$true})
  if (-not $DryRunOnly) {
    $runs += @(@{name='baseline-8192'; split='dev'; tokens=8192; count=100; dry=$false},
      @{name='candidate-2048'; split='dev'; tokens=2048; count=100; dry=$false})
  }
  [IO.Directory]::CreateDirectory((Join-Path $projectRoot $OutputDir)) | Out-Null
  $manifest = [ordered]@{format='output-budget-screen-v1'; state='RUNNING'; startedAt=(Get-Date -Format o)
    codeCommit=$codeCommit; protectedSha256=$hashes; distinctRealQuestions=100; plannedRealRequests=200
    dryRunOnly=$DryRunOnly.IsPresent; order=@($runs | ForEach-Object { $_.name }); runs=@()}
  $manifestPath = Join-Path $OutputDir 'manifest.json'
  function Save-Manifest { $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8 }
  Save-Manifest
  foreach ($run in $runs) {
    Assert-Frozen
    $directory = Join-Path $OutputDir $run.name
    [IO.Directory]::CreateDirectory((Join-Path $projectRoot $directory)) | Out-Null
    $entry = [ordered]@{name=$run.name; state='RUNNING'; startedAt=(Get-Date -Format o); maxTokens=$run.tokens}
    $manifest.runs += $entry
    Save-Manifest
    $parameters = @{Split=$run.split; MaxTokens=$run.tokens; OutputDir=$directory; SelfCorrection=$true; ResultChecker=$true}
    if ($run.dry) { $parameters.DryRun=$true }
    Write-Output "Starting $($run.name): split=$($run.split), questions=$($run.count), maxTokens=$($run.tokens)"
    & "$PSScriptRoot/run_eval.ps1" @parameters *> (Join-Path $directory 'run.log')
    Assert-Frozen
    $reports = @(Get-ChildItem -LiteralPath $directory -Filter 'eval-*.json' -File)
    if ($reports.Count -ne 1) { throw 'Missing or duplicate report.' }
    $report = Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
    if ($report.meta.gitCommit -cne $codeCommit -or $report.overall.total -ne $run.count) { throw 'Commit or denominator differs.' }
    if ($run.dry -and ($report.overall.correct -ne 200 -or @($report.itemEvidence | Where-Object { $_.calls.Count -gt 0 }).Count -gt 0)) {
      throw 'Gold preflight failed or called model.'
    }
    $entry.state='COMPLETE'; $entry.completedAt=(Get-Date -Format o); $entry.report=$reports[0].Name
    $entry.reportSha256=(Get-FileHash -LiteralPath $reports[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $entry.total=$report.overall.total; $entry.correct=$report.overall.correct
    Save-Manifest
    Write-Output "Completed $($run.name): $($entry.correct)/$($entry.total)"
  }
  $manifest.state='COMPLETE'; $manifest.completedAt=(Get-Date -Format o)
  Save-Manifest
} catch {
  if ($null -ne $manifest -and $null -ne $manifestPath) {
    $manifest.state='FAILED'; $manifest.failure=$_.Exception.Message
    Save-Manifest
  }
  throw
} finally { Pop-Location }
