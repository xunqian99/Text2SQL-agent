








param(
    [Parameter(Mandatory = $true)][string]$Report
)

$ErrorActionPreference = 'Stop'
$sep = [char]1

function Get-Rows {
    param([string]$Sql)
    $raw = docker exec text2sql-postgres psql -U text2sql -d olist -t -A -F $sep -c $Sql 2>&1
    $rows = @()
    foreach ($r in $raw) {
        $s = [string]$r
        if ($s.Trim() -eq '') { continue }
        if ($s -match '^(ERROR|WARNING|NOTICE|FATAL)') { return $null }
        $rows += , @($s -split $sep)
    }
    # 用逗号包一层，避免 PowerShell 把数组展开成多个返回值。
    return , $rows
}

function Get-Gold-Sql {
    param([string]$Id)
    foreach ($y in Get-ChildItem 'data/eval' -Filter 'T*.yaml') {
        $c = [System.IO.File]::ReadAllText($y.FullName, [System.Text.Encoding]::UTF8)
        $p = ($c -split "(?m)^(?=- id:)") |
            Where-Object { $_ -match "(?m)^- id:\s*$([regex]::Escape($Id))\s*$" } |
            Select-Object -First 1
        if (-not $p) { continue }
        $lines = $p -split "`n"
        $ing = $false
        $sql = @()
        foreach ($l in $lines) {
            if ($l -match '^\s*gold_sql:\s*\|') { $ing = $true; continue }
            if (-not $ing) { continue }
            if ($l -match '^    ') { $sql += $l.Trim() }
            elseif ($l.Trim() -eq '') { continue }
            else { break }
        }
        if ($sql.Count -gt 0) { return ($sql -join ' ') }
    }
    return $null
}

# PowerShell 5 的 Get-Content -Raw 会按 ANSI 读 UTF-8 文件，导致中文乱码后
# ConvertFrom-Json 失败。显式按 UTF-8 读。
$report = [System.IO.File]::ReadAllText((Resolve-Path $Report), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$mismatches = @()
$fake = @()
$checked = 0

foreach ($f in $report.failures) {
    # 只看「执行成功但结果不匹配」的失败。
    # 执行失败（超时、列不存在、语法错）没有结果集可比，
    # 把它们混进来会得出「结果等价」的错误结论——脚本第一版就踩了这个坑：
    # T5-014 实际是查询超时（18.7s > 10s 上限），却被当成结果不匹配分析。
    if ($f.status -ne 'SUCCESS') { continue }
    $goldSql = Get-Gold-Sql $f.id
    if (-not $goldSql) { continue }
    $grows = Get-Rows $goldSql
    $mrows = Get-Rows $f.generatedSql
    if ($null -eq $grows -or $null -eq $mrows) { continue }
    if ($grows.Count -eq 0 -or $mrows.Count -eq 0) { continue }

    $gcols = $grows[0].Count
    $mcols = $mrows[0].Count
    $checked++

    if ($gcols -ne $mcols) {
        $mismatches += [PSCustomObject]@{
            Id = $f.id; GoldCols = $gcols; ModelCols = $mcols
            GoldRows = $grows.Count; ModelRows = $mrows.Count
        }
    }

    # ?? gold ?????????????????????????
    $limit = [math]::Pow(2, $gcols)
    for ($mask = 1; $mask -lt $limit; $mask++) {
        $pick = @()
        for ($b = 0; $b -lt $gcols; $b++) {
            if ($mask -band (1 -shl $b)) { $pick += $b }
        }
        if ($pick.Count -ne $mcols) { continue }

        $proj = @()
        $usable = $true
        foreach ($row in $grows) {
            if ($row.Count -ne $gcols) { $usable = $false; break }
            $pr = @()
            foreach ($b in $pick) { $pr += $row[$b] }
            $proj += , $pr
        }
        if (-not $usable) { continue }

        # ???????????????????????????
        $ps = ($proj | ForEach-Object { $_ -join '|' } | Sort-Object) -join "`n"
        $ms = ($mrows | ForEach-Object { $_ -join '|' } | Sort-Object) -join "`n"
        if ($ps -eq $ms) {
            $fake += [PSCustomObject]@{ Id = $f.id; GoldCols = ($pick -join ',') }
            break
        }
    }
}

Write-Output "checked=$checked"
Write-Output ""
Write-Output "== column count mismatch: $($mismatches.Count) =="
$mismatches | ForEach-Object {
    "  {0,-8} gold {1}cols/{2}rows  model {3}cols/{4}rows" -f $_.Id, $_.GoldCols, $_.GoldRows, $_.ModelCols, $_.ModelRows
}
Write-Output ""
Write-Output "== projection-equivalent (FALSE FAILURES): $($fake.Count) =="
$fake | ForEach-Object { "  {0}  (gold cols {1})" -f $_.Id, $_.GoldCols }
