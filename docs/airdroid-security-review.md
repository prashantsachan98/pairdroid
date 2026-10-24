# AirDroid bundle: limited static security review

Reviewed 2026-10-24 using REA 6.7.0, its verified JADX headless 0.7.1 provider,
and Android SDK 36.0.0 `aapt2` and `apksigner`.

The supplied artifact is **AirDroid Parental Control, the controller app**, not
AirDroid Kids. This is supported by its package, application label, archive
metadata and `ParentSplashActivity` launcher. It does not establish how a separate
child app obtains or maintains capture authorization.

## Scope and limits

This review covers metadata, declared permissions, signing integrity, exported
components, network-security configuration and packaged dependency indicators.
The app was not installed or executed. No accounts, devices or backend services
were accessed. No proprietary source, protection mechanism, capture-consent
bypass or hidden-monitoring implementation was reproduced or investigated.

Static configuration is not runtime behavior. A permission declaration does not
prove that permission is granted or exercised. An exported component is not by
itself an exploitable vulnerability, and a packaged SDK is not proof of actual
transmission or collection. No confirmed exploitable vulnerability is established
by this limited review.

## Artifact identity

| Field | Observation |
| --- | --- |
| Supplied format | APKMirror `.apkm` ZIP with one base APK and two ABI splits |
| Package | `com.sand.airdroidkidp` |
| Version | `2.8.1.0`, code `20801000` |
| Minimum / target SDK | 26 / 36 |
| Architectures | `arm64-v8a`, `armeabi-v7a` |
| REA parsed inventory | 40,548 classes; 6,039 resources |
| Declared permissions | 31; REA and AAPT inventories agree |
| Android components | 115 activities, 27 services, 18 receivers, 4 providers |
| Exported components | 12; 7 have no manifest permission guard |
| Native libraries | 9 per ABI, 18 split entries total |

Bundle SHA-256:
`a7a0c16a93841bb620ab3ce2c2ef6950b0cc10200add952de391b247e5f5cc49`

Base APK SHA-256:
`661bf8f19f1f4d7e77f85542169723617ccecea53bf6c641c00e8852e45c546b`

The archive's human-readable publisher/title fields were treated as untrusted
metadata and cross-checked against the compiled APK. No independent comparison
with an official-store signing-certificate baseline was performed.

Evidence: [bundle inventory](evidence/airdroid-2.8.1.0/bundle-inventory.json),
[REA result](evidence/airdroid-2.8.1.0/rea-package.json),
[cross-checks](evidence/airdroid-2.8.1.0/cross-checks.json).

## Findings and review priorities

### 1. Cleartext is blocked by default, with broad exceptions

The packaged network security configuration sets
`base-config cleartextTrafficPermitted=false`. A separate domain configuration
allows cleartext for seven entries, including subdomains: `airdroid.com`,
`amap.com`, `baidu.com`, `4399.com`, `iqiyi.com`, and two private IPv4 addresses.
This is a configuration exception to review, not proof that sensitive information
is actually sent over HTTP. The private addresses also merit checking for
unintended development configuration in the shipped build.

Recommended review: justify each exception, remove unused entries, and verify
sensitive production requests use authenticated TLS. No endpoint was contacted.

The same resource contains a user-installed CA trust override under
`debug-overrides`. That is not evidence that a non-debuggable release trusts user
CAs. The manifest does not explicitly enable debugging; its Android default is
false. Network security configuration can override manifest cleartext settings.
See [Android's network-security documentation](https://developer.android.com/privacy-and-security/security-config).

Evidence: [decoded network configuration](evidence/airdroid-2.8.1.0/network-security-config.txt),
[application attributes](evidence/airdroid-2.8.1.0/manifest-audit.json).

### 2. Exported interfaces require authorization review

Twelve components are explicitly exported. Five declare a permission guard; seven
have no manifest-level permission guard. The latter include the expected launcher,
five other activities and an OEM-push receiver. Five of the exported activities
accept browsable intents, including billing, feedback, routing and authentication
integration entry points.

This identifies attack surface, not a demonstrated authorization failure. Runtime
caller validation, URL allowlists, session checks and intent handling were not
examined. Recommended review: ensure only intended public components are exported,
and verify that externally supplied inputs cannot bypass application authorization.
No component was invoked and no exploit input was developed.

All four providers are non-exported in this manifest. Android documents the
security role of [component export declarations](https://developer.android.com/privacy-and-security/risks/android-exported).

Evidence: complete component and permission inventory in
[manifest-audit.json](evidence/airdroid-2.8.1.0/manifest-audit.json).

### 3. Privacy-sensitive permissions and SDK packaging

Declared capabilities include camera, microphone, fine/coarse location, selected
media access, overlays, notification-policy access and full-screen intents.
Legacy storage grants are restricted by maximum SDK versions. The manifest also
declares advertising-services identifier/attribution permissions.

Dependency metadata includes Firebase Analytics 22.1.0, Firebase Messaging and
Crashlytics artifacts, Google Play Services measurement/auth/maps/location
artifacts, Facebook integration, and an OEM-push integration. Messaging auto-init
is declared enabled; Facebook advertiser-ID collection is declared disabled.
These mixed settings must not be reduced to a claim that all SDKs actively track
users. No runtime data-flow or network-traffic observation was performed.

Recommended review: reconcile each capability with the parent-app feature it
supports, verify collection/consent settings and disclosures, and produce a full
resolved SBOM before drawing dependency vulnerability conclusions. This app's
packaged cloud-related components should not be adopted as a model for PairDroid's
local-only requirement.

Native filenames indicate media/networking, storage, barcode and image-processing
components, including `libjingle_peerconnection_so.so`, `libmmkv.so`, `libzbar.so`
and `libyuv.so`. Filenames alone do not establish exact versions, reachability,
license compliance, capture APIs or transport design.

Evidence: [dependency metadata](evidence/airdroid-2.8.1.0/dependency-metadata.json),
[native inventory](evidence/airdroid-2.8.1.0/native-libraries.json),
[permissions and collection flags](evidence/airdroid-2.8.1.0/manifest-audit.json).

### 4. Signatures verify; legacy signing-key strength merits review

All three APKs pass `apksigner verify` and share the same signer certificate.
The base APK verifies using v2/v3 schemes and has a verified source stamp.
The signer subject is `OU=Sand Studio`; its reported public key is RSA-1024.
Matching signatures establish integrity under that key, not an independent proof
of publisher identity or application safety.

RSA-1024 is a legacy key size relative to modern signature-generation guidance.
For comparison, [NIST SP 800-131A Rev. 2](https://csrc.nist.gov/pubs/sp/800/131/a/r2/final)
distinguishes legacy verification from generation with stronger RSA keys. This
observation warrants publisher key-management review; it is not evidence of a
compromised private key or a practical signature-forgery attack.

Signer certificate SHA-256:
`5f718936b3e83b442dbe4b50ee6e0b0c8e7fea851fb14ce644614f1ba070ce63`

Evidence: [all-split signature summary](evidence/airdroid-2.8.1.0/signature-summary.json)
and the corresponding `*-signature.txt` files in that directory. REA itself did
not verify signatures; this part was independently performed with `apksigner`.

### 5. Positive configuration observations

`allowBackup=false` is explicit. `debuggable` is not explicitly enabled. All
providers are non-exported, the default network policy disallows cleartext, and
split signing identities match. These are useful configuration observations,
not a complete assurance of storage or network security. Android describes the
relevant [application attributes](https://developer.android.com/guide/topics/manifest/application-element).

## Reproducibility

Extracted APKs are under ignored `.tools/airdroid-2.8.1.0/`; the original download
is unchanged. No proprietary APK or decompiled implementation was added to project
source. The report retains metadata and analysis evidence only.

The REA invocation used the installed Node 22 runtime and official REA launcher:

```sh
REA_JADX_MCP_JAR="$PWD/.tools/jadx-headless-mcp-0.7.1-all.jar" \
python3 scripts/rea_probe.py \
  --node /home/vltp008/.npm/_npx/748400177f45359f/node_modules/node/bin/node \
  --launcher /home/vltp008/.npm/_npx/748400177f45359f/node_modules/rea-agents/scripts/rea.mjs \
  --apk .tools/airdroid-2.8.1.0/base.apk \
  --output docs/evidence/airdroid-2.8.1.0/rea-package.json
```

Supporting read-only checks used `apksigner verify --verbose --print-certs`,
`aapt2 dump badging`, and `aapt2 dump xmltree` for the manifest and network security
resource. Automated cross-checks confirmed all split signers match and that REA's
permission inventory exactly matches the AAPT-decoded manifest. Runtime behavior,
backend security, precise dependency versions for every component, and actual
collection remain unverified.
