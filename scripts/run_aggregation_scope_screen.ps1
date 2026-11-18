param([string]$OutputDir = 'reports/aggregation-scope-screen-20261118')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  if (Test-Path -LiteralPath $OutputDir) { throw 'Never overwrite or replay an existing screen.' }
  $codeCommit = (& git rev-parse HEAD).Trim()
  $protected = @('src','pom.xml','data/eval','scripts/run_eval.ps1','scripts/run_aggregation_scope_screen.ps1',
    'scripts/summarize_aggregation_scope.py','scripts/summarize_holdout.py','scripts/summarize_request_budget.py',
    'docs/SEMANTIC_CONSISTENCY_MATRIX_20261117.md')
  if (@(& git status --porcelain -- $protected).Count -gt 0) { throw 'Commit protected inputs first.' }
  $hashes = [ordered]@{}
  foreach ($file in @(& git ls-files -- $protected)) {
    $hashes[$file] = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  $localConfig = 'src/main/resources/application-local.yml'
  $hashes[$localConfig] = (Get-FileHash -LiteralPath $localConfig -Algorithm SHA256).Hash.ToLowerInvariant()
  $taskCp = 'target/test-classes;target/classes;' + (Get-Content target/customer-identity-classpath.txt -Raw).Trim()
  $runtime = [ordered]@{}
  foreach ($file in @(Get-ChildItem target/classes,target/test-classes,target/aggregation-p15 -Recurse -File)) {
    $runtime[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  foreach ($entry in $taskCp.Split(';')) {
    if (Test-Path -LiteralPath $entry -PathType Leaf) {
      $runtime[(Resolve-Path -LiteralPath $entry).Path] = (Get-FileHash -LiteralPath $entry -Algorithm SHA256).Hash.ToLowerInvariant()
    }
  }
  function Assert-Frozen {
    if ((& git rev-parse HEAD).Trim() -cne $codeCommit -or @(& git status --porcelain -- $protected).Count -gt 0) { throw 'Protected code/inputs changed.' }
    foreach ($set in @($hashes,$runtime)) {
      foreach ($file in $set.Keys) {
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $set[$file]) { throw "Frozen bytes changed: $file" }
      }
    }
  }
  Assert-Frozen
  [IO.Directory]::CreateDirectory((Join-Path $projectRoot $OutputDir)) | Out-Null
  $manifestPath = Join-Path $OutputDir 'manifest.json'
  $manifest = [ordered]@{format='aggregation-scope-screen-v1';state='RUNNING';startedAt=(Get-Date -Format o)
    codeCommit=$codeCommit;baselineSourceCommit='4cb09014df5931cead0caa5e4b79b0f95e28f732'
    protectedSha256=$hashes;runtimeSha256=$runtime;distinctRealQuestions=100;plannedRealRequests=200
    order=@('p15','p16');runs=@()}
  function Save-Manifest { $manifest | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $manifestPath -Encoding utf8 }
  Save-Manifest
  foreach ($name in $manifest.order) {
    $armCp = if ($name -eq 'p15') { 'target/aggregation-p15/classes;' + $taskCp } else { $taskCp }
    & java -cp $armCp com.text2sql.agent.retrieval.AggregationScopePairProbe (Join-Path $OutputDir "$name-messages.json") *> (Join-Path $OutputDir "$name-messages.log")
    if ($LASTEXITCODE -ne 0) { throw "Message capture failed: $name" }
  }
  & python scripts/summarize_aggregation_scope.py $OutputDir --preflight
  if ($LASTEXITCODE -ne 0) { throw 'Message pairs differ beyond system rule 9.' }
  foreach ($name in $manifest.order) {
    $path = Join-Path $OutputDir "$name-messages.json"
    $hashes[$path] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
  }
  Save-Manifest
  if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
    $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY','User')
  }
  if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) { throw 'Model credential missing.' }
  foreach ($name in $manifest.order) {
    Assert-Frozen
    $directory = Join-Path $OutputDir $name
    [IO.Directory]::CreateDirectory((Join-Path $projectRoot $directory)) | Out-Null
    $entry = [ordered]@{name=$name;state='RUNNING';startedAt=(Get-Date -Format o)}
    $manifest.runs += $entry; Save-Manifest
    $armCp = if ($name -eq 'p15') { 'target/aggregation-p15/classes;' + $taskCp } else { $taskCp }
    $parameters = @{Split='dev';SelfCorrection=$true;ResultChecker=$true;OutputDir=$directory;Preview=$true}
    $javaArgs = (& "$PSScriptRoot/run_eval.ps1" @parameters).Split(' ',[StringSplitOptions]::RemoveEmptyEntries)
    Write-Output "Starting $name (100 original dev questions)"
    & java -cp $armCp com.text2sql.agent.Text2SqlAgentApplication @javaArgs *> (Join-Path $directory 'run.log')
    $entry.exitCode=$LASTEXITCODE; Save-Manifest
    if ($entry.exitCode -ne 0) { throw "Run failed, no replay: $name" }
    Assert-Frozen
    $files = @(Get-ChildItem -LiteralPath $directory -Filter 'eval-*.json' -File)
    if ($files.Count -ne 1) { throw 'Missing/duplicate report.' }
    $report=Get-Content -LiteralPath $files[0].FullName -Raw | ConvertFrom-Json
    $expectedVersion=if ($name -eq 'p15') { 'p15-entity-coverage-v15' } else { 'p16-aggregation-scope-v16' }
    if ($report.meta.gitCommit -cne $codeCommit -or $report.meta.promptVersion -cne $expectedVersion -or $report.overall.total -ne 100) { throw 'Wrong runtime identity or denominator.' }
    $entry.state='COMPLETE';$entry.completedAt=(Get-Date -Format o);$entry.report=$files[0].Name
    $entry.reportSha256=(Get-FileHash -LiteralPath $files[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $entry.correct=$report.overall.correct; Save-Manifest
    Write-Output "Completed ${name}: $($entry.correct)/100"
  }
  $manifest.state='COMPLETE';$manifest.completedAt=(Get-Date -Format o); Save-Manifest
} catch {
  if ($null -ne $manifest -and $null -ne $manifestPath) {
    $manifest.state='FAILED';$manifest.failure=$_.Exception.Message; Save-Manifest
  }
  throw
} finally { Pop-Location }
