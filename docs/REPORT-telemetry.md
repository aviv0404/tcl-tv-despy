# TCL Smart TV Pro: telemetry and ads report

This is the full write-up behind the [README](../README.md). It covers what was found, how it was verified, and what each change does.

**Device:** TCL Smart_TV_Pro (G08_4K_GB), Android TV 14 (UTT2.250416.001), MediaTek MT9653 "merak", `ro.product.region=AP`, security patch 2025-12-05. Analysis done in 2026.

**Method:**
- Read-only ADB recon of all 172 installed packages (`scripts/recon.ps1`).
- 49 TCL and suspicious APKs pulled (`scripts/pull-apks.ps1`) and analyzed statically with **REA 6.8.0** (JADX headless MCP 0.7.1, SHA-256 verified) plus a DEX-string and endpoint extractor (`scripts/rea_bulk.py`).
- Live corroboration: `dumpsys`, running services, service bindings, a 3-minute socket watch (`ss`) and logcat.

> **REA is static-only.** It shows what the code *can* do; it does not run the APK. Wherever possible, the "Evidence" column also gives live evidence of what was actually running or connecting on the TV.

Evidence quotes below are limited to class, method, permission, setting and host names needed to verify the findings. No decompiled code or APKs are included in this repository.

---

## Summary: what's watching you

| # | Finding | Evidence | Severity |
|---|---------|----------|----------|
| 1 | **`com.tcl.ttvs` is TCL's central telemetry hub.** Every TCL app's BI SDK routes reports through its `BIService`; even Android **Settings** binds to it. | REA: `bi.com.tcl.bi.DataReport` → `reportDataFromTTVS()`. Live: `BIService` bound by `com.android.tv.settings`, `com.tcl.usercenter`, `com.tcl.partnercustomizer` and ttvs itself | 🔴 High |
| 2 | **Remote-control keypress logging.** ttvs registers a max-priority receiver for `tcl.sys.intent.action.USERSTATE_KEYDOWN` / `com.tcl.TCLkeyevent` and reports `keyName/keyCode/keyType/recordTime` (event type `35EB7EEF09`). **Which keys get logged is configured by the server** (`listInputKeyConfig`). | REA decompile: `KeyDownBehaviorReceiver.onReceive/a()`, `UsagestManager.initKeyDownReceiver()` | 🔴 High |
| 3 | **Usage/viewing statistics and device identifiers** are collected and uploaded: field names include `wifimac`, `Mac`, `android_id`, `serial`, `ip`, `DNUM`, `TID`, `VAID`/`AAID`/`OAID`, `packageName`, `channelName`, `startTime`/`endTime` and page-flow paths. Upload paths include `/report/custom2withencryption`. | ttvs classes `oversea.usagest.*`, `trackdata.*`, `identifier.*` (`PostIdentifierInfoApi`, `VAIDDao`, `AAIDDao`). Live: `TrackDataService`, `UsagestService`, `IdentifierService` and `BIService` running | 🔴 High |
| 4 | **Collection is ON by default and there's no setting for it.** The BI SDK reads `Settings.Global gdpr_user_action` with a **default of 1 (allowed)**. On the tested TV the key was unset, so everything was reported. | REA: `Settings.Global.getInt(cr, "gdpr_user_action", 1)`, `GDPRInterceptor.shouldIntercept()`. Live: `settings get global gdpr_user_action` → `null` | 🔴 High, **easy fix** |
| 5 | **The cloud config service phones home with the device number.** `com.tcl.providers.config` CloudService POSTs `{"clientType":"TCL-AP-MT9653-S1",...,"dnum":...}` to `on-hwtds-o.api.leiniao.com`. | Live logcat; live connections to 8.222.250.15 (Alibaba Cloud, Singapore) | 🟠 Medium |
| 6 | **Persistent TCL push channel:** a keepalive to `8.219.1.25:6178` (Alibaba Cloud, Singapore) from uid 1000 (TCL system apps). ttvs bundles an XMPP (Smack) client, a MessageService and MQTT clients. | Live socket watch over 3 minutes | 🟠 Medium |
| 7 | **Microphone-capture code in the HbbTV engine.** `com.tcl.interactive.InteractivePorting.startAudioRecord()` opens `AudioRecord(source=10 VOICE_PERFORMANCE, 48 kHz stereo)`. It's driven from native code (`init_porting_native`, `sita.hbbtv_tias_app`), so HbbTV apps delivered over the broadcast signal could trigger it. | REA decompile. Live: **not running** | 🟠 Medium (dormant) |
| 8 | **ACR (Samba TV) code is present but dormant.** `com.tcl.initsetup` has `SambaTVFragment/Service` and a `/module_user/samba_tv_agreement` consent route. ttvs references `com.tcl.software.sambatv_acr`, `ACR_FEATURE_NAME` and `SambaVideoService.remote`. | REA/strings. Live: no sambatv package, no `/data/system/sambatv`, no ACR process (region AP). Other regions may ship it active. | 🟡 Low here; disable defensively |
| 9 | **Ads:** TCL's ad server `on-euads-o.api.leiniao.com`, plus ad SDKs (Smaato, SpotX, smartclip, Google IMA/AdMob/DoubleClick, Firebase Analytics) in the TCL home feed, TCL Channel and partner-row apps. | REA SDK fingerprints and endpoints in `waterfall.overseas`, `channelplus`, `partnercustomizer`, `magiconnectfree`, `ttvs` | 🟠 Medium |
| 10 | **Silent app installs and updates.** ttvs has an `InstallManager`/app store and updates itself (installer=`com.tcl.ttvs`). `com.tcl.guard` holds `REQUEST_INSTALL_PACKAGES`. `system tcl_app_autoupdate` was 1. | REA manifest; live settings | 🟠 Medium |
| 11 | **Screen and log capture for "diagnostics":** `repairguide` (CAPTURE_VIDEO_OUTPUT, Tencent Bugly, RemoteDiagnosis), `logkit` (UpLoadLog), and ttvs strings `BIReport_Screenshot` / `AudioRecordData`. | REA manifest/strings | 🟡 Low-Med |

**Not found:** no running Samba TV/ACR process, no `TelevisionViewingBehavior` provider on this unit, and no third-party "spy" app. The tracking is TCL's own first-party BI and ads, plus standard Google services.

---

## Where the data goes (TCL infrastructure)

Leiniao (雷鸟 / FFalcon) is TCL's internet-TV, content and ads subsidiary. These hosts were seen live or found in the code:

| Host / IP | Used by | Purpose (REA + live) |
|---|---|---|
| `on-hwtds-o.api.leiniao.com`, `on-hw-tdp-o.api.leiniao.com` → 8.222.250.15 | providers.config, esticker | Unified cloud config; sends `dnum` |
| `tds-o` / `tdp-o.api.leiniao.com` → 43.142.211.31 / 175.24.142.94 (China) | providers.config | Config/data platform |
| `8.219.1.25:6178` (Alibaba Cloud, Singapore) | uid 1000 | Persistent push keepalive |
| `hwtdc-o`, `idsaas-o`, `hwbaodan-o`, `hwpreins-o`, `hwapps-o`, `hwlauncher-o.api.leiniao.com` | ttvs | Data collection, device-ID SaaS, activation/registration, pre-install pushes, app store |
| `on-euads-o.api.leiniao.com`, `hwads-t.api.my7v.com` | channelplus, partnercustomizer, waterfall | **Ads** |
| `hwprivacy-o`, `privacy-o`, `tpa-o`, `tpc-o.api.leiniao.com` | useragreement, usercenter, waterfall | Consent/agreement sync |
| `tmdeviceapi.tclking.com`, `*-newuser-tcl.cedock.com` | ttvs | Device registration and new-user stats |
| 3.76.142.125 (AWS Frankfurt) and a local CDN address | uid 1000 | Unresolved; both were gone after the debloat |

The blocklists in [`blocklist/`](../blocklist) cover all of these domains. `blocklist/hosts.txt` lists about 130 individual hostnames from the analysis.

---

## Per-package findings and recommendations

These are the tiers used by `scripts/packages.csv` and `scripts/debloat.ps1`.

- **DISABLE** = `pm disable-user --user 0 <pkg>`; undo with `pm enable <pkg>`. All of these are system or updated-system apps with factory copies, so it's fully reversible without root.
- **OPTIONAL** = your call, depending on whether you use the feature.
- **KEEP** = don't touch.

### Tier A: telemetry and ads core (recommended DISABLE)

| Package | What it is | Evidence | Removal risk |
|---|---|---|---|
| `com.tcl.ttvs` (TTVSCore, /data update over /product) | TCL services hub: BI/usage/keypress/ID tracking, TrackData, push/message, T-Cast, built-in Alexa, app store and silent installs, TCL update checks | Findings 1–4, 6 and 10. 4 boot hooks, 7 exported services, runs as **system uid**. Live: 10 services running | **Medium.** You lose TCL T-Cast casting, TCL Alexa, the TCL message box, TCL app auto-updates and maybe TCL firmware update prompts (re-enable it temporarily to update). Google Cast is unaffected. |
| `com.tcl.partnercustomizer` | Injects TCL partner rows and programs into the Google TV home | Leiniao ads endpoint; binds BIService (live); running | Low. Partner rows disappear. |
| `com.tcl.waterfall.overseas` | TCL "CyberUI" content and ads feed, plus watch history | Firebase Analytics, Google IMA, `LocalWatchHistoryManager`, `SERVER_WATCH_HISTORY`, ads host | Low |
| `com.tcl.channelplus` | "TCL Channel" ad-supported FAST channels | Smaato/SpotX/smartclip, `on-euads-o`, READ_EPG_DATA, ACCESS_WATCHED_PROGRAMS | Low. You lose TCL Channel. |
| `com.tcl.esticker` | Store-mode stickers and pop-ups; contains ACR message strings | WRITE_SECURE_SETTINGS, PACKAGE_USAGE_STATS, `EN_TCL_AUTOMATIC_CONTENT_RECOGNITION_MSG` | Low |
| `com.tcl.exhibit` | Store demo / art mode | `art-o` and `movip-o.api.leiniao.com`, BI | Low |
| `com.tcl.magiconnectfree` | TCL phone-to-TV connector | Firebase Analytics and Crashlytics, AdMob, fine location | Low. The TCL phone app link stops working. |
| `com.tcl.messagebox` | TCL notifications and promos | Boot hook, PACKAGE_USAGE_STATS, BI. Live service | Low |
| `com.tcl.dashboard` | TCL smart-home dashboard | MQTT/Paho, AWS IoT/Cognito/Kinesis/Firehose, BI | Low |
| `com.tcl.tv.tclhome_passive` | TCL Home IoT passive device registration | MQTT/Paho, AWS IoT, boot hook | Low |
| `com.tcl.logkit` | Log capture and **upload** | `UpLoadLog`, feedback/dfs leiniao hosts | Low |
| `com.tcl.repairguide` | Diagnostics, including **RemoteDiagnosis** | CAPTURE_VIDEO_OUTPUT, Tencent Bugly, location | Low |
| `com.tcl.interactive` | HbbTV engine ("TIAS") | **Microphone-capture code** (finding 7), SYSTEM_ALERT_WINDOW, QUERY_ALL_PACKAGES | Low. You lose HbbTV red-button services, which are mostly used in Europe. |
| `tv.mopa.ginga` | Ginga interactive-TV middleware | Only used by Brazilian broadcasts | Low |
| `com.tcl.smartalexa` | TCL built-in Alexa | Location, PACKAGE_USAGE_STATS, BI | Low, unless you use Alexa |
| `com.tcl.eva` | TCL voice/gesture assistant (wake-up and gestures) | Boot hook, QUERY_ALL_PACKAGES | Low |
| `com.tcl.usercenter` | TCL account | BI. Settings binds to it (live) | Low-Med. The "TCL account" item in Settings may stop working. |
| `com.tcl.ocean.instructions` | E-manual / user guide | Boot hook, BI | Low |
| `com.tcl.hotelmenu` | Hotel mode | WRITE_SECURE_SETTINGS, QUERY_ALL_PACKAGES | Low |
| `com.tcl.guard` | "Safety Guard" cleaner and permission manager | REQUEST_INSTALL_PACKAGES, PACKAGE_USAGE_STATS, BI | Low-Med |
| `com.google.android.feedback`, `com.android.tv.feedbackconsent` | Google feedback and bug-report upload | READ_LOGS, DUMP, usage stats | Low |
| `android.autoinstalls.config.google.gtvpai` | Play Auto Installs (pushes partner apps) | Stub | Low |
| `com.google.android.play.games` | Play Games | n/a | Low |
| `com.android.adservices.api`, `com.android.ondevicepersonalization.services`, `com.android.federatedcompute.services` | Android Privacy Sandbox ad services | APEX modules; Android may refuse to disable them | Low (attempted only) |

### Tier B: optional features

| Package | Feature | Notes |
|---|---|---|
| `com.tcl.useragreement` | TCL privacy agreements (syncs consent to the `tpa`/`tpc`/`privacy` hosts) | Medium: some TCL apps may re-prompt. Disable only after Tier A is stable. |
| `com.tcl.initsetup` | TCL first-boot setup, including the **Samba TV ACR consent** | Medium: **re-enable it before any factory reset.** |
| `com.tcl.UpdatePeripheral` | TCL soundbar/peripheral firmware | Disable it if you have no TCL soundbar. |
| `com.tcl.hearaid`, `com.tcl.t_solo` | Speaker calibration / "listen alone" | hearaid holds RECORD_AUDIO and fine location |
| `com.tcl.gallery` | TCL gallery and screensaver | BI |
| `com.tcl.gamebar` | HDMI game bar | |
| `com.tcl.airplay2`, `com.mediatek.AirplayAPK`, `com.mediatek.airplaydaemon` | Apple AirPlay | Keep them if you cast from an iPhone or Mac. |
| `com.tcl.miracast` | Screen mirroring | Google Cast keeps working without it. |
| `com.google.android.katniss` | Google Assistant / voice search | The remote's microphone button stops working. |
| `com.google.android.apps.tv.dreamx` | Google ambient screensaver | |

### KEEP (would break the TV or lock you out)
- `com.tcl.tv` (Live TV and inputs), `com.tcl.tvinput`, `com.tcl.systemserver` (APEX, persistent), `com.tcl.systemui.plugin`, `com.tcl.globalkeyoverlay`, `com.tcl.suspension` (Inputs/quick panel), `com.tcl.autopair` (**remote pairing**), `com.tcl.factory.view`.
- `com.tcl.providers.config`: the core config provider used by many TCL apps. Neutralize it with DNS blocking and the GDPR flag instead.
- `com.tcl.inputmethod.international`: the default keyboard. Install and select Gboard first if you want to remove it.
- `com.tcl.ui_mediaCenter` (USB player; its "Samba" hit is the SMB file-sharing library), `com.tvos`, `com.tpa.kb`.
- `com.mediatek.backgrounddetection` (AI picture-quality frame analysis, persistent, local only) and `com.mediatek.network`.
- `com.google.android.apps.tv.launcherx` (the stock HOME) and `com.google.android.tungsten.setupwraith` (HOME fallback). Only touch these with `scripts/set-launcher.ps1`, after Frame is installed and working.
- SystemUI, Settings, the package installer, the permission controller, Google Play services, the Play Store, Chromecast built-in (`mediashell`), the TV Remote service and `com.netflix.tokenmanager`.
- `debloat.ps1` refuses to touch the critical ones in this list (see `$ProtectedPackages` in `scripts/_common.ps1`).

---

## Non-package fixes (reversible)

| Setting | Change | Why | Restore |
|---|---|---|---|
| `settings put global gdpr_user_action 0` | TCL BI kill switch | Every TCL BI SDK (and ttvs's `GDPRInterceptor`) drops reports when this is not 1 | `settings delete global gdpr_user_action` |
| `settings put system tcl_app_autoupdate 0` | Stops TCL's silent app updates | Prevents disabled apps from being updated and re-activated | `settings put system tcl_app_autoupdate 1` |
| DNS blocking | Blocks TCL telemetry and ad domains | Catches direct SDK uploads if an app falls back from ttvs | Remove the blocklist / `set-private-dns.ps1 -Off` |
| Google (manual, on the TV) | Settings → Privacy → **Usage & diagnostics: Off**; **Ads → Delete advertising ID**; Location off if you don't need it | Google-side telemetry | Toggle back |

**Defense in depth:** when ttvs is disabled, the BI SDK falls back to sending directly from each app (`reportBySdk`). That's why the GDPR flag and DNS blocking are applied as well as disabling packages.

---

## Verification after the debloat

The tested unit ran Tier A plus five Tier B packages (`katniss`, `airplay2`, `AirplayAPK`, `airplaydaemon`, `gamebar`):

```powershell
.\scripts\debloat.ps1 -Tier A -Include com.google.android.katniss,com.tcl.airplay2,com.mediatek.AirplayAPK,com.mediatek.airplaydaemon,com.tcl.gamebar
```

| What | Result |
|---|---|
| Backup | `backup/<timestamp>/`: full package lists (`-f/-e/-d/-s/-3`), all settings namespaces, getprop, HOME, IME, and the prior state of every changed package and setting |
| Packages | **32/32 disabled** with `pm disable-user --user 0`, 0 failures (including the Privacy Sandbox APEX apps). `com.mediatek.airplaydaemon` is a PERSISTENT app that Android starts even when disabled, so it was also removed for user 0 (`pm uninstall -k --user 0`; restore with `cmd package install-existing`). |
| Settings | `global gdpr_user_action` null → **0**; `system tcl_app_autoupdate` 1 → **0** |
| DNS | Android Private DNS (DNS-over-TLS, strict) → `<NEXTDNS_ID>.dns.nextdns.io`, validated, with `blocklist/nextdns-denylist.txt` (28 domains) in the profile's Denylist. Leiniao, my7v, tclking, doubleclick, app-measurement and bugly are blocked; Netflix, YouTube, Play, Spotify and Google updates resolve normally. |
| Reboot test | Clean boot. All changes persisted. No app crashes or ANRs. The only crash-buffer entry is an `init` subprocess abort 3 s into boot, before the Android framework starts. It was already present before any change, so it's stock firmware behavior. |
| Functional | Live TV (`com.tcl.tv`), Inputs, Settings and About, the Google TV home and YouTube all work. |
| Network after | 3-minute socket watch: **no uid-1000 (TCL system) connections at all.** The 8.219.1.25:6178 push channel, the 8.222.250.15 Leiniao config server, 3.76.142.125 and the CDN address are all gone. Only Google/YouTube/Chromecast traffic remains. |

**Remaining (can't be removed without root, harmless):**
- `LogkitInputEvent`: a root init service from `/system_ext/bin/logkit.sh`. It watches for a debug key combination to dump logs to USB; its upload app is disabled.
- The MediaTek AirPlay/FairPlay HAL daemons, which sit idle without the AirPlay apps.
- `com.tcl.providers.config` CloudService is kept, but its server is DNS-blocked.

---

## Taking over HOME on Google TV (why both launcherx and setupwraith are disabled)

Making a sideloaded launcher the default home (`cmd package set-home-activity`, or the HOME role) is **not enough on Google TV**:

- `com.google.android.apps.tv.launcherx` declares its HOME intent filter at **priority 2**, and setupwraith's `RecoveryActivity` at **priority 1**.
- Android caps sideloaded apps at priority 0 and ignores the user's default-home choice whenever a higher-priority HOME exists. On the TV, the HOME role holder was Frame but the HOME key still opened launcherx (`start home ainfo is …launcherx`).
- REA decompile of `RecoveryActivity.onCreate` shows two behaviors:
  - If only launcherx's activity is disabled, it re-enables and relaunches it.
  - If the whole launcherx package is disabled, it just calls `finish()`, which would leave no usable home if nothing else were installed.

So the only non-root way is to disable **both** packages for user 0, which is what `scripts/set-launcher.ps1 -Mode frame` does. It checks that Frame starts first, and rolls back automatically if HOME doesn't resolve to Frame afterwards.

**Verified on the TV:**
- HOME resolves to `dev.frame.launcher/.HomeActivity`, and the HOME role holder is Frame.
- The HOME key opens Frame and returns to it from Netflix, YouTube and Live TV.
- The Inputs tile opens TCL's input panel, and its TV entry starts Live TV. The panel still lists a "Google TV Home" entry, which now just opens Frame.
- After a reboot, the TV boots via `FallbackHome` straight into Frame, and both packages stay disabled.
- No app crashes.

**Side effects:** the Google TV home, with its recommendations and ads, is gone. `set-launcher.ps1 -Mode stock` or a factory reset brings it back.

---

## Remote app buttons

TCL remotes have dedicated app buttons. On this model `/vendor/etc/hot-key-info.ini` maps YouTube to keycode 247, Google Play to 248, Amazon/Prime Video to 249 and VUDU to 250. TCL's system handles these, not the launcher, and if the target app isn't installed the button does nothing.

Android's `Generic.kcm` maps the generic `KEYCODE_BUTTON_n` gamepad keys back to DPAD_CENTER, so on a launcher they could open whichever tile has focus. Frame consumes `BUTTON_3/4/6/7`. `BUTTON_3` opens YouTube and `BUTTON_4` opens Netflix when those apps are installed; otherwise the keys do nothing. On this model TCL's window manager already intercepts `BUTTON_3` before it reaches any app.
