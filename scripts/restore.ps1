<#
.SYNOPSIS
  Undo debloat.ps1: re-enable every package it disabled and restore the settings it changed.
.DESCRIPTION
  Uses backup\<timestamp>\package_state.csv and settings_state.csv written by debloat.ps1.
  Packages that were removed for user 0 are brought back with `cmd package install-existing`.
.EXAMPLE
  .\restore.ps1 -DryRun                            # show what would be restored from the latest backup
  .\restore.ps1                                    # restore everything from the latest backup
  .\restore.ps1 -Packages com.tcl.ttvs             # re-enable just one package
  .\restore.ps1 -BackupDir ..\backup\20250101-120000 -SkipSettings
#>
param(
  [string]$Serial,
  [string]$BackupDir,
  [string[]]$Packages,
  [switch]$SkipSettings,
  [switch]$DryRun
)
. (Join-Path $PSScriptRoot '_common.ps1')
$Packages = Split-List $Packages

if (-not $BackupDir) {
  $backupRoot = Join-Path (Get-RepoRoot) 'backup'
  if (Test-Path $backupRoot) {
    $BackupDir = Get-ChildItem $backupRoot -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'package_state.csv') } |
      Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
  }
}
if (-not $BackupDir -or -not (Test-Path (Join-Path $BackupDir 'package_state.csv'))) { throw 'No backup with package_state.csv found. Pass -BackupDir.' }
Write-Host "Restoring from $BackupDir"

$state = @(Import-Csv (Join-Path $BackupDir 'package_state.csv') | Where-Object { $_.result -like 'OK*' })
if ($Packages) { $state = @($state | Where-Object { $Packages -contains $_.package }) }
$settingsFile = Join-Path $BackupDir 'settings_state.csv'
$restoreSettings = -not $SkipSettings -and -not $Packages -and (Test-Path $settingsFile)

if ($DryRun) {
  $state | Format-Table package, result -AutoSize | Out-String -Width 200 | Write-Host
  if ($restoreSettings) { Import-Csv $settingsFile | Format-Table ns, key, prior -AutoSize | Out-String | Write-Host }
  Write-Host "DryRun: would re-enable $($state.Count) package(s). No changes made."
  return
}

$null = Resolve-Tv $Serial
foreach ($row in $state) {
  $p = $row.package
  $out = (Adb shell pm enable --user 0 $p) -join ' '
  if ($out -notmatch 'new state: enabled') {
    # The package was removed for user 0 (pm uninstall -k --user 0): reinstall it from the system image.
    $out = ((Adb shell cmd package install-existing --user 0 $p) + (Adb shell pm enable --user 0 $p)) -join ' '
  }
  Write-Host ('{0,-46} {1}' -f $p, $out)
}

if ($restoreSettings) {
  foreach ($s in Import-Csv $settingsFile) {
    if ($s.prior -eq 'null' -or $s.prior -eq '') { Adb shell settings delete $s.ns $s.key | Out-Null }
    else { Adb shell settings put $s.ns $s.key $s.prior | Out-Null }
    $now = (Adb shell settings get $s.ns $s.key | Select-Object -Last 1).Trim()
    Write-Host ('setting {0}/{1} restored -> {2}' -f $s.ns, $s.key, $now)
  }
}
Write-Host 'Done. A reboot is recommended: adb reboot'
