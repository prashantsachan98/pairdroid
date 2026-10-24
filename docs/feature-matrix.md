# Feature status

“Implemented” means actual source paths exist; runtime validation is tracked
separately in [testing](testing.md). This is an engineering prototype.

| Area | Status | Boundaries |
| --- | --- | --- |
| Separate Parent/Child apps | Implemented | Single Flutter project, Android flavors |
| NSD discovery | Implemented; physical test pending | IPv4 interface filtering, manual fallback |
| Encrypted device pairing | Implemented | Full certificate comparison + six-digit code + child approval; one parent per child |
| QR pairing | Not implemented | Short-code workflow is available |
| Private command authorization | Implemented | Mutual TLS certificate identity checked on every command |
| Status, battery, charging | Implemented | Live heartbeat in open device page |
| Reconnect/IP change | Partial | Pinned retry, NSD endpoint updates, manual edit; OEM/physical tests pending |
| Unpair and identity rotation | Implemented | Revocation before policy cleanup; rotation requires removing all pairings |
| Installed app list/icons | Implemented | Launcher-visible apps only, capped at 500 |
| Daily/weekly usage charts | Implemented | Android UsageEvents estimates; permission/retention limitations |
| App limits/downtime/allowlist | Implemented | Child-local time, canonical persistent policy, ~30s evaluation |
| Offline policy behavior | Partial | Saved state survives; evaluation needs visible service; OS suspension can persist independently |
| Enforceable blocking | Conditional, unverified | Legitimate owner/profile-owner DPM only; ordinary installs advisory |
| Usage/safety alerts | Partial | Child reminders and parent-pulled audit events; no parent background alert subscriber |
| Live screen sharing | Initial implementation; basic emulator flow passed | Child/system consent, frame delivery and Parent Stop tested on emulator; bounded JPEG pull; physical lifecycle matrix pending |
| Hardware codec / low-latency video | Not implemented | No WebRTC or MediaCodec; no unmeasured latency claim |
| Capture orientation adaptation | Implemented; hardware test pending | Existing virtual display resized, no token reuse |
| Permission onboarding/transparency | Implemented | Native permission/Settings paths, Child status and management log |
| Reboot/force-stop recovery | Limited by design | Explicit service restart; no boot persistence |
| Website restrictions | Not implemented | Would require supported VPN or managed-device mechanisms |
| Location | Not implemented | Separate permission/consent design needed |
| Camera/audio/private messages | Excluded | No covert or ambient monitoring |
| Multiple-parent/family accounts | Not implemented | No cloud accounts |

Open-source files, protocol, architecture, setup, security model, Android permission
matrix, REA report, tests, and development APK build commands are included. A
production release, signing configuration, comprehensive license notice export,
physical-device acceptance, security audit, and performance validation remain.
