param(
  [ValidateSet("eval", "dev", "all")][string]$Split = "eval",
  [int]$Limit = 0,
  [switch]$DryRun,
  [int]$Port = 18080
)

if (-not $DryRun -and -not $env:AGENT_LLM_API_KEY) {
  Write-Error "AGENT_LLM_API_KEY is required for a real eval. Use -DryRun for the offline self-check."
  exit 1
}

$mavenArgs = "--spring.profiles.active=local --server.port=$Port --agent.eval.enabled=true --agent.eval.split=$Split --agent.eval.limit=$Limit"
if ($DryRun) { $mavenArgs += " --agent.eval.dry-run=true" }

Write-Host "Running Maven eval (split=$Split, limit=$Limit, dryRun=$DryRun)..."
mvn spring-boot:run "-Dspring-boot.run.arguments=$mavenArgs"
