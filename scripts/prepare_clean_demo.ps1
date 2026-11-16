param(
  [ValidatePattern('^[a-z0-9][a-z0-9-]{0,30}$')][string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
  [ValidateRange(1024, 65535)][int]$DatabasePort = 15432,
  [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$cleanContainer = "text2sql-clean-$RunId"
$cleanVolume = "$cleanContainer-data"
if (-not $OutputDir) { $OutputDir = "reports/clean-demo-$RunId" }
$outputPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDir))
if (Test-Path -LiteralPath $outputPath) { throw 'Evidence directory exists; use a new run ID/output directory.' }
if ($cleanContainer -eq 'text2sql-postgres' -or $cleanVolume -eq 'text2sql-pgdata') { throw 'Protected original database.' }
$existingContainer = docker container ls -a --filter "name=^/$cleanContainer`$" --format '{{.Names}}'
if ($LASTEXITCODE -ne 0 -or $existingContainer) { throw 'Container inventory failed or target already exists.' }
$existingVolume = docker volume ls --filter "name=^$cleanVolume`$" --format '{{.Name}}'
if ($LASTEXITCODE -ne 0 -or $existingVolume) { throw 'Volume inventory failed or target already exists.' }
if (@(Get-NetTCPConnection -State Listen -LocalPort $DatabasePort -ErrorAction SilentlyContinue).Count) {
  throw 'Selected database port is already in use.'
}
$image = 'pgvector/pgvector:pg16'
$imageId = docker image inspect --format '{{.Id}}' $image
if ($LASTEXITCODE -ne 0) { throw 'Required PostgreSQL image is not available locally.' }
$csvPath = Join-Path $projectRoot 'data/raw/olist'
$schemaPath = Join-Path $projectRoot 'data/schema'
$sqlFiles = @('01_olist_schema.sql', '02_load_olist.sql', '03_extend_schema.sql',
  '04_seed_extend.sql', '05_readonly_role.sql', '06_persistence.sql')
$csvFiles = @('olist_customers_dataset.csv', 'olist_sellers_dataset.csv',
  'product_category_name_translation.csv', 'olist_products_dataset.csv', 'olist_orders_dataset.csv',
  'olist_order_items_dataset.csv', 'olist_order_payments_dataset.csv', 'olist_order_reviews_dataset.csv',
  'olist_geolocation_dataset.csv')
$inputs = @()
foreach ($file in @($csvFiles | ForEach-Object { Join-Path $csvPath $_ }) + @($sqlFiles | ForEach-Object { Join-Path $schemaPath $_ })) {
  if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing required input: $file" }
  $inputs += @{ path = $file.Substring($projectRoot.Length + 1).Replace('\', '/');
    bytes = (Get-Item -LiteralPath $file).Length; sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() }
}
New-Item -ItemType Directory -Path $outputPath | Out-Null
@{ runId = $RunId; container = $cleanContainer; volume = $cleanVolume; image = $image; imageId = $imageId;
  databasePort = $DatabasePort; address = '127.0.0.1'; inputs = $inputs;
  sourceCommit = (git -C $projectRoot rev-parse HEAD); startedUtc = [DateTime]::UtcNow.ToString('o') } |
  ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputPath 'protocol.json') -Encoding UTF8

function Invoke-CleanPsql([string]$Role, [string]$Sql, [string]$EvidenceFile, [switch]$ExpectDenied) {
  $ErrorActionPreference = 'Continue' # Windows PowerShell 5.1 wraps expected native stderr as ErrorRecord.
  $result = docker exec $cleanContainer psql -X -U $Role -d olist -v ON_ERROR_STOP=1 -At -c $Sql 2>&1
  $resultCode = $LASTEXITCODE
  $ErrorActionPreference = 'Stop'
  $result | Set-Content -LiteralPath (Join-Path $outputPath $EvidenceFile) -Encoding UTF8
  if ($ExpectDenied) {
    if ($resultCode -eq 0 -or ($result -join "`n") -notmatch 'permission denied') { throw "Expected permission denial: $EvidenceFile" }
  } elseif ($resultCode -ne 0) { throw "Database verification failed: $EvidenceFile" }
  return ($result -join "`n")
}

# Create only new resources. Existing databases/volumes are never stopped, rebuilt or removed.
docker volume create --label 'text2sql.acceptance=clean-demo' $cleanVolume | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated volume.' }
$previousPassword = [Environment]::GetEnvironmentVariable('POSTGRES_PASSWORD', 'Process')
try {
  $env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
  docker run --detach --name $cleanContainer --label 'text2sql.acceptance=clean-demo' `
    -e POSTGRES_DB=olist -e POSTGRES_USER=text2sql -e POSTGRES_PASSWORD `
    -e 'POSTGRES_INITDB_ARGS=--encoding=UTF8 --locale=C' -e TZ=Asia/Shanghai `
    -p "127.0.0.1:${DatabasePort}:5432" `
    --mount "type=volume,source=$cleanVolume,target=/var/lib/postgresql/data" `
    --mount "type=bind,source=$csvPath,target=/data/olist,readonly" `
    --mount "type=bind,source=$schemaPath,target=/data/schema,readonly" $imageId |
      Set-Content -LiteralPath (Join-Path $outputPath 'container-id.txt') -Encoding UTF8
  if ($LASTEXITCODE -ne 0) { throw 'Cannot start isolated database.' }
} finally {
  [Environment]::SetEnvironmentVariable('POSTGRES_PASSWORD', $previousPassword, 'Process')
}
$ready = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
  docker exec $cleanContainer pg_isready -U text2sql -d olist *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true; break }
  Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw 'Isolated PostgreSQL did not become ready.' }
$stages = @()
foreach ($sqlFile in $sqlFiles) {
  Write-Host "Initializing isolated database: $sqlFile"
  $timer = [Diagnostics.Stopwatch]::StartNew()
  $ErrorActionPreference = 'Continue'
  docker exec $cleanContainer psql -X -U text2sql -d olist -v ON_ERROR_STOP=1 -f "/data/schema/$sqlFile" *> (Join-Path $outputPath "$sqlFile.log")
  $ErrorActionPreference = 'Stop'
  if ($LASTEXITCODE -ne 0) { throw "Initialization failed: $sqlFile (new container preserved for diagnosis)." }
  $stages += @{ file = $sqlFile; elapsedMs = $timer.ElapsedMilliseconds; exitCode = 0 }
}
$ErrorActionPreference = 'Continue'
docker exec $cleanContainer psql -X -U text2sql -d olist -v ON_ERROR_STOP=1 -f /data/schema/06_persistence.sql *> (Join-Path $outputPath 'persistence-idempotency.log')
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'Persistence migration idempotency failed.' }
$permissionSql = @'
SELECT json_build_object(
  'businessTableCount', (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('semantic_cache','llm_call_log','conversation_session','conversation_turn')),
  'roWritableBusinessTables', (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('semantic_cache','llm_call_log','conversation_session','conversation_turn') AND (has_table_privilege('text2sql_ro',schemaname||'.'||tablename,'INSERT') OR has_table_privilege('text2sql_ro',schemaname||'.'||tablename,'UPDATE') OR has_table_privilege('text2sql_ro',schemaname||'.'||tablename,'DELETE') OR has_table_privilege('text2sql_ro',schemaname||'.'||tablename,'TRUNCATE'))),
  'rwWritableBusinessTables', (SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('semantic_cache','llm_call_log','conversation_session','conversation_turn') AND (has_table_privilege('text2sql_rw',schemaname||'.'||tablename,'INSERT') OR has_table_privilege('text2sql_rw',schemaname||'.'||tablename,'UPDATE') OR has_table_privilege('text2sql_rw',schemaname||'.'||tablename,'DELETE') OR has_table_privilege('text2sql_rw',schemaname||'.'||tablename,'TRUNCATE'))),
  'roCreateSchema',has_schema_privilege('text2sql_ro','public','CREATE'),
  'rwCreateSchema',has_schema_privilege('text2sql_rw','public','CREATE'),
  'rwCanAppendLog',has_table_privilege('text2sql_rw','llm_call_log','INSERT'),
  'rwCanUpdateLog',has_table_privilege('text2sql_rw','llm_call_log','UPDATE'),
  'rwCanDeleteLog',has_table_privilege('text2sql_rw','llm_call_log','DELETE'));
'@
$permissions = (Invoke-CleanPsql text2sql $permissionSql 'permissions.json') | ConvertFrom-Json
if ($permissions.businessTableCount -ne 37 -or $permissions.roWritableBusinessTables -ne 0 -or
    $permissions.rwWritableBusinessTables -ne 0 -or $permissions.roCreateSchema -or $permissions.rwCreateSchema -or
    -not $permissions.rwCanAppendLog -or $permissions.rwCanUpdateLog -or $permissions.rwCanDeleteLog) { throw 'Unexpected privileges.' }
foreach ($role in @('text2sql_ro', 'text2sql_rw')) {
  Invoke-CleanPsql $role 'UPDATE orders SET order_status=order_status WHERE false' "$role-update-denied.txt" -ExpectDenied | Out-Null
  Invoke-CleanPsql $role 'CREATE TABLE public.acceptance_forbidden (id int)' "$role-create-denied.txt" -ExpectDenied | Out-Null
}
$orderCount = Invoke-CleanPsql text2sql_ro "SELECT count(*) FROM orders WHERE order_purchase_timestamp >= '2018-01-01' AND order_purchase_timestamp < '2019-01-01'" 'order-count.txt'
if ($orderCount.Trim() -ne '54011') { throw 'Unexpected base Olist snapshot.' }
$counts = @'
SELECT json_object_agg(table_name, row_count) FROM (
  SELECT tablename AS table_name, (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I',schemaname,tablename), false,true,'')))[1]::text::bigint AS row_count
  FROM pg_tables WHERE schemaname='public' ORDER BY tablename
) t;
'@
Invoke-CleanPsql text2sql $counts 'table-counts.json' | Out-Null
@{ databasePrepared = $true; stages = $stages; businessTableCount = 37; permissionsPassed = $true;
  orderCount2018 = 54011; originalDatabaseModified = $false;
  limitations = 'Fresh database on the same host/image; extension random seed is not a guarantee of byte-identical rows. HTTP/Gold acceptance is a separate step.' } |
  ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputPath 'database-summary.json') -Encoding UTF8
Write-Host "Prepared $cleanContainer on 127.0.0.1:$DatabasePort; existing database untouched."
Write-Host "Evidence: $outputPath. Container and volume are preserved; no automatic deletion."
