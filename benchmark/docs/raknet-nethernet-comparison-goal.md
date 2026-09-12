# Live RakNet versus proposed NetherNet performance goal

Created: 2026-09-07. Status: active; repeated comparison is running.

Compare the existing live RakNet transport with the WebRTC/NetherNet transport
on `teamziax/NetworkCompatible` branch `nxs-dev`, using this fork's established
benchmark workload model. Exclude all RakNet runtime improvements developed
as a result of the earlier benchmark. Here `nxs-dev` identifies the candidate
source branch, not a benchmark host or deployment environment.

User scope extension: review the wider Warden Signalling work, especially
stateless connection handoff, its lifecycle/failure behavior and its relationship
to transport performance. The source-backed
[handoff review](warden-stateless-handoff-review.md) records current implementation,
public discovery, historical native evidence, compatibility limits and the newer
contract/fleet proposals. Keep Warden answer/admission latency separate from the
established transport comparison.

## Source and artifact selection

| Input | Initial evidence | Required treatment |
| --- | --- | --- |
| Benchmark tooling | `ziaxzulu/Network` `codex/cloudburst-raknet-benchmarks` at `082dcc803d5ac000e2fe3e92e1342585fb377fe5`; original benchmark branch at `aa3055bd4c6b3880913cd74911cba50ec4f1cea3` | Reuse workloads, planners, and result validation independently of the improved RakNet implementation. |
| Proposed transport | `teamziax/NetworkCompatible` `nxs-dev` at `86b396686039c7da4e80a162d280700ca15af79b`, fetched from GitHub | Exercise `transport-nethernet` with the actual platform native library. Record the resolved Java/JNI/libdatachannel/libjuice pins and artifact hashes. |
| Live RakNet | The locally available production dependency catalog references `org.cloudburstmc.netty:netty-transport-raknet:1.1.0.CR1-20260820.174333-6` | This is a discovery lead, not deployed-runtime proof. Refresh production provenance, resolve the immutable artifact and source, and verify the deployed version before labelling results live. |
| Fork checkout | `6eb05d32e7f95741aacdb69c9be6c778de737bea` | An older unmodified source candidate, not automatically the live baseline. |
| Current upstream develop | `508ed83c8a1fe1ce4287ad6d192c9d38bb0d2ffd`, read from GitHub | Another unmodified source candidate; do not silently substitute latest upstream for the deployed version. |

The benchmark branch includes the improved runtime through its ancestry.
Checking out that whole branch, or disabling one controller option, does not
establish the requested baseline. Keep the live artifact unchanged and adapt
the harness to its public API. Missing internal counters must be identified as
unavailable rather than populated with zero or supplied by optimization patches.

## Execution work

1. Verify the live artifact and its provenance; hash both transport artifacts
   and all relevant native dependencies. Pin the benchmark independently.
2. Reuse the workload model through transport adapters, keeping offered bytes,
   message sizes, pacing, connection counts, and measurement boundaries equal.
   Validate real delivery and ordering through native NetherNet before load
   runs. Fake/native-admission fixtures alone are not performance evidence.
3. Compare RakNet reliable ordered channel 0 with NetherNet's corresponding
   reliable ordered application path. Record any differences in framing,
   batching, encryption, queue limits, and socket defaults. Keep signalling,
   ICE, DTLS, and connection establishment timing separate from established
   channel measurements. If handshake measurements are added, report their
   start/end events explicitly.
4. Run a short smoke for both transports, followed by repeated matched pilot
   runs. Use at least three measured repetitions with equal warmup and run
   duration, alternating transport order to reduce time and thermal bias.
5. Run external latency, jitter, loss, and blackhole cases using the same
   topology for both transports; native UDP must traverse the impairment path.
   Preserve healthy and impaired client cohorts and timestamped qdisc evidence.
6. Produce raw results, a reproducible command/manifest package, and a concise
   comparison of tradeoffs with uncertainty, failures, and unavailable metrics.

## Workload and measurement contract

Start from `baseline-matrix.md`, `baseline-status.md`, and
`production-usage-evidence.md` at the pinned benchmark revision.

| Workload | Coverage |
| --- | --- |
| Capacity and overload | One-client rate curves; 64, 256, 512, 1200, 1340, 1400 byte payloads and split-heavy payloads. Distinguish deployed limiter settings from library defaults. |
| Fanout | Representative 100-client fanout at 5 Mbps/client; immediate 256-byte traffic at 1 Mbps/client; higher counts only with recorded host headroom. |
| Batched traffic | Equivalent synthetic batches at 10, 20, and 50 ms cadence. |
| Resource packs | 8 KiB and 256 KiB messages paced at 200 ms. Record any real message-size limit instead of silently reducing payloads. |
| Adverse paths | Matched clean, latency/jitter/loss, mixed healthy/impaired, and disappearance cases; include blackhole and recovery where the harness supports event-aligned observation. |

Measure useful application throughput, delivery completeness, message rate,
probe RTT p50/p95/p99, per-client throughput and Jain fairness, CPU, RSS/native
memory and GC, queued bytes where observable, wire bytes/datagrams, retry cost,
disconnects, and recovery time. Define each metric consistently. Do not equate
RakNet ACK/NACK counters with SCTP counters or claim one-way latency from an
unsynchronised probe. Keep unavailable counters explicit.

Record JVM, OS/kernel, CPU allocation, thread counts, heap settings, native
versions, MTU, topology, impairment direction, and concurrent host load.
Separate application-byte goodput from encrypted/framed wire cost. Equalize
resource budgets and application semantics while retaining each transport's
real protocol costs.

## Completion and evidence limits

The comparison is complete when both pinned transports have repeatable matched
results, provenance excludes benchmark-induced RakNet runtime changes, raw
artifacts and commands support the tables, and conclusions explain stability
and topology limits. Identify any matrix rows that could not execute and why;
do not silently drop failures from aggregates.

Loopback or namespace results may support a development transport comparison.
Production capacity or NIC line-rate claims require the existing separate-host
prerequisites and validation/promotion gates. Historical optimized-RakNet
results are context only. Synthetic transport results do not establish stock
Minecraft client compatibility, gameplay quality, or production deployment.

## Execution progress

The production dependency catalog was refreshed at revision
`db97168467838187c80d469be21d78d6b5e39113` and still pins the discovered RakNet
artifact. All 77 Java files in its published source JAR match unmodified
upstream `508ed83c8a1fe1ce4287ad6d192c9d38bb0d2ffd` byte for byte. The binary
SHA-256 is `24b72dd6e01e6707b91d759f7bf08bf0d3b166e392119632ce8430d8899d9971`.
Deployed-process readback remains unavailable; the running comparison labels
this as the production-pinned baseline.

The standalone [comparison harness](../comparison/README.md) now uses that
unchanged JAR and the candidate's actual NXS admitted server path with native
NetherNet clients. The JNI source chain was verified, its existing native tests
passed, and a separate Release library was built and verified as loaded by the
benchmark. Shared payload, measurement, and impairment logic keep both transports
on the same four-CPU allocation and Netty/JVM versions.

Smoke/preflight evidence covers two clients, 100 clients, 256 KiB messages,
kernel loss, and a three-second blackout/recovery. CPU-window accounting was
corrected after the initial saturation smoke. Only the subsequent frozen
campaign is eligible for the repeated comparison; the earlier smoke results
are not the comparison baseline.

`benchmark/comparison/artifacts/comparison-20260907/plan.json` defines the
258-row campaign: 43 workloads, two transports, three repetitions, alternating
transport order, five-second warmup and ten-second measurement. Raw results,
artifact hashes, commands, failed rows, qdisc evidence, and the 200 ms timeline
are retained there. `completed.jsonl` is the completed-row record and
`complete.json` is written only when the full plan has executed.

The first campaign runner exited with status 143 after 107 recorded rows. Guarded
resume preserved the unrecorded attempt and continued the exact plan with unchanged
Java/runtime/native hashes. Later batches can checkpoint by row count and elapsed
invocation time. The original failures and all resumed-invocation metadata remain
in the artifact package.

Completed on 2026-09-07: all 258 full-matrix rows and 48 small-cohort controls
executed against the same frozen binaries. Strict summaries and independent
audits reconcile the complete plans, raw hashes, per-client active-window
totals, CPU accounting and aggregate medians. Post-drain snapshot skew is
quantified and normalized from retained peer counters without rewriting raw
results. Failures, unstable tails and missing measurements remain visible.

The [final comparison](raknet-nethernet-comparison-20260907.md) covers throughput,
latency, CPU/RSS, kernel traffic, fairness, loss, blackout recovery and the
disappearance API observations. It links all raw evidence, the PNG/SVG figures,
reproduction commands and a checksum-verified archive. The
[validation report](raknet-nethernet-validation-20260907.md) records remaining
evidence limits, and the [Warden review](warden-stateless-handoff-review.md)
explains stateless admission, lifecycle behavior and wider proposals. No
production capacity, stable global p99, deployed-process or stock-client
gameplay claim is made.
