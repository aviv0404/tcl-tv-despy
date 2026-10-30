<#
.SYNOPSIS
  Switch the TV's HOME screen between Frame and the stock Google TV launcher.
.DESCRIPTION
  Google TV's launcher (launcherx) declares its HOME filter at priority 2 and setupwraith's
  RecoveryActivity at priority 1. Sideloaded apps are capped at priority 0, and Android ignores
  the user's default-home choice whenever a higher-priority HOME exists. RecoveryActivity also
  re-enables launcherx if only its activity is disabled. So Frame can only own the HOME key and
  boot if BOTH packages are disabled for user 0.
  -Mode frame checks that Frame starts, asks for YES, disables both, and rolls back automatically
  if HOME doesn't resolve to Frame. -Mode stock re-enables both. A factory reset also undoes it.
.EXAMPLE
  .\set-launcher.ps1 -Mode status
  .\set-launcher.ps1 -Mode frame      # asks for confirmation; add -Force to skip it
  .\set-launcher.ps1 -Mode stock      # put Google TV back
#>
param(
  [Parameter(Mandatory)][ValidateSet('frame', 'stock', 'status')][string]$Mode,
  [string]$Serial,
  [switch]$Force
)
. (Join-Path $PSScriptRoot '_common.ps1')
$null = Resolve-Tv $Serial

$frame = 'dev.frame.launcher/.HomeActivity'
$stock = 'com.google.android.apps.tv.launcherx/.home.HomeActivity'
$stockPkgs = 'com.google.android.apps.tv.launcherx', 'com.google.android.tungsten.setupwraith'

function HomeResolves { (Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1).Trim() }
function TopActivity {
  $l = (Adb shell dumpsys activity activities) | Select-String 'topResumedActivity|mResumedActivity' | Select-Object -First 1
  if ($l) { $l.Line.Trim() } else { 'unknown' }
}
function PkgState($p) {
  if (-not ((Adb shell pm list packages $p) -match "^package:$([regex]::Escape($p))$")) { return 'not installed' }
  $d = (Adb shell pm list packages -d) -join "`n"
  if ($d -match "package:$([regex]::Escape($p))(\s|$)") { 'disabled' } else { 'enabled' }
}
function Show-Status {
  foreach ($p in $stockPkgs + 'dev.frame.launcher') { Write-Host ('{0,-44} {1}' -f $p, (PkgState $p)) }
  Write-Host "HOME resolves to:  $(HomeResolves)"
  Write-Host "HOME role holder:  $((Adb shell cmd role get-role-holders android.app.role.HOME | Select-Object -Last 1).Trim())"
}
function PressHome {
  Adb shell input keyevent KEYCODE_HOME | Out-Null
  Start-Sleep -Seconds 2
  Write-Host "After HOME key:    $(TopActivity)"
}

switch ($Mode) {
  'status' { Show-Status }

  'frame' {
    if (-not ((Adb shell pm list packages dev.frame.launcher) -match 'dev.frame.launcher')) { throw 'Frame is not installed. Install the APK first (see README).' }
    # Preflight: Frame must start cleanly before the stock launcher is taken away.
    $start = (Adb shell am start -W -n $frame) -join "`n"
    if ($start -notmatch 'Status: ok') { throw "Frame failed to start; not changing HOME.`n$start" }
    Write-Host 'Frame starts OK.'
    if (-not $Force) {
      Write-Host "This disables $($stockPkgs -join ' and ') for user 0 (undo: .\set-launcher.ps1 -Mode stock)."
      if ((Read-Host 'Type YES to continue') -ne 'YES') { Write-Host 'Cancelled.'; return }
    }
    Adb shell cmd package set-home-activity $frame | Out-Null
    foreach ($p in $stockPkgs) {
      if ((PkgState $p) -eq 'not installed') { Write-Host ('{0,-44} not installed, skipped' -f $p); continue }
      Write-Host ('{0,-44} {1}' -f $p, ((Adb shell pm disable-user --user 0 $p) -join ' '))
    }
    $res = HomeResolves
    Write-Host "HOME resolves to:  $res"
    if ($res -ne $frame) {
      Write-Warning 'HOME does not resolve to Frame; rolling back to stock.'
      & $PSCommandPath -Mode stock -Serial $script:TvSerial
      return
    }
    PressHome
  }

  'stock' {
    foreach ($p in $stockPkgs) {
      if ((PkgState $p) -eq 'not installed') { Write-Host ('{0,-44} not installed, skipped' -f $p); continue }
      Write-Host ('{0,-44} {1}' -f $p, ((Adb shell pm enable --user 0 $p) -join ' '))
    }
    Adb shell cmd package set-home-activity $stock | Out-Null
    Write-Host "HOME resolves to:  $(HomeResolves)"
    PressHome
  }
}
