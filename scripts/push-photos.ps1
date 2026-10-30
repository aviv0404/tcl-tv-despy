<#
.SYNOPSIS
  Use your own background photos in Frame (they replace the bundled set while any are present).
.DESCRIPTION
  Copies .jpg/.jpeg/.png/.webp files into Frame's private photo folder on the TV:
  /sdcard/Android/data/dev.frame.launcher/files/backgrounds. Frame shows them in file-name
  order and rotates every 10 minutes (or press the photo button on the home screen).
  Landscape photos of at least 1920x1080 look best. Use -Reset to go back to the bundled photos.
.EXAMPLE
  .\push-photos.ps1 -Path C:\Pictures\TV                   # add every photo in a folder
  .\push-photos.ps1 -Path .\a.jpg, .\b.jpg -Replace        # replace the current set
  .\push-photos.ps1 -Reset                                  # remove pushed photos
#>
param(
  [string[]]$Path,
  [switch]$Replace,
  [switch]$Reset,
  [string]$Serial
)
. (Join-Path $PSScriptRoot '_common.ps1')
$Path = Split-List $Path
if (-not $Reset -and -not $Path) { throw 'Give -Path <folder or files>, or -Reset.' }
$null = Resolve-Tv $Serial
$dir = '/sdcard/Android/data/dev.frame.launcher/files/backgrounds'

if ($Reset -or $Replace) {
  Adb shell rm -rf $dir | Out-Null
  Write-Host 'Removed pushed photos.'
}
if ($Path) {
  $files = Get-ChildItem -Path $Path -File -ErrorAction Stop | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp)$' }
  if (-not $files) { throw 'No .jpg/.jpeg/.png/.webp files found.' }
  Adb shell mkdir -p $dir | Out-Null
  foreach ($f in $files) {
    $out = (Adb push $f.FullName "$dir/$($f.Name)") -join ' '
    if ($out -notmatch '1 file pushed') { throw "Push failed for $($f.Name): $out" }
    Write-Host "pushed $($f.Name)"
  }
}
$count = @(Adb shell ls $dir | Where-Object { $_ -match '\.(jpe?g|png|webp)$' }).Count
Write-Host "$count custom photo(s) on the TV$(if ($count -eq 0) { ' - Frame uses its bundled photos' })."

# Reload now if Frame is on screen; otherwise it picks the photos up the next time it loads one.
$top = (Adb shell dumpsys activity activities) | Select-String 'topResumedActivity' | Select-Object -First 1
if ("$top" -match 'dev\.frame\.launcher') {
  Adb shell am force-stop dev.frame.launcher | Out-Null
  Adb shell am start -n dev.frame.launcher/.HomeActivity | Out-Null
  Write-Host 'Frame reloaded.'
}
