param(
  [ValidateSet("eval", "dev", "all")][string]$Split = "eval",
  [int]$Limit = 0,
  [switch]$DryRun,
  [int]$Port = 18080
)

if (-not $DryRun -and -not $env:AGENT_LLM_API_KEY) {
  Write-Error "真实评估需要 AGENT_LLM_API_KEY（或 application-local.yml 的 api-key）。dry-run 可加 -DryRun。"
  exit 1
}

$arguments = "--spring.profiles.active=local --server.port=$Port --agent.eval.enabled=true --agent.eval.split=$Split --agent.eval.limit=$Limit"
if ($DryRun) { $arguments += " --agent.eval.dry-run=true" }

Write-Host "mvn spring-boot:run `"-Dspring-boot.run.arguments=$arguments`""
mvn spring-boot:run "-Dspring-boot.run.arguments=$arguments"
