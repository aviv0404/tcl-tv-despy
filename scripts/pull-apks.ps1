<#
.SYNOPSIS
  Read-only: copy installed APKs (base + splits) from the TV for static analysis.
.DESCRIPTION
  Pulls every APK file reported by `pm path <package>` into apks\<package>\ (or -OutDir).
  By default it pulls the vendor / telemetry / ads packages matched by -Pattern; pass -Packages
  to choose your own. Pulled APKs are copyrighted by their vendors: keep them private
  (apks\ is git-ignored). Analyze them with: python scripts\rea_bulk.py
.EXAMPLE
  .\pull-apks.ps1                                   # default pattern (TCL, MediaTek, telemetry)
  .\pull-apks.ps1 -Packages com.tcl.ttvs,com.tcl.esticker
#>
param(
  [string]$Serial,
  [string[]]$Packages,
  [string]$Pattern = '^(com\.tcl\.|com\.mediatek\.|tv\.mopa\.|android\.autoinstalls\.|com\.google\.android\.(feedback|katniss|tungsten\.setupwraith|apps\.tv\.launcherx|apps\.tv\.dreamx|play\.games)$|com\.android\.(tv\.feedbackconsent|adservices\.api|ondevicepersonalization\.services|federatedcompute\.services)$)',
  [string]$OutDir,
  [switch]$Force
)
. (Join-Path $PSScriptRoot '_common.ps1')
$Packages = Split-List $Packages
$null = Resolve-Tv $Serial
if (-not $OutDir) { $OutDir = Join-Path (Get-RepoRoot) 'apks' }

if (-not $Packages) {
  $Packages = Adb shell pm list packages -u | ForEach-Object { ($_ -replace '^package:', '').Trim() } |
    Where-Object { $_ -match $Pattern } | Sort-Object
}
Write-Host "$(@($Packages).Count) package(s) -> $OutDir"

$total = 0L
foreach ($p in $Packages) {
  $paths = @(Adb shell pm path $p | Where-Object { $_ -like 'package:*' } | ForEach-Object { $_.Substring(8).Trim() })
  if (-not $paths) { Write-Host ('{0,-50} no APK path (not installed?)' -f $p); continue }
  $dest = Join-Path $OutDir $p
  if ((Test-Path $dest) -and -not $Force) { Write-Host ('{0,-50} already pulled' -f $p); continue }
  New-Item -ItemType Directory -Force $dest | Out-Null
  $ok = 0
  foreach ($remote in $paths) {
    $local = Join-Path $dest ($remote -split '/')[-1]
    $out = (Adb pull $remote $local) -join ' '
    if ($out -match '1 file pulled') { $ok++; $total += (Get-Item $local).Length }
    else { Write-Warning "$p : $out" }
  }
  Write-Host ('{0,-50} {1}/{2} file(s)' -f $p, $ok, $paths.Count)
}
Write-Host ('Done. {0:N0} MB pulled.' -f ($total / 1MB))
