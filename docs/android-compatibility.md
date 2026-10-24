# Android compatibility and permissions

Build: minSdk 26 (Android 8), compile/target SDK 37 (Android 17), JDK 17.
SDK 37 is installed on this host. AGP 8.13.1 builds it but warns that its tested
maximum is API 36.1. The warning is intentionally retained. A supported AGP upgrade
and Android 17 device validation remain release prerequisites; older Flutter
3.35.6 cannot be assumed compatible with arbitrary AGP 9 upgrades.

| Android | Relevant behavior | Implementation / validation |
| --- | --- | --- |
| 8–9 / 26–28 | Background limits; notification channels and foreground service | Minimum supported; not device-tested here |
| 10–11 / 29–30 | MediaProjection service type; package visibility on 11 | Launcher-intent queries, no QUERY_ALL_PACKAGES; not device-tested |
| 12 / 31–32 | Foreground start restrictions | Start only from visible Child action; not device-tested |
| 13 / 33 | POST_NOTIFICATIONS runtime grant | Required by this app for visible Child management |
| 14 / 34 | Per-session capture token, callback, service type permissions | New consent each session; one virtual display per token |
| 15 / 35 | Projection/background lifecycle restrictions | No boot-started projection; visible user action |
| 16 / 36 | Optional local-network protection experiments | Target-37 build tested on API-36 emulator; optional opt-in permission compatibility unverified |
| 17 / 37 | ACCESS_LOCAL_NETWORK runtime protection for target 37+ | Declared and requested on API 37+; device test pending |

| Permission / access | Role | Why / how granted |
| --- | --- | --- |
| INTERNET | Both, required | TCP/TLS on LAN; does not require actual internet |
| ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE | Both | Link status and matching LAN Network |
| CHANGE_WIFI_MULTICAST_STATE | Child | NSD multicast lock and connected-device FGS prerequisite |
| ACCESS_LOCAL_NETWORK | Both on Android 17+ | Runtime consent for broad LAN discovery and sockets |
| POST_NOTIFICATIONS | Child on Android 13+ | Visible management/sharing and limit reminders |
| PACKAGE_USAGE_STATS | Child, optional | Special user grant in Usage access Settings; reports unavailable without it |
| FOREGROUND_SERVICE / CONNECTED_DEVICE | Child | Explicit visible LAN service; manifest normal permissions |
| FOREGROUND_SERVICE_MEDIA_PROJECTION | Child, optional | Separate system capture approval per session |
| BIND_DEVICE_ADMIN | Admin receiver system gate | Receiver protection only; not ordinary runtime privilege elevation |

Child-only usage, notification and foreground-service permissions, management
service, admin receiver and launcher package queries live in the Child flavor
manifest. Parent declares only network-related permissions.

## Restriction capabilities

Normal installation: read granted usage, synchronize policy, show advisory reminders;
cannot suspend apps, enforce hard screen-time limits, prevent uninstall, or prevent
permission revocation. No Accessibility workaround is present.

Legitimate device owner/profile owner: `DevicePolicyManager.setPackagesSuspended`
can suspend eligible packages in its managed scope. Android may refuse protected
or administrative apps. PairDroid tracks successful suspensions, unsets obsolete
ones, protects itself/Settings/launcher/default dialer, and exempts configured
always-allowed apps. A profile owner controls its profile, not the whole personal
device. Evaluation runs about every 30s while the visible service runs; not a hard
real-time quota. Suspension may persist after the service dies, so unsuspension
at the end of downtime is not guaranteed if the service is stopped.

Saved policies survive Wi-Fi loss and service restarts. A reboot/force-stop requires
manual restart; OEM battery management can still interrupt execution. Ordinary
installs remain user-controlled. No unsupported claim of tamper resistance.

MediaProjection system approval cannot be replaced by pairing. Android protected
windows may be black; OS revocation ends capture. Orientation changes resize the
existing display surface rather than reuse the one-shot token. Hardware testing
of these lifecycle paths remains required.

Sources: [MediaProjection](https://developer.android.com/media/grow/media-projection),
[NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd),
[UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager),
[DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager),
[foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[local network permission](https://developer.android.com/privacy-and-security/local-network-permission).
