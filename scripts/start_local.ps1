param(
  [ValidateSet('demo', 'baseline')][string]$Mode = 'demo',
  [ValidateRange(1, 65535)][int]$Port = 8080,
  [string]$CallLogDir = '',
  [switch]$Preview
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localConfig = Join-Path $projectRoot 'src/main/resources/application-local.yml'
if (-not (Test-Path -LiteralPath $localConfig)) {
  throw 'Copy src/main/resources/application-local.example.yml to application-local.yml first; keep credentials in environment variables.'
}
if (-not $Preview -and [string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
  $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY', 'User')
}

# Secret stays in the child environment, never in Maven arguments or output.
$applicationArgs = "--spring.profiles.active=local,$Mode --server.address=127.0.0.1 --server.port=$Port --agent.eval.enabled=false"
if ($CallLogDir) {
  if ($CallLogDir -match '[\s"'']') { throw 'CallLogDir must not contain whitespace or quotes.' }
  $applicationArgs += " --agent.observability.dir=$CallLogDir"
}
if ($Preview) {
  Write-Output $applicationArgs
  exit 0
}
Write-Host "Starting $Mode at http://127.0.0.1:$Port (local single-user only)."
Write-Host 'Check /api/health for the effective capabilities. No model call is made by health.'
Push-Location $projectRoot
try {
  mvn spring-boot:run "-Dspring-boot.run.arguments=$applicationArgs"
  if ($LASTEXITCODE -ne 0) { throw "Application exited with code $LASTEXITCODE." }
} finally {
  Pop-Location
}
