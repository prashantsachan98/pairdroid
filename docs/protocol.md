# LAN protocol v1

Transport: mutually certificate-authenticated TLS 1.2/1.3, one request and response
per TCP connection, UTF-8 JSON followed by LF. No HTTP or external signaling.
The Child listens on an OS-assigned high port and advertises `_pairdroid._tcp.`
with TXT `v=1` and `id=<certificate SHA-256>`. That advertisement is untrusted.
IPv4 RFC1918 addresses on matching Wi-Fi/hotspot/Ethernet interface prefixes only.
The parent binds sockets to the matching Android Network where available; hotspot
fallback binds the local interface address. Public IPs, hostnames, VPN interfaces,
IPv6, loopback, and routed subnets are not supported by this version.

## Enrollment

Child opens a 120-second window with a CSPRNG six-digit code (five attempts).
Parent copies the child's full SHA-256 DER certificate fingerprint out of band,
then connects with that exact certificate pin and its own client certificate.

```json
{"v":1,"cmd":"pair","name":"Parent","code":"012345"}
```

Child displays the requesting parent's full certificate fingerprint. The user
compares it against the Parent screen and approves or denies. The server waits at
most 45 seconds. Only explicit approval writes the parent's pin to encrypted
storage; expiry, timeout, denial, service stop, or revocation prevents enrollment.
One parent per child, multiple children per parent. The code is consumed after
successful approval. Opening a window cannot replace an existing parent.

Unknown certificates may complete TLS only to reach this enrollment gate. No
private command is dispatched unless the presented certificate matches the saved
parent pin. Client certificate proof-of-possession occurs in TLS, not in JSON.
Neither discovery hints nor caller-provided device names authorize anything.

## Commands

Every request includes `v:1` and `cmd`. Supported commands after enrollment:

| Command | Payload | Result |
| --- | --- | --- |
| `status` | none | Device, battery, permissions, policy, sharing state, timestamp |
| `apps` | none | Visible launcher apps: package, label, PNG icon as base64 |
| `usage` | none | Seven child-local calendar days of package foreground estimates |
| `policy` | `policy` | Canonical persisted policy, enforced/advisory capability |
| `alerts` | none | Latest 50 local audit/usage alerts |
| `shareRequest` | none | Pending child approval; never starts capture |
| `frame` | none | Latest in-memory JPEG, dimensions, capture time, quality |
| `stopSharing` | none | Ends capture or cancels pending request |
| `unpair` | none | Revokes authorization, terminates sharing, clears policy |

```json
{"v":1,"cmd":"policy","policy":{"limits":{"org.example.game":30},"start":1320,"end":420,"allowed":["org.example.reader"]}}
```

Minutes are 0–1439 child-local wall-clock time. Equal start/end disables downtime;
start after end wraps midnight. Zero app limit disables that limit. Always-allowed
apps override downtime and app limits. Child stamps the update; server receipt
order determines policy order. There is no offline parent edit queue or merge.
Changes cannot be acknowledged as saved until the child persists them.

Responses:

```json
{"ok":true,"data":{"name":"Example device","at":1792800000000}}
{"ok":false,"error":"Unauthorized certificate"}
```

Requests are limited to 64 KiB, responses to 8 MiB. Four server workers plus eight
queued connections bound concurrency; TLS/read timeouts bound waiting. Larger
inventories are capped at 500 launcher apps. A busy enrollment can be retried.

The Parent polls status every five seconds while a device page is open. Each poll
checks pins again. NSD identity hints can update an endpoint after an IP change,
but the new endpoint must still present the saved certificate. Manual endpoint
editing is available. No payload is queued to a disconnected device.

Screen transport is JPEG pull, at most 2.5 captured frames/s at 540-pixel longest
edge, adaptive JPEG quality 30–60. Parent polls every 500 ms in Live view, displays
its measured frame RPC round-trip time, and holds no recording. This is not an
end-to-end latency measurement or a hardware video codec. No viewer frame polls
for 15 seconds triggers stop on the next five-second service tick. Explicit Stop,
revocation, service stop, and Android projection termination release resources.
