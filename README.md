# PairDroid

An independent, MIT-licensed Android parental-control prototype that communicates
on the same local Wi-Fi or supported hotspot. One Flutter project produces Parent
and Child apps; Android integrations and the LAN service use Kotlin. No account,
Firebase, signaling server, analytics, subscription, or internet connection is
required at runtime.

This is an initial implementation, not a production-ready parental-control product.
See [test evidence and acceptance status](docs/testing.md) before relying on it.
Physical two-phone acceptance remains required.

## Run

Prerequisites: Flutter 3.35.6 / Dart 3.9.2 or compatible stable, JDK 17, Android SDK
37 with build tools, and Android phones running Android 8.0 or newer.

```sh
flutter pub get
flutter run --flavor child -d CHILD_DEVICE_ID
flutter run --flavor parent -d PARENT_DEVICE_ID
```

```sh
flutter build apk --debug --flavor child
flutter build apk --debug --flavor parent
flutter analyze
flutter test
cd android
./gradlew :app:testChildDebugUnitTest
./gradlew :app:connectedChildDebugAndroidTest
```

APKs: `build/app/outputs/flutter-apk/app-child-debug.apk` and
`build/app/outputs/flutter-apk/app-parent-debug.apk`. Debug APKs are for development.
Configure your own release signing before distribution; generated release signing
currently uses the debug key.

## Connect

1. Connect both phones to the same Wi-Fi. Internet access may be disabled.
2. On Child, allow network/notification permissions, start the local service, and
   open the pairing window. Usage access is optional for pairing.
3. On Parent, find nearby devices or enter the child's displayed IPv4 address and
   port manually. Enter the **full fingerprint from the Child screen** and its
   six-digit code. Discovery advertisements do not establish trust.
4. Request pairing. On Child, compare the parent fingerprint shown on both phones
   and explicitly approve. A code alone never authorizes the parent.
5. Open the paired device. Grant Usage access on Child for reports. For live view,
   request it on Parent, approve on Child, and accept the Android capture dialog.
6. Stop sharing on Child or its notification at any time. Either device can revoke
   pairing; Child clears the locally stored policy when revoking.

Normal installs provide **advisory** limits. Enforceable package suspension needs
legitimate device-owner/profile-owner provisioning on a managed device. No
Accessibility Service is used. Local policy evaluation requires the visible Child
service; OS suspension can outlive the service, and force-stop/reboot is not a
reliable enforcement environment. See [platform limits](docs/android-compatibility.md).

## Contents

- [Setup, provisioning, and troubleshooting](docs/setup.md)
- [Architecture](docs/architecture.md)
- [Protocol and enrollment](docs/protocol.md)
- [Security model](docs/security.md)
- [Android permissions and compatibility](docs/android-compatibility.md)
- [Implemented, partial, and unsupported features](docs/feature-matrix.md)
- [REA investigation and evidence](docs/reverse-engineering.md)
- [Supplied AirDroid controller bundle security review](docs/airdroid-security-review.md)
- [Supplied AirDroid Kids APK security review](docs/airdroid-kids-security-review.md)
- [Tests and remaining acceptance work](docs/testing.md)
- [Dependency license review](docs/dependencies.md)

The interface and implementation are original. AirDroid is referenced only as a
publicly documented workflow benchmark. Supplied controller and Kids artifacts were
later reviewed separately for security metadata; no proprietary implementation
code is incorporated.
