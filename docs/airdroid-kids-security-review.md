# AirDroid Kids: limited static security review

Reviewed 2026-10-24. The supplied file `/home/vltp008/Downloads/AirDroid kid` is an
APK identifying itself as **AirDroid Kids 1.8.5.0** (`com.sand.airdroidkids`).
REA/JADX inspection and independent Android SDK metadata/signature checks completed.

This review covers artifact identity, permissions, component exposure, signatures,
network security configuration and packaged dependency indicators. No installation,
execution, account access, backend requests, capture implementation tracing,
consent-bypass investigation or reproduction of hidden monitoring was performed.
It establishes neither runtime behavior nor a confirmed exploitable vulnerability.

## Identity and evidence

| Field | Observation |
| --- | --- |
| Package | `com.sand.airdroidkids` |
| Application label | `AirDroid Kids` |
| Version | `1.8.5.0`, code `10805000` |
| File size | 87,203,825 bytes |
| Minimum / target SDK | 26 / 36 |
| REA class / resource count | 27,206 / 4,011 |
| Permission declarations | 50 unique names; REA and AAPT inventories agree |
| Components | 46 activities, 1 activity alias, 38 services, 20 receivers, 5 providers |
| Exported components | 15; 7 declare permission guards and 8 do not |
| Native-library inventory | 18 entries across two ABIs |

Artifact SHA-256:
`344b8b537e46e3e8fb3238604bce51ccd8e11e8f94e56cd8bacd8b5ceccf4270`

The extensionless original was not modified. REA's first request against that
path returned an error. Inspection then succeeded on a byte-identical working
copy named `.tools/airdroid-kids-supplied/supplied.apk`; its SHA-256 was verified
against the original. This establishes successful analysis of the same bytes,
without attributing an unverified cause to the initial error.

Tool versions: REA 6.7.0, its SHA-256-verified JADX headless 0.7.1 provider, Node
22.23.4, Java 17, Android SDK build tools 36.0.0. REA's provider limitations remain
in the saved response. Signature verification was performed by `apksigner`, not
by REA.

Evidence: [artifact identity](evidence/airdroid-kids-supplied/artifact.json),
[REA response](evidence/airdroid-kids-supplied/rea-package.json),
[AAPT badging](evidence/airdroid-kids-supplied/badging.txt),
[independent cross-checks](evidence/airdroid-kids-supplied/cross-checks.json).

## Security and privacy observations

### Broad privacy-sensitive capability declarations

The manifest declares access categories covering calls/call logs, contacts, SMS,
location including background location, camera, microphone, usage statistics,
installed-package visibility, account information, media/storage and advertising
identifiers. It also declares special-access and system-service-related capabilities.

These are declarations, not proof of grants or collection. Some accesses require
runtime or special user authorization; declaring a system-protected permission
does not itself grant it. SDK ranges and OS restrictions also matter. This static
review cannot conclude what data the app actually reads, retains or transmits.

Recommended privacy review: map each sensitive capability to a disclosed feature,
check least privilege and actual grant states, and verify retention/deletion and
authorization behavior in a separately consented runtime assessment. No private
messages, call records or other device data were accessed here.

Evidence: [permission inventory](evidence/airdroid-kids-supplied/manifest-audit.json).

### Exported interfaces: distinguish exposure from missing authorization

Fifteen components are explicitly exported. Eight have no manifest permission
guard; seven declare a guard. Lack of a manifest guard does not rule out runtime
authorization, and export alone does not demonstrate an exploit.

One exported provider declares a custom log-read permission whose protection level
is `signature`. The other four providers are not exported. The log provider should
therefore **not** be reported as publicly readable on manifest evidence alone.
Its read/write behavior, URI handling and runtime implementation were not tested.
Android explains [signature-level permissions](https://developer.android.com/guide/topics/manifest/permission-element)
and [component exposure](https://developer.android.com/privacy-and-security/risks/android-exported).

Recommended review: verify public activities/receivers authenticate relevant
requests, and confirm that any sensitive provider interface enforces its intended
access policy. No component invocations or exploit inputs were attempted.

Evidence: [components, permission guards and custom permission declarations](evidence/airdroid-kids-supplied/manifest-audit.json).

### Network configuration: cleartext exceptions merit review

The base network policy sets `cleartextTrafficPermitted=false`. A domain-specific
configuration allows cleartext for seven entries, including subdomains: five
public domains and two private IPv4 addresses. This is the same exception set
observed in the supplied controller bundle; the decoded resource retains the
exact entries.

This configuration permits exceptions in networking stacks that honor Android's
network-security configuration. It does not prove that any particular request is
unencrypted, or establish behavior of every native networking library. Sensitive
runtime flows were not observed.

The resource also contains user-CA trust under `debug-overrides`. The manifest
does not explicitly enable `debuggable` (Android's default is false), so those
debug-only overrides should not be treated as release user-CA trust. See
[Android network security configuration](https://developer.android.com/privacy-and-security/security-config).

Recommended review: justify each cleartext exception, remove unused development
entries, and verify sensitive production traffic is authenticated and encrypted.
No listed endpoint was contacted.

Evidence: [decoded configuration](evidence/airdroid-kids-supplied/network-security-config.txt).

### Signing integrity and publisher identity are separate questions

`apksigner` verifies the supplied APK with v2/v3 signatures. Its signer certificate
SHA-256 matches all three APKs in the previously supplied controller bundle:

`5f718936b3e83b442dbe4b50ee6e0b0c8e7fea851fb14ce644614f1ba070ce63`

The certificate reports `OU=Sand Studio` and RSA-1024. Matching signatures establish
integrity under the same signing identity, not independent proof of publisher
provenance, safety or compatibility between these app versions. No official-store
signer baseline comparison was performed. No verified source stamp is reported
for this APK; that alone is not evidence of tampering.

The legacy RSA key-size observation is the same key-management review item as in
the [controller report](airdroid-security-review.md). It is not a demonstrated key
compromise or signature-forgery attack.

Evidence: [signature verification](evidence/airdroid-kids-supplied/signature.txt).

### Dependency packaging is not proof of active tracking

Packaged metadata includes Firebase Analytics 22.1.0, Firebase/Google messaging and
measurement-related components, Google Play Services libraries and AndroidX.
Native filenames indicate networking/media, image processing, storage and machine
learning components. Exact versions and reachable runtime functionality cannot be
established for every binary from filenames alone.

Library presence does not establish initialization, consent behavior, transmission,
retention, license compliance or vulnerability status. No full SBOM or dependency
vulnerability verdict is claimed. A runtime privacy audit would need separate,
authorized observations.

Evidence: [dependency metadata](evidence/airdroid-kids-supplied/dependency-metadata.json),
[native-library inventory](evidence/airdroid-kids-supplied/native-libraries.json).

### Positive manifest observations

`allowBackup=false` is explicit; debugging is not explicitly enabled. The default
network configuration blocks cleartext. Most providers are not exported, and the
single exported provider declares a signature-level guard. These are configuration
observations, not comprehensive assurances of storage or network security.

## Comparison with the controller bundle

| Observation | Controller supplied earlier | Kids supplied now |
| --- | --- | --- |
| Package | `com.sand.airdroidkidp` | `com.sand.airdroidkids` |
| Version | 2.8.1.0 | 1.8.5.0 |
| Declared permissions | 31 | 50 |
| Components | 164 | 110 |
| Exported components | 12 | 15 |
| Exported without manifest permission | 7 | 8 |
| Signer certificate | Same SHA-256 | Same SHA-256 |
| Network exceptions | Seven entries | Same seven entries |

These counts compare metadata, not security scores. The different version numbers
and roles do not establish protocol compatibility or which capture APIs either
app executes.

## Reproduce the bounded analysis

With the byte-identical `.apk`-named working copy present:

```sh
REA_JADX_MCP_JAR="$PWD/.tools/jadx-headless-mcp-0.7.1-all.jar" \
python3 scripts/rea_probe.py \
  --node /home/vltp008/.npm/_npx/748400177f45359f/node_modules/node/bin/node \
  --launcher /home/vltp008/.npm/_npx/748400177f45359f/node_modules/rea-agents/scripts/rea.mjs \
  --apk .tools/airdroid-kids-supplied/supplied.apk \
  --output docs/evidence/airdroid-kids-supplied/rea-package.json
```

Independent checks used `apksigner verify --verbose --print-certs`, `aapt2 dump
badging`, and `aapt2 dump xmltree` for the manifest and network configuration.
Artifact hashes, permission-set equality, package identity and signer equality
were cross-checked. The working APK and raw inspection files remain under ignored
`.tools/`; no proprietary implementation was copied into PairDroid source.
