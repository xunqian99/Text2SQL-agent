param([switch]$Preview)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$plan = Get-Content (Join-Path $PSScriptRoot 'artifact-plan.json') -Raw | ConvertFrom-Json
$protocol = Get-Content (Join-Path $PSScriptRoot 'protocol.json') -Raw | ConvertFrom-Json
function Require-Hash($Path, $Expected) {
    if (-not (Test-Path -LiteralPath $Path)) { throw 'A frozen runtime input is missing; do not substitute it.' }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
        throw 'A frozen runtime input changed; do not start the acceptance app.'
    }
}
Require-Hash (Join-Path $PSScriptRoot 'protocol.json') $plan.protocolSha256
Require-Hash $PSCommandPath $plan.launcherSha256
Require-Hash $plan.executableJar $plan.executableJarSha256
Require-Hash (Join-Path $projectRoot 'src/main/resources/application-local.yml') $protocol.localConfigSha256
Push-Location $projectRoot
try {
    foreach ($property in $protocol.sourceObjects.PSObject.Properties) {
        $actual = (git rev-parse "HEAD:$($property.Name)").Trim()
        if ($LASTEXITCODE -ne 0 -or $actual -ne $property.Value) { throw 'Frozen source identity changed.' }
    }
    $pending = @(git status --porcelain -- src pom.xml data/eval scripts/start_local.ps1)
    if ($LASTEXITCODE -ne 0 -or $pending.Count -ne 0) { throw 'Protected source must be committed before acceptance.' }
    $applicationArgs = @('-jar', $plan.executableJar, '--spring.profiles.active=local,demo',
        "--spring.config.additional-location=$($plan.configDirectoryUri)",
        '--server.address=127.0.0.1', "--server.port=$($plan.port)", '--agent.eval.enabled=false',
        "--agent.observability.dir=$($plan.callLogDirectory)")
    if ($Preview) {
        Write-Output ($applicationArgs | ConvertTo-Json)
    } else {
        if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
            $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY', 'User')
        }
        if ([string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) { throw 'Model key is required in the child environment.' }
        # Windows PowerShell 5.1 must not promote a native stderr warning to a terminating error.
        $ErrorActionPreference = 'Continue'
        & java @applicationArgs
        $applicationExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($applicationExit -ne 0) { throw "Acceptance app exited with code $applicationExit." }
    }
} finally { $ErrorActionPreference = 'Stop'; Pop-Location }
