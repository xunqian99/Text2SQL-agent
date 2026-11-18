param(
  [string]$OutputDir = 'reports/stage2-regression-20261117',
  [ValidateSet('p10-result-contract-v10','p15-entity-coverage-v15','p17-time-dimension-v17','p18-dimension-display-v18','p19-request-scope-v19')]
  [string]$PromptVersion = 'p10-result-contract-v10',
  [string]$ProtocolPath = 'docs/STAGE2_REGRESSION_PROTOCOL_20261117.md'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  if (Test-Path -LiteralPath $OutputDir) { throw 'Output directory exists; never overwrite or replay.' }
  if (-not (Test-Path -LiteralPath $ProtocolPath -PathType Leaf) -or
      -not (Get-Content -LiteralPath $ProtocolPath -Raw).Contains($PromptVersion)) {
    throw 'Protocol must exist and declare the expected prompt version before evaluation.'
  }
  $codeCommit = (& git rev-parse HEAD).Trim()
  $protected = @('src','pom.xml','data/eval','data/holdout','scripts/run_eval.ps1',
    'scripts/run_stage2_regression.ps1','scripts/summarize_stage2_regression.py',
    'scripts/summarize_request_budget.py','scripts/summarize_holdout.py',
    'docs/STAGE2_REGRESSION_PROTOCOL_20261117.md',$ProtocolPath,
    'reports/generation-mode-matrix-20261116','reports/ablation-20261116/summary.json',
    'reports/customer-identity-http-20261117','reports/output-contract-http-20261117')
  $hashes = [ordered]@{}
  foreach ($file in @(& git ls-files -- $protected)) {
    $hashes[$file] = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  $hashes['src/main/resources/application-local.yml'] = (Get-FileHash 'src/main/resources/application-local.yml' -Algorithm SHA256).Hash.ToLowerInvariant()
  $evalCp = 'target/test-classes;target/classes;' + (Get-Content target/customer-identity-classpath.txt -Raw).Trim()
  $runtime = [ordered]@{}
  foreach ($file in @(Get-ChildItem target/classes,target/test-classes -Recurse -File | Sort-Object FullName)) {
    $runtime[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  foreach ($entry in $evalCp.Split(';')) {
    if (Test-Path -LiteralPath $entry -PathType Leaf) {
      $runtime[(Resolve-Path -LiteralPath $entry).Path] = (Get-FileHash -LiteralPath $entry -Algorithm SHA256).Hash.ToLowerInvariant()
    }
  }
  function Assert-Frozen {
    if ((& git rev-parse HEAD).Trim() -cne $codeCommit -or @(& git status --porcelain -- $protected).Count -gt 0) {
      throw 'Protected code, inputs, protocol or commit changed.'
    }
    foreach ($set in @($hashes,$runtime)) {
      foreach ($file in $set.Keys) {
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $set[$file]) {
          throw "Frozen bytes changed: $file"
        }
      }
    }
  }
  Assert-Frozen
  $baseline = Get-Content reports/generation-mode-matrix-20261116/manifest.json -Raw | ConvertFrom-Json
  $defaultRuns = @($baseline.runs | Where-Object { $_.name -match '^r[123]-default$' })
  if ($baseline.state -ne 'COMPLETE' -or $defaultRuns.Count -ne 3 -or ($defaultRuns.correct | Measure-Object -Average).Average -ne 91) {
    throw 'Preregistered historical default baseline differs.'
  }
  [IO.Directory]::CreateDirectory((Join-Path $projectRoot $OutputDir)) | Out-Null
  $manifestPath = Join-Path $OutputDir 'manifest.json'
  $manifest = [ordered]@{format='stage2-regression-v1';state='RUNNING';startedAt=(Get-Date -Format o)
    codeCommit=$codeCommit;protectedSha256=$hashes;runtimeSha256=$runtime
    expectedPromptVersion=$PromptVersion;protocolPath=$ProtocolPath
    referenceAccuracyPercent=91;minimumMeanAccuracyPercent=89;historicalComparison=$true
    distinctRealQuestions=100;plannedRealRequests=300;order=@('gold','r1','r2','r3');runs=@()}
  function Save-Manifest { $manifest | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $manifestPath -Encoding utf8 }
  Save-Manifest
  if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
    $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY','User')
  }
  if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) { throw 'Global model credential missing.' }
  foreach ($name in $manifest.order) {
    Assert-Frozen
    $directory = Join-Path $OutputDir $name
    [IO.Directory]::CreateDirectory((Join-Path $projectRoot $directory)) | Out-Null
    $entry = [ordered]@{name=$name;state='RUNNING';startedAt=(Get-Date -Format o)}
    $manifest.runs += $entry; Save-Manifest
    $parameters = @{Split='eval';SelfCorrection=$true;ResultChecker=$true;OutputDir=$directory;Preview=$true}
    $count = 100
    if ($name -eq 'gold') { $parameters.Split='all'; $parameters.DryRun=$true; $count=200 }
    $argumentLine = (& "$PSScriptRoot/run_eval.ps1" @parameters)
    $javaArgs = $argumentLine.Split(' ',[StringSplitOptions]::RemoveEmptyEntries)
    Write-Output "Starting $name ($count original questions)"
    & java -cp $evalCp com.text2sql.agent.Text2SqlAgentApplication @javaArgs *> (Join-Path $directory 'run.log')
    $entry.exitCode = $LASTEXITCODE
    Save-Manifest
    if ($entry.exitCode -ne 0) { throw "Java run failed: $name, exit=$($entry.exitCode); retained, no replay." }
    Assert-Frozen
    $reports = @(Get-ChildItem -LiteralPath $directory -Filter 'eval-*.json' -File)
    if ($reports.Count -ne 1) { throw 'Missing or duplicate report.' }
    $report = Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
    if ($report.meta.gitCommit -cne $codeCommit -or $report.overall.total -ne $count) { throw 'Code or denominator differs.' }
    if ($report.meta.promptVersion -cne $PromptVersion) { throw 'Runtime prompt differs from preregistered candidate.' }
    if ($name -eq 'gold' -and ($report.overall.correct -ne 200 -or @($report.itemEvidence | Where-Object {$_.calls.Count -gt 0}).Count -gt 0)) {
      throw 'Gold preflight failed or called model.'
    }
    $entry.state='COMPLETE';$entry.completedAt=(Get-Date -Format o);$entry.report=$reports[0].Name
    $entry.reportSha256=(Get-FileHash -LiteralPath $reports[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $entry.total=$report.overall.total;$entry.correct=$report.overall.correct
    Save-Manifest
    Write-Output "Completed ${name}: $($entry.correct)/$count"
  }
  $manifest.state='COMPLETE';$manifest.completedAt=(Get-Date -Format o);Save-Manifest
} catch {
  if ($null -ne $manifest -and $null -ne $manifestPath) {
    $manifest.state='FAILED';$manifest.failure=$_.Exception.Message;Save-Manifest
  }
  throw
} finally { Pop-Location }
