# REA investigation — 2026-10-24

Update: a subsequently supplied AirDroid **controller** bundle has now received a
limited static security review using REA/JADX and Android SDK inspection tools.
See [the artifact review](airdroid-security-review.md) for hashes, observed facts,
evidence and limitations. The initial investigation below describes the earlier
phase when no third-party artifact had been provided.

A subsequently supplied Kids APK (`com.sand.airdroidkids`, version 1.8.5.0) has also
been inspected with REA/JADX. Its separate [static security review](airdroid-kids-security-review.md)
covers signatures, permissions, component exposure, dependencies and configuration;
it does not analyze capture-consent bypasses or reproduce hidden monitoring.

## Scope and provenance

No authorized AirDroid APK, installation, account, or physical device was supplied.
Consequently there was **no binary/runtime reverse engineering of AirDroid** and
no claim about its proprietary internals. Public vendor documentation provides
workflow evidence; public Android documentation provides independent API choices.
No licensing, authentication, encryption, or anti-tamper mechanism was bypassed.

## REA setup and actual use

Official source: [morluto/rea](https://github.com/morluto/rea),
[installation instructions](https://github.com/morluto/rea/blob/main/docs/installation.md).
REA 6.7.0 was downloaded with npm. Host Node 20.19.3 is below REA's supported engine
range; Node 22.23.4 was installed through npm's package runner for REA. Java 17 and
Android SDK 37 are installed; ADB 34.0.4 is available. JADX was initially absent.

Ran official setup for Codex only (no commercial Hopper, no unrelated agents):

```sh
npx --yes --package=node@22 --package=rea-agents rea setup --client codex --skill=false --dry-run --format json
npx --yes --package=node@22 --package=rea-agents rea setup --client codex --skill=false --yes --format json
```

The MCP registration was then pointed at the installed supported Node runtime.
JADX headless 0.7.1 was fetched from the exact release pinned in REA's
`dist/android/JadxRelease.js` and its SHA-256 verified:
`6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`.
Its local path is configured as `REA_JADX_MCP_JAR`. Tool binaries are ignored by git.

`scripts/rea_probe.py` initialized the real stdio MCP server and enumerated **207
tools**, including Android inspection and ADB operations. Since the agent's live
tool catalog does not hot-load newly registered servers, this local stdio client
was used to call REA directly. Restart Codex to expose its newly registered tools
normally. The probe successfully called:

- `inspect_android_package` on **our own built Child APK**, with JADX, confirming
  package `org.pairdroid.pairdroid.child`, minSdk 26, targetSdk 37, declared
  permissions, and class/resource inventory. Static metadata is not runtime proof.
- `inspect_adb_device` on the disposable Android 16 emulator, confirming API 36 and
  its emulator identity. This is not an observation of AirDroid behavior.

Evidence: [setup](evidence/rea-setup.json), [initial readiness](evidence/rea-doctor.json),
[MCP discovery](evidence/rea-mcp.json), [actual REA results](evidence/rea-self-analysis.json).
The initial doctor predates JADX configuration; the successful analysis result
records the actual provider version and digest. Probe results include artifact
hashes for the build inspected at the time; subsequent source rebuilds change hashes.

## Evidence-backed workflow matrix

Vendor sources:
[V1 user guide](https://www.airdroid.com/guide/parental-control/),
[V2 enrollment/removal](https://help.airdroid.com/hc/en-us/articles/5658781082523-How-to-bind-remove-devices-in-AirDroid-Parental-Control),
[V3 overview](https://help.airdroid.com/hc/en-us/articles/4677178239131-AirDroid-Parental-Control-Overview),
[V4 getting started](https://help.airdroid.com/hc/en-us/articles/4507395498779-What-is-AirDroid-Parental-Control-and-how-to-start-using).
These are documented behavior, not hands-on observations. Confidence is high for
feature existence, low/unknown for undocumented transport and implementation.

| Feature | Public evidence | Mechanism hypothesis / uncertainty | OS limit | Independent implementation |
| --- | --- | --- | --- | --- |
| Enrollment/removal | V2 describes account login, binding code, child enrollment, removal | Vendor backend/session design unknown | OS permissions still separate | Account-free mutual TLS, one-time code, explicit fingerprint approval/revocation |
| Roles and architecture | V4 distinguishes parental controller and Kids install | Vendor component architecture unknown | Different privileges in managed and normal modes | Flutter flavors, shared Kotlin integrations |
| Permission onboarding | V1/V3 describe child setup and permission-dependent capabilities | Specific service internals unverified | User grants and revocation are authoritative | Visible permission checklist and Android Settings |
| Discovery/connection | No verified LAN-only discovery behavior in supplied evidence | Do not infer vendor mDNS, ports, TLS or offline operation | Router isolation/multicast filtering | NSD with pinned endpoint fallback; manual IPv4 |
| App inventory | V4 lists App List | PackageManager plausible, not verified vendor implementation | Package visibility since Android 11 | Launcher query with package/label/icon |
| Usage reports | V4 lists daily events | UsageStatsManager plausible; vendor accounting unknown | Special usage access, event retention | Seven-day UsageEvents replay, disclosure of estimates |
| App limits/downtime | V1/V4 document limits/restrictions | Vendor enforcement/privileges unknown | Ordinary apps cannot arbitrarily suspend others | Advisory notification; legitimate DPM owner suspension only |
| Screen view | V1/V3 document mirroring for Android child | MediaProjection plausible; codec/transport unverified | Fresh system consent, visible foreground service | Child-approved MediaProjection and encrypted JPEG pull |
| Foreground/lifecycle | No direct vendor service observation | Do not infer stealth, boot, battery exemptions | Android/OEM background limits | Explicit visible service, manual restart, bounded sharing watchdog |
| Errors/reconnection | V3 notes permissions may need re-grant | Error recovery internals unknown | IP changes, permission loss, OS termination | Heartbeat, visible errors, pin-preserving endpoint rediscovery/manual update |

API mappings are design decisions, **not recovered AirDroid implementation facts**.
See [compatibility](android-compatibility.md) for API sources and restrictions.

## Follow-up with an authorized artifact

If the user provides an authorized APK or installation, record package/version,
SHA-256 and scope first; use REA package/class/method/reference inspection, then
ADB observation of enrollment, permission-denied, lifecycle and reconnect paths.
Keep evidence references and separate observations from inferences. Never copy
source, assets, secrets or protected implementation details. No proprietary APK
is necessary to run or build PairDroid.
