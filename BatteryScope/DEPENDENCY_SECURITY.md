# Dependency security evidence

## Verified baseline — 2026-11-25, batch 8

Source `4ca475ef51eb1deea1d715982ffc50f085455082`, [Android CI 45662476887](https://github.com/astramark06-sudo/Battery-only/actions/runs/45662476887), build artifact 15046177383. ZIP SHA-256 `d57404de4b21373b71131b0ee861860e5b901a2ec628eb757492b1ba80889554`. Live OSV querybatch report `osv-debug.json` scanned at 2026-11-25T17:17:16.896909+00:00. 157 unique selected Maven coordinates, 15 matched components, 60 distinct advisory IDs.

| Scope | Resolved components | Matched components |
|---|---:|---:|
| debugRuntimeClasspath | 2 | 0 |
| debugUnitTestRuntimeClasspath | 7 | 0 |
| debugAndroidTestRuntimeClasspath | 10 | 0 |
| rootBuildscriptClasspath | 145 | 15 |
| appBuildscriptClasspath | 0 | 0 |

Scopes overlap; the scope counts must not be summed to obtain unique coordinates. All matches were build-time only in this inventory.

| Build component | Advisory matches |
|---|---:|
| Guava 32.0.1-jre | 1 |
| protobuf-java 3.22.3 | 1 |
| commons-io 2.13.0 | 1 |
| netty-codec-http2 4.1.93.Final | 9 |
| netty-codec-http 4.1.93.Final | 19 |
| netty-codec 4.1.93.Final | 3 |
| netty-common 4.1.93.Final | 2 |
| netty-handler-proxy 4.1.93.Final | 1 |
| netty-handler 4.1.93.Final | 7 |
| commons-compress 1.21 | 2 |
| jose4j 0.9.5 | 1 |
| bcpkix-jdk18on 1.77 | 2 |
| bcprov-jdk18on 1.77 | 10 |
| jdom2 2.0.6 | 1 |
| kotlin-gradle-plugin 2.0.21 | 1 |

The individual advisory IDs and scope associations are in the CI artifact. Counts overlap between related packages. This is not exploitability analysis. Batch 9 proposes Kotlin 2.4.21, AGP 9.2.1 and Gradle 9.4.1, but the exact resolved inventory and post-upgrade results must be checked before declaring any finding resolved. Avoid indiscriminately forcing newer transitive dependencies that may break AGP; evaluate compatible upstream versions first.

## Release review procedure

1. Download the exact latest successful source-run artifact and verify its ZIP checksum.
2. Check resolved version inventory, OSV timestamp, completion status and scope coverage. Compare old/new advisory IDs by package and scope; retain new findings.
3. An incomplete scan is a failure, not an empty result. A matched app runtime component blocks the CI gate. Build/test matches require explicit review and a documented compatible remediation or an authorized risk decision before release; successful compilation is not security approval.
4. This is a public Maven advisory-match check, not complete supply-chain integrity verification. SDK/native libraries, embedded/shaded dependencies, unmapped product advisories, runner image and dependencies downloaded by actions are not comprehensively covered. Exact action commit pins and disabled shared Gradle caches reduce exposure but do not prove trusted build inputs.
5. Do not add private history, device identifiers, signing keys or credentials to the advisory inventory or public report. Only public Maven coordinates are submitted.

Current state: batch 8 baseline verified; batch 9 upgrade pending CI when this file was prepared.
