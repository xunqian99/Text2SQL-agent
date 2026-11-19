$ErrorActionPreference = 'Stop'
$reviewRoot = Join-Path $PSScriptRoot 'submission-1'
New-Item -ItemType Directory -Path $reviewRoot -Force | Out-Null
$manifest = Get-Content -Raw reports/replan-stage-b-20261119/submission-1-manifest.json | ConvertFrom-Json
$drift = @()
$n = 0
foreach ($entry in $manifest.files.PSObject.Properties) {
  $n++
  if (!(Test-Path -LiteralPath $entry.Name -PathType Leaf)) { $drift += @{path=$entry.Name; issue='MISSING'}; continue }
  $actual = (Get-FileHash -LiteralPath $entry.Name -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $entry.Value) { $drift += @{path=$entry.Name; expected=$entry.Value; actual=$actual} }
}
$supplement = Get-Content -Raw reports/replan-stage-b-20261119/submission-1-index-supplement.json | ConvertFrom-Json
foreach ($entry in $supplement.files.PSObject.Properties) {
  $actual = (Get-FileHash -LiteralPath $entry.Name -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $entry.Value) { $drift += @{path=$entry.Name; expected=$entry.Value; actual=$actual} }
}
$captures = @()
foreach ($set in @('original200','development100','formal100')) {
  $before=Get-Content -Raw "reports/replan-stage-b-20261119/messages-p21-$set.json" | ConvertFrom-Json
  $after=Get-Content -Raw "reports/replan-stage-b-20261119/messages-B-$set.json" | ConvertFrom-Json
  $mismatches=@()
  if ($before.items.Count -ne $after.items.Count) { $mismatches += 'COUNT' }
  for ($i=0; $i -lt $before.items.Count; $i++) {
    # Object property order is not message order. Select a fixed property order;
    # preserve role/text and message sequence exactly and case-sensitively.
    $leftItem=$before.items[$i]; $rightItem=$after.items[$i]
    $left=[ordered]@{id=$leftItem.id;question=$leftItem.question;retrievedTables=@($leftItem.retrievedTables);schemaDdlChars=$leftItem.schemaDdlChars;messages=@($leftItem.messages | ForEach-Object {[ordered]@{role=$_.role;text=$_.text}})} | ConvertTo-Json -Depth 30 -Compress
    $right=[ordered]@{id=$rightItem.id;question=$rightItem.question;retrievedTables=@($rightItem.retrievedTables);schemaDdlChars=$rightItem.schemaDdlChars;messages=@($rightItem.messages | ForEach-Object {[ordered]@{role=$_.role;text=$_.text}})} | ConvertTo-Json -Depth 30 -Compress
    if ($left -cne $right) { $mismatches += $before.items[$i].id }
  }
  $captures += @{dataset=$set; compared=$before.items.Count; mismatches=$mismatches}
}
$suites=@(Get-ChildItem reports/replan-stage-b-20261119/full-build-final/surefire -Filter 'TEST-*.xml')
$tests=0; $failures=0; $errors=0; $skipped=0
foreach ($suite in $suites) {
  [xml]$xml=Get-Content -Raw -LiteralPath $suite.FullName
  $tests += [int]$xml.testsuite.tests; $failures += [int]$xml.testsuite.failures
  $errors += [int]$xml.testsuite.errors; $skipped += [int]$xml.testsuite.skipped
}
$patchOutput = & git apply --reverse --check reports/replan-stage-b-20261119/candidate.patch 2>&1
$patchExit=$LASTEXITCODE
$jar=(Get-FileHash -LiteralPath target/text2sql-agent-0.1.0-SNAPSHOT.jar -Algorithm SHA256).Hash.ToLowerInvariant()
$recoveryJar=(Get-FileHash -LiteralPath .private/recovery/replan-b0-20261119/text2sql-agent-B-candidate.jar -Algorithm SHA256).Hash.ToLowerInvariant()
$result=@{manifestFiles=$n; drift=$drift; supplementChecked=$true; captureComparison=$captures; finalMaven=@{suites=$suites.Count; tests=$tests; failures=$failures; errors=$errors; skipped=$skipped}; reversePatchExit=$patchExit; reversePatchOutput=@($patchOutput | ForEach-Object {"$_"}); jarSha256=$jar; recoveryJarSha256=$recoveryJar; providerCalls=0}
$result | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 (Join-Path $reviewRoot 'evidence-verification-v2.json')
$result | ConvertTo-Json -Depth 30
