param(
  [string]$OutputDir = "reports/request-budget-$(Get-Date -Format 'yyyyMMdd-HHmmss')",
  [switch]$DryRunOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  if (Test-Path -LiteralPath $OutputDir) { throw 'Output directory already exists; never overwrite evidence.' }
  $codeCommit = (& git rev-parse HEAD).Trim()
  $protected = @('src', 'pom.xml', 'data/eval', 'scripts/run_eval.ps1',
    'scripts/run_request_budget_matrix.ps1', 'docs/REQUEST_BUDGET_PROTOCOL_20261116.md')
  if ((& git status --porcelain -- $protected).Count -gt 0) { throw 'Commit protected code, inputs and protocol first.' }
  $frozen = @(& git ls-files -- $protected)
  $hashes = [ordered]@{}
  foreach ($file in $frozen) { $hashes[$file] = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() }
  # Include local config identity without ever serializing its values or credentials.
  $localConfig = 'src/main/resources/application-local.yml'
  $hashes[$localConfig] = (Get-FileHash -LiteralPath $localConfig -Algorithm SHA256).Hash.ToLowerInvariant()
  foreach ($file in @(& git ls-files -- data/eval | Where-Object { $_ -match '\.ya?ml$' })) {
    if ((& git hash-object --no-filters -- $file).Trim() -cne (& git rev-parse "${codeCommit}:$file").Trim()) {
      throw "Input bytes differ from committed data: $file"
    }
  }
  $runs = @(@{ name='gold'; split='all'; count=200; dry=$true; enabled=$true })
  if (-not $DryRunOnly) {
    $runs += @(
      @{name='r1-off'; split='eval'; count=100; dry=$false; enabled=$false},
      @{name='r1-on'; split='eval'; count=100; dry=$false; enabled=$true},
      @{name='r2-on'; split='eval'; count=100; dry=$false; enabled=$true},
      @{name='r2-off'; split='eval'; count=100; dry=$false; enabled=$false},
      @{name='r3-off'; split='eval'; count=100; dry=$false; enabled=$false},
      @{name='r3-on'; split='eval'; count=100; dry=$false; enabled=$true}
    )
  }
  [System.IO.Directory]::CreateDirectory((Join-Path $projectRoot $OutputDir)) | Out-Null
  $manifest = [ordered]@{
    format='request-budget-matrix-v1'; state='RUNNING'; startedAt=(Get-Date -Format o)
    codeCommit=$codeCommit; protectedSha256=$hashes; dryRunOnly=$DryRunOnly.IsPresent
    timeoutMs=15000; maxModelCalls=2; retryReserveMs=2000; sdkMaxAttempts=1
    order=@($runs | ForEach-Object { $_.name }); runs=@()
  }
  $manifestPath = Join-Path $OutputDir 'manifest.json'
  $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
  foreach ($run in $runs) {
    if ((& git rev-parse HEAD).Trim() -cne $codeCommit -or (& git status --porcelain -- $protected).Count -gt 0) {
      throw 'Protected code/input/protocol changed; do not mix runs.'
    }
    foreach ($file in $hashes.Keys) {
      if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hashes[$file]) {
        throw "Protected file bytes changed: $file"
      }
    }
    $directory = Join-Path $OutputDir $run.name
    [System.IO.Directory]::CreateDirectory((Join-Path $projectRoot $directory)) | Out-Null
    $entry = [ordered]@{name=$run.name; state='RUNNING'; startedAt=(Get-Date -Format o); directory=$directory; enabled=$run.enabled}
    $manifest.runs += $entry
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    $parameters = @{Split=$run.split; DatasetDir='data/eval'; OutputDir=$directory
      SelfCorrection=$true; ResultChecker=$true; DisableRequestBudget=(!$run.enabled)}
    if ($run.dry) { $parameters.DryRun=$true }
    Write-Output "Starting $($run.name), fixed commit $codeCommit"
    & "$PSScriptRoot/run_eval.ps1" @parameters *> (Join-Path $directory 'run.log')
    $reports = @(Get-ChildItem -LiteralPath $directory -Filter 'eval-*.json' -File)
    if ($reports.Count -ne 1) { throw "Missing or duplicate report: $($run.name)" }
    $evaluation = Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
    if ($evaluation.meta.gitCommit -cne $codeCommit -or $evaluation.overall.total -ne $run.count) {
      throw 'Changed commit or denominator.'
    }
    if ($run.dry -and ($evaluation.overall.correct -ne 200 -or @($evaluation.itemEvidence | Where-Object { $_.calls.Count -gt 0 }).Count -gt 0)) {
      throw 'Gold preflight failed or called model.'
    }
    $entry.state='COMPLETE'; $entry.completedAt=(Get-Date -Format o); $entry.report=$reports[0].Name
    $entry.reportSha256=(Get-FileHash -LiteralPath $reports[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $entry.total=$evaluation.overall.total; $entry.correct=$evaluation.overall.correct
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
} finally { Pop-Location }
