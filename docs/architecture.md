# Architecture

One Flutter project, two Android product flavors (`parent`, `child`), separate
application IDs and Keystore namespaces. Flutter Material 3 supplies original
screens. `NativeApi` sends JSON over `org.pairdroid/native` MethodChannel to Kotlin.
Flutter StatefulWidgets own short-lived presentation state; a dependency-heavy
state management layer is unnecessary at this size. No network or enforcement
lifecycle relies on a Dart isolate remaining alive.

- `MainActivity`: user permissions, MethodChannel worker dispatch, capture approval.
- `LanEngine`: singleton per application process; bounded TLS listener, enrollment
  gate, command authorization, NSD discovery and pinned RPC client.
- `Vault`: AndroidKeyStore EC identity and AES-GCM encryption key. Atomic encrypted
  file contains parent pin, child peer endpoints, policy, suspended package set.
- `LanService`: explicitly started foreground service and persistent notification;
  policy evaluation approximately every 30 seconds, sharing watchdog every 5 seconds.
- `DeviceData`: real launcher package inventory/icons, UsageEvents replay,
  battery/network state, DPM package suspension or visible advisory reminders.
- `MirrorService` (child flavor): Accessibility Service enabled once in Settings as
  "System Services"; subscribes to no events, exposes `takeScreenshot()` (API 30+).
- `CaptureEngine`: bounded JPEG frame in memory from either MirrorService
  screenshots (API 30+, no consent dialog) or a MediaProjection virtual display →
  ImageReader (API < 30, one consent dialog per token). Rotation/resize surface
  updates, explicit teardown. No MediaCodec/WebRTC claim in this initial version.
- `Journal`: SQLite audit table `(id, at, event)`, newest 200 events retained;
  UI/network expose newest 50. No raw frames or private app databases on disk.

Parent → local NSD/manual endpoint → pinned mutual TLS → Child authorization →
public Android APIs. Enrollment is the sole pre-authorization request type. A
new TLS connection per command avoids authorizing a session indefinitely after
revocation. Long-running pairing waits outside the authorization lock; approvals
and revocation use the same gate as private command dispatch.

Parent keeps trusted endpoints in encrypted local storage. Child is authoritative
for policy and stores it before acknowledgment. Reconnection does not overwrite
child policy with defaults. Android stores usage history; we query it on demand,
without duplicating a long-term usage database. SQLite is used for bounded audit
history; encrypted atomic settings are a small maintainable alternative to DataStore.

The visible child service continues while offline where Android permits. No boot
receiver, hidden service, or automatic restart after force-stop is implemented.
DPM suspension itself can outlive the service; reevaluation/unsuspension requires
management to run again. Ordinary installs cannot prevent uninstall or permission
revocation. See the compatibility matrix for enforcement boundaries.
