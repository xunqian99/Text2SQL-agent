param(
    [string]$SourceRef = '9d6285a419dbf78f19d4d288625c1438a6fad9e9',
    [ValidatePattern('^[a-z0-9-]+$')][string]$RunId = 'text2sql-clean-cache-20261117'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidenceRoot = $PSScriptRoot
$acceptanceRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) $RunId))
$sourceRoot = Join-Path $acceptanceRoot 'source'
$repositoryRoot = Join-Path $acceptanceRoot 'maven-repository'
$settings = Join-Path $PSScriptRoot 'settings.xml'
if (Test-Path -LiteralPath $acceptanceRoot) { throw 'Never reuse a cold-build directory or cache.' }
if (Test-Path -LiteralPath (Join-Path $evidenceRoot 'protocol.json')) { throw 'Never overwrite a cold-build archive.' }

function Write-JsonNew($Name, $Value) {
    $path = Join-Path $evidenceRoot $Name
    if (Test-Path -LiteralPath $path) { throw "Evidence already exists: $Name" }
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $path -Encoding utf8
}

# This script runs in a dedicated shell. Do not alter User/Machine environment storage.
$clearedNames = @(Get-ChildItem Env: | Where-Object {
    $_.Name -match '^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)' -or
    $_.Name -in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')
} | Select-Object -ExpandProperty Name)
foreach ($name in $clearedNames) { Remove-Item -LiteralPath "Env:$name" }

New-Item -ItemType Directory -Path $sourceRoot, $repositoryRoot | Out-Null
$sourceArchive = Join-Path $acceptanceRoot 'source.zip'
Push-Location $projectRoot
try {
    $commit = (git rev-parse "$SourceRef^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve source commit.' }
    $objects = [ordered]@{}
    foreach ($path in @('pom.xml', 'src', 'data', 'scripts')) {
        $objects[$path] = (git rev-parse "${commit}:$path").Trim()
        if ($LASTEXITCODE -ne 0) { throw "Cannot resolve source object: $path" }
    }
    git archive --format=zip "--output=$sourceArchive" $commit pom.xml src data scripts
    if ($LASTEXITCODE -ne 0) { throw 'Cannot export committed source.' }
} finally { Pop-Location }
Expand-Archive -LiteralPath $sourceArchive -DestinationPath $sourceRoot
if (Test-Path -LiteralPath (Join-Path $sourceRoot 'src/main/resources/application-local.yml')) {
    throw 'Private local configuration must not be exported.'
}
$initialCacheFiles = @(Get-ChildItem -LiteralPath $repositoryRoot -File -Recurse).Count
if ($initialCacheFiles -ne 0) { throw 'Cold Maven cache was not empty.' }
$mavenArgs = @('-B', '-ntp', '-C', '-s', $settings, '-gs', $settings,
    "-Dmaven.repo.local=$repositoryRoot", 'clean', 'verify')
Write-JsonNew 'protocol.json' ([ordered]@{
    format='empty-maven-cache-build-v1'; frozenAt=[DateTimeOffset]::UtcNow.ToString('o')
    sourceCommit=$commit; sourceObjects=$objects; exportedPaths=@('pom.xml', 'src', 'data', 'scripts')
    sourceArchiveSha256=(Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    runnerSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    settingsSha256=(Get-FileHash -LiteralPath $settings -Algorithm SHA256).Hash.ToLowerInvariant()
    sourceDirectory=$sourceRoot; repositoryDirectory=$repositoryRoot
    initialCacheFileCount=$initialCacheFiles; privateLocalConfigPresent=$false
    clearedProcessEnvironmentNames=$clearedNames; credentialValuesRecorded=$false
    globalSettingsOverridden=$true; userSettingsOverridden=$true
    mirror='https://maven.aliyun.com/repository/public'; strictChecksums=$true
    mavenCommand=@('mvn') + $mavenArgs
    scope='same Windows host and existing PostgreSQL; fresh source export and empty Maven cache; not a new OS'
})
& mvn -version *> (Join-Path $evidenceRoot 'toolchain.log')
if ($LASTEXITCODE -ne 0) { throw 'Maven toolchain is unavailable.' }
$timer = [Diagnostics.Stopwatch]::StartNew()
$startedAt = [DateTimeOffset]::UtcNow.ToString('o')
Push-Location $sourceRoot
try {
    & mvn @mavenArgs *> (Join-Path $evidenceRoot 'maven-cold-build.log')
    $buildExit = $LASTEXITCODE
} finally { Pop-Location; $timer.Stop() }

$testTotal = 0; $failureTotal = 0; $errorTotal = 0; $skipTotal = 0
$xmlFiles = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'target/surefire-reports') -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
$archiveTests = Join-Path $evidenceRoot 'surefire'
New-Item -ItemType Directory -Path $archiveTests | Out-Null
foreach ($file in $xmlFiles) {
    [xml]$suite = Get-Content -LiteralPath $file.FullName -Raw
    $testTotal += [int]$suite.testsuite.tests
    $failureTotal += [int]$suite.testsuite.failures
    $errorTotal += [int]$suite.testsuite.errors
    $skipTotal += [int]$suite.testsuite.skipped
    Copy-Item -LiteralPath $file.FullName -Destination $archiveTests
}
$jar = Join-Path $sourceRoot 'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
$jarHash = if (Test-Path -LiteralPath $jar) { (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
$cacheFiles = @(Get-ChildItem -LiteralPath $repositoryRoot -File -Recurse)
Write-JsonNew 'result.json' ([ordered]@{
    startedAt=$startedAt; finishedAt=[DateTimeOffset]::UtcNow.ToString('o'); wallMs=$timer.ElapsedMilliseconds
    mavenExitCode=$buildExit; suites=$xmlFiles.Count; tests=$testTotal; failures=$failureTotal; errors=$errorTotal; skipped=$skipTotal
    initialCacheFileCount=$initialCacheFiles; finalCacheFileCount=$cacheFiles.Count
    jarCount=@($cacheFiles | Where-Object Extension -eq '.jar').Count
    pomCount=@($cacheFiles | Where-Object Extension -eq '.pom').Count
    cacheBytes=($cacheFiles | Measure-Object Length -Sum).Sum; executableJar=$jar; executableJarSha256=$jarHash
    externalModelCalls=0; externalModelCostYuan=0; sourceUnmodified=$true
})
if ($buildExit -ne 0) { throw "Cold build failed with exit $buildExit; preserve all evidence and this cache." }
if ($testTotal -ne 710 -or $failureTotal -ne 0 -or $errorTotal -ne 0 -or $skipTotal -ne 0 -or -not $jarHash) {
    throw 'Cold build did not satisfy the frozen test/jar requirements.'
}
Write-Output "Cold build passed: $testTotal tests; initial cache=0 files; source=$commit; model calls=0."
