<#
.SYNOPSIS
    把已有评估报告的 token 数换算成金额。

.DESCRIPTION
    为什么需要它：报告里的金额是用「跑评估那一刻配置的单价」算出来的。
    项目里单价长期是 0，所以所有历史报告的金额都是 0——那是「没算」，不是「免费」。

    单价填好之后没必要重跑评估：报告里存着每条样本的 token 数，拿现成的 token
    × 新单价就能换算。这也是把 token 与金额分开记录的好处。

.PARAMETER InputPricePer1k
    输入单价，元 / 千 token。

.PARAMETER OutputPricePer1k
    输出单价，元 / 千 token。

.PARAMETER Reports
    要处理的报告，默认 reports 目录下所有 eval-*.json（排除 dryrun）。

.EXAMPLE
    powershell -File scripts/recompute_cost.ps1 -InputPricePer1k 0.5 -OutputPricePer1k 8

.NOTES
    dry-run 报告的 token 恒为 0（没有调用模型），会被自动跳过。
#>
param(
    [Parameter(Mandatory = $true)][double]$InputPricePer1k,
    [Parameter(Mandatory = $true)][double]$OutputPricePer1k,
    [string[]]$Reports
)

$ErrorActionPreference = 'Stop'

# 用 powershell -File 调用时，命令行里的 "a.json,b.json" 会被当成**一个**字符串传进来，
# 而不是数组。自己拆一次，让两种调用方式（-File 与直接调用）行为一致。
if ($Reports -and $Reports.Count -eq 1 -and $Reports[0] -like '*,*') {
    $Reports = $Reports[0] -split ','
}

if (-not $Reports -or $Reports.Count -eq 0) {
    $Reports = Get-ChildItem 'reports/eval-*.json' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*dryrun*' } |
        Sort-Object Name |
        ForEach-Object { $_.FullName }
}

if (-not $Reports) {
    Write-Host '没有找到可处理的报告。' -ForegroundColor Yellow
    exit 0
}

Write-Host ("单价：输入 {0} 元/千token，输出 {1} 元/千token" -f $InputPricePer1k, $OutputPricePer1k) -ForegroundColor Cyan

$rows = foreach ($file in $Reports) {
    # 必须显式 -Encoding UTF8：Windows PowerShell 5.1 的 Get-Content 默认按本地代码页读，
    # 报告里有中文（评估问题），按 ANSI 读会破坏 JSON 结构，报「':' or '}' expected」。
    $r = Get-Content $file -Raw -Encoding UTF8 | ConvertFrom-Json
    $o = $r.overall
    $totalPrompt = [double]$o.avgPromptTokens * [int]$o.total
    if ($totalPrompt -le 0) { continue }

    [pscustomobject]@{
        报告       = Split-Path $file -Leaf
        模型       = $r.meta.model
        样本数     = $o.total
        准确率     = '{0:P1}' -f $o.executionAccuracy
        输入token  = [int]$totalPrompt
        输入成本元 = [math]::Round($totalPrompt / 1000 * $InputPricePer1k, 4)
    }
}

$rows | Format-Table -AutoSize

$sum = ($rows | Measure-Object -Property 输入成本元 -Sum).Sum
Write-Host ('累计输入成本：{0} 元' -f [math]::Round($sum, 4)) -ForegroundColor Green
Write-Host ''
Write-Host '注意：这里只换算输入部分。' -ForegroundColor Yellow
Write-Host '评估报告没有单独留存输出 token，所以这不是完整成本，是**成本下界**。' -ForegroundColor Yellow
Write-Host '要拿到完整成本，用 reports/llm-calls/*.jsonl——阶段 6 新增的逐条记录里' -ForegroundColor Yellow
Write-Host '同时有 promptTokens 和 completionTokens。' -ForegroundColor Yellow
