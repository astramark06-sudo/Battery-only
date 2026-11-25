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

## Batch 9 post-upgrade build evidence — 2026-11-25

Source `659ce163beb3e318911f4e8025e628ca5e8684fe`, Android CI 45663973732, build artifact 15045533863, ZIP SHA-256 `968cae9ec08b684a3e5b9e67104883e5a5c613072f2b7dec3948f688a8612f35`. OSV scan at 2026-11-25T17:24:53.512382+00:00. Resolved KGP 2.4.21, AGP 9.2.1, Gradle 9.4.1, app stdlib 2.4.21. 123 unique coordinates, 6 matched components and 10 distinct IDs, all buildscript. 51 earlier IDs no longer returned, 9 retained, 1 newly returned (Commons Lang). Target GHSA-r937-wjx7-w2jp absent. No runtime/test matches. This demonstrates fewer matches, not comprehensive exploitability or embedded-dependency assurance.

| Build-only coordinate | Remaining IDs | Candidate version |
|---|---|---|
| com.google.guava:guava:33.3.1-jre | GHSA-xxph-c9ww-hj94 | 33.7.2-jre |
| org.apache.commons:commons-lang3:3.16.0 | GHSA-j288-q9x7-2f5v | 3.18.0 |
| org.bitbucket.b_c:jose4j:0.9.5 | GHSA-3677-xxcr-wjqv | 0.9.6 |
| org.bouncycastle:bcpkix-jdk18on:1.79 | GHSA-wg6q-6289-32hp | 1.87 |
| org.bouncycastle:bcprov-jdk18on:1.79 | GHSA-574f-3g2m-x479, GHSA-9pwp-9qqc-pr26, GHSA-c3fc-8qff-9hwx, GHSA-p93r-85wp-75v3, GHSA-qp49-qgx5-5m26 | 1.87 |
| org.jdom:jdom2:2.0.6 | GHSA-2363-cqg2-863c | 2.0.6.1 |

Remediation references: [Guava advisory](https://github.com/advisories/GHSA-xxph-c9ww-hj94) and [published artifact](https://central.sonatype.com/artifact/com.google.guava/guava/33.7.2-jre); [Commons Lang advisory](https://github.com/advisories/GHSA-j288-q9x7-2f5v); [jose4j advisory](https://github.com/advisories/GHSA-3677-xxcr-wjqv); [JDOM advisory](https://github.com/advisories/GHSA-2363-cqg2-863c); [BC 1.85 security release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-85/) and [BC 1.87 security release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-87/). Guava vendor page unavailable during review; fixed version corroborated by CVE-2026-102554 records and Maven Central. BC modules bcprov/bcpkix/bcutil aligned together to the released 1.87 to avoid mismatched crypto dependencies. These are candidate buildscript classpath overrides, not app dependencies or proof of AGP compatibility; selected-version gate plus build, tests, signing and fresh OSV scan required. Do not assume embedded Gradle/SDK libraries change with these overrides.

Batch 9 build/unit/lint passed, instrumentation pending at time of preparation. Batch 10 remains detached until CI gate.


## Batch 10 verified result — 2026-11-25

Source `b1811ba9482f741b884a4a706324a7d8ecdede95`, [Android run 45665066410](https://github.com/astramark06-sudo/Battery-only/actions/runs/45665066410), build artifact 15046348898 (SHA-256 `1df0e6d595955a34f957c1d3326959472df5994d295e2807c3fdab1173abcf95`). Report timestamp 2026-11-25T17:31:01.001472+00:00. Both archive digest and CRC independently checked. 123 distinct resolved public Maven coordinates, **0 matched components, 0 distinct advisory IDs**; report status, runtime gate and release review are all `no_known_matches`. Root buildscript 112 coordinates, app buildscript 0, app runtime 2, JVM test runtime 7, Android test runtime 10; overlapping scopes are not summed. All seven patched buildscript selections (Guava 33.7.2-jre, Commons Lang 3.18.0, jose4j 0.9.6, bcprov/bcpkix/bcutil 1.87, JDOM2 2.0.6.1) appear in the root buildscript scope, not app/test scopes. Kotlin 2.4.21, AGP 9.2.1, Gradle 9.4.1 selected. Buildscript Kotlin stdlib 2.3.0 differs from app stdlib 2.4.21; no assertion that all embedded toolchain Kotlin versions match. No remaining matches in this exact scan, compared with 60 distinct IDs in batch 8 and 10 in batch 9. No universal vulnerability-free or reproducible-build claim. Repeat on changed source or dependencies; lock/verification metadata, embedded/native/SDK coverage and runner trust remain documented limitations. See [CURRENT_ACCEPTANCE.md](CURRENT_ACCEPTANCE.md).

## Shared permission policy follow-up — verified 2026-11-25

Source `76009d5b8169a63a955b800e28c586efe6af9be0`, Android run 45669685429, artifact 15048918141 (ZIP SHA-256 `07c8467c46e39245573840e89edfd54f94f897aa2b8574e18f28548c349404d0`). Scan 2026-11-25T17:57:07.618748+00:00, 123 distinct coordinates, zero known matches in all scopes; report status, runtime gate and release review no_known_matches. Exact dependency inventory bytes equal batch 10; no new dependency or app source changes. Shared permission check runs in build CI and offline release policy. Coverage limitations unchanged; see CURRENT_ACCEPTANCE.md.
