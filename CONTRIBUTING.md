# Contributing

Use Flutter stable, Java 17, Android SDK 37, and the checked-in Gradle wrapper.
Run `flutter analyze`, `flutter test`, and native unit tests before submitting.
For native changes, run the Android instrumentation tests on a disposable device.
See [setup](docs/setup.md) and [testing](docs/testing.md).

Keep changes small and connect UI to actual native services. Describe the trigger,
new behavior, Android versions tested, and any acceptance checks still blocked.
Never commit device identities, pairing codes, private usage logs, or third-party
APKs. Do not add cloud dependencies, telemetry, stealth behavior, Accessibility
workarounds, or proprietary implementation code. Sensitive capabilities require
visible child controls and OS consent. Document any new permission or dependency
license in the corresponding documentation.

Report security issues privately to the repository maintainer using the repository
host's private vulnerability reporting facility when configured. This starter
repository has no configured security contact; do not publish private family data
in issues.
