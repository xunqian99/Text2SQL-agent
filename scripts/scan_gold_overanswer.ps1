# Scan every eval item and report gold SQLs whose top-level SELECT has more
# columns than the question plausibly asks for.
#
# Heuristic: a gold query is "over-answering" when it returns several columns
# but the question only names one metric. This cannot be decided mechanically,
# so the script only LISTS candidates; a human decides.
#
# It exists to prove that eval-set fixes are applied by a rule, not cherry-picked
# from whichever failures happened to be convenient.

$out = New-Object System.Collections.Generic.List[string]

foreach ($y in Get-ChildItem 'data/eval' -Filter 'T*.yaml' | Sort-Object Name) {
    $c = [System.IO.File]::ReadAllText($y.FullName, [System.Text.Encoding]::UTF8)
    $parts = ($c -split "(?m)^(?=- id:)") | Where-Object { $_ -match '^\- id:' }
    foreach ($p in $parts) {
        $lines = $p -split "`n"
        $id = (($lines | Where-Object { $_ -match '^- id:' }) -replace '^- id:\s*', '').Trim()
        $q = (($lines | Where-Object { $_ -match '^\s*question:' }) -replace '^\s*question:\s*', '').Trim()

        $ing = $false
        $sql = @()
        foreach ($l in $lines) {
            if ($l -match '^\s*gold_sql:\s*\|') { $ing = $true; continue }
            if (-not $ing) { continue }
            if ($l -match '^    ') { $sql += $l.Trim() }
            elseif ($l.Trim() -eq '') { continue }
            else { break }
        }
        $gsql = ($sql -join ' ')

        # top-level SELECT column count (ignore commas inside parens)
        $m = [regex]::Match($gsql, '(?is)select\s+(.*?)\s+from\s')
        if (-not $m.Success) { continue }
        $body = $m.Groups[1].Value
        $depth = 0; $n = 1
        foreach ($ch in $body.ToCharArray()) {
            if ($ch -eq '(') { $depth++ }
            elseif ($ch -eq ')') { $depth-- }
            elseif ($ch -eq ',' -and $depth -eq 0) { $n++ }
        }

        # single-row results have no identification ambiguity, so a scalar answer
        # is always acceptable; flag them separately.
        $aggOnly = ($body -notmatch '(?i)group\s+by')
        if ($n -gt 1) {
            $out.Add(('{0} | cols={1} | aggOnly={2} | {3}' -f $id, $n, $aggOnly, $q))
        }
    }
}

[System.IO.File]::WriteAllLines('target/gold-overanswer-candidates.txt', $out, (New-Object System.Text.UTF8Encoding $false))
Write-Output "candidates=$($out.Count)"
