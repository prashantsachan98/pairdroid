# Dependency license review

Initial review, 2026-10-24. No AirDroid code, artwork, SDK, commercial API, or binary
is incorporated. PairDroid source is MIT. Build tools and reverse-engineering tools
are development tools, not bundled app functionality.

| Dependency | Scope | License / compatibility |
| --- | --- | --- |
| Flutter framework / Dart SDK | UI/runtime/build | BSD-3-Clause; permissive, retain upstream notices |
| Kotlin stdlib / AndroidX / Material Android artifacts from Flutter embedding | Android runtime/build | Apache-2.0; permissive, retain notices |
| Flutter pub transitive collection, async, characters, material_color_utilities, meta, vector_math and related SDK libraries | Flutter runtime | BSD-style permissive licenses; pinned versions in pubspec.lock |
| flutter_test, flutter_lints/lints and test transitive packages | Tests/development | BSD-3-Clause / permissive; not app feature SDKs |
| JUnit 4.13.2 | JVM tests only | EPL-1.0; unmodified test-only dependency, not shipped in app |
| AndroidX Test runner/ext JUnit | Instrumentation only | Apache-2.0; not shipped in production app |
| REA 6.7.0 | Local development tool only | MIT; repository dependency notices remain upstream |
| JADX headless provider 0.7.1 / JADX | Local analysis only | Apache-2.0 upstream; not shipped in app |
| Gradle / Android Gradle Plugin | Build only | Apache-2.0; SDK tool usage remains subject to its tool license |

The resolved Dart license-file inventory (25 files, with SHA-256 digests) is in
[evidence/dart-license-inventory.json](evidence/dart-license-inventory.json).

The application adds no third-party Dart runtime plugin. Android platform APIs
(Keystore, TLS, NSD, SQLite, UsageStats, DPM, MediaProjection/ImageReader) provide
native capabilities. `flutter pub deps` and Gradle dependency reports reproduce
resolved graphs. Flutter's LicenseRegistry supplies bundled package notices;
review NOTICE files for engine/transitive binaries and package the corresponding
notices when distributing. This initial inventory is not a complete release SBOM;
a complete resolved artifact notice export remains a release requirement.
