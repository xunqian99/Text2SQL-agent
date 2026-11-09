param(
  [ValidateSet("eval", "dev", "all")][string]$Split = "eval",
  [int]$Limit = 0,
  [switch]$DryRun,
  [switch]$SelfCorrection,
  [int]$MaxAttempts = 2,
  [int]$Port = 18080
)

if (-not $DryRun -and -not $env:AGENT_LLM_API_KEY) {
  Write-Error "AGENT_LLM_API_KEY is required for a real eval. Use -DryRun for the offline self-check."
  exit 1
}

$mavenArgs = "--spring.profiles.active=local --server.port=$Port --agent.eval.enabled=true --agent.eval.split=$Split --agent.eval.limit=$Limit"
if ($DryRun) { $mavenArgs += " --agent.eval.dry-run=true" }
if ($SelfCorrection) {
  # Self-correction + heuristic result check. Compare against the baseline
  # (both switches off) to measure whether retry actually helps.
  $mavenArgs += " --agent.self-correction.enabled=true --agent.self-correction.max-attempts=$MaxAttempts"
  $mavenArgs += " --agent.result-checker.enabled=true"
}

Write-Host "Running Maven eval (split=$Split, limit=$Limit, dryRun=$DryRun, selfCorrection=$SelfCorrection)..."
mvn spring-boot:run "-Dspring-boot.run.arguments=$mavenArgs"
