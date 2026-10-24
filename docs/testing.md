# Test record and acceptance status

Environment: 2026-10-24, Flutter 3.35.6/Dart 3.9.2, Java 17, SDK 37, Gradle 8.13,
AGP 8.13.1; Android 16/API 36 x86_64 emulator (16 KiB page image). No physical
Android phones are attached. Tests on one emulator are not a substitute for two
phones and a Wi-Fi router.

## Automated evidence

- `flutter analyze`: no issues.
- `flutter test --reporter expanded`: 3 passing tests. Valid/invalid pairing input,
  propagation of native authorization errors, empty parent dashboard → pairing UI.
- `:app:testChildDebugUnitTest`: 4 passing JUnit tests. Bounded/truncated framing,
  IPv4 private-address guard, cross-midnight downtime, limits/allowlist boundaries.
- `:app:connectedChildDebugAndroidTest`: 1 passing Android integration test with
  actual AndroidKeyStore identities, certificate-authenticated TLS and native
  service logic. The test covers multiple assertions: unpaired status/apps/policy
  rejection, wrong server fingerprint, deny/approve gate, secondary certificate
  rejection, real launcher-app inventory, permission-gated seven-day usage query,
  inability to obtain frames before capture approval, policy round-trip/storage
  across listener restart, revocation, and enrollment attempt cap.
- Parent and Child debug APKs compile. Debug signing is not a distribution setup.
- Child UI smoke test rendered successfully, displayed the real Android notification
  permission prompt, and started its actual ongoing connected-device foreground
  service. See [UI smoke record](evidence/ui-smoke.txt).
- Real REA MCP discovery, JADX inspection of our own APK, and emulator ADB
  inspection completed; see `docs/evidence/rea-self-analysis.json`.

Native logs: [integration](evidence/android-instrumentation.log). Generated detailed
reports live under `build/app/reports/` and `build/app/outputs/androidTest-results/`.
The initial failed installation log is retained as `android-tests.log`: the saved
emulator data partition was full, so zero tests executed in that run. A fresh
emulator under `/tmp/pairdroid-avds` fixed the environment issue. A subsequent
TLS failure exposed Conscrypt's need for prehashed ECDSA (`DIGEST_NONE`) permission
in the Keystore identity. That implementation issue was fixed and the real test
passed. No saved user emulator data was wiped.

An additional [two-app UI smoke test](evidence/two-app-ui-smoke.txt) paired the
distinct Parent and Child packages through their real Flutter screens, verified
both fingerprints, selected Child approval, and displayed live authenticated status.
It also requested sharing, selected Child approval and Android full-screen consent,
received/rendered real JPEG frames, and verified that Parent Stop cleared the image
and Child reported capture stopped. One observed frame-request RTT was 114 ms;
this is emulator RPC timing, not glass-to-glass latency or a device benchmark.
Both packages ran on the same emulator; this is not a two-phone or NSD router test.

The integration test runs two separate certificate identities inside a single
disposable test app process. It exercises the actual native TLS listener and Android
APIs, but does not by itself validate the complete two-app UI exchange, real Wi-Fi NSD,
the full MediaProjection lifecycle matrix, or DPM package suspension.

## Mandatory acceptance checklist

| Criterion | Result / remaining dependency |
| --- | --- |
| Both apps build | Passed, debug artifacts provided |
| Two phones discover or manually connect | Blocked: no physical phones/router available |
| Pairing requires child approval | Passed in actual Android TLS integration and two-app UI on emulator; physical test pending |
| Unpaired LAN client private-command rejection | Passed with distinct Android Keystore client identities |
| No-internet operation | Architecture has no cloud dependency; disconnected-router test blocked on hardware |
| Live status | Actual two-app live status validated on emulator; two-phone heartbeat test pending |
| Authorized usage | Real UsageEvents query and seven-day response validated in emulator; real usage accuracy pending |
| Policy sync | TLS policy round-trip and stored readback passed |
| Offline policy persistence/enforcement | Stored policy survives listener restart; physical offline/DPM evaluation blocked |
| Capture only after child/system consent | Frame gate plus real two-app Child/system consent and frame delivery passed on emulator; physical lifecycle matrix pending |
| Capture stops/revokes | Parent Stop cleared frame and ended Child capture on emulator; revoke/disconnect/rotation during capture pending |
| Safe reconnect/IP change | Pin check tested; real network/IP churn pending |
| No mandatory cloud | Source/runtime dependency design is local-only; no Firebase/telemetry SDK |
| Sources/build/setup | Included |

## Physical-device test procedure (required before a release)

1. Install each variant on separate supported phones. Pair while checking both
   fingerprints; repeat denial, wrong code, expiry, wrong certificate and revoked
   client attempts. Verify no private data reaches any unpaired certificate.
2. Test manual IP and NSD independently, then airplane mode with Wi-Fi re-enabled,
   router WAN unplugged, router guest/client isolation and multicast filtering.
   Isolation should show a clear failure; multicast filtering should leave manual
   IP usable. No app fallback should claim to bypass router isolation.
3. Generate known foreground app use, grant/revoke Usage access, compare daily and
   weekly estimates, app labels/icons and timestamps. Check midnight/timezone/DST,
   multi-window, reboot, missing events and denied permission states.
4. Save policy while connected. Disconnect Wi-Fi and verify advisory alerts. On a
   legitimately provisioned dedicated managed test device, verify actual eligible
   package suspension, downtime, daily reset, allowlist, refused packages and
   cleanup on revocation. Stop/kill the service and document delayed evaluation.
5. Request screen view. Verify no frames before Child approval plus system consent.
   Test denial/cancel, every Stop surface, leaving viewer, Android projection stop,
   revoke, unplug Wi-Fi, rotate the child, app-window capture and FLAG_SECURE content.
   Confirm notification visibility and that stale images are cleared.
6. Measure frame RPC round-trip time separately from glass-to-glass latency (film a
   running timer on both devices). Record device models, Android versions, frame
   rate, CPU/battery use, dropped frames and resolution. No latency target is yet
   verified; this JPEG preview is not a completed low-latency video implementation.
7. Change DHCP address and toggle Wi-Fi/hotspot. Verify rediscovery uses the existing
   pin, manual reconnect works when NSD is filtered, and revocation stays revoked.
8. Test Android 17 local-network permission denial/re-grant and OEM battery
   management on representative physical devices. Older Android versions remain
   unvalidated beyond API-compatible compilation.
