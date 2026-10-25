# Security and privacy

This prototype has not had an independent security audit. It is suitable for
engineering evaluation, not an assurance of reliable or tamper-resistant control.

## Trust and authorization

Each app flavor generates a non-exportable EC signing identity in AndroidKeyStore.
The SHA-256 pin covers its entire DER certificate. TLS verifies possession of the
corresponding key. Parent verifies the child's out-of-band pin before sending its
one-time code. Child verifies its stored parent pin on every private command.

Child explicitly opens enrollment (120s, five attempts), compares the requesting
parent fingerprint and approves it. Unknown client certificates can reach only
the pairing gate. An untrusted discovery advertisement cannot authorize a device.
Certificate substitution fails pinning; an enrollment code without child approval
cannot authorize private commands. TLS handles transcript protection and replay
resistance. There are no bearer tokens or custom encryption primitives.

The short code is not a substitute for fingerprint verification. A person who
convinces the child to approve the wrong fingerprint can become the authorized
parent. Device names are untrusted labels. Approval requires physical verification
on both displays. One child supports one parent at a time.

## Storage and lifecycle

Trust/policy state is encrypted with Keystore AES-GCM, atomically written, and
excluded from Android backup. Private keys never pass through Flutter. The SQLite
journal is app-private OS-sandboxed storage (not separately encrypted); it records
management events and package counts, not message contents. Uninstall clears app
state. Key rotation requires stopping management and removing all pairings; the
new certificate must be explicitly paired again.

Revocation removes trust and stops sharing before attempting policy cleanup. Each
new command checks trust. Already returned data cannot be recalled. In-flight app
inventory/usage responses can complete before a concurrent revocation obtains
the dispatch lock. The bounded workers, queues, read sizes, deadlines, and pairing
attempt cap reduce trivial resource abuse but do not make this a hardened public
server. Denial of service by hostile LAN clients remains possible. Do not expose
or forward its port.

## Transparency

No camera, microphone, notifications-content reader,
location permission, message extraction, telemetry, or cloud service.
The Child flavor ships an Accessibility Service ("System Services") that only calls
`takeScreenshot()` for approved live view (API 30+); it subscribes to no
accessibility events and reads no content.
Child grants Usage access in Android Settings. Each capture starts only after a
parent request, child action, and a foreground service (plus Android MediaProjection
consent on API < 30). Child UI and notification provide Stop. Parent stop, local
revocation, capture-source termination, or viewer timeout release capture resources.

Frames stay in memory and are sent only to the authorized certificate. No
recording is implemented. Protected Android content can render black. Viewer
round-trip timings are not a claim of glass-to-glass video latency. Notification
permission is required to start visible Child management on Android 13+.

## Limits to review before production

- IPv4 matching interface/subnet is a reachability restriction, not proof that two
  devices share one physical router; local routing, VPNs, compromised OSes and
  administrator port forwarding can change topology. No remote relay is supplied.
- Hardware-backed Keystore availability varies by device; StrongBox is not required.
- Device wall-clock changes affect schedules and reports. Policy evaluation can be
  delayed or killed by Android/OEM power controls.
- Multiple parents, secondary users/profiles, exhaustive package visibility,
  hardened rate limiting, request pagination, revocation races under heavy load,
  and unattended fleet provisioning need further work.
- No security-maintainer contact is configured yet. Report findings privately to
  the repository owner; omit personal data and keys from all shared logs.
