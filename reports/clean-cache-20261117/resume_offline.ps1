$ErrorActionPreference = 'Stop'
$protocol = Get-Content (Join-Path $PSScriptRoot 'protocol.json') -Raw | ConvertFrom-Json
$sourceRoot = $protocol.sourceDirectory
$repositoryRoot = $protocol.repositoryDirectory
$settings = Join-Path $PSScriptRoot 'settings.xml'
if (Test-Path (Join-Path $PSScriptRoot 'result.json')) { throw 'Never overwrite a verification attempt.' }
$live = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -and $_.CommandLine.Contains($repositoryRoot)
})
if ($live.Count -ne 0) { throw 'Original Maven process is still live; do not start a duplicate.' }

$initialTests = Join-Path $PSScriptRoot 'initial-surefire'
New-Item -ItemType Directory -Path $initialTests | Out-Null
Copy-Item -Path (Join-Path $sourceRoot 'target/surefire-reports/TEST-*.xml') -Destination $initialTests
$jar = Join-Path $sourceRoot 'target/text2sql-agent-0.1.0-SNAPSHOT.jar'
[ordered]@{
    observedAt=[DateTimeOffset]::UtcNow.ToString('o'); originalWrapperExitCode=1; originalMavenExitCode=$null
    liveMavenProcesses=0; originalExecutableJarSha256=(Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    reason='Windows PowerShell 5.1 treated redirected native Mockito stderr warning as a terminating NativeCommandError. Maven exit code was not captured. Original XML and jar retained; no model calls.'
    action='Use the same partially populated cache with Maven offline verify. This is not a second empty-cache attempt.'
} | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'initial-runner-failure.json') -Encoding utf8

Get-ChildItem Env: | Where-Object {
    $_.Name -match '^(AGENT_|SPRING_|TEXT2SQL_TEST_DB_)' -or
    $_.Name -in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')
} | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }

$mavenArgs = @('-o', '-B', '-ntp', '-C', '-s', $settings, '-gs', $settings,
    "-Dmaven.repo.local=$repositoryRoot", 'verify')
$timer = [Diagnostics.Stopwatch]::StartNew()
$startedAt = [DateTimeOffset]::UtcNow.ToString('o')
Push-Location $sourceRoot
try {
    # Native stderr can contain warnings. Only the native exit code decides success.
    $ErrorActionPreference = 'Continue'
    & mvn @mavenArgs *> (Join-Path $PSScriptRoot 'maven-offline-verify.log')
    $buildExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
    $timer.Stop()
}
$totals = [ordered]@{ suites=0; tests=0; failures=0; errors=0; skipped=0 }
$archiveTests = Join-Path $PSScriptRoot 'surefire'
New-Item -ItemType Directory -Path $archiveTests | Out-Null
foreach ($file in (Get-ChildItem (Join-Path $sourceRoot 'target/surefire-reports') -Filter 'TEST-*.xml' -File)) {
    [xml]$suite = Get-Content $file.FullName -Raw
    $totals.suites++
    foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $totals[$key] += [int]$suite.testsuite.$key }
    Copy-Item -LiteralPath $file.FullName -Destination $archiveTests
}
$cacheFiles = @(Get-ChildItem -LiteralPath $repositoryRoot -File -Recurse)
[ordered]@{
    phase='offline verify after original wrapper failure'; mavenCommand=@('mvn')+$mavenArgs
    startedAt=$startedAt; finishedAt=[DateTimeOffset]::UtcNow.ToString('o'); wallMs=$timer.ElapsedMilliseconds
    mavenExitCode=$buildExit; originalMavenExitCode=$null; originalWrapperExitCode=1; offline=$true
    suites=$totals.suites; tests=$totals.tests; failures=$totals.failures; errors=$totals.errors; skipped=$totals.skipped
    initialCacheFileCount=$protocol.initialCacheFileCount; finalCacheFileCount=$cacheFiles.Count
    jarCount=@($cacheFiles | Where-Object Extension -eq '.jar').Count
    pomCount=@($cacheFiles | Where-Object Extension -eq '.pom').Count
    cacheBytes=($cacheFiles | Measure-Object Length -Sum).Sum
    executableJar=$jar; executableJarSha256=(Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    externalModelCalls=0; externalModelCostYuan=0
} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $PSScriptRoot 'result.json') -Encoding utf8
if ($buildExit -ne 0 -or $totals.tests -ne 710 -or $totals.failures -ne 0 -or $totals.errors -ne 0 -or $totals.skipped -ne 0) {
    throw 'Offline verification failed; preserve the same cache and all evidence.'
}
Write-Output "Offline verify passed: $($totals.tests) tests; initial cache=0; no model calls. Original wrapper failure retained."
