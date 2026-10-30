<#
.SYNOPSIS
  Read-only recon of the TV: packages, settings, running services, network connections and logs.
.DESCRIPTION
  Changes nothing on the TV. Writes everything to recon\<timestamp>\ (or -OutDir), including a
  suspects.txt table of TCL / MediaTek / ads / telemetry packages to pull and analyze next.
  The output contains your device's identifiers (serial, MAC addresses, account names, IPs).
  Keep it private: recon\ is git-ignored.
.EXAMPLE
  .\recon.ps1                         # about 4 minutes, including a 3-minute connection watch
  .\recon.ps1 -WatchSeconds 0         # skip the connection watch
#>
param(
  [string]$Serial,
  [string]$OutDir,
  [int]$WatchSeconds = 180
)
. (Join-Path $PSScriptRoot '_common.ps1')
$null = Resolve-Tv $Serial
if (-not $OutDir) { $OutDir = Join-Path (Get-RepoRoot) ("recon\" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $OutDir | Out-Null
function Out-Recon([string]$Name) { $input | Save-Lines (Join-Path $OutDir $Name) }
Write-Host "Recon -> $OutDir"

# ---- device and packages
Adb shell getprop | Out-Recon 'getprop.txt'
Adb shell pm list packages -f -u | Out-Recon 'pkgs_all_f.txt'
Adb shell pm list packages -e | Out-Recon 'pkgs_enabled.txt'
Adb shell pm list packages -d | Out-Recon 'pkgs_disabled.txt'
Adb shell pm list packages -s | Out-Recon 'pkgs_system.txt'
Adb shell pm list packages -3 | Out-Recon 'pkgs_3p.txt'
$installed = Adb shell pm list packages --user 0 | ForEach-Object { ($_ -replace '^package:', '').Trim() }
$disabled = Adb shell pm list packages -d --user 0 | ForEach-Object { ($_ -replace '^package:', '').Trim() }

# package|path|installer|state  (state: enabled, disabled, or not-installed-for-user-0)
$table = foreach ($l in (Adb shell pm list packages -f -i -u)) {
  if ($l -match '^package:(.+)=([^=\s]+)\s+installer=(\S+)') {
    $pkg = $Matches[2]
    $st = if ($installed -notcontains $pkg) { 'not-installed-u0' } elseif ($disabled -contains $pkg) { 'disabled' } else { 'enabled' }
    [pscustomobject]@{ package = $pkg; path = (Split-Path $Matches[1] -Parent).Replace('\', '/'); installer = $Matches[3]; state = $st }
  }
}
$table = @($table | Sort-Object package)
$table | ForEach-Object { '{0}|{1}|{2}|{3}' -f $_.package, $_.path, $_.installer, $_.state } | Out-Recon 'pkgs_table.txt'

foreach ($ns in 'global', 'secure', 'system') { Adb shell settings list $ns | Out-Recon "settings_$ns.txt" }
Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Recon 'home_resolve.txt'

# ---- what is running
Adb shell ps -A -o 'USER,PID,PPID,NAME' | Out-Recon 'ps.txt'
Adb shell dumpsys activity services | Out-Recon 'running_services.txt'
Adb shell dumpsys alarm | Out-Recon 'alarm.txt'
Adb shell dumpsys jobscheduler | Out-Recon 'jobscheduler.txt'
Adb shell dumpsys connectivity | Out-Recon 'connectivity.txt'
Adb shell dumpsys netstats | Out-Recon 'netstats.txt'

# ---- suspects: vendor, ads, telemetry, ACR, assistant and auto-install packages
$suspectRe = '^(com\.tcl\.|com\.mediatek\.|tv\.mopa\.|com\.tvos|com\.tpa\.|android\.autoinstalls\.)|' +
  'adservices|ondevicepersonalization|federatedcompute|feedback|samba|acr|alexa|analytics|katniss|launcherx|tungsten|dreamx|play\.games'
$suspects = @($table | Where-Object { $_.package -match $suspectRe })
$running = (Get-Content (Join-Path $OutDir 'running_services.txt')) -join "`n"
$suspects | ForEach-Object {
  $_ | Add-Member -NotePropertyName services -NotePropertyValue ([regex]::Matches($running, "ServiceRecord\{\S+ u0 $([regex]::Escape($_.package))/").Count)
}
$suspects | Format-Table package, state, services, installer, path -AutoSize | Out-String -Width 250 | Out-Recon 'suspects.txt'
foreach ($s in $suspects | Where-Object services -gt 0) {
  Adb shell dumpsys activity services $s.package | Out-Recon "svc_$($s.package).txt"
}

# ---- network: current sockets, then a watch for new remote peers
Adb shell ss -tne | Out-Recon 'ss.txt'
if ($WatchSeconds -gt 0) {
  Write-Host "Watching TCP connections for $WatchSeconds s (use the TV normally meanwhile)..."
  $seen = New-Object 'System.Collections.Generic.HashSet[string]'
  $lines = New-Object System.Collections.Generic.List[string]
  $end = (Get-Date).AddSeconds($WatchSeconds)
  while ((Get-Date) -lt $end) {
    foreach ($l in (Adb shell ss -tne)) {
      if ($l -notmatch '^(ESTAB|SYN-SENT|CLOSE-WAIT|TIME-WAIT|FIN-WAIT)' -or $l -match '127\.0\.0\.1|::1\]') { continue }
      $cols = $l -split '\s+'
      $key = "$($cols[4]) $([regex]::Match($l, 'uid:\d+').Value)"
      if ($seen.Add($key)) { $lines.Add("$(Get-Date -Format HH:mm:ss) $l") }
    }
    Start-Sleep -Seconds 10
  }
  $lines | Out-Recon 'ss_watch.txt'
  # uid 1000 = system apps (TCL services run as system); look these peers up with whois / reverse DNS.
  Write-Host "Distinct remote peers: $($seen.Count)  (uid:1000 = system/TCL: $(@($seen | Where-Object { $_ -match 'uid:1000$' }).Count))"
}

Adb logcat -d -b main,system,events | Out-Recon 'logcat.txt'
Write-Host "Done. Packages: $($table.Count)  Suspects: $($suspects.Count)  See $(Join-Path $OutDir 'suspects.txt')"
Write-Host 'Next: .\pull-apks.ps1 to copy the suspect APKs for static analysis.'
