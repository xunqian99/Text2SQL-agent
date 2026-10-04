# Dump every failure with its gold SQL and model SQL, so patterns can be eyeballed.
# Output is UTF-8; read it with [System.IO.File]::ReadAllText to avoid PS5 ANSI issues.

param([string]$Report = 'reports/eval-20261004-140246.json')

function Get-GoldSql {
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

$j = [System.IO.File]::ReadAllText((Resolve-Path $Report), [System.Text.Encoding]::UTF8) | ConvertFrom-Json

$out = New-Object System.Collections.Generic.List[string]
$out.Add("report: $Report")
$out.Add("failures: $($j.failures.Count)")
$out.Add("")

foreach ($f in $j.failures) {
    $g = Get-GoldSql $f.id
    if (-not $g) { $g = '(not found)' }
    $tbl = ([regex]::Matches($g, '(?i)\b(from|join)\s+([a-z_][a-z0-9_]*)')).Count
    $msg = ($f.message -replace '\s+', ' ')
    if ($msg.Length -gt 90) { $msg = $msg.Substring(0, 90) }
    $out.Add(('===== {0} | {1} | goldTables={2} | {3} =====' -f $f.id, $f.difficulty, $tbl, $f.status))
    $out.Add(('Q: {0}' -f $f.question))
    $out.Add(('G: {0}' -f (($g -replace '\s+', ' ').Trim())))
    $out.Add(('M: {0}' -f (($f.generatedSql -replace '\s+', ' ').Trim())))
    if ($msg) { $out.Add(('E: {0}' -f $msg)) }
    $out.Add('')
}

[System.IO.File]::WriteAllLines('target/failure-analysis.txt', $out, (New-Object System.Text.UTF8Encoding $false))
Write-Output "lines=$($out.Count)"
