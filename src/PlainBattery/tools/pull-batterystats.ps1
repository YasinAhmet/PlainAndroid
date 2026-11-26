<#
.SYNOPSIS
  Pulls `dumpsys batterystats --checkin` from the phone over adb, maps UIDs to package names
  and appends per-app power estimates to a CSV log on the PC.

.DESCRIPTION
  The same numbers Plain Battery shows on the phone, as a second log that survives the app or a
  wiped phone. Each run appends one row per UID with a non-zero estimate:
    pulled_at, stats_since, uid, name, mah_since_reset, screen_mah, mah_with_shared, mah_since_last_pull
  plus one row per hardware component (uid column = "hw") and one "measured" row with the
  coulomb-counter discharge since the reset.

  batterystats resets on a full charge. Right after a reset the first `--checkin` returns the
  saved previous period once; the script detects a changed period and asks again.

.EXAMPLE
  .\tools\pull-batterystats.ps1
  .\tools\pull-batterystats.ps1 -Serial <adb-serial> -KeepRaw
#>
param(
    [string]$Serial = "<adb-serial>",
    [string]$LogDir = (Join-Path $PSScriptRoot "..\logs"),
    [switch]$KeepRaw
)
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
New-Item -ItemType Directory -Force $LogDir | Out-Null
$LogDir = (Resolve-Path $LogDir).Path
$csv = Join-Path $LogDir "batterystats-pc.csv"
$stateFile = Join-Path $LogDir "last-pull.json"

function Get-Checkin {
    $out = & $adb -s $Serial shell dumpsys batterystats --checkin
    if ($LASTEXITCODE -ne 0) { throw "adb failed (exit $LASTEXITCODE). Is the phone connected?" }
    return $out
}

function Read-Checkin([string[]]$lines) {
    $r = @{ Start = 0L; DischargeMah = 0.0; DischargePct = 0; Names = @{}; Apps = @{}; Hw = [ordered]@{} }
    foreach ($line in $lines) {
        $f = $line.Split(',')
        if ($f.Count -lt 5) { continue }
        $cat = $f[2]
        switch ($f[3]) {
            "uid" { if ($cat -eq "i" -and $f.Count -ge 6) {
                        $u = [int]$f[4]
                        if (-not $r.Names.ContainsKey($u)) { $r.Names[$u] = New-Object System.Collections.ArrayList }
                        if (-not $r.Names[$u].Contains($f[5])) { [void]$r.Names[$u].Add($f[5]) } } }
            "bt"  { if ($cat -eq "l" -and $f.Count -ge 10) { $r.Start = [long]$f[9] } }
            "dc"  { if ($cat -eq "l" -and $f.Count -ge 9) { $r.DischargePct = [int]$f[4]; $r.DischargeMah = [double]$f[8] } }
            "pwi" { if ($cat -eq "l" -and $f.Count -ge 6) {
                        $mah = [double]::Parse($f[5], [Globalization.CultureInfo]::InvariantCulture)
                        if ($f[4] -eq "uid") {
                            $u = [int]$f[1]
                            $scr = if ($f.Count -ge 8) { [double]::Parse($f[7], [Globalization.CultureInfo]::InvariantCulture) } else { 0 }
                            $sm  = if ($f.Count -ge 9) { [double]::Parse($f[8], [Globalization.CultureInfo]::InvariantCulture) } else { $mah }
                            if ($r.Apps.ContainsKey($u)) { $o = $r.Apps[$u]; $r.Apps[$u] = @($o[0] + $mah, $o[1] + $scr, $o[2] + $sm) }
                            else { $r.Apps[$u] = @($mah, $scr, $sm) }
                        } else {
                            $k = if ($f[4] -eq "???") { "other" } else { $f[4] }
                            $prev = 0.0; if ($r.Hw.Contains($k)) { $prev = $r.Hw[$k] }
                            $r.Hw[$k] = $prev + $mah
                        } } }
        }
    }
    return $r
}

$fixed = @{ -5 = "Tethering / hotspot traffic"; -4 = "Removed apps"; 0 = "Kernel / root processes";
    1000 = "Android system"; 1001 = "Phone / radio"; 1002 = "Bluetooth"; 1010 = "Wi-Fi"; 1013 = "Media server";
    1041 = "Audio server"; 1046 = "Media codecs"; 1047 = "Camera server"; 2000 = "ADB shell" }

$last = $null
if (Test-Path $stateFile) { $last = Get-Content $stateFile -Raw | ConvertFrom-Json }

$lines = Get-Checkin
$cur = Read-Checkin $lines
if ($cur.Apps.Count -eq 0) { throw "No power data in the dump: $($lines | Select-Object -First 1)" }
if ($null -eq $last -or [long]$last.start -ne $cur.Start) {
    # New period (or first run): the first answer may be the saved previous period. Ask again.
    $lines2 = Get-Checkin
    $cur2 = Read-Checkin $lines2
    if ($cur2.Apps.Count -gt 0) { $lines = $lines2; $cur = $cur2 }
}

# Installed packages by uid fill gaps in the checkin table.
$pmMap = @{}
foreach ($l in (& $adb -s $Serial shell pm list packages -U -u)) {
    if ($l -match '^package:(\S+) uid:(\d+)') { $u = [int]$Matches[2]; if (-not $pmMap.ContainsKey($u)) { $pmMap[$u] = $Matches[1] } }
}

$now = Get-Date
$stamp = $now.ToString("yyyy-MM-dd HH:mm:ss")
$since = [DateTimeOffset]::FromUnixTimeMilliseconds($cur.Start).LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss")
$samePeriod = ($null -ne $last -and [long]$last.start -eq $cur.Start)
$prevApps = @{}
if ($samePeriod) { foreach ($p in $last.apps.PSObject.Properties) { $prevApps[[int]$p.Name] = [double]$p.Value } }

function Format-Num([double]$v) { $v.ToString("0.00", [Globalization.CultureInfo]::InvariantCulture) }
function Format-Text([string]$s) { if ($s -match '[,"]') { '"' + $s.Replace('"', '""') + '"' } else { $s } }

$rows = New-Object System.Collections.Generic.List[string]
if (-not (Test-Path $csv)) { $rows.Add("pulled_at,stats_since,uid,name,mah_since_reset,screen_mah,mah_with_shared,mah_since_last_pull") }
$dm = if ($samePeriod) { Format-Num ($cur.DischargeMah - [double]$last.discharge) } else { "" }
$rows.Add("$stamp,$since,measured,coulomb counter ($($cur.DischargePct)%),$(Format-Num $cur.DischargeMah),,,$dm")
foreach ($e in ($cur.Apps.GetEnumerator() | Sort-Object { $_.Value[0] } -Descending)) {
    $u = [int]$e.Key; $v = $e.Value
    if ($v[0] -lt 0.05) { continue }
    $name = if ($fixed.ContainsKey($u)) { $fixed[$u] }
        elseif ($cur.Names.ContainsKey($u)) { ($cur.Names[$u] -join " ") }
        elseif ($pmMap.ContainsKey($u)) { $pmMap[$u] }
        else { "uninstalled (uid $u)" }
    $d = if ($samePeriod) { $p0 = 0.0; if ($prevApps.ContainsKey($u)) { $p0 = $prevApps[$u] }; Format-Num ([Math]::Max(0, $v[0] - $p0)) } else { "" }
    $rows.Add("$stamp,$since,$u,$(Format-Text $name),$(Format-Num $v[0]),$(Format-Num $v[1]),$(Format-Num $v[2]),$d")
}
foreach ($k in $cur.Hw.Keys) {
    if ($cur.Hw[$k] -ge 0.05) { $rows.Add("$stamp,$since,hw,$k,$(Format-Num $cur.Hw[$k]),,,") }
}
[IO.File]::AppendAllLines($csv, $rows)

if ($KeepRaw) {
    $rawDir = Join-Path $LogDir "raw"; New-Item -ItemType Directory -Force $rawDir | Out-Null
    [IO.File]::WriteAllLines((Join-Path $rawDir ("checkin-" + $now.ToString("yyyyMMdd-HHmmss") + ".txt")), [string[]]$lines)
}

$appsState = @{}
foreach ($e in $cur.Apps.GetEnumerator()) { $appsState["$($e.Key)"] = $e.Value[0] }
@{ start = $cur.Start; discharge = $cur.DischargeMah; pulled = $stamp; apps = $appsState } |
    ConvertTo-Json -Depth 3 | Set-Content -Encoding utf8 $stateFile

Write-Host ("Stats since {0}: measured {1:0} mAh ({2}%). Top apps:" -f $since, $cur.DischargeMah, $cur.DischargePct)
$cur.Apps.GetEnumerator() | Sort-Object { $_.Value[0] } -Descending | Select-Object -First 10 | ForEach-Object {
    $u = [int]$_.Key
    $n = if ($fixed.ContainsKey($u)) { $fixed[$u] } elseif ($cur.Names.ContainsKey($u)) { $cur.Names[$u][0] } elseif ($pmMap.ContainsKey($u)) { $pmMap[$u] } else { "uid $u" }
    Write-Host ("  {0,8:0.0} mAh  {1}" -f $_.Value[0], $n)
}
Write-Host "Appended $($rows.Count) rows to $csv"
