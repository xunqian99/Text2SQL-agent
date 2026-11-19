$ErrorActionPreference = 'Stop'
Set-Location 'C:/Users/xunqian/Desktop/agent'
$folder = 'reports/clarification-candidate-dev-screen-20261119'
$manifestPath = "$folder/manifest.json"
$manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
if ($manifest.state -ne 'READY') { throw 'Screen already started; no replay.' }
function Assert-Frozen {
  if ((git rev-parse HEAD).Trim() -cne $manifest.codeCommit) { throw 'HEAD changed' }
  foreach ($set in @($manifest.protectedSha256,$manifest.runtimeSha256)) {
    foreach ($entry in $set.PSObject.Properties) {
      if ((Get-FileHash -LiteralPath $entry.Name -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.Value) { throw "Frozen bytes changed: $($entry.Name)" }
    }
  }
  foreach ($entry in $manifest.captureSha256.PSObject.Properties) {
    if ((Get-FileHash -LiteralPath "$folder/$($entry.Name)" -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.Value) { throw 'Capture changed' }
  }
}
function Save-Manifest { $manifest | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $manifestPath -Encoding utf8 }
Assert-Frozen
if (Get-Process java -ErrorAction SilentlyContinue) { throw 'Java process already active' }
if (Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue) { throw 'Evaluation port occupied' }
$stamp = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time')
if ($stamp.DayOfWeek -notin @('Saturday','Sunday') -and (($stamp.Hour -ge 9 -and $stamp.Hour -lt 12) -or ($stamp.Hour -ge 14 -and $stamp.Hour -lt 18))) { throw 'Peak pricing window' }
if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) { $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY','User') }
if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) { throw 'Model credential missing' }
$argumentLine = & ./scripts/run_eval.ps1 -Split dev -SelfCorrection -ResultChecker -OutputDir $folder -Preview
$javaArgs = $argumentLine.Split(' ',[StringSplitOptions]::RemoveEmptyEntries)
$manifest.state='RUNNING'
$manifest | Add-Member startedAt $stamp.ToString('o')
$manifest | Add-Member arguments $javaArgs
Save-Manifest
try {
  & java -cp $manifest.classpath com.text2sql.agent.Text2SqlAgentApplication @javaArgs *> "$folder/run.log"
  $manifest | Add-Member exitCode $LASTEXITCODE
  Assert-Frozen
  if ($manifest.exitCode -ne 0) { throw 'Java process failed; retained, no replay' }
  $reports = @(Get-ChildItem $folder -Filter 'eval-*.json' -File)
  if ($reports.Count -ne 1) { throw 'Missing or duplicate report' }
  $report = Get-Content $reports[0].FullName -Raw | ConvertFrom-Json
  if ($report.meta.gitCommit -cne $manifest.codeCommit -or $report.overall.total -ne 100 -or $report.meta.promptVersion -cne $manifest.expectedPromptVersion) { throw 'Runtime identity differs' }
  $manifest.state='COMPLETE'
  $manifest | Add-Member completedAt (Get-Date -Format o)
  $manifest | Add-Member report $reports[0].Name
  $manifest | Add-Member reportSha256 (Get-FileHash $reports[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  Save-Manifest
  Write-Output "COMPLETE $($report.overall.correct)/100"
} catch {
  $manifest.state='FAILED'
  $manifest | Add-Member failure $_.Exception.Message
  Save-Manifest
  throw
}
