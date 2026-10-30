# Shared helpers, dot-sourced by the other scripts in this folder. Not meant to be run directly.
$ErrorActionPreference = 'Stop'

# Picks the TV to talk to: -Serial, then $env:TV_SERIAL, then the only connected adb device.
# A host:port serial is connected automatically. Returns the serial and remembers it for Adb.
function Resolve-Tv([string]$Serial) {
  # Call adb by its full path: a bare `adb` inside these scripts would resolve to the Adb function below.
  $exe = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $exe) {
    throw 'adb was not found on PATH. Install Android platform-tools first (see README, "Prerequisites").'
  }
  $script:AdbExe = $exe.Source
  if (-not $Serial) { $Serial = $env:TV_SERIAL }
  $ErrorActionPreference = 'Continue'
  if ($Serial -match '^[^:\s]+:\d+$') { & $script:AdbExe connect $Serial 2>&1 | Out-Null }
  if (-not $Serial) {
    $devices = @(& $script:AdbExe devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" } |
        ForEach-Object { ($_ -split "`t")[0] })
    if ($devices.Count -eq 0) {
      throw 'No TV connected. Pass -Serial <TV_IP>:5555 (or set $env:TV_SERIAL), or run: adb connect <TV_IP>:<port>'
    }
    if ($devices.Count -gt 1) { throw "Several adb devices are connected ($($devices -join ', ')). Choose one with -Serial." }
    $Serial = $devices[0]
  }
  $state = "$(& $script:AdbExe -s $Serial get-state 2>&1 | Select-Object -Last 1)".Trim()
  if ($state -ne 'device') {
    throw "TV $Serial is not connected or not authorized ($state). Run: adb connect $Serial, then accept the prompt on the TV."
  }
  $script:TvSerial = $Serial
  return $Serial
}

# Runs adb against the resolved TV and returns its output (stdout and stderr) as strings.
function Adb { $ErrorActionPreference = 'Continue'; & $script:AdbExe -s $script:TvSerial @args 2>&1 | ForEach-Object { "$_" } }

function Get-RepoRoot { Split-Path $PSScriptRoot -Parent }

function Save-Lines([string]$Path) { [IO.File]::WriteAllLines($Path, [string[]]@($input)) }

# `powershell -File x.ps1 -Opt a,b` passes "a,b" as one string, so list parameters are split here.
function Split-List([string[]]$Values) { @($Values | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }

# Packages that must never be disabled by these scripts: doing so can brick the UI or lock you out.
# The stock launcher pair is handled separately (and reversibly) by set-launcher.ps1.
$ProtectedPackages = @(
  'android', 'com.android.systemui', 'com.android.tv.settings', 'com.android.settings',
  'com.android.packageinstaller', 'com.google.android.packageinstaller',
  'com.android.permissioncontroller', 'com.google.android.permissioncontroller',
  'com.android.providers.settings', 'com.android.shell', 'com.android.inputdevices',
  'com.google.android.gms', 'com.android.vending',
  'com.tcl.tv', 'com.tcl.tvinput', 'com.tcl.systemserver', 'com.tcl.systemui.plugin',
  'com.tcl.globalkeyoverlay', 'com.tcl.suspension', 'com.tcl.autopair', 'com.tcl.factory.view',
  'com.tcl.providers.config', 'com.tcl.inputmethod.international',
  'com.google.android.apps.tv.launcherx', 'com.google.android.tungsten.setupwraith',
  'dev.frame.launcher'
)
