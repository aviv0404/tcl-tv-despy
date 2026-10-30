<#
.SYNOPSIS
  Reversible, non-root debloat of a TCL Google TV / Android TV over ADB.
.DESCRIPTION
  Reads the package tiers from packages.csv (A = telemetry/ads, B = optional features) and:
    1. Shows the plan and asks for confirmation (skip with -Force; -DryRun only shows it).
    2. Backs up package lists, settings and props to backup\<timestamp>\ in the repo folder.
    3. Records the prior state of every target package and setting (restore.ps1 uses it).
    4. Sets the TCL BI kill switch (global gdpr_user_action=0) and turns off TCL app auto-updates
       (system tcl_app_autoupdate=0), unless -SkipSettings.
    5. Runs `pm disable-user --user 0` for each package. Persistent apps, which Android starts even
       when disabled, are also removed for user 0 with `pm uninstall -k --user 0`.
  Nothing is deleted from the system image. Undo everything with .\restore.ps1.
.EXAMPLE
  .\debloat.ps1 -DryRun                                  # show what Tier A would do; change nothing
  .\debloat.ps1                                          # Tier A (recommended)
  .\debloat.ps1 -Tier A -Include com.tcl.gamebar,com.google.android.katniss
  .\debloat.ps1 -Tier A,B -Exclude com.tcl.initsetup,com.tcl.miracast
  .\debloat.ps1 -Tier None -Include com.tcl.gallery      # only the packages you name
  .\debloat.ps1 -ListFile my-list.txt                    # one package per line, # for comments
#>
param(
  [string]$Serial,
  [string[]]$Tier = @('A'),
  [string[]]$Include,
  [string[]]$Exclude,
  [string]$ListFile,
  [switch]$SkipSettings,
  [switch]$DryRun,
  [switch]$Force
)
. (Join-Path $PSScriptRoot '_common.ps1')
$Tier = Split-List $Tier
$Include = Split-List $Include
$Exclude = Split-List $Exclude
foreach ($t in $Tier) { if ('A', 'B', 'None' -notcontains $t) { throw "Unknown tier '$t'. Use A, B, A,B or None." } }
$null = Resolve-Tv $Serial

# ---- build the target list
$catalog = @(Import-Csv (Join-Path $PSScriptRoot 'packages.csv'))
if ($ListFile) {
  $targets = Get-Content $ListFile | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
} else {
  $targets = $catalog | Where-Object { $Tier -contains $_.tier } | ForEach-Object { $_.package }
}
$targets = @(@($targets) + $Include | Where-Object { $_ } | Where-Object { $Exclude -notcontains $_ } | Select-Object -Unique)
$blocked = @($targets | Where-Object { $ProtectedPackages -contains $_ })
if ($blocked) { throw "Refusing to disable protected package(s): $($blocked -join ', '). See README, Tier C." }
if (-not $targets) { throw 'Nothing to do: the package list is empty.' }

# ---- current state
function PkgList { Adb shell pm list packages @args --user 0 | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ } }
$installed = PkgList
$disabledBefore = PkgList -d

$settings = @()
if (-not $SkipSettings) {
  $settings = @(
    @{ ns = 'global'; key = 'gdpr_user_action'; value = '0' }    # TCL BI SDKs drop all reports when this is not 1
    @{ ns = 'system'; key = 'tcl_app_autoupdate'; value = '0' }  # stop TCL silent app updates
  ) | ForEach-Object {
    [pscustomobject]@{ ns = $_.ns; key = $_.key; prior = (Adb shell settings get $_.ns $_.key | Select-Object -Last 1).Trim(); new = $_.value }
  }
}

$state = foreach ($p in $targets) {
  $info = $catalog | Where-Object package -eq $p | Select-Object -First 1
  [pscustomobject]@{
    package     = $p
    installed   = ($installed -contains $p)
    wasDisabled = ($disabledBefore -contains $p)
    what        = if ($info) { $info.what } else { '(not in packages.csv)' }
    result      = ''
  }
}

Write-Host "TV: $script:TvSerial"
if ($settings) { $settings | Format-Table ns, key, prior, new -AutoSize | Out-String -Width 200 | Write-Host }
$state | Format-Table package, installed, wasDisabled, what -AutoSize -Wrap | Out-String -Width 200 | Write-Host
$todo = @($state | Where-Object { $_.installed -and -not $_.wasDisabled }).Count
Write-Host "$todo package(s) will be disabled for user 0."
if ($DryRun) { Write-Host 'DryRun: no changes made.'; return }
if (-not $Force -and (Read-Host 'Type YES to continue') -ne 'YES') { Write-Host 'Cancelled.'; return }

# ---- backup
$bk = Join-Path (Get-RepoRoot) ("backup\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $bk | Out-Null
Write-Host "Backup -> $bk"
Adb shell pm list packages -f -u | Save-Lines (Join-Path $bk 'packages_all_f.txt')
Adb shell pm list packages -e | Save-Lines (Join-Path $bk 'packages_enabled.txt')
Adb shell pm list packages -d | Save-Lines (Join-Path $bk 'packages_disabled.txt')
Adb shell pm list packages -s | Save-Lines (Join-Path $bk 'packages_system.txt')
Adb shell pm list packages -3 | Save-Lines (Join-Path $bk 'packages_user.txt')
foreach ($ns in 'global', 'secure', 'system') { Adb shell settings list $ns | Save-Lines (Join-Path $bk "settings_$ns.txt") }
Adb shell getprop | Save-Lines (Join-Path $bk 'getprop.txt')
Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Save-Lines (Join-Path $bk 'home.txt')
Adb shell settings get secure default_input_method | Save-Lines (Join-Path $bk 'ime.txt')
$settings | Export-Csv -NoTypeInformation (Join-Path $bk 'settings_state.csv')

# ---- apply
foreach ($s in $settings) {
  $out = Adb shell settings put $s.ns $s.key $s.new
  $now = (Adb shell settings get $s.ns $s.key | Select-Object -Last 1).Trim()
  Write-Host ('setting {0}/{1}: {2} -> {3} {4}' -f $s.ns, $s.key, $s.prior, $now, ($out -join ' '))
}

foreach ($row in $state) {
  if (-not $row.installed) { $row.result = 'SKIP not installed'; Write-Host ('{0,-46} {1}' -f $row.package, $row.result); continue }
  if ($row.wasDisabled) { $row.result = 'SKIP already disabled'; Write-Host ('{0,-46} {1}' -f $row.package, $row.result); continue }
  $out = (Adb shell pm disable-user --user 0 $row.package) -join ' '
  $row.result = if ($out -match 'new state: disabled') { 'OK disabled-user' } else { "FAIL $out" }
  # Android still starts PERSISTENT apps when they are disabled, so also remove them for user 0.
  if ($row.result -like 'OK*' -and ((Adb shell dumpsys package $row.package) -match 'pkgFlags=\[.*PERSISTENT')) {
    $u = (Adb shell pm uninstall -k --user 0 $row.package) -join ' '
    $row.result = if ($u -match 'Success') { 'OK disabled-user + uninstalled-user0 (persistent)' } else { "OK disabled-user (uninstall failed: $u)" }
  }
  Write-Host ('{0,-46} {1}' -f $row.package, $row.result)
}
$state | Select-Object package, installed, wasDisabled, result | Export-Csv -NoTypeInformation (Join-Path $bk 'package_state.csv')
Adb shell pm list packages -d | Save-Lines (Join-Path $bk 'packages_disabled_after.txt')

$ok = @($state | Where-Object result -like 'OK*').Count
$fail = @($state | Where-Object result -like 'FAIL*')
Write-Host "`nDisabled: $ok   Failed: $($fail.Count)   Backup: $bk"
if ($fail) { $fail | Format-Table package, result -AutoSize -Wrap | Out-String -Width 200 | Write-Host }
Write-Host "Undo with: .\restore.ps1 -BackupDir '$bk'"
Write-Host 'A reboot is recommended to confirm everything sticks: adb reboot'
