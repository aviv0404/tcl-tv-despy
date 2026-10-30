"""Bulk static analysis of pulled APKs with REA (https://github.com/morluto/rea).

For each package directory under apks/ (see scripts/pull-apks.ps1):
  * REA inspect-android-package  -> rea/<pkg>/manifest.json
  * REA search-android-classes "" -> rea/<pkg>/classes.json (full class inventory)
  * DEX string table + resource/asset/native text scan for URLs and hostnames
    -> rea/<pkg>/endpoints.txt
Then writes rea/summary.json with permissions, boot hooks, exported services,
third-party SDK fingerprints and endpoints per package, plus two readable digests:
rea/facts.txt (per package) and rea/hosts.txt (host -> packages that reference it).

Usage:
  python scripts/rea_bulk.py                     # every package under apks/
  python scripts/rea_bulk.py com.tcl.ttvs        # only these packages
  python scripts/rea_bulk.py --apks D:/apks --out D:/rea --workers 2

REA needs a JDK 17+ and REA_JADX_MCP_JAR pointing at the JADX MCP jar (see README).
The REA command is taken from $REA_CMD, else `rea` on PATH, else `npx --yes rea-agents@latest`.
Outputs contain decompiled vendor code: keep apks/ and rea/ private (both are git-ignored).
"""
import argparse
import concurrent.futures as cf
import json
import os
import re
import shlex
import shutil
import struct
import subprocess
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APKS = os.path.join(ROOT, "apks")
OUT = os.path.join(ROOT, "rea")


def rea_command():
    if os.environ.get("REA_CMD"):
        return shlex.split(os.environ["REA_CMD"], posix=os.name != "nt")
    rea = shutil.which("rea")
    if rea:
        return [rea]
    npx = shutil.which("npx")
    if npx:
        return [npx, "--yes", "rea-agents@latest"]
    raise SystemExit("REA not found: npm i -g rea-agents (Node 22+), or set REA_CMD.")


REA = None

SKIP_REA = {"com.tcl.android.webview", "tv.mopa.ginga"}  # Chromium fork / Brazil-only Ginga middleware

SDK_PATTERNS = {
    "Firebase Analytics": r"com/google/firebase/analytics|com\.google\.firebase\.analytics",
    "Firebase Crashlytics": r"com\.google\.firebase\.crashlytics",
    "Google Analytics": r"com\.google\.android\.gms\.analytics",
    "Google Ads/IMA": r"com\.google\.android\.gms\.ads|com\.google\.ads\.interactivemedia",
    "Umeng": r"com\.umeng\.",
    "Tencent Bugly": r"com\.tencent\.bugly",
    "SensorsData": r"com\.sensorsdata\.",
    "AppsFlyer": r"com\.appsflyer\.",
    "Adjust": r"com\.adjust\.sdk",
    "Branch": r"io\.branch\.",
    "Facebook SDK": r"com\.facebook\.(ads|appevents|FacebookSdk)",
    "Amplitude": r"com\.amplitude\.",
    "Mixpanel": r"com\.mixpanel\.",
    "Sentry": r"io\.sentry\.",
    "Alibaba/Aliyun": r"com\.(alibaba|aliyun|taobao)\.",
    "Huawei HMS": r"com\.huawei\.hms",
    "Samba TV": r"(?i)samba",
    "Alphonso": r"(?i)alphonso",
    "Gracenote": r"(?i)gracenote",
    "Inscape/Vizio": r"(?i)inscape",
    "Moat/IAS": r"com\.moat\.|integralads",
    "Conviva": r"com\.conviva\.",
    "Nielsen": r"(?i)nielsen",
    "Comscore": r"(?i)comscore",
    "OMSDK": r"com\.iab\.omid",
    "Amazon Alexa": r"com\.amazon\.alexa",
    "MQTT/Paho": r"org\.eclipse\.paho",
    "TCL BI/track": r"(?i)com\.tcl\.[a-z0-9_.]*\.(bi|trackdata|track|report|statistic|usagest|analytics|datareport|log(report|upload)?)\b",
    "TCL ACR/content recognition": r"(?i)(\bacr\b|\.acr\.|acrservice|contentrecogn|fingerprint\.audio|videofinger|autocontent)",
}

SENSITIVE_PERMS = re.compile(
    r"RECORD_AUDIO|CAPTURE_AUDIO|CAPTURE_VIDEO|READ_FRAME_BUFFER|CAPTURE_SECURE_VIDEO|"
    r"PACKAGE_USAGE_STATS|READ_LOGS|DUMP|INSTALL_PACKAGES|DELETE_PACKAGES|"
    r"ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|READ_PHONE_STATE|READ_PRIVILEGED_PHONE_STATE|"
    r"GET_ACCOUNTS|READ_CONTACTS|BIND_ACCESSIBILITY|SYSTEM_ALERT_WINDOW|"
    r"RECEIVE_BOOT_COMPLETED|INTERACT_ACROSS_USERS|WRITE_SECURE_SETTINGS|"
    r"QUERY_ALL_PACKAGES|REAL_GET_TASKS|GET_TASKS|ACCESS_WIFI_STATE|LOCAL_MAC_ADDRESS|"
    r"READ_TV_LISTINGS|ACCESS_WATCHED_PROGRAMS|READ_EPG_DATA|TV_INPUT_HARDWARE|"
    r"com\.tcl\.(bi|.*track|.*report|.*usage)",
    re.I,
)

URL_RE = re.compile(rb"(?:https?|wss?|mqtts?|tcp)://[A-Za-z0-9._\-]+(?::\d+)?(?:/[^\s\"'<>\\\x00-\x1f]*)?")
HOST_RE = re.compile(
    rb"(?<![A-Za-z0-9_\-.])(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+"
    rb"(?:com|cn|net|io|tv|org|co|de|me|info|xyz|ai|cloud|app|link)(?![A-Za-z0-9_\-])"
)
NOISE_HOST = re.compile(
    r"^(schemas\.android\.com|www\.w3\.org|ns\.adobe\.com|xml\.org|xmlpull\.org|"
    r"apache\.org|www\.apache\.org|json\.org|example\.(com|org)|localhost|"
    r"purl\.org|java\.sun\.com|xmlpull\.org|schema\.org|developer\.android\.com|"
    r"github\.com|goo\.gl|www\.example\.com)$"
)
JAVA_PKG_PREFIX = re.compile(r"^(com|org|net|io|android|androidx|java|javax|kotlin|kotlinx|dalvik|cn|tv)\.[a-z0-9_]+\.[a-z0-9_]+|^(android|java|javax|kotlin|kotlinx|libcore|persist|ro|sys|vendor|bi|dalvik|okhttp3|retrofit2|\d+[a-z]*)\.")


def uleb128(b, off):
    result = shift = 0
    while True:
        byte = b[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, off
        shift += 7


def dex_strings(data):
    if not data.startswith(b"dex\n"):
        return []
    size, off = struct.unpack_from("<II", data, 0x38)
    out = []
    for i in range(size):
        (soff,) = struct.unpack_from("<I", data, off + 4 * i)
        _, p = uleb128(data, soff)
        end = data.index(b"\x00", p)
        out.append(data[p:end])
    return out


def scan_endpoints(apk):
    urls, hosts = set(), set()
    dex_text = []
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            n = info.filename
            if info.file_size > 64 * 1024 * 1024:
                continue
            low = n.lower()
            if low.endswith((".png", ".jpg", ".webp", ".gif", ".mp4", ".ttf", ".otf", ".ogg", ".mp3", ".wav", ".9.png")):
                continue
            try:
                data = z.read(n)
            except Exception:
                continue
            if low.endswith(".dex"):
                dex_text.extend(dex_strings(data))
                continue
            for m in URL_RE.findall(data):
                urls.add(m.decode("latin-1"))
            for m in HOST_RE.findall(data):
                hosts.add(m.decode("latin-1"))
    for s in dex_text:
        for m in URL_RE.findall(s):
            urls.add(m.decode("latin-1"))
        if len(s) < 200:
            for m in HOST_RE.findall(s):
                hosts.add(m.decode("latin-1"))
    clean_hosts = set()
    for h in hosts | {re.sub(r"^[a-z]+://", "", u).split("/")[0].split(":")[0] for u in urls}:
        h = h.lower().strip(".")
        if not h or NOISE_HOST.match(h) or JAVA_PKG_PREFIX.match(h):
            continue
        clean_hosts.add(h)
    return sorted(urls), sorted(clean_hosts), dex_text


def run_rea(args, out_path):
    if os.path.exists(out_path) and os.path.getsize(out_path) > 200:
        return True
    p = subprocess.run([*REA, *args, "--format", "json"], capture_output=True, text=True, encoding="utf-8", errors="replace", shell=False)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(p.stdout if p.stdout.strip() else p.stderr)
    return p.returncode == 0


def pick_apk(d):
    apks = [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".apk")]
    if not apks:
        return None
    base = [a for a in apks if os.path.basename(a) == "base.apk"]
    return base[0] if base else max(apks, key=os.path.getsize)


def analyze(pkg):
    d = os.path.join(APKS, pkg)
    apk = pick_apk(d)
    if not apk:
        return pkg, {"error": "no apk pulled"}
    od = os.path.join(OUT, pkg)
    os.makedirs(od, exist_ok=True)
    res = {"apk": os.path.relpath(apk, ROOT)}
    if pkg not in SKIP_REA and "*" not in SKIP_REA:
        ok1 = run_rea(["inspect-android-package", apk], os.path.join(od, "manifest.json"))
        ok2 = run_rea(["search-android-classes", apk, ""], os.path.join(od, "classes.json"))
        res["rea_ok"] = ok1 and ok2
    else:
        res["rea_ok"] = "skipped"
    # endpoints from every APK in the set (base + splits)
    all_urls, all_hosts, sdk_hits = set(), set(), {}
    for f in os.listdir(d):
        if f.endswith(".apk"):
            u, h, dex = scan_endpoints(os.path.join(d, f))
            all_urls.update(u)
            all_hosts.update(h)
    with open(os.path.join(od, "endpoints.txt"), "w", encoding="utf-8") as f:
        f.write("# hosts\n" + "\n".join(sorted(all_hosts)) + "\n\n# urls\n" + "\n".join(sorted(all_urls)) + "\n")
    res["hosts"] = sorted(all_hosts)
    res["url_count"] = len(all_urls)
    # manifest facts
    mpath = os.path.join(od, "manifest.json")
    if os.path.exists(mpath):
        try:
            m = json.load(open(mpath, encoding="utf-8"))["normalized_result"]
            text = m["manifest"]["text"]
            res["version"] = m.get("version_name")
            res["shared_uid"] = (re.search(r'sharedUserId="([^"]+)"', text) or [None, None])[1]
            res["persistent"] = 'android:persistent="true"' in text
            res["permissions"] = m.get("permissions", [])
            res["sensitive_permissions"] = [p for p in res["permissions"] if SENSITIVE_PERMS.search(p)]
            comps = m["components"]["items"]
            res["boot_hooks"] = sorted({c["name"] for c in comps if any(a in ("android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.QUICKBOOT_POWERON", "com.tcl.action.BOOT_COMPLETED") or "BOOT" in a for a in c.get("actions", []))})
            res["network_hooks"] = sorted({c["name"] for c in comps if any("CONNECTIVITY" in a or "NETWORK_STATE" in a for a in c.get("actions", []))})
            res["services"] = sorted(c["name"] for c in comps if c["kind"] == "service")
            res["receivers"] = sorted(c["name"] for c in comps if c["kind"] == "receiver")
            res["providers"] = sorted(c["name"] for c in comps if c["kind"] == "provider")
            res["exported_services"] = sorted(c["name"] for c in comps if c["kind"] == "service" and c.get("exported") and not c.get("permission"))
            res["home_activity"] = "android.intent.category.HOME" in text
        except Exception as e:  # noqa: BLE001
            res["manifest_error"] = str(e)
    cpath = os.path.join(od, "classes.json")
    if os.path.exists(cpath):
        try:
            raw = open(cpath, encoding="utf-8").read()
            hits = sorted(set(re.findall(r'"((?:[A-Za-z0-9_$]+\.)+[A-Za-z0-9_$]+)"', raw)))
            res["class_names_total"] = len(hits)
            for name, pat in SDK_PATTERNS.items():
                found = [h for h in hits if re.search(pat, h)]
                if found:
                    sdk_hits[name] = found[:15] + ([f"... +{len(found) - 15} more"] if len(found) > 15 else [])
        except Exception as e:  # noqa: BLE001
            res["classes_error"] = str(e)
    res["sdk_hits"] = sdk_hits
    return pkg, res


def write_digests(summary):
    """Human-readable views of summary.json: per-package facts and a host -> packages index."""
    with open(os.path.join(OUT, "facts.txt"), "w", encoding="utf-8") as f:
        for pkg in sorted(summary):
            r = summary[pkg]
            f.write(f"== {pkg}  version={r.get('version')}  persistent={r.get('persistent')}  shared_uid={r.get('shared_uid')}\n")
            for key in ("sensitive_permissions", "boot_hooks", "network_hooks", "exported_services"):
                if r.get(key):
                    f.write(f"  {key}: {', '.join(r[key])}\n")
            if r.get("sdk_hits"):
                f.write(f"  sdks: {', '.join(sorted(r['sdk_hits']))}\n")
            f.write(f"  hosts: {len(r.get('hosts', []))}  urls: {r.get('url_count', 0)}\n\n")
    by_host = {}
    for pkg, r in summary.items():
        for h in r.get("hosts", []):
            by_host.setdefault(h, set()).add(pkg)
    with open(os.path.join(OUT, "hosts.txt"), "w", encoding="utf-8") as f:
        for h in sorted(by_host, key=lambda x: (".".join(reversed(x.split("."))))):
            f.write(f"{h}\t{', '.join(sorted(by_host[h]))}\n")


def main():
    global APKS, OUT, REA
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("packages", nargs="*", help="only analyze these package names")
    ap.add_argument("--apks", default=APKS, help="folder with one sub-folder of APKs per package (default: apks/)")
    ap.add_argument("--out", default=OUT, help="output folder (default: rea/)")
    ap.add_argument("--workers", type=int, default=3, help="parallel REA runs (default: 3)")
    ap.add_argument("--strings-only", action="store_true", help="skip REA/JADX; only run the DEX/resource string scan")
    a = ap.parse_args()
    APKS, OUT = os.path.abspath(a.apks), os.path.abspath(a.out)
    if not os.path.isdir(APKS):
        raise SystemExit(f"APK folder not found: {APKS} (run scripts/pull-apks.ps1 first)")
    if a.strings_only:
        SKIP_REA.add("*")
    else:
        jar = os.environ.get("REA_JADX_MCP_JAR", "")
        if not jar or not os.path.isfile(jar):
            raise SystemExit("REA_JADX_MCP_JAR is not set to an existing jadx-headless-mcp-0.7.1-all.jar "
                             "(see README > Prerequisites), or pass --strings-only.")
    REA = rea_command()
    os.makedirs(OUT, exist_ok=True)

    pkgs = sorted(p for p in os.listdir(APKS) if os.path.isdir(os.path.join(APKS, p)))
    if a.packages:
        pkgs = [p for p in pkgs if p in a.packages]
    summary = {}
    with cf.ThreadPoolExecutor(max_workers=a.workers) as ex:
        for pkg, res in ex.map(analyze, pkgs):
            summary[pkg] = res
            print(f"{pkg}: rea={res.get('rea_ok')} hosts={len(res.get('hosts', []))} sdks={list(res.get('sdk_hits', {}).keys())}", flush=True)
    prev = {}
    sp = os.path.join(OUT, "summary.json")
    if os.path.exists(sp):
        prev = json.load(open(sp, encoding="utf-8"))
    prev.update(summary)
    json.dump(prev, open(sp, "w", encoding="utf-8"), indent=1)
    write_digests(prev)
    print(f"Wrote {sp}, facts.txt and hosts.txt")


if __name__ == "__main__":
    main()
