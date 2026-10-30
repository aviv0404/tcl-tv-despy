<#
.SYNOPSIS
  Point the TV's Android Private DNS (DNS-over-TLS, strict mode) at a filtering resolver, or turn it off.
.DESCRIPTION
  -ConfigId uses a NextDNS profile (<id>.dns.nextdns.io); add blocklist\nextdns-denylist.txt to that
  profile's Denylist first. -Hostname works with any DNS-over-TLS provider, for example
  dns.adguard-dns.com or a Control D resolver.
  Strict Private DNS fails closed: if the resolver can't be reached the TV has no internet. So the
  script checks that normal names still resolve and rolls back automatically if they don't.
  The previous setting is saved to backup\private_dns_prior.csv for -Off.
.EXAMPLE
  .\set-private-dns.ps1 -ConfigId abc123
  .\set-private-dns.ps1 -Hostname dns.adguard-dns.com
  .\set-private-dns.ps1 -Off
#>
param(
  [string]$Serial,
  [string]$ConfigId,
  [string]$Hostname,
  [switch]$Off
)
. (Join-Path $PSScriptRoot '_common.ps1')
$null = Resolve-Tv $Serial

function Get-Setting($k) { (Adb shell settings get global $k | Select-Object -Last 1).Trim() }
function Resolve-OnTv([string]$h) {
  $m = [regex]::Match(((Adb shell ping -c 1 -W 2 $h) -join ' '), 'PING \S+ \(([\d\.]+)\)')
  if ($m.Success) { $m.Groups[1].Value } else { 'no-resolve' }
}

$bkDir = Join-Path (Get-RepoRoot) 'backup'
$stateFile = Join-Path $bkDir 'private_dns_prior.csv'

if ($Off) {
  if (Test-Path $stateFile) {
    foreach ($s in Import-Csv $stateFile) {
      if ($s.prior -eq 'null' -or $s.prior -eq '') { Adb shell settings delete global $s.key | Out-Null }
      else { Adb shell settings put global $s.key $s.prior | Out-Null }
    }
  } else { Adb shell settings put global private_dns_mode opportunistic | Out-Null }
  Write-Host "private_dns_mode=$(Get-Setting private_dns_mode) specifier=$(Get-Setting private_dns_specifier)"
  return
}

if ($ConfigId) {
  if ($ConfigId -notmatch '^[a-f0-9]{6}$') { throw 'ConfigId should be the 6-character NextDNS profile ID (e.g. abc123).' }
  $Hostname = "$ConfigId.dns.nextdns.io"
}
if (-not $Hostname) { throw 'Give -ConfigId <NextDNS ID>, -Hostname <DoT host>, or -Off.' }
if ($Hostname -notmatch '^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$') { throw "'$Hostname' doesn't look like a DNS-over-TLS hostname." }

if (-not (Test-Path $stateFile)) {
  New-Item -ItemType Directory -Force $bkDir | Out-Null
  'private_dns_mode', 'private_dns_specifier' | ForEach-Object { [pscustomobject]@{ key = $_; prior = (Get-Setting $_) } } |
    Export-Csv -NoTypeInformation $stateFile
}

Adb shell settings put global private_dns_specifier $Hostname | Out-Null
Adb shell settings put global private_dns_mode hostname | Out-Null
Start-Sleep -Seconds 8
Write-Host "private_dns_mode=$(Get-Setting private_dns_mode) specifier=$(Get-Setting private_dns_specifier)"

$google = Resolve-OnTv 'www.google.com'
$tcl = Resolve-OnTv 'on-hwtds-o.api.leiniao.com'
Write-Host "www.google.com -> $google    on-hwtds-o.api.leiniao.com -> $tcl"
if ($google -eq 'no-resolve') {
  Write-Warning 'Normal DNS fails through this resolver; rolling back to the previous Private DNS setting.'
  & $PSCommandPath -Serial $script:TvSerial -Off
  return
}
if ('0.0.0.0', '127.0.0.1', 'no-resolve' -contains $tcl) { Write-Host "OK: TCL telemetry domain is blocked ($tcl)." }
else { Write-Warning 'The TCL telemetry domain still resolves. Add blocklist\nextdns-denylist.txt (or an equivalent list) to your resolver.' }
