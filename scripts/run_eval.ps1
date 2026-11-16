param(
  [ValidateSet("eval", "dev", "all")][string]$Split = "eval",
  [int]$Limit = 0,
  [switch]$DryRun,
  [switch]$SelfCorrection,
  [switch]$ResultChecker,
  [switch]$DisableClarification,
  [switch]$DisableRequestBudget,
  [ValidateRange(1, 120000)][int]$RequestTimeoutMs = 15000,
  [ValidateRange(1, 16)][int]$RequestMaxModelCalls = 2,
  [ValidateRange(0, 120000)][int]$RetryReserveMs = 2000,
  [ValidateRange(1, 2)][int]$MaxAttempts = 2,
  [ValidateRange(1, 65535)][int]$Port = 18080,
  [ValidateRange(1, 131072)][int]$MaxTokens = 8192,
  [ValidateSet('provider-default', 'none', 'low', 'high', 'max')][string]$ReasoningEffort = 'provider-default',
  [switch]$DisableExplicitReasoning,
  [string]$OutputDir = 'reports',
  [string]$DatasetDir = 'data/eval',
  [string]$SuiteManifest = '',
  [switch]$Preview
)

$ErrorActionPreference = "Stop"
if (-not $DisableRequestBudget -and $RetryReserveMs -gt $RequestTimeoutMs) {
  throw 'RetryReserveMs must not exceed RequestTimeoutMs.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'src/main/resources/application-local.yml'))) {
  throw "Missing local profile. Copy src/main/resources/application-local.example.yml to application-local.yml first."
}
if ($ResultChecker -and -not $SelfCorrection) {
  throw "-ResultChecker requires -SelfCorrection: result checking triggers the production retry."
}
if (-not $Preview -and -not $DryRun -and [string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
  $env:AGENT_LLM_API_KEY = [Environment]::GetEnvironmentVariable('AGENT_LLM_API_KEY', 'User')
}
if (-not $Preview -and -not $DryRun -and [string]::IsNullOrWhiteSpace($env:AGENT_LLM_API_KEY)) {
  Write-Error "AGENT_LLM_API_KEY is required for a real eval. Use -DryRun for the offline self-check."
  exit 1
}

$attempts = if ($SelfCorrection) { $MaxAttempts } else { 1 }
foreach ($pathValue in @($DatasetDir, $SuiteManifest, $OutputDir)) {
  if ($pathValue -match '[\s"'']') { throw 'Evaluation paths must not contain whitespace or quotes.' }
}
$mavenArgs = @(
  '--spring.profiles.active=local', "--server.port=$Port",
  '--agent.eval.enabled=true', "--agent.eval.split=$Split", "--agent.eval.limit=$Limit",
  "--agent.eval.dir=$DatasetDir", "--agent.eval.suite-manifest=$SuiteManifest",
  "--agent.eval.dry-run=$($DryRun.IsPresent.ToString().ToLowerInvariant())",
  "--agent.eval.out-dir=$OutputDir",
  '--agent.eval.exit-after-run=true', '--agent.eval.repeat=1',
  '--agent.eval.self-correction-enabled=false', '--agent.eval.self-correction-max-attempts=1',
  '--agent.llm.model=deepseek-flash', '--agent.llm.temperature=0.0', "--agent.llm.max-tokens=$MaxTokens",
  "--agent.llm.reasoning-effort=$ReasoningEffort",
  "--agent.prompt.explicit-reasoning-enabled=$((!$DisableExplicitReasoning.IsPresent).ToString().ToLowerInvariant())",
  '--agent.retrieval.enabled=true', '--agent.retrieval.top-k=8',
  '--agent.retrieval.column-pruning-enabled=true', '--agent.retrieval.join-hints-enabled=true',
  '--agent.retrieval.join-list-enabled=false', '--agent.retrieval.join-plan-enabled=true',
  '--agent.semantic.enabled=true', '--agent.fewshot.enabled=true', '--agent.fewshot.max-examples=2',
  "--agent.clarification.enabled=$((!$DisableClarification.IsPresent).ToString().ToLowerInvariant())",
  "--agent.self-correction.enabled=$($SelfCorrection.IsPresent.ToString().ToLowerInvariant())",
  "--agent.self-correction.max-attempts=$attempts",
  "--agent.result-checker.enabled=$($ResultChecker.IsPresent.ToString().ToLowerInvariant())",
  "--agent.request-budget.enabled=$((!$DisableRequestBudget.IsPresent).ToString().ToLowerInvariant())",
  "--agent.request-budget.timeout-ms=$RequestTimeoutMs",
  "--agent.request-budget.max-model-calls=$RequestMaxModelCalls",
  "--agent.request-budget.min-retry-remaining-ms=$RetryReserveMs",
  '--agent.cache.enabled=false', '--agent.routing.enabled=false',
  '--agent.tool-use.enabled=false', '--agent.conversation.enabled=false'
  '--agent.observability.enabled=true', '--agent.observability.include-content=false',
  "--agent.observability.dir=$OutputDir/llm-calls"
) -join ' '
if ($Preview) {
  Write-Output $mavenArgs
  exit 0
}

Write-Host "Running eval: model=deepseek-flash, maxTokens=$MaxTokens, reasoningEffort=$ReasoningEffort, split=$Split, selfCorrection=$SelfCorrection, resultChecker=$ResultChecker"
Push-Location $projectRoot
try {
  mvn spring-boot:run "-Dspring-boot.run.arguments=$mavenArgs"
  if ($LASTEXITCODE -ne 0) { throw "Maven eval failed (exit $LASTEXITCODE)." }
} finally {
  Pop-Location
}
