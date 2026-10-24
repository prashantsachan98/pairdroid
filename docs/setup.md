# Setup and troubleshooting

## Build and install

Use Flutter stable (verified here with 3.35.6/Dart 3.9.2), JDK 17, SDK 37, Android
build tools, and ADB. `flutter doctor -v` should find the Android toolchain.
The wrapper uses Gradle 8.13 and AGP 8.13.1. See the compatibility note on AGP's
compile-SDK warning. First dependency resolution needs internet on the build host;
the installed app has no internet service dependency.

```sh
flutter pub get
flutter build apk --debug --flavor child
flutter build apk --debug --flavor parent
adb -s CHILD_SERIAL install -r build/app/outputs/flutter-apk/app-child-debug.apk
adb -s PARENT_SERIAL install -r build/app/outputs/flutter-apk/app-parent-debug.apk
```

Two package IDs allow both apps on a test device, though physical acceptance needs
two separate phones. Release signing must be replaced with the publisher's own
keystore before distributing release builds.

## Permissions and pairing

Child: allow notifications and (Android 17+) local-network access, start the
service, open pairing. Parent: allow local-network access, discover or enter IP/
port, copy full child fingerprint directly from Child, enter its code. Compare
parent fingerprints on both displays and approve on Child within 45 seconds.
Codes expire after two minutes and five attempts. No account is involved.

Child Usage access is an optional special permission in Android Settings, required
for usage summaries. Grant it to **PairDroid Child**, then return to the app. A
pairing grant never substitutes for Usage access or capture consent.

To share: Parent requests Live view; Child taps Review & allow and grants the
system capture prompt. Both Child screen and persistent notification offer Stop.
Leaving the Parent live tab requests teardown; loss of frame polling times out.

## Legitimate managed-device evaluation

Ordinary installation is advisory. For DPM suspension, provision a dedicated test
phone or managed work profile through Android's supported device management
workflow. Do not run owner commands on someone's personal device without their
informed setup. A new/factory-reset test device without accounts may permit:

```sh
adb shell dpm set-device-owner org.pairdroid.pairdroid.child/org.pairdroid.pairdroid.PolicyAdmin
```

Android may reject provisioning based on device state, accounts, OEM policy or
profile rules. Do not try to bypass those checks. Profile-owner mode controls only
that profile. No factory reset or owner provisioning is performed by this project.

Keep the visible Child service running for time-based evaluation. Stopping it can
leave existing OS suspensions in effect until it runs again. Revoke on Child to
clear PairDroid policy and release tracked suspensions before deprovisioning.

## Offline networks

- Test airplane mode with Wi-Fi re-enabled and no SIM/cellular route.
- Disconnect the router's WAN cable; the app must still pair and exchange status.
- Guest Wi-Fi/client isolation can prevent peer TCP connections. No application
  fallback can bypass router isolation; change the router/network configuration.
- Multicast filtering can hide NSD; copy IP/port manually.
- Android may prefer cellular when Wi-Fi has no internet. Parent explicitly binds
  its socket to the matching Android Network (hotspot uses local bind fallback).
- IPv6-only Wi-Fi, routed LANs/VPNs and custom OEM interface names are unsupported.
- When the IP changes, leave discovery running or edit the endpoint in Overview.
  Any endpoint still has to pass the saved certificate pin.
- If permissions are denied, grant them in App permissions. OEM power settings
  can terminate foreground services; restart management from the visible Child UI.

## REA

See the investigation report for installed versions and evidence. General setup:

```sh
npx rea-agents setup --client codex --skill=false
```

Use a supported Node release (22.19+ in the 22 series, 24.11+, or supported newer
major). Download only the JADX headless release explicitly pinned by REA, verify
its digest, configure `REA_JADX_MCP_JAR`, and restart the agent after registration.
`scripts/rea_probe.py --help` explains how to verify MCP and inspect an authorized
APK. Do not commit proprietary artifacts or private device data.
