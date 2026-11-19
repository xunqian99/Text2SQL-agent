$ErrorActionPreference='Stop'
$base='reports/replan-stage-b-20261119/attempt-2'
$out='reports/replan-stage-b-20261119/independent-review/submission-2'
$manifest=Get-Content -Raw "$base/submission-2-manifest.json" | ConvertFrom-Json
$id=Get-Content -Raw "$base/candidate-identity.json" | ConvertFrom-Json
$drift=@();$counts=@{}
foreach($field in @('files','sourceFiles','compiledClasses')){
  $source=if($field -eq 'files'){$manifest}else{$id}
  $n=0
  foreach($entry in $source.$field.PSObject.Properties){
    $n++
    if(!(Test-Path -LiteralPath $entry.Name -PathType Leaf)){$drift+=@{group=$field;path=$entry.Name;issue='missing'};continue}
    $actual=(Get-FileHash -LiteralPath $entry.Name -Algorithm SHA256).Hash.ToLowerInvariant()
    if($actual -ne $entry.Value){$drift+=@{group=$field;path=$entry.Name;expected=$entry.Value;actual=$actual}}
  }
  $counts[$field]=$n
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path target/text2sql-agent-0.1.0-SNAPSHOT.jar))
try{
  $n=0
  foreach($entry in $id.dependencies.PSObject.Properties){
    $n++;$stream=$zip.GetEntry($entry.Name).Open()
    try{$sha=[System.Security.Cryptography.SHA256]::Create();$actual=[Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant()}finally{$stream.Dispose();$sha.Dispose()}
    $baseline=(Get-FileHash -LiteralPath (Join-Path target/replan-stage-a-frozen $entry.Name) -Algorithm SHA256).Hash.ToLowerInvariant()
    if($actual -ne $entry.Value -or $actual -ne $baseline){$drift+=@{group='dependencies';path=$entry.Name;expected=$entry.Value;actual=$actual;baseline=$baseline}}
  };$counts['dependencies']=$n
}finally{$zip.Dispose()}
$capture=@()
foreach($set in @('original200','development100','formal100')){
  $oldPath="reports/replan-stage-b-20261119/messages-p21-$set.json"
  $oldHash=(Get-FileHash -LiteralPath $oldPath -Algorithm SHA256).Hash.ToLowerInvariant()
  $reusedHash=(Get-FileHash -LiteralPath "$base/messages-p21-$set.json" -Algorithm SHA256).Hash.ToLowerInvariant()
  $before=Get-Content -Raw "$base/messages-p21-$set.json" | ConvertFrom-Json
  $after=Get-Content -Raw "$base/messages-B-$set.json" | ConvertFrom-Json
  $diff=@()
  if($before.items.Count -ne $after.items.Count){$diff+='COUNT'}
  for($i=0;$i -lt $before.items.Count;$i++){
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
$patchOutput=& git apply --reverse --check "$base/candidate.patch" 2>&1;$patchExit=$LASTEXITCODE
$jar=(Get-FileHash -LiteralPath target/text2sql-agent-0.1.0-SNAPSHOT.jar -Algorithm SHA256).Hash.ToLowerInvariant()
$recovery=(Get-FileHash -LiteralPath $id.savedCandidateJar -Algorithm SHA256).Hash.ToLowerInvariant()
$history=Get-Content -Raw reports/replan-stage-b-20261119/independent-review/submission-1-evidence-manifest.json | ConvertFrom-Json
$historyDrift=@()
foreach($e in $history.PSObject.Properties){$actual=(Get-FileHash -LiteralPath "reports/replan-stage-b-20261119/independent-review/submission-1/$($e.Name)" -Algorithm SHA256).Hash.ToLowerInvariant();if($actual -ne $e.Value){$historyDrift+=$e.Name}}
$result=@{counts=$counts;drift=$drift;captureComparison=$capture;finalMaven=@{suites=$suites.Count;tests=$tests;failures=$fail;errors=$err;skipped=$skip};reversePatchExit=$patchExit;reversePatchOutput=@($patchOutput|ForEach-Object{"$_"});jarSha256=$jar;recoveryJarSha256=$recovery;submission1ReviewEvidenceDrift=$historyDrift;providerCalls=0}
$result|ConvertTo-Json -Depth 25|Set-Content -Encoding utf8 "$out/evidence-verification.json"
$result|ConvertTo-Json -Depth 25
