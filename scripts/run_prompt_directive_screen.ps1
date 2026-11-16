param(
  [string]$OutputDir = "reports/prompt-directive-$(Get-Date -Format 'yyyyMMdd-HHmmss')",
  [switch]$DryRunOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  if (Test-Path -LiteralPath $OutputDir) { throw 'Output directory exists; never overwrite evidence.' }
  $codeCommit = (& git rev-parse HEAD).Trim()
  $protocol = 'docs/PROMPT_DIRECTIVE_PROTOCOL_20261116.md'
  $protected = @('src', 'pom.xml', 'data/eval', 'scripts/run_eval.ps1',
    'scripts/run_prompt_directive_screen.ps1', 'scripts/summarize_prompt_directive.py',
    'scripts/test_prompt_directive_evidence.py', 'scripts/summarize_output_budget.py',
    'scripts/summarize_request_budget.py', 'scripts/summarize_holdout.py', $protocol)
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
    if ($null -ne $manifest -and $manifest.Contains('promptPairsSha256')) {
      if ((Get-FileHash -LiteralPath (Join-Path $OutputDir 'prompt-pairs.json') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $manifest.promptPairsSha256) { throw 'Prompt witnesses changed.' }
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
  $runs = @(@{name='gold'; split='all'; explicit=$true; count=200; dry=$true})
  if (-not $DryRunOnly) {
    $runs += @(@{name='explicit-steps'; split='dev'; explicit=$true; count=100; dry=$false},
      @{name='no-explicit-steps'; split='dev'; explicit=$false; count=100; dry=$false})
  }
  [IO.Directory]::CreateDirectory((Join-Path $projectRoot $OutputDir)) | Out-Null
  $manifest = [ordered]@{format='prompt-directive-screen-v1'; configuredReasoningEffort='provider-default'; state='RUNNING'; startedAt=(Get-Date -Format o)
    codeCommit=$codeCommit; protectedSha256=$hashes; distinctRealQuestions=$(if ($DryRunOnly) { 0 } else { 100 }); plannedRealRequests=$(if ($DryRunOnly) { 0 } else { 200 })
    dryRunOnly=$DryRunOnly.IsPresent; order=@($runs | ForEach-Object { $_.name }); runs=@()}
  $manifestPath = Join-Path $OutputDir 'manifest.json'
  function Save-Manifest { $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8 }
  Save-Manifest
  if (-not $DryRunOnly) {
    & mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/directive-screen-classpath.txt' *> (Join-Path $OutputDir 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Prompt witness compilation failed.' }
    $probeCp = 'target/test-classes;target/classes;' + (Get-Content target/directive-screen-classpath.txt -Raw).Trim()
    & java -cp $probeCp com.text2sql.agent.retrieval.PromptDirectivePairProbe (Join-Path $OutputDir 'prompt-pairs.json') $codeCommit *> (Join-Path $OutputDir 'prompt-pairs.log')
    if ($LASTEXITCODE -ne 0) { throw 'Prompt witness capture failed.' }
    $manifest.promptPairsSha256 = (Get-FileHash -LiteralPath (Join-Path $OutputDir 'prompt-pairs.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    $pairs = Get-Content -LiteralPath (Join-Path $OutputDir 'prompt-pairs.json') -Raw | ConvertFrom-Json
    if ($pairs.items.Count -ne 100 -or $pairs.codeCommit -cne $codeCommit -or $pairs.modelCalls -ne 0) { throw 'Wrong prompt witnesses.' }
    $pairById = @{}
    foreach ($item in $pairs.items) { if ($pairById.ContainsKey($item.id)) { throw 'Duplicate prompt witness.' }; $pairById[$item.id] = $item }
    Save-Manifest
  }
  foreach ($run in $runs) {
    Assert-Frozen
    $directory = Join-Path $OutputDir $run.name
    [IO.Directory]::CreateDirectory((Join-Path $projectRoot $directory)) | Out-Null
    $entry = [ordered]@{name=$run.name; state='RUNNING'; startedAt=(Get-Date -Format o); explicitReasoningEnabled=$run.explicit}
    $manifest.runs += $entry
    Save-Manifest
    $parameters = @{Split=$run.split; MaxTokens=8192; ReasoningEffort='provider-default'; DisableExplicitReasoning=(!$run.explicit); OutputDir=$directory; SelfCorrection=$true; ResultChecker=$true}
    if ($run.dry) { $parameters.DryRun=$true }
    Write-Output "Starting $($run.name): split=$($run.split), questions=$($run.count), maxTokens=8192, explicitReasoning=$($run.explicit)"
    & "$PSScriptRoot/run_eval.ps1" @parameters *> (Join-Path $directory 'run.log')
    Assert-Frozen
    $reports = @(Get-ChildItem -LiteralPath $directory -Filter 'eval-*.json' -File)
    if ($reports.Count -ne 1) { throw 'Missing or duplicate report.' }
    $report = Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
    if ($report.meta.gitCommit -cne $codeCommit -or $report.overall.total -ne $run.count) { throw 'Commit or denominator differs.' }
    if ($report.meta.effectiveConfig.configuredReasoningEffort -cne 'provider-default' -or $report.meta.effectiveConfig.maxTokens -ne 8192 -or $report.meta.effectiveConfig.explicitReasoningEnabled -ne $run.explicit) { throw 'Generation configuration differs.' }
    if (-not $run.dry) {
      $hashField = if ($run.explicit) { 'defaultPromptSha256' } else { 'candidatePromptSha256' }
      $version = if ($run.explicit) { $pairs.defaultPromptVersion } else { $pairs.candidatePromptVersion }
      if ($report.meta.promptVersion -cne $version -or $report.meta.datasetSha256 -cne $pairs.datasetSha256 -or $report.meta.sampleIdsSha256 -cne $pairs.sampleIdsSha256) { throw 'Prompt version or dataset differs from witnesses.' }
      foreach ($item in $report.itemEvidence) {
        if (-not $pairById.ContainsKey($item.id) -or $item.questionSha256 -cne $pairById[$item.id].questionSha256) { throw "Question differs: $($item.id)" }
        if ($item.calls.Count -gt 0 -and $item.calls[0].promptSha256 -cne $pairById[$item.id].$hashField) { throw "First prompt differs from captured variant: $($item.id)" }
      }
    }
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
