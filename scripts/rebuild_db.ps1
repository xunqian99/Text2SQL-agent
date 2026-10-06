# 一键重建数据库：建表 -> 导入 Olist -> 建扩展表 -> 生成扩展数据
#
# 为什么要有这个脚本：
#   建表脚本（01/03）和插数脚本（02/04）是分开的，单独跑 03 会清空扩展表数据。
#   把它们固定成一条命令，就不会再出现"只跑了建表"这种事。
#
# 用法：powershell -File scripts/rebuild_db.ps1

$ErrorActionPreference = 'Stop'
$container = 'text2sql-postgres'
$db        = 'olist'
$user      = 'text2sql'

Write-Host '[1/7] 检查容器状态...' -ForegroundColor Cyan
$running = docker inspect -f '{{.State.Running}}' $container 2>$null
if ($running -ne 'true') {
    Write-Host '  容器未运行，尝试启动...' -ForegroundColor Yellow
    docker compose -f "$PSScriptRoot\..\docker\docker-compose.yml" up -d
    Start-Sleep -Seconds 10
}

Write-Host '[2/7] 建表（Olist 原始 9 张）...' -ForegroundColor Cyan
docker exec $container psql -U $user -d $db -q -v ON_ERROR_STOP=1 -f /data/schema/01_olist_schema.sql
if ($LASTEXITCODE -ne 0) { throw '建表失败' }

Write-Host '[3/7] 导入 Olist 真实数据（约 150 万行，需要 1-2 分钟）...' -ForegroundColor Cyan
docker exec $container psql -U $user -d $db -q -v ON_ERROR_STOP=1 -f /data/schema/02_load_olist.sql
if ($LASTEXITCODE -ne 0) { throw '导入失败' }

Write-Host '[4/7] 建扩展表（补到 37 张）...' -ForegroundColor Cyan
docker exec $container psql -U $user -d $db -q -v ON_ERROR_STOP=1 -f /data/schema/03_extend_schema.sql
if ($LASTEXITCODE -ne 0) { throw '建扩展表失败' }

Write-Host '[5/7] 生成扩展数据...' -ForegroundColor Cyan
docker exec $container psql -U $user -d $db -q -v ON_ERROR_STOP=1 -f /data/schema/04_seed_extend.sql
if ($LASTEXITCODE -ne 0) { throw '生成扩展数据失败' }

# 阶段 5：创建只读账号。必须放在最后——要在所有表建好之后才能
# GRANT SELECT ON ALL TABLES，否则只对新表生效。
#
# 漏跑这一步的后果很隐蔽：数据库本身完全正常，但应用一起动就报
# 「password authentication failed for user "text2sql_ro"」，
# 看起来像配置写错，实际是这个账号压根没建。
Write-Host '[6/7] 创建只读账号 text2sql_ro...' -ForegroundColor Cyan
docker exec $container psql -U $user -d $db -q -v ON_ERROR_STOP=1 -f /data/schema/05_readonly_role.sql
if ($LASTEXITCODE -ne 0) { throw '创建只读账号失败' }

# 阶段 6：持久化表与专用写账号。
# 和上一步一样必须放最后——GRANT 只能对已存在的表生效。
# 漏跑的后果同样是静默的：缓存不会落库、调用日志不会入库，但问数功能完全正常，
# 只有主动去查表才会发现「怎么一条都没有」。
Write-Host '[7/7] 创建持久化表与写账号 text2sql_rw...' -ForegroundColor Cyan
docker exec $container psql -U $user -d $db -q -v ON_ERROR_STOP=1 -f /data/schema/06_persistence.sql
if ($LASTEXITCODE -ne 0) { throw '创建持久化表失败' }

Write-Host '完成。运行 scripts/check_counts.sql 可核对行数。' -ForegroundColor Green
