$ErrorActionPreference='Stop'
$base='reports/replan-stage-b-20261119/attempt-4'
$out='reports/replan-stage-b-20261119/independent-review/submission-4'
$manifest=Get-Content -Raw "$base/submission-4-manifest.json" | ConvertFrom-Json
$identity=Get-Content -Raw "$base/candidate-identity.json" | ConvertFrom-Json
$drift=@();$counts=@{}
foreach($field in @('files','sourceFiles','compiledClasses')) {
  $source=if($field -eq 'files'){$manifest}else{$identity}
  $n=0
  foreach($entry in $source.$field.PSObject.Properties) {
    if($entry.Name -match '(^|[\\/])(blind|holdout)([\\/]|$)'){throw 'Forbidden evidence path in manifest'}
    $n++
    if(!(Test-Path -LiteralPath $entry.Name -PathType Leaf)) {$drift+=@{group=$field;path=$entry.Name;issue='missing'};continue}
    $actual=(Get-FileHash -LiteralPath $entry.Name -Algorithm SHA256).Hash.ToLowerInvariant()
    if($actual -ne $entry.Value){$drift+=@{group=$field;path=$entry.Name;expected=$entry.Value;actual=$actual}}
  }
  $counts[$field]=$n
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarPath='target/text2sql-agent-0.1.0-SNAPSHOT.jar'
$zip=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $jarPath))
$baselineJar='.private/recovery/p21-ea5a8a4/text2sql-agent-p21.jar'
$baselineZip=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $baselineJar))
try {
  $n=0
  foreach($entry in $identity.dependencies.PSObject.Properties) {
    $n++;$stream=$zip.GetEntry($entry.Name).Open()
    try{$sha=[System.Security.Cryptography.SHA256]::Create();$actual=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$sha.Dispose()}
    $baselineStream=$baselineZip.GetEntry($entry.Name).Open()
    try{$sha=[System.Security.Cryptography.SHA256]::Create();$baseline=[BitConverter]::ToString($sha.ComputeHash($baselineStream)).Replace('-','').ToLowerInvariant()}finally{$baselineStream.Dispose();$sha.Dispose()}
    if($actual -ne $entry.Value -or $actual -ne $baseline){$drift+=@{group='dependencies';path=$entry.Name;expected=$entry.Value;actual=$actual;baseline=$baseline}}
  }
  $counts['dependencies']=$n
  $n=0
  foreach($entry in $identity.compiledClasses.PSObject.Properties) {
    if(!$entry.Name.StartsWith('target/classes/')){continue}
    $n++;$zipName=$entry.Name.Replace('target/classes/','BOOT-INF/classes/')
    $zipEntry=$zip.GetEntry($zipName)
    if($null -eq $zipEntry){$drift+=@{group='packagedClasses';path=$zipName;issue='missing'};continue}
    $stream=$zipEntry.Open()
    try{$sha=[System.Security.Cryptography.SHA256]::Create();$actual=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$sha.Dispose()}
    if($actual -ne $entry.Value){$drift+=@{group='packagedClasses';path=$zipName;expected=$entry.Value;actual=$actual}}
  }
  $counts['packagedClasses']=$n
}finally{$zip.Dispose();$baselineZip.Dispose()}
$capture=@()
foreach($set in @('original200','development100','formal100')) {
  $oldHash=(Get-FileHash -LiteralPath "reports/replan-stage-b-20261119/messages-p21-$set.json" -Algorithm SHA256).Hash.ToLowerInvariant()
  $reusedHash=(Get-FileHash -LiteralPath "$base/messages-p21-$set.json" -Algorithm SHA256).Hash.ToLowerInvariant()
  $before=Get-Content -Raw "$base/messages-p21-$set.json" | ConvertFrom-Json
  $after=Get-Content -Raw "$base/messages-B-$set.json" | ConvertFrom-Json
  $diff=@()
  if($before.items.Count -ne $after.items.Count){$diff+='COUNT'}
  for($i=0;$i -lt $before.items.Count;$i++) {
    $a=$before.items[$i];$b=$after.items[$i]
    $left=[ordered]@{id=$a.id;question=$a.question;retrievedTables=@($a.retrievedTables);schemaDdlChars=$a.schemaDdlChars;messages=@($a.messages|ForEach-Object{[ordered]@{role=$_.role;text=$_.text}})}|ConvertTo-Json -Depth 30 -Compress
    $right=[ordered]@{id=$b.id;question=$b.question;retrievedTables=@($b.retrievedTables);schemaDdlChars=$b.schemaDdlChars;messages=@($b.messages|ForEach-Object{[ordered]@{role=$_.role;text=$_.text}})}|ConvertTo-Json -Depth 30 -Compress
    if($left -cne $right){$diff+=$a.id}
  }
  $capture+=@{dataset=$set;compared=$before.items.Count;mismatches=$diff;baselineReuseHashEqual=($oldHash -eq $reusedHash)}
}
$suites=@(Get-ChildItem "$base/full-build-final/surefire" -Filter 'TEST-*.xml')
$tests=0;$fail=0;$err=0;$skip=0
foreach($suite in $suites){[xml]$x=Get-Content -Raw $suite.FullName;$tests+=[int]$x.testsuite.tests;$fail+=[int]$x.testsuite.failures;$err+=[int]$x.testsuite.errors;$skip+=[int]$x.testsuite.skipped}
$history=Get-Content -Raw reports/replan-stage-b-20261119/independent-review/submission-1-evidence-manifest.json | ConvertFrom-Json
$historyDrift=@()
foreach($entry in $history.PSObject.Properties){$actual=(Get-FileHash -LiteralPath "reports/replan-stage-b-20261119/independent-review/submission-1/$($entry.Name)" -Algorithm SHA256).Hash.ToLowerInvariant();if($actual -ne $entry.Value){$historyDrift+=$entry.Name}}
$oldManifest=Get-Content -Raw reports/replan-stage-b-20261119/attempt-2/submission-2-manifest.json | ConvertFrom-Json
$oldEvidence=@($oldManifest.files.PSObject.Properties | Where-Object {$_.Name -match '(attempt-2/|independent-review/submission-2/|independent-review/(IndependentBProbe|ExtendedBProbe)\.java$)'})
$oldEvidenceDrift=@()
foreach($entry in $oldEvidence){$actual=(Get-FileHash -LiteralPath $entry.Name -Algorithm SHA256).Hash.ToLowerInvariant();if($actual -ne $entry.Value){$oldEvidenceDrift+=$entry.Name}}
$result=[ordered]@{candidateSourceCommit='9d0c3908ec461f2d92821e5a41d9aa42a6b50390';currentHead=(& git rev-parse HEAD);counts=$counts;drift=$drift;captureComparison=$capture;finalMaven=@{suites=$suites.Count;tests=$tests;failures=$fail;errors=$err;skipped=$skip};jarSha256=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant();savedJarSha256=(Get-FileHash -LiteralPath $identity.savedCandidateJar -Algorithm SHA256).Hash.ToLowerInvariant();expectedJarSha256=$identity.jarSha256;baselineJarSha256=(Get-FileHash -LiteralPath $baselineJar -Algorithm SHA256).Hash.ToLowerInvariant();submission1ReviewEvidenceDrift=$historyDrift;submission2EvidenceChecked=$oldEvidence.Count;submission2EvidenceDrift=$oldEvidenceDrift;providerCalls=0}
$result|ConvertTo-Json -Depth 25|Set-Content -Encoding utf8 "$out/evidence-verification-corrected.json"
@{counts=$counts;driftCount=$drift.Count;finalMaven=$result.finalMaven;captures=$capture;history1Drift=$historyDrift.Count;history2Drift=$oldEvidenceDrift.Count}|ConvertTo-Json -Depth 10

